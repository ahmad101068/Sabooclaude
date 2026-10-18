package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.RecordWaste
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.UpdateItem
import ir.sabou.inventory.WasteReason
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Expiry dates from invoices and shelf life, kept in the database, alerted and cleared by waste (ADR-0030). */
class ExpiryTest {
    private val dir = Files.createTempDirectory("expiry")
    private val db = DevDatabases.open(dir.resolve("e.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 18)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun expiringGoodsAreListedAlertedAndGoneOnceWasted() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val yogurt = core.inventory.createItem(CreateItem(GlobalId.new(), "ماست", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val chicken = core.inventory.createItem(CreateItem(GlobalId.new(), "مرغ", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        core.inventory.updateItem(UpdateItem(GlobalId.new(), chicken, "مرغ", Quantity.ZERO, Quantity.ZERO, "", "", null, emptySet(), true, shelfLifeDays = 2))
        assertEquals(2, core.overview.items().single { it.id == chicken }.shelfLifeDays)
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(GlobalId.new(), "پروتئین", "021")).resultId
        val inv = core.purchasing.postInvoice(PostPurchaseInvoice(GlobalId.new(), branch, supplier, "1", kitchen, day.plusDays(-3), day, listOf(
            InvoiceLine(yogurt, Quantity.units(4), Money.of(4_000_000), expiresOn = day.plusDays(-1)),
            InvoiceLine(chicken, Quantity.units(10), Money.of(20_000_000))))).resultId
        assertEquals(day.plusDays(-1), core.purchases.invoice(inv)!!.lines.single { it.itemId == yogurt }.expiresOn)

        val lots = core.overview.lots(branch, day)
        assertEquals(setOf("ماست" to -1L, "مرغ" to -1L), lots.map { it.item.name to it.lot.daysLeft(day) }.toSet())   // chicken: bought 3 days ago, keeps 2
        val owner = core.overview.me().userId
        assertEquals(2, core.alerts.forUser(owner, day).count { it.kind == Alert.Kind.EXPIRY })

        core.inventory.waste(RecordWaste(GlobalId.new(), branch, kitchen, yogurt, Quantity.units(4), WasteReason.EXPIRED, "", day))
        assertEquals(listOf("مرغ"), core.overview.lots(branch, day).map { it.item.name })
        assertTrue(core.alerts.forUser(owner, day).none { it.kind == Alert.Kind.EXPIRY && "ماست" in it.title })
    }
}
