package ir.sabou.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ir.sabou.app.R
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.NavRow
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.SearchHit
import kotlinx.coroutines.delay

/** One box to find anything (ADR-0040). */
@Composable
fun SearchScreen(nav: Nav) {
    val session = LocalSession.current
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    // Search after a short pause in typing, not on every key.
    LaunchedEffect(text) { delay(250); query = text }
    val hits by load(session, query) { if (query.isBlank()) emptyList() else search.find(query) }
    Column(Modifier.fillMaxSize()) {
        Header("جستجو", onBack = nav.back)
        Page {
            TextInput("نام، شماره‌ی فاکتور یا سند، شماره‌ی چک، مبلغ…", text, { text = it })
            Loaded(hits) { list ->
                if (query.isNotBlank() && list.isEmpty()) EmptyState("چیزی پیدا نشد.")
                if (query.isBlank()) Text("مثلاً «کاله»، «فخ-۱۴۰۵-۰۰۰۱۲»، «۱۲۳۴۵»، «۲۵۰۰۰۰۰۰».", style = SabouType.caption, color = Sabou.colors.muted)
                list.forEach { h ->
                    NavRow(icon(h.kind), h.title, h.detail, onClick = { routeOf(h)?.let { nav.go(it) } })
                }
            }
        }
    }
}

private fun icon(kind: SearchHit.Kind): Int = when (kind) {
    SearchHit.Kind.CUSTOMER, SearchHit.Kind.EMPLOYEE -> R.drawable.ic_person
    SearchHit.Kind.SUPPLIER, SearchHit.Kind.PURCHASE_INVOICE, SearchHit.Kind.PURCHASE_ORDER -> R.drawable.ic_purchase
    SearchHit.Kind.ITEM, SearchHit.Kind.STOCK_COUNT -> R.drawable.ic_count
    SearchHit.Kind.MENU_ITEM -> R.drawable.ic_recipe
    SearchHit.Kind.DAILY_SALE -> R.drawable.ic_sales
    SearchHit.Kind.CHEQUE -> R.drawable.ic_payment
    SearchHit.Kind.ASSET, SearchHit.Kind.DOCUMENT -> R.drawable.ic_finance
}

private fun routeOf(h: SearchHit): Route? = when (h.kind) {
    SearchHit.Kind.CUSTOMER -> Route.Customers
    SearchHit.Kind.SUPPLIER -> Route.SupplierAccount(h.id)
    SearchHit.Kind.ITEM -> Route.ItemEdit(h.id)
    SearchHit.Kind.MENU_ITEM -> Route.Menu
    SearchHit.Kind.EMPLOYEE -> Route.StaffDocuments(h.id)
    SearchHit.Kind.PURCHASE_INVOICE -> Route.PurchaseDetail(h.id)
    SearchHit.Kind.PURCHASE_ORDER -> Route.OrderDetail(h.id)
    SearchHit.Kind.STOCK_COUNT -> Route.CountDetail(h.id)
    SearchHit.Kind.DAILY_SALE -> Route.Sales
    SearchHit.Kind.CHEQUE -> Route.ChequeDetail(h.id)
    SearchHit.Kind.ASSET -> Route.AssetDetail(h.id)
    SearchHit.Kind.DOCUMENT -> null
}
