package ir.sabou.inventory

import ir.sabou.inventory.memory.InMemoryItemStore
import ir.sabou.inventory.memory.InMemoryLocationStore
import ir.sabou.inventory.memory.InMemoryRecipeStore
import ir.sabou.inventory.memory.InMemoryStockStore
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LedgerAccessRegistry
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.StandardAccounts
import ir.sabou.ledger.memory.InMemoryAccountStore
import ir.sabou.ledger.memory.InMemoryJournalStore
import ir.sabou.ledger.memory.InMemoryPeriodStore
import ir.sabou.platform.Actor
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryEventLog
import ir.sabou.platform.memory.InMemoryDocumentNumberStore
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ir.sabou.inventory.memory.InMemoryLotStore
import ir.sabou.platform.NoDocument

/** Expiry lots: first expired, first out; carried by transfers; the books never depend on them (ADR-0030). */
class LotsTest {
    private val branchA = Scope.Branch(BranchId(GlobalId.new()))
    private val branchB = Scope.Branch(BranchId(GlobalId.new()))
    private val session = MutableSession(Actor(GlobalId.new(), "owner", Role.OWNER, emptySet()))
    private val uow = InMemoryUnitOfWork()
    private val journals = InMemoryJournalStore()
    private val items = InMemoryItemStore()
    private val locations = InMemoryLocationStore()
    private val stock = InMemoryStockStore()
    private val recipes = InMemoryRecipeStore()
    private val lots = InMemoryLotStore()
    private val numbers = InMemoryDocumentNumberStore().also { uow.register(it) }
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }, numbers) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, InMemoryAccountStore(StandardAccounts.chart()), journals, InMemoryPeriodStore())
    private val gateway = InventoryGateway(ledger, registry.issue(ModuleId.INVENTORY), items, locations, stock, lots = lots)
    private val ops = InventoryOperations(bus, gateway, items, locations, recipes)
    private val purchasing = registry.issue(ModuleId.PURCHASING)
    private val sales = registry.issue(ModuleId.SALES)
    private val day = BusinessDate(20_000)

    init { uow.register(journals, items, locations, stock, recipes, lots) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun kg(v: Long) = Quantity.units(v)
    private fun item(name: String) = ops.createItem(CreateItem(GlobalId.new(), name, StockUnit.KILOGRAM, kg(1))).resultId
    private fun location(scope: Scope.Branch, name: String) = ops.createLocation(CreateLocation(GlobalId.new(), scope, name)).resultId
    private fun left(item: GlobalId, location: GlobalId) = lots.open(item, location).map { it.expiresOn.epochDay - day.epochDay to it.remaining.micros / Quantity.SCALE }

    private fun buy(location: GlobalId, item: GlobalId, qty: Long, expires: BusinessDate?, invoice: GlobalId = GlobalId.new()) =
        bus.execute(ModuleId.PURCHASING, Simple(branchA, Permission.PURCHASE_RECORD)) { _, ctx ->
            gateway.receive(ctx, purchasing, location, listOf(ReceiptLine(item, kg(qty), Money.of(qty * 1_000), expires)), day, "PURCHASE_INVOICE", invoice, "خرید",
                listOf(LineDraft(StandardAccounts.PAYABLE, credit = Money.of(qty * 1_000), by = purchasing))).id
        }.let { invoice }

    private fun sell(location: GlobalId, item: GlobalId, qty: Long) = bus.execute(ModuleId.SALES, Simple(branchA, Permission.SALES_POST)) { _, ctx ->
        gateway.consume(ctx, sales, location, listOf(IssueLine(item, kg(qty))), day, "DAILY_SALES", GlobalId.new(), "مصرف").journal!!.id
    }

    @Test fun theEarliestExpiryIsUsedFirstAndGoodsWithoutADateLast() {
        val milk = item("شیر")
        val fridge = location(branchA, "یخچال")
        buy(fridge, milk, 10, day.plusDays(5))
        buy(fridge, milk, 4, null)                       // no date, no shelf life: untracked
        buy(fridge, milk, 6, day.plusDays(2))
        assertEquals(listOf(2L to 6L, 5L to 10L), left(milk, fridge))
        sell(fridge, milk, 8)                            // 6 of the day-2 lot, 2 of the day-5 lot
        assertEquals(listOf(5L to 8L), left(milk, fridge))
        ops.waste(RecordWaste(GlobalId.new(), branchA, fridge, milk, kg(9), WasteReason.EXPIRED, "", day))
        assertEquals(emptyList(), left(milk, fridge))   // the rest of the lot, then 1 of the untracked
        assertEquals(kg(3), gateway.balance(milk, fridge).quantity)
        assertEquals("INVALID_INPUT:expiresOn", code { buy(fridge, milk, 1, day.plusDays(-1)) })
    }

    @Test fun shelfLifeDatesReceiptsAndProductionAndTransfersCarryTheDates() {
        val meat = item("گوشت")
        val sauce = ops.createItem(CreateItem(GlobalId.new(), "سس", StockUnit.KILOGRAM, kg(0), prepared = true)).resultId
        ops.updateItem(UpdateItem(GlobalId.new(), meat, "گوشت", kg(1), kg(0), "", "", null, emptySet(), true, shelfLifeDays = 3))
        ops.updateItem(UpdateItem(GlobalId.new(), sauce, "سس", kg(0), kg(0), "", "", null, emptySet(), true, shelfLifeDays = 2))
        assertEquals("INVALID_INPUT:shelfLife", code { ops.updateItem(UpdateItem(GlobalId.new(), meat, "گوشت", kg(1), kg(0), "", "", null, emptySet(), true, shelfLifeDays = 0)) })
        val store = location(branchA, "سردخانه")
        val kitchen = location(branchA, "آشپزخانه")
        val other = location(branchB, "شعبه دو")
        val invoice = buy(store, meat, 10, null)
        assertEquals(listOf(3L to 10L), left(meat, store))
        buy(store, meat, 5, day.plusDays(1))             // a printed date wins over the shelf life
        assertEquals(listOf(1L to 5L, 3L to 10L), left(meat, store))

        ops.transfer(TransferStock(GlobalId.new(), branchA, store, kitchen, listOf(IssueLine(meat, kg(7))), day, ""))
        assertEquals(listOf(3L to 8L), left(meat, store))
        assertEquals(listOf(1L to 5L, 3L to 2L), left(meat, kitchen))
        ops.transfer(TransferStock(GlobalId.new(), branchA, store, other, listOf(IssueLine(meat, kg(1))), day, ""))
        assertEquals(listOf(3L to 1L), left(meat, other))

        ops.publishPrepRecipe(PublishPrepRecipe(GlobalId.new(), sauce, BusinessDate(19_000), kg(1), listOf(RecipeLine(meat, kg(1)))))
        ops.produce(RecordProduction(GlobalId.new(), branchA, kitchen, sauce, kg(2), day))
        assertEquals(listOf(2L to 2L), left(sauce, kitchen))
        assertEquals(listOf(1L to 3L, 3L to 2L), left(meat, kitchen))   // production used the earliest meat

    }

    @Test fun aReversedReceiptTakesBackItsOwnLot() {
        val cream = item("خامه")
        val fridge = location(branchA, "یخچال")
        val late = buy(fridge, cream, 5, day.plusDays(5))
        buy(fridge, cream, 5, day.plusDays(2))
        bus.execute(ModuleId.PURCHASING, Simple(branchA, Permission.PURCHASE_RECORD)) { _, ctx ->
            gateway.reverseDocument(ctx, purchasing, "PURCHASE_INVOICE", late, day, "اشتباه"); late
        }
        assertEquals(listOf(2L to 5L), left(cream, fridge))       // not the earlier-expiring lot
    }

    @NoDocument
    private class Simple(override val scope: Scope, override val requiredPermission: Permission) : Command {
        override val commandId: GlobalId = GlobalId.new()
        override fun fingerprint() = "simple"
    }
}
