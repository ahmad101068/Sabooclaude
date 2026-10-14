package ir.sabou.core

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Actor
import ir.sabou.platform.Permission

/** What a branch owes in VAT and levies on its sales, and the VAT on purchases it may set against it. */
data class SalesTaxView(val owed: Money, val inputCredit: Money)

/** Accounting read models (ADR-0045). */
class AccountingReads internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun Actor.require(scope: Scope) {
        if (!canAccess(scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(scope)))
    }

    fun salesTax(branch: Scope.Branch): SalesTaxView {
        actor(Permission.TREASURY_PAYMENT, Permission.LEDGER_VIEW).require(branch)
        return SalesTaxView(core.salesOps.salesTaxOwed(branch), Money.of(maxOf(0L, core.ledger.balance(StandardAccounts.VAT_RECEIVABLE, branch).rial)))
    }
}
