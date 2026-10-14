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
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.components.Chip
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
                    NavRow(R.drawable.ic_calendar, "پیش‌بینی فروش", "دو هفته‌ی آینده، با روزهای تعطیل و مناسبت", onClick = { nav.go(Route.SalesForecast) })
                    NavRow(R.drawable.ic_finance, "پیش‌بینی نقدینگی", "۱۳ هفته‌ی آینده: طلب، چک، فاکتور، حقوق و فروش؛ با سناریو", onClick = { nav.go(Route.CashForecast) })
                    NavRow(R.drawable.ic_sales, "مشتریان نسیه", "سهم از فروش، مانده، سررسیدگذشته و تأخیر معمول", onClick = { nav.go(Route.CreditCustomers) })
                    NavRow(R.drawable.ic_finance, "سود و زیان امروز در برابر بودجه", "با برآورد حقوق و اجاره‌ی روزانه؛ و ماه تا امروز", onClick = { nav.go(Route.DailyPnl) })
                    NavRow(R.drawable.ic_finance, "سود و زیان", "روزانه، به تفکیک شعبه، درصد بهای غذا و نیروی کار", onClick = { nav.go(Route.ProfitLoss) })
                    NavRow(R.drawable.ic_finance, "چرا سود تغییر کرد", "تعداد، ترکیب، قیمت، بهای مواد، تخفیف و هزینه‌ها؛ در برابر دوره‌ی قبل", onClick = { nav.go(Route.ProfitBridge) })
                    NavRow(R.drawable.ic_finance, "تراز آزمایشی", "مانده‌ی همه‌ی حساب‌ها", onClick = { nav.go(Route.TrialBalance) })
                    NavRow(R.drawable.ic_finance, "بودجه در برابر عملکرد", "ماهانه یا سالانه، به تفکیک حساب", onClick = { nav.go(Route.BudgetReport) })
                }
                if (session.can(Permission.SALES_VIEW)) {
                    SectionTitle("فروش")
                    NavRow(R.drawable.ic_sales, "گزارش پایان روز", "فروش، تسویه‌ها، بهای غذا و کسر و اضافه‌ی صندوق", onClick = { nav.go(Route.DayFlash) })
                    NavRow(R.drawable.ic_sales, "اقلام پرفروش و حاشیه‌ی سود", "سهم هر قلم از فروش و سود هر پرس", onClick = { nav.go(Route.ProductMix) })
                    NavRow(R.drawable.ic_recipe, "مهندسی منو", "ستاره، اسب کاری، معما و سگ؛ و بهای غذا در برابر هدف", onClick = { nav.go(Route.MenuEngineering) })
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
                    if (p.provisional) Banner(
                        "${Fa.number(p.openShortages.toLong())} کسری مصرف باز در این بازه: بهای مواد بخشی از فروش‌ها هنوز ثبت نشده، پس بهای غذا کمتر و سود بیشتر از واقع است. " +
                            "با ثبت فاکتور خرید جامانده یا انبارگردانی، سود قطعی می‌شود.",
                        ChipKind.ACCENT,
                    )
                    SCard {
                        if (p.provisional) Chip("سود موقت", ChipKind.ACCENT)
                        KeyValue("درآمد", Fa.rial(p.totals.revenue))
                        KeyValue("بهای تمام‌شده", "− " + Fa.rial(p.totals.cogs))
                        KeyValue("سود ناخالص", Fa.rial(p.totals.grossProfit))
                        KeyValue("هزینه‌ها", "− " + Fa.rial(p.totals.expenses))
                        Divider()
                        KeyValue("سود (زیان) خالص · ریال", Fa.rial(p.totals.profit), if (p.totals.profit < 0) Sabou.colors.danger else Sabou.colors.ink, strong = true)
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
                                        Text(Fa.rial(l.amount), style = SabouType.bodyStrong, color = Sabou.colors.ink)
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
                Text(Fa.rial(t.profit), style = SabouType.bodyStrong, color = if (t.profit < 0) Sabou.colors.danger else Sabou.colors.ink)
            }
            Text("درآمد ${Fa.rialShort(t.revenue)} · بهای تمام‌شده ${Fa.rialShort(t.cogs)} · هزینه ${Fa.rialShort(t.expenses)}",
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
                                Text("سند ${Fa.digits(r.number)} · ${Fa.date(r.date)}", style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                Text(if (r.debit > 0) "بدهکار ${Fa.rial(r.debit)}" else "بستانکار ${Fa.rial(r.credit)}", style = SabouType.bodyStrong, color = Sabou.colors.ink)
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
                            KeyValue("قابل تسویه (ریال)", Fa.rial(f.payable), strong = true)
                            KeyValue("فروش ناخالص", Fa.rial(f.gross)); KeyValue("تخفیف", "− " + Fa.rial(f.discount))
                            KeyValue("حق سرویس", Fa.rial(f.serviceCharge)); KeyValue("مالیات و عوارض", Fa.rial(f.tax))
                        }
                        SCard {
                            KeyValue("مهمان", Fa.number(f.guests.toLong())); KeyValue("تراکنش", Fa.number(f.transactions.toLong()))
                            KeyValue("میانگین هر مهمان", f.perGuest?.let { Fa.rial(it) } ?: "—")
                            KeyValue("میانگین هر تراکنش", f.perTransaction?.let { Fa.rial(it) } ?: "—")
                        }
                        SCard {
                            f.settlements.forEach { (name, m) -> KeyValue(name, Fa.rial(m)) }
                            if (!f.credit.isZero) KeyValue("نسیه", Fa.rial(f.credit))
                            Divider()
                            KeyValue("نقد فروش امروز", Fa.rial(f.cashSales))
                            KeyValue("نقد شمارش‌شده در بستن روز", f.countedCash?.let { Fa.rial(it) } ?: "روز بسته نشده")
                            Text("تنخواه اول روز و نسیه‌های وصول‌شده‌ی نقدی در «نقد فروش امروز» نیست؛ اختلاف را با آن‌ها بسنجید.", style = SabouType.caption, color = Sabou.colors.muted)
                            f.cashDifference?.let { d ->
                                KeyValue(if (d < 0) "کسری صندوق" else "اضافه‌ی صندوق", Fa.rial(kotlin.math.abs(d)), if (d < 0) Sabou.colors.danger else Sabou.colors.ink, strong = true)
                            }
                        }
                        SCard {
                            KeyValue("بهای مواد مصرفی", Fa.rial(f.cost))
                            KeyValue("درصد بهای غذا", f.foodCostBp?.let { Fa.percent(it) } ?: "—")
                            f.purchases?.let { KeyValue("خرید امروز", Fa.rial(it)) }; f.waste?.let { KeyValue("ضایعات امروز", Fa.rial(it)) }
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
                        SCard { KeyValue("فروش ناخالص (ریال)", Fa.rial(m.gross), strong = true); KeyValue("روزهای فروش", Fa.number(m.days.toLong())) }
                        if (m.channels.isNotEmpty()) SCard {
                            Text("خالص فروش به تفکیک کانال", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            m.channels.forEach { c ->
                                KeyValue("${ReportTables.channelName(c.channel)} · ${Fa.percent(c.shareBp)}",
                                    Fa.rialShort(c.net.rial) + (c.perTransaction?.let { " · هر فاکتور ${Fa.rialShort(it.rial)}" } ?: ""))
                            }
                        }
                        Text(MIX_COST_NOTE, style = SabouType.caption, color = Sabou.colors.muted)
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
                                Text("${Fa.quantity(r.portions)} پرس · ${Fa.rial(r.gross)} ریال", style = SabouType.caption, color = Sabou.colors.muted)
                                Text(
                                    r.unitCost?.let { c ->
                                        "بهای مواد هر پرس ${Fa.rial(c)} · حاشیه ${r.unitMargin?.let { Fa.rial(it) } ?: "—"} · بهای غذا ${r.costBp?.let { Fa.percent(it) } ?: "—"}"
                                    } ?: "بهای مواد معلوم نیست (رسپی یا قیمت خرید ندارد)",
                                    style = SabouType.caption, color = if (r.unitCost == null) Sabou.colors.danger else Sabou.colors.muted,
                                )
                                r.actualUnitCost?.let { c ->
                                    val basis = if (r.fullyCosted) "" else " · بر پایه‌ی ${Fa.quantity(r.costedPortions)} از ${Fa.quantity(r.portions)} پرس"
                                    Text(
                                        "بهای واقعی هنگام فروش ${Fa.rial(c)} · حاشیه ${r.actualUnitMargin?.let { Fa.rial(it) } ?: "—"} · بهای غذا ${r.actualCostBp?.let { Fa.percent(it) } ?: "—"}$basis",
                                        style = SabouType.caption, color = Sabou.colors.ink,
                                    )
                                    if (r.actualCostShort) Text("بخشی از مواد هنگام فروش در موجودی دفتری نبود؛ این بها تا تعیین تکلیف کسری کمتر از واقع است.",
                                        style = SabouType.caption, color = Sabou.colors.danger)
                                }
                            }
                        }
                    }
                    ExportButtons("اقلام-پرفروش") { reports.productMix(branch, from, to).let { m -> listOfNotNull(ReportTables.productMix(m), m.channels.takeIf { it.isNotEmpty() }?.let { ReportTables.channels(m) }) } }
                }
            }
        }
    }

    /** Menu engineering (ADR-0028): each dish by popularity and margin, with what to do about it. */
    @Composable
    fun SalesForecast(nav: Nav) {
        val session = LocalSession.current
        var editing by rememberSaveable { mutableStateOf<Long?>(null) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("پیش‌بینی فروش", "خالص فروش، دو هفته‌ی آینده", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { forecasts.sales(branch, session.today, 14) }
                Page {
                    Text("از میانگین همان روز هفته در ۸ هفته‌ی گذشته (هفته‌های نزدیک‌تر وزن بیشتر؛ فقط روزهای ثبت‌شده: روزی که بی‌فروش بسته شده صفر حساب می‌شود و روز ثبت‌نشده یا تعطیل کنار می‌ماند؛ تعطیل و مناسبت گذشته به روز عادی برگردانده می‌شود)؛ بازه از پراکندگی همان روزها. روز تعطیل یا مناسبت را علامت بزنید تا پیش‌بینی، برنامه‌ی شیفت و آماده‌سازی با آن جور شود.",
                        style = SabouType.caption, color = Sabou.colors.muted)
                    action.error?.let { Banner(it) }
                    Loaded(data) { f ->
                        SCard {
                            KeyValue("روند ۴ هفته‌ی اخیر", (if (f.trendBp >= 10_000) "+" else "−") + Fa.percent(kotlin.math.abs(f.trendBp - 10_000)))
                            KeyValue("خطای پیش‌بینی در ۴ هفته‌ی گذشته", f.errorBp?.let(Fa::percent) ?: "سابقه‌ی کافی نیست")
                        }
                        SCard {
                            f.days.forEach { d ->
                                Row(Modifier.fillMaxWidth().clickable { editing = if (editing == d.date.epochDay) null else d.date.epochDay }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(Fa.dayTitle(d.date), style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                        d.special?.let { s ->
                                            Text(when (s.kind) { ir.sabou.sales.SpecialDay.Kind.CLOSED -> "تعطیل"; ir.sabou.sales.SpecialDay.Kind.HOLIDAY -> "تعطیل رسمی ${Fa.number(s.percent.toLong())}٪"; ir.sabou.sales.SpecialDay.Kind.EVENT -> "مناسبت ${Fa.number(s.percent.toLong())}٪" } +
                                                if (s.note.isNotBlank()) " · ${s.note}" else "", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(if (d.samples == 0) "—" else Fa.rialShort(d.expected), style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                        if (d.samples > 0 && d.expected > 0) Text("${Fa.rialShort(d.low)} تا ${Fa.rialShort(d.high)}", style = SabouType.caption, color = Sabou.colors.muted)
                                    }
                                }
                                if (editing == d.date.epochDay && session.can(Permission.SHIFT_MANAGE)) SpecialDayForm(branch, d.date, d.special, action) { editing = null }
                                Divider()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Cash forecast (ADR-0051): 13 weeks from what is in the till, with the dated documents and the sales pattern. */
    @Composable
    fun CashForecast(nav: Nav) {
        val session = LocalSession.current
        var preset by rememberSaveable { mutableStateOf(0) }
        var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
        val presets = ir.sabou.core.CashScenario.PRESETS
        Column(Modifier.fillMaxSize()) {
            Header("پیش‌بینی نقدینگی", "۱۳ هفته‌ی آینده", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val scenario = presets[preset]
                val data by load(session, branch, preset) { cashFlows.forecast(branch, session.today, scenario) }
                Page {
                    ir.sabou.app.ui.components.Segmented(presets.map { it.name }, preset, { preset = it })
                    Text(when (preset) { 1 -> "فروش ۱۵٪ کمتر و همه‌ی مشتریان ۳۰ روز دیرتر"; 2 -> "فروش ۱۰٪ بیشتر"; else -> "فروش مثل پیش‌بینی و وصول مثل سابقه‌ی هر مشتری" },
                        style = SabouType.caption, color = Sabou.colors.muted)
                    Loaded(data) { f ->
                        SCard {
                            KeyValue("نقد و بانک امروز (ریال)", Fa.rial(f.opening), strong = true)
                            f.lowest?.let { KeyValue("کمترین مانده (هفته‌ی ${Fa.number(it.index.toLong())})", Fa.rial(it.closing), if (it.closing < 0) Sabou.colors.danger else Sabou.colors.ink) }
                            KeyValue("مانده‌ی پایان هفته‌ی ${Fa.number(f.weeks.size.toLong())}", Fa.rial(f.weeks.last().closing))
                        }
                        f.firstShortWeek?.let { w -> Banner("در هفته‌ی ${Fa.number(w.index.toLong())} (تا ${Fa.date(w.to)}) مانده به ${Fa.rial(w.closing)} ریال می‌رسد.") }
                        Text(ReportTables.cashAssumptions(f.assumptions), style = SabouType.caption, color = Sabou.colors.muted)
                        SCard {
                            f.weeks.forEach { w ->
                                Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == w.index) null else w.index }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("هفته‌ی ${Fa.number(w.index.toLong())} · ${Fa.date(w.from)}", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                        Text("ورودی ${Fa.rialShort(w.inflow)} · خروجی ${Fa.rialShort(w.outflow)}", style = SabouType.caption, color = Sabou.colors.muted)
                                    }
                                    Text(Fa.rialShort(w.closing), style = SabouType.bodyStrong, color = if (w.closing < 0) Sabou.colors.danger else Sabou.colors.ink)
                                }
                                if (expanded == w.index) {
                                    ir.sabou.core.CashItem.Kind.entries.forEach { k ->
                                        val v = w.byKind[k] ?: 0L
                                        if (v != 0L) KeyValue(ReportTables.cashKindName(k), Fa.rial(v), if (v < 0) Sabou.colors.danger else Sabou.colors.ink)
                                    }
                                    f.items.filter { it.date >= w.from && it.date <= w.to && !it.estimated }.forEach { i ->
                                        Text("${Fa.date(i.date)} · ${i.text} · ${Fa.rial(i.amount)}", style = SabouType.caption, color = Sabou.colors.muted)
                                    }
                                }
                                Divider()
                            }
                        }
                        if (f.unscheduled.isNotEmpty()) SCard {
                            Text("بدون تاریخ (در جدول نیامده)", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            f.unscheduled.forEach { u -> KeyValue(u.title, Fa.rial(u.amount)) }
                        }
                        Text(ReportTables.CASH_BASIS, style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    ExportButtons("پیش‌بینی-نقدینگی") { ReportTables.cashForecast(cashFlows.forecast(branch, session.today, scenario)) }
                }
            }
        }
    }

    /** Credit customers (ADR-0051): how much of the sales each one is, what they owe and how late they usually pay. */
    @Composable
    fun CreditCustomers(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("مشتریان نسیه", "۹۰ روز گذشته", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { cashFlows.customers(branch, session.today) }
                Page {
                    Loaded(data) { r ->
                        SCard {
                            KeyValue("فروش شعبه (ریال)", Fa.rial(r.totalSales), strong = true)
                            KeyValue("سهم فروش نسیه", r.creditShareBp?.let(Fa::percent) ?: "—")
                            KeyValue("سهم بزرگ‌ترین مشتری", r.topShareBp?.let(Fa::percent) ?: "—")
                            KeyValue("سهم سه مشتری بزرگ", r.topThreeShareBp?.let(Fa::percent) ?: "—")
                        }
                        if (r.rows.isEmpty()) EmptyState("در این بازه فروش نسیه یا طلبی نیست.")
                        r.rows.forEach { c ->
                            SCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.customer.name, style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                    Text(c.shareBp?.let(Fa::percent) ?: "—", style = SabouType.bodyStrong, color = Sabou.colors.primary)
                                }
                                Text("فروش نسیه ${Fa.rial(c.sales)} · مانده ${Fa.rial(c.outstanding)}", style = SabouType.caption, color = Sabou.colors.muted)
                                if (c.overdue > 0) Text("سررسیدگذشته ${Fa.rial(c.overdue)} ریال", style = SabouType.caption, color = Sabou.colors.danger)
                                Text(c.delayDays?.let { "معمولاً ${Fa.number(it.toLong())} روز پس از سررسید می‌پردازد · وصول ${Fa.number((c.collectionDays ?: 0).toLong())} روز پس از فروش" }
                                    ?: "هنوز طلبی از او کامل وصول نشده", style = SabouType.caption, color = Sabou.colors.muted)
                            }
                        }
                    }
                    ExportButtons("مشتریان-نسیه") { listOf(ReportTables.customers(cashFlows.customers(branch, session.today))) }
    /** Why profit changed (ADR-0053): the period against the one of the same length before it, cause by cause. */
    @Composable
    fun ProfitBridge(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(monthStart(session.today)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
        Column(Modifier.fillMaxSize()) {
            Header("چرا سود تغییر کرد", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, from, to) {
                    val (f0, t0) = profitBridges.previous(from, to)
                    profitBridges.of(branch, from, to, f0, t0)
                }
                Page {
                    PeriodPicker(from, to) { f, t -> from = f; to = t }
                    Loaded(data) { b ->
                        Text("در برابر ${Fa.date(b.base.from)} تا ${Fa.date(b.base.to)} (همان تعداد روز، پیش از این بازه)", style = SabouType.caption, color = Sabou.colors.muted)
                        if (b.provisional) Banner("کسری انبارِ تعیین‌تکلیف‌نشده هست؛ ارقام قطعی نیست.")
                        SCard { b.summary.forEach { Text(it, style = SabouType.body, color = Sabou.colors.ink) } }
                        SCard {
                            KeyValue("سود دوره‌ی مبنا (ریال)", Fa.rial(b.base.profit), strong = true)
                            Divider()
                            val biggest = b.steps.maxOfOrNull { kotlin.math.abs(it.amount) }?.takeIf { it > 0 } ?: 1L
                            b.steps.filter { it.amount != 0L }.forEachIndexed { i, st ->
                                Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == i) null else i }.padding(vertical = 4.dp)) {
                                    KeyValue(ir.sabou.core.ProfitBridges.stepName(st.kind), (if (st.amount > 0) "+" else "") + Fa.rial(st.amount),
                                        if (st.amount < 0) Sabou.colors.danger else Sabou.colors.primary)
                                    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(SabouShapes.chip).background(Sabou.colors.track)) {
                                        Box(Modifier.fillMaxWidth(kotlin.math.abs(st.amount).toFloat() / biggest).padding(vertical = 3.dp).clip(SabouShapes.chip)
                                            .background(if (st.amount < 0) Sabou.colors.danger else Sabou.colors.primary))
                                    }
                                    if (expanded == i) st.details.forEach { d -> Text("${d.name}: ${Fa.rial(d.amount)}", style = SabouType.caption, color = Sabou.colors.muted) }
                                }
                            }
                            Divider()
                            KeyValue("سود این دوره (ریال)", Fa.rial(b.current.profit), strong = true)
                        }
                        Text("عامل‌ها دقیقاً با تغییر سود در دفاتر برابرند؛ تعداد، ترکیب، قیمت و بهای مواد از ردیف‌های فروش و بهای واقعی هنگام فروش است.",
                            style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    ExportButtons("چرا-سود-تغییر-کرد") { val (f0, t0) = profitBridges.previous(from, to); listOf(ReportTables.profitBridge(profitBridges.of(branch, from, to, f0, t0))) }
                }
            }
        }
    }

    @Composable
    private fun SpecialDayForm(branch: Scope.Branch, date: BusinessDate, current: ir.sabou.sales.SpecialDay?, action: ir.sabou.app.ui.Action, onDone: () -> Unit) {
        var kind by rememberSaveable { mutableStateOf((current?.kind ?: ir.sabou.sales.SpecialDay.Kind.HOLIDAY).ordinal) }
        var percent by rememberSaveable { mutableStateOf(current?.percent?.takeIf { it > 0 }?.let { Fa.number(it.toLong()) } ?: "") }
        var note by rememberSaveable { mutableStateOf(current?.note.orEmpty()) }
        val kinds = ir.sabou.sales.SpecialDay.Kind.entries
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ir.sabou.app.ui.components.Segmented(listOf("تعطیل", "تعطیل رسمی", "مناسبت"), kind, { kind = it })
            val closed = kinds[kind] == ir.sabou.sales.SpecialDay.Kind.CLOSED
            if (!closed) TextInput("درصد فروش نسبت به روز عادی (مثلاً ۱۵۰)", percent, { percent = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            TextInput("توضیح", note, { note = it })
            val p = Fa.latinDigits(percent.trim()).toIntOrNull()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("ثبت", {
                    action.run({ specialDayOps.set(ir.sabou.sales.SetSpecialDay(GlobalId.new(), branch, date, kinds[kind], if (closed) 0 else p!!, note)) }) { onDone() }
                }, Modifier.weight(1f), enabled = (closed || p != null) && !action.busy)
                if (current != null) SecondaryButton("حذف", {
                    action.run({ specialDayOps.set(ir.sabou.sales.SetSpecialDay(GlobalId.new(), branch, date, current.kind, current.percent, current.note, remove = true)) }) { onDone() }
                }, Modifier.weight(1f), danger = true)
            }
        }
    }

    @Composable
    fun DailyPnl(nav: Nav) {
        val session = LocalSession.current
        var date by rememberSaveable { mutableStateOf(session.today) }
        Column(Modifier.fillMaxSize()) {
            Header("سود و زیان روزانه", "در برابر بودجه", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, date) { reports.dailyPnl(branch, date) }
                Page {
                    DateInput("روز", date, { date = it }, session.today)
                    Loaded(data) { p ->
                        val salesDay = p.line(ir.sabou.core.DailyPnl.Group.SALES).day
                        val salesMtd = p.line(ir.sabou.core.DailyPnl.Group.SALES).mtd
                        SCard {
                            PnlRow("", "امروز", "بودجه", "ماه تا امروز", "بودجه", header = true)
                            Divider()
                            p.lines.forEach { l ->
                                val name = when (l.group) {
                                    ir.sabou.core.DailyPnl.Group.SALES -> "فروش"
                                    ir.sabou.core.DailyPnl.Group.FOOD_COST -> "بهای غذا"
                                    ir.sabou.core.DailyPnl.Group.LABOR -> "نیروی کار"
                                    ir.sabou.core.DailyPnl.Group.OTHER -> "سایر هزینه‌ها"
                                }
                                PnlRow(name, Fa.rialShort(l.day) + if (l.dayEstimated) "*" else "", Fa.rialShort(l.dayBudget),
                                    Fa.rialShort(l.mtd) + if (l.mtdEstimated) "*" else "", Fa.rialShort(l.mtdBudget))
                                if (l.group != ir.sabou.core.DailyPnl.Group.SALES) {
                                    val d = p.bp(l.day, salesDay); val m = p.bp(l.mtd, salesMtd)
                                    if (d != null || m != null) PnlRow("", d?.let(Fa::percent) ?: "", "", m?.let(Fa::percent) ?: "", "", muted = true)
                                }
                            }
                            Divider()
                            PnlRow("سود", Fa.rialShort(p.dayProfit), Fa.rialShort(p.dayProfitBudget), Fa.rialShort(p.mtdProfit), Fa.rialShort(p.mtdProfitBudget), header = true,
                                warn = p.mtdProfit < p.mtdProfitBudget)
                        }
                        SCard {
                            KeyValue("فروش ماه با همین روند", Fa.rial(p.salesRunRate) + " ریال")
                            if (p.monthSalesBudget > 0) KeyValue("بودجه‌ی فروش ماه", Fa.rial(p.monthSalesBudget) + " ریال",
                                if (p.salesRunRate < p.monthSalesBudget) Sabou.colors.danger else Sabou.colors.ink)
                        }
                        Text("* برآورد: حقوق، اجاره و هزینه‌هایی که ماهانه ثبت می‌شوند، تا ثبت شدن به اندازه‌ی بودجه‌ی روزانه‌شان حساب می‌شوند تا سود روز بیش از واقع نشان داده نشود. " +
                            "هزینه‌ای که بودجه ندارد همان مبلغ ثبت‌شده است.", style = SabouType.caption, color = Sabou.colors.muted)
                        if (session.can(Permission.BUDGET_MANAGE)) SecondaryButton("تنظیم بودجه", { nav.go(Route.BudgetReport) })
                    }
                }
            }
        }
    }

    @Composable
    private fun PnlRow(name: String, day: String, dayBudget: String, mtd: String, mtdBudget: String, header: Boolean = false, muted: Boolean = false, warn: Boolean = false) {
        val style = if (header) SabouType.bodyStrong else if (muted) SabouType.caption else SabouType.body
        val color = if (warn) Sabou.colors.danger else if (muted) Sabou.colors.muted else Sabou.colors.ink
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(name, style = style, color = color, modifier = Modifier.weight(1.3f))
            listOf(day, dayBudget, mtd, mtdBudget).forEachIndexed { i, v ->
                Text(v, style = style, color = if (i % 2 == 1) Sabou.colors.muted else color, modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    fun MenuEngineering(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(session.today.plusDays(-29)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        var target by rememberSaveable { mutableStateOf("") }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("مهندسی منو", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, from, to) { reports.menuEngineering(branch, from, to) }
                Page {
                    PeriodPicker(from, to) { f, t -> from = f; to = t }
                    Text("محبوب: سهم پرس‌ها دست‌کم ۷۰٪ سهم برابر. سودده: حاشیه‌ی هر پرس (قیمت − بهای امروز مواد) دست‌کم میانگین منو. بهای مواد با قیمت خرید خودش به‌روز می‌شود.",
                        style = SabouType.caption, color = Sabou.colors.muted)
                    Text(COST_BASIS_NOTE, style = SabouType.caption, color = Sabou.colors.muted)
                    Loaded(data) { e ->
                        SCard {
                            KeyValue("هدف بهای غذا", e.targetBp?.let(Fa::percent) ?: "تعیین نشده")
                            e.averageMargin?.let { KeyValue("میانگین حاشیه‌ی هر پرس", Fa.rial(it) + " ریال") }
                            KeyValue("مرز محبوبیت", Fa.percent(e.popularityBp))
                        }
                        if (session.can(Permission.MENU_PRICE_MANAGE)) FormCard("هدف بهای غذا") {
                            TextInput("درصد هدف (مثلاً ۳۰)", target, { target = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                            action.error?.let { Banner(it) }
                            val bp = Fa.latinDigits(target).trim().toLongOrNull()?.times(100)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton("ذخیره", { action.run({ salesOps.setFoodCostTarget(ir.sabou.sales.SetFoodCostTarget(GlobalId.new(), bp)) }) { target = "" } },
                                    enabled = bp != null && !action.busy)
                                if (e.targetBp != null) SecondaryButton("برداشتن هدف", { action.run({ salesOps.setFoodCostTarget(ir.sabou.sales.SetFoodCostTarget(GlobalId.new(), null)) }) })
                            }
                        }
                        if (e.rows.isEmpty()) EmptyState("در این بازه فروش ثبت نهایی نشده است.")
                        val groups = listOf(
                            ir.sabou.core.MenuClass.STAR to ("ستاره‌ها" to "محبوب و سودده: همین‌طور نگه دارید و جای خوب منو بدهید."),
                            ir.sabou.core.MenuClass.PLOWHORSE to ("اسب‌های کاری" to "محبوب ولی کم‌سود: کمی قیمت یا بهای مواد (اندازه‌ی پرس، ماده‌ی ارزان‌تر) را اصلاح کنید."),
                            ir.sabou.core.MenuClass.PUZZLE to ("معماها" to "سودده ولی کم‌فروش: معرفی کنید، جای بهتری در منو بدهید یا نامش را جذاب‌تر کنید."),
                            ir.sabou.core.MenuClass.DOG to ("سگ‌ها" to "کم‌فروش و کم‌سود: از منو بردارید یا از نو طراحی کنید."),
                            null to ("بدون بهای مواد" to "رسپی یا قیمت خرید مواد معلوم نیست."),
                        )
                        groups.forEach { (cls, label) ->
                            val rows = e.rows.filter { it.menuClass == cls }
                            if (rows.isEmpty()) return@forEach
                            SectionTitle(label.first, Fa.number(rows.size.toLong()))
                            Text(label.second, style = SabouType.caption, color = Sabou.colors.muted)
                            rows.forEach { r ->
                                SCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(r.mix.name, style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                        r.mix.costBp?.let { Text("بهای غذا ${Fa.percent(it)}", style = SabouType.label, color = if (r.overTarget) Sabou.colors.danger else Sabou.colors.primary) }
                                    }
                                    Text("${Fa.quantity(r.mix.portions)} پرس (${Fa.percent(r.portionShareBp)}) · ${Fa.rial(r.mix.gross)} ریال", style = SabouType.caption, color = Sabou.colors.muted)
                                    r.mix.unitMargin?.let { m ->
                                        Text("قیمت ${r.mix.averagePrice?.let { Fa.rial(it) }} · مواد ${r.mix.unitCost?.let { Fa.rial(it) }} · حاشیه ${Fa.rial(m)} ریال", style = SabouType.caption, color = Sabou.colors.muted)
                                    }
                                    if (r.overTarget) Text("بیش از هدف بهای غذا", style = SabouType.caption, color = Sabou.colors.danger)
                                }
                            }
                        }
                    }
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
                            KeyValue("مصرف واقعی (ریال)", Fa.rial(u.actualValue), strong = true)
                            KeyValue("مصرف تئوریک (فروش)", Fa.rial(u.theoreticalValue))
                            KeyValue("ضایعات ثبت‌شده", Fa.rial(u.wasteValue))
                            if (u.compsValue != 0L) KeyValue("پذیرایی، غذای پرسنل و اهدایی", Fa.rial(u.compsValue))
                            KeyValue("اختلاف توضیح‌داده‌نشده", Fa.rial(u.unexplainedValue), if (u.unexplainedValue > 0) Sabou.colors.danger else Sabou.colors.ink)
                            Text("اختلاف توضیح‌داده‌نشده همان کسری انبارگردانی است؛ بدون انبارگردانی در بازه، صفر می‌ماند.", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                        SCard {
                            KeyValue("انبارگردانی تأییدشده در این بازه", Fa.number(u.counts.toLong()))
                            u.lastCount?.let { KeyValue("آخرین انبارگردانی", Fa.date(it)) }
                            KeyValue("کالاهای شمرده‌شده", "${Fa.number(u.countedRows.toLong())} از ${Fa.number(u.rows.size.toLong())}")
                        }
                        if (!u.measuredByCount) Banner(
                            "در این بازه انبارگردانی تأییدشده‌ای نیست؛ «مصرف واقعی» فقط عدد دفاتر است، نه اندازه‌گیری انبار. مصرفی که ثبت نشده (ضایعات یا کسری) در آن دیده نمی‌شود. " +
                                "برای مصرف واقعی، اول و آخر بازه انبار را بشمارید.",
                            ChipKind.ACCENT,
                        ) else if (u.countedRows < u.rows.size) Text(
                            "برای کالاهایی که در این بازه شمرده نشده‌اند، «واقعی» عدد دفاتر است و اختلافشان دیده نمی‌شود.",
                            style = SabouType.caption, color = Sabou.colors.muted,
                        )
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

/** Product mix and menu engineering use today's ingredient costs; the period's profit is the P&L (from the books). */
internal const val COST_BASIS_NOTE =
    "بهای مواد در این صفحه با قیمت امروز مواد حساب می‌شود (برای تصمیم قیمت و منو). سود واقعی این بازه را «سود و زیان» از دفاتر نشان می‌دهد."

internal const val MIX_COST_NOTE =
    "«بهای مواد هر پرس» با قیمت امروز مواد است (برای تصمیم قیمت و منو). «بهای واقعی هنگام فروش» همان است که هر فروش هنگام ثبت از انبار برداشت؛ سود کل این بازه را «سود و زیان» از دفاتر نشان می‌دهد."
