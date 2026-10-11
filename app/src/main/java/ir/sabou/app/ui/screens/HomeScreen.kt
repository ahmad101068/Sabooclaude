package ir.sabou.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.NavRow
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouShapes
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.Settlement
import ir.sabou.treasury.TreasuryKind

private data class Today(
    val net: Long?,
    val status: String,
    val statusKind: ChipKind,
    val cash: Long,
    val card: Long,
    val credit: Long,
)

private data class Todo(val icon: Int, val title: String, val detail: String, val route: Route, val warn: Boolean)

private data class HomeData(val today: Today?, val todos: List<Todo>, val setupMissing: List<Pair<String, Route>>, val dashboard: ir.sabou.core.Dashboard? = null)

@Composable
fun HomeScreen(nav: Nav) {
    val session = LocalSession.current
    val branch = session.branch
    val data by load(session, branch) {
        val date = session.today
        // A role without a permission simply sees less; other failures still surface.
        fun <T> safe(block: () -> T): T? = try { block() } catch (e: ir.sabou.kernel.DomainException) {
            if (e.error is ir.sabou.kernel.DomainError.PermissionDenied || e.error is ir.sabou.kernel.DomainError.ScopeDenied) null else throw e
        }

        val accounts = (safe { overview.paymentAccounts() } ?: emptyList()).associateBy { it.id }
        val today = branch?.let { b ->
            safe { overview.today(date).firstOrNull { Scope.Branch(it.branch.id) == b } }?.let { day ->
                val sale = day.sale
                val liquid = sale?.settlements?.filterIsInstance<Settlement.Liquid>().orEmpty()
                Today(
                    net = sale?.netFood?.rial,
                    status = when {
                        day.closed -> "روز بسته"
                        sale == null -> "ثبت نشده"
                        sale.status == SaleStatus.POSTED -> "ثبت نهایی"
                        else -> "پیش‌نویس"
                    },
                    statusKind = if (day.closed) ChipKind.NEUTRAL else ChipKind.ACCENT,
                    cash = liquid.filter { accounts[it.treasuryAccountId]?.kind.let { k -> k == TreasuryKind.CASH || k == TreasuryKind.PETTY_CASH } }.sumOf { it.amount.rial },
                    card = liquid.filter { accounts[it.treasuryAccountId]?.kind.let { k -> k == TreasuryKind.CARD_TERMINAL || k == TreasuryKind.BANK } }.sumOf { it.amount.rial },
                    credit = sale?.settlements?.filterIsInstance<Settlement.Credit>().orEmpty().sumOf { it.amount.rial },
                )
            }
        }

        val todos = buildList {
            if (branch != null) {
                val yesterday = date.plusDays(-1)
                safe { overview.today(yesterday).firstOrNull { Scope.Branch(it.branch.id) == branch } }?.let { d ->
                    if (d.sale?.status == SaleStatus.POSTED && !d.closed) {
                        add(Todo(R.drawable.ic_clock, "بستن روز فروش دیروز", "شمارش صندوق و بستن روز مانده است", Route.Sales, true))
                    }
                }
            }
            safe { overview.lowStock() }?.takeIf { it.isNotEmpty() }?.let { low ->
                val names = low.take(2).joinToString("، ") { it.item.name } + if (low.size > 2) " و ${Fa.number(low.size - 2L)} مورد دیگر" else ""
                add(Todo(R.drawable.ic_alert, "${Fa.number(low.size.toLong())} کالا زیر حداقل موجودی", names, Route.Stock, true))
            }
            safe {
                overview.invoices().filter { it.invoice.status == InvoiceStatus.POSTED && it.invoice.dueDate <= date.plusDays(7) }
                    .map { it.outstanding }.filter { !it.isZero }
            }?.takeIf { it.isNotEmpty() }?.let { due ->
                add(Todo(R.drawable.ic_calendar, "${Fa.number(due.size.toLong())} فاکتور خرید سررسید این هفته", "جمع ${Fa.rial(Money.sum(due))} ریال", Route.Purchases, false))
            }
            safe { books.chequesDue(date, 7) }?.takeIf { it.isNotEmpty() }?.let { cheques ->
                val overdue = cheques.count { it.cheque.dueDate < date }
                add(Todo(R.drawable.ic_payment, "${Fa.number(cheques.size.toLong())} چک سررسید این هفته" + if (overdue > 0) " (${Fa.number(overdue.toLong())} گذشته)" else "",
                    "جمع ${Fa.rial(cheques.sumOf { it.cheque.amount.rial })} ریال", Route.Cheques, overdue > 0))
            }
            if (session.actor.role.allows(Permission.PURCHASE_APPROVE)) safe { books.pendingApprovals() }?.takeIf { it.isNotEmpty() }?.let { list ->
                add(Todo(R.drawable.ic_check, "${Fa.number(list.size.toLong())} فاکتور در انتظار تأیید شما", "پیش از پرداخت تأیید لازم است", Route.PendingApprovals, false))
            }
            if (branch != null && session.actor.role.allows(Permission.INVENTORY_ADJUST)) safe {
                overview.stockCounts(branch).filter { it.count.status == ir.sabou.inventory.CountStatus.PENDING }
            }?.takeIf { it.isNotEmpty() }?.let { list ->
                add(Todo(R.drawable.ic_count, "${Fa.number(list.size.toLong())} انبارگردانی در انتظار تأیید", "اختلاف‌ها را با دلیل تأیید یا رد کنید", Route.CountHistory, true))
            }
            safe { buying.reviewQueue() }?.takeIf { it.isNotEmpty() }?.let { list ->
                add(Todo(R.drawable.ic_alert, "${Fa.number(list.size.toLong())} ردیف فاکتور در انتظار بررسی", "کالای ناشناخته را به کالا یا هزینه وصل کنید", Route.ReviewQueue, false))
            }
            safe { buying.priceChanges(date.plusDays(-7), date, 1_000) }?.takeIf { it.isNotEmpty() }?.let { list ->
                add(Todo(R.drawable.ic_alert, "${Fa.number(list.size.toLong())} تغییر قیمت بیش از ۱۰٪", list.take(2).joinToString("، ") { it.item.name }, Route.PriceChanges, false))
            }
        }

        val setup = overview.setupStatus()
        val missing = buildList {
            if (session.actor.role.allows(Permission.TREASURY_ACCOUNT_MANAGE) && !setup.hasAccounts) add("تعریف صندوق و حساب بانکی" to Route.Accounts)
            if (session.actor.role.allows(Permission.INVENTORY_ITEM_MANAGE) && !setup.hasItems) add("تعریف کالاهای انبار" to Route.Items)
            if (session.actor.role.allows(Permission.RECIPE_MANAGE) && !setup.hasMenu) add("تعریف منو و رسپی" to Route.Menu)
        }
        // Owner, manager and accountant see the month at a glance; others the day only.
        val dashboard = if (session.actor.role.allows(Permission.LEDGER_VIEW)) safe { dashboards.of(date, branch) } else null
        HomeData(today, todos, missing, dashboard)
    }

    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val j = Fa.jalali(session.today)
                Text("${Fa.dayTitle(session.today)} ${Fa.digits(j.year.toString())}", style = SabouType.caption, color = Sabou.colors.muted)
                Text("سلام، ${session.actor.displayName}", style = SabouType.title, color = Sabou.colors.ink)
                BranchSwitcher()
            }
        }
        val d = data.orNull()
        item { Hero(d?.today, onClick = { nav.go(Route.Sales) }) }
        item { QuickActions(nav) }
        d?.dashboard?.let { dash ->
            item { SectionTitle("این ماه", "از ${Fa.dayTitle(dash.from)}") }
            item { Kpis(dash, onClick = { nav.go(Route.Reports) }) }
            item { CostDonut(dash, onClick = { nav.go(Route.Reports) }) }
            item { WeekBars(dash) }
        }
        if (d != null && d.setupMissing.isNotEmpty()) {
            item { SectionTitle("راه‌اندازی", "${Fa.number(d.setupMissing.size.toLong())} مرحله") }
            d.setupMissing.forEach { (title, route) ->
                item { NavRow(R.drawable.ic_settings, title, "برای شروع کار لازم است", onClick = { nav.go(route) }) }
            }
        }
        if (d != null && d.todos.isNotEmpty()) {
            item { SectionTitle("نیازمند اقدام", "${Fa.number(d.todos.size.toLong())} مورد") }
            d.todos.forEach { t ->
                item {
                    NavRow(
                        t.icon, t.title, t.detail,
                        tint = if (t.warn) Sabou.colors.onAccentSoft else Sabou.colors.moneyIn,
                        tile = if (t.warn) Sabou.colors.accentSoft else Sabou.colors.moneyInSoft,
                        onClick = { nav.go(t.route) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(today: Today?, onClick: () -> Unit) {
    val c = Sabou.colors
    Column(
        Modifier.fillMaxWidth().clip(SabouShapes.hero).background(c.primary).clickable(role = Role.Button, onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("فروش خالص امروز", style = SabouType.body, color = c.onPrimaryMuted, modifier = Modifier.weight(1f))
            if (today != null) Chip(today.status, ChipKind.ACCENT)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(today?.net?.let { Fa.rial(it) } ?: "—", style = SabouType.display, color = c.onPrimary)
            Text("ریال", style = SabouType.body, color = c.onPrimaryMuted, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("نقد" to today?.cash, "کارت" to today?.card, "نسیه" to today?.credit).forEach { (label, v) ->
                Column(
                    Modifier.weight(1f).clip(SabouShapes.field).background(c.primarySurface).padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(label, style = SabouType.caption, color = c.onPrimaryMuted)
                    Text(v?.let(Fa::rialShort) ?: "—", style = SabouType.bodyStrong, color = c.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun QuickActions(nav: Nav) {
    val session = LocalSession.current
    // Only actions this role may perform.
    val items = listOf(
        Triple("ثبت فروش", R.drawable.ic_sales, Route.Sales) to Permission.SALES_RECORD,
        Triple("پرداخت", R.drawable.ic_payment, Route.Payment) to Permission.TREASURY_PAYMENT,
        Triple("دریافت کالا", R.drawable.ic_operations, Route.NewPurchase) to Permission.PURCHASE_RECORD,
        Triple("انبارگردانی", R.drawable.ic_count, Route.Count) to Permission.INVENTORY_COUNT,
    ).filter { session.can(it.second) }.map { it.first }
    if (items.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (label, icon, route) ->
            Column(
                Modifier.weight(1f).heightIn(min = 88.dp).clip(SabouShapes.card).background(Sabou.colors.surface)
                    .border(1.dp, Sabou.colors.border, SabouShapes.card).clickable(role = Role.Button) { nav.go(route) }.padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(40.dp).clip(SabouShapes.tile).background(Sabou.colors.primarySoft), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), contentDescription = null, tint = Sabou.colors.primary, modifier = Modifier.size(22.dp))
                }
                Text(label, style = SabouType.label, color = Sabou.colors.ink, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------- Month at a glance

@Composable
private fun Kpis(d: ir.sabou.core.Dashboard, onClick: () -> Unit) {
    val c = Sabou.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val change = d.revenueChangeBp
            Kpi("فروش", Fa.rialShort(d.revenue), Modifier.weight(1f), onClick,
                note = change?.let { (if (it >= 0) "▲ " else "▼ ") + Fa.percent(kotlin.math.abs(it)) + " نسبت به ماه قبل" } ?: "ماه قبل: ${Fa.rialShort(d.previousRevenue)}",
                noteColor = when { change == null -> c.muted; change >= 0 -> c.primary; else -> c.danger })
            Kpi(if (d.profit >= 0) "سود" else "زیان", Fa.rialShort(kotlin.math.abs(d.profit)), Modifier.weight(1f), onClick,
                valueColor = if (d.profit >= 0) c.ink else c.danger,
                note = "هزینه‌ها: ${Fa.rialShort(d.costs)}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("بهای غذا", d.ratios.foodBp?.let(Fa::percent) ?: "—", Modifier.weight(1f), onClick, note = "از فروش غذا")
            Kpi("دستمزد", d.ratios.laborBp?.let(Fa::percent) ?: "—", Modifier.weight(1f), onClick, note = "پس از تأیید حقوق ماه")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("نقد صندوق", Fa.rialShort(d.cash), Modifier.weight(1f), onClick)
            Kpi("بانک و کارت‌خوان", Fa.rialShort(d.bank), Modifier.weight(1f), onClick)
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier, onClick: () -> Unit, note: String? = null,
                valueColor: androidx.compose.ui.graphics.Color = Sabou.colors.ink, noteColor: androidx.compose.ui.graphics.Color = Sabou.colors.muted) {
    Column(
        modifier.heightIn(min = 84.dp).clip(SabouShapes.card).background(Sabou.colors.surface).border(1.dp, Sabou.colors.border, SabouShapes.card)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = SabouType.caption, color = Sabou.colors.muted)
        Text(value, style = SabouType.section, color = valueColor)
        if (note != null) Text(note, style = SabouType.caption, color = noteColor)
    }
}

@Composable
private fun CostDonut(d: ir.sabou.core.Dashboard, onClick: () -> Unit) {
    val colors = ir.sabou.app.ui.components.chartColors()
    // «سایر» always takes the last (neutral) colour.
    fun colorOf(i: Int, code: String?) = if (code == null) colors.last() else colors[i % (colors.size - 1)]
    ir.sabou.app.ui.components.SCard(onClick = onClick) {
        Text("هزینه‌ها و سود این ماه", style = SabouType.section, color = Sabou.colors.ink)
        if (d.costs <= 0 && d.revenue <= 0) {
            Text("هنوز سندی در این ماه ثبت نشده است.", style = SabouType.body, color = Sabou.colors.muted)
            return@SCard
        }
        val ring = d.slices.map { it.amount } + listOf(d.profit.coerceAtLeast(0))
        val ringColors = d.slices.mapIndexed { i, s -> colorOf(i, s.code) } + Sabou.colors.track
        val total = ring.sum().takeIf { it > 0 } ?: 1L
        fun share(amount: Long) = Fa.percent(ir.sabou.kernel.Ratio.mulDiv(amount, 10_000, total))
        val summary = d.slices.joinToString("، ") { "${it.label} ${share(it.amount)}" }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            ir.sabou.app.ui.components.Donut(ring, ringColors, "هزینه‌ها: $summary؛ ${if (d.profit >= 0) "سود" else "زیان"} ${Fa.rial(kotlin.math.abs(d.profit))} ریال") {
                Text(if (d.profit >= 0) "سود" else "زیان", style = SabouType.caption, color = Sabou.colors.muted)
                Text(Fa.rialShort(kotlin.math.abs(d.profit)), style = SabouType.section, color = if (d.profit >= 0) Sabou.colors.primary else Sabou.colors.danger)
                Text("ریال", style = SabouType.caption, color = Sabou.colors.muted)
            }
        }
        d.slices.forEachIndexed { i, s ->
            ir.sabou.app.ui.components.LegendRow(colorOf(i, s.code), s.label, share(s.amount), Fa.rialShort(s.amount))
        }
        if (d.profit > 0) ir.sabou.app.ui.components.LegendRow(Sabou.colors.track, "سود", share(d.profit), Fa.rialShort(d.profit))
        Text("سهم‌ها از جمع هزینه‌ها و سود (= فروش)؛ مبالغ به ریال.", style = SabouType.caption, color = Sabou.colors.muted)
    }
}

@Composable
private fun WeekBars(d: ir.sabou.core.Dashboard) {
    ir.sabou.app.ui.components.SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("فروش ۷ روز اخیر", style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
            Text("امروز ${Fa.rialShort(d.week.last().revenue)}", style = SabouType.caption, color = Sabou.colors.muted)
        }
        ir.sabou.app.ui.components.Bars(
            d.week.map { it.revenue }, d.week.map { Fa.weekday(it.date).take(1) },
            "فروش هفت روز اخیر: " + d.week.joinToString("، ") { "${Fa.weekday(it.date)} ${Fa.rial(it.revenue)} ریال" },
            Modifier.padding(top = 12.dp),
        )
    }
}

