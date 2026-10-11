package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Alerts of one user, computed in the background with that user's permissions (ADR-0024). */
class AlertsTest {
    private val dir = Files.createTempDirectory("alerts")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("a.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 18)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun eachUserGetsTheAlertsTheirRoleMaySeeWithoutSigningIn() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cheese = core.inventory.createItem(CreateItem(GlobalId.new(), "پنیر", StockUnit.KILOGRAM, Quantity.units(5))).resultId
        val a = core.purchasing.registerSupplier(RegisterSupplier(GlobalId.new(), "لبنیات", "021")).resultId
        val b = core.purchasing.registerSupplier(RegisterSupplier(GlobalId.new(), "پخش", "021")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(GlobalId.new(), branch, a, "1", kitchen, day.plusDays(-1), day, listOf(InvoiceLine(cheese, Quantity.units(1), Money.of(3_000_000)))))
        core.purchasing.postInvoice(PostPurchaseInvoice(GlobalId.new(), branch, b, "2", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(1), Money.of(3_600_000)))))
        val ownerId = core.overview.me().userId
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        val cashierId = core.overview.users().single { it.username == "cashier" }.id
        core.identity.logout()                                         // nobody signed in

        val owner = core.alerts.forUser(ownerId, day)
        assertEquals(setOf(Alert.Kind.LOW_STOCK, Alert.Kind.PRICE_CHANGE), owner.map { it.kind }.toSet())
        assertTrue(owner.single { it.kind == Alert.Kind.PRICE_CHANGE }.title.contains("+۲۰٪"))
        assertTrue(owner.single { it.kind == Alert.Kind.LOW_STOCK }.text.contains("۲"))       // 2 kg left, minimum 5
        assertTrue(core.alerts.forUser(cashierId, day).isEmpty())       // a cashier sees neither stock nor purchases
        // The same situation keeps its key: it is announced once.
        assertEquals(owner.map { it.key }, core.alerts.forUser(ownerId, day).map { it.key })
        // Nobody became signed in, and nothing can be done in the stand-in session.
        assertEquals(null, core.session.currentActor())
        val owned = core.users.byId(ownerId)!!.toActor()
        assertTrue(assertFailsWith<DomainException> {
            core.session.readAs(owned) { core.inventory.createItem(CreateItem(GlobalId.new(), "آرد", StockUnit.KILOGRAM, Quantity.ZERO)) }
        }.error.code.contains("COMMAND_IN_READ_ONLY_SESSION"))
    }
}
