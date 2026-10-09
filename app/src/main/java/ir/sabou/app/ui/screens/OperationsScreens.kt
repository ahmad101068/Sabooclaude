package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
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
import ir.sabou.app.ui.components.QuantityInput
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.inventory.CountLine
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.Item
import ir.sabou.inventory.PostStockCount
import ir.sabou.inventory.ReceiptLine
import ir.sabou.inventory.RecordOpeningStock
import ir.sabou.inventory.RecordWaste
import ir.sabou.inventory.TransferStock
import ir.sabou.inventory.WasteReason
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.payroll.ApprovePayroll
import ir.sabou.payroll.CalculatePayroll
import ir.sabou.payroll.LiabilityKind
import ir.sabou.payroll.PaySalary
import ir.sabou.payroll.RecordAttendance
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.payroll.RemitLiability
import ir.sabou.payroll.ReversePayroll
import ir.sabou.payroll.RunStatus
import ir.sabou.platform.Permission
import ir.sabou.purchasing.ImmediatePayment
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.purchasing.PaySupplierInvoice
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ReturnToSupplier
import ir.sabou.purchasing.ReversePurchaseInvoice
import ir.sabou.purchasing.ReverseSupplierPayment

object OperationsScreens {

    // ------------------------------------------------------------ Hub (design: Operations)

    /** Who sees which group: the hub only lists what the role may open (AUD-012). */
    private val inventoryPerms = listOf(Permission.INVENTORY_VIEW, Permission.INVENTORY_COUNT, Permission.INVENTORY_WASTE, Permission.INVENTORY_TRANSFER, Permission.RECIPE_MANAGE)
    private val purchasePerms = listOf(Permission.PURCHASE_VIEW, Permission.SUPPLIER_MANAGE)
    private val personnelPerms = listOf(Permission.PERSONNEL_VIEW, Permission.PERSONNEL_MANAGE, Permission.ATTENDANCE_RECORD, Permission.PAYROLL_CALCULATE, Permission.PAYROLL_APPROVE, Permission.PAYROLL_PAY)
    val allPerms = inventoryPerms + purchasePerms + personnelPerms

    @Composable
    fun Hub(nav: Nav) {
        val session = LocalSession.current
        fun any(perms: List<Permission>) = perms.any { session.can(it) }
        val counts by load(session, session.branch) {
            val low = if (session.can(Permission.INVENTORY_VIEW)) overview.lowStock().size else null
            val payable = if (session.can(Permission.PURCHASE_VIEW)) overview.payables() else null
            low to payable
        }
        val (low, payable) = counts.orNull() ?: (null to null)
        Column(Modifier.fillMaxSize()) {
            Header("عملیات") { BranchSwitcher() }
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (any(inventoryPerms)) item { SectionTitle("انبار") }
                if (session.can(Permission.INVENTORY_VIEW)) item { NavRow(R.drawable.ic_operations, "موجودی", low?.let { if (it > 0) "${Fa.number(it.toLong())} کالا زیر حداقل" else "همه کالاها بالای حداقل" }, onClick = { nav.go(Route.Stock) }) }
                if (session.can(Permission.INVENTORY_COUNT)) item { NavRow(R.drawable.ic_count, "انبارگردانی", "شمارش و ثبت اختلاف", onClick = { nav.go(Route.Count) }) }
                if (session.can(Permission.INVENTORY_TRANSFER)) item { NavRow(R.drawable.ic_transfer, "انتقال", "بین انبارها و شعب", onClick = { nav.go(Route.StockTransfer) }) }
                if (session.can(Permission.INVENTORY_WASTE)) item { NavRow(R.drawable.ic_waste, "ضایعات", "ثبت با دلیل", onClick = { nav.go(Route.Waste) }) }
                if (session.can(Permission.RECIPE_MANAGE)) item { NavRow(R.drawable.ic_recipe, "رسپی و بهای تمام‌شده", "نسخه‌های رسپی آیتم‌های منو", onClick = { nav.go(Route.Recipes) }) }
                if (any(purchasePerms)) item { SectionTitle("خرید") }
                if (session.can(Permission.PURCHASE_VIEW)) item { NavRow(R.drawable.ic_purchase, "خرید و دریافت کالا", payable?.let { "بدهی ${Fa.tomanShort(it.rial)}" }, tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.Purchases) }) }
                if (any(purchasePerms)) item { NavRow(R.drawable.ic_supplier, "تأمین‌کنندگان", "فهرست و مانده حساب", tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.Suppliers) }) }
                if (any(personnelPerms)) item { SectionTitle("پرسنل") }
                if (session.can(Permission.PERSONNEL_VIEW) || session.can(Permission.PERSONNEL_MANAGE)) item { NavRow(R.drawable.ic_person, "کارکنان", "ثبت و مشخصات", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Personnel) }) }
                if (session.can(Permission.ATTENDANCE_RECORD) || session.can(Permission.PERSONNEL_VIEW)) item { NavRow(R.drawable.ic_clock, "حضور و غیاب", "ثبت روزانه کارکرد", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Attendance) }) }
                if (listOf(Permission.PAYROLL_CALCULATE, Permission.PAYROLL_APPROVE, Permission.PAYROLL_PAY).any { session.can(it) }) item { NavRow(R.drawable.ic_payroll, "حقوق", "محاسبه، تأیید و پرداخت", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Payroll) }) }
            }
        }
    }

    // ------------------------------------------------------------ Inventory

    private class InvData(val items: List<Item>, val locations: List<ir.sabou.inventory.Location>)

    @Composable
    private fun invData(branch: Scope.Branch) = load(LocalSession.current, branch) {
        InvData(overview.items().filter { it.isActive }, overview.locations(branch).filter { it.isActive })
    }

    @Composable
    fun Stock(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("موجودی انبار", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val inv by invData(branch)
                var locationId by remember(branch) { mutableStateOf<GlobalId?>(null) }
                var opening by remember { mutableStateOf(false) }
                Page {
                    Loaded(inv) { d ->
                        val loc = locationId ?: d.locations.firstOrNull()?.id
                        val balances by load(session, loc) { loc?.let { overview.stock(it) }.orEmpty() }
                        if (d.locations.size > 1) Picker("انبار", d.locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                        if (d.items.isEmpty()) EmptyState("کالایی تعریف نشده است.", "تعریف کالا") { nav.go(Route.Items) }
                        Loaded(balances) { list ->
                            val byItem = list.associateBy { it.itemId }
                            SCard {
                                d.items.forEach { item ->
                                    val b = byItem[item.id]
                                    val q = b?.quantity ?: Quantity.ZERO
                                    val low = !item.minimumStock.isZero && q < item.minimumStock
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                            val avg = if (b != null && !q.isZero) " · میانگین ${Fa.toman(b.value.rial * Quantity.SCALE / q.micros)} تومان" else ""
                                            Text("ارزش ${Fa.toman(b?.value?.rial ?: 0)} تومان$avg", style = SabouType.caption, color = Sabou.colors.muted)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("${Fa.quantity(q)} ${unitName(item.unit)}", style = SabouType.bodyStrong, color = if (low) Sabou.colors.danger else Sabou.colors.ink)
                                            if (low) Chip("زیر حداقل", ChipKind.DANGER)
                                        }
                                    }
                                    Divider()
                                }
                            }
                        }
                        if (session.can(Permission.INVENTORY_OPENING)) {
                            if (!opening) SecondaryButton("ثبت موجودی اول دوره", { opening = true })
                            else OpeningForm(branch, d, loc) { opening = false }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun OpeningForm(branch: Scope.Branch, d: InvData, locationId: GlobalId?, done: () -> Unit) {
        val session = LocalSession.current
        var itemId by remember { mutableStateOf<GlobalId?>(null) }
        var qty by remember { mutableStateOf<Quantity?>(null) }
        var value by remember { mutableStateOf<Money?>(null) }
        val action = rememberAction()
        val id = remember { GlobalId.new() }
        FormCard("موجودی اول دوره") {
            Text("کالایی که از قبل در انبار دارید با ارزش آن ثبت می‌شود (طرف مقابل: سرمایه).", style = SabouType.caption, color = Sabou.colors.muted)
            Picker("کالا", d.items.map { Choice(it.id, it.name, unitName(it.unit)) }, itemId, { itemId = it })
            QuantityInput("مقدار", d.items.firstOrNull { it.id == itemId }?.let { unitName(it.unit) } ?: "", { qty = it })
            MoneyInput("ارزش کل", value, { value = it })
            action.error?.let { Banner(it) }
            PrimaryButton("ثبت", {
                action.run({ inventory.openingStock(RecordOpeningStock(id, branch, locationId!!, listOf(ReceiptLine(itemId!!, qty!!, value!!)), session.today)) }) { done() }
            }, enabled = locationId != null && itemId != null && qty != null && value != null, busy = action.busy)
        }
    }

    private val wasteReasons = listOf(
        Choice(WasteReason.SPOILAGE, "فساد"), Choice(WasteReason.EXPIRED, "تاریخ گذشته"), Choice(WasteReason.PREPARATION, "ضایعات آماده‌سازی"),
        Choice(WasteReason.DAMAGE, "آسیب‌دیدگی"), Choice(WasteReason.OTHER, "سایر (با توضیح)"),
    )

    @Composable
    fun Waste(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("ثبت ضایعات", onBack = nav.back)
            WithBranch { branch ->
                val inv by invData(branch)
                var locationId by remember { mutableStateOf<GlobalId?>(null) }
                var itemId by remember { mutableStateOf<GlobalId?>(null) }
                var qty by remember { mutableStateOf<Quantity?>(null) }
                var reason by remember { mutableStateOf(WasteReason.SPOILAGE) }
                var note by remember { mutableStateOf("") }
                var date by remember { mutableStateOf(session.today) }
                val id = remember { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(inv) { d ->
                        val loc = locationId ?: d.locations.firstOrNull()?.id
                        FormCard {
                            if (d.locations.size > 1) Picker("انبار", d.locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                            Picker("کالا", d.items.map { Choice(it.id, it.name, unitName(it.unit)) }, itemId, { itemId = it })
                            QuantityInput("مقدار", d.items.firstOrNull { it.id == itemId }?.let { unitName(it.unit) } ?: "", { qty = it })
                            Picker("دلیل", wasteReasons, reason, { reason = it })
                            TextInput("توضیح", note, { note = it })
                            DateInput("تاریخ", date, { date = it }, session.today)
                            action.error?.let { Banner(it) }
                            PrimaryButton("ثبت ضایعات", {
                                action.run({ inventory.waste(RecordWaste(id.value, branch, loc!!, itemId!!, qty!!, reason, note, date)) }) { nav.back() }
                            }, enabled = loc != null && itemId != null && qty != null && (reason != WasteReason.OTHER || note.isNotBlank()), busy = action.busy)
                            Text("ضایعات به بهای میانگین از انبار کم و به هزینه ضایعات منظور می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Count(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("انبارگردانی", onBack = nav.back)
            WithBranch { branch ->
                val inv by invData(branch)
                var locationId by remember { mutableStateOf<GlobalId?>(null) }
                Page {
                    Loaded(inv) { d ->
                        val loc = locationId ?: d.locations.firstOrNull()?.id
                        if (d.locations.size > 1) Picker("انبار", d.locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                        if (loc != null) key(loc) { CountSheet(nav, branch, loc, d.items) }
                    }
                }
            }
        }
    }

    @Composable
    private fun CountSheet(nav: Nav, branch: Scope.Branch, locationId: GlobalId, items: List<Item>) {
        val session = LocalSession.current
        val balances by load(session, locationId) { overview.stock(locationId) }
        val counted = remember { mutableStateMapOf<GlobalId, Quantity?>() }
        val id = remember { GlobalId.new() }
        var confirm by remember { mutableStateOf(false) }
        val action = rememberAction()
        Loaded(balances) { list ->
            val book = list.associateBy({ it.itemId }, { it.quantity })
            FormCard("مقدار شمارش‌شده") {
                Text("فقط کالاهایی را که شمردید وارد کنید؛ اختلاف به حساب مغایرت انبار ثبت می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                items.forEach { item ->
                    key(item.id) {
                        QuantityInput("${item.name} — دفتری ${Fa.quantity(book[item.id] ?: Quantity.ZERO)}", unitName(item.unit), { counted[item.id] = it })
                    }
                }
                action.error?.let { Banner(it) }
                PrimaryButton("ثبت انبارگردانی", { confirm = true }, enabled = counted.values.any { it != null }, busy = action.busy)
            }
        }
        if (confirm) {
            Confirm("ثبت انبارگردانی؟", "موجودی دفتری کالاهای شمارش‌شده با مقدار شمارش جایگزین می‌شود.", "ثبت", onConfirm = {
                val lines = counted.entries.mapNotNull { (k, v) -> v?.let { CountLine(k, it) } }
                action.run({ inventory.count(PostStockCount(id, branch, locationId, lines, session.today)) }) { nav.back() }
            }, onDismiss = { confirm = false })
        }
    }

    @Composable
    fun StockTransfer(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("انتقال کالا", onBack = nav.back)
            WithBranch { branch ->
                val data by load(session, branch) {
                    Triple(overview.items().filter { it.isActive }, overview.locations(branch).filter { it.isActive }, overview.transferTargets())
                }
                var source by remember { mutableStateOf<GlobalId?>(null) }
                var target by remember { mutableStateOf<GlobalId?>(null) }
                var itemId by remember { mutableStateOf<GlobalId?>(null) }
                var qty by remember { mutableStateOf<Quantity?>(null) }
                val lines = remember { mutableStateListOf<IssueLine>() }
                var note by remember { mutableStateOf("") }
                val id = remember { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { (items, own, all) ->
                        FormCard {
                            Picker("از انبار", own.map { Choice(it.id, it.name) }, source, { source = it })
                            Picker("به انبار", all.filter { it.id != source }.map { Choice(it.id, it.name, if (it.scope != branch) "شعبه دیگر" else null) }, target, { target = it })
                            Divider()
                            Picker("کالا", items.map { Choice(it.id, it.name, unitName(it.unit)) }, itemId, { itemId = it })
                            QuantityInput("مقدار", items.firstOrNull { it.id == itemId }?.let { unitName(it.unit) } ?: "", { qty = it })
                            SecondaryButton("افزودن به فهرست", { lines.add(IssueLine(itemId!!, qty!!)); itemId = null }, enabled = itemId != null && qty != null)
                            lines.forEach { l -> KeyValue(items.firstOrNull { it.id == l.itemId }?.name ?: "", Fa.quantity(l.quantity)) }
                            TextInput("توضیح", note, { note = it })
                            action.error?.let { Banner(it) }
                            PrimaryButton("ثبت انتقال", {
                                action.run({ inventory.transfer(TransferStock(id.value, branch, source!!, target!!, lines.toList(), session.today, note)) }) { nav.back() }
                            }, enabled = source != null && target != null && lines.isNotEmpty(), busy = action.busy)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Recipes(nav: Nav) = MeScreens.Menu(nav)

    // ------------------------------------------------------------ Purchasing

    @Composable
    fun Purchases(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.invoices() }
        Column(Modifier.fillMaxSize()) {
            Header("خرید و دریافت کالا", onBack = nav.back)
            Page {
                if (session.can(Permission.PURCHASE_RECORD)) PrimaryButton("ثبت فاکتور خرید", { nav.go(Route.NewPurchase) })
                Loaded(data) { list ->
                    SCard { KeyValue("جمع بدهی (تومان)", Fa.toman(Money.sum(list.filter { it.invoice.status == InvoiceStatus.POSTED }.map { it.outstanding })), strong = true) }
                    if (list.isEmpty()) EmptyState("هنوز فاکتوری ثبت نشده است.")
                    list.forEach { (inv, supplier, outstanding) ->
                        val sub = "${Fa.digits(inv.supplierInvoiceNo)} · ${Fa.date(inv.date)}" +
                            if (inv.status == InvoiceStatus.REVERSED) " · برگشت‌خورده" else if (outstanding.isZero) " · تسویه" else " · سررسید ${Fa.date(inv.dueDate)}"
                        NavRow(R.drawable.ic_purchase, supplier, sub, Fa.tomanShort(outstanding.rial), tint = Sabou.colors.onAccentSoft,
                            tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.PurchaseDetail(inv.id)) })
                    }
                }
            }
        }
    }

    private class LineDraft(item: GlobalId?, qty: Quantity?, value: Money?) {
        var item by mutableStateOf(item)
        var qty by mutableStateOf(qty)
        var value by mutableStateOf(value)
    }

    @Composable
    fun NewPurchase(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("فاکتور خرید", "دریافت کالا به انبار", onBack = nav.back)
            WithBranch { branch ->
                val data by load(session, branch) {
                    // A storekeeper records invoices but may not pay: payment accounts load only with PURCHASE_PAY.
                    Triple(overview.suppliers().map { it.supplier }.filter { it.isActive },
                        InvData(overview.items().filter { it.isActive }, overview.locations(branch).filter { it.isActive }),
                        if (session.can(Permission.PURCHASE_PAY)) overview.paymentAccounts(branch) else emptyList())
                }
                var supplier by remember { mutableStateOf<GlobalId?>(null) }
                var number by remember { mutableStateOf("") }
                var locationId by remember { mutableStateOf<GlobalId?>(null) }
                var date by remember { mutableStateOf(session.today) }
                var due by remember { mutableStateOf(session.today.plusDays(30)) }
                val lines = remember { mutableStateListOf(LineDraft(null, null, null)) }
                var payNow by remember { mutableStateOf(false) }
                var payAccount by remember { mutableStateOf<GlobalId?>(null) }
                var payAmount by remember { mutableStateOf<Money?>(null) }
                val id = remember { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { (sups, inv, accounts) ->
                        val loc = locationId ?: inv.locations.firstOrNull()?.id
                        FormCard {
                            PickerOrHint("تأمین‌کننده", sups.map { Choice(it.id, it.name, it.phone) }, supplier, { supplier = it }, "ابتدا تأمین‌کننده را تعریف کنید.")
                            if (sups.isEmpty()) SecondaryButton("تعریف تأمین‌کننده", { nav.go(Route.Suppliers) })
                            TextInput("شماره فاکتور تأمین‌کننده", number, { number = it })
                            if (inv.locations.size > 1) Picker("انبار دریافت", inv.locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                            DateInput("تاریخ فاکتور", date, { date = it }, session.today)
                            DateInput("سررسید", due, { due = it }, session.today)
                        }
                        FormCard("اقلام") {
                            lines.forEachIndexed { i, l ->
                                key(l) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("ردیف ${Fa.number(i + 1L)}", style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                            if (lines.size > 1) Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { lines.remove(l) }.padding(6.dp))
                                        }
                                        Picker("کالا", inv.items.map { Choice(it.id, it.name, unitName(it.unit)) }, l.item, { l.item = it })
                                        QuantityInput("مقدار", inv.items.firstOrNull { it.id == l.item }?.let { unitName(it.unit) } ?: "", { l.qty = it })
                                        MoneyInput("مبلغ کل ردیف", l.value, { l.value = it })
                                        Divider()
                                    }
                                }
                            }
                            SecondaryButton("افزودن ردیف", { lines.add(LineDraft(null, null, null)) })
                            val total = lines.sumOf { it.value?.rial ?: 0 }
                            KeyValue("جمع فاکتور (تومان)", Fa.toman(total), strong = true)
                        }
                        if (session.can(Permission.PURCHASE_PAY)) FormCard {
                            Row(Modifier.clickable { payNow = !payNow }, verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = payNow, onCheckedChange = { payNow = it })
                                Text("همین الان پرداخت می‌کنم", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            }
                            if (payNow) {
                                Picker("از حساب", accountChoices(accounts), payAccount, { payAccount = it })
                                MoneyInput("مبلغ پرداخت", payAmount, { payAmount = it })
                            }
                        }
                        action.error?.let { Banner(it) }
                        val ready = supplier != null && number.isNotBlank() && loc != null &&
                            lines.all { it.item != null && it.qty != null && it.value != null } && (!payNow || (payAccount != null && payAmount != null))
                        PrimaryButton("ثبت فاکتور", {
                            val invoiceLines = lines.map { InvoiceLine(it.item!!, it.qty!!, it.value!!) }
                            val pay = if (payNow) ImmediatePayment(payAccount!!, payAmount!!) else null
                            action.run({ purchasing.postInvoice(PostPurchaseInvoice(id.value, branch, supplier!!, number, loc!!, date, due, invoiceLines, pay)) }) { nav.back() }
                        }, enabled = ready, busy = action.busy)
                    }
                }
            }
        }
    }

    private class PurchaseView(
        val invoice: ir.sabou.purchasing.PurchaseInvoice,
        val supplier: String,
        val outstanding: Money,
        val payments: List<ir.sabou.purchasing.SupplierPayment>,
        val items: Map<GlobalId, Item>,
        val accounts: List<ir.sabou.treasury.TreasuryAccount>,
    )

    @Composable
    fun PurchaseDetail(nav: Nav, invoiceId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, invoiceId) {
            val v = overview.invoice(invoiceId)
            PurchaseView(
                invoice = v.invoice,
                supplier = v.supplier,
                outstanding = v.outstanding,
                payments = v.payments,
                items = overview.items().associateBy { it.id },
                accounts = if (session.can(Permission.PURCHASE_PAY)) overview.paymentAccounts() else emptyList(),
            )
        }
        var payAccount by remember { mutableStateOf<GlobalId?>(null) }
        var payAmount by remember { mutableStateOf<Money?>(null) }
        var reason by remember { mutableStateOf("") }
        var confirmReverse by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
        val id = remember { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        pending?.let { (title, act) -> Confirm(title, "سند برگشتی ثبت می‌شود و سابقه حذف نمی‌شود.", "برگشت بزن", act, { pending = null }, danger = true) }
        Column(Modifier.fillMaxSize()) {
            Header("فاکتور خرید", onBack = nav.back)
            Page {
                Loaded(data) { d ->
                    val inv = d.invoice
                    SCard {
                        Text(d.supplier, style = SabouType.section, color = Sabou.colors.ink)
                        KeyValue("شماره", Fa.digits(inv.supplierInvoiceNo))
                        KeyValue("تاریخ / سررسید", "${Fa.date(inv.date)} / ${Fa.date(inv.dueDate)}")
                        Divider()
                        inv.lines.forEach { l -> KeyValue("${d.items[l.itemId]?.name ?: ""} × ${Fa.quantity(l.quantity)}", Fa.toman(l.value)) }
                        Divider()
                        KeyValue("جمع فاکتور", Fa.toman(inv.total))
                        KeyValue("مانده (تومان)", Fa.toman(d.outstanding), strong = true)
                        if (inv.status == InvoiceStatus.REVERSED) Chip("برگشت‌خورده", ChipKind.DANGER)
                    }
                    if (d.payments.isNotEmpty()) SCard {
                        Text("پرداخت‌ها", style = SabouType.section, color = Sabou.colors.ink)
                        d.payments.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${Fa.date(p.date)} · ${d.accounts.firstOrNull { it.id == p.treasuryAccountId }?.name ?: ""}" + if (p.reversed) " · برگشت‌خورده" else "",
                                    style = SabouType.body, color = Sabou.colors.muted, modifier = Modifier.weight(1f))
                                Text(Fa.toman(p.amount), style = SabouType.bodyStrong, color = Sabou.colors.moneyOut)
                                if (!p.reversed && session.can(Permission.PURCHASE_REVERSE)) {
                                    Text("برگشت", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable {
                                        pending = "برگشت این پرداخت؟" to {
                                            action.run({ purchasing.reversePayment(ReverseSupplierPayment(GlobalId.new(), inv.scope, p.id, session.today, "اصلاح پرداخت")) })
                                        }
                                    }.padding(start = 10.dp))
                                }
                            }
                        }
                    }
                    action.error?.let { Banner(it) }
                    if (inv.status == InvoiceStatus.POSTED && !d.outstanding.isZero && session.can(Permission.PURCHASE_PAY)) {
                        FormCard("پرداخت به تأمین‌کننده") {
                            Picker("از حساب", accountChoices(d.accounts), payAccount, { payAccount = it })
                            MoneyInput("مبلغ", payAmount, { payAmount = it }, hint = "مانده: ${Fa.toman(d.outstanding)} تومان")
                            PrimaryButton("ثبت پرداخت", {
                                action.run({ purchasing.payInvoice(PaySupplierInvoice(id.value, inv.scope, inv.id, payAccount!!, payAmount!!, session.today)) }) {
                                    id.value = GlobalId.new(); payAmount = null
                                }
                            }, enabled = payAccount != null && payAmount != null, busy = action.busy)
                        }
                    }
                    if (inv.status == InvoiceStatus.POSTED && session.can(Permission.PURCHASE_REVERSE)) {
                        FormCard("مرجوعی یا برگشت فاکتور") {
                            ReturnForm(inv.scope, inv.id, inv.lines.map { it.itemId }.distinct().map { Choice(it, d.items[it]?.name ?: "") })
                            Divider()
                            TextInput("دلیل برگشت کل فاکتور", reason, { reason = it })
                            SecondaryButton("برگشت کل فاکتور", { confirmReverse = true }, enabled = reason.trim().length >= 3, danger = true)
                        }
                    }
                    if (confirmReverse) {
                        Confirm("برگشت فاکتور؟", "کالاها از انبار خارج و بدهی تأمین‌کننده خنثی می‌شود. ابتدا پرداخت‌ها و مرجوعی‌ها باید برگشت خورده باشند.", "برگشت بزن",
                            onConfirm = { action.run({ purchasing.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), inv.scope, inv.id, session.today, reason)) }) { nav.back() } },
                            onDismiss = { confirmReverse = false }, danger = true)
                    }
                }
            }
        }
    }

    @Composable
    private fun ReturnForm(scope: Scope.Branch, invoiceId: GlobalId, items: List<Choice<GlobalId>>) {
        val session = LocalSession.current
        var itemId by remember { mutableStateOf<GlobalId?>(null) }
        var qty by remember { mutableStateOf<Quantity?>(null) }
        var reason by remember { mutableStateOf("") }
        val action = rememberAction()
        Picker("کالای مرجوعی", items, itemId, { itemId = it })
        QuantityInput("مقدار مرجوعی", "", { qty = it })
        TextInput("دلیل مرجوعی", reason, { reason = it })
        action.error?.let { Banner(it) }
        SecondaryButton("ثبت مرجوعی (به قیمت فاکتور)", {
            action.run({ purchasing.returnGoods(ReturnToSupplier(GlobalId.new(), scope, invoiceId, listOf(IssueLine(itemId!!, qty!!)), session.today, reason)) })
        }, enabled = itemId != null && qty != null && reason.isNotBlank() && !action.busy)
    }

    @Composable
    fun Suppliers(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.suppliers().map { it.supplier to it.owed } }
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        val id = remember { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("تأمین‌کنندگان", onBack = nav.back)
            Page {
                Loaded(data) { list ->
                    if (list.isEmpty()) EmptyState("هنوز تأمین‌کننده‌ای ثبت نشده است.")
                    list.forEach { (s, balance) ->
                        SCard { KeyValue(s.name + if (s.phone.isNotBlank()) " · ${Fa.digits(s.phone)}" else "", "بدهی ${Fa.toman(balance)}") }
                    }
                }
                if (session.can(Permission.SUPPLIER_MANAGE)) {
                    FormCard("تأمین‌کننده جدید") {
                        TextInput("نام", name, { name = it })
                        TextInput("تلفن", phone, { phone = it }, keyboard = KeyboardType.Phone)
                        action.error?.let { Banner(it) }
                        PrimaryButton("ثبت", {
                            action.run({ purchasing.registerSupplier(RegisterSupplier(id.value, name, phone)) }) { name = ""; phone = ""; id.value = GlobalId.new() }
                        }, enabled = name.trim().length >= 2, busy = action.busy)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ Personnel and payroll

    @Composable
    fun Personnel(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("کارکنان", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { overview.employees(branch) }
                var name by remember { mutableStateOf("") }
                var nationalId by remember { mutableStateOf("") }
                var salary by remember { mutableStateOf<Money?>(null) }
                val id = remember { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { list ->
                        if (list.isEmpty()) EmptyState("کارمندی برای این شعبه ثبت نشده است.")
                        list.forEach { e -> SCard { KeyValue(e.name, "حقوق ${Fa.tomanShort(e.monthlySalary.rial)}"); Text("کد ملی ${Fa.digits(e.nationalId)}", style = SabouType.caption, color = Sabou.colors.muted) } }
                    }
                    if (session.can(Permission.PERSONNEL_MANAGE)) {
                        FormCard("کارمند جدید") {
                            TextInput("نام و نام خانوادگی", name, { name = it })
                            TextInput("کد ملی", nationalId, { nationalId = it }, keyboard = KeyboardType.Number)
                            MoneyInput("حقوق پایه ماهانه", salary, { salary = it })
                            action.error?.let { Banner(it) }
                            PrimaryButton("ثبت", {
                                action.run({ payroll.registerEmployee(RegisterEmployee(id.value, branch, name, nationalId, salary!!)) }) {
                                    name = ""; nationalId = ""; salary = null; id.value = GlobalId.new()
                                }
                            }, enabled = name.isNotBlank() && nationalId.isNotBlank() && salary != null, busy = action.busy)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Attendance(nav: Nav) {
        val session = LocalSession.current
        var date by remember { mutableStateOf(session.today) }
        Column(Modifier.fillMaxSize()) {
            Header("حضور و غیاب", onBack = nav.back)
            WithBranch { branch ->
                val data by load(session, branch, date) { overview.attendance(branch, date) }
                Page {
                    SCard { DateInput("روز", date, { date = it }, session.today) }
                    Loaded(data) { list ->
                        if (list.isEmpty()) EmptyState("کارمند فعالی ثبت نشده است.")
                        list.forEach { (e, record) -> key(e.id, date) { AttendanceRow(branch, date, e.id, e.name, record) } }
                    }
                }
            }
        }
    }

    @Composable
    private fun AttendanceRow(branch: Scope.Branch, date: BusinessDate, employeeId: GlobalId, name: String, record: ir.sabou.payroll.AttendanceRecord?) {
        fun hours(min: Int?) = min?.takeIf { it > 0 }?.let { Fa.quantity(Quantity.of(it.toLong() * Quantity.SCALE / 60)) } ?: ""
        fun minutes(text: String): Int? = if (text.isBlank()) 0 else Fa.parseQuantity(text)?.let { (it.micros * 60 / Quantity.SCALE).toInt() }
        var worked by remember { mutableStateOf(hours(record?.workedMinutes)) }
        var overtime by remember { mutableStateOf(hours(record?.overtimeMinutes)) }
        var absent by remember { mutableStateOf(hours(record?.absentMinutes)) }
        val action = rememberAction()
        FormCard(name) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("کارکرد (ساعت)", worked, { worked = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                TextInput("اضافه‌کار", overtime, { overtime = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                TextInput("غیبت", absent, { absent = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            }
            action.error?.let { Banner(it) }
            val w = minutes(worked); val o = minutes(overtime); val a = minutes(absent)
            SecondaryButton(if (record == null) "ثبت" else "اصلاح", {
                action.run({ payroll.recordAttendance(RecordAttendance(GlobalId.new(), branch, employeeId, date, w!!, o!!, a!!)) })
            }, enabled = w != null && o != null && a != null && !action.busy)
        }
    }

    private class PayrollView(
        val runs: List<ir.sabou.payroll.PayrollRun>,
        val names: Map<GlobalId, String>,
        val accounts: List<ir.sabou.treasury.TreasuryAccount>,
        val insurance: Money,
        val tax: Money,
        val unpaid: Map<GlobalId, Map<GlobalId, Money>>,
    )

    @Composable
    fun Payroll(nav: Nav) {
        val session = LocalSession.current
        // Payroll runs cover whole Jalali months: choose one of the last twelve.
        val months = remember(session.today) {
            val j = Fa.jalali(session.today)
            (0 until 12).map { back ->
                val index = j.year * 12 + (j.month - 1) - back
                val y = index / 12; val m = index % 12 + 1
                Choice(Fa.fromJalali(y, m, 1) to Fa.fromJalali(y, m, Fa.monthLength(y, m)), "${Fa.monthNames[m - 1]} ${Fa.digits(y.toString())}")
            }
        }
        var period by remember { mutableStateOf(months.first().value) }
        val fromDate = period.first
        val toDate = period.second
        Column(Modifier.fillMaxSize()) {
            Header("حقوق", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) {
                    val v = overview.payroll(branch)
                    PayrollView(
                        runs = v.runs, names = v.names, insurance = v.insurance, tax = v.tax, unpaid = v.unpaid,
                        accounts = if (session.can(Permission.PAYROLL_PAY)) overview.paymentAccounts(branch) else emptyList(),
                    )
                }
                val action = rememberAction()
                var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
                pending?.let { (title, act) -> Confirm(title, "سند حقوق این دوره برگشت می‌خورد؛ می‌توانید دوباره محاسبه و تأیید کنید.", "برگشت بزن", act, { pending = null }, danger = true) }
                val calcId = remember { mutableStateOf(GlobalId.new()) }
                var payAccount by remember { mutableStateOf<GlobalId?>(null) }
                Page {
                    if (session.can(Permission.PAYROLL_CALCULATE)) {
                        FormCard("محاسبه حقوق دوره") {
                            Picker("ماه", months, period, { period = it })
                            action.error?.let { Banner(it) }
                            PrimaryButton("محاسبه", {
                                action.run({ payroll.calculate(CalculatePayroll(calcId.value, branch, fromDate, toDate)) }) { calcId.value = GlobalId.new() }
                            }, busy = action.busy)
                            Text("محاسبه طبق پارامترهای قانونی ثبت‌شده برای آن سال انجام می‌شود. تأیید باید توسط فرد دیگری انجام شود.", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                    Loaded(data) { d ->
                        if (d.runs.isEmpty()) EmptyState("هنوز حقوقی محاسبه نشده است.")
                        if (d.accounts.isNotEmpty()) Picker("حساب پرداخت حقوق و بیمه", accountChoices(d.accounts), payAccount, { payAccount = it })
                        d.runs.forEach { run ->
                            SCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${Fa.date(run.from)} تا ${Fa.date(run.to)}", style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                    Chip(when (run.status) { RunStatus.DRAFT -> "پیش‌نویس"; RunStatus.APPROVED -> "تأیید شده"; RunStatus.REVERSED -> "برگشت‌خورده" },
                                        if (run.status == RunStatus.APPROVED) ChipKind.PRIMARY else ChipKind.NEUTRAL)
                                }
                                run.payslips.forEach { p ->
                                    Divider()
                                    KeyValue(d.names[p.employeeId] ?: "", "خالص ${Fa.toman(p.net)}")
                                    Text("ناخالص ${Fa.toman(p.gross)} · بیمه ${Fa.toman(p.employeeInsurance)} · مالیات ${Fa.toman(p.incomeTax)}",
                                        style = SabouType.caption, color = Sabou.colors.muted)
                                    val unpaid = d.unpaid[run.id]?.get(p.employeeId)
                                    if (unpaid != null && !unpaid.isZero && session.can(Permission.PAYROLL_PAY)) {
                                        SecondaryButton("پرداخت ${Fa.toman(unpaid)} تومان", {
                                            action.run({ payroll.pay(PaySalary(GlobalId.new(), branch, run.id, p.employeeId, payAccount!!, unpaid, session.today)) })
                                        }, enabled = payAccount != null && !action.busy)
                                    }
                                }
                                if (run.status == RunStatus.DRAFT && session.can(Permission.PAYROLL_APPROVE)) {
                                    PrimaryButton("تأیید و ثبت سند حقوق", { action.run({ payroll.approve(ApprovePayroll(GlobalId.new(), branch, run.id)) }) }, busy = action.busy)
                                }
                                if (run.status == RunStatus.APPROVED && session.can(Permission.PAYROLL_APPROVE)) {
                                    SecondaryButton("برگشت لیست حقوق", {
                                        pending = "برگشت لیست حقوق؟" to { action.run({ payroll.reverse(ReversePayroll(GlobalId.new(), branch, run.id, session.today, "اصلاح محاسبه")) }) }
                                    }, danger = true)
                                }
                            }
                        }
                        if (!d.insurance.isZero || !d.tax.isZero) {
                            FormCard("بیمه و مالیات پرداخت‌نشده") {
                                KeyValue("بیمه", Fa.toman(d.insurance) + " تومان")
                                KeyValue("مالیات حقوق", Fa.toman(d.tax) + " تومان")
                                if (session.can(Permission.PAYROLL_PAY)) {
                                    if (!d.insurance.isZero) SecondaryButton("پرداخت بیمه", {
                                        action.run({ payroll.remit(RemitLiability(GlobalId.new(), branch, LiabilityKind.INSURANCE, payAccount!!, d.insurance, session.today)) })
                                    }, enabled = payAccount != null)
                                    if (!d.tax.isZero) SecondaryButton("پرداخت مالیات", {
                                        action.run({ payroll.remit(RemitLiability(GlobalId.new(), branch, LiabilityKind.INCOME_TAX, payAccount!!, d.tax, session.today)) })
                                    }, enabled = payAccount != null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
