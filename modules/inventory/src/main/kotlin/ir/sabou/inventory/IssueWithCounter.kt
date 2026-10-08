package ir.sabou.inventory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.ensure
import ir.sabou.ledger.JournalDraft
import ir.sabou.ledger.JournalEntry
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.PostingCapability
import ir.sabou.ledger.SourceDocument
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.CommandContext

data class CounterIssue(val lines: List<IssuedCost>, val cost: Money, val variance: Long, val journal: JournalEntry)

/**
 * Goods out against an owner's account at a price the owner decides (for example a return to the
 * supplier at invoice price). Stock leaves at weighted-average cost; the difference between price
 * and cost goes to inventory variance (6106), so stock value and GL stay equal.
 */
fun InventoryGateway.issueWithCounter(
    context: CommandContext,
    owner: PostingCapability,
    locationId: GlobalId,
    lines: List<IssueLine>,
    date: BusinessDate,
    sourceType: String,
    sourceId: GlobalId,
    description: String,
    counterDebitLines: List<LineDraft>,
): CounterIssue {
    ensure(owner.module == context.module) { DomainError.OwnedByAnotherModule(owner.module.name) }
    val location = location(locationId)
    context.requireScope(location.scope)
    ensure(lines.isNotEmpty() && lines.none { it.quantity.isZero } && lines.map { it.itemId }.distinct().size == lines.size) {
        DomainError.InvalidInput("lines", "ردیف‌های خروج معتبر نیست.")
    }
    ensure(counterDebitLines.all { it.credit.isZero }) { DomainError.InvalidInput("lines", "ردیف مقابل باید بدهکار باشد.") }
    val priced = lines.map { item(it.itemId); IssuedCost(it.itemId, it.quantity, valueOf(balance(it.itemId, location.id), it.quantity)) }
    val cost = Money.sum(priced.map { it.cost })
    val price = Money.sum(counterDebitLines.map { it.debit })
    val variance = price.rial - cost.rial
    val varianceLine = when {
        variance > 0 -> listOf(LineDraft(StandardAccounts.INVENTORY_VARIANCE, credit = Money.of(variance), by = ownCapability))
        variance < 0 -> listOf(LineDraft(StandardAccounts.INVENTORY_VARIANCE, debit = Money.of(-variance), by = ownCapability))
        else -> emptyList()
    }
    val inventoryLine = if (cost.isZero) emptyList() else listOf(LineDraft(StandardAccounts.INVENTORY, credit = cost, memo = location.name, by = ownCapability))
    val journal = postAs(context, owner, JournalDraft(date, location.scope, sourceType, sourceId, description, counterDebitLines + inventoryLine + varianceLine))
    priced.forEach {
        stockOut(context, it.itemId, location, it.quantity, it.cost, MovementKind.ISSUE, date, journal.id, SourceDocument(owner.module, sourceType, sourceId), null)
    }
    return CounterIssue(priced, cost, variance, journal)
}
