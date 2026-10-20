package ir.sabou.core

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
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.StartupVerdict
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.ImmediatePayment
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.purchasing.PaySupplierInvoice
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ReversePurchaseInvoice
import ir.sabou.sales.CollectReceivable
import ir.sabou.sales.CustomerType
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.ReverseDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Audit 2026-10 (ADR-0063): hundreds of random operations — purchases (paid now, later, partly), cash and credit sales,
 * collections, supplier payments, waste, reversals of sales and invoices — and after every one the books must agree
 * with themselves: the trial balance nets to zero, the inventory account equals the stock's value, receivables,
 * payables and cash equal their sub-ledgers. A refused command (DomainException) is a legitimate outcome; anything
 * else thrown is a defect. At the end the audit chain verifies and every report reads the whole history.
 */
class InvariantFuzzTest {

    @Test fun theBooksAgreeWithThemselvesAfterAnySequence() {
        listOf(7L, 20261020L, 424242L).forEach { run(it, OPERATIONS) }
    }

    private fun run(seed: Long, operations: Int) {
        val rnd = Random(seed)
        val dir = Files.createTempDirectory("sabou-fuzz")
        val db = DevDatabases.open(dir.resolve("f.db"))
        var now = 1_700_000_000_000L
        val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now }, emptyList(), null)
        try {
            core.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
            val branch = Scope.Branch(core.overview.branches().single().id)
            val kitchen = core.overview.locations(branch).single().id
            val cash = core.overview.treasury().single().account.id
            fun id() = GlobalId.new()
            val items = (1..6).map { core.inventory.createItem(CreateItem(id(), "کالا $it", StockUnit.KILOGRAM, Quantity.units(1))).resultId }
            val dishes = (1..5).map { d ->
                core.inventory.defineMenuItem(DefineMenuItem(id(), "غذا $d")).resultId.also { m ->
                    core.inventory.publishRecipe(PublishRecipe(id(), m, BusinessDate(19_000),
                        listOf(RecipeLine(items[d % 6], Quantity.of(100_000L + 50_000L * d)), RecipeLine(items[(d + 2) % 6], Quantity.of(70_000)))))
                }
            }
            val suppliers = (1..3).map { core.purchasing.registerSupplier(RegisterSupplier(id(), "تأمین $it", "021")).resultId }
            val customers = (1..2).map { core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت $it", CustomerType.COMPANY, "021", Money.of(10_000_000_000))).resultId }
            var day = BusinessDate(20_000)
            val log = ArrayList<String>()

            fun attempt(name: String, block: () -> Unit) {
                try { block(); log += name } catch (e: DomainException) { log += "$name refused ${e.error.code}" } catch (e: Throwable) {
                    fail("seed $seed, step ${log.size} «$name» threw ${e::class.simpleName}: ${e.message}\n" + log.takeLast(8).joinToString("\n"))
                }
            }

            fun check(step: Int) {
                fun where() = "seed $seed, step $step (${log.lastOrNull()})"
                val trial = core.overview.trialBalance().sumOf { it.second }
                assertEquals(0L, trial, "trial balance off at ${where()}")
                val places = core.locations.all().filter { it.scope == branch }.map { it.id }
                val stockValue = places.sumOf { loc -> core.stock.balances(loc).sumOf { it.value.rial } }
                assertEquals(stockValue, core.ledger.balance(StandardAccounts.INVENTORY, branch).rial, "inventory account ≠ stock value at ${where()}")
                val receivables = core.overview.openReceivables().filter { it.receivable.scope == branch }.sumOf { it.outstanding.rial }
                assertEquals(receivables, core.ledger.balance(StandardAccounts.RECEIVABLE, branch).rial, "receivables ≠ sub-ledger at ${where()}")
                val payables = core.overview.invoices().filter { it.invoice.scope == branch && it.invoice.status == InvoiceStatus.POSTED }.sumOf { it.outstanding.rial }
                assertEquals(payables, -core.ledger.balance(StandardAccounts.PAYABLE, branch).rial, "payables ≠ open invoices at ${where()}")
                assertEquals(core.treasuryGateway.balance(cash), core.ledger.balance(StandardAccounts.CASH, branch).rial, "cash box ≠ cash account at ${where()}")
            }

            for (step in 1..operations) {
                if (rnd.nextInt(8) == 0) { day = day.plusDays(1); now += 86_400_000L; core.identity.login("owner", "123456".toCharArray()) }
                when (rnd.nextInt(9)) {
                    0, 1 -> attempt("purchase") {
                        val lines = (0..rnd.nextInt(2)).map { InvoiceLine(items[rnd.nextInt(6)], Quantity.of(rnd.nextLong(200_000, 5_000_000)), Money.of(rnd.nextLong(100_000, 9_000_000))) }
                            .distinctBy { it.itemId }
                        val total = lines.sumOf { it.value.rial }
                        val pay = when (rnd.nextInt(3)) { 0 -> ImmediatePayment(cash, Money.of(total)); 1 -> ImmediatePayment(cash, Money.of(rnd.nextLong(1, total))); else -> null }
                        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, suppliers[rnd.nextInt(3)], "F$step", kitchen, day, day.plusDays(rnd.nextLong(0, 20)), lines, payNow = pay))
                    }
                    2, 3 -> attempt("cash sale") {
                        val lines = (0..rnd.nextInt(2)).map { SaleLineInput(dishes[rnd.nextInt(5)], Quantity.units(rnd.nextLong(1, 4)), Money.of(rnd.nextLong(1, 30) * 100_000)) }.distinctBy { it.menuItemId }
                        val total = lines.sumOf { it.unitPrice!!.rial * it.portions.micros / Quantity.SCALE }
                        val discount = if (rnd.nextInt(4) == 0) rnd.nextLong(0, total / 5 + 1) else 0L
                        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, lines, Money.of(discount), Money.ZERO, Money.ZERO,
                            listOf(Settlement.Liquid(cash, Money.of(total - discount))), discountReason = if (discount > 0) "آزمون" else "")).resultId
                        core.salesOps.post(PostDailySale(id(), branch, s))
                    }
                    4 -> attempt("credit sale") {
                        val lines = listOf(SaleLineInput(dishes[rnd.nextInt(5)], Quantity.units(rnd.nextLong(1, 6)), Money.of(rnd.nextLong(1, 30) * 100_000)))
                        val total = lines.sumOf { it.unitPrice!!.rial * it.portions.micros / Quantity.SCALE }
                        val part = rnd.nextLong(0, total + 1)
                        val settlements = listOfNotNull(Settlement.Liquid(cash, Money.of(part)).takeIf { part > 0 },
                            Settlement.Credit(customers[rnd.nextInt(2)], Money.of(total - part), day.plusDays(12)).takeIf { total - part > 0 })
                        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, lines, Money.ZERO, Money.ZERO, Money.ZERO, settlements)).resultId
                        core.salesOps.post(PostDailySale(id(), branch, s))
                    }
                    5 -> attempt("collect") {
                        val r = core.overview.openReceivables().filter { it.receivable.scope == branch }.randomOrNull(rnd) ?: return@attempt
                        core.salesOps.collect(CollectReceivable(id(), branch, r.receivable.id, cash, Money.of(rnd.nextLong(1, r.outstanding.rial + 1)), day))
                    }
                    6 -> attempt("pay supplier") {
                        val inv = core.overview.invoices().filter { it.invoice.scope == branch && it.invoice.status == InvoiceStatus.POSTED && !it.outstanding.isZero }.randomOrNull(rnd) ?: return@attempt
                        core.purchasing.payInvoice(PaySupplierInvoice(id(), branch, inv.invoice.id, cash, Money.of(rnd.nextLong(1, inv.outstanding.rial + 1)), day))
                    }
                    7 -> attempt("waste") {
                        core.inventory.waste(RecordWaste(id(), branch, kitchen, items[rnd.nextInt(6)], Quantity.of(rnd.nextLong(10_000, 400_000)), WasteReason.SPOILAGE, "", day))
                    }
                    else -> if (rnd.nextBoolean()) attempt("reverse sale") {
                        val s = core.sales.sales(branch, BusinessDate(19_990), day).filter { it.status == SaleStatus.POSTED }.randomOrNull(rnd) ?: return@attempt
                        core.salesOps.reverse(ReverseDailySale(id(), branch, s.id, day, "آزمون"))
                    } else attempt("reverse invoice") {
                        val inv = core.purchases.invoices().filter { it.scope == branch && it.status == InvoiceStatus.POSTED }.randomOrNull(rnd) ?: return@attempt
                        core.purchasing.reverseInvoice(ReversePurchaseInvoice(id(), branch, inv.id, day, "آزمون"))
                    }
                }
                check(step)
            }

            // The whole history: the audit chain, and every report reads it without failing.
            assertEquals(StartupVerdict.Healthy, core.verifyAuditFull(), "seed $seed")
            val start = BusinessDate(20_000)
            val a = core.assessments.of(branch, start, day)
            assertTrue(a.score == null || a.score in 0..100, "seed $seed score ${a.score}")
            assertTrue(a.kpis.all { k -> k.score == null || k.score in 0..100 }, "seed $seed")
            val mid = BusinessDate((start.epochDay + day.epochDay) / 2)
            if (mid > start) {
                val b = core.profitBridges.of(branch, mid, day, start, mid.plusDays(-1))
                assertEquals(b.change, b.steps.sumOf { it.amount }, "seed $seed: bridge steps ≠ change")
            }
            core.whatIfs.prices(branch, start, day, mapOf(items.first() to 1_500L))
            core.cashFlows.forecast(branch, day)
            core.reports.actualVsTheoretical(branch, null, start, day)
            println("FUZZ seed $seed: ${log.size} operations, refused: " + log.filter { "refused" in it }.groupingBy { it }.eachCount())
        } finally {
            db.close(); dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        val OPERATIONS = System.getenv("SABOU_FUZZ")?.toIntOrNull() ?: 150
    }
}
