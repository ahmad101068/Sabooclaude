package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CountEntry
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.DeleteItem
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.InventoryOperations
import ir.sabou.inventory.LineReason
import ir.sabou.inventory.MovementKind
import ir.sabou.inventory.PublishPrepRecipe
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.ReceiptLine
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.RecordOpeningStock
import ir.sabou.inventory.RecordProduction
import ir.sabou.inventory.RecordWaste
import ir.sabou.inventory.ReverseInventoryDocument
import ir.sabou.inventory.StockLevels
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.SubmitStockCount
import ir.sabou.inventory.ApproveStockCount
import ir.sabou.inventory.TransferStock
import ir.sabou.inventory.UpdateItem
import ir.sabou.inventory.UpdateLocation
import ir.sabou.inventory.VarianceReason
import ir.sabou.inventory.WasteReason
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Inventory review, stage 1 (ADR-0044): reversals, document list, stock card, levels per location, item and location fixes. */
class InventoryFixesTest {
    private val dir = Files.createTempDirectory("invfix")
    private val db = DevDatabases.open(dir.resolve("i.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 18)
    private fun id() = GlobalId.new()
    private fun kg(n: Long) = Quantity.units(n)
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private lateinit var branch: Scope.Branch
    private var kitchen = GlobalId.new()

    private fun start() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
    }

    private fun item(name: String, unit: StockUnit = StockUnit.KILOGRAM, prepared: Boolean = false) =
        core.inventory.createItem(CreateItem(id(), name, unit, Quantity.ZERO, prepared = prepared)).resultId

    private fun open(itemId: GlobalId, qty: Long, value: Long, location: GlobalId = kitchen) =
        core.inventory.openingStock(RecordOpeningStock(id(), branch, location, listOf(ReceiptLine(itemId, kg(qty), Money.of(value))), day)).resultId

    private fun qty(itemId: GlobalId, location: GlobalId = kitchen) = core.stock.balance(itemId, location).quantity

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun wasteOfSeveralItemsAndOfADishIsListedAndCanBeTakenBack() {
        start()
        val cheese = item("پنیر"); val dough = item("خمیر")
        open(cheese, 10, 10_000_000); open(dough, 10, 5_000_000)
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, day.plusDays(-1), listOf(RecipeLine(cheese, Quantity.of(200_000)), RecipeLine(dough, Quantity.of(300_000)))))
        val waste = core.inventory.waste(RecordWaste(id(), branch, kitchen, cheese, kg(1), WasteReason.SPOILAGE, "یخچال خاموش شد", day,
            lines = listOf(IssueLine(dough, kg(2))))).resultId
        core.inventory.waste(RecordWaste(id(), branch, kitchen, null, null, WasteReason.DAMAGE, "", day, dishId = pizza, portions = kg(2)))
        assertEquals(kg(10) - kg(1) - Quantity.of(400_000), qty(cheese))
        val docs = core.stockRecords.documents(branch, day, day).filter { it.document.type == InventoryOperations.WASTE }
        assertEquals(2, docs.size)
        assertTrue(docs.any { it.document.dish.contains("پیتزا") && it.lines.size == 2 })
        assertEquals("یخچال خاموش شد", docs.single { it.document.id == waste }.document.note)

        // Entered by mistake: taken back with a reason; stock and the waste expense return.
        val wasteBefore = core.ledger.balance(StandardAccounts.WASTE).rial
        core.inventory.reverseDocument(ReverseInventoryDocument(id(), branch, InventoryOperations.WASTE, waste, day, "اشتباه ثبت شد"))
        assertEquals(kg(10) - Quantity.of(400_000), qty(cheese))
        assertEquals(wasteBefore - 1_000_000 - 1_000_000, core.ledger.balance(StandardAccounts.WASTE).rial)
        val view = core.stockRecords.documents(branch, day, day).single { it.document.id == waste }
        assertTrue(view.reversed); assertEquals("اشتباه ثبت شد", view.document.reversedReason)
        assertEquals("INVALID_STATE:STOCK_MOVEMENT:ALREADY_REVERSED", code {
            core.inventory.reverseDocument(ReverseInventoryDocument(id(), branch, InventoryOperations.WASTE, waste, day, "دوباره"))
        })
    }

    @Test fun productionWithAShortYieldAndTransfersAreReversible() {
        start()
        val tomato = item("گوجه"); val sauce = item("سس", prepared = true)
        open(tomato, 20, 20_000_000)
        core.inventory.publishPrepRecipe(PublishPrepRecipe(id(), sauce, day.plusDays(-1), kg(10), listOf(RecipeLine(tomato, kg(12)))))
        // Planned 10 kg, 8 kg came out: all 12 M of tomato is in the 8 kg (1.5 M a kilo).
        val batch = core.inventory.produce(RecordProduction(id(), branch, kitchen, sauce, kg(10), day, actualOutput = kg(8))).resultId
        assertEquals(kg(8), qty(sauce)); assertEquals(12_000_000, core.stock.balance(sauce, kitchen).value.rial)
        assertEquals(kg(10), core.stockRecords.documents(branch, day, day).single { it.document.id == batch }.document.planned)

        val store = core.inventory.createLocation(CreateLocation(id(), branch, "انبار خشک")).resultId
        val move = core.inventory.transfer(TransferStock(id(), branch, kitchen, store, listOf(IssueLine(tomato, kg(3))), day, "")).resultId
        core.inventory.reverseDocument(ReverseInventoryDocument(id(), branch, InventoryOperations.TRANSFER, move, day, "انبار اشتباه"))
        assertEquals(kg(8), qty(tomato)); assertEquals(Quantity.ZERO, qty(tomato, store))
        core.inventory.reverseDocument(ReverseInventoryDocument(id(), branch, InventoryOperations.PRODUCTION, batch, day, "تولید ثبت تکراری"))
        assertEquals(kg(20), qty(tomato)); assertEquals(Quantity.ZERO, qty(sauce))
    }

    @Test fun theStockCardShowsEveryMovementWithTheRunningBalance() {
        start()
        val rice = item("برنج")
        open(rice, 10, 10_000_000)
        core.inventory.waste(RecordWaste(id(), branch, kitchen, rice, kg(2), WasteReason.SPOILAGE, "", day))
        val card = core.stockRecords.card(rice, kitchen, day, day)
        assertEquals(listOf("موجودی اول دوره" to 10L, "ضایعات" to 8L), card.rows.map { it.title to it.balanceQuantity / Quantity.SCALE })
        assertEquals(8_000_000, card.closingValue)
        assertTrue(card.rows.all { it.number != null })
    }

    @Test fun aCountGainOnEmptyStockIsValuedAtTheLastPurchaseCost() {
        start()
        val oil = item("روغن")
        open(oil, 2, 3_000_000)
        core.inventory.waste(RecordWaste(id(), branch, kitchen, oil, kg(2), WasteReason.SPOILAGE, "", day))
        // Not opening stock: the last cost comes from a purchase receipt; with none, the gain stays at zero as before.
        val supplier = core.purchasing.registerSupplier(ir.sabou.purchasing.RegisterSupplier(id(), "روغن‌فروشی", "021")).resultId
        core.purchasing.postInvoice(ir.sabou.purchasing.PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day,
            listOf(ir.sabou.purchasing.InvoiceLine(oil, kg(1), Money.of(2_000_000)))))
        core.inventory.waste(RecordWaste(id(), branch, kitchen, oil, kg(1), WasteReason.SPOILAGE, "", day))
        val count = core.counts.submit(SubmitStockCount(id(), branch, kitchen, day, listOf(CountEntry(oil, kg(3))))).resultId
        core.counts.approve(ApproveStockCount(id(), branch, count, mapOf(oil to LineReason(VarianceReason.PREVIOUS_COUNT_ERROR))))
        assertEquals(6_000_000, core.stock.balance(oil, kitchen).value.rial)          // 3 kg × 2 M, not zero
    }

    @Test fun lowStockOnlyWhereTheItemIsKept() {
        start()
        val store = core.inventory.createLocation(CreateLocation(id(), branch, "انبار خشک")).resultId
        val flour = item("آرد"); val salt = item("نمک")
        core.inventory.updateItem(UpdateItem(id(), flour, "آرد", kg(5), Quantity.ZERO, "", "", null, emptySet(), true))
        core.inventory.updateItem(UpdateItem(id(), salt, "نمک", kg(5), Quantity.ZERO, "", "", null, emptySet(), true,
            locationLevels = mapOf(store to StockLevels(kg(2), kg(6)))))
        open(flour, 1, 1_000_000, store)
        val low = core.overview.lowStock().map { it.item.name to it.location.name }.toSet()
        // Flour: only the storeroom that holds it, not the kitchen. Salt: only where a level is set, at that level.
        assertEquals(setOf("آرد" to "انبار خشک", "نمک" to "انبار خشک"), low)
    }

    @Test fun aWrongUnitIsFixedAndAnUnusedItemDeletedButNotOnceUsed() {
        start()
        val sugar = item("شکر", StockUnit.GRAM)
        core.inventory.updateItem(UpdateItem(id(), sugar, "شکر", Quantity.ZERO, Quantity.ZERO, "", "", null, emptySet(), true, unit = StockUnit.KILOGRAM,
            category = "خشکبار", code = "123456"))
        with(core.overview.items().single { it.id == sugar }) { assertEquals(StockUnit.KILOGRAM, unit); assertEquals("خشکبار", category) }
        val tea = item("چای")
        assertEquals("INVALID_STATE:ITEM:DUPLICATE_CODE", code {
            core.inventory.updateItem(UpdateItem(id(), tea, "چای", Quantity.ZERO, Quantity.ZERO, "", "", null, emptySet(), true, code = "123456"))
        })
        core.inventory.deleteItem(DeleteItem(id(), tea))
        assertTrue(core.overview.items().none { it.id == tea })
        open(sugar, 1, 1_000_000)
        assertEquals("INVALID_STATE:ITEM:UNIT_IN_USE", code {
            core.inventory.updateItem(UpdateItem(id(), sugar, "شکر", Quantity.ZERO, Quantity.ZERO, "", "", null, emptySet(), true, unit = StockUnit.GRAM))
        })
        assertEquals("INVALID_STATE:ITEM:IN_USE", code { core.inventory.deleteItem(DeleteItem(id(), sugar)) })
    }

    @Test fun aLocationIsRenamedAndRetiredOnlyWhenEmpty() {
        start()
        val store = core.inventory.createLocation(CreateLocation(id(), branch, "انبار")).resultId
        val rice = item("برنج")
        open(rice, 1, 1_000_000, store)
        core.inventory.updateLocation(UpdateLocation(id(), branch, store, "انبار خشک", true))
        assertEquals("INVALID_STATE:LOCATION:HAS_STOCK", code { core.inventory.updateLocation(UpdateLocation(id(), branch, store, "انبار خشک", false)) })
        core.inventory.transfer(TransferStock(id(), branch, store, kitchen, listOf(IssueLine(rice, kg(1))), day, ""))
        core.inventory.updateLocation(UpdateLocation(id(), branch, store, "انبار خشک", false))
        assertTrue(core.overview.locations(branch).single { it.id == store }.let { !it.isActive && it.name == "انبار خشک" })
        assertEquals(listOf(MovementKind.TRANSFER_IN), core.stockRecords.card(rice, kitchen, day, day).rows.map { it.kind })
    }
}
