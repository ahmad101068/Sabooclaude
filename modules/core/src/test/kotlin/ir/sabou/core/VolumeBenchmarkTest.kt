package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A year of a busy restaurant on a real SQLite file, then the reads people wait for (P0-4). Off by default
 * (minutes to build); run with SABOU_BENCH=<days>. Each read must stay under its budget.
 */
class VolumeBenchmarkTest {
    @Test fun aYearOfDataStaysFast() {
        val days = System.getenv("SABOU_BENCH")?.toIntOrNull() ?: return
        val dir = Files.createTempDirectory("bench")
        val db = DevDatabases.open(dir.resolve("b.db"))
        val start = BusinessDate(20_000)
        var now = 1_700_000_000_000L
        val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now })
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val cash = core.overview.treasury().single().account.id
        val kitchen = core.overview.locations(branch).single().id
        val items = (1..60).map { core.inventory.createItem(CreateItem(GlobalId.new(), "کالا $it", StockUnit.KILOGRAM, Quantity.units(5))).resultId }
        val dishes = (1..40).map { d ->
            core.inventory.defineMenuItem(DefineMenuItem(GlobalId.new(), "غذا $d")).resultId.also { id ->
                core.inventory.publishRecipe(PublishRecipe(GlobalId.new(), id, BusinessDate(19_000), (0..3).map { RecipeLine(items[(d * 7 + it * 11) % items.size], Quantity.of(50_000)) }))
            }
        }
        val suppliers = (1..8).map { core.purchasing.registerSupplier(RegisterSupplier(GlobalId.new(), "تأمین‌کننده $it", "021")).resultId }
        val built = System.nanoTime()
        for (d in 0 until days) {
            val day = start.plusDays(d.toLong())
            now += 86_400_000L
            core.identity.login("owner", "123456".toCharArray())   // a new working day, a new sign-in
            if (d % 2 == 0) suppliers.forEachIndexed { i, s ->
                core.purchasing.postInvoice(PostPurchaseInvoice(GlobalId.new(), branch, s, "$d-$i", kitchen, day, day,
                    (0..4).map { k -> InvoiceLine(items[(i * 5 + k + d) % items.size], Quantity.units(8), Money.of(1_000_000L + (d % 30) * 10_000)) }))
            }
            repeat(SALES_A_DAY) { n ->
                val lines = (0..2).map { k -> SaleLineInput(dishes[(n * 3 + k + d) % dishes.size], Quantity.units(1 + (n % 3).toLong()), Money.of(500_000)) }
                    .distinctBy { it.menuItemId }
                val total = lines.sumOf { 500_000L * it.portions.micros / Quantity.SCALE }
                val id = core.salesOps.saveDraft(SaveSaleDraft(GlobalId.new(), branch, day, kitchen, lines, Money.ZERO, Money.ZERO, Money.ZERO,
                    listOf(Settlement.Liquid(cash, Money.of(total))))).resultId
                core.salesOps.post(PostDailySale(GlobalId.new(), branch, id))
            }
        }
        println("BENCH built $days days in ${(System.nanoTime() - built) / 1_000_000} ms")
        val today = start.plusDays(days - 1L)
        val owner = core.overview.me().userId
        fun timed(name: String, budgetMs: Long, block: () -> Any?) {
            block()                                                       // warm
            val t = System.nanoTime(); block(); val ms = (System.nanoTime() - t) / 1_000_000
            println("BENCH $name: $ms ms (budget $budgetMs)")
            assertTrue(ms <= budgetMs, "$name took $ms ms")
        }
        timed("home dashboard", 800) { core.dashboards.of(today, branch) }
        timed("p&l year", 1500) { core.reports.profitAndLoss(start, today, branch) }
        timed("day flash", 300) { core.reports.dayFlash(branch, today) }
        timed("sales day", 300) { core.overview.salesDay(branch, today) }
        timed("price changes", 800) { core.buying.priceChanges(today.plusDays(-7), today, 1_000) }
        timed("last paid", 800) { core.buying.lastPaid(branch) }
        timed("low stock", 500) { core.overview.lowStock() }
        timed("invoices list", 800) { core.overview.invoices() }
        timed("alerts", 1500) { core.alerts.forUser(owner, today) }
        timed("product mix month", 1500) { core.reports.productMix(branch, today.plusDays(-30), today) }
        db.close(); dir.toFile().deleteRecursively()
    }

    private companion object { const val SALES_A_DAY = 25 }
}
