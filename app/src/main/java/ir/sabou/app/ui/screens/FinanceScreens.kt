package ir.sabou.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.ui.Load
import ir.sabou.app.ui.ExportButtons
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.IconTile
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.MoneyInput
import ir.sabou.app.ui.components.NavRow
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.components.Segmented
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.ReportTables
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountType
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.sales.CollectReceivable
import ir.sabou.treasury.Direction
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.ReconcileAccount
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.ReverseTreasuryDocument
import ir.sabou.treasury.TransferFunds
import ir.sabou.treasury.TreasuryMovement

object FinanceScreens {

    fun movementLabel(m: TreasuryMovement): String {
        val base = when (m.source.type) {
            "TREASURY_RECEIPT" -> "دریافت"
            "TREASURY_PAYMENT" -> "پرداخت هزینه"
            "TREASURY_TRANSFER" -> "انتقال وجه"
            "TREASURY_RECONCILIATION" -> "اختلاف شمارش صندوق"
            "DAILY_SALE_SETTLEMENT" -> "تسویه فروش روز"
            "RECEIVABLE_COLLECTION" -> "دریافت از مشتری"
            "SUPPLIER_PAYMENT", "PURCHASE_INVOICE" -> "پرداخت به تأمین‌کننده"
            "SALARY_PAYMENT" -> "پرداخت حقوق"
            "PAYROLL_REMITTANCE" -> "پرداخت بیمه / مالیات"
            "CHEQUE_COLLECT" -> "وصول چک"
            "CHEQUE_CLEAR" -> "پاس شدن چک"
            "CHEQUE_BOUNCE" -> "برگشت چک"
            "CHEQUE_SETTLE" -> "تسویه چک برگشتی"
            "ASSET_ACQUISITION" -> "خرید دارایی ثابت"
            "ASSET_DISPOSAL" -> "فروش دارایی ثابت"
            else -> "گردش"
        }
        return if (m.reversalOf != null) "برگشت $base" else base
    }

    @Composable
    fun ModuleChip(module: ModuleId) = when (module) {
        ModuleId.SALES -> Chip("فروش", ChipKind.SALES)
        ModuleId.PURCHASING -> Chip("خرید", ChipKind.PURCHASE)
        ModuleId.PAYROLL -> Chip("حقوق", ChipKind.PURCHASE)
        ModuleId.ASSETS -> Chip("دارایی", ChipKind.PURCHASE)
        else -> Chip("خزانه", ChipKind.TREASURY)
    }

    @Composable
    fun MovementRow(m: TreasuryMovement, accountName: String) {
        val inflow = m.direction == Direction.RECEIPT
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(movementLabel(m), style = SabouType.bodyStrong, color = Sabou.colors.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModuleChip(m.source.module)
                    Text("$accountName · ${Fa.date(m.date)}", style = SabouType.caption, color = Sabou.colors.muted)
                }
            }
            Text((if (inflow) "+ " else "− ") + Fa.tomanShort(m.amount.rial), style = SabouType.bodyStrong,
                color = if (inflow) Sabou.colors.moneyIn else Sabou.colors.moneyOut)
        }
    }

    // ------------------------------------------------------------ Hub (design: Treasury)

    /** Anyone who may open at least one report. */
    val reportPerms = listOf(Permission.LEDGER_VIEW, Permission.SALES_VIEW, Permission.INVENTORY_VIEW, Permission.PERSONNEL_VIEW, Permission.ATTENDANCE_RECORD, Permission.PAYROLL_CALCULATE)

    @Composable
    fun Hub(nav: Nav) {
        val session = LocalSession.current
        var tab by remember { mutableIntStateOf(0) }
        val canSee = session.can(Permission.TREASURY_VIEW)
        val data by load(session) { if (canSee) overview.treasury() to overview.recentMovements(30) else emptyList<ir.sabou.core.AccountBalance>() to emptyList() }
        Column(Modifier.fillMaxSize()) {
            Header("صندوق و بانک")
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canSee) item { Segmented(listOf("همه", "شعبه", "سازمان"), tab) { tab = it } }
                if (canSee) item {
                    Loaded(data) { (balances, movements) ->
                        val visible = balances.filter {
                            when (tab) {
                                1 -> it.account.scope == session.branch
                                2 -> it.account.scope == Scope.Organization
                                else -> true
                            }
                        }
                        val names = balances.associate { it.account.id to it.account.name }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (visible.isEmpty()) EmptyState("حسابی برای نمایش نیست.", if (session.can(Permission.TREASURY_ACCOUNT_MANAGE)) "تعریف حساب" else null) { nav.go(Route.Accounts) }
                            visible.forEach { b ->
                                val (tint, tile) = kindColors(b.account.kind)
                                SCard(onClick = { nav.go(Route.AccountHistory(b.account.id)) }, padding = PaddingValues(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        IconTile(kindIcon(b.account.kind), tint, tile)
                                        Column(Modifier.weight(1f)) {
                                            Text(b.account.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                            Text(kindName(b.account.kind) + if (b.account.scope == Scope.Organization) " · سازمان" else "", style = SabouType.caption, color = Sabou.colors.muted)
                                        }
                                        Text(Fa.tomanShort(b.balance), style = SabouType.amount, color = Sabou.colors.ink)
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryButton("دریافت", { nav.go(Route.Receipt) }, Modifier.weight(1f), enabled = session.can(Permission.TREASURY_RECEIPT))
                                SecondaryButton("پرداخت", { nav.go(Route.Payment) }, Modifier.weight(1f), enabled = session.can(Permission.TREASURY_PAYMENT))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SecondaryButton("انتقال / واریز", { nav.go(Route.Transfer) }, Modifier.weight(1f), enabled = session.can(Permission.TREASURY_TRANSFER))
                                SecondaryButton("شمارش صندوق", { nav.go(Route.Reconcile) }, Modifier.weight(1f), enabled = session.can(Permission.TREASURY_RECONCILE))
                            }
                            SCard {
                                Text("گردش اخیر", style = SabouType.section, color = Sabou.colors.ink)
                                if (movements.isEmpty()) Text("هنوز گردشی ثبت نشده است.", style = SabouType.body, color = Sabou.colors.muted)
                                movements.take(15).forEach { m -> Divider(); MovementRow(m, names[m.accountId] ?: "") }
                                Text("برگشت هر ردیف فقط از سند اصلی آن انجام می‌شود؛ ردیف‌های «فروش» و «خرید» را از همان بخش اصلاح کنید.",
                                    style = SabouType.caption, color = Sabou.colors.muted)
                            }
                        }
                    }
                }
                item { SectionTitle("گزارش‌ها") }
                if (reportPerms.any { session.can(it) }) item {
                    NavRow(R.drawable.ic_finance, "گزارش‌های مدیریتی", "سود و زیان، پایان روز، پرفروش‌ها، مصرف مواد، کارکرد — با خروجی اکسل و PDF", onClick = { nav.go(Route.Reports) })
                }
                if (session.can(Permission.SALES_VIEW)) item { NavRow(R.drawable.ic_person, "طلب از مشتریان", "دریافت نسیه‌ها", onClick = { nav.go(Route.Receivables) }) }
                if (session.can(Permission.PURCHASE_VIEW)) item { NavRow(R.drawable.ic_purchase, "بدهی به تأمین‌کنندگان", "فاکتورهای پرداخت‌نشده", onClick = { nav.go(Route.Purchases) }) }
                if (session.can(Permission.LEDGER_VIEW)) item { NavRow(R.drawable.ic_finance, "تراز آزمایشی", "مانده همه حساب‌های دفتر کل", onClick = { nav.go(Route.TrialBalance) }) }
                if (session.can(Permission.TREASURY_VIEW) || session.can(Permission.CHEQUE_MANAGE)) item {
                    NavRow(R.drawable.ic_payment, "چک‌ها", "دریافتی و پرداختی، سررسیدها، وصول و برگشت", tint = Sabou.colors.bank, tile = Sabou.colors.bankSoft, onClick = { nav.go(Route.Cheques) })
                }
                if (session.can(Permission.LEDGER_VIEW)) item { NavRow(R.drawable.ic_finance, "بودجه", "بودجه در برابر عملکرد", onClick = { nav.go(Route.BudgetReport) }) }
                if (session.can(Permission.ASSET_VIEW) || session.can(Permission.ASSET_MANAGE)) item {
                    NavRow(R.drawable.ic_settings, "دارایی‌های ثابت", "ثبت، استهلاک و فروش", onClick = { nav.go(Route.Assets) })
                }
            }
        }
    }

    // ------------------------------------------------------------ Forms

    /** A receipt or payment; a cheque box or cheque book also takes the cheque (ADR-0013). */
    @Composable
    private fun MoneyForm(
        nav: Nav,
        title: String,
        submit: String,
        purposes: List<Choice<String>>?,
        payment: Boolean,
        submitAction: ir.sabou.core.SabouCore.(account: ir.sabou.treasury.TreasuryAccount, purpose: String?, amount: Money, date: ir.sabou.kernel.BusinessDate, note: String,
                                              commandId: GlobalId, cheque: ir.sabou.treasury.ChequeDetails?, chequeId: GlobalId?) -> Unit,
    ) {
        val session = LocalSession.current
        val accounts by load(session) { overview.treasury().map { it.account } }
        var accountId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var purpose by rememberSaveable { mutableStateOf(purposes?.firstOrNull()?.value) }
        var amount by rememberSaveable { mutableStateOf<Money?>(null) }
        var date by rememberSaveable { mutableStateOf(session.today) }
        var note by rememberSaveable { mutableStateOf("") }
        var heldCheque by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        val chequeFields = ir.sabou.app.ui.rememberChequeFields()
        val commandId = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header(title, onBack = nav.back)
            Page {
                Loaded(accounts) { list ->
                    // A payment can come from a cheque book (our cheque) or a cheque box (a customer's cheque passed on);
                    // a receipt can go into a cheque box.
                    val usable = list.filter { it.kind.isOrdinary || (if (payment) true else it.kind == ir.sabou.treasury.TreasuryKind.RECEIVED_CHEQUES) }
                    val account = usable.firstOrNull { it.id == accountId }
                    val newCheque = account != null && ((payment && account.kind == ir.sabou.treasury.TreasuryKind.ISSUED_CHEQUES) ||
                        (!payment && account.kind == ir.sabou.treasury.TreasuryKind.RECEIVED_CHEQUES))
                    val passOn = payment && account?.kind == ir.sabou.treasury.TreasuryKind.RECEIVED_CHEQUES
                    FormCard {
                        Picker("حساب", accountChoices(usable, cheques = true), accountId, { accountId = it; heldCheque = null })
                        if (purposes != null) Picker("بابت", purposes, purpose, { purpose = it })
                        if (passOn) ir.sabou.app.ui.HeldChequePicker(account!!.scope, account.id, heldCheque) { row -> heldCheque = row.cheque.id; amount = row.cheque.amount }
                        else MoneyInput("مبلغ", amount, { amount = it })
                        if (passOn) amount?.let { KeyValue("مبلغ چک", Fa.toman(it) + " تومان") }
                        DateInput("تاریخ", date, { date = it }, session.today)
                        TextInput("شرح", note, { note = it })
                        if (newCheque) ir.sabou.app.ui.ChequeInputs(chequeFields, session.today, "", payment, list.filter { it.scope == account!!.scope })
                        val cheque = if (newCheque) ir.sabou.app.ui.chequeDetails(chequeFields, session.today, "", payment) else null
                        action.error?.let { Banner(it) }
                        PrimaryButton(submit, {
                            val a = amount ?: return@PrimaryButton
                            action.run({ submitAction(account!!, purpose, a, date, note, commandId.value, cheque, heldCheque.takeIf { passOn }) }) { nav.back() }
                        }, enabled = account != null && amount != null && note.isNotBlank() && (!newCheque || cheque != null) && (!passOn || heldCheque != null), busy = action.busy)
                    }
                }
            }
        }
    }

    private val receiptPurposes = listOf(
        Choice(ReceiptPurpose.OWNER_CAPITAL.name, "آورده مالک"),
        Choice(ReceiptPurpose.OTHER_INCOME.name, "سایر درآمدها"),
    )
    private val paymentPurposes = listOf(
        Choice(PaymentPurpose.RENT.name, "اجاره"),
        Choice(PaymentPurpose.UTILITIES.name, "قبوض (آب، برق، گاز، تلفن)"),
        Choice(PaymentPurpose.OTHER_EXPENSE.name, "سایر هزینه‌ها"),
        Choice(PaymentPurpose.OWNER_WITHDRAWAL.name, "برداشت مالک"),
    )

    @Composable
    fun ReceiptForm(nav: Nav) = MoneyForm(nav, "دریافت وجه", "ثبت دریافت", receiptPurposes, payment = false) { account, purpose, amount, date, note, id, cheque, _ ->
        treasury.receipt(RecordReceipt(id, account.scope, account.id, ReceiptPurpose.valueOf(purpose!!), amount, date, note, cheque))
    }

    @Composable
    fun PaymentForm(nav: Nav) = MoneyForm(nav, "پرداخت هزینه", "ثبت پرداخت", paymentPurposes, payment = true) { account, purpose, amount, date, note, id, cheque, chequeId ->
        treasury.payment(RecordPayment(id, account.scope, account.id, PaymentPurpose.valueOf(purpose!!), amount, date, note, cheque, chequeId))
    }

    @Composable
    fun ReconcileForm(nav: Nav) {
        val session = LocalSession.current
        val balances by load(session) { overview.treasury() }
        var accountId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var counted by rememberSaveable { mutableStateOf<Money?>(null) }
        var note by rememberSaveable { mutableStateOf("شمارش پایان روز") }
        val commandId = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("شمارش صندوق", onBack = nav.back)
            Page {
                Loaded(balances) { list ->
                    FormCard {
                        Picker("صندوق", accountChoices(list.map { it.account }), accountId, { accountId = it })
                        val book = list.firstOrNull { it.account.id == accountId }?.balance
                        if (book != null) KeyValue("مانده دفتری", Fa.toman(book) + " تومان")
                        MoneyInput("مبلغ شمارش‌شده", counted, { counted = it })
                        val c = counted
                        if (book != null && c != null && c.rial != book) {
                            Banner((if (c.rial > book) "اضافه صندوق: " else "کسری صندوق: ") + Fa.toman(kotlin.math.abs(c.rial - book)) + " تومان", ChipKind.ACCENT)
                        }
                        TextInput("شرح", note, { note = it })
                        action.error?.let { Banner(it) }
                        PrimaryButton("ثبت شمارش", {
                            val account = list.first { it.account.id == accountId }.account
                            action.run({ treasury.reconcile(ReconcileAccount(commandId.value, account.scope, account.id, c!!, session.today, note)) }) { nav.back() }
                        }, enabled = accountId != null && c != null, busy = action.busy)
                    }
                }
            }
        }
    }

    @Composable
    fun TransferForm(nav: Nav) {
        val session = LocalSession.current
        val accounts by load(session) { overview.treasury().map { it.account } }
        var source by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var target by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var amount by rememberSaveable { mutableStateOf<Money?>(null) }
        var date by rememberSaveable { mutableStateOf(session.today) }
        var note by rememberSaveable { mutableStateOf("") }
        val commandId = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("انتقال وجه", "مثلاً واریز نقد صندوق به بانک", onBack = nav.back)
            Page {
                Loaded(accounts) { list ->
                    FormCard {
                        Picker("از حساب", accountChoices(list), source, { source = it })
                        Picker("به حساب", accountChoices(list).filter { it.value != source }, target, { target = it })
                        val a = list.firstOrNull { it.id == source }
                        val b = list.firstOrNull { it.id == target }
                        if (a != null && b != null && a.scope != b.scope) {
                            Banner("انتقال بین شعبه/سازمان است و در هر دو طرف از طریق حساب بین‌شعبه‌ای ثبت می‌شود.", ChipKind.PRIMARY)
                        }
                        MoneyInput("مبلغ", amount, { amount = it })
                        DateInput("تاریخ", date, { date = it }, session.today)
                        TextInput("شرح", note, { note = it })
                        action.error?.let { Banner(it) }
                        PrimaryButton("ثبت انتقال", {
                            action.run({ treasury.transfer(TransferFunds(commandId.value, a!!.scope, a.id, b!!.id, amount!!, date, note)) }) { nav.back() }
                        }, enabled = a != null && b != null && amount != null && note.isNotBlank(), busy = action.busy)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ Account history

    @Composable
    fun AccountHistory(nav: Nav, accountId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, accountId) { overview.accountHistory(accountId) }
        var reverse by remember { mutableStateOf<TreasuryMovement?>(null) }
        var reason by rememberSaveable { mutableStateOf("") }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            val title = (data.orNull()?.first?.account?.name) ?: "گردش حساب"
            Header(title, onBack = nav.back)
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Loaded(data) { (b, _) ->
                        SCard { KeyValue("مانده", Fa.toman(b.balance) + " تومان", strong = true) }
                    }
                }
                item { action.error?.let { Banner(it) } }
                item { ExportButtons("گردش-حساب") { overview.accountHistory(accountId).let { (b, list) -> listOf(ReportTables.accountHistory(b, list)) } } }
                val rows = data.orNull()?.second.orEmpty()
                items(rows) { m ->
                    val own = m.source.module == ModuleId.TREASURY && m.reversalOf == null && m.source.type != "TREASURY_RECONCILIATION"
                    SCard(onClick = if (own && session.can(Permission.TREASURY_REVERSE)) ({ reverse = m }) else null) {
                        MovementRow(m, "")
                    }
                }
            }
        }
        reverse?.let { m ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { reverse = null },
                title = { Text("برگشت «${movementLabel(m)}»", style = SabouType.section) },
                text = { TextInput("دلیل برگشت", reason, { reason = it }) },
                confirmButton = {
                    androidx.compose.material3.TextButton(enabled = reason.trim().length >= 3, onClick = {
                        val scope = data.orNull()?.first?.account?.scope ?: return@TextButton
                        action.run({ treasury.reverse(ReverseTreasuryDocument(GlobalId.new(), scope, m.source.type, m.source.id, session.today, reason)) }) { reverse = null; reason = "" }
                    }) { Text("برگشت بزن", style = SabouType.bodyStrong, color = Sabou.colors.danger) }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { reverse = null }) { Text("انصراف", style = SabouType.bodyStrong) } },
                containerColor = Sabou.colors.surface,
            )
        }
    }

    // ------------------------------------------------------------ Receivables

    @Composable
    fun Receivables(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.openReceivables() }
        Column(Modifier.fillMaxSize()) {
            Header("طلب از مشتریان", onBack = nav.back)
            Page {
                Loaded(data) { list ->
                    SCard { KeyValue("جمع طلب (تومان)", Fa.toman(Money.sum(list.map { it.outstanding })), strong = true) }
                    if (list.isEmpty()) EmptyState("طلب بازی وجود ندارد.")
                    list.forEach { r ->
                        val due = r.receivable.dueDate
                        NavRow(R.drawable.ic_person, r.customer, "سررسید ${Fa.date(due)}" + if (due < session.today) " · گذشته" else "",
                            Fa.tomanShort(r.outstanding.rial), onClick = { nav.go(Route.Collect(r.receivable.id)) })
                    }
                }
                ExportButtons("طلب-از-مشتریان") { listOf(ReportTables.receivables(overview.openReceivables())) }
            }
        }
    }

    @Composable
    fun CollectForm(nav: Nav, receivableId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, receivableId) {
            val open = overview.receivable(receivableId)
            Triple(open.receivable, open.outstanding, overview.paymentAccounts(open.receivable.scope))
        }
        var accountId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var amount by rememberSaveable { mutableStateOf<Money?>(null) }
        var date by rememberSaveable { mutableStateOf(session.today) }
        val chequeFields = ir.sabou.app.ui.rememberChequeFields()
        val commandId = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        val customer by load(session, receivableId) { overview.receivable(receivableId).customer }
        Column(Modifier.fillMaxSize()) {
            Header("دریافت از مشتری", onBack = nav.back)
            Page {
                Loaded(data) { (r, outstanding, accounts) ->
                    val usable = accounts.filter { it.kind.isOrdinary || it.kind == ir.sabou.treasury.TreasuryKind.RECEIVED_CHEQUES }
                    val byCheque = usable.firstOrNull { it.id == accountId }?.kind == ir.sabou.treasury.TreasuryKind.RECEIVED_CHEQUES
                    val party = customer.orNull().orEmpty()
                    FormCard {
                        KeyValue("مانده طلب", Fa.toman(outstanding) + " تومان", strong = true)
                        Picker("واریز به", accountChoices(usable, cheques = true), accountId, { accountId = it })
                        MoneyInput(if (byCheque) "مبلغ چک" else "مبلغ دریافتی", amount, { amount = it })
                        DateInput("تاریخ", date, { date = it }, session.today)
                        if (byCheque) ir.sabou.app.ui.ChequeInputs(chequeFields, session.today, party, issued = false, banks = emptyList())
                        val cheque = if (byCheque) ir.sabou.app.ui.chequeDetails(chequeFields, session.today, party, issued = false) else null
                        action.error?.let { Banner(it) }
                        PrimaryButton("ثبت دریافت", {
                            action.run({ salesOps.collect(CollectReceivable(commandId.value, r.scope, r.id, accountId!!, amount!!, date, cheque)) }) { nav.back() }
                        }, enabled = accountId != null && amount != null && (!byCheque || cheque != null) && session.can(Permission.RECEIVABLE_COLLECT), busy = action.busy)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ Trial balance

    @Composable
    fun TrialBalance(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.trialBalance() }
        Column(Modifier.fillMaxSize()) {
            Header("تراز آزمایشی", if (session.actor.isOwner) "همه شعب و سازمان" else "فقط شعبه‌های در دسترس شما", onBack = nav.back)
            Page {
                Loaded(data) { rows ->
                    SCard {
                        rows.forEach { (a, v) ->
                            KeyValue("${Fa.digits(a.code.value)} · ${a.name}", (if (v < 0) "بس " else "بد ") + Fa.toman(kotlin.math.abs(v)),
                                if (a.type == AccountType.REVENUE || a.type == AccountType.EXPENSE) Sabou.colors.muted else Sabou.colors.ink)
                        }
                        Divider()
                        KeyValue("جمع (باید صفر باشد)", Fa.toman(rows.sumOf { it.second }), strong = true)
                    }
                }
                if (session.can(Permission.LEDGER_VIEW)) SecondaryButton("سود و زیان و گزارش‌های دیگر", { nav.go(Route.Reports) })
                ExportButtons("تراز-آزمایشی") { listOf(ReportTables.trialBalance(overview.trialBalance(), session.today)) }
            }
        }
    }
}
