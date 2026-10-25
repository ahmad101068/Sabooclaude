package ir.sabou.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.ExportButtons
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.ColorBars
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.Donut
import ir.sabou.app.ui.components.GroupedBars
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.LegendRow
import ir.sabou.app.ui.components.LineChart
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.ScoreTrack
import ir.sabou.app.ui.components.chartColors
import ir.sabou.app.ui.load
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.DashKpi
import ir.sabou.core.Fa
import ir.sabou.core.Finding
import ir.sabou.core.KpiUnit
import ir.sabou.core.ManagementDashboard
import ir.sabou.core.MenuClass
import ir.sabou.core.ReportTables

/**
 * The manager's dashboard (ADR-0064). One column on a phone, two on a small tablet, three on a large one; every
 * figure comes from the books (ManagementDashboards), what the app does not record says so instead of a number.
 */
object ManagementDashboardScreen {

    @Composable
    fun Show(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(session.today.plusDays(-6)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        Column(Modifier.fillMaxSize()) {
            Header("داشبورد مدیریتی", "از دفاتر، با همان ارقام گزارش‌ها", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, from, to) { managementDashboards.of(branch, from, to) }
                Page {
                    ReportsScreens.PeriodPicker(from, to) { f, t -> from = f; to = t }
                    Loaded(data) { d ->
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val columns = when { maxWidth >= 900.dp -> 3; maxWidth >= 600.dp -> 2; else -> 1 }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Grid(if (columns == 1) 2 else 3 * (columns - 1), d.kpis.map { k -> cell { KpiCard(k) { kpiTarget(k)?.let(nav::go) } } })
                                Grid(columns, listOf(
                                    cell { WeekCard(d) },
                                    cell { CategoryCard(d) },
                                    cell { PaymentCard(d) },
                                    cell { MonthsCard(d) },
                                    cell(if (columns >= 3) 2 else 1) { TopItemsCard(d) { nav.go(Route.MenuEngineering) } },
                                    cell { SuggestionsCard(d) { nav.go(Route.Assessment) } },
                                    cell { OperationsCard(d) },
                                    cell { LatestSalesCard(d) },
                                ))
                            }
                        }
                        Text("${d.branchName} · ${Fa.date(d.from)} تا ${Fa.date(d.to)} در برابر ${Fa.date(d.baseFrom)} تا ${Fa.date(d.baseTo)} · مبالغ به ریال",
                            style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    ExportButtons("داشبورد-مدیریتی") { ReportTables.managementDashboard(managementDashboards.of(branch, from, to)) }
                }
            }
        }
    }

    private fun kpiTarget(k: DashKpi): Route? = when {
        k.title.startsWith("فروش") -> Route.DayFlash
        k.title.startsWith("سود") -> Route.ProfitBridge
        k.title.startsWith("درصد") -> Route.MenuEngineering
        k.title.startsWith("رضایت") -> Route.FieldRecords
        k.title.startsWith("هشدار") -> Route.Assessment
        else -> null
    }

    private fun cell(span: Int = 1, content: @Composable () -> Unit): Pair<Int, @Composable () -> Unit> = span to content

    /** Lays [items] (span to content) in rows of [columns], each row's items sharing its width by span. */
    @Composable
    private fun Grid(columns: Int, items: List<Pair<Int, @Composable () -> Unit>>) {
        val rows = ArrayList<List<Pair<Int, @Composable () -> Unit>>>()
        var row = ArrayList<Pair<Int, @Composable () -> Unit>>(); var used = 0
        items.forEach { item ->
            val span = item.first.coerceAtMost(columns)
            if (used + span > columns) { rows += row; row = ArrayList(); used = 0 }
            row += span to item.second; used += span
        }
        if (row.isNotEmpty()) rows += row
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    r.forEach { (span, content) -> Box(Modifier.weight(span.toFloat())) { content() } }
                    val left = columns - r.sumOf { it.first }
                    if (left > 0 && rows.size > 1) Box(Modifier.weight(left.toFloat())) {}
                }
            }
        }
    }

    private fun money(v: Long) = Fa.rialShort(v)

    @Composable
    private fun KpiCard(k: DashKpi, onClick: () -> Unit) {
        SCard(onClick = onClick) {
            Text(k.title, style = SabouType.label, color = Sabou.colors.muted, maxLines = 1)
            Text(
                when {
                    k.value == null -> "—"
                    k.unit == KpiUnit.PERCENT -> Fa.percent(k.value!!)
                    k.unit == KpiUnit.RIAL -> money(k.value!!)
                    else -> Fa.number(k.value!!)
                },
                style = SabouType.title, color = if (k.value == null) Sabou.colors.muted else Sabou.colors.ink, maxLines = 1,
            )
            val change = k.changeBp?.let { bp ->
                val sign = if (bp > 0) "▲ " else if (bp < 0) "▼ " else ""
                sign + Fa.percent(kotlin.math.abs(bp)) + if (k.unit == KpiUnit.PERCENT) " واحد" else ""
            }
            Text(listOfNotNull(change, k.note.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { " " }, style = SabouType.caption,
                color = when (k.good) { true -> Sabou.colors.moneyIn; false -> Sabou.colors.danger; null -> Sabou.colors.muted }, maxLines = 2)
        }
    }

    @Composable
    private fun Title(text: String, trailing: String? = null) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = SabouType.label, color = Sabou.colors.primary)
        }
    }

    @Composable
    private fun WeekCard(d: ManagementDashboard) {
        SCard {
            Title("روند فروش هفت روز اخیر")
            LineChart(d.week.map { it.sales }, d.week.map { it.previous }, d.week.map { Fa.weekday(it.date).take(1) },
                "فروش هفت روز اخیر در برابر همان روزهای هفته‌ی قبل: " + d.week.joinToString("، ") { "${Fa.weekday(it.date)} ${Fa.rial(it.sales)} ریال (قبل ${Fa.rial(it.previous)})" })
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                Legend(Sabou.colors.primary, "این هفته ${money(d.week.sumOf { it.sales })}")
                Legend(Sabou.colors.subtle, "هفته‌ی قبل ${money(d.week.sumOf { it.previous })}")
            }
        }
    }

    @Composable
    private fun Legend(color: Color, text: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
            Text(text, style = SabouType.caption, color = Sabou.colors.muted)
        }
    }

    @Composable
    private fun CategoryCard(d: ManagementDashboard) {
        SCard {
            Title("فروش به تفکیک دسته")
            if (d.categories.isEmpty()) { Text("فروشی در این دوره نیست.", style = SabouType.body, color = Sabou.colors.muted); return@SCard }
            val top = d.categories.take(6)
            ColorBars(top.map { it.amount }, top.map { it.name }, chartColors(), { money(it) },
                "فروش به تفکیک دسته: " + top.joinToString("، ") { "${it.name} ${Fa.rial(it.amount)} ریال" })
            if (d.categories.any { it.name == "بدون دسته" }) Text("دسته‌ی غذاها را در «منو» تعیین کنید.", style = SabouType.caption, color = Sabou.colors.muted,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun PaymentCard(d: ManagementDashboard) {
        SCard {
            Title("ترکیب روش پرداخت")
            if (d.payments.isEmpty()) { Text("پرداختی در این دوره نیست.", style = SabouType.body, color = Sabou.colors.muted); return@SCard }
            val colors = chartColors()
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Donut(d.payments.map { it.amount }, d.payments.indices.map { colors[it % colors.size] },
                    "روش پرداخت: " + d.payments.joinToString("، ") { "${it.name} ${Fa.percent(it.shareBp)}" }) {
                    Text(money(d.payments.sumOf { it.amount }), style = SabouType.section, color = Sabou.colors.ink)
                    Text("ریال", style = SabouType.caption, color = Sabou.colors.muted)
                }
            }
            d.payments.forEachIndexed { i, p -> LegendRow(colors[i % colors.size], p.name, Fa.percent(p.shareBp), money(p.amount)) }
        }
    }

    @Composable
    private fun MonthsCard(d: ManagementDashboard) {
        SCard {
            Title("هزینه و سود ماهانه")
            val c = Sabou.colors
            val colors = listOf(c.danger, c.bank, c.moneyIn)
            GroupedBars(d.months.map { listOf(it.labor, it.materials, it.profit) }, d.months.map { Fa.monthNames[it.month - 1] }, colors,
                "شش ماه اخیر: " + d.months.joinToString("، ") { "${Fa.monthNames[it.month - 1]}: پرسنل ${Fa.rial(it.labor)}، مواد ${Fa.rial(it.materials)}، سود ${Fa.rial(it.profit)} ریال" })
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                Legend(colors[0], "پرسنل"); Legend(colors[1], "بهای مواد"); Legend(colors[2], "سود عملیاتی")
            }
            if (d.months.any { it.profit < 0 }) Text("سود منفی (زیان) با ستون کوتاه نشان داده می‌شود؛ رقمش در خروجی اکسل است.", style = SabouType.caption, color = c.muted)
        }
    }

    private fun classChip(c: MenuClass?): Pair<String, ChipKind>? = when (c) {
        MenuClass.STAR -> "پرفروش و پرسود" to ChipKind.SALES
        MenuClass.PLOWHORSE -> "پرفروش کم‌سود" to ChipKind.ACCENT
        MenuClass.PUZZLE -> "پرسود کم‌فروش" to ChipKind.PRIMARY
        MenuClass.DOG -> "کم‌فروش کم‌سود" to ChipKind.DANGER
        null -> null
    }

    @Composable
    private fun TopItemsCard(d: ManagementDashboard, onMore: () -> Unit) {
        SCard(onClick = onMore) {
            Title("عملکرد اقلام پرفروش", "مهندسی منو ←")
            if (d.topItems.isEmpty()) { Text("فروشی در این دوره نیست.", style = SabouType.body, color = Sabou.colors.muted); return@SCard }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                listOf("غذا" to 2f, "تعداد" to 1f, "فروش" to 1.3f, "حاشیه" to 1f, "وضعیت" to 1.6f).forEach { (h, w) ->
                    Text(h, style = SabouType.label, color = Sabou.colors.muted, modifier = Modifier.weight(w))
                }
            }
            d.topItems.forEachIndexed { i, it ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.name, style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(2f), maxLines = 1)
                    Text(Fa.quantity(it.portions), style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                    Text(money(it.sales), style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1.3f))
                    Text(it.marginBp?.let(Fa::percent) ?: "—", style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                    Box(Modifier.weight(1.6f)) { classChip(it.menuClass)?.let { (t, k) -> Chip(t, k) } }
                }
            }
        }
    }

    @Composable
    private fun SuggestionsCard(d: ManagementDashboard, onMore: () -> Unit) {
        SCard(onClick = onMore) {
            Title("اقدامات پیشنهادی", "کارنامه ←")
            if (d.suggestions.isEmpty()) Text("با داده‌ی این دوره عارضه‌ای پیدا نشد.", style = SabouType.body, color = Sabou.colors.muted)
            d.suggestions.forEach { f ->
                val tint = when (f.severity) { Finding.Severity.CRITICAL, Finding.Severity.HIGH -> Sabou.colors.dangerSoft; Finding.Severity.MEDIUM -> Sabou.colors.accentSoft; else -> Sabou.colors.primarySoft }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(tint).padding(10.dp)) {
                    Text(f.title, style = SabouType.bodyStrong, color = Sabou.colors.ink, maxLines = 2)
                    Text("پیشنهاد: ${f.action}", style = SabouType.caption, color = Sabou.colors.muted, maxLines = 2)
                }
            }
        }
    }

    @Composable
    private fun OperationsCard(d: ManagementDashboard) {
        SCard {
            Title("وضعیت عملیات")
            val colors = chartColors()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                d.operations.forEachIndexed { i, a -> ScoreTrack(a.area.title, a.score, colors[i % colors.size]) }
            }
            Text("امتیاز حوزه‌های کارنامه از ۱۰۰؛ «نامعلوم» یعنی داده‌ی کافی نیست.", style = SabouType.caption, color = Sabou.colors.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }

    @Composable
    private fun LatestSalesCard(d: ManagementDashboard) {
        SCard {
            Title("آخرین فروش‌ها")
            if (d.latestSales.isEmpty()) Text("فروشی در ۳۰ روز اخیر ثبت نشده.", style = SabouType.body, color = Sabou.colors.muted)
            d.latestSales.forEachIndexed { i, s ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.dishes.ifBlank { "—" }, style = SabouType.body, color = Sabou.colors.ink, maxLines = 1)
                        Text(Fa.dayTitle(s.date), style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(s.payable), style = SabouType.bodyStrong, color = Sabou.colors.ink)
                        if (s.status == ir.sabou.sales.SaleStatus.REVERSED) Chip("برگشت خورده", ChipKind.DANGER) else Chip("ثبت شده", ChipKind.SALES)
                    }
                }
            }
            Text("فروش روزانه از سپند یا ثبت دستی؛ وضعیت لحظه‌ای هر سفارش در برنامه نیست.", style = SabouType.caption, color = Sabou.colors.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
