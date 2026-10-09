package ir.sabou.treasury

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.PostingCapability
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission

/** A received cheque handed to the bank for collection (no money moves until it is collected). */
data class DepositCheque(override val commandId: GlobalId, override val scope: Scope, val chequeId: GlobalId, val bankAccountId: GlobalId, val date: BusinessDate) : Command {
    override val requiredPermission = Permission.CHEQUE_MANAGE
    override fun fingerprint() = "$scope|$chequeId|$bankAccountId|${date.epochDay}"
}

/** A deposited cheque taken back from the bank. */
data class RecallCheque(override val commandId: GlobalId, override val scope: Scope, val chequeId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.CHEQUE_MANAGE
    override fun fingerprint() = "$scope|$chequeId|${date.epochDay}|$reason"
}

/** A received cheque paid into our bank account. */
data class CollectCheque(override val commandId: GlobalId, override val scope: Scope, val chequeId: GlobalId, val bankAccountId: GlobalId, val date: BusinessDate) : Command {
    override val requiredPermission = Permission.CHEQUE_MANAGE
    override fun fingerprint() = "$scope|$chequeId|$bankAccountId|${date.epochDay}"
}

/** Our cheque paid by our bank: the money leaves the bank account it was drawn on. */
data class ClearIssuedCheque(override val commandId: GlobalId, override val scope: Scope, val chequeId: GlobalId, val date: BusinessDate) : Command {
    override val requiredPermission = Permission.CHEQUE_MANAGE
    override fun fingerprint() = "$scope|$chequeId|${date.epochDay}"
}

/** A cheque (received or ours) that bounced: the claim or debt moves to the bounced-cheques account. */
data class BounceCheque(override val commandId: GlobalId, override val scope: Scope, val chequeId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.CHEQUE_MANAGE
    override fun fingerprint() = "$scope|$chequeId|${date.epochDay}|$reason"
}

/** A bounced cheque settled in money: received into [accountId] (theirs) or paid from it (ours). */
data class SettleBouncedCheque(override val commandId: GlobalId, override val scope: Scope, val chequeId: GlobalId, val accountId: GlobalId, val date: BusinessDate) : Command {
    override val requiredPermission = Permission.CHEQUE_MANAGE
    override fun fingerprint() = "$scope|$chequeId|$accountId|${date.epochDay}"
}

class ChequeOperations(
    private val bus: CommandBus,
    private val gateway: TreasuryGateway,
    private val capability: PostingCapability,
) {
    init {
        require(capability.module == ModuleId.TREASURY)
    }

    private fun cheque(id: GlobalId, scope: Scope): Cheque {
        val cheque = gateway.cheque(id)
        ensure(cheque.scope == scope) { DomainError.InvalidInput("scope", "چک متعلق به این شعبه نیست.") }
        return cheque
    }

    private fun bank(id: GlobalId, scope: Scope): TreasuryAccount {
        val bank = gateway.account(id)
        ensure(bank.kind == TreasuryKind.BANK && bank.scope == scope) { DomainError.InvalidInput("bankAccount", "حساب بانکی همین شعبه را انتخاب کنید.") }
        return bank
    }

    private fun title(c: Cheque) = "چک ${c.details.number} ${c.details.bank} · ${c.details.counterparty}"

    fun deposit(c: DepositCheque): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val cheque = cheque(cmd.chequeId, cmd.scope)
        bank(cmd.bankAccountId, cmd.scope)
        gateway.noteCheque(ctx, cheque.id, ChequeStatus.DEPOSITED, cmd.bankAccountId, cmd.date, TreasuryOperations.CHEQUE_DEPOSIT, GlobalId.new(), "")
        cheque.id
    }

    fun recall(c: RecallCheque): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val cheque = cheque(cmd.chequeId, cmd.scope)
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل الزامی است.") }
        gateway.noteCheque(ctx, cheque.id, ChequeStatus.IN_HAND, null, cmd.date, TreasuryOperations.CHEQUE_DEPOSIT, GlobalId.new(), cmd.reason)
        cheque.id
    }

    fun collect(c: CollectCheque): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val cheque = cheque(cmd.chequeId, cmd.scope)
        ensure(cheque.direction == ChequeDirection.RECEIVED) { DomainError.InvalidState("CHEQUE", cheque.status.name) }
        val docId = GlobalId.new()
        gateway.moveCheque(ctx, capability, cheque.id, gateway.account(cheque.accountId), bank(cmd.bankAccountId, cmd.scope), ChequeStatus.COLLECTED,
            cmd.date, TreasuryOperations.CHEQUE_COLLECT, docId, "وصول ${title(cheque)}")
        docId
    }

    fun clear(c: ClearIssuedCheque): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val cheque = cheque(cmd.chequeId, cmd.scope)
        ensure(cheque.direction == ChequeDirection.ISSUED) { DomainError.InvalidState("CHEQUE", cheque.status.name) }
        val docId = GlobalId.new()
        gateway.moveCheque(ctx, capability, cheque.id, bank(cheque.bankAccountId!!, cmd.scope), gateway.account(cheque.accountId), ChequeStatus.CLEARED,
            cmd.date, TreasuryOperations.CHEQUE_CLEAR, docId, "پاس شدن ${title(cheque)}")
        docId
    }

    fun bounce(c: BounceCheque): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val cheque = cheque(cmd.chequeId, cmd.scope)
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل برگشت الزامی است.") }
        val docId = GlobalId.new()
        val received = cheque.direction == ChequeDirection.RECEIVED
        gateway.settle(
            ctx, capability, cheque.accountId, if (received) Direction.PAYMENT else Direction.RECEIPT, cheque.amount, cmd.date,
            TreasuryOperations.CHEQUE_BOUNCE, docId, "برگشت ${title(cheque)}: ${cmd.reason.trim()}",
            listOf(
                if (received) LineDraft(StandardAccounts.BOUNCED_CHEQUES_RECEIVABLE, debit = cheque.amount, memo = cheque.details.counterparty, by = capability)
                else LineDraft(StandardAccounts.BOUNCED_CHEQUES_PAYABLE, credit = cheque.amount, memo = cheque.details.counterparty, by = capability),
            ),
            ChequeInstruction.Move(cheque.id, ChequeStatus.BOUNCED),
        )
        docId
    }

    fun settleBounced(c: SettleBouncedCheque): CommandOutcome = bus.execute(ModuleId.TREASURY, c) { cmd, ctx ->
        val cheque = cheque(cmd.chequeId, cmd.scope)
        val account = gateway.account(cmd.accountId)
        ensure(account.kind.isOrdinary && account.scope == cmd.scope) { DomainError.InvalidInput("account", "حساب نقدی یا بانکی همین شعبه را انتخاب کنید.") }
        val docId = GlobalId.new()
        val received = cheque.direction == ChequeDirection.RECEIVED
        gateway.settle(
            ctx, capability, account.id, if (received) Direction.RECEIPT else Direction.PAYMENT, cheque.amount, cmd.date,
            TreasuryOperations.CHEQUE_SETTLE, docId, "تسویه‌ی ${title(cheque)} برگشتی",
            listOf(
                if (received) LineDraft(StandardAccounts.BOUNCED_CHEQUES_RECEIVABLE, credit = cheque.amount, memo = cheque.details.counterparty, by = capability)
                else LineDraft(StandardAccounts.BOUNCED_CHEQUES_PAYABLE, debit = cheque.amount, memo = cheque.details.counterparty, by = capability),
            ),
            ChequeInstruction.Move(cheque.id, ChequeStatus.SETTLED),
        )
        docId
    }
}
