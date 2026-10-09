package ir.sabou.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.sabou.kernel.GlobalId

/** Every page of the app. Tabs are the five roots of the bottom navigation (ADR-0005). */
sealed interface Route {
    sealed interface Tab : Route
    data object Home : Tab
    data object Sales : Tab
    data object Operations : Tab
    data object Finance : Tab
    data object Me : Tab

    // Finance
    data object Receipt : Route
    data object Payment : Route
    data object Transfer : Route
    data object Reconcile : Route
    data object Receivables : Route
    data class Collect(val receivableId: GlobalId) : Route
    data object TrialBalance : Route
    data class AccountHistory(val accountId: GlobalId) : Route

    // Operations
    data object Stock : Route
    data object Waste : Route
    data object Count : Route
    data object StockTransfer : Route
    data object Recipes : Route
    data object Purchases : Route
    data object NewPurchase : Route
    data class PurchaseDetail(val invoiceId: GlobalId) : Route
    data object Suppliers : Route
    data object Personnel : Route
    data object Attendance : Route
    data object Payroll : Route

    // Settings
    data object Branches : Route
    data object Users : Route
    data object Items : Route
    data object Locations : Route
    data object Menu : Route
    data object Accounts : Route
    data object Customers : Route
    data object Policies : Route
    data object Backup : Route
}

/** Process-wide UI state: the signed-in session and the back stack survive activity re-creation. */
class UiState {
    var session by mutableStateOf<AppSession?>(null)
    val stack = mutableStateListOf<Route>(Route.Home)

    val current: Route get() = stack.last()

    fun go(route: Route) {
        if (route is Route.Tab) { stack.clear(); stack.add(route) } else stack.add(route)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun signOut() {
        session?.core?.identity?.logout()
        session?.close()
        session = null
        stack.clear(); stack.add(Route.Home)
    }
}
