package ir.sabou.core

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.platform.Actor
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.Permission
import ir.sabou.posimport.BankStatementFile
import ir.sabou.posimport.ImportFileException
import ir.sabou.posimport.Sheets
import ir.sabou.treasury.RecordStatementLine
import ir.sabou.treasury.StatementPosting
import ir.sabou.treasury.BankStatement
import ir.sabou.treasury.Direction
import ir.sabou.treasury.ImportBankStatement
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.StatementLine
import ir.sabou.treasury.StatementLineInput
import ir.sabou.treasury.TransferFunds
import ir.sabou.treasury.TreasuryKind
import ir.sabou.treasury.TreasuryMovement

/** A statement file read and checked, before import. */
data class StatementPreview(
    val lines: List<StatementLineInput>,
    val closingBalance: Long?,
    val problems: List<String>,
    /** Lines of the file already imported in an earlier statement of the chosen account; they are left out. */
    val alreadyImported: Int = 0,
) {
    val ready: Boolean get() = lines.isNotEmpty() && problems.isEmpty()
}

/** A book movement with what the journal says about it. */
data class BookItem(val movement: TreasuryMovement, val description: String)

/**
 * A statement against the books (ADR-0035). The statement's closing balance should equal the book balance on its
 * last day, minus what only the books have (cheques not yet cleared, deposits in transit), plus what only the
 * statement has (fees, card settlements not yet recorded). What remains is [unexplained].
 */
data class Reconciliation(
    val statement: BankStatement,
    val matched: Int,
    val statementOnly: List<StatementLine>,
    val bookOnly: List<BookItem>,
    val bookBalance: Long,
) {
    private val statementOnlyNet: Long get() = statementOnly.sumOf { if (it.direction == Direction.RECEIPT) it.amount.rial else -it.amount.rial }
    private val bookOnlyNet: Long get() = bookOnly.sumOf { if (it.movement.direction == Direction.RECEIPT) it.movement.amount.rial else -it.movement.amount.rial }
    /** The book balance as the bank should see it. */
    val adjustedBook: Long get() = bookBalance - bookOnlyNet + statementOnlyNet
    val unexplained: Long? get() = statement.closingBalance?.let { it.rial - adjustedBook }
}

/** How a statement-only line is put into the books, after which it matches itself. */
sealed interface StatementAction {
    data class Pay(val purpose: PaymentPurpose) : StatementAction
    data class Receive(val purpose: ReceiptPurpose) : StatementAction
    /** Money moved between this bank account and another treasury account (e.g. a card terminal's settlement). */
    data class Transfer(val otherAccountId: GlobalId) : StatementAction
}

/** Bank statement import and reconciliation (ADR-0035). */
class BankRec internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun bankAccount(id: GlobalId): ir.sabou.treasury.TreasuryAccount {
        val account = core.treasuryAccounts.byId(id) ?: throw DomainException(DomainError.NotFound("TREASURY_ACCOUNT"))
        val a = actor(Permission.TREASURY_RECONCILE)
        if (!a.canAccess(account.scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(account.scope)))
        if (account.kind != TreasuryKind.BANK) throw DomainException(DomainError.InvalidState("TREASURY_ACCOUNT", "NOT_BANK"))
        return account
    }

    fun preview(file: PosFile, accountId: GlobalId? = null): StatementPreview {
        actor(Permission.TREASURY_RECONCILE)
        val parsed = try { BankStatementFile.parse(Sheets.read(file.name, file.bytes)) } catch (e: ImportFileException) {
            return StatementPreview(emptyList(), null, listOf(e.message ?: "فایل خوانده نشد."))
        }
        val lines = parsed.rows.map { r ->
            StatementLineInput(r.date, r.description, if (r.deposit != null) Direction.RECEIPT else Direction.PAYMENT,
                Money.of(r.deposit ?: r.withdrawal!!), r.balance?.let(Money::of), r.reference)
        }
        val repeated = accountId?.let { id -> lines.size - core.bankStatements.newLines(bankAccount(id).id, lines).size } ?: 0
        return StatementPreview(lines, parsed.closingBalance, parsed.problems, repeated)
    }

    fun import(commandId: GlobalId, accountId: GlobalId, file: PosFile): CommandOutcome {
        val account = bankAccount(accountId)
        val p = preview(file)
        if (!p.ready) throw DomainException(DomainError.InvalidInput("file", p.problems.firstOrNull() ?: "فایل سطری ندارد."))
        return core.bankStatements.import(ImportBankStatement(commandId, account.scope, account.id, file.name, p.lines, p.closingBalance?.let(Money::of)))
    }

    fun statements(accountId: GlobalId): List<BankStatement> =
        core.bankStatementStore.of(bankAccount(accountId).id).map(core.bankStatements::effective).sortedByDescending { it.to }

    fun reconciliation(statementId: GlobalId): Reconciliation {
        val stored = core.bankStatementStore.byId(statementId) ?: throw DomainException(DomainError.NotFound("BANK_STATEMENT"))
        bankAccount(stored.accountId)
        val statement = core.bankStatements.effective(stored)
        val matched = core.bankStatements.matchedMovements(statement.accountId)
        val all = core.treasuryMovements.byAccount(statement.accountId)
        val bookBalance = all.filter { it.date <= statement.to }.sumOf { if (it.direction == Direction.RECEIPT) it.amount.rial else -it.amount.rial }
        // What the books have and no statement has shown yet, up to this statement's end — including items from before
        // it (a cheque issued last month and still not cleared is still outstanding, audit F07). Reconciliation starts
        // with the account's first statement: movements before that are its opening, not outstanding items.
        val start = core.bankStatementStore.of(statement.accountId).minOf { it.from }.plusDays(-ir.sabou.treasury.BankMatching.DAYS)
        val bookOnly = core.bankStatements.matchable(statement.accountId)
            .filter { it.id !in matched && it.date <= statement.to && it.date >= start }
            .sortedBy { it.date }
            .map { BookItem(it, core.journals.byId(it.journalId)?.description.orEmpty()) }
        return Reconciliation(statement, statement.lines.size - statement.unmatched.size, statement.unmatched, bookOnly, bookBalance)
    }

    /**
     * Records a statement-only line in the books (a payment, a receipt or a transfer) and matches it with that very
     * line, in one command (audit F01).
     */
    fun record(commandId: GlobalId, statementId: GlobalId, index: Int, action: StatementAction): CommandOutcome {
        val statement = core.bankStatementStore.byId(statementId) ?: throw DomainException(DomainError.NotFound("BANK_STATEMENT"))
        bankAccount(statement.accountId)
        val posting = when (action) {
            is StatementAction.Pay -> StatementPosting.Pay(action.purpose)
            is StatementAction.Receive -> StatementPosting.Receive(action.purpose)
            is StatementAction.Transfer -> StatementPosting.Transfer(action.otherAccountId)
        }
        return core.bankStatements.recordLine(RecordStatementLine(commandId, statement.scope, statement.id, index, posting))
    }

    /** Unmatched book movements of the statement's account that could be this line (same direction and amount). */
    fun candidates(statementId: GlobalId, index: Int): List<BookItem> {
        val statement = core.bankStatementStore.byId(statementId) ?: throw DomainException(DomainError.NotFound("BANK_STATEMENT"))
        bankAccount(statement.accountId)
        val line = statement.lines.getOrNull(index) ?: return emptyList()
        val matched = core.bankStatements.matchedMovements(statement.accountId)
        return core.bankStatements.matchable(statement.accountId)
            .filter { it.id !in matched && it.direction == line.direction && it.amount == line.amount }
            .sortedBy { kotlin.math.abs(it.date.epochDay - line.date.epochDay) }
            .map { BookItem(it, core.journals.byId(it.journalId)?.description.orEmpty()) }
    }
}
