package ir.sabou.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.sabou.app.data.AppState
import ir.sabou.app.ui.AppSession
import ir.sabou.app.ui.Drafts
import ir.sabou.app.ui.Route
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
    )

    @Test fun everyPageOpensAndItsDraftCanBeSaved() {
        val container = app.container
        container.factoryReset(null)
        val core = (container.state.value as AppState.Ready).core
        core.bootstrap("شعبه آزمایشی", "مالک", "owner", "123456".toCharArray())
        val actor = checkNotNull(core.session.currentActor())
        val branch = core.identity.accessibleBranches(actor).first().id
        instrumentation.runOnMainSync { app.ui.session = AppSession(core, actor, branch) {} }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (page in pages) {
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
