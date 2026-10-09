package ir.sabou.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.export.ChequePrint
import ir.sabou.app.ui.ExportButtons
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.Confirm
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.MoneyInput
import ir.sabou.app.ui.components.NavRow
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.Segmented
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.rememberCommandId
import ir.sabou.app.ui.rememberValueMap
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.assets.AcquireAsset
import ir.sabou.assets.AssetStatus
import ir.sabou.assets.DepreciationMethod
import ir.sabou.assets.DisposeAsset
import ir.sabou.assets.Funding
import ir.sabou.assets.ReverseDepreciationRun
import ir.sabou.assets.RunDepreciation
import ir.sabou.core.Fa
import ir.sabou.core.Messages
import ir.sabou.core.ReportTables
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.BudgetPeriod
import ir.sabou.ledger.SetBudget
import ir.sabou.platform.Permission
import ir.sabou.treasury.BounceCheque
import ir.sabou.treasury.ChequeDirection
import ir.sabou.treasury.ChequeStatus
import ir.sabou.treasury.ClearIssuedCheque
import ir.sabou.treasury.CollectCheque
import ir.sabou.treasury.DepositCheque
import ir.sabou.treasury.RecallCheque
import ir.sabou.treasury.SettleBouncedCheque
import ir.sabou.treasury.TreasuryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cheques, budgets and fixed assets (ADR-0013). */
object BooksScreens {
    private fun statusKind(s: ChequeStatus) = when (s) {
        ChequeStatus.BOUNCED -> ChipKind.DANGER
        ChequeStatus.IN_HAND, ChequeStatus.DEPOSITED, ChequeStatus.ISSUED -> ChipKind.ACCENT
        ChequeStatus.VOID -> ChipKind.NEUTRAL
        else -> ChipKind.PRIMARY
    }

    // ------------------------------------------------------------ Cheques

    @Composable
    fun Cheques(nav: Nav) {
        val session = LocalSession.current
        var tab by rememberSaveable { mutableStateOf(0) }
        val data by load(session, tab) {
            when (tab) {
                0 -> books.chequesDue(session.today, 14)
                1 -> books.cheques(ChequeDirection.RECEIVED)
                else -> books.cheques(ChequeDirection.ISSUED)
            }
        }
        Column(Modifier.fillMaxSize()) {
            Header("چک‌ها", onBack = nav.back)
            Page {
                Segmented(listOf("سررسید نزدیک", "دریافتی", "پرداختی"), tab, { tab = it })
                Text("چک دریافتی را هنگام «دریافت وجه» یا «دریافت از مشتری» با انتخاب صندوق چک ثبت کنید؛ چک ما را هنگام پرداخت با انتخاب دسته‌چک.",
                    style = SabouType.caption, color = Sabou.colors.muted)
                Loaded(data) { list ->
                    if (list.isEmpty()) EmptyState(if (tab == 0) "چکی در دو هفته‌ی آینده سررسید نمی‌شود." else "چکی ثبت نشده است.")
                    list.forEach { r ->
                        val c = r.cheque
                        val overdue = c.pending && c.dueDate < session.today
                        NavRow(R.drawable.ic_payment, "${c.details.counterparty} · ${Fa.digits(c.details.number)}",
                            "${if (c.direction == ChequeDirection.RECEIVED) "دریافتی" else "پرداختی"} · سررسید ${Fa.date(c.dueDate)}" +
                                (if (overdue) " · گذشته" else "") + " · ${ReportTables.chequeStatusName(c.status)}",
                            Fa.tomanShort(c.amount.rial), tint = if (overdue || c.status == ChequeStatus.BOUNCED) Sabou.colors.danger else Sabou.colors.bank,
                            tile = Sabou.colors.bankSoft, onClick = { nav.go(Route.ChequeDetail(c.id)) })
                    }
                }
                ExportButtons("چک‌ها") {
                    listOf(ReportTables.cheques(books.cheques(ChequeDirection.RECEIVED), "چک‌های دریافتی"), ReportTables.cheques(books.cheques(ChequeDirection.ISSUED), "چک‌های پرداختی"))
                }
            }
        }
    }

    @Composable
    fun ChequeDetail(nav: Nav, chequeId: GlobalId) {
        val session = LocalSession.current
        val context = LocalContext.current
        val data by load(session, chequeId) {
            val row = books.cheque(chequeId)
            row to overview.paymentAccounts(row.cheque.scope)
        }
        var bankId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var settleAccount by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var date by rememberSaveable { mutableStateOf(session.today) }
        var reason by rememberSaveable { mutableStateOf("") }
        var confirmBounce by rememberSaveable { mutableStateOf(false) }
        var printError by remember { mutableStateOf<String?>(null) }
        val id = rememberCommandId(chequeId)
        val action = rememberAction()
        val print = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            session.scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val cheque = session.core.books.cheque(chequeId).cheque
                        (context.contentResolver.openOutputStream(uri, "w") ?: error("EXPORT_TARGET_UNAVAILABLE")).use { ChequePrint.write(context, cheque, it) }
                    }
                }
                printError = result.exceptionOrNull()?.let(Messages::of)
            }
        }
        Column(Modifier.fillMaxSize()) {
            Header("چک", onBack = nav.back)
            Page {
                Loaded(data) { (row, accounts) ->
                    val c = row.cheque
                    val banks = accounts.filter { it.kind == TreasuryKind.BANK }
                    val ordinary = accounts.filter { it.kind.isOrdinary }
                    SCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${c.details.counterparty}", style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                            Chip(ReportTables.chequeStatusName(c.status), statusKind(c.status))
                        }
                        KeyValue("مبلغ (تومان)", Fa.toman(c.amount), strong = true)
                        KeyValue("شماره / بانک", "${Fa.digits(c.details.number)} · ${c.details.bank}")
                        if (c.details.sayadId.isNotBlank()) KeyValue("شناسه صیادی", Fa.digits(c.details.sayadId))
                        KeyValue("سررسید", Fa.dayTitle(c.dueDate) + " " + Fa.digits(Fa.jalali(c.dueDate).year.toString()))
                        KeyValue(if (c.direction == ChequeDirection.RECEIVED) "در صندوق" else "دسته‌چک", row.account)
                        row.bank?.let { KeyValue("بانک ما", it) }
                        Divider()
                        c.events.forEach { e ->
                            Text("${Fa.date(e.date)} · ${ReportTables.chequeStatusName(e.status)}" + (if (e.reversed) " (برگشت‌خورده)" else "") + if (e.note.isNotBlank()) " · ${e.note}" else "",
                                style = SabouType.caption, color = if (e.reversed) Sabou.colors.subtle else Sabou.colors.muted)
                        }
                    }
                    action.error?.let { Banner(it) }
                    printError?.let { Banner(it) }
                    val can = session.can(Permission.CHEQUE_MANAGE)
                    if (c.direction == ChequeDirection.ISSUED && c.status == ChequeStatus.ISSUED) {
                        SecondaryButton("چاپ روی برگ چک (PDF)", { print.launch("cheque-${Fa.latinDigits(c.details.number)}.pdf") })
                        Text("جای هر خط روی برگ چک بانک‌ها کمی فرق دارد؛ اول روی کاغذ معمولی آزمایش کنید.", style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    if (can && c.status in setOf(ChequeStatus.IN_HAND, ChequeStatus.DEPOSITED, ChequeStatus.ISSUED, ChequeStatus.BOUNCED)) FormCard("ثبت وضعیت") {
                        DateInput("تاریخ", date, { date = it }, session.today)
                        when (c.status) {
                            ChequeStatus.IN_HAND, ChequeStatus.DEPOSITED -> {
                                val bank = bankId ?: c.bankAccountId ?: banks.firstOrNull()?.id
                                PickerOrHint("حساب بانکی", banks.map { Choice(it.id, it.name) }, bank, { bankId = it }, "حساب بانکی برای این شعبه تعریف نشده است.")
                                if (c.status == ChequeStatus.IN_HAND) SecondaryButton("واگذاری به بانک برای وصول", {
                                    action.run({ chequeOps.deposit(DepositCheque(id.value, c.scope, c.id, bank!!, date)) }) { id.value = GlobalId.new() }
                                }, enabled = bank != null && !action.busy)
                                if (c.status == ChequeStatus.DEPOSITED) SecondaryButton("پس گرفتن از بانک", {
                                    action.run({ chequeOps.recall(RecallCheque(id.value, c.scope, c.id, date, "پس گرفته شد")) }) { id.value = GlobalId.new() }
                                }, enabled = !action.busy)
                                PrimaryButton("وصول شد (به حساب بانک)", {
                                    action.run({ chequeOps.collect(CollectCheque(id.value, c.scope, c.id, bank!!, date)) }) { id.value = GlobalId.new() }
                                }, enabled = bank != null, busy = action.busy)
                            }
                            ChequeStatus.ISSUED -> PrimaryButton("پاس شد (از حساب ${row.bank ?: "بانک"})", {
                                action.run({ chequeOps.clear(ClearIssuedCheque(id.value, c.scope, c.id, date)) }) { id.value = GlobalId.new() }
                            }, busy = action.busy)
                            ChequeStatus.BOUNCED -> {
                                Text(if (c.direction == ChequeDirection.RECEIVED) "وقتی صاحب چک مبلغ را پرداخت کرد:" else "وقتی مبلغ را به گیرنده پرداختیم:",
                                    style = SabouType.caption, color = Sabou.colors.muted)
                                Picker("حساب", accountChoices(ordinary), settleAccount, { settleAccount = it })
                                PrimaryButton("تسویه‌ی چک برگشتی", {
                                    action.run({ chequeOps.settleBounced(SettleBouncedCheque(id.value, c.scope, c.id, settleAccount!!, date)) }) { id.value = GlobalId.new() }
                                }, enabled = settleAccount != null, busy = action.busy)
                            }
                            else -> Unit
                        }
                        if (c.status != ChequeStatus.BOUNCED) {
                            Divider()
                            TextInput("دلیل برگشت", reason, { reason = it })
                            SecondaryButton("چک برگشت خورد", { confirmBounce = true }, enabled = reason.trim().length >= 3, danger = true)
                        }
                    }
                    if (confirmBounce) Confirm("ثبت برگشت چک؟", if (c.direction == ChequeDirection.RECEIVED) "مبلغ به «چک‌های دریافتی برگشتی» می‌رود تا تسویه شود."
                        else "مبلغ به «چک‌های پرداختی برگشتی» می‌رود تا پرداخت شود.", "ثبت برگشت",
                        onConfirm = { confirmBounce = false; action.run({ chequeOps.bounce(BounceCheque(id.value, c.scope, c.id, date, reason)) }) { id.value = GlobalId.new(); reason = "" } },
                        onDismiss = { confirmBounce = false }, danger = true)
                }
            }
        }
    }

    // ------------------------------------------------------------ Budget

    private fun monthRange(year: Int, month: Int): Pair<BusinessDate, BusinessDate> =
        Fa.fromJalali(year, month, 1) to Fa.fromJalali(year, month, Fa.monthLength(year, month))

    @Composable
    fun Budget(nav: Nav) {
        val session = LocalSession.current
        val thisYear = Fa.jalali(session.today).year
        var yearOffset by rememberSaveable { mutableStateOf(0) }
        val year = thisYear + yearOffset
        var orgLevel by rememberSaveable { mutableStateOf(false) }
        var account by rememberSaveable { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize()) {
            Header("بودجه", "مبلغ هر ماه برای هر حساب درآمد و هزینه", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val scope: Scope = if (orgLevel) Scope.Organization else branch
                val accounts by load(session) { books.budgetAccounts() }
                val existing by load(session, scope, account, year) {
                    val code = account?.let(AccountCode::of)
                    if (code == null) emptyMap<BusinessDate, Money>() else books.budgetEntries(scope).filter { it.account == code }.associate { it.from to it.amount }
                }
                Page {
                    Segmented(listOf(Fa.digits(thisYear.toString()), Fa.digits((thisYear + 1).toString())), yearOffset, { yearOffset = it })
                    if (session.can(Permission.ORGANIZATION_DATA)) Segmented(listOf("شعبه", "دفتر مرکزی"), if (orgLevel) 1 else 0, { orgLevel = it == 1 })
                    Loaded(accounts) { list ->
                        Picker("حساب", list.map { Choice(it.code.value, it.name, it.code.value) }, account, { account = it })
                    }
                    if (account != null) Loaded(existing) { saved ->
                        androidx.compose.runtime.key(scope, account, year) { BudgetMonths(scope, AccountCode.of(account!!), year, saved) }
                    }
                }
            }
        }
    }

    @Composable
    private fun BudgetMonths(scope: Scope, account: AccountCode, year: Int, saved: Map<BusinessDate, Money>) {
        val amounts = rememberValueMap<Int, Money?>()
        var sameForAll by rememberSaveable { mutableStateOf<Money?>(null) }
        val id = rememberCommandId(scope, account, year)
        val action = rememberAction()
        FormCard("ماه‌های ${Fa.digits(year.toString())}") {
            MoneyInput("یک مبلغ برای همه‌ی ماه‌ها", sameForAll, { v -> sameForAll = v; if (v != null) (1..12).forEach { amounts[it] = v } })
            (1..12).forEach { m ->
                val (from, _) = monthRange(year, m)
                androidx.compose.runtime.key(m, sameForAll) {
                    MoneyInput(Fa.monthNames[m - 1], if (amounts.containsKey(m)) amounts[m] else saved[from], { amounts[m] = it })
                }
            }
            val total = (1..12).sumOf { m -> (if (amounts.containsKey(m)) amounts[m] else saved[monthRange(year, m).first])?.rial ?: 0L }
            KeyValue("جمع سال (تومان)", Fa.toman(total), strong = true)
            action.error?.let { Banner(it) }
            PrimaryButton("ذخیره بودجه", {
                val periods = (1..12).mapNotNull { m ->
                    val (from, to) = monthRange(year, m)
                    (if (amounts.containsKey(m)) amounts[m] else saved[from])?.let { BudgetPeriod(from, to, it) }
                }
                action.run({ budgets.set(SetBudget(id.value, scope, account, periods)) }) { id.value = GlobalId.new() }
            }, busy = action.busy)
        }
    }

    @Composable
    fun BudgetReport(nav: Nav) {
        val session = LocalSession.current
        val today = Fa.jalali(session.today)
        var mode by rememberSaveable { mutableStateOf(0) }
        var month by rememberSaveable { mutableStateOf(today.month) }
        var allBranches by rememberSaveable { mutableStateOf(true) }
        val (from, to) = if (mode == 0) monthRange(today.year, month) else Fa.fromJalali(today.year, 1, 1) to monthRange(today.year, 12).second
        val branch = session.branch
        val scope: Scope? = if (allBranches) null else branch
        val data by load(session, from, to, scope) { books.budgetVsActual(from, to, scope) }
        Column(Modifier.fillMaxSize()) {
            Header("بودجه در برابر عملکرد", onBack = nav.back) { BranchSwitcher() }
            Page {
                SCard {
                    Segmented(listOf("ماه", "سال ${Fa.digits(today.year.toString())}"), mode, { mode = it })
                    if (mode == 0) Picker("ماه", (1..12).map { Choice(it, Fa.monthNames[it - 1]) }, month, { month = it })
                    Segmented(listOf("همه‌ی شعب", "این شعبه"), if (allBranches) 0 else 1, { allBranches = it == 0 })
                }
                if (session.can(Permission.BUDGET_MANAGE)) NavRow(R.drawable.ic_settings, "تعیین بودجه", "مبلغ ماهانه‌ی هر حساب", onClick = { nav.go(Route.Budget) })
                Loaded(data) { r ->
                    if (r.lines.isEmpty()) EmptyState("برای این دوره بودجه یا گردشی نیست.")
                    r.lines.forEach { l ->
                        val revenue = l.account.type == ir.sabou.ledger.AccountType.REVENUE
                        val bad = if (revenue) l.actual < l.budget else l.actual > l.budget && l.budget > 0
                        SCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(l.account.name, style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                l.usedBp?.let { Chip(Fa.percent(it), if (bad) ChipKind.DANGER else ChipKind.PRIMARY) }
                            }
                            KeyValue("بودجه", Fa.toman(l.budget))
                            KeyValue("عملکرد", Fa.toman(l.actual), valueColor = if (bad) Sabou.colors.danger else Sabou.colors.ink)
                        }
                    }
                }
                ExportButtons("بودجه") { listOf(ReportTables.budget(books.budgetVsActual(from, to, scope))) }
            }
        }
    }

    // ------------------------------------------------------------ Fixed assets

    @Composable
    fun Assets(nav: Nav) {
        val session = LocalSession.current
        val today = Fa.jalali(session.today)
        // Depreciation is usually booked to the end of the last finished month.
        val lastMonthEnd = Fa.fromJalali(today.year, today.month, 1).plusDays(-1)
        var through by rememberSaveable { mutableStateOf(lastMonthEnd) }
        var confirm by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Header("دارایی‌های ثابت", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, through) { books.assets(branch, through) to books.depreciationRuns(branch) }
                val id = rememberCommandId(branch, through)
                val action = rememberAction()
                Page {
                    if (session.can(Permission.ASSET_MANAGE)) PrimaryButton("ثبت دارایی", { nav.go(Route.NewAsset) })
                    Loaded(data) { (rows, runs) ->
                        val active = rows.filter { it.asset.status == AssetStatus.ACTIVE }
                        SCard {
                            KeyValue("بهای دارایی‌های فعال", Fa.toman(Money.sum(active.map { it.asset.cost })))
                            KeyValue("ارزش دفتری", Fa.toman(Money.sum(active.map { it.asset.bookValue })), strong = true)
                        }
                        if (rows.isEmpty()) EmptyState("دارایی ثابتی ثبت نشده است.")
                        rows.forEach { r ->
                            val a = r.asset
                            NavRow(R.drawable.ic_settings, a.name, "${a.category.ifBlank { "دارایی" }} · ارزش دفتری ${Fa.toman(a.bookValue)}" +
                                if (a.status == AssetStatus.DISPOSED) " · واگذارشده" else "", Fa.tomanShort(a.cost.rial), onClick = { nav.go(Route.AssetDetail(a.id)) })
                        }
                        if (session.can(Permission.ASSET_MANAGE) && active.isNotEmpty()) FormCard("ثبت استهلاک") {
                            DateInput("تا تاریخ", through, { through = it }, session.today)
                            val due = Money.sum(rows.map { it.nextDepreciation })
                            KeyValue("استهلاک این دوره (تومان)", Fa.toman(due), strong = true)
                            action.error?.let { Banner(it) }
                            PrimaryButton("ثبت استهلاک", { confirm = true }, enabled = !due.isZero, busy = action.busy)
                            if (confirm) Confirm("ثبت استهلاک؟", "سند استهلاک همه‌ی دارایی‌های این شعبه تا ${Fa.date(through)} ثبت می‌شود.", "ثبت",
                                onConfirm = { confirm = false; action.run({ fixedAssets.depreciate(RunDepreciation(id.value, branch, through)) }) },
                                onDismiss = { confirm = false })
                        }
                        val latest = runs.firstOrNull { !it.reversed }
                        if (latest != null && session.can(Permission.ASSET_MANAGE)) SCard {
                            Text("آخرین استهلاک: تا ${Fa.date(latest.through)} · ${Fa.toman(latest.total)} تومان", style = SabouType.body, color = Sabou.colors.ink)
                            SecondaryButton("برگرداندن آخرین استهلاک", {
                                action.run({ fixedAssets.reverseRun(ReverseDepreciationRun(GlobalId.new(), branch, latest.id, session.today, "ثبت اشتباه")) })
                            }, danger = true)
                        }
                    }
                    ExportButtons("دارایی-ثابت") { listOf(ReportTables.assets(books.assets(branch), session.today)) }
                }
            }
        }
    }

    /** Common classes of the tax depreciation table, as a starting point (to be confirmed by an advisor). */
    private data class Preset(val label: String, val method: DepreciationMethod, val months: Int?, val rateBp: Long?)
    private val presets = listOf(
        Preset("تجهیزات آشپزخانه و تأسیسات — خط مستقیم ۱۰ ساله", DepreciationMethod.STRAIGHT_LINE, 120, null),
        Preset("اثاثیه و مبلمان — خط مستقیم ۱۰ ساله", DepreciationMethod.STRAIGHT_LINE, 120, null),
        Preset("رایانه و صندوق فروش — خط مستقیم ۳ ساله", DepreciationMethod.STRAIGHT_LINE, 36, null),
        Preset("خودرو سواری و وانت — نزولی ۲۵٪", DepreciationMethod.DECLINING_BALANCE, null, 2_500),
        Preset("ساختمان — نزولی ۸٪", DepreciationMethod.DECLINING_BALANCE, null, 800),
        Preset("دلخواه", DepreciationMethod.STRAIGHT_LINE, null, null),
    )

    @Composable
    fun NewAsset(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("ثبت دارایی ثابت", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val accounts by load(session, branch) { overview.paymentAccounts() }
                var name by rememberSaveable { mutableStateOf("") }
                var category by rememberSaveable { mutableStateOf("") }
                var cost by rememberSaveable { mutableStateOf<Money?>(null) }
                var salvage by rememberSaveable { mutableStateOf<Money?>(null) }
                var date by rememberSaveable { mutableStateOf(session.today) }
                var preset by rememberSaveable { mutableStateOf(0) }
                var method by rememberSaveable { mutableStateOf(0) }
                var months by rememberSaveable { mutableStateOf("120") }
                var rate by rememberSaveable { mutableStateOf("25") }
                var funding by rememberSaveable { mutableStateOf(0) }
                var payAccount by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var accumulated by rememberSaveable { mutableStateOf<Money?>(null) }
                var through by rememberSaveable { mutableStateOf(session.today) }
                val id = rememberCommandId()
                val action = rememberAction()
                Page {
                    Loaded(accounts) { list ->
                        FormCard {
                            TextInput("نام دارایی", name, { name = it }, placeholder = "مثلاً فر پیتزا")
                            TextInput("گروه", category, { category = it }, placeholder = "مثلاً تجهیزات آشپزخانه")
                            MoneyInput("بهای خرید", cost, { cost = it })
                            MoneyInput("ارزش اسقاط (اختیاری)", salvage, { salvage = it })
                            DateInput("تاریخ خرید یا بهره‌برداری", date, { date = it }, session.today)
                        }
                        FormCard("روش استهلاک") {
                            Picker("گروه جدول استهلاک", presets.mapIndexed { i, p -> Choice(i, p.label) }, preset, { i ->
                                preset = i
                                val p = presets[i]
                                method = if (p.method == DepreciationMethod.STRAIGHT_LINE) 0 else 1
                                p.months?.let { months = it.toString() }
                                p.rateBp?.let { rate = (it / 100).toString() }
                            })
                            Segmented(listOf("خط مستقیم", "نزولی"), method, { method = it })
                            if (method == 0) TextInput("عمر مفید (ماه)", Fa.digits(months), { months = Fa.latinDigits(it) }, keyboard = KeyboardType.Number)
                            else TextInput("نرخ سالانه (درصد)", Fa.digits(rate), { rate = Fa.latinDigits(it) }, keyboard = KeyboardType.Number)
                            Text("نرخ‌ها پیشنهاد اولیه از جدول استهلاکات است؛ با مشاور مالیاتی تطبیق دهید.", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                        FormCard("پرداخت") {
                            Segmented(listOf("خرید و پرداخت", "از قبل داشتیم"), funding, { funding = it })
                            if (funding == 0) Picker("از حساب", accountChoices(list), payAccount, { payAccount = it })
                            else {
                                MoneyInput("استهلاک انباشته تا امروز (اختیاری)", accumulated, { accumulated = it })
                                if (accumulated != null && !accumulated!!.isZero) DateInput("استهلاک تا این تاریخ ثبت شده", through, { through = it }, session.today)
                                Text("ارزش آن به حساب سرمایه ثبت می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                            }
                        }
                        val lifeMonths = months.trim().toIntOrNull()
                        val rateBp = rate.trim().toLongOrNull()?.let { it * 100 }
                        action.error?.let { Banner(it) }
                        PrimaryButton("ثبت دارایی", {
                            val f = if (funding == 0) Funding.Paid(payAccount!!) else Funding.Existing(accumulated ?: Money.ZERO, through.takeIf { accumulated?.isZero == false })
                            val m = if (method == 0) DepreciationMethod.STRAIGHT_LINE else DepreciationMethod.DECLINING_BALANCE
                            action.run({ fixedAssets.acquire(AcquireAsset(id.value, branch, name, category, cost!!, salvage ?: Money.ZERO, date, m,
                                lifeMonths.takeIf { method == 0 }, rateBp.takeIf { method == 1 }, f)) }) { nav.back() }
                        }, enabled = name.trim().length >= 2 && cost != null && (funding == 1 || payAccount != null) &&
                            (if (method == 0) lifeMonths != null else rateBp != null), busy = action.busy)
                    }
                }
            }
        }
    }

    @Composable
    fun AssetDetail(nav: Nav, assetId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, assetId) { books.asset(assetId) to overview.paymentAccounts() }
        var date by rememberSaveable { mutableStateOf(session.today) }
        var proceeds by rememberSaveable { mutableStateOf<Money?>(null) }
        var account by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var reason by rememberSaveable { mutableStateOf("") }
        var confirm by rememberSaveable { mutableStateOf(false) }
        val id = rememberCommandId(assetId)
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("دارایی ثابت", onBack = nav.back)
            Page {
                Loaded(data) { (row, accounts) ->
                    val a = row.asset
                    SCard {
                        Text(a.name, style = SabouType.section, color = Sabou.colors.ink)
                        KeyValue("گروه / شعبه", "${a.category.ifBlank { "—" }} · ${row.branch}")
                        KeyValue("تاریخ خرید", Fa.date(a.acquiredOn))
                        KeyValue("روش", if (a.method == DepreciationMethod.STRAIGHT_LINE) "خط مستقیم · ${Fa.number(a.usefulLifeMonths?.toLong() ?: 0)} ماه" else "نزولی · ${Fa.percent(a.rateBp ?: 0)} در سال")
                        KeyValue("بها", Fa.toman(a.cost))
                        if (!a.salvage.isZero) KeyValue("ارزش اسقاط", Fa.toman(a.salvage))
                        KeyValue("استهلاک انباشته", Fa.toman(a.accumulated))
                        KeyValue("ارزش دفتری (تومان)", Fa.toman(a.bookValue), strong = true)
                        a.depreciatedThrough?.let { KeyValue("استهلاک ثبت‌شده تا", Fa.date(it)) }
                        if (a.status == AssetStatus.DISPOSED) Chip("واگذارشده ${a.disposedOn?.let(Fa::date) ?: ""}", ChipKind.NEUTRAL)
                    }
                    if (a.status == AssetStatus.ACTIVE && session.can(Permission.ASSET_MANAGE)) FormCard("فروش یا کنار گذاشتن") {
                        DateInput("تاریخ", date, { date = it }, session.today)
                        MoneyInput("مبلغ فروش (خالی = بدون فروش)", proceeds, { proceeds = it })
                        if (proceeds != null && !proceeds!!.isZero) Picker("دریافت به حساب", accountChoices(accounts), account, { account = it })
                        TextInput("دلیل", reason, { reason = it })
                        action.error?.let { Banner(it) }
                        SecondaryButton("ثبت واگذاری", { confirm = true },
                            enabled = reason.trim().length >= 3 && (proceeds == null || proceeds!!.isZero || account != null), danger = true)
                    }
                    if (confirm) Confirm("واگذاری دارایی؟", "استهلاک تا این تاریخ ثبت و دارایی از دفاتر خارج می‌شود؛ سود یا زیان آن ثبت می‌شود.", "ثبت",
                        onConfirm = {
                            confirm = false
                            val money = proceeds ?: Money.ZERO
                            action.run({ fixedAssets.dispose(DisposeAsset(id.value, a.scope, a.id, date, money, account.takeIf { !money.isZero }, reason)) }) { nav.back() }
                        },
                        onDismiss = { confirm = false }, danger = true)
                }
            }
        }
    }
}
