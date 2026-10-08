package ir.sabou.treasury

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.PostingCapability
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission

/**
 * Purposes a treasury user may record directly. Deliberately no supplier, customer, payroll or tax
 * settlement here: those belong to the owning modules (AUD-003).
 */
enum class ReceiptPurpose(val counterAccount: AccountCode) {
    OWNER_CAPITAL(StandardAccounts.CAPITAL),
    OTHER_INCOME(StandardAccounts.OTHER_INCOME),
}

enum class PaymentPurpose(val counterAccount: AccountCode) {
    RENT(StandardAccounts.RENT),
    UTILITIES(StandardAccounts.UTILITIES),
    OTHER_EXPENSE(StandardAccounts.OTHER_EXPENSE),
    OWNER_WITHDRAWAL(StandardAccounts.CAPITAL),
}

data class OpenTreasuryAccount(
    override val commandId: GlobalId,
    override val scope: Scope,
    val name: String,
    val kind: TreasuryKind,
) : Command {
    override val requiredPermission = Permission.TREASURY_ACCOUNT_MANAGE
    override fun fingerprint() = "$scope|$name|$kind"
}

data class RecordReceipt(
    override val commandId: GlobalId,
    override val scope: Scope,
    val accountId: GlobalId,
    val purpose: ReceiptPurpose,
    val amount: Money,
    val date: BusinessDate,
    val description: String,
) : Command {
    override val requiredPermission = Permission.TREASURY_RECEIPT
    override fun fingerprint() = "$scope|$accountId|$purpose|${amount.rial}|${date.epochDay}|$description"
}

data class RecordPayment(
    override val commandId: GlobalId,
    override val scope: Scope,
    val accountId: GlobalId,
    val purpose: PaymentPurpose,
    val amount: Money,
    val date: BusinessDate,
    val description: String,
) : Command {
    override val requiredPermission = Permission.TREASURY_PAYMENT
    override fun fingerprint() = "$scope|$accountId|$purpose|${amount.rial}|${date.epochDay}|$description"
}

/** Moves money between two treasury accounts, including between branches (cash deposit to bank). */
data class TransferFunds(
    override val commandId: GlobalId,
    override val scope: Scope,
    val fromAccountId: GlobalId,
    val toAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val description: String,
) : Command {
    override val requiredPermission = Permission.TREASURY_TRANSFER
    override fun fingerprint() = "$scope|$fromAccountId|$toAccountId|${amount.rial}|${date.epochDay}|$description"
}

/** Physical count of a cash box; the difference is booked to cash over/short. */
data class ReconcileAccount(
    override val commandId: GlobalId,
    override val scope: Scope,
    val accountId: GlobalId,
    val counted: Money,
    val date: BusinessDate,
    val description: String,
) : Command {
    override val requiredPermission = Permission.TREASURY_RECONCILE
    override fun fingerprint() = "$scope|$accountId|${counted.rial}|${date.epochDay}|$description"
}

/** Reverses a treasury-owned document only. Documents of other modules are reversed there (AUD-002). */
data class ReverseTreasuryDocument(
    override val commandId: GlobalId,
    override val scope: Scope,
    val documentType: String,
    val documentId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.TREASURY_REVERSE
    override fun fingerprint() = "$scope|$documentType|$documentId|${date.epochDay}|$reason"
}

class TreasuryOperations(
    private val bus: CommandBus,
    private val gateway: TreasuryGateway,
    private val capability: PostingCapability,
    private val accounts: TreasuryAccountStore,
) {
    init {
        require(capability.module == ModuleId.TREASURY)
    }

    fun openAccount(c: OpenTreasuryAccount): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام حساب الزامی است.") }
        ensure(accounts.all().none { it.scope == cmd.scope && it.name == name }) { DomainError.InvalidState("TREASURY_ACCOUNT", "DUPLICATE_NAME") }
        val gl = when (cmd.kind) {
            TreasuryKind.CASH -> StandardAccounts.CASH
            TreasuryKind.BANK -> StandardAccounts.BANK
            TreasuryKind.CARD_TERMINAL -> StandardAccounts.CARD_CLEARING
            TreasuryKind.PETTY_CASH -> StandardAccounts.PETTY_CASH
        }
        val account = TreasuryAccount(GlobalId.new(), name, cmd.kind, cmd.scope, gl)
        accounts.save(account)
        ctx.audit(AuditDraft("TREASURY_ACCOUNT_OPEN", "TREASURY_ACCOUNT", account.id.value, "${cmd.kind}:$name"))
        account.id
    }

    fun receipt(c: RecordReceipt): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        requireAccountScope(cmd.accountId, cmd.scope)
        val docId = GlobalId.new()
        gateway.settle(
            ctx, capability, cmd.accountId, Direction.RECEIPT, cmd.amount, cmd.date, RECEIPT, docId, cmd.description,
            listOf(LineDraft(cmd.purpose.counterAccount, credit = cmd.amount, memo = cmd.purpose.name, by = capability)),
        )
        docId
    }

    fun payment(c: RecordPayment): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        requireAccountScope(cmd.accountId, cmd.scope)
        val docId = GlobalId.new()
        gateway.settle(
            ctx, capability, cmd.accountId, Direction.PAYMENT, cmd.amount, cmd.date, PAYMENT, docId, cmd.description,
            listOf(LineDraft(cmd.purpose.counterAccount, debit = cmd.amount, memo = cmd.purpose.name, by = capability)),
        )
        docId
    }

    fun transfer(c: TransferFunds): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        ensure(cmd.fromAccountId != cmd.toAccountId) { DomainError.InvalidInput("account", "حساب مبدأ و مقصد یکسان است.") }
        val from = gateway.account(cmd.fromAccountId)
        val to = gateway.account(cmd.toAccountId)
        ensure(from.scope == cmd.scope) { DomainError.InvalidInput("scope", "محدوده فرمان با حساب مبدأ یکسان نیست.") }
        ctx.requireScope(to.scope)
        val docId = GlobalId.new()
        if (from.scope == to.scope) {
            gateway.recordTransferWithinScope(ctx, from, to, cmd.amount, cmd.date, docId, cmd.description)
        } else {
            // Cross-scope: each side stays balanced in its own scope through the inter-branch account.
            gateway.settle(
                ctx, capability, from.id, Direction.PAYMENT, cmd.amount, cmd.date, TRANSFER, docId, cmd.description,
                listOf(LineDraft(StandardAccounts.INTER_BRANCH, debit = cmd.amount, memo = "به ${to.name}", by = capability)),
            )
            gateway.settle(
                ctx, capability, to.id, Direction.RECEIPT, cmd.amount, cmd.date, TRANSFER, docId, cmd.description,
                listOf(LineDraft(StandardAccounts.INTER_BRANCH, credit = cmd.amount, memo = "از ${from.name}", by = capability)),
            )
        }
        docId
    }

    fun reconcile(c: ReconcileAccount): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        requireAccountScope(cmd.accountId, cmd.scope)
        val book = gateway.balance(cmd.accountId)
        val difference = cmd.counted.rial - book
        val docId = GlobalId.new()
        ctx.audit(AuditDraft("TREASURY_COUNT", "TREASURY_ACCOUNT", cmd.accountId.value, "book=$book;counted=${cmd.counted.rial}"))
        if (difference != 0L) {
            val amount = Money.of(kotlin.math.abs(difference))
            val direction = if (difference > 0) Direction.RECEIPT else Direction.PAYMENT
            val line = if (difference > 0) LineDraft(StandardAccounts.CASH_OVER_SHORT, credit = amount, by = capability)
            else LineDraft(StandardAccounts.CASH_OVER_SHORT, debit = amount, by = capability)
            gateway.settle(ctx, capability, cmd.accountId, direction, amount, cmd.date, RECONCILIATION, docId, cmd.description, listOf(line))
        }
        docId
    }

    fun reverse(c: ReverseTreasuryDocument): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        ensure(cmd.documentType in OWN_DOCUMENTS) { DomainError.OwnedByAnotherModule("NOT_TREASURY_DOCUMENT") }
        gateway.reverseDocument(ctx, capability, cmd.documentType, cmd.documentId, cmd.date, cmd.reason)
        cmd.documentId
    }

    private fun requireAccountScope(accountId: GlobalId, scope: Scope) {
        ensure(gateway.account(accountId).scope == scope) { DomainError.InvalidInput("scope", "محدوده فرمان با حساب خزانه یکسان نیست.") }
    }

    companion object {
        const val RECEIPT = "TREASURY_RECEIPT"
        const val PAYMENT = "TREASURY_PAYMENT"
        const val TRANSFER = "TREASURY_TRANSFER"
        const val RECONCILIATION = "TREASURY_RECONCILIATION"
        private val OWN_DOCUMENTS = setOf(RECEIPT, PAYMENT, TRANSFER, RECONCILIATION)
    }
}
