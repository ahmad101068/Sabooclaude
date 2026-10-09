package ir.sabou.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.sabou.app.data.AppState
import ir.sabou.app.ui.AppSession
import ir.sabou.app.ui.Drafts
import ir.sabou.app.ui.Route
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens every page of the app as the owner on a real device and saves its form state the way going to
 * the background does: a page that crashes, or holds state that cannot be saved, fails here.
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SabouApplication

    private val pages: List<Route> = listOf(
        Route.Home, Route.Sales, Route.Operations, Route.Finance, Route.Me,
        Route.Receipt, Route.Payment, Route.Transfer, Route.Reconcile, Route.Receivables, Route.TrialBalance,
        Route.Stock, Route.Waste, Route.Count, Route.StockTransfer, Route.Recipes, Route.Purchases, Route.NewPurchase,
        Route.Suppliers, Route.Personnel, Route.Attendance, Route.Payroll,
        Route.Branches, Route.Users, Route.Items, Route.Locations, Route.Menu, Route.Accounts, Route.Customers, Route.Policies, Route.Backup,
        Route.Reports, Route.ProfitLoss, Route.DayFlash, Route.ProductMix, Route.Usage, Route.AttendanceReport,
        Route.PrepRecipes, Route.Production,
        Route.Orders, Route.NewOrder(), Route.ReviewQueue, Route.PriceChanges, Route.Suggestions,
    )

    @Test fun everyPageOpensAndItsDraftCanBeSaved() {
        val container = app.container
        container.factoryReset(null)
        val core = (container.state.value as AppState.Ready).core
        core.bootstrap("شعبه آزمایشی", "مالک", "owner", "123456".toCharArray())
        val actor = checkNotNull(core.session.currentActor())
        val branch = core.identity.accessibleBranches(actor).first().id
        instrumentation.runOnMainSync { app.ui.session = AppSession(core, actor, branch) {} }
        val itemId = GlobalId.new()
        core.inventory.createItem(CreateItem(itemId, "پیاز", StockUnit.KILOGRAM, Quantity.ZERO))
        core.inventory.createItem(CreateItem(GlobalId.new(), "سس مخصوص", StockUnit.KILOGRAM, Quantity.ZERO, prepared = true))
        val today = java.time.LocalDate.now().toEpochDay()
        // Purchasing documents so the detail pages have something to show.
        val scope = ir.sabou.kernel.Scope.Branch(branch)
        val location = core.overview.locations(scope).first().id
        val supplier = core.purchasing.registerSupplier(ir.sabou.purchasing.RegisterSupplier(GlobalId.new(), "لبنیات", "021")).resultId
        val order = core.orders.create(ir.sabou.purchasing.CreatePurchaseOrder(GlobalId.new(), scope, supplier, location, ir.sabou.kernel.BusinessDate(today),
            ir.sabou.kernel.BusinessDate(today + 1), listOf(ir.sabou.purchasing.OrderLine(itemId, Quantity.units(2), ir.sabou.kernel.Money.of(100_000))))).resultId
        val invoice = core.purchasing.postInvoice(ir.sabou.purchasing.PostPurchaseInvoice(GlobalId.new(), scope, supplier, "1", location, ir.sabou.kernel.BusinessDate(today),
            ir.sabou.kernel.BusinessDate(today), listOf(ir.sabou.purchasing.InvoiceLine(itemId, Quantity.units(1), ir.sabou.kernel.Money.of(50_000))),
            reviewLines = listOf(ir.sabou.purchasing.ReviewLine("دستکش", "۲ بسته", ir.sabou.kernel.Money.of(20_000))),
            attachments = listOf(ir.sabou.platform.AttachmentInput("f.jpg", "image/jpeg", ByteArray(500) { 1 })))).resultId
        val allPages = pages + listOf(
            Route.ItemEdit(itemId), Route.LedgerDetail("4101", today - 30, today, null),
            Route.PurchaseDetail(invoice), Route.OrderDetail(order), Route.PurchaseFromOrder(order), Route.SupplierEdit(supplier),
            Route.NewOrder(supplier, location, listOf(ir.sabou.app.ui.OrderDraftLine(itemId, 2_000_000, 100_000))),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (page in allPages) {
                scenario.onActivity { app.ui.go(page) }
                instrumentation.waitForIdleSync()
                Thread.sleep(600)                                    // let the page load its data
                instrumentation.waitForIdleSync()
                var saved = 0
                scenario.onActivity {
                    val draft = checkNotNull(app.ui.draftSnapshot(System.currentTimeMillis()))
                    saved = Drafts.marshall(draft).size
                }
                assertTrue("draft of $page", saved > 0)
            }
        }
        instrumentation.runOnMainSync { app.ui.signOut() }
    }
}
