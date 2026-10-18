package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.CustomerType
import ir.sabou.sales.RegisterCustomer
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One search box: names in any spelling, invoice and document numbers, amounts; only what the role may see (ADR-0040). */
class SearchTest {
    private val dir = Files.createTempDirectory("search")
    private val db = DevDatabases.open(dir.resolve("s.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 20)
    private fun id() = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun namesNumbersAmountsAndPermissions() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cheese = core.inventory.createItem(CreateItem(id(), "پنیر پیتزا", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات کاله", "02144556677")).resultId
        core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آرین", CustomerType.COMPANY, "021", Money.of(1_000_000)))
        val inv = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "A-778", kitchen, day, day,
            listOf(InvoiceLine(cheese, Quantity.units(5), Money.of(12_345_000))))).resultId

        assertEquals(listOf(SearchHit.Kind.ITEM to cheese), core.search.find("پنير").map { it.kind to it.id })          // Arabic ي
        assertEquals(supplier, core.search.find("۴۴۵۵۶۶").single { it.kind == SearchHit.Kind.SUPPLIER }.id)              // part of the phone
        assertEquals(inv, core.search.find("a 778").single().id)                                                          // the supplier's number
        assertEquals(inv, core.search.find("12,345,000").single().id)                                                     // the amount
        val number = core.numbers.of(inv)!!.text
        assertTrue(core.search.find(number).any { it.id == inv && it.kind == SearchHit.Kind.PURCHASE_INVOICE })        // «فخ-1405-00001»
        assertTrue(core.search.find("1").any { it.id == inv })                                                            // just the sequence

        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout(); core.identity.login("cashier", "654321".toCharArray())
        assertEquals(emptyList(), core.search.find("کاله"))                                                               // no purchasing for a cashier
        assertEquals(listOf("شرکت آرین"), core.search.find("آرین").map { it.title })
    }
}
