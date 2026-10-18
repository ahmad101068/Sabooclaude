package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.RecordWaste
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.WasteReason
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.ClosePeriod
import ir.sabou.ledger.ManualLine
import ir.sabou.ledger.PostManualJournal
import ir.sabou.ledger.ReverseManualJournal
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.CollectReceivable
import ir.sabou.sales.CustomerType
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The golden cases of the architecture handoff (master scenario §6, acceptance FIN/INV), run against the core as it
 * is: on a real SQLite file, through the same command bus and stores the app uses. Each case names its handoff ID.
 * They fix the expected numbers so that any later persistence or server (ADR-0056) must reproduce them exactly.
 */
class GoldenCasesTest {
    private val dir = Files.createTempDirectory("sabou-golden")
    private val db = DevDatabases.open(dir.resolve("g.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = BusinessDate(20_000)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private lateinit var branch: Scope.Branch
    private var kitchen = GlobalId.new()
    private var cash = GlobalId.new()
    private var supplier = GlobalId.new()

    private fun setUp() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        cash = core.overview.treasury().single().account.id
        supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "021")).resultId
    }

    private fun count(table: String) = core.db.query("SELECT COUNT(*) AS n FROM $table").single().long("n")
    private fun journal(debit: Long, credit: Long, key: GlobalId = id(), date: BusinessDate = day, memo: String = "تعمیر هود") =
        PostManualJournal(key, branch, date, memo, listOf(
            ManualLine(StandardAccounts.REPAIRS, rial(debit), Money.ZERO), ManualLine(StandardAccounts.LOANS_PAYABLE, Money.ZERO, rial(credit))))
    private fun buy(itemId: GlobalId, qty: Long, value: Long, date: BusinessDate = day, no: String = id().value.take(8)) =
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, no, kitchen, date, date, listOf(InvoiceLine(itemId, Quantity.units(qty), rial(value)))))

    // ---------------------------------------------------------------- ledger

    /** Golden «تراز دفترکل»: 300,000 debit against 300,000 credit posts. */
    @Test fun balancedJournalPosts() {
        setUp()
        val entry = core.accounting.post(journal(300_000, 300_000)).resultId
        assertEquals(300_000, core.ledger.balance(StandardAccounts.REPAIRS, branch).rial)
        assertEquals(-300_000, core.ledger.balance(StandardAccounts.LOANS_PAYABLE, branch).rial)
        assertTrue(core.journals.byId(entry) != null)
    }

    /** Golden «عدم تراز» / FIN-001: 300,000 against 299,999 is refused atomically, with nothing written. */
    @Test fun unbalancedJournalIsRefusedWithoutAnyEffect() {
        setUp()
        val before = listOf("journal_entries", "audit_events", "idempotency", "domain_events").map(::count)
        val e = assertFailsWith<DomainException> { core.accounting.post(journal(300_000, 299_999)) }
        assertEquals("UNBALANCED_JOURNAL", e.error.code)
        assertEquals(before, listOf("journal_entries", "audit_events", "idempotency", "domain_events").map(::count))
        assertEquals(0, core.ledger.balance(StandardAccounts.REPAIRS, branch).rial)
    }

    /** Golden «درخواست تکراری» / FIN-005 and «payload متفاوت» / FIN-006. */
    @Test fun sameKeyReplaysAndADifferentPayloadConflicts() {
        setUp()
        val key = id()
        val first = core.accounting.post(journal(300_000, 300_000, key))
        val entries = count("journal_entries")
        val again = core.accounting.post(journal(300_000, 300_000, key))
        assertTrue(again.replayed)
        assertEquals(first.resultId, again.resultId)                        // the same document, the same answer
        assertEquals(entries, count("journal_entries"))
        val e = assertFailsWith<DomainException> { core.accounting.post(journal(400_000, 400_000, key)) }
        assertEquals("IDEMPOTENCY_CONFLICT", e.error.code)                  // HTTP 409 in a server
        assertEquals(entries, count("journal_entries"))
        assertEquals(300_000, core.ledger.balance(StandardAccounts.REPAIRS, branch).rial)
    }

    /** FIN-002: a document is reversed once; a second reversal, even with a new key, is refused. */
    @Test fun aDocumentIsReversedExactlyOnce() {
        setUp()
        val entry = core.accounting.post(journal(300_000, 300_000)).resultId
        core.accounting.reverse(ReverseManualJournal(id(), branch, entry, day, "ثبت اشتباه"))
        assertEquals(0, core.ledger.balance(StandardAccounts.REPAIRS, branch).rial)
        val e = assertFailsWith<DomainException> { core.accounting.reverse(ReverseManualJournal(id(), branch, entry, day, "دوباره")) }
        assertEquals("INVALID_STATE:JOURNAL:ALREADY_REVERSED", e.error.code)
        assertEquals(0, core.ledger.balance(StandardAccounts.REPAIRS, branch).rial)
    }

    /** FIN-004: posting into a closed period fails closed. */
    @Test fun aClosedPeriodTakesNoPosting() {
        setUp()
        core.accounting.closePeriod(ClosePeriod(id(), day.plusDays(-30), day))
        val before = count("journal_entries")
        val e = assertFailsWith<DomainException> { core.accounting.post(journal(300_000, 300_000)) }
        assertEquals("PERIOD_CLOSED", e.error.code)
        assertEquals(before, count("journal_entries"))
        core.accounting.post(journal(300_000, 300_000, date = day.plusDays(1)))   // the day after is open
    }

    /** Golden «تسهیم صفر»: 100 over weights [0, 0] is a domain error, not a vanished amount (audit P1-03). */
    @Test fun allocatingOverNoWeightsFailsClosed() {
        assertEquals("INVALID_STATE:ALLOCATION:ZERO_WEIGHTS", assertFailsWith<DomainException> { Ratio.allocate(100, listOf(0, 0)) }.error.code)
    }

    // ---------------------------------------------------------------- inventory and cost

    /** Golden «WAC»: 10 × 100 + 20 × 130 → average 120; taking 8 costs 960, leaving 22 worth 2,640. */
    @Test fun movingAverageCost() {
        setUp()
        val item = core.inventory.createItem(CreateItem(id(), "روغن", StockUnit.LITER, Quantity.ZERO)).resultId
        buy(item, 10, 1_000)
        buy(item, 20, 2_600)
        val balance = core.stock.balance(item, kitchen)
        assertEquals(Quantity.units(30), balance.quantity)
        assertEquals(rial(3_600), balance.value)
        core.inventory.waste(RecordWaste(id(), branch, kitchen, item, Quantity.units(8), WasteReason.SPOILAGE, "", day))
        assertEquals(960, core.ledger.balance(StandardAccounts.WASTE, branch).rial)
        val after = core.stock.balance(item, kitchen)
        assertEquals(Quantity.units(22), after.quantity)
        assertEquals(rial(2_640), after.value)
        assertEquals(2_640, core.ledger.balance(StandardAccounts.INVENTORY, branch).rial)   // the books and the stock agree
    }

    /**
     * Golden «سود مشارکت»: 3 portions × 500,000 less a 100,000 discount = 1,400,000 net; 3 × 200,000 ingredients;
     * contribution 800,000 before any other cost.
     */
    @Test fun contributionMargin() {
        setUp()
        val beef = core.inventory.createItem(CreateItem(id(), "گوشت", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val kabab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), kabab, BusinessDate(19_000), listOf(RecipeLine(beef, Quantity.of(200_000)))))
        buy(beef, 5, 5_000_000)                                              // 1,000,000 per kg
        val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(kabab, Quantity.units(3), rial(500_000))),
            rial(100_000), Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, rial(1_400_000))), discountReason = "مشتری ثابت")).resultId
        core.salesOps.post(PostDailySale(id(), branch, sale))
        val p = core.reports.profitAndLoss(day, day, branch)
        assertEquals(1_400_000, p.ratios.sales)
        assertEquals(600_000, p.totals.cogs)
        assertEquals(800_000, p.totals.grossProfit)
        assertEquals(rial(600_000), core.sales.sale(sale)!!.lines.single().cost)   // ADR-0050: the line's own cost
    }

    /**
     * Golden «race مصرف» / INV-001: two sales ask more than is in stock. The second takes what is there at cost and
     * leaves the rest as an open, visible shortage (ADR-0022) — never a hidden negative stock — and the period's
     * profit says it is provisional until the shortage is settled.
     */
    @Test fun sellingBeyondStockIsVisibleNeverHidden() {
        setUp()
        val beef = core.inventory.createItem(CreateItem(id(), "گوشت", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val kabab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), kabab, BusinessDate(19_000), listOf(RecipeLine(beef, Quantity.of(200_000)))))
        buy(beef, 1, 1_000_000)
        fun sell(portions: Long): GlobalId {
            val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(kabab, Quantity.units(portions), rial(500_000))),
                Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, rial(portions * 500_000))))).resultId
            core.salesOps.post(PostDailySale(id(), branch, s)); return s
        }
        sell(4)                                                              // 0.8 kg of 1 kg
        val second = sell(4)                                                 // 0.2 kg left; 0.6 kg short
        assertEquals(Quantity.ZERO, core.stock.balance(beef, kitchen).quantity)
        assertEquals(rial(200_000), core.sales.sale(second)!!.cost)          // only what was there, at cost
        val short = core.overview.openShortages(branch).single()
        assertEquals(Quantity.of(600_000), short.shortage.open)
        assertTrue(core.reports.profitAndLoss(day, day, branch).provisional)
        assertTrue(core.sales.sale(second)!!.lines.single().costShort)
    }

    /** Golden «تغییر قیمت دیرهنگام» / INV-002: a later, dearer purchase leaves the cost already posted unchanged. */
    @Test fun aLaterPriceNeverRewritesPostedCost() {
        setUp()
        val beef = core.inventory.createItem(CreateItem(id(), "گوشت", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val kabab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), kabab, BusinessDate(19_000), listOf(RecipeLine(beef, Quantity.of(200_000)))))
        buy(beef, 5, 5_000_000)
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(kabab, Quantity.units(10), rial(500_000))),
            Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, rial(5_000_000))))).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
        val cogs = core.reports.profitAndLoss(day, day, branch).totals.cogs
        buy(beef, 5, 9_000_000, date = day.plusDays(1))                     // 1,800,000 per kg
        assertEquals(rial(2_000_000), core.sales.sale(s)!!.cost)
        assertEquals(rial(2_000_000), core.sales.sale(s)!!.lines.single().cost)
        assertEquals(cogs, core.reports.profitAndLoss(day, day, branch).totals.cogs)
        assertEquals(2_000_000, cogs)
    }

    // ---------------------------------------------------------------- corporate customer

    /**
     * Golden «مشتری شرکتی»: 50 meals delivered on credit due in 10 days, paid on day 12. The debt and its
     * collection are separate records; on day 12, before payment, it is 2 days past due (1–30 bucket); after, nothing.
     */
    @Test fun corporateDebtAgesAndIsCollectedSeparately() {
        setUp()
        val company = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آلفا", CustomerType.COMPANY, "021", rial(0))).resultId
        val meal = core.inventory.defineMenuItem(DefineMenuItem(id(), "ناهار")).resultId
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(meal, Quantity.units(50), rial(1_000_000))),
            Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Credit(company, rial(50_000_000), day.plusDays(10))))).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
        val r = core.sales.receivablesOfSale(s).single()
        assertEquals(50_000_000, core.ledger.balance(StandardAccounts.RECEIVABLE, branch).rial)
        assertEquals(0, core.overview.treasury().single().balance)          // a sale on credit brings no cash
        val aging = core.ledgerReads.receivablesAging(day.plusDays(12)).single()
        assertEquals(50_000_000, aging.buckets[1])
        assertEquals(50_000_000, aging.total)
        core.salesOps.collect(CollectReceivable(id(), branch, r.id, cash, rial(50_000_000), day.plusDays(12)))
        assertEquals(1, core.sales.collections(r.id).size)
        assertEquals(0, core.salesOps.outstanding(r.id).rial)
        assertEquals(0, core.ledger.balance(StandardAccounts.RECEIVABLE, branch).rial)
        assertEquals(50_000_000, core.overview.treasury().single().balance)
        assertTrue(core.ledgerReads.receivablesAging(day.plusDays(12)).isEmpty())
    }
}
