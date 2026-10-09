package ir.sabou.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.ui.ExportButtons
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.NavRow
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.load
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouShapes
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.PnlTotals
import ir.sabou.core.ReportTables
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountCode
import ir.sabou.platform.Permission

/** Management reports: profit and loss, end of day, sales mix and margins, actual vs theoretical usage, attendance. */
object ReportsScreens {

    @Composable
    fun Hub(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("گزارش‌ها", onBack = nav.back)
            Page {
                if (session.can(Permission.LEDGER_VIEW)) {
                    SectionTitle("مالی")
                    NavRow(R.drawable.ic_finance, "سود و زیان", "روزانه، به تفکیک شعبه، درصد بهای غذا و نیروی کار", onClick = { nav.go(Route.ProfitLoss) })
                    NavRow(R.drawable.ic_finance, "تراز آزمایشی", "مانده‌ی همه‌ی حساب‌ها", onClick = { nav.go(Route.TrialBalance) })
                }
                if (session.can(Permission.SALES_VIEW)) {
                    SectionTitle("فروش")
                    NavRow(R.drawable.ic_sales, "گزارش پایان روز", "فروش، تسویه‌ها، بهای غذا و کسر و اضافه‌ی صندوق", onClick = { nav.go(Route.DayFlash) })
                    NavRow(R.drawable.ic_sales, "اقلام پرفروش و حاشیه‌ی سود", "سهم هر قلم از فروش و سود هر پرس", onClick = { nav.go(Route.ProductMix) })
                }
                if (session.can(Permission.INVENTORY_VIEW)) {
                    SectionTitle("انبار")
                    NavRow(R.drawable.ic_count, "مصرف واقعی در برابر تئوریک", "کسری و کارایی مصرف مواد", onClick = { nav.go(Route.Usage) })
                }
                if (listOf(Permission.PERSONNEL_VIEW, Permission.ATTENDANCE_RECORD, Permission.PAYROLL_CALCULATE).any { session.can(it) }) {
                    SectionTitle("پرسنل")
                    NavRow(R.drawable.ic_clock, "کارکرد و اضافه‌کار", "جمع ساعت‌ها در هر بازه", onClick = { nav.go(Route.AttendanceReport) })
                }
            }
        }
    }

    // ------------------------------------------------------------ Period and place

    /** From / to with quick choices: today, this Jalali month, last month. */
    @Composable
    fun PeriodPicker(from: BusinessDate, to: BusinessDate, onChange: (BusinessDate, BusinessDate) -> Unit) {
        val session = LocalSession.current
        val today = session.today
        val j = Fa.jalali(today)
        val monthStart = Fa.fromJalali(j.year, j.month, 1)
        val prevYear = if (j.month == 1) j.year - 1 else j.year
        val prevMonth = if (j.month == 1) 12 else j.month - 1
        val lastStart = Fa.fromJalali(prevYear, prevMonth, 1)
        val lastEnd = Fa.fromJalali(prevYear, prevMonth, Fa.monthLength(prevYear, prevMonth))
        SCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("امروز", today, today), Triple("این ماه", monthStart, today), Triple("ماه قبل", lastStart, lastEnd)).forEach { (label, f, t) ->
                    val on = f == from && t == to
                    Box(Modifier.clip(SabouShapes.chip).background(if (on) Sabou.colors.primarySoft else Sabou.colors.track).clickable { onChange(f, t) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(label, style = SabouType.label, color = if (on) Sabou.colors.primary else Sabou.colors.muted)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { DateInput("از", from, { onChange(it, maxOf(it, to)) }, today) }
                Box(Modifier.weight(1f)) { DateInput("تا", to, { onChange(minOf(from, it), it) }, today) }
            }
        }
    }

    @Composable
    private fun BranchChoice(selected: Scope.Branch?, allowAll: Boolean, onSelect: (Scope.Branch?) -> Unit) {
        val session = LocalSession.current
        val branches by load(session) { overview.branches() }
        val list = branches.let { (it as? ir.sabou.app.ui.Load.Done)?.value }.orEmpty()
        if (list.size <= 1 && !allowAll) return
        val choices = (if (allowAll) listOf(Choice<String?>(null, "همه‌ی شعب")) else emptyList()) + list.map { Choice<String?>(it.id.value.value, it.name) }
        Picker("شعبه", choices, selected?.branchId?.value?.value, { id -> onSelect(id?.let { Scope.Branch(ir.sabou.kernel.BranchId(GlobalId.parse(it))) }) })
    }

    // ------------------------------------------------------------ Profit and loss

    @Composable
    fun ProfitLoss(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(monthStart(session.today)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        var branch by rememberSaveable { mutableStateOf<Scope.Branch?>(null) }
        val data by load(session, from, to, branch) { reports.profitAndLoss(from, to, branch) }
        Column(Modifier.fillMaxSize()) {
            Header("سود و زیان", onBack = nav.back)
            Page {
                PeriodPicker(from, to) { f, t -> from = f; to = t }
                BranchChoice(branch, allowAll = true) { branch = it }
                Loaded(data) { p ->
                    SCard {
                        KeyValue("درآمد", Fa.toman(p.totals.revenue))
                        KeyValue("بهای تمام‌شده", "− " + Fa.toman(p.totals.cogs))
                        KeyValue("سود ناخالص", Fa.toman(p.totals.grossProfit))
                        KeyValue("هزینه‌ها", "− " + Fa.toman(p.totals.expenses))
                        Divider()
                        KeyValue("سود (زیان) خالص · تومان", Fa.toman(p.totals.profit), if (p.totals.profit < 0) Sabou.colors.danger else Sabou.colors.ink, strong = true)
                    }
                    SCard {
                        Text("نسبت به فروش غذا", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Ratio("بهای غذا", p.ratios.foodBp, Modifier.weight(1f))
                            Ratio("نیروی کار", p.ratios.laborBp, Modifier.weight(1f))
                            Ratio("بهای اصلی", p.ratios.primeBp, Modifier.weight(1f))
                        }
                        Text("حقوق هر ماه با تأیید لیست حقوق، در روز آخر همان ماه ثبت می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    listOf("درآمدها" to p.revenue, "بهای تمام‌شده" to p.costOfSales, "هزینه‌ها" to p.expenses).forEach { (title, lines) ->
                        if (lines.isNotEmpty()) {
                            SectionTitle(title)
                            SCard {
                                lines.forEach { l ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        nav.go(Route.LedgerDetail(l.account.code.value, from.epochDay, to.epochDay, branch?.branchId?.value?.value))
                                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${Fa.digits(l.account.code.value)} · ${l.account.name}", style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                        Text(Fa.toman(l.amount), style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                    }
                                }
                                Text("برای دیدن سندها روی هر ردیف بزنید.", style = SabouType.caption, color = Sabou.colors.muted)
                            }
                        }
                    }
                    if (p.byBranch.size > 1) {
                        SectionTitle("به تفکیک شعبه")
                        SCard { p.byBranch.forEach { (name, t) -> TotalsRow(name, t) } }
                    }
                    if (p.byDay.size > 1) {
                        SectionTitle("روزانه")
                        SCard { p.byDay.forEach { (d, t) -> TotalsRow(Fa.dayTitle(d), t) } }
                    }
                }
                ExportButtons("سود-و-زیان") {
                    val place = branch?.let { b -> overview.branches().firstOrNull { it.id == b.branchId }?.name } ?: "همه‌ی شعب"
                    ReportTables.profitAndLoss(reports.profitAndLoss(from, to, branch), place)
                }
            }
        }
    }

    @Composable
    private fun Ratio(label: String, bp: Long?, modifier: Modifier) {
        Column(modifier.clip(SabouShapes.field).background(Sabou.colors.track).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(bp?.let { Fa.percent(it) } ?: "—", style = SabouType.amount, color = Sabou.colors.ink)
            Text(label, style = SabouType.caption, color = Sabou.colors.muted)
        }
    }

    @Composable
    private fun TotalsRow(label: String, t: PnlTotals) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                Text(Fa.toman(t.profit), style = SabouType.bodyStrong, color = if (t.profit < 0) Sabou.colors.danger else Sabou.colors.ink)
            }
            Text("درآمد ${Fa.tomanShort(t.revenue)} · بهای تمام‌شده ${Fa.tomanShort(t.cogs)} · هزینه ${Fa.tomanShort(t.expenses)}",
                style = SabouType.caption, color = Sabou.colors.muted)
        }
    }

    @Composable
    fun LedgerDetail(nav: Nav, route: Route.LedgerDetail) {
        val session = LocalSession.current
        val from = BusinessDate(route.from); val to = BusinessDate(route.to)
        val branch = route.branch?.let { Scope.Branch(ir.sabou.kernel.BranchId(GlobalId.parse(it))) }
        val code = AccountCode.of(route.account)
        val data by load(session, route) { reports.ledgerDetail(code, from, to, branch) }
        val name by load(session, route.account) { overview.trialBalance().firstOrNull { it.first.code == code }?.first?.name ?: route.account }
        val title = (name as? ir.sabou.app.ui.Load.Done)?.value ?: route.account
        Column(Modifier.fillMaxSize()) {
            Header("گردش $title", "از ${Fa.date(from)} تا ${Fa.date(to)}", onBack = nav.back)
            Page {
                Loaded(data) { rows ->
                    if (rows.isEmpty()) EmptyState("سندی در این بازه نیست.")
                    rows.forEach { r ->
                        SCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("سند ${Fa.number(r.number)} · ${Fa.date(r.date)}", style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                Text(if (r.debit > 0) "بدهکار ${Fa.toman(r.debit)}" else "بستانکار ${Fa.toman(r.credit)}", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            }
                            Text(r.description + if (r.memo.isBlank()) "" else " · ${r.memo}", style = SabouType.caption, color = Sabou.colors.muted)
                            Text(r.scope, style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                }
                ExportButtons("گردش-حساب-${route.account}") { listOf(ReportTables.ledgerDetail(title, reports.ledgerDetail(code, from, to, branch), from, to)) }
            }
        }
    }

    // ------------------------------------------------------------ Sales

    @Composable
    fun DayFlash(nav: Nav) {
        val session = LocalSession.current
        var date by rememberSaveable { mutableStateOf(session.today) }
        Column(Modifier.fillMaxSize()) {
            Header("گزارش پایان روز", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, date) { reports.dayFlash(branch, date) }
                Page {
                    SCard { DateInput("روز", date, { date = it }, session.today) }
                    Loaded(data) { f ->
                        if (!f.posted) Banner("فروش این روز هنوز ثبت نهایی نشده است.", ChipKind.ACCENT)
                        SCard {
                            KeyValue("قابل تسویه (تومان)", Fa.toman(f.payable), strong = true)
                            KeyValue("فروش ناخالص", Fa.toman(f.gross)); KeyValue("تخفیف", "− " + Fa.toman(f.discount))
                            KeyValue("حق سرویس", Fa.toman(f.serviceCharge)); KeyValue("مالیات و عوارض", Fa.toman(f.tax))
                        }
                        SCard {
                            KeyValue("مهمان", Fa.number(f.guests.toLong())); KeyValue("تراکنش", Fa.number(f.transactions.toLong()))
                            KeyValue("میانگین هر مهمان", f.perGuest?.let { Fa.toman(it) } ?: "—")
                            KeyValue("میانگین هر تراکنش", f.perTransaction?.let { Fa.toman(it) } ?: "—")
                        }
                        SCard {
                            f.settlements.forEach { (name, m) -> KeyValue(name, Fa.toman(m)) }
                            if (!f.credit.isZero) KeyValue("نسیه", Fa.toman(f.credit))
                            Divider()
                            KeyValue("نقد فروش امروز", Fa.toman(f.cashSales))
                            KeyValue("نقد شمارش‌شده در بستن روز", f.countedCash?.let { Fa.toman(it) } ?: "روز بسته نشده")
                            Text("تنخواه اول روز و نسیه‌های وصول‌شده‌ی نقدی در «نقد فروش امروز» نیست؛ اختلاف را با آن‌ها بسنجید.", style = SabouType.caption, color = Sabou.colors.muted)
                            f.cashDifference?.let { d ->
                                KeyValue(if (d < 0) "کسری صندوق" else "اضافه‌ی صندوق", Fa.toman(kotlin.math.abs(d)), if (d < 0) Sabou.colors.danger else Sabou.colors.ink, strong = true)
                            }
                        }
                        SCard {
                            KeyValue("بهای مواد مصرفی", Fa.toman(f.cost))
                            KeyValue("درصد بهای غذا", f.foodCostBp?.let { Fa.percent(it) } ?: "—")
                            f.purchases?.let { KeyValue("خرید امروز", Fa.toman(it)) }; f.waste?.let { KeyValue("ضایعات امروز", Fa.toman(it)) }
                        }
                    }
                    ExportButtons("پایان-روز") { listOf(ReportTables.dayFlash(reports.dayFlash(branch, date))) }
                }
            }
        }
    }

    @Composable
    fun ProductMix(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(monthStart(session.today)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        Column(Modifier.fillMaxSize()) {
            Header("اقلام پرفروش و حاشیه‌ی سود", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, from, to) { reports.productMix(branch, from, to) }
                Page {
                    PeriodPicker(from, to) { f, t -> from = f; to = t }
                    Loaded(data) { m ->
                        SCard { KeyValue("فروش ناخالص (تومان)", Fa.toman(m.gross), strong = true); KeyValue("روزهای فروش", Fa.number(m.days.toLong())) }
                        if (m.rows.isEmpty()) EmptyState("در این بازه فروش ثبت نهایی نشده است.")
                        m.rows.forEach { r ->
                            SCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.name, style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                    Text(Fa.percent(r.shareBp), style = SabouType.bodyStrong, color = Sabou.colors.primary)
                                }
                                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(SabouShapes.chip).background(Sabou.colors.track)) {
                                    Box(Modifier.fillMaxWidth(r.shareBp / 10_000f).padding(vertical = 3.dp).clip(SabouShapes.chip).background(Sabou.colors.primary))
                                }
                                Text("${Fa.quantity(r.portions)} پرس · ${Fa.toman(r.gross)} تومان", style = SabouType.caption, color = Sabou.colors.muted)
                                Text(
                                    r.unitCost?.let { c ->
                                        "بهای مواد هر پرس ${Fa.toman(c)} · حاشیه ${r.unitMargin?.let { Fa.toman(it) } ?: "—"} · بهای غذا ${r.costBp?.let { Fa.percent(it) } ?: "—"}"
                                    } ?: "بهای مواد معلوم نیست (رسپی یا قیمت خرید ندارد)",
                                    style = SabouType.caption, color = if (r.unitCost == null) Sabou.colors.danger else Sabou.colors.muted,
                                )
                            }
                        }
                    }
                    ExportButtons("اقلام-پرفروش") { listOf(ReportTables.productMix(reports.productMix(branch, from, to))) }
                }
            }
        }
    }

    // ------------------------------------------------------------ Inventory

    @Composable
    fun Usage(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(monthStart(session.today)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        Column(Modifier.fillMaxSize()) {
            Header("مصرف واقعی در برابر تئوریک", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                var location by rememberSaveable(branch) { mutableStateOf<GlobalId?>(null) }
                val locations by load(session, branch) { overview.locations(branch) }
                val data by load(session, branch, location, from, to) { reports.actualVsTheoretical(branch, location, from, to) }
                Page {
                    PeriodPicker(from, to) { f, t -> from = f; to = t }
                    Loaded(locations) { locs ->
                        if (locs.size > 1) Picker("انبار", listOf(Choice<GlobalId?>(null, "همه‌ی انبارهای شعبه")) + locs.map { Choice<GlobalId?>(it.id, it.name) }, location, { location = it })
                    }
                    Loaded(data) { u ->
                        SCard {
                            KeyValue("مصرف واقعی (تومان)", Fa.toman(u.actualValue), strong = true)
                            KeyValue("مصرف تئوریک (فروش)", Fa.toman(u.theoreticalValue))
                            KeyValue("ضایعات ثبت‌شده", Fa.toman(u.wasteValue))
                            if (u.compsValue != 0L) KeyValue("پذیرایی، غذای پرسنل و اهدایی", Fa.toman(u.compsValue))
                            KeyValue("اختلاف توضیح‌داده‌نشده", Fa.toman(u.unexplainedValue), if (u.unexplainedValue > 0) Sabou.colors.danger else Sabou.colors.ink)
                            Text("اختلاف توضیح‌داده‌نشده همان کسری انبارگردانی است؛ بدون انبارگردانی در بازه، صفر می‌ماند.", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                        if (u.rows.isEmpty()) EmptyState("در این بازه گردشی نیست.")
                        u.rows.forEach { r ->
                            val unit = ReportTables.unitName(r.item.unit)
                            fun q(v: Long) = (if (v < 0) "−" else "") + Fa.quantity(ir.sabou.kernel.Quantity.of(kotlin.math.abs(v)))
                            SCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.item.name, style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                    Text("کارایی ${r.efficiencyBp?.let { Fa.percent(it) } ?: "—"}", style = SabouType.bodyStrong,
                                        color = if ((r.efficiencyBp ?: 10_000) < 9_500) Sabou.colors.danger else Sabou.colors.primary)
                                }
                                Text("واقعی ${q(r.actual.quantity)} · تئوریک ${q(r.theoretical.quantity)} · ضایعات ${q(r.waste.quantity)}${if (r.comps.quantity != 0L) " · اهدایی ${q(r.comps.quantity)}" else ""} · بی‌توضیح ${q(r.unexplained.quantity)} $unit",
                                    style = SabouType.caption, color = Sabou.colors.muted)
                                Text("اول ${q(r.opening.quantity)} · خرید ${q(r.purchases.quantity)} · انتقال ${q(r.transfers.quantity)} · تولید ${q(r.production.quantity)} · پایان ${q(r.closing.quantity)}",
                                    style = SabouType.caption, color = Sabou.colors.muted)
                            }
                        }
                    }
                    ExportButtons("مصرف-واقعی-تئوریک") { listOf(ReportTables.usage(reports.actualVsTheoretical(branch, location, from, to))) }
                }
            }
        }
    }

    // ------------------------------------------------------------ Attendance

    @Composable
    fun AttendanceReport(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(monthStart(session.today)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        Column(Modifier.fillMaxSize()) {
            Header("کارکرد و اضافه‌کار", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, from, to) { reports.attendance(branch, from, to) }
                Page {
                    PeriodPicker(from, to) { f, t -> from = f; to = t }
                    Loaded(data) { rows ->
                        if (rows.isEmpty()) EmptyState("کارمندی در این بازه مشغول به کار نبوده است.")
                        fun h(m: Long) = Fa.quantity(ir.sabou.kernel.Quantity.of(ir.sabou.kernel.Ratio.mulDiv(m, ir.sabou.kernel.Quantity.SCALE, 60)))
                        rows.forEach { r ->
                            SCard {
                                Text(r.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                Text("${Fa.number(r.days.toLong())} روز ثبت‌شده · کار ${h(r.workedMinutes)} ساعت · اضافه‌کار ${h(r.overtimeMinutes)} ساعت · غیبت ${h(r.absentMinutes)} ساعت",
                                    style = SabouType.caption, color = Sabou.colors.muted)
                            }
                        }
                    }
                    ExportButtons("کارکرد-و-اضافه-کار") {
                        val name = overview.branches().firstOrNull { it.id == branch.branchId }?.name ?: "شعبه"
                        listOf(ReportTables.attendance(reports.attendance(branch, from, to), name, from, to))
                    }
                }
            }
        }
    }

    private fun monthStart(today: BusinessDate): BusinessDate = Fa.jalali(today).let { Fa.fromJalali(it.year, it.month, 1) }
}
