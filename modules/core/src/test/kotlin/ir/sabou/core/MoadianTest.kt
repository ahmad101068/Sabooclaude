package ir.sabou.core

import ir.sabou.inventory.DefineMenuItem
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.sales.CustomerType
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.SetCustomerTaxInfo
import ir.sabou.sales.SetItemTaxCode
import ir.sabou.sales.SetTaxProfile
import ir.sabou.sales.VatRate
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tax-system readiness: seller identity, VAT by date, dish product IDs, company tax IDs (ADR-0037). */
class MoadianTest {
    private val dir = Files.createTempDirectory("moadian")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("m.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 20)
    private fun id() = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun readinessListsWhatIsMissingUntilItIsComplete() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kebab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        val company = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آرین", CustomerType.COMPANY, "021", Money.of(1_000_000))).resultId
        assertEquals(3, core.moadian.readiness(day).problems.size)

        assertEquals("INVALID_INPUT:sellerTaxId", assertFailsWith<DomainException> {
            core.taxOps.setProfile(SetTaxProfile(id(), "0012345678", "", listOf(VatRate(JalaliCalendar.date(1404, 1, 1), 1_000))))
        }.error.code)
        // VAT 10 % from 1404, 12 % from 1406 (illustrative): the rate in force follows the date.
        core.taxOps.setProfile(SetTaxProfile(id(), "۰۰۱۲۳۴۵۶۷۹", "a1b2c3", listOf(VatRate(JalaliCalendar.date(1406, 1, 1), 1_200), VatRate(JalaliCalendar.date(1404, 1, 1), 1_000))))
        assertEquals("A1B2C3", core.taxStore.profile()!!.memoryId)
        assertEquals(1_000L, core.moadian.readiness(day).vatToday)
        assertEquals(1_200L, core.moadian.readiness(JalaliCalendar.date(1406, 2, 1)).vatToday)

        assertEquals("INVALID_INPUT:productId", assertFailsWith<DomainException> { core.taxOps.setItemCode(SetItemTaxCode(id(), kebab, "123", "")) }.error.code)
        core.taxOps.setItemCode(SetItemTaxCode(id(), kebab, "2720000123456", "1627"))
        assertEquals("INVALID_INPUT:taxId", assertFailsWith<DomainException> { core.taxOps.setCustomerTaxInfo(SetCustomerTaxInfo(id(), branch, company, "10101614562", "")) }.error.code)
        core.taxOps.setCustomerTaxInfo(SetCustomerTaxInfo(id(), branch, company, "10101614561", "1234567890"))
        assertEquals("10101614561", core.overview.customers(branch).single().customer.taxId)

        val ready = core.moadian.readiness(day)
        assertTrue(ready.ready, ready.problems.toString())
        assertEquals("2720000123456", ready.codes.getValue(kebab).productId)
    }
}
