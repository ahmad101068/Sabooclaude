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
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InventoryTest {
    private val branchA = Scope.Branch(BranchId(GlobalId.new()))
    private val branchB = Scope.Branch(BranchId(GlobalId.new()))
    private val session = MutableSession(Actor(GlobalId.new(), "owner", Role.OWNER, emptySet()))
    private val uow = InMemoryUnitOfWork()
    private val journals = InMemoryJournalStore()
    private val items = InMemoryItemStore()
    private val locations = InMemoryLocationStore()
    private val stock = InMemoryStockStore()
    private val recipes = InMemoryRecipeStore()
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, InMemoryAccountStore(StandardAccounts.chart()), journals, InMemoryPeriodStore())
    private val gateway = InventoryGateway(ledger, registry.issue(ModuleId.INVENTORY), items, locations, stock)
    private val ops = InventoryOperations(bus, gateway, items, locations, recipes)
    private val purchasing = registry.issue(ModuleId.PURCHASING)
    private val sales = registry.issue(ModuleId.SALES)
    private val day = BusinessDate(20_000)

    init { uow.register(journals, items, locations, stock, recipes) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun kg(v: Long) = Quantity.units(v)
    private fun rial(v: Long) = Money.of(v)
    private fun item(name: String) = ops.createItem(CreateItem(GlobalId.new(), name, StockUnit.KILOGRAM, kg(1))).resultId
    private fun location(scope: Scope.Branch, name: String) = ops.createLocation(CreateLocation(GlobalId.new(), scope, name)).resultId

    private fun buy(location: GlobalId, scope: Scope, item: GlobalId, qty: Quantity, value: Long, invoice: GlobalId = GlobalId.new()) =
        bus.execute(ModuleId.PURCHASING, Simple(scope, Permission.PURCHASE_RECORD)) { _, ctx ->
            gateway.receive(ctx, purchasing, location, listOf(ReceiptLine(item, qty, rial(value))), day, "PURCHASE_INVOICE", invoice, "خرید",
                listOf(LineDraft(StandardAccounts.PAYABLE, credit = rial(value), by = purchasing))).id
        }.let { invoice }

    private fun sell(location: GlobalId, scope: Scope, lines: List<IssueLine>, doc: GlobalId = GlobalId.new()): Money {
        var cost = Money.ZERO
        bus.execute(ModuleId.SALES, Simple(scope, Permission.SALES_POST)) { _, ctx ->
            cost = gateway.consume(ctx, sales, location, lines, day, "DAILY_SALES", doc, "مصرف فروش").totalCost
            doc
        }
        return cost
    }

    private fun stockValue(scope: Scope.Branch): Long = locations.all().filter { it.scope == scope }
        .flatMap { stock.balances(it.id) }.sumOf { it.value.rial }

    @Test fun weightedAverageCostPerLocation() {
        val cheese = item("پنیر پیتزا")
        val store = location(branchA, "انبار مرکزی")
        buy(store, branchA, cheese, kg(10), 1_000_000)
        buy(store, branchA, cheese, kg(10), 2_000_000)
        assertEquals(750_000, sell(store, branchA, listOf(IssueLine(cheese, kg(5)))).rial)
        assertEquals(2_250_000, gateway.balance(cheese, store).value.rial)
        assertEquals(750_000, ledger.balance(StandardAccounts.COGS, branchA).rial)
        assertEquals(stockValue(branchA), ledger.balance(StandardAccounts.INVENTORY, branchA).rial)
    }

    @Test fun lastUnitTakesTheRemainderSoNoValueIsLeftBehind() {
        val oil = item("روغن")
        val store = location(branchA, "انبار")
        buy(store, branchA, oil, kg(3), 100)
        val costs = (1..3).map { sell(store, branchA, listOf(IssueLine(oil, kg(1)))).rial }
        assertEquals(100, costs.sum())
        assertEquals(0, gateway.balance(oil, store).value.rial)
    }

    @Test fun shortageInAnyLineWritesNothing() {
        val a = item("گوشت"); val b = item("نان")
        val store = location(branchA, "انبار")
        buy(store, branchA, a, kg(5), 5_000)
        assertTrue(code { sell(store, branchA, listOf(IssueLine(a, kg(2)), IssueLine(b, kg(1)))) }.startsWith("INSUFFICIENT_STOCK"))
        assertEquals(kg(5), gateway.balance(a, store).quantity)
        assertEquals(0, ledger.balance(StandardAccounts.COGS).rial)
    }

    @Test fun interBranchTransferKeepsEachBranchsBooksTrue_AUD011() {
        val meat = item("گوشت چرخ‌کرده")
        val central = location(branchA, "انبار مرکزی A")
        val kitchenB = location(branchB, "آشپزخانه B")
        buy(central, branchA, meat, kg(20), 4_000_000)
        ops.transfer(TransferStock(GlobalId.new(), branchA, central, kitchenB, listOf(IssueLine(meat, kg(5))), day, "ارسال به شعبه B"))
        sell(kitchenB, branchB, listOf(IssueLine(meat, kg(2))))
        assertEquals(stockValue(branchA), ledger.balance(StandardAccounts.INVENTORY, branchA).rial)
        assertEquals(stockValue(branchB), ledger.balance(StandardAccounts.INVENTORY, branchB).rial)
        assertTrue(ledger.balance(StandardAccounts.INVENTORY, branchB).rial >= 0)
        assertEquals(0, ledger.balance(StandardAccounts.INTER_BRANCH).rial)
        assertEquals(400_000, ledger.balance(StandardAccounts.COGS, branchB).rial)
    }

    @Test fun storekeeperOfOneBranchCannotSendStockIntoAnother() {
        val meat = item("گوشت")
        val a = location(branchA, "انبار A")
        val b = location(branchB, "انبار B")
        buy(a, branchA, meat, kg(5), 1_000)
        session.actor = Actor(GlobalId.new(), "storekeeper-A", Role.STOREKEEPER, setOf(branchA.branchId))
        assertTrue(code { ops.transfer(TransferStock(GlobalId.new(), branchA, a, b, listOf(IssueLine(meat, kg(1))), day, "")) }.startsWith("SCOPE_DENIED"))
    }

    @Test fun countAndWastePostTheirVariances() {
        val rice = item("برنج")
        val store = location(branchA, "انبار")
        buy(store, branchA, rice, kg(10), 1_000_000)
        ops.count(PostStockCount(GlobalId.new(), branchA, store, listOf(CountLine(rice, kg(9))), day))
        assertEquals(100_000, ledger.balance(StandardAccounts.INVENTORY_VARIANCE, branchA).rial)
        ops.waste(RecordWaste(GlobalId.new(), branchA, store, rice, kg(1), WasteReason.SPOILAGE, "", day))
        assertEquals(100_000, ledger.balance(StandardAccounts.WASTE, branchA).rial)
        assertEquals(kg(8), gateway.balance(rice, store).quantity)
        assertEquals(stockValue(branchA), ledger.balance(StandardAccounts.INVENTORY, branchA).rial)
        assertEquals("INVALID_INPUT:note", code { ops.waste(RecordWaste(GlobalId.new(), branchA, store, rice, kg(1), WasteReason.OTHER, "", day)) })
    }

    @Test fun recipeVersionInForceOnTheSaleDateIsUsed() {
        val dough = item("خمیر")
        val pizza = ops.defineMenuItem(DefineMenuItem(GlobalId.new(), "پیتزا مخلوط")).resultId
        ops.publishRecipe(PublishRecipe(GlobalId.new(), pizza, BusinessDate(19_000), listOf(RecipeLine(dough, Quantity.of(250_000)))))
        ops.publishRecipe(PublishRecipe(GlobalId.new(), pizza, BusinessDate(20_000), listOf(RecipeLine(dough, Quantity.of(300_000)))))
        val book = RecipeBook(recipes)
        assertEquals(Quantity.of(500_000), book.requirements(pizza, BusinessDate(19_500), Quantity.units(2)).single().quantity)
        assertEquals(Quantity.of(600_000), book.requirements(pizza, BusinessDate(20_001), Quantity.units(2)).single().quantity)
        assertEquals("INVALID_STATE:RECIPE:NOT_AFTER_LATEST_VERSION", code {
            ops.publishRecipe(PublishRecipe(GlobalId.new(), pizza, BusinessDate(19_999), listOf(RecipeLine(dough, Quantity.of(1)))))
        })
    }

    @Test fun receiptCanOnlyBeReversedByItsOwnerAndOnlyWhileTheGoodsAreThere() {
        val milk = item("شیر")
        val store = location(branchA, "انبار")
        val invoice = buy(store, branchA, milk, kg(10), 1_000)
        sell(store, branchA, listOf(IssueLine(milk, kg(4))))
        assertTrue(code {
            bus.execute(ModuleId.SALES, Simple(branchA, Permission.SALES_REVERSE)) { _, ctx ->
                gateway.reverseDocument(ctx, sales, "PURCHASE_INVOICE", invoice, day, "تلاش"); invoice
            }
        }.startsWith("OWNED_BY"))
        assertTrue(code {
            bus.execute(ModuleId.PURCHASING, Simple(branchA, Permission.PURCHASE_REVERSE)) { _, ctx ->
                gateway.reverseDocument(ctx, purchasing, "PURCHASE_INVOICE", invoice, day, "برگشت فاکتور"); invoice
            }
        }.startsWith("INSUFFICIENT_STOCK"))
        assertEquals(kg(6), gateway.balance(milk, store).quantity)
    }

    @Test fun salesReversalRestoresStockAtOriginalCost() {
        val flour = item("آرد")
        val store = location(branchA, "انبار")
        buy(store, branchA, flour, kg(10), 10_000)
        val doc = GlobalId.new()
        sell(store, branchA, listOf(IssueLine(flour, kg(3))), doc)
        bus.execute(ModuleId.SALES, Simple(branchA, Permission.SALES_REVERSE)) { _, ctx ->
            gateway.reverseDocument(ctx, sales, "DAILY_SALES", doc, day, "ثبت اشتباه"); doc
        }
        assertEquals(StockBalance(flour, store, kg(10), rial(10_000)), gateway.balance(flour, store))
        assertEquals(0, ledger.balance(StandardAccounts.COGS).rial)
    }

    private class Simple(override val scope: Scope, override val requiredPermission: Permission) : Command {
        override val commandId: GlobalId = GlobalId.new()
        override fun fingerprint() = "simple"
    }

    // ---------------------------------------------------------------- yield, prepared items, period queries

    @Test fun recipeYieldTakesMoreFromStockThanEndsUpInTheDish() {
        val onion = item("پیاز")
        val line = RecipeLine(onion, Quantity.of(80_000), yieldPercent = 80)          // 80 g in the dish, 20 % peeled away
        assertEquals(Quantity.of(100_000), line.grossFor(Quantity.units(1)))
        assertEquals(Quantity.of(1_000_000), line.grossFor(Quantity.units(10)))
        assertEquals(Quantity.of(80_000), RecipeLine(onion, Quantity.of(80_000)).grossFor(Quantity.units(1)))   // default 100 %
        val menu = ops.defineMenuItem(DefineMenuItem(GlobalId.new(), "سوپ")).resultId
        assertEquals("INVALID_INPUT:yield", code { ops.publishRecipe(PublishRecipe(GlobalId.new(), menu, day, listOf(line.copy(yieldPercent = 0)))) })
        ops.publishRecipe(PublishRecipe(GlobalId.new(), menu, day, listOf(line)))
        assertEquals(listOf(IssueLine(onion, Quantity.units(1))), RecipeBook(recipes).requirements(menu, day, Quantity.units(10)))
    }

    @Test fun productionConservesValueAndNeedsAPreparedItem() {
        val kitchen = location(branchA, "آشپزخانه مرکزی")
        val tomato = item("گوجه"); val oil = item("روغن")
        val sauce = ops.createItem(CreateItem(GlobalId.new(), "سس پاستا", StockUnit.KILOGRAM, kg(0), prepared = true)).resultId
        buy(kitchen, branchA, tomato, kg(10), 1_000_000)
        buy(kitchen, branchA, oil, kg(2), 300_000)
        // 4 kg of sauce from 5 kg tomato (90 % usable) and 1 kg oil.
        ops.publishPrepRecipe(PublishPrepRecipe(GlobalId.new(), sauce, day, kg(4),
            listOf(RecipeLine(tomato, Quantity.of(4_500_000), 90), RecipeLine(oil, kg(1)))))
        assertEquals("INVALID_STATE:ITEM:NOT_PREPARED", code {
            ops.publishPrepRecipe(PublishPrepRecipe(GlobalId.new(), tomato, day, kg(1), listOf(RecipeLine(oil, kg(1)))))
        })
        ops.produce(RecordProduction(GlobalId.new(), branchA, kitchen, sauce, kg(8), day))     // two batches
        assertEquals(Quantity.ZERO, gateway.balance(tomato, kitchen).quantity)
        assertEquals(Quantity.ZERO, gateway.balance(oil, kitchen).quantity)
        val made = gateway.balance(sauce, kitchen)
        assertEquals(kg(8), made.quantity)
        assertEquals(1_300_000, made.value.rial)                                              // all ingredient value, nothing lost
        assertEquals(1_300_000, ledger.balance(StandardAccounts.INVENTORY, branchA).rial)     // no journal needed, still equal
        assertTrue(code { ops.produce(RecordProduction(GlobalId.new(), branchA, kitchen, sauce, kg(1), day)) }.startsWith("INSUFFICIENT_STOCK"))
        // Movements by period: the opening of the next day is today's closing.
        val totals = stock.totalsBefore(kitchen, day.plusDays(1)).associateBy { it.itemId }
        assertEquals(8_000_000, totals.getValue(sauce).quantity)
        assertEquals(0, totals.getValue(tomato).quantity)
        assertEquals(5, stock.movementsAt(kitchen, day, day).size)   // 2 purchases, 2 ingredients out, 1 sauce in
        assertTrue(stock.movementsAt(kitchen, day.plusDays(1), day.plusDays(2)).isEmpty())
    }

    @Test fun itemDetailsAreEditableButStayConsistent() {
        val rice = item("برنج")
        val supplierA = GlobalId.new(); val supplierB = GlobalId.new()
        assertEquals("INVALID_INPUT:parLevel", code {
            ops.updateItem(UpdateItem(GlobalId.new(), rice, "برنج", kg(10), kg(5), "", "", null, emptySet(), true))
        })
        assertEquals("INVALID_INPUT:supplier", code {
            ops.updateItem(UpdateItem(GlobalId.new(), rice, "برنج", kg(10), kg(30), "", "", supplierB, setOf(supplierA), true))
        })
        ops.updateItem(UpdateItem(GlobalId.new(), rice, "برنج هاشمی", kg(10), kg(30), "انبار خشک · قفسه ۲", "", supplierA, setOf(supplierA, supplierB), true))
        val stored = items.byId(rice)!!
        assertEquals("برنج هاشمی", stored.name); assertEquals(kg(30), stored.parLevel); assertEquals("انبار خشک · قفسه ۲", stored.shelf)
        assertEquals(setOf(supplierA, supplierB), stored.approvedSupplierIds)
        assertEquals(StockUnit.KILOGRAM, stored.unit)
    }
}
