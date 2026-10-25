package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.DatePolicy
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.TreasuryKind
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0065: the fixes of the October 2026 audit (SABOU-AUDIT-002, 003, 008, 011), each as found by its probe. */
class AuditRemediationTest {
    private val dir = Files.createTempDirectory("sabou-remedy")
    private val db = DevDatabases.open(dir.resolve("r.db"))
    private var now = Fa.fromJalali(1404, 8, 20).epochDay * 86_400_000L + 9 * 3_600_000L     // 20 Aban 1404, 12:30 in Tehran
    private val clock = Clock { now }
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private val today = Fa.fromJalali(1404, 8, 20)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private fun open(policy: DatePolicy?) = SabouCore.open(db, InMemoryAnchorStore(), clock, emptyList(), null, datePolicy = policy)

    private class Shop(val core: SabouCore, val branch: Scope.Branch, val kitchen: GlobalId, val cash: GlobalId, val meat: GlobalId, val kebab: GlobalId, val supplier: GlobalId)

    private fun shop(core: SabouCore): Shop {
        core.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val meat = core.inventory.createItem(CreateItem(id(), "گوشت", StockUnit.KILOGRAM, Quantity.units(0))).resultId
        val kebab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), kebab, BusinessDate(19_000), listOf(RecipeLine(meat, Quantity.units(1)))))
        return Shop(core, branch, core.overview.locations(branch).single().id, core.overview.treasury().single().account.id, meat, kebab,
            core.purchasing.registerSupplier(RegisterSupplier(id(), "قصابی", "021")).resultId)
    }

    private fun Shop.sell(date: BusinessDate, portions: Long = 10): GlobalId {
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, date, kitchen, listOf(SaleLineInput(kebab, Quantity.units(portions), rial(5_000_000))),
            rial(0), rial(0), rial(0), listOf(Settlement.Liquid(cash, rial(5_000_000 * portions))))).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
        return s
    }

    private fun Shop.buy(date: BusinessDate, kilos: Long = 10, number: String = id().value.take(6)) =
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, number, kitchen, date, date, listOf(InvoiceLine(meat, Quantity.units(kilos), rial(2_000_000 * kilos)))))

    // ------------------------------------------------------------ SABOU-AUDIT-002

    @Test fun theDatePolicyRefusesTheFutureAndTheFarPastWithoutBackdate() {
        val s = shop(open(SabouCore.restaurantDatePolicy()))
        s.buy(today.plusDays(-1), 100)
        assertEquals("INVALID_INPUT:date", code { s.sell(today.plusDays(1)) })
        assertEquals("INVALID_INPUT:date", code { s.sell(today.plusDays(400)) })
        s.sell(today)                                                  // today is fine
        s.sell(today.plusDays(-200), 1)                                // the owner may backdate (BACKDATE)
        // A storekeeper records purchases inside the window only.
        s.core.identity.createUser("store", "انباردار", Role.STOREKEEPER, setOf(s.branch.branchId), "222222".toCharArray())
        s.core.identity.login("store", "222222".toCharArray())
        s.buy(today.plusDays(-45), 1)
        assertEquals("INVALID_INPUT:date", code { s.buy(today.plusDays(-46), 1) })
        assertEquals("INVALID_INPUT:date", code { s.buy(today.plusDays(1), 1) })
    }

    @Test fun periodEndDocumentsMayCarryTheEndOfThisMonth() {
        val p = SabouCore.restaurantDatePolicy()
        val millis = now
        assertEquals(today, p.latest(millis, "SALE"))
        assertEquals(Fa.fromJalali(1404, 8, 30), p.latest(millis, ir.sabou.payroll.PayrollOperations.RUN))
        assertEquals(Fa.fromJalali(1404, 8, 30), p.latest(millis, ir.sabou.assets.AssetOperations.DEPRECIATION))
        // Iran's day: 23:00 UTC on the 19th is already the 20th in Tehran.
        assertEquals(today, p.today(Fa.fromJalali(1404, 8, 19).epochDay * 86_400_000L + 23 * 3_600_000L))
    }

    @Test fun withoutAPolicyOnlyClosedPeriodsLimitTheDate() {
        val s = shop(open(null))
        s.buy(today.plusDays(300), 100)
        s.sell(today.plusDays(300), 1)
    }

    // ------------------------------------------------------------ SABOU-AUDIT-003

    @Test fun theFoodCostOfAShortageSaleStaysInTheMonthOfTheSale() {
        val s = shop(open(SabouCore.restaurantDatePolicy()))
        val sale = Fa.fromJalali(1404, 7, 28)
        s.sell(sale)                                                   // 10 kebabs, no meat in the books
        val mehr = s.core.reports.profitAndLoss(Fa.fromJalali(1404, 7, 1), Fa.fromJalali(1404, 7, 30), s.branch)
        assertTrue(mehr.provisional)
        s.buy(Fa.fromJalali(1404, 8, 5))                               // the meat invoice, recorded next month
        val after = s.core.reports.profitAndLoss(Fa.fromJalali(1404, 7, 1), Fa.fromJalali(1404, 7, 30), s.branch)
        val aban = s.core.reports.profitAndLoss(Fa.fromJalali(1404, 8, 1), Fa.fromJalali(1404, 8, 30), s.branch)
        assertEquals(20_000_000, after.ratios.foodCost)                // the sale's month has its food cost
        assertFalse(after.provisional)
        assertEquals(0, aban.ratios.foodCost)
        // Stock and the inventory account still agree, and the books balance.
        assertEquals(s.core.stock.balance(s.meat, s.kitchen).value.rial, s.core.ledger.balance(ir.sabou.ledger.StandardAccounts.INVENTORY, s.branch).rial)
        assertEquals(0L, s.core.overview.trialBalance().sumOf { it.second })
    }

    @Test fun aShortageOlderThanThePersonsWindowIsCostedWhenTheGoodsComeIn() {
        val s = shop(open(SabouCore.restaurantDatePolicy()))
        val old = today.plusDays(-60)
        s.sell(old)
        s.core.identity.createUser("store", "انباردار", Role.STOREKEEPER, setOf(s.branch.branchId), "222222".toCharArray())
        s.core.identity.login("store", "222222".toCharArray())
        s.buy(today)                                                   // the storekeeper may not post 60 days back
        s.core.identity.login("owner", "123456".toCharArray())
        assertEquals(20_000_000, s.core.reports.profitAndLoss(today, today, s.branch).ratios.foodCost)
        assertEquals(0, s.core.reports.profitAndLoss(old, old, s.branch).ratios.foodCost)
    }

    @Test fun aMonthWithAnOpenShortageCannotBeClosed() {
        val s = shop(open(null))
        s.sell(Fa.fromJalali(1404, 6, 20))
        val check = s.core.closing.checklist(1404, 6).checks.single { it.key == "SHORTAGES" }
        assertEquals(CloseCheck.Severity.BLOCK, check.severity)
        assertTrue(code { s.core.closing.close(id(), 1404, 6) }.startsWith("INVALID_STATE:PERIOD:CHECKLIST:"))
    }

    // ------------------------------------------------------------ SABOU-AUDIT-008

    @Test fun aCashierSeesTheCashBoxesOnly() {
        val s = shop(open(null))
        val bank = s.core.treasury.openAccount(OpenTreasuryAccount(id(), s.branch, "بانک ملت", TreasuryKind.BANK)).resultId
        s.core.identity.createUser("cashier", "صندوق", Role.CASHIER, setOf(s.branch.branchId), "111111".toCharArray())
        s.core.identity.login("cashier", "111111".toCharArray())
        assertEquals(listOf(s.cash), s.core.overview.treasury().map { it.account.id })
        assertTrue(code { s.core.overview.accountHistory(bank) }.startsWith("PERMISSION_DENIED"))
        assertTrue(code { s.core.books.cheques() }.startsWith("PERMISSION_DENIED"))
        assertTrue(s.core.overview.paymentAccounts().any { it.id == bank })   // a bank account can still be chosen to settle a sale
        s.core.identity.login("owner", "123456".toCharArray())
        assertEquals(setOf(s.cash, bank), s.core.overview.treasury().map { it.account.id }.toSet())
    }

    // ------------------------------------------------------------ SABOU-AUDIT-011

    @Test fun whoSeesProfitSignsOutAfterTenIdleMinutesOthersAfterThirty() {
        val s = shop(open(null))
        s.core.identity.createUser("cashier", "صندوق", Role.CASHIER, setOf(s.branch.branchId), "111111".toCharArray())
        now += 11 * 60_000L
        assertNull(s.core.session.currentActor())                      // the owner: signed out after 10 minutes
        s.core.identity.login("cashier", "111111".toCharArray())
        now += 11 * 60_000L
        assertEquals("صندوق", s.core.session.currentActor()?.displayName)
        now += 31 * 60_000L
        assertNull(s.core.session.currentActor())
    }
}
