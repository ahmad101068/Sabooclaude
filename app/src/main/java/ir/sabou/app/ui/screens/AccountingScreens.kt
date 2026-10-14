package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.ExportButtons
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.SecondaryButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.MoneyInput
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.platform.Permission

/** Accounting screens of the review of the other modules (ADR-0045). */
object AccountingScreens {

    /** VAT and levies the branch's sales owe, paid to the tax office (net of VAT paid on purchases). */
    @Composable
    fun SalesTax(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("مالیات و عوارض فروش", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { ledgerReads.salesTax(branch) to overview.paymentAccounts(branch) }
                var accountId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var amount by rememberSaveable { mutableStateOf<Money?>(null) }
                var credit by rememberSaveable { mutableStateOf<Money?>(null) }
                var note by rememberSaveable { mutableStateOf("") }
                var date by rememberSaveable { mutableStateOf(session.today) }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { (tax, accounts) ->
                        SCard {
                            KeyValue("بدهی مالیات و عوارض فروش", Fa.rial(tax.owed) + " ریال", strong = true)
                            KeyValue("اعتبار مالیاتی خرید", Fa.rial(tax.inputCredit) + " ریال")
                        }
                        if (session.can(Permission.TREASURY_PAYMENT)) FormCard("پرداخت به اداره‌ی مالیات") {
                            Text("مبلغ اظهارنامه را وارد کنید؛ اگر اعتبار مالیاتی خرید دارید، بخشی که با آن تهاتر می‌شود از حساب پرداخت نمی‌شود.",
                                style = SabouType.caption, color = Sabou.colors.muted)
                            MoneyInput("مالیات و عوارض این دوره", amount, { amount = it })
                            if (!tax.inputCredit.isZero) MoneyInput("تهاتر با اعتبار خرید (حداکثر ${Fa.rial(tax.inputCredit)})", credit, { credit = it })
                            val cash = (amount?.rial ?: 0L) - (credit?.rial ?: 0L)
                            if (cash > 0) Picker("پرداخت از", accountChoices(accounts), accountId, { accountId = it })
                            TextInput("توضیح", note, { note = it }, placeholder = "مثلاً اظهارنامه‌ی پاییز")
                            DateInput("تاریخ", date, { date = it }, session.today)
                            KeyValue("پرداخت از حساب", Fa.rial(maxOf(0L, cash)) + " ریال")
                            action.error?.let { Banner(it) }
                            PrimaryButton("ثبت پرداخت", {
                                action.run({
                                    salesOps.paySalesTax(ir.sabou.sales.PaySalesTax(id.value, branch, accountId ?: accounts.first().id, amount!!, date, note, credit ?: Money.ZERO))
                                }) { amount = null; credit = null; note = ""; id.value = GlobalId.new() }
                            }, enabled = amount != null && cash >= 0 && (cash == 0L || accountId != null) && accounts.isNotEmpty() && !action.busy, busy = action.busy)
                        }
                    }
                }
            }
        }
    }

    private fun place(branch: ir.sabou.kernel.Scope.Branch?) = if (branch == null) "همه‌ی شعب در دسترس" else "یک شعبه"

    /** Branch filter of the accounting reports: all the person may see, or the current branch. */
    @Composable
    private fun BranchFilter(all: Boolean, onChange: (Boolean) -> Unit) {
        val session = LocalSession.current
        Row(Modifier.clickable { onChange(!all) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = !all, onCheckedChange = { onChange(!it) })
            Text(if (session.branch == null) "فقط شعبه‌ی جاری (شعبه‌ای انتخاب نشده)" else "فقط شعبه‌ی جاری", style = SabouType.body, color = Sabou.colors.ink)
        }
    }

    @Composable
    fun TrialBalance(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(Fa.jalali(session.today).let { Fa.fromJalali(it.year, 1, 1) }) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        var all by rememberSaveable { mutableStateOf(true) }
        val branch = if (all) null else session.branch
        val data by load(session, from, to, branch) { ledgerReads.trialBalance(from, to, branch) }
        Column(Modifier.fillMaxSize()) {
            Header("تراز آزمایشی", onBack = nav.back)
            Page {
                ReportsScreens.PeriodPicker(from, to) { f, t -> from = f; to = t }
                BranchFilter(all) { all = it }
                Loaded(data) { rows ->
                    if (rows.isEmpty()) EmptyState("در این بازه سندی نیست.")
                    rows.forEach { r ->
                        SCard {
                            Text("${Fa.digits(r.account.code.value)} · ${r.account.name}", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            KeyValue("مانده‌ی اول", side(r.opening))
                            KeyValue("گردش بدهکار / بستانکار", "${Fa.rial(r.debit)} / ${Fa.rial(r.credit)}")
                            KeyValue("مانده", side(r.closing), strong = true)
                        }
                    }
                    SCard { KeyValue("جمع مانده‌ها (باید صفر باشد)", Fa.rial(rows.sumOf { it.closing })) }
                }
                ExportButtons("تراز-آزمایشی") { listOf(ir.sabou.core.ReportTables.trialBalance(ledgerReads.trialBalance(from, to, branch), from, to, place(branch))) }
            }
        }
    }

    private fun side(v: Long) = (if (v < 0) "بس " else if (v > 0) "بد " else "") + Fa.rial(kotlin.math.abs(v))

    @Composable
    fun BalanceSheet(nav: Nav) {
        val session = LocalSession.current
        var asOf by rememberSaveable { mutableStateOf(session.today) }
        var all by rememberSaveable { mutableStateOf(true) }
        val branch = if (all) null else session.branch
        val data by load(session, asOf, branch) { ledgerReads.balanceSheet(asOf, branch) }
        Column(Modifier.fillMaxSize()) {
            Header("ترازنامه", onBack = nav.back)
            Page {
                DateInput("تا تاریخ", asOf, { asOf = it }, session.today)
                BranchFilter(all) { all = it }
                Loaded(data) { b ->
                    SCard {
                        Text("دارایی‌ها", style = SabouType.section, color = Sabou.colors.ink)
                        b.assets.forEach { KeyValue(it.account.name, Fa.rial(it.amount)) }
                        KeyValue("جمع دارایی‌ها", Fa.rial(b.totalAssets), strong = true)
                    }
                    SCard {
                        Text("بدهی‌ها", style = SabouType.section, color = Sabou.colors.ink)
                        b.liabilities.forEach { KeyValue(it.account.name, Fa.rial(it.amount)) }
                        KeyValue("جمع بدهی‌ها", Fa.rial(b.totalLiabilities), strong = true)
                    }
                    SCard {
                        Text("حقوق مالکانه", style = SabouType.section, color = Sabou.colors.ink)
                        b.equity.forEach { KeyValue(it.account.name, Fa.rial(it.amount)) }
                        KeyValue("سود (زیان) سال‌های قبل", Fa.rial(b.earlierProfit))
                        KeyValue("سود (زیان) سال جاری", Fa.rial(b.yearProfit))
                        KeyValue("جمع حقوق مالکانه", Fa.rial(b.totalEquity), strong = true)
                    }
                    SCard { KeyValue("جمع بدهی‌ها و حقوق مالکانه", Fa.rial(b.totalLiabilities + b.totalEquity), strong = true) }
                    Text("سود هر سال با سند بستن حساب‌ها منتقل نمی‌شود؛ ترازنامه آن را از دفاتر حساب می‌کند. برای قفل سال، ماه‌ها را از «بستن ماه» ببندید.",
                        style = SabouType.caption, color = Sabou.colors.muted)
                }
                ExportButtons("ترازنامه") { listOf(ir.sabou.core.ReportTables.balanceSheet(ledgerReads.balanceSheet(asOf, branch), place(branch))) }
            }
        }
    }

    @Composable
    fun JournalBook(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(Fa.jalali(session.today).let { Fa.fromJalali(it.year, it.month, 1) }) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        var all by rememberSaveable { mutableStateOf(true) }
        val branch = if (all) null else session.branch
        val data by load(session, from, to, branch) { ledgerReads.journalBook(from, to, branch) to ledgerReads.chart().associate { it.code to it.name } }
        Column(Modifier.fillMaxSize()) {
            Header("دفتر روزنامه", onBack = nav.back)
            Page {
                ReportsScreens.PeriodPicker(from, to) { f, t -> from = f; to = t }
                BranchFilter(all) { all = it }
                Loaded(data) { (list, names) ->
                    if (list.isEmpty()) EmptyState("در این بازه سندی نیست.")
                    list.forEach { v ->
                        SCard {
                            Text((v.number?.let { "سند ${Fa.digits(it)} · " } ?: "") + Fa.date(v.entry.date), style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            Text(v.entry.description, style = SabouType.caption, color = Sabou.colors.muted)
                            v.entry.lines.forEach { l ->
                                KeyValue(names[l.account] ?: l.account.value, if (!l.debit.isZero) "بد ${Fa.rial(l.debit)}" else "بس ${Fa.rial(l.credit)}")
                            }
                        }
                    }
                }
                ExportButtons("دفتر-روزنامه") {
                    listOf(ir.sabou.core.ReportTables.journalBook(ledgerReads.journalBook(from, to, branch), ledgerReads.chart().associate { it.code to it.name }, from, to, place(branch)))
                }
            }
        }
    }

    @Composable
    fun Statements(nav: Nav) {
        val session = LocalSession.current
        var supplierSide by rememberSaveable { mutableStateOf(session.can(Permission.PURCHASE_VIEW)) }
        var partyId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var from by rememberSaveable { mutableStateOf(Fa.jalali(session.today).let { Fa.fromJalali(it.year, 1, 1) }) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        val parties by load(session, supplierSide) {
            if (supplierSide) overview.suppliers().map { Choice(it.supplier.id, it.supplier.name) } else ledgerReads.allCustomers().map { Choice(it.id, it.name) }
        }
        val data by load(session, supplierSide, partyId, from, to) {
            partyId?.let { if (supplierSide) ledgerReads.supplierStatement(it, from, to) else ledgerReads.customerStatement(it, from, to) }
        }
        Column(Modifier.fillMaxSize()) {
            Header("صورت‌حساب طرف‌حساب", onBack = nav.back)
            Page {
                Picker("طرف‌حساب", listOf(Choice(true, "تأمین‌کننده"), Choice(false, "مشتری")), supplierSide, { supplierSide = it; partyId = null })
                Loaded(parties) { list -> Picker(if (supplierSide) "تأمین‌کننده" else "مشتری", list, partyId, { partyId = it }) }
                ReportsScreens.PeriodPicker(from, to) { f, t -> from = f; to = t }
                Loaded(data) { st ->
                    if (st == null) return@Loaded
                    SCard {
                        KeyValue("مانده‌ی ابتدای دوره", Fa.rial(st.opening))
                        KeyValue("مانده‌ی پایان دوره", Fa.rial(st.closing), strong = true)
                        Text(if (supplierSide) "مانده = بدهی ما به تأمین‌کننده" else "مانده = بدهی مشتری به ما", style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    st.rows.forEach { r ->
                        SCard {
                            Text("${Fa.date(r.date)} · ${r.title}" + (r.number?.let { " · ${Fa.digits(it)}" } ?: ""), style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            KeyValue(if (r.debit > 0) "بدهکار" else "بستانکار", Fa.rial(maxOf(r.debit, r.credit)))
                            KeyValue("مانده", Fa.rial(r.balance))
                        }
                    }
                    ExportButtons("صورت‌حساب-${st.party}") { listOf(ir.sabou.core.ReportTables.statement(st, supplierSide)) }
                }
            }
        }
    }

    @Composable
    fun Aging(nav: Nav) {
        val session = LocalSession.current
        var receivables by rememberSaveable { mutableStateOf(session.can(Permission.SALES_VIEW)) }
        val data by load(session, receivables) { if (receivables) ledgerReads.receivablesAging(session.today) else ledgerReads.payablesAging(session.today) }
        val labels = listOf("سررسید نشده", "۱ تا ۳۰ روز", "۳۱ تا ۶۰ روز", "۶۱ تا ۹۰ روز", "بیش از ۹۰ روز")
        Column(Modifier.fillMaxSize()) {
            Header("سنی بدهی و طلب", onBack = nav.back)
            Page {
                Picker("گزارش", listOf(Choice(true, "طلب از مشتریان"), Choice(false, "بدهی به تأمین‌کنندگان")), receivables, { receivables = it })
                Loaded(data) { rows ->
                    SCard {
                        labels.forEachIndexed { i, l -> KeyValue(l, Fa.rial(rows.sumOf { it.buckets[i] }), if (i >= 3) Sabou.colors.danger else Sabou.colors.ink) }
                        KeyValue("جمع", Fa.rial(rows.sumOf { it.total }), strong = true)
                    }
                    if (rows.isEmpty()) EmptyState("مانده‌ی بازی نیست.")
                    rows.forEach { r ->
                        SCard {
                            KeyValue(r.party, Fa.rial(r.total), strong = true)
                            labels.forEachIndexed { i, l -> if (r.buckets[i] > 0) KeyValue(l, Fa.rial(r.buckets[i])) }
                        }
                    }
                }
                ExportButtons(if (receivables) "سنی-مطالبات" else "سنی-بدهی") {
                    listOf(ir.sabou.core.ReportTables.aging(if (receivables) ledgerReads.receivablesAging(session.today) else ledgerReads.payablesAging(session.today), session.today, receivables))
                }
            }
        }
    }

    /** The chart of accounts: the standard accounts and the restaurant's own, which are defined and retired here. */
    @Composable
    fun Chart(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { ledgerReads.chart() }
        var code by rememberSaveable { mutableStateOf("") }
        var name by rememberSaveable { mutableStateOf("") }
        var editing by rememberSaveable { mutableStateOf<String?>(null) }
        var newName by rememberSaveable { mutableStateOf("") }
        val action = rememberAction()
        val edit = rememberAction()
        val types = listOf(
            ir.sabou.ledger.AccountType.ASSET to "دارایی‌ها", ir.sabou.ledger.AccountType.LIABILITY to "بدهی‌ها", ir.sabou.ledger.AccountType.EQUITY to "حقوق مالکانه",
            ir.sabou.ledger.AccountType.REVENUE to "درآمدها", ir.sabou.ledger.AccountType.EXPENSE to "هزینه‌ها",
        )
        Column(Modifier.fillMaxSize()) {
            Header("سرفصل حساب‌ها", onBack = nav.back)
            Page {
                if (session.can(Permission.ACCOUNT_MANAGE)) FormCard("حساب تازه") {
                    Text("رقم اول کد نوع حساب است: ۱ دارایی، ۲ بدهی، ۳ سرمایه، ۴ درآمد، ۵ تا ۹ هزینه. مثلاً ۶۲۰۱ «تعمیر هود» یا ۲۲۰۲ «وام بانک ملت».",
                        style = SabouType.caption, color = Sabou.colors.muted)
                    TextInput("کد چهاررقمی", code, { code = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                    TextInput("نام حساب", name, { name = it })
                    action.error?.let { Banner(it) }
                    PrimaryButton("افزودن", { action.run({ accounting.defineAccount(ir.sabou.ledger.DefineAccount(GlobalId.new(), code, name)) }) { code = ""; name = "" } },
                        enabled = code.length == 4 && name.trim().length >= 2 && !action.busy, busy = action.busy)
                }
                Loaded(data) { list ->
                    types.forEach { (type, title) ->
                        SCard {
                            Text(title, style = SabouType.section, color = Sabou.colors.ink)
                            list.filter { it.type == type }.forEach { a ->
                                val own = !a.isSystem
                                Text("${Fa.digits(a.code.value)} · ${a.name}" + (if (a.isControl) " · کنترلی" else "") + (if (!a.isActive) " · بسته" else "") + (if (own) " · حساب خودتان" else ""),
                                    style = SabouType.body, color = if (a.isActive) Sabou.colors.ink else Sabou.colors.muted,
                                    modifier = Modifier.clickable(enabled = own && session.can(Permission.ACCOUNT_MANAGE)) { editing = a.code.value; newName = a.name }.padding(vertical = 4.dp))
                                if (editing == a.code.value) {
                                    TextInput("نام", newName, { newName = it })
                                    edit.error?.let { Banner(it) }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrimaryButton("ذخیره", { edit.run({ accounting.updateAccount(ir.sabou.ledger.UpdateAccount(GlobalId.new(), a.code, newName, a.isActive)) }) { editing = null } },
                                            Modifier.weight(1f), enabled = newName.trim().length >= 2 && !edit.busy)
                                        SecondaryButton(if (a.isActive) "بستن حساب" else "فعال کردن", {
                                            edit.run({ accounting.updateAccount(ir.sabou.ledger.UpdateAccount(GlobalId.new(), a.code, newName, !a.isActive)) }) { editing = null }
                                        }, Modifier.weight(1f), danger = a.isActive)
                                    }
                                }
                            }
                        }
                    }
                    Text("حساب کنترلی را فقط بخش خودش (فروش، خرید، انبار، …) سند می‌زند. حساب‌های دیگر در پرداخت، دریافت، فاکتور هزینه و سند دستی انتخاب می‌شوند.",
                        style = SabouType.caption, color = Sabou.colors.muted)
                }
            }
        }
    }

    /** What suppliers were owed and customers owed when the books started. */
    @Composable
    fun OpeningBalances(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("مانده‌های اول دوره", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { overview.suppliers().map { it.supplier } to ledgerReads.allCustomers() }
                var supplierId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var sAmount by rememberSaveable { mutableStateOf<Money?>(null) }
                var sRef by rememberSaveable { mutableStateOf("مانده‌ی اول دوره") }
                var customerId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var cAmount by rememberSaveable { mutableStateOf<Money?>(null) }
                var date by rememberSaveable { mutableStateOf(session.today) }
                var due by rememberSaveable { mutableStateOf(session.today.plusDays(30)) }
                val sAction = rememberAction()
                val cAction = rememberAction()
                Page {
                    Text("بدهی‌ها و طلب‌هایی که پیش از شروع کار با برنامه بوده‌اند، در برابر سرمایه ثبت می‌شوند و بعد مثل هر فاکتور یا نسیه پرداخت یا وصول می‌شوند. موجودی انبار را از «موجودی اول دوره» و دارایی‌ها را از «دارایی‌های ثابت» وارد کنید.",
                        style = SabouType.caption, color = Sabou.colors.muted)
                    DateInput("تاریخ مانده", date, { date = it }, session.today)
                    DateInput("سررسید", due, { due = it }, session.today)
                    Loaded(data) { (suppliers, customers) ->
                        FormCard("بدهی به تأمین‌کننده") {
                            Picker("تأمین‌کننده", suppliers.filter { it.isActive }.map { Choice(it.id, it.name) }, supplierId, { supplierId = it })
                            MoneyInput("مبلغ بدهی", sAmount, { sAmount = it })
                            TextInput("شرح (به‌جای شماره‌ی فاکتور)", sRef, { sRef = it })
                            sAction.error?.let { Banner(it) }
                            PrimaryButton("ثبت", {
                                sAction.run({ purchasing.openingBalance(ir.sabou.purchasing.RecordSupplierOpeningBalance(GlobalId.new(), branch, supplierId!!, sRef, sAmount!!, date, due)) }) { sAmount = null; supplierId = null }
                            }, enabled = supplierId != null && sAmount != null && sRef.isNotBlank() && !sAction.busy, busy = sAction.busy)
                        }
                        FormCard("طلب از مشتری") {
                            Picker("مشتری", customers.filter { it.isActive }.map { Choice(it.id, it.name) }, customerId, { customerId = it })
                            MoneyInput("مبلغ طلب", cAmount, { cAmount = it })
                            cAction.error?.let { Banner(it) }
                            PrimaryButton("ثبت", {
                                cAction.run({ salesOps.customerOpeningBalance(ir.sabou.sales.RecordCustomerOpeningBalance(GlobalId.new(), branch, customerId!!, cAmount!!, date, due)) }) { cAmount = null; customerId = null }
                            }, enabled = customerId != null && cAmount != null && !cAction.busy, busy = cAction.busy)
                        }
                    }
                }
            }
        }
    }
}
