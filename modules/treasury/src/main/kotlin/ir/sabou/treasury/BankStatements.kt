package ir.sabou.treasury

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission

/**
 * One line of a bank statement (ADR-0035). [direction] is from the account's side: RECEIPT = money in (deposit),
 * PAYMENT = money out (withdrawal). [movementId] is the book movement it was matched with.
 */
data class StatementLine(
    val index: Int,
    val date: BusinessDate,
    val description: String,
    val direction: Direction,
    val amount: Money,
    val balance: Money?,
    val reference: String,
    val movementId: GlobalId? = null,
)

data class StatementLineInput(val date: BusinessDate, val description: String, val direction: Direction, val amount: Money, val balance: Money?, val reference: String) : java.io.Serializable

/** A bank statement imported for one bank account; its lines are matched with the book movements of that account. */
data class BankStatement(
    val id: GlobalId,
    val accountId: GlobalId,
    val scope: Scope,
    val fileName: String,
    val from: BusinessDate,
    val to: BusinessDate,
    /** The statement's own balance after its last line, when the file shows balances. */
    val closingBalance: Money?,
    val lines: List<StatementLine>,
    val importedBy: String,
    val importedAt: Long,
) {
    val unmatched: List<StatementLine> get() = lines.filter { it.movementId == null }
}

interface BankStatementStore {
    fun byId(id: GlobalId): BankStatement?
    fun of(accountId: GlobalId): List<BankStatement>
    fun save(statement: BankStatement)
}

/** Statement line ↔ book movement matching: same direction and amount, nearest date within [DAYS], one to one. */
object BankMatching {
    /** A deposit or cheque may reach the statement a few days after it was booked, or before (card settlements). */
    const val DAYS = 4L

    /** Lines matched by index → movement id; [taken] are movements already matched elsewhere. */
    fun match(lines: List<StatementLine>, movements: List<TreasuryMovement>, taken: Set<GlobalId>): Map<Int, GlobalId> {
        val used = HashSet(taken)
        val out = HashMap<Int, GlobalId>()
        // Exact dates first, then one day apart, and so on: a close match is never stolen by a far one.
        for (distance in 0L..DAYS) {
            for (line in lines.filter { it.movementId == null && it.index !in out }) {
                val m = movements.filter { it.id !in used && it.direction == line.direction && it.amount == line.amount && kotlin.math.abs(it.date.epochDay - line.date.epochDay) == distance }
                    .minByOrNull { it.recordedAtEpochMillis } ?: continue
                out[line.index] = m.id
                used += m.id
            }
        }
        return out
    }
}

/** A statement exported by the bank (Excel or CSV), already read into lines. Nothing is posted. */
@NoDocument
data class ImportBankStatement(
    override val commandId: GlobalId,
    override val scope: Scope,
    val accountId: GlobalId,
    val fileName: String,
    val lines: List<StatementLineInput>,
    val closingBalance: Money?,
) : Command {
    override val requiredPermission get() = Permission.TREASURY_RECONCILE
    // Every field of every line: a retry with a different file must not answer with the first file's result.
    override fun fingerprint() = "$scope|$accountId|$fileName|${closingBalance?.rial}|" +
        lines.joinToString(";") { "${it.date.epochDay}:${it.direction}:${it.amount.rial}:${it.balance?.rial}:${it.reference.length}:${it.reference}:${it.description.length}:${it.description}" }
}

/** How a statement-only line is put into the books (it is then matched with exactly that line). */
sealed interface StatementPosting : java.io.Serializable {
    data class Pay(val purpose: PaymentPurpose) : StatementPosting
    data class Receive(val purpose: ReceiptPurpose) : StatementPosting
    /** Money moved between this bank account and another treasury account (e.g. a card terminal's settlement). */
    data class Transfer(val otherAccountId: GlobalId) : StatementPosting
}

/**
 * Records one statement-only line in the books and matches it with THAT line, in one transaction: the line can
 * never end up matched to another equal line, and once matched it cannot be recorded twice (audit F01).
 */
@NoDocument
data class RecordStatementLine(
    override val commandId: GlobalId,
    override val scope: Scope,
    val statementId: GlobalId,
    val index: Int,
    val posting: StatementPosting,
) : Command {
    override val requiredPermission get() = Permission.TREASURY_RECONCILE
    override val additionalPermissions: Set<Permission> get() = setOf(
        when (posting) {
            is StatementPosting.Pay -> Permission.TREASURY_PAYMENT
            is StatementPosting.Receive -> Permission.TREASURY_RECEIPT
            is StatementPosting.Transfer -> Permission.TREASURY_TRANSFER
        },
    )
    override fun fingerprint() = "$scope|$statementId|$index|$posting"
}

/** Matches a statement line with a book movement by hand, or ([movementId] null) undoes its match. */
@NoDocument
data class MatchStatementLine(
    override val commandId: GlobalId,
    override val scope: Scope,
    val statementId: GlobalId,
    val index: Int,
    val movementId: GlobalId?,
) : Command {
    override val requiredPermission get() = Permission.TREASURY_RECONCILE
    override fun fingerprint() = "$scope|$statementId|$index|$movementId"
}

/** Matches what can be matched now (after the missing receipts and payments were recorded). */
@NoDocument
data class AutoMatchStatement(override val commandId: GlobalId, override val scope: Scope, val statementId: GlobalId) : Command {
    override val requiredPermission get() = Permission.TREASURY_RECONCILE
    override fun fingerprint() = "$scope|$statementId"
}

class BankStatementOperations(
    private val bus: CommandBus,
    private val accounts: TreasuryAccountStore,
    private val movements: MovementStore,
    private val statements: BankStatementStore,
    private val treasury: TreasuryOperations,
) {
    fun import(c: ImportBankStatement): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val account = accounts.byId(cmd.accountId) ?: throw DomainException(DomainError.NotFound("TREASURY_ACCOUNT"))
        ensure(account.scope == cmd.scope) { DomainError.InvalidInput("scope", "محدوده فرمان با حساب یکسان نیست.") }
        ensure(account.kind == TreasuryKind.BANK) { DomainError.InvalidState("TREASURY_ACCOUNT", "NOT_BANK") }
        ensure(cmd.lines.isNotEmpty() && cmd.lines.size <= MAX_LINES) { DomainError.InvalidInput("lines", "صورت‌حساب خالی است یا بیش از حد سطر دارد.") }
        ensure(cmd.lines.none { it.amount.isZero || it.amount.rial < 0 }) { DomainError.InvalidInput("lines", "مبلغ هر سطر باید بیشتر از صفر باشد.") }
        val from = cmd.lines.minOf { it.date }; val to = cmd.lines.maxOf { it.date }
        // Lines already imported (a file that overlaps an earlier one, wholly or in part) are left out, so no bank
        // line is ever there twice; the rest is imported. Nothing new at all: the file was already imported.
        val fresh = newLines(account.id, cmd.lines)
        ensure(fresh.isNotEmpty()) { DomainError.InvalidState("BANK_STATEMENT", "ALREADY_IMPORTED") }
        val lines = fresh.mapIndexed { i, l -> StatementLine(i, l.date, l.description.trim().take(300), l.direction, l.amount, l.balance, l.reference.trim().take(60)) }
        val draft = BankStatement(GlobalId.new(), account.id, account.scope, cmd.fileName.take(120), from, to, cmd.closingBalance, lines, ctx.actor.displayName, ctx.nowEpochMillis)
        val statement = autoMatched(draft)
        statements.save(statement)
        ctx.audit(AuditDraft("BANK_STATEMENT_IMPORT", "BANK_STATEMENT", statement.id.value,
            "account=${account.id};lines=${lines.size};skipped=${cmd.lines.size - lines.size};matched=${lines.size - statement.unmatched.size}"))
        statement.id
    }

    /**
     * The lines of [input] not yet in an earlier statement of [accountId]. Lines are compared by everything the bank
     * says about them (date, direction, amount, running balance, reference) and counted: two genuinely equal
     * transactions of a day stay two, and only as many as were imported before are left out.
     */
    fun newLines(accountId: GlobalId, input: List<StatementLineInput>): List<StatementLineInput> {
        val seen = HashMap<String, Int>()
        statements.of(accountId).forEach { s -> s.lines.forEach { l -> seen.merge(lineKey(l.date, l.direction, l.amount, l.balance, l.reference), 1, Int::plus) } }
        return input.filter { l ->
            val k = lineKey(l.date, l.direction, l.amount, l.balance, l.reference)
            val left = seen[k] ?: 0
            if (left > 0) { seen[k] = left - 1; false } else true
        }
    }

    /** Records a statement-only line ([RecordStatementLine]); returns the treasury document's id. */
    fun recordLine(c: RecordStatementLine): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val statement = effective(statement(cmd.statementId, cmd.scope))
        val line = statement.lines.getOrNull(cmd.index) ?: throw DomainException(DomainError.NotFound("STATEMENT_LINE"))
        ensure(line.movementId == null) { DomainError.InvalidState("STATEMENT_LINE", "ALREADY_MATCHED") }
        val account = accounts.byId(statement.accountId) ?: throw DomainException(DomainError.NotFound("TREASURY_ACCOUNT"))
        val text = line.description.ifBlank { "سطر صورت‌حساب" }.take(200)
        // The treasury document runs inside this command, under an id derived from it (a retry finds it again).
        val inner = GlobalId.parse(java.util.UUID.nameUUIDFromBytes("${cmd.commandId.value}/statement-line".toByteArray()).toString())
        val docId = when (val p = cmd.posting) {
            is StatementPosting.Pay -> {
                ensure(line.direction == Direction.PAYMENT) { DomainError.InvalidInput("action", "این سطر واریز است، نه برداشت.") }
                treasury.payment(RecordPayment(inner, account.scope, account.id, p.purpose, line.amount, line.date, text)).resultId
            }
            is StatementPosting.Receive -> {
                ensure(line.direction == Direction.RECEIPT) { DomainError.InvalidInput("action", "این سطر برداشت است، نه واریز.") }
                treasury.receipt(RecordReceipt(inner, account.scope, account.id, p.purpose, line.amount, line.date, text)).resultId
            }
            is StatementPosting.Transfer -> {
                val other = accounts.byId(p.otherAccountId) ?: throw DomainException(DomainError.NotFound("TREASURY_ACCOUNT"))
                val (from, to) = if (line.direction == Direction.RECEIPT) other to account else account to other
                treasury.transfer(TransferFunds(inner, from.scope, from.id, to.id, line.amount, line.date, text)).resultId
            }
        }
        val movement = movements.byAccount(account.id).single { it.source.id == docId && it.direction == line.direction && it.reversalOf == null }
        statements.save(statement.copy(lines = statement.lines.map { if (it.index == cmd.index) it.copy(movementId = movement.id) else it }))
        ctx.audit(AuditDraft("BANK_STATEMENT_RECORD", "BANK_STATEMENT", statement.id.value, "line=${cmd.index};movement=${movement.id}"))
        docId
    }

    /**
     * [statement] as it stands now: a line whose book movement was reversed since it was matched is unmatched again
     * (audit F05) — the money it stood for is no longer in the books.
     */
    fun effective(statement: BankStatement): BankStatement {
        if (statement.lines.none { it.movementId != null }) return statement
        val valid = matchable(statement.accountId).map { it.id }.toSet()
        return statement.copy(lines = statement.lines.map { l -> if (l.movementId != null && l.movementId !in valid) l.copy(movementId = null) else l })
    }

    fun match(c: MatchStatementLine): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val statement = effective(statement(cmd.statementId, cmd.scope))
        val line = statement.lines.getOrNull(cmd.index) ?: throw DomainException(DomainError.NotFound("STATEMENT_LINE"))
        cmd.movementId?.let { id ->
            val m = movements.byId(id) ?: throw DomainException(DomainError.NotFound("TREASURY_MOVEMENT"))
            ensure(m.accountId == statement.accountId && m.reversalOf == null && movements.reversalOf(m.id) == null) { DomainError.InvalidState("TREASURY_MOVEMENT", "NOT_MATCHABLE") }
            ensure(m.direction == line.direction && m.amount == line.amount) { DomainError.InvalidState("STATEMENT_LINE", "AMOUNT_DIFFERS") }
            ensure(id !in matchedMovements(statement.accountId)) { DomainError.InvalidState("TREASURY_MOVEMENT", "ALREADY_MATCHED") }
        }
        statements.save(statement.copy(lines = statement.lines.map { if (it.index == cmd.index) it.copy(movementId = cmd.movementId) else it }))
        ctx.audit(AuditDraft(if (cmd.movementId == null) "BANK_STATEMENT_UNMATCH" else "BANK_STATEMENT_MATCH", "BANK_STATEMENT", statement.id.value,
            "line=${cmd.index};movement=${cmd.movementId ?: line.movementId}"))
        statement.id
    }

    fun autoMatch(c: AutoMatchStatement): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val statement = effective(statement(cmd.statementId, cmd.scope))
        val next = autoMatched(statement)
        statements.save(next)
        ctx.audit(AuditDraft("BANK_STATEMENT_AUTOMATCH", "BANK_STATEMENT", statement.id.value, "matched=${statement.unmatched.size - next.unmatched.size}"))
        statement.id
    }

    /** Book movements of the account that may appear on a statement: not reversed, not reversals. */
    fun matchable(accountId: GlobalId): List<TreasuryMovement> {
        val all = movements.byAccount(accountId)
        val reversed = all.mapNotNull { it.reversalOf }.toSet()
        return all.filter { it.reversalOf == null && it.id !in reversed }
    }

    fun matchedMovements(accountId: GlobalId): Set<GlobalId> {
        val valid = matchable(accountId).map { it.id }.toSet()
        return statements.of(accountId).flatMap { s -> s.lines.mapNotNull { it.movementId } }.filter { it in valid }.toSet()
    }

    private fun autoMatched(statement: BankStatement): BankStatement {
        val found = BankMatching.match(statement.lines, matchable(statement.accountId), matchedMovements(statement.accountId))
        return statement.copy(lines = statement.lines.map { l -> found[l.index]?.let { l.copy(movementId = it) } ?: l })
    }

    private fun statement(id: GlobalId, scope: Scope): BankStatement {
        val s = statements.byId(id) ?: throw DomainException(DomainError.NotFound("BANK_STATEMENT"))
        ensure(s.scope == scope) { DomainError.InvalidInput("scope", "این صورت‌حساب متعلق به این محدوده نیست.") }
        return s
    }

    private fun lineKey(date: BusinessDate, direction: Direction, amount: Money, balance: Money?, reference: String) =
        "${date.epochDay}|$direction|${amount.rial}|${balance?.rial}|${reference.trim().take(60)}"

    companion object {
        const val MAX_LINES = 5_000
    }
}
