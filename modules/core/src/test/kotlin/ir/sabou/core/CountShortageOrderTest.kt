package ir.sabou.core

// Regression for SABOU-AUDIT-009 (independent audit 1405/08/06): a count writes off only the shortages it measured.
// Question: when a stock count is approved, does it write off only the consumption shortages it actually measured
// (those that existed when the shelf was counted), or also shortages that arose after the count was taken?

import ir.sabou.inventory.ApproveStockCount
import ir.sabou.inventory.CountEntry
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.SubmitStockCount
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class CountShortageOrderTest {
    private val today = Fa.fromJalali(1405, 8, 6)
    private val now = today.epochDay * 86_400_000L + 9 * 3_600_000L
    private fun id() = GlobalId.new()

    private fun run(withCount: Boolean): String {
        val dir = Files.createTempDirectory("probe-count")
        val db = DevDatabases.open(dir.resolve("c.db"))
        val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now }, emptyList(), null, datePolicy = SabouCore.restaurantDatePolicy())
        core.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val meat = core.inventory.createItem(CreateItem(id(), "گوشت", StockUnit.KILOGRAM, Quantity.units(0))).resultId
        val kebab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), kebab, BusinessDate(19_000), listOf(RecipeLine(meat, Quantity.units(1)))))
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "قصابی", "021")).resultId
        // A previous delivery priced the meat at 2,000,000 per kg (the write-off's estimate), then all of it was used.
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "OLD", kitchen, today.plusDays(-3), today.plusDays(-3), listOf(InvoiceLine(meat, Quantity.units(1), Money.of(2_000_000)))))
        fun sell(kg: Long) { val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, today, kitchen, listOf(SaleLineInput(kebab, Quantity.units(kg), Money.of(5_000_000))),
            Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, Money.of(5_000_000 * kg))))).resultId; core.salesOps.post(PostDailySale(id(), branch, s)) }
        sell(1)                                                                       // shelf now empty, book 0
        var countId: GlobalId? = null
        if (withCount) {
            // 09:00 the storekeeper counts the meat: 0 kg, as the book says (no difference).
            core.identity.createUser("store", "انباردار", Role.STOREKEEPER, setOf(branch.branchId), "222222".toCharArray())
            core.identity.login("store", "222222".toCharArray())
            countId = core.counts.submit(SubmitStockCount(id(), branch, kitchen, today, listOf(CountEntry(meat, Quantity.ZERO)))).resultId
            core.identity.login("owner", "123456".toCharArray())
        }
        // 13:00 lunch: 10 kebabs sold before the morning delivery is booked -> open shortage of 10 kg.
        sell(10)
        // 15:00 the owner approves the morning count.
        countId?.let { core.counts.approve(ApproveStockCount(id(), branch, it, emptyMap())) }
        // 16:00 the 10 kg delivered this morning are booked.
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "NEW", kitchen, today, today, listOf(InvoiceLine(meat, Quantity.units(10), Money.of(25_000_000)))))
        val bal = core.stock.balance(meat, kitchen)
        val r = "withCount=$withCount -> book stock after the delivery = ${bal.quantity.micros / 1_000_000} kg (value ${bal.value.rial}); the 10 kg were already eaten at lunch; shortages=" +
            core.shortages.openAt(setOf(kitchen)).size + " open"
        db.close(); dir.toFile().deleteRecursively()
        return r
    }

    @Test fun aCountDoesNotWriteOffAShortageThatAroseAfterIt() {
        assertTrue(run(false).contains("= 0 kg (value 0)"), run(false))
        assertTrue(run(true).contains("= 0 kg (value 0)"), run(true))
    }
}
