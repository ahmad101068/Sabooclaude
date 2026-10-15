package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.ui.AttachmentList
import ir.sabou.app.ui.AttachmentPicker
import ir.sabou.app.ui.ExportButtons
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.OrderDraftLine
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
import ir.sabou.app.ui.components.ItemQuantityInput
import ir.sabou.app.ui.components.QuantityInput
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.Segmented
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.rememberCommandId
import ir.sabou.app.ui.rememberRows
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.PlanLine
import ir.sabou.core.ReportTables
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.Item
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountCode
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.Permission
import ir.sabou.purchasing.AccountLine
import ir.sabou.purchasing.ApplySupplierCredit
import ir.sabou.purchasing.AttachToInvoice
import ir.sabou.purchasing.CancelPurchaseOrder
import ir.sabou.purchasing.CreatePurchaseOrder
import ir.sabou.purchasing.ImmediatePayment
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.purchasing.OrderLine
import ir.sabou.purchasing.OrderStatus
import ir.sabou.purchasing.PaySupplierInvoice
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ReleaseCreditAllocation
import ir.sabou.purchasing.ResolveReviewLine
import ir.sabou.purchasing.ReturnToSupplier
import ir.sabou.purchasing.ReverseSupplierPayment
import ir.sabou.purchasing.ReversePurchaseInvoice
import ir.sabou.purchasing.ReviewLine
import ir.sabou.purchasing.Supplier
import ir.sabou.purchasing.SupplierNames
import ir.sabou.purchasing.UpdateSupplier

/**
 * Purchasing: invoices (goods, expense lines, held lines, attachments), orders, suggested purchases,
 * supplier delivery days, price changes and the review queue.
 */
object PurchaseScreens {
    private val weekdayShort = listOf("ش", "ی", "د", "س", "چ", "پ", "ج")
    private val weekdayNames = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه")

    private fun minutesNow(): Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    private fun clock(minutes: Int) = Fa.digits("%02d:%02d".format(minutes / 60, minutes % 60))

    /** «یکشنبه ۱۸ مهر · سفارش تا امروز ساعت ۱۴:۰۰» */
    private fun deliveryText(d: ir.sabou.purchasing.Delivery, today: BusinessDate): String {
        val by = when (d.orderBy) { today -> "امروز"; today.plusDays(1) -> "فردا"; else -> Fa.dayTitle(d.orderBy) }
        return "تحویل ${Fa.dayTitle(d.date)} · سفارش تا $by" + (d.cutoffMinutes?.let { " ساعت ${clock(it)}" } ?: "")
    }

    /** Unit price in rial per whole unit, or null. */
    private fun unitPrice(qty: Quantity?, value: Money?): Long? =
        if (qty == null || value == null || qty.isZero) null else Ratio.mulDiv(value.rial, Quantity.SCALE, qty.micros)

    private fun changeText(previous: Long, now: Long): String {
        val bp = ir.sabou.kernel.Ratio.changeBp(previous, now) ?: return "قیمت اول"
        return (if (bp > 0) "+" else if (bp < 0) "−" else "") + Fa.percent(kotlin.math.abs(bp))
    }

    // ------------------------------------------------------------ Invoices

    @Composable
    fun Purchases(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) {
            val list = overview.invoices()
            val review = buying.reviewQueue().size
            val changes = buying.priceChanges(session.today.plusDays(-30), session.today).size
            Triple(list, review, changes)
        }
        Column(Modifier.fillMaxSize()) {
            Header("خرید و دریافت کالا", onBack = nav.back)
            Page {
                if (session.can(Permission.PURCHASE_RECORD)) PrimaryButton("ثبت فاکتور خرید", { nav.go(Route.NewPurchase) })
                Loaded(data) { (list, review, changes) ->
                    SCard { KeyValue("جمع بدهی (ریال)", Fa.rial(Money.sum(list.filter { it.invoice.status == InvoiceStatus.POSTED }.map { it.outstanding })), strong = true) }
                    NavRow(R.drawable.ic_purchase, "سفارش‌های خرید", "پیشنهاد خرید، سفارش و تحویل", tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft,
                        onClick = { nav.go(Route.Orders) })
                    if (session.can(Permission.PURCHASE_APPROVE) || session.can(Permission.APPROVAL_RULES)) NavRow(R.drawable.ic_check, "در انتظار تأیید", "تأیید فاکتورها پیش از پرداخت",
                        onClick = { nav.go(Route.PendingApprovals) })
                    NavRow(R.drawable.ic_alert, "در انتظار بررسی", if (review > 0) "${Fa.number(review.toLong())} ردیف کالای ناشناخته" else "ردیفی در انتظار نیست",
                        tint = if (review > 0) Sabou.colors.danger else Sabou.colors.primary, onClick = { nav.go(Route.ReviewQueue) })
                    NavRow(R.drawable.ic_alert, "تغییر قیمت‌ها", if (changes > 0) "${Fa.number(changes.toLong())} تغییر قیمت در ۳۰ روز اخیر" else "تغییر قیمت مهمی نبوده",
                        onClick = { nav.go(Route.PriceChanges) })
                    if (list.isEmpty()) EmptyState("هنوز فاکتوری ثبت نشده است.")
                    list.forEach { (inv, supplier, outstanding, number) ->
                        val sub = (number?.let { Fa.digits(it) + " · " } ?: "") + "فاکتور ${Fa.digits(inv.supplierInvoiceNo)} · ${Fa.date(inv.date)}" +
                            (if (inv.status == InvoiceStatus.REVERSED) " · برگشت‌خورده" else if (outstanding.isZero) " · تسویه" else " · سررسید ${Fa.date(inv.dueDate)}") +
                            (if (inv.openReviewLines.isNotEmpty()) " · در انتظار بررسی" else "")
                        NavRow(R.drawable.ic_purchase, supplier, sub, Fa.rialShort(outstanding.rial), tint = Sabou.colors.onAccentSoft,
                            tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.PurchaseDetail(inv.id)) })
                    }
                }
                ExportButtons("فاکتورهای-خرید") { listOf(ReportTables.invoices(overview.invoices())) }
            }
        }
    }

    private class GoodsRow(item: GlobalId?, qty: Quantity?, value: Money?, supplierName: String, expiresDay: Long? = null) {
        var item by mutableStateOf(item)
        var qty by mutableStateOf(qty)
        var value by mutableStateOf(value)
        var supplierName by mutableStateOf(supplierName)
        /** Expiry as an epoch day (kept as a plain number across process death). */
        var expiresDay by mutableStateOf(expiresDay)
        val complete: Boolean get() = item != null && qty != null && value != null
    }

    private class AccountRow(account: String?, amount: Money?, branch: GlobalId?, memo: String) {
        var account by mutableStateOf(account)
        var amount by mutableStateOf(amount)
        var branch by mutableStateOf(branch)
        var memo by mutableStateOf(memo)
        val complete: Boolean get() = account != null && amount != null
    }

    private class HeldRow(name: String, qtyNote: String, amount: Money?) {
        var name by mutableStateOf(name)
        var qtyNote by mutableStateOf(qtyNote)
        var amount by mutableStateOf(amount)
        val complete: Boolean get() = name.isNotBlank() && amount != null
    }

    private class InvoiceForm(
        val suppliers: List<Supplier>,
        val items: List<Item>,
        val locations: List<ir.sabou.inventory.Location>,
        val accounts: List<ir.sabou.treasury.TreasuryAccount>,
        val expenseAccounts: List<ir.sabou.ledger.Account>,
        val branches: List<ir.sabou.platform.Branch>,
        val order: ir.sabou.core.OrderRow?,
    )

    @Composable
    fun NewPurchase(nav: Nav, orderId: GlobalId?) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header(if (orderId == null) "فاکتور خرید" else "تحویل سفارش", "دریافت کالا و ثبت بدهی", onBack = nav.back)
            WithBranch { branch ->
                val data by load(session, branch, orderId) {
                    InvoiceForm(
                        overview.suppliers().map { it.supplier }.filter { it.isActive },
                        overview.items().filter { it.isActive },
                        overview.locations(branch).filter { it.isActive },
                        // A storekeeper records invoices but may not pay: payment accounts load only with PURCHASE_PAY.
                        if (session.can(Permission.PURCHASE_PAY)) overview.paymentAccounts(branch) else emptyList(),
                        buying.expenseAccounts(),
                        overview.branches(),
                        orderId?.let { buying.order(it) },
                    )
                }
                Page {
                    Loaded(data) { form ->
                        if (form.order != null && form.order.order.scope != branch) Banner("این سفارش متعلق به شعبه‌ی دیگری است؛ شعبه را از بالای صفحه عوض کنید.", ChipKind.ACCENT)
                        else key(orderId) { InvoiceFormBody(nav, branch, form) }
                    }
                }
            }
        }
    }

    @Composable
    private fun InvoiceFormBody(nav: Nav, branch: Scope.Branch, form: InvoiceForm) {
        val session = LocalSession.current
        val order = form.order?.order
        var supplier by rememberSaveable { mutableStateOf(order?.supplierId) }
        var number by rememberSaveable { mutableStateOf("") }
        var locationId by rememberSaveable { mutableStateOf(order?.locationId) }
        var date by rememberSaveable { mutableStateOf(session.today) }
        var due by rememberSaveable { mutableStateOf(session.today.plusDays(30)) }
        var note by rememberSaveable { mutableStateOf("") }
        val goods = rememberRows<GoodsRow>({ listOf(it.item, it.qty, it.value, it.supplierName, it.expiresDay) },
            { GoodsRow(it[0] as GlobalId?, it[1] as Quantity?, it[2] as Money?, it[3] as String, it.getOrNull(4) as Long?) }) {
            order?.lines?.map { GoodsRow(it.itemId, it.quantity, it.value, "") } ?: listOf(GoodsRow(null, null, null, ""))
        }
        val expenseRows = rememberRows<AccountRow>({ listOf(it.account, it.amount, it.branch, it.memo) },
            { AccountRow(it[0] as String?, it[1] as Money?, it[2] as GlobalId?, it[3] as String) }) { emptyList() }
        val held = rememberRows<HeldRow>({ listOf(it.name, it.qtyNote, it.amount) },
            { HeldRow(it[0] as String, it[1] as String, it[2] as Money?) }) { emptyList() }
        // Picked files are not kept with the draft (they can be large); they are picked again if the app was closed.
        val files = remember { mutableStateListOf<AttachmentInput>() }
        var payNow by rememberSaveable { mutableStateOf(false) }
        var vat by rememberSaveable { mutableStateOf<Money?>(null) }
        var discount by rememberSaveable { mutableStateOf<Money?>(null) }
        var freight by rememberSaveable { mutableStateOf<Money?>(null) }
        var partial by rememberSaveable { mutableStateOf(false) }
        var payAccount by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var payAmount by rememberSaveable { mutableStateOf<Money?>(null) }
        val id = rememberCommandId()
        val action = rememberAction()
        val hints by load(session, supplier, branch) {
            (supplier?.let { buying.supplierItemNames(it) } ?: emptyMap()) to buying.lastPaid(branch)
        }
        // The last price paid for each item from any supplier and warehouse: a dearer supplier is noticed too.
        val (aliases, lastPrices) = hints.orNull() ?: (emptyMap<String, GlobalId>() to emptyMap<GlobalId, ir.sabou.core.LastPaid>())
        val itemsById = form.items.associateBy { it.id }
        val loc = locationId ?: form.locations.firstOrNull()?.id
        val supplierObj = form.suppliers.firstOrNull { it.id == supplier }

        if (form.order != null) SCard {
            Text("سفارش ${form.order.number?.let(Fa::digits) ?: Fa.number(form.order.order.number)} · ${form.order.supplier}", style = SabouType.bodyStrong, color = Sabou.colors.ink)
            Text("مقدار و مبلغ هر ردیف را مطابق فاکتور تأمین‌کننده اصلاح کنید.", style = SabouType.caption, color = Sabou.colors.muted)
            Row(Modifier.clickable { partial = !partial }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = partial, onCheckedChange = { partial = it })
                Text("فقط بخشی از سفارش رسید؛ سفارش برای باقی باز بماند", style = SabouType.body, color = Sabou.colors.ink)
            }
        }
        FormCard {
            PickerOrHint("تأمین‌کننده", form.suppliers.map { Choice(it.id, it.name, it.phone) }, supplier, { supplier = it },
                "تأمین‌کننده‌ای تعریف نشده است؛ مدیر یا مالک باید آن را تعریف کند.")
            if (form.suppliers.isEmpty() && session.can(Permission.SUPPLIER_MANAGE)) SecondaryButton("تعریف تأمین‌کننده", { nav.go(Route.Suppliers) })
            TextInput("شماره فاکتور تأمین‌کننده", number, { number = it })
            if (form.locations.size > 1) Picker("انبار دریافت", form.locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
            DateInput("تاریخ فاکتور", date, { date = it }, session.today)
            DateInput("سررسید", due, { due = it }, session.today)
        }
        FormCard("کالاها") {
            goods.forEachIndexed { i, l ->
                key(l) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ردیف ${Fa.number(i + 1L)}", style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                            Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { goods.remove(l) }.padding(6.dp))
                        }
                        TextInput("نام کالا در فاکتور تأمین‌کننده (اختیاری)", l.supplierName, {
                            l.supplierName = it
                            if (l.item == null) aliases[SupplierNames.normalize(it)]?.let { match -> l.item = match }
                        })
                        Picker("کالا", form.items.map { Choice(it.id, it.name, unitName(it.unit)) }, l.item, { l.item = it })
                        val item = l.item?.let { itemsById[it] }
                        ItemQuantityInput("مقدار", item, { l.qty = it }, value = l.qty)
                        MoneyInput("مبلغ کل ردیف", l.value, { l.value = it })
                        ir.sabou.app.ui.components.OptionalDateInput("تاریخ انقضا", l.expiresDay?.let(::BusinessDate), { l.expiresDay = it?.epochDay }, session.today)
                        if (l.expiresDay == null && item?.shelfLifeDays != null) {
                            Text("بدون تاریخ، انقضا ${Fa.number(item.shelfLifeDays!!.toLong())} روز پس از تاریخ فاکتور حساب می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                        val price = unitPrice(l.qty, l.value)
                        val previous = l.item?.let { lastPrices[it] }
                        val last = previous?.price
                        if (price != null && item != null) {
                            val text = "قیمت هر ${unitName(item.unit)}: ${Fa.rial(price)} ریال" +
                                (previous?.let { p -> " · خرید قبلی ${Fa.rial(p.price)} از ${p.supplier} (${changeText(p.price, price)})" } ?: "")
                            val far = last != null && (ir.sabou.kernel.Ratio.changeBp(last, price)?.let { kotlin.math.abs(it) >= 500 } ?: false)
                            Text(text, style = SabouType.caption, color = if (far) Sabou.colors.danger else Sabou.colors.muted)
                        }
                        if (item != null && supplier != null && item.approvedSupplierIds.isNotEmpty() && supplier !in item.approvedSupplierIds) {
                            Banner("این تأمین‌کننده برای «${item.name}» در فهرست مجاز نیست.", ChipKind.ACCENT)
                        }
                        Divider()
                    }
                }
            }
            SecondaryButton("افزودن ردیف کالا", { goods.add(GoodsRow(null, null, null, "")) })
        }
        FormCard("هزینه‌های بدون کالا") {
            Text("مثل قبض، تعمیر یا اجاره‌ای که در همین فاکتور آمده؛ مستقیم به حساب هزینه می‌رود. می‌توانید سهم شعبه‌ی دیگری را جدا کنید.",
                style = SabouType.caption, color = Sabou.colors.muted)
            expenseRows.forEach { r ->
                key(r) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Picker("حساب هزینه", form.expenseAccounts.map { Choice(it.code.value, it.name, it.code.value) }, r.account, { r.account = it })
                        MoneyInput("مبلغ", r.amount, { r.amount = it })
                        if (form.branches.size > 1) Picker("برای شعبه", form.branches.map { Choice(it.id.value, it.name) }, r.branch ?: branch.branchId.value, { r.branch = it })
                        TextInput("شرح", r.memo, { r.memo = it })
                        Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { expenseRows.remove(r) }.padding(6.dp))
                        Divider()
                    }
                }
            }
            SecondaryButton("افزودن ردیف هزینه", { expenseRows.add(AccountRow(null, null, null, "")) })
        }
        FormCard("کالای ناشناخته") {
            Text("ردیفی که نمی‌دانید کدام کالاست ثبت می‌شود و در «در انتظار بررسی» می‌ماند تا بعداً به کالا یا حساب هزینه وصل شود.",
                style = SabouType.caption, color = Sabou.colors.muted)
            held.forEach { r ->
                key(r) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextInput("نام در فاکتور", r.name, { r.name = it })
                        TextInput("مقدار (به همان شکل فاکتور)", r.qtyNote, { r.qtyNote = it }, placeholder = "مثلاً ۲ بسته")
                        MoneyInput("مبلغ", r.amount, { r.amount = it })
                        Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { held.remove(r) }.padding(6.dp))
                        Divider()
                    }
                }
            }
            SecondaryButton("افزودن کالای ناشناخته", { held.add(HeldRow("", "", null)) })
        }
        if (goods.isNotEmpty()) FormCard("تخفیف، کرایه و مالیات") {
            Text("تخفیف و کرایه‌ی کل فاکتور به نسبت مبلغ روی بهای کالاها سرشکن می‌شود. مالیات بر ارزش افزوده اعتبار مالیاتی است و از مالیات فروش کم می‌شود.",
                style = SabouType.caption, color = Sabou.colors.muted)
            MoneyInput("تخفیف کل فاکتور", discount, { discount = it })
            MoneyInput("کرایه حمل", freight, { freight = it })
            MoneyInput("مالیات بر ارزش افزوده", vat, { vat = it })
        }
        val total = goods.sumOf { it.value?.rial ?: 0 } + expenseRows.sumOf { it.amount?.rial ?: 0 } + held.sumOf { it.amount?.rial ?: 0 } -
            (discount?.rial ?: 0) + (freight?.rial ?: 0) + (vat?.rial ?: 0)
        FormCard("توضیح و پیوست") {
            TextInput("توضیح", note, { note = it }, singleLine = false)
            files.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.fileName, style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                    Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { files.remove(f) }.padding(6.dp))
                }
            }
            AttachmentPicker("پیوست عکس یا PDF فاکتور") { files.add(it) }
            KeyValue("جمع فاکتور (ریال)", Fa.rial(total), strong = true)
            supplierObj?.nextDelivery(session.today, minutesNow())?.let { Text(deliveryText(it, session.today), style = SabouType.caption, color = Sabou.colors.muted) }
        }
        if (session.can(Permission.PURCHASE_PAY)) FormCard {
            Row(Modifier.clickable { payNow = !payNow }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = payNow, onCheckedChange = { payNow = it })
                Text("همین الان پرداخت می‌کنم", style = SabouType.bodyStrong, color = Sabou.colors.ink)
            }
            if (payNow) {
                Picker("از حساب", accountChoices(form.accounts), payAccount, { payAccount = it })
                MoneyInput("مبلغ پرداخت", payAmount, { payAmount = it })
            }
        }
        action.error?.let { Banner(it) }
        val anyLine = goods.isNotEmpty() || expenseRows.isNotEmpty() || held.isNotEmpty()
        val ready = supplier != null && number.isNotBlank() && anyLine && (goods.isEmpty() || loc != null) &&
            goods.all { it.complete } && expenseRows.all { it.complete } && held.all { it.complete } &&
            (!payNow || (payAccount != null && payAmount != null))
        PrimaryButton("ثبت فاکتور", {
            val cmd = PostPurchaseInvoice(
                id.value, branch, supplier!!, number, loc.takeIf { goods.isNotEmpty() }, date, due,
                goods.map { InvoiceLine(it.item!!, it.qty!!, it.value!!, it.supplierName, it.expiresDay?.let(::BusinessDate)) },
                if (payNow) ImmediatePayment(payAccount!!, payAmount!!) else null,
                expenseRows.map { r ->
                    AccountLine(AccountCode.of(r.account!!), r.amount!!, r.branch?.takeIf { it != branch.branchId.value }?.let { Scope.Branch(BranchId(it)) }, r.memo)
                },
                held.map { ReviewLine(it.name, it.qtyNote, it.amount!!) },
                note, form.order?.order?.id, files.toList(),
                vat = if (goods.isEmpty()) Money.ZERO else vat ?: Money.ZERO, discount = if (goods.isEmpty()) Money.ZERO else discount ?: Money.ZERO,
                freight = if (goods.isEmpty()) Money.ZERO else freight ?: Money.ZERO, orderPartial = partial && form.order != null,
            )
            action.run({ purchasing.postInvoice(cmd) }) { invoiceId -> nav.back(); nav.go(Route.PurchaseDetail(invoiceId.resultId)) }
        }, enabled = ready, busy = action.busy)
    }

    private class PurchaseView(
        val view: ir.sabou.core.InvoiceView,
        val items: Map<GlobalId, Item>,
        val accounts: List<ir.sabou.treasury.TreasuryAccount>,
        val accountNames: Map<String, String>,
        val branchNames: Map<GlobalId, String>,
        val locations: List<ir.sabou.inventory.Location>,
    )

    @Composable
    fun PurchaseDetail(nav: Nav, invoiceId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, invoiceId) {
            val v = overview.invoice(invoiceId)
            PurchaseView(
                view = v,
                items = overview.items().associateBy { it.id },
                accounts = if (session.can(Permission.PURCHASE_PAY)) overview.paymentAccounts() else emptyList(),
                accountNames = buying.expenseAccounts().associate { it.code.value to it.name },
                branchNames = overview.branches().associate { it.id.value to it.name },
                locations = if (session.can(Permission.PURCHASE_RECORD)) overview.locations(v.invoice.scope).filter { it.isActive } else emptyList(),
            )
        }
        var payAccount by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var payAmount by rememberSaveable { mutableStateOf<Money?>(null) }
        var creditAmount by rememberSaveable { mutableStateOf<Money?>(null) }
        var heldCheque by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        val chequeFields = ir.sabou.app.ui.rememberChequeFields()
        var reason by rememberSaveable { mutableStateOf("") }
        var confirmReverse by rememberSaveable { mutableStateOf(false) }
        var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }   // holds an action: not saveable
        val id = rememberCommandId()
        val creditId = rememberCommandId()
        val action = rememberAction()
        pending?.let { (title, act) -> Confirm(title, "سند برگشتی ثبت می‌شود و سابقه حذف نمی‌شود.", "تأیید", { pending = null; act() }, { pending = null }, danger = true) }
        Column(Modifier.fillMaxSize()) {
            Header("فاکتور خرید", onBack = nav.back)
            Page {
                Loaded(data) { d ->
                    val v = d.view
                    val inv = v.invoice
                    SCard {
                        Text(v.supplier, style = SabouType.section, color = Sabou.colors.ink)
                        v.number?.let { KeyValue("شماره سند", Fa.digits(it), strong = true) }
                        KeyValue("شماره فاکتور فروشنده", Fa.digits(inv.supplierInvoiceNo))
                        KeyValue("تاریخ / سررسید", "${Fa.date(inv.date)} / ${Fa.date(inv.dueDate)}")
                        if (inv.lines.isNotEmpty()) Divider()
                        inv.lines.forEach { l ->
                            val item = d.items[l.itemId]
                            KeyValue("${item?.name ?: ""} × ${Fa.quantity(l.quantity)}" + if (l.supplierItemName.isNotBlank()) " («${l.supplierItemName}»)" else "", Fa.rial(l.value))
                        }
                        if (inv.accountLines.isNotEmpty()) Divider()
                        inv.accountLines.forEach { l ->
                            val where = l.branch?.let { " · ${d.branchNames[it.branchId.value] ?: "شعبه دیگر"}" } ?: ""
                            KeyValue("${d.accountNames[l.account.value] ?: l.account.value}$where" + if (l.memo.isNotBlank()) " · ${l.memo}" else "", Fa.rial(l.amount))
                        }
                        Divider()
                        KeyValue("جمع فاکتور", Fa.rial(inv.total))
                        KeyValue("مانده (ریال)", Fa.rial(v.outstanding), strong = true)
                        if (inv.note.isNotBlank()) Text(inv.note, style = SabouType.caption, color = Sabou.colors.muted)
                        if (inv.status == InvoiceStatus.REVERSED) Chip("برگشت‌خورده", ChipKind.DANGER)
                    }
                    v.order?.let { o ->
                        SCard {
                            NavRow(R.drawable.ic_purchase, "سفارش شماره ${Fa.number(o.number)}", "سفارش در برابر فاکتور", onClick = { nav.go(Route.OrderDetail(o.id)) })
                            o.lines.forEach { ol ->
                                val got = inv.lines.filter { it.itemId == ol.itemId }
                                val gotQty = Quantity.of(got.sumOf { it.quantity.micros })
                                val gotPrice = unitPrice(gotQty, Money.of(got.sumOf { it.value.rial }))
                                val name = d.items[ol.itemId]?.name ?: ""
                                val text = "سفارش ${Fa.quantity(ol.quantity)} × ${Fa.rial(ol.unitPrice)} · تحویل ${Fa.quantity(gotQty)}" + (gotPrice?.let { " × ${Fa.rial(it)}" } ?: "")
                                val differs = gotQty != ol.quantity || (gotPrice != null && gotPrice != ol.unitPrice.rial)
                                KeyValue(name, text, valueColor = if (differs) Sabou.colors.danger else Sabou.colors.ink)
                            }
                        }
                    }
                    if (inv.reviewLines.isNotEmpty()) SCard {
                        Text("کالای ناشناخته", style = SabouType.section, color = Sabou.colors.ink)
                        inv.reviewLines.forEachIndexed { i, l ->
                            val r = l.resolution
                            val status = when {
                                r == null -> "در انتظار بررسی"
                                r.itemId != null -> "به «${d.items[r.itemId]?.name ?: ""}» (${Fa.quantity(r.quantity ?: Quantity.ZERO)}) وصل شد"
                                else -> "به حساب «${r.account?.let { d.accountNames[it.value] ?: it.value } ?: ""}» رفت"
                            }
                            KeyValue("${l.supplierItemName}" + if (l.quantityNote.isNotBlank()) " · ${l.quantityNote}" else "", Fa.rial(l.amount))
                            Text(status, style = SabouType.caption, color = if (r == null) Sabou.colors.danger else Sabou.colors.muted)
                            if (r == null && inv.status == InvoiceStatus.POSTED && session.can(Permission.PURCHASE_RECORD)) {
                                key(i) { ResolveForm(inv.scope, inv.id, i, d.items.values.filter { it.isActive && !it.prepared }, d.locations, inv.locationId, d.accountNames) }
                            }
                            Divider()
                        }
                    }
                    if (v.payments.isNotEmpty()) SCard {
                        Text("پرداخت‌ها", style = SabouType.section, color = Sabou.colors.ink)
                        v.payments.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${Fa.date(p.date)} · ${d.accounts.firstOrNull { it.id == p.treasuryAccountId }?.name ?: ""}" + if (p.reversed) " · برگشت‌خورده" else "",
                                    style = SabouType.body, color = Sabou.colors.muted, modifier = Modifier.weight(1f))
                                Text(Fa.rial(p.amount), style = SabouType.bodyStrong, color = Sabou.colors.moneyOut)
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
                    if (v.credits.isNotEmpty()) SCard {
                        Text("اعتبار مرجوعی", style = SabouType.section, color = Sabou.colors.ink)
                        v.credits.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Fa.date(c.date) + (if (c.returnId != null) " · از مرجوعی" else " · اعمال اعتبار") + if (c.released) " · آزادشده" else "",
                                    style = SabouType.body, color = Sabou.colors.muted, modifier = Modifier.weight(1f))
                                Text(Fa.rial(c.amount), style = SabouType.bodyStrong, color = Sabou.colors.moneyIn)
                                if (!c.released && inv.status == InvoiceStatus.POSTED && session.can(Permission.PURCHASE_PAY)) {
                                    Text("آزاد کن", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable {
                                        pending = "آزاد کردن این اعتبار؟" to {
                                            action.run({ purchasing.releaseAllocation(ReleaseCreditAllocation(GlobalId.new(), inv.scope, c.id, "آزادسازی برای اصلاح")) })
                                        }
                                    }.padding(start = 10.dp))
                                }
                            }
                        }
                    }
                    SCard {
                        Text("پیوست‌ها", style = SabouType.section, color = Sabou.colors.ink)
                        if (v.attachments.isEmpty()) Text("پیوستی ندارد.", style = SabouType.caption, color = Sabou.colors.muted)
                        AttachmentList(v.attachments)
                        if (session.can(Permission.PURCHASE_RECORD)) AttachmentPicker("افزودن عکس یا PDF") { file ->
                            action.run({ purchasing.attach(AttachToInvoice(GlobalId.new(), inv.scope, inv.id, listOf(file))) })
                        }
                    }
                    action.error?.let { Banner(it) }
                    if (inv.status == InvoiceStatus.POSTED && !v.outstanding.isZero && !v.supplierCredit.isZero && session.can(Permission.PURCHASE_PAY)) {
                        FormCard("استفاده از اعتبار مرجوعی") {
                            Text("این تأمین‌کننده ${Fa.rial(v.supplierCredit)} ریال اعتبار استفاده‌نشده دارد.", style = SabouType.caption, color = Sabou.colors.muted)
                            MoneyInput("مبلغ", creditAmount, { creditAmount = it })
                            PrimaryButton("اعمال اعتبار", {
                                action.run({ purchasing.applyCredit(ApplySupplierCredit(creditId.value, inv.scope, inv.id, creditAmount!!, session.today)) }) {
                                    creditId.value = GlobalId.new(); creditAmount = null
                                }
                            }, enabled = creditAmount != null, busy = action.busy)
                        }
                    }
                    if (inv.discrepancies.isNotEmpty()) MatchCard(inv, { d.items[it]?.name ?: "" }, action)
                    if (inv.requiredApprovals > 0 || inv.approvals.isNotEmpty()) {
                        ApprovalCard(inv, action)
                    }
                    if (inv.status == InvoiceStatus.POSTED && !v.outstanding.isZero && session.can(Permission.PURCHASE_PAY)) {
                        FormCard("پرداخت به تأمین‌کننده") {
                            if (inv.needsMatchReview) Banner("پرداخت پس از بررسی مغایرت فاکتور ممکن است.", ChipKind.ACCENT)
                            else if (!inv.approved) Banner("پرداخت پس از تأیید فاکتور ممکن است.", ChipKind.ACCENT)
                            val account = d.accounts.firstOrNull { it.id == payAccount }
                            val ourCheque = account?.kind == ir.sabou.treasury.TreasuryKind.ISSUED_CHEQUES
                            val passOn = account?.kind == ir.sabou.treasury.TreasuryKind.RECEIVED_CHEQUES
                            Picker("از حساب", accountChoices(d.accounts, cheques = true), payAccount, { payAccount = it; heldCheque = null })
                            if (passOn) ir.sabou.app.ui.HeldChequePicker(account!!.scope, account.id, heldCheque) { row -> heldCheque = row.cheque.id; payAmount = row.cheque.amount }
                            else MoneyInput("مبلغ", payAmount, { payAmount = it }, hint = "مانده: ${Fa.rial(v.outstanding)} ریال")
                            if (passOn) payAmount?.let { KeyValue("مبلغ چک", Fa.rial(it) + " ریال") }
                            if (ourCheque) ir.sabou.app.ui.ChequeInputs(chequeFields, session.today, v.supplier, issued = true, banks = d.accounts.filter { it.scope == account!!.scope })
                            val cheque = if (ourCheque) ir.sabou.app.ui.chequeDetails(chequeFields, session.today, v.supplier, issued = true) else null
                            PrimaryButton("ثبت پرداخت", {
                                action.run({ purchasing.payInvoice(PaySupplierInvoice(id.value, inv.scope, inv.id, payAccount!!, payAmount!!, session.today, cheque, heldCheque.takeIf { passOn })) }) {
                                    id.value = GlobalId.new(); payAmount = null; heldCheque = null; chequeFields.clear()
                                }
                            }, enabled = inv.approved && payAccount != null && payAmount != null && (!ourCheque || cheque != null) && (!passOn || heldCheque != null), busy = action.busy)
                        }
                    }
                    if (inv.status == InvoiceStatus.POSTED && session.can(Permission.PURCHASE_REVERSE)) {
                        FormCard("مرجوعی یا برگشت فاکتور") {
                            if (inv.lines.isNotEmpty()) {
                                ReturnForm(inv.scope, inv.id, inv.lines.map { it.itemId }.distinct().mapNotNull { d.items[it] })
                                Text("اگر فاکتور تسویه شده باشد، مبلغ مرجوعی از فاکتورهای باز همین تأمین‌کننده کم می‌شود و بقیه اعتبار می‌ماند.",
                                    style = SabouType.caption, color = Sabou.colors.muted)
                                Divider()
                            }
                            TextInput("دلیل برگشت کل فاکتور", reason, { reason = it })
                            SecondaryButton("برگشت کل فاکتور", { confirmReverse = true }, enabled = reason.trim().length >= 3, danger = true)
                        }
                    }
                    if (confirmReverse) {
                        Confirm("برگشت فاکتور؟", "کالاها از انبار خارج و بدهی تأمین‌کننده خنثی می‌شود. ابتدا پرداخت‌ها، مرجوعی‌ها و اعتبارهای اعمال‌شده باید برگشت خورده باشند.", "برگشت بزن",
                            onConfirm = { confirmReverse = false; action.run({ purchasing.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), inv.scope, inv.id, session.today, reason)) }) { nav.back() } },
                            onDismiss = { confirmReverse = false }, danger = true)
                    }
                }
            }
        }
    }

    @Composable
    private fun ResolveForm(
        scope: Scope.Branch, invoiceId: GlobalId, index: Int, items: List<Item>, locations: List<ir.sabou.inventory.Location>,
        defaultLocation: GlobalId?, accountNames: Map<String, String>,
    ) {
        val session = LocalSession.current
        var mode by rememberSaveable { mutableStateOf(0) }
        var itemId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var qty by rememberSaveable { mutableStateOf<Quantity?>(null) }
        var locationId by rememberSaveable { mutableStateOf(defaultLocation) }
        var account by rememberSaveable { mutableStateOf<String?>(null) }
        val id = rememberCommandId()
        val action = rememberAction()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(listOf("کالاست", "هزینه است"), mode, { mode = it })
            if (mode == 0) {
                Picker("کالا", items.map { Choice(it.id, it.name, unitName(it.unit)) }, itemId, { itemId = it })
                ItemQuantityInput("مقدار", items.firstOrNull { it.id == itemId }, { qty = it }, value = qty)
                val loc = locationId ?: locations.firstOrNull()?.id
                if (locations.size > 1) Picker("انبار", locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                action.error?.let { Banner(it) }
                SecondaryButton("وصل به کالا", {
                    action.run({ purchasing.resolveReviewLine(ResolveReviewLine(id.value, scope, invoiceId, index, itemId!!, qty!!, loc, null, session.today)) })
                }, enabled = itemId != null && qty != null && loc != null && !action.busy)
            } else {
                Picker("حساب هزینه", accountNames.map { (code, name) -> Choice(code, name, code) }, account, { account = it })
                action.error?.let { Banner(it) }
                SecondaryButton("ثبت به‌عنوان هزینه", {
                    action.run({ purchasing.resolveReviewLine(ResolveReviewLine(id.value, scope, invoiceId, index, null, null, null, AccountCode.of(account!!), session.today)) })
                }, enabled = account != null && !action.busy)
            }
        }
    }

    @Composable
    private fun ReturnForm(scope: Scope.Branch, invoiceId: GlobalId, items: List<Item>) {
        val session = LocalSession.current
        var itemId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var qty by rememberSaveable { mutableStateOf<Quantity?>(null) }
        var reason by rememberSaveable { mutableStateOf("") }
        val id = rememberCommandId()
        val action = rememberAction()
        Picker("کالای مرجوعی", items.map { Choice(it.id, it.name, unitName(it.unit)) }, itemId, { itemId = it })
        ItemQuantityInput("مقدار مرجوعی", items.firstOrNull { it.id == itemId }, { qty = it }, value = qty)
        TextInput("دلیل مرجوعی", reason, { reason = it })
        action.error?.let { Banner(it) }
        SecondaryButton("ثبت مرجوعی (به قیمت فاکتور)", {
            action.run({ purchasing.returnGoods(ReturnToSupplier(id.value, scope, invoiceId, listOf(IssueLine(itemId!!, qty!!)), session.today, reason)) }) {
                id.value = GlobalId.new(); qty = null; reason = ""
            }
        }, enabled = itemId != null && qty != null && reason.isNotBlank() && !action.busy)
    }

    @Composable
    fun ReviewQueue(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { buying.reviewQueue() }
        Column(Modifier.fillMaxSize()) {
            Header("در انتظار بررسی", "ردیف‌های فاکتور که هنوز کالا یا حسابشان معلوم نیست", onBack = nav.back)
            Page {
                Loaded(data) { list ->
                    if (list.isEmpty()) EmptyState("همه‌ی ردیف‌ها تعیین تکلیف شده‌اند.")
                    list.forEach { r ->
                        NavRow(R.drawable.ic_alert, r.line.supplierItemName, "${r.supplier} · فاکتور ${Fa.digits(r.invoice.supplierInvoiceNo)} · ${Fa.date(r.invoice.date)}",
                            Fa.rialShort(r.line.amount.rial), tint = Sabou.colors.danger, onClick = { nav.go(Route.PurchaseDetail(r.invoice.id)) })
                    }
                }
            }
        }
    }

    @Composable
    fun PriceChanges(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(session.today.plusDays(-30)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        var level by rememberSaveable { mutableStateOf(0) }
        val threshold = listOf(500L, 1_000L, 2_000L)[level]
        val data by load(session, from, to, threshold) { buying.priceChanges(from, to, threshold) }
        Column(Modifier.fillMaxSize()) {
            Header("تغییر قیمت تأمین‌کنندگان", onBack = nav.back)
            Page {
                SCard {
                    DateInput("از", from, { from = it }, session.today)
                    DateInput("تا", to, { to = it }, session.today)
                    Segmented(listOf("بیش از ۵٪", "بیش از ۱۰٪", "بیش از ۲۰٪"), level, { level = it })
                }
                Loaded(data) { list ->
                    if (list.isEmpty()) EmptyState("در این بازه تغییر قیمتی بیش از این حد نبوده است.")
                    list.forEach { c ->
                        SCard(onClick = { nav.go(Route.PurchaseDetail(c.invoiceId)) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${c.item.name} · ${c.supplier}", style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                Chip(changeText(c.previousPrice, c.price), if ((c.changeBp ?: 1) > 0) ChipKind.DANGER else ChipKind.PRIMARY)
                            }
                            Text("هر ${unitName(c.item.unit)}: ${Fa.rial(c.previousPrice)} از ${c.previousSupplier} (${Fa.date(c.previousDate)}) ← ${Fa.rial(c.price)} (${Fa.date(c.date)})",
                                style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                }
                ExportButtons("تغییر-قیمت") { listOf(ReportTables.priceChanges(buying.priceChanges(from, to, threshold), from, to)) }
            }
        }
    }

    // ------------------------------------------------------------ Approvals

    /**
     * What differs from the order, or the possible duplicate, found when the invoice was recorded (ADR-0029); someone
     * other than the recorder accepts it with a note before it can be paid. A wrong invoice is reversed or returned.
     */
    @Composable
    private fun MatchCard(inv: ir.sabou.purchasing.PurchaseInvoice, itemName: (GlobalId) -> String, action: ir.sabou.app.ui.Action) {
        val session = LocalSession.current
        var note by rememberSaveable(inv.id) { mutableStateOf("") }
        val commandId = rememberCommandId()
        SCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("مغایرت فاکتور", style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                Chip(if (inv.matchReview != null) "بررسی شد" else "بررسی نشده", if (inv.matchReview != null) ChipKind.PRIMARY else ChipKind.DANGER)
            }
            inv.discrepancies.forEach { d -> Text("• " + discrepancyText(d, itemName), style = SabouType.caption, color = Sabou.colors.ink) }
            inv.matchReview?.let { r -> Text("بررسی: ${r.name} · ${r.note}", style = SabouType.caption, color = Sabou.colors.muted) }
            val canReview = inv.status == InvoiceStatus.POSTED && inv.needsMatchReview && session.can(Permission.PURCHASE_APPROVE) &&
                (inv.recordedBy != session.actor.userId || session.actor.role == ir.sabou.platform.Role.OWNER)
            if (canReview) {
                Text("اگر فاکتور درست است (مثلاً تأمین‌کننده کمتر یا بیشتر فرستاده و پذیرفته‌اید)، با توضیح بپذیرید. اگر اشتباه است، فاکتور را برگشت بزنید یا مرجوعی ثبت کنید.",
                    style = SabouType.caption, color = Sabou.colors.muted)
                TextInput("توضیح بررسی", note, { note = it })
                PrimaryButton("بررسی کردم، فاکتور درست است", {
                    action.run({ purchasing.reviewMatch(ir.sabou.purchasing.ReviewInvoiceMatch(commandId.value, inv.scope, inv.id, note)) }) { note = ""; commandId.value = GlobalId.new() }
                }, enabled = note.trim().length >= 3 && !action.busy, busy = action.busy)
            } else if (inv.needsMatchReview) {
                Text("فرد دیگری با اجازه‌ی تأیید فاکتور باید این مغایرت را بررسی کند.", style = SabouType.caption, color = Sabou.colors.muted)
            }
        }
    }

    private fun discrepancyText(d: ir.sabou.purchasing.Discrepancy, itemName: (GlobalId) -> String): String {
        val name = d.itemId?.let(itemName).orEmpty()
        fun qty(v: Long?) = Fa.quantity(Quantity.of(v ?: 0))
        return when (d.kind) {
            ir.sabou.purchasing.Discrepancy.Kind.QUANTITY -> "$name: سفارش ${qty(d.ordered)}، فاکتور ${qty(d.invoiced)}"
            ir.sabou.purchasing.Discrepancy.Kind.PRICE -> "$name: فی سفارش ${Fa.rial(d.ordered ?: 0)}، فی فاکتور ${Fa.rial(d.invoiced ?: 0)} ریال"
            ir.sabou.purchasing.Discrepancy.Kind.NOT_ORDERED -> "$name سفارش داده نشده بود (${qty(d.invoiced)})"
            ir.sabou.purchasing.Discrepancy.Kind.NOT_DELIVERED -> "$name سفارش شده بود (${qty(d.ordered)}) ولی در فاکتور نیست"
            ir.sabou.purchasing.Discrepancy.Kind.POSSIBLE_DUPLICATE ->
                "فاکتور دیگری از همین تأمین‌کننده با همین مبلغ" + (d.invoiced?.let { " در ${Fa.date(ir.sabou.kernel.BusinessDate(it))}" } ?: "") + " ثبت شده است؛ شاید تکراری باشد"
        }
    }

    @Composable
    private fun ApprovalCard(inv: ir.sabou.purchasing.PurchaseInvoice, action: ir.sabou.app.ui.Action) {
        val session = LocalSession.current
        var reason by rememberSaveable { mutableStateOf("") }
        var confirm by rememberSaveable { mutableStateOf(false) }
        SCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("تأیید برای پرداخت", style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                Chip("${Fa.number(inv.approvals.size.toLong())} از ${Fa.number(inv.requiredApprovals.toLong())}", if (inv.approved) ChipKind.PRIMARY else ChipKind.ACCENT)
            }
            inv.approvals.forEach { a ->
                Text("تأیید: ${a.name}" + if (a.self) " (ثبت‌کننده · ${a.reason.orEmpty()})" else "", style = SabouType.caption,
                    color = if (a.self) Sabou.colors.onAccentSoft else Sabou.colors.muted)
            }
            if (inv.status == InvoiceStatus.POSTED && !inv.approved && session.can(Permission.PURCHASE_APPROVE)) {
                // Approving an invoice you recorded yourself (owner only): with a reason, within the owner's policy.
                val self = inv.recordedBy == session.actor.userId
                var why by rememberSaveable(inv.id) { mutableStateOf("") }
                if (self) TextInput("دلیل تأیید فاکتوری که خودتان ثبت کرده‌اید", why, { why = it })
                PrimaryButton("تأیید می‌کنم", {
                    action.run({ approvals.approve(ir.sabou.purchasing.ApproveInvoice(GlobalId.new(), inv.scope, inv.id, why.takeIf { self })) }) { why = "" }
                }, enabled = !self || why.trim().length >= 3, busy = action.busy)
            }
            if (inv.status == InvoiceStatus.POSTED && inv.approvals.isNotEmpty() && session.can(Permission.PURCHASE_UNAPPROVE)) {
                TextInput("دلیل لغو تأیید", reason, { reason = it })
                SecondaryButton("لغو همه‌ی تأییدها", { confirm = true }, enabled = reason.trim().length >= 3, danger = true)
            }
        }
        if (confirm) Confirm("لغو تأییدها؟", "فاکتور تا تأیید دوباره پرداخت نمی‌شود.", "لغو تأیید",
            onConfirm = { confirm = false; action.run({ approvals.unapprove(ir.sabou.purchasing.UnapproveInvoice(GlobalId.new(), inv.scope, inv.id, reason)) }) { reason = "" } },
            onDismiss = { confirm = false }, danger = true)
    }

    @Composable
    fun PendingApprovals(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { books.pendingApprovals() }
        val matches by load(session) { books.matchReviews() }
        Column(Modifier.fillMaxSize()) {
            Header("در انتظار تأیید", "فاکتورهایی که پیش از پرداخت تأیید لازم دارند", onBack = nav.back)
            Page {
                if (session.can(Permission.APPROVAL_RULES)) NavRow(R.drawable.ic_settings, "قانون‌های تأیید", "بر اساس شعبه، تأمین‌کننده، نوع و مبلغ", onClick = { nav.go(Route.ApprovalRules) })
                matches.orNull()?.takeIf { it.isNotEmpty() }?.let { list ->
                    Text("مغایرت با سفارش یا شاید تکراری", style = SabouType.section, color = Sabou.colors.ink)
                    list.forEach { r ->
                        NavRow(R.drawable.ic_alert, r.supplier, "${Fa.digits(r.invoice.supplierInvoiceNo)} · ${Fa.number(r.invoice.discrepancies.size.toLong())} مغایرت · ${Fa.date(r.invoice.date)}",
                            Fa.rialShort(r.invoice.total.rial), tint = Sabou.colors.danger, onClick = { nav.go(Route.PurchaseDetail(r.invoice.id)) })
                    }
                    Text("تأیید پرداخت", style = SabouType.section, color = Sabou.colors.ink)
                }
                Loaded(data) { list ->
                    if (list.isEmpty()) EmptyState("فاکتوری در انتظار تأیید نیست.")
                    list.forEach { item ->
                        val inv = item.invoice.invoice
                        NavRow(R.drawable.ic_purchase, item.invoice.supplier,
                            "${Fa.digits(inv.supplierInvoiceNo)} · سررسید ${Fa.date(inv.dueDate)} · ${Fa.number(inv.approvals.size.toLong())} از ${Fa.number(inv.requiredApprovals.toLong())} تأیید",
                            Fa.rialShort(inv.total.rial), tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.PurchaseDetail(inv.id)) })
                    }
                }
            }
        }
    }

    @Composable
    fun ApprovalRules(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { Triple(books.approvalRules(), overview.branches(), overview.suppliers().map { it.supplier }) }
        val policy by load(session) { books.selfApprovalPolicy() }
        var name by rememberSaveable { mutableStateOf("") }
        var branchId by rememberSaveable { mutableStateOf<BranchId?>(null) }
        var supplierId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var category by rememberSaveable { mutableStateOf(0) }
        var minAmount by rememberSaveable { mutableStateOf<Money?>(null) }
        var steps by rememberSaveable { mutableStateOf(0) }
        val id = rememberCommandId()
        val action = rememberAction()
        val categories = listOf(null, ir.sabou.purchasing.InvoiceCategory.GOODS, ir.sabou.purchasing.InvoiceCategory.EXPENSES, ir.sabou.purchasing.InvoiceCategory.MIXED)
        fun categoryName(c: ir.sabou.purchasing.InvoiceCategory?) = when (c) {
            null -> "همه"; ir.sabou.purchasing.InvoiceCategory.GOODS -> "کالا"; ir.sabou.purchasing.InvoiceCategory.EXPENSES -> "هزینه"; ir.sabou.purchasing.InvoiceCategory.MIXED -> "ترکیبی"
        }
        Column(Modifier.fillMaxSize()) {
            Header("قانون‌های تأیید فاکتور", onBack = nav.back)
            Page {
                Text("فاکتوری که با یک قانون جور باشد، پیش از پرداخت به همان تعداد تأییدِ افراد مختلف نیاز دارد (سخت‌ترین قانون ملاک است). " +
                    "قانون فقط بر فاکتورهای بعد از تعریفش اثر دارد. ثبت‌کننده‌ی فاکتور نمی‌تواند آن را تأیید کند؛ مالک فقط طبق تنظیم پایین و با ذکر دلیل.", style = SabouType.caption, color = Sabou.colors.muted)
                Loaded(data) { (rules, branches, suppliers) ->
                    if (rules.isEmpty()) EmptyState("قانونی تعریف نشده است؛ همه‌ی فاکتورها بدون تأیید پرداخت می‌شوند.")
                    rules.forEach { r ->
                        SCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                Chip(if (r.isActive) "فعال" else "غیرفعال", if (r.isActive) ChipKind.PRIMARY else ChipKind.NEUTRAL)
                            }
                            Text(listOf(
                                r.branch?.let { b -> branches.firstOrNull { it.id == b.branchId }?.name } ?: "همه‌ی شعب",
                                r.supplierId?.let { s -> suppliers.firstOrNull { it.id == s }?.name } ?: "همه‌ی تأمین‌کنندگان",
                                "نوع: ${categoryName(r.category)}", "از ${Fa.rial(r.minAmount)} ریال", "${Fa.number(r.steps.toLong())} تأیید",
                            ).joinToString(" · "), style = SabouType.caption, color = Sabou.colors.muted)
                            if (session.can(Permission.APPROVAL_RULES)) SecondaryButton(if (r.isActive) "غیرفعال کن" else "فعال کن", {
                                action.run({ approvals.saveRule(ir.sabou.purchasing.SaveApprovalRule(GlobalId.new(), r.id, r.name, r.branch, r.supplierId, r.category, r.minAmount, r.steps, !r.isActive)) })
                            })
                        }
                    }
                    policy.orNull()?.let { SelfApprovalCard(it, action) }
                    if (session.can(Permission.APPROVAL_RULES)) FormCard("قانون جدید") {
                        TextInput("نام", name, { name = it }, placeholder = "مثلاً خریدهای بالای ۱۰ میلیون")
                        Picker("شعبه", listOf(Choice<BranchId?>(null, "همه‌ی شعب")) + branches.map { Choice<BranchId?>(it.id, it.name) }, branchId, { branchId = it })
                        Picker("تأمین‌کننده", listOf(Choice<GlobalId?>(null, "همه")) + suppliers.map { Choice<GlobalId?>(it.id, it.name) }, supplierId, { supplierId = it })
                        Text("نوع فاکتور", style = SabouType.caption, color = Sabou.colors.muted)
                        Segmented(categories.map(::categoryName), category, { category = it })
                        MoneyInput("از مبلغ", minAmount, { minAmount = it }, hint = "خالی = هر مبلغی")
                        Text("تعداد تأیید", style = SabouType.caption, color = Sabou.colors.muted)
                        Segmented(listOf("۱", "۲", "۳"), steps, { steps = it })
                        action.error?.let { Banner(it) }
                        PrimaryButton("ثبت قانون", {
                            action.run({
                                approvals.saveRule(ir.sabou.purchasing.SaveApprovalRule(id.value, null, name, branchId?.let { Scope.Branch(it) }, supplierId, categories[category],
                                    minAmount ?: Money.ZERO, steps + 1))
                            }) { name = ""; minAmount = null; id.value = GlobalId.new() }
                        }, enabled = name.trim().length >= 2, busy = action.busy)
                    }
                }
            }
        }
    }

    /** The owner's own invoices: may they be approved by the owner, up to which total (always with a reason). */
    @Composable
    private fun SelfApprovalCard(policy: ir.sabou.purchasing.SelfApprovalPolicy, action: ir.sabou.app.ui.Action) {
        val session = LocalSession.current
        var allowed by rememberSaveable(policy) { mutableStateOf(policy.allowed) }
        var cap by rememberSaveable(policy) { mutableStateOf(policy.maxAmount) }
        SCard {
            Text("تأیید فاکتورِ ثبت‌شده توسط خود مالک", style = SabouType.section, color = Sabou.colors.ink)
            Text(
                when {
                    !policy.allowed -> "مجاز نیست؛ فاکتوری که مالک ثبت کند را باید شخص دیگری تأیید کند."
                    policy.maxAmount == null -> "مجاز، با ذکر دلیل، بدون سقف مبلغ."
                    else -> "مجاز، با ذکر دلیل، تا ${Fa.rial(policy.maxAmount!!)} ریال."
                }, style = SabouType.caption, color = Sabou.colors.muted,
            )
            if (session.can(Permission.APPROVAL_RULES)) {
                Segmented(listOf("مجاز", "مجاز نیست"), if (allowed) 0 else 1, { allowed = it == 0 })
                if (allowed) MoneyInput("سقف مبلغ فاکتور", cap, { cap = it }, hint = "خالی = بدون سقف")
                SecondaryButton("ذخیره", {
                    action.run({ approvals.setSelfApproval(ir.sabou.purchasing.SetSelfApprovalPolicy(GlobalId.new(), allowed, cap.takeIf { allowed })) })
                }, enabled = !action.busy && (allowed != policy.allowed || cap != policy.maxAmount))
            }
        }
    }

    // ------------------------------------------------------------ Suppliers

    @Composable
    fun Suppliers(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.suppliers() }
        var name by rememberSaveable { mutableStateOf("") }
        var phone by rememberSaveable { mutableStateOf("") }
        val id = rememberCommandId()
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("تأمین‌کنندگان", onBack = nav.back)
            Page {
                Loaded(data) { list ->
                    if (list.isEmpty()) EmptyState("هنوز تأمین‌کننده‌ای ثبت نشده است.")
                    list.forEach { b ->
                        val s = b.supplier
                        val delivery = s.nextDelivery(session.today, minutesNow())?.let { deliveryText(it, session.today) }
                        val sub = listOfNotNull(
                            s.phone.takeIf { it.isNotBlank() }?.let(Fa::digits),
                            "بدهی ${Fa.rial(b.owed)}",
                            if (b.credit.isZero) null else "اعتبار ${Fa.rial(b.credit)}",
                            if (!s.isActive) "غیرفعال" else delivery,
                        ).joinToString(" · ")
                        NavRow(R.drawable.ic_supplier, s.name, sub, tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft,
                            onClick = { nav.go(Route.SupplierAccount(s.id)) })
                    }
                    if (list.isNotEmpty() && session.can(Permission.PURCHASE_VIEW)) ExportButtons("تامین-کنندگان") { listOf(ReportTables.suppliers(overview.suppliers())) }
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

    /** What we owe a supplier, its unapplied return credit per branch, and money it paid back. */
    @Composable
    fun SupplierAccount(nav: Nav, supplierId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, supplierId) {
            val account = buying.supplierAccount(supplierId)
            Triple(account, overview.branches().associate { it.id to it.name },
                if (session.can(Permission.PURCHASE_PAY) && session.can(Permission.TREASURY_RECEIPT)) overview.paymentAccounts() else emptyList())
        }
        var scopeId by rememberSaveable { mutableStateOf<BranchId?>(null) }
        var accountId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var amount by rememberSaveable { mutableStateOf<Money?>(null) }
        var date by rememberSaveable { mutableStateOf(session.today) }
        var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
        val chequeFields = ir.sabou.app.ui.rememberChequeFields()
        val id = rememberCommandId(supplierId)
        val action = rememberAction()
        pending?.let { (title, act) -> Confirm(title, "سند برگشتی ثبت می‌شود و سابقه حذف نمی‌شود.", "برگشت بزن", { pending = null; act() }, { pending = null }, danger = true) }
        Column(Modifier.fillMaxSize()) {
            Header("حساب تأمین‌کننده", onBack = nav.back)
            Page {
                Loaded(data) { (acc, branches, accounts) ->
                    val s = acc.supplier
                    SCard {
                        Text(s.name, style = SabouType.section, color = Sabou.colors.ink)
                        if (s.phone.isNotBlank()) KeyValue("تلفن", Fa.digits(s.phone))
                        KeyValue("بدهی ما (ریال)", Fa.rial(acc.owed), strong = true)
                        acc.credits.forEach { (b, credit) -> KeyValue("اعتبار مرجوعی · ${branches[b.branchId] ?: "شعبه"}", Fa.rial(credit), Sabou.colors.moneyIn) }
                        s.nextDelivery(session.today, minutesNow())?.let { Text(deliveryText(it, session.today), style = SabouType.caption, color = Sabou.colors.muted) }
                    }
                    if (session.can(Permission.SUPPLIER_MANAGE)) NavRow(R.drawable.ic_settings, "ویرایش مشخصات", "تلفن، روزهای تحویل، مهلت سفارش، شبا", onClick = { nav.go(Route.SupplierEdit(s.id)) })
                    if (s.sheba.isNotBlank()) SCard { KeyValue("شبا", s.sheba) }
                    if (session.can(Permission.PURCHASE_PAY)) SupplierPayments(s.id, s.name)
                    if (acc.refunds.isNotEmpty()) SCard {
                        Text("استرداد اعتبار", style = SabouType.section, color = Sabou.colors.ink)
                        acc.refunds.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${Fa.date(r.date)} · ${accounts.firstOrNull { it.id == r.treasuryAccountId }?.name ?: ""}" + if (r.reversed) " · برگشت‌خورده" else "",
                                    style = SabouType.body, color = Sabou.colors.muted, modifier = Modifier.weight(1f))
                                Text(Fa.rial(r.amount), style = SabouType.bodyStrong, color = Sabou.colors.moneyIn)
                                if (!r.reversed && session.can(Permission.PURCHASE_REVERSE)) {
                                    Text("برگشت", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable {
                                        pending = "برگشت این استرداد؟" to {
                                            action.run({ purchasing.reverseRefund(ir.sabou.purchasing.ReverseSupplierRefund(GlobalId.new(), r.scope, r.id, session.today, "اصلاح استرداد")) })
                                        }
                                    }.padding(start = 10.dp))
                                }
                            }
                        }
                    }
                    if (acc.credits.isNotEmpty() && accounts.isNotEmpty()) FormCard("دریافت وجه اعتبار از تأمین‌کننده") {
                        Text("وقتی تأمین‌کننده مبلغ کالای مرجوعی را پس می‌دهد (به‌جای کم کردن از فاکتور بعدی).", style = SabouType.caption, color = Sabou.colors.muted)
                        val branch = scopeId ?: acc.credits.first().first.branchId
                        val available = acc.credits.firstOrNull { it.first.branchId == branch }?.second ?: Money.ZERO
                        if (acc.credits.size > 1) Picker("اعتبار شعبه", acc.credits.map { Choice(it.first.branchId, branches[it.first.branchId] ?: "شعبه", "${Fa.rial(it.second)} ریال") },
                            branch, { scopeId = it })
                        val usable = accounts.filter { it.kind != ir.sabou.treasury.TreasuryKind.ISSUED_CHEQUES }
                        val account = usable.firstOrNull { it.id == accountId }
                        val byCheque = account?.kind == ir.sabou.treasury.TreasuryKind.RECEIVED_CHEQUES
                        Picker("واریز به", accountChoices(usable, cheques = true), accountId, { accountId = it })
                        MoneyInput(if (byCheque) "مبلغ چک" else "مبلغ", amount, { amount = it }, hint = "حداکثر ${Fa.rial(available)} ریال")
                        DateInput("تاریخ", date, { date = it }, session.today)
                        if (byCheque) ir.sabou.app.ui.ChequeInputs(chequeFields, session.today, s.name, issued = false, banks = emptyList())
                        val cheque = if (byCheque) ir.sabou.app.ui.chequeDetails(chequeFields, session.today, s.name, issued = false) else null
                        action.error?.let { Banner(it) }
                        PrimaryButton("ثبت دریافت", {
                            action.run({
                                purchasing.receiveRefund(ir.sabou.purchasing.ReceiveSupplierRefund(id.value, Scope.Branch(branch), s.id, accountId!!, amount!!, date, cheque))
                            }) { id.value = GlobalId.new(); amount = null; chequeFields.clear() }
                        }, enabled = accountId != null && amount != null && (!byCheque || cheque != null), busy = action.busy)
                    }
                }
            }
        }
    }

    /** Paying several invoices at once, advances before the invoice and setting them against invoices (ADR-0045). */
    @Composable
    private fun SupplierPayments(supplierId: GlobalId, name: String) {
        val session = LocalSession.current
        WithBranch { branch ->
            val data by load(session, supplierId, branch) {
                Triple(overview.paymentAccounts(), ledgerReads.supplierAdvances(supplierId).filter { it.first.scope == branch },
                    overview.invoices().filter { it.invoice.supplierId == supplierId && it.invoice.scope == branch && !it.outstanding.isZero })
            }
            var mode by rememberSaveable { mutableStateOf(0) }
            var accountId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
            var amount by rememberSaveable { mutableStateOf<Money?>(null) }
            var note by rememberSaveable { mutableStateOf("") }
            var date by rememberSaveable { mutableStateOf(session.today) }
            var applyTo by rememberSaveable { mutableStateOf<GlobalId?>(null) }
            val action = rememberAction()
            Loaded(data) { (accounts, advances, open) ->
                advances.filter { !it.first.reversed }.forEach { (a, left) ->
                    SCard {
                        KeyValue("پیش‌پرداخت ${Fa.date(a.date)}", "${Fa.rial(left)} از ${Fa.rial(a.amount)}")
                        if (a.note.isNotBlank()) Text(a.note, style = SabouType.caption, color = Sabou.colors.muted)
                        if (!left.isZero && open.isNotEmpty()) {
                            Picker("تسویه با فاکتور", open.map { Choice(it.invoice.id, "${it.invoice.supplierInvoiceNo} · ${Fa.rial(it.outstanding)}") }, applyTo, { applyTo = it })
                            SecondaryButton("تسویه از این پیش‌پرداخت", {
                                val inv = open.first { it.invoice.id == applyTo }
                                action.run({ purchasing.applyAdvance(ir.sabou.purchasing.ApplySupplierAdvance(GlobalId.new(), branch, a.id, inv.invoice.id, minOf(left, inv.outstanding), session.today)) })
                            }, enabled = applyTo != null && !action.busy)
                        }
                        if (left == a.amount && session.can(Permission.PURCHASE_REVERSE)) Text("برگشت پیش‌پرداخت", style = SabouType.label, color = Sabou.colors.danger,
                            modifier = Modifier.clickable { action.run({ purchasing.reverseAdvance(ir.sabou.purchasing.ReverseSupplierAdvance(GlobalId.new(), branch, a.id, session.today, "برگشت پیش‌پرداخت")) }) }.padding(vertical = 6.dp))
                    }
                }
                FormCard("پرداخت به $name") {
                    Picker("نوع", listOf(Choice(0, "پرداخت فاکتورها (قدیمی‌ترین سررسید اول)"), Choice(1, "پیش‌پرداخت (پیش از فاکتور)")), mode, { mode = it })
                    if (mode == 0) KeyValue("جمع قابل پرداخت", Fa.rial(Money.sum(open.filter { it.invoice.approved && !it.invoice.needsMatchReview }.map { it.outstanding })))
                    Picker("از حساب", accountChoices(accounts), accountId, { accountId = it })
                    MoneyInput("مبلغ", amount, { amount = it })
                    if (mode == 1) TextInput("توضیح", note, { note = it })
                    DateInput("تاریخ", date, { date = it }, session.today)
                    action.error?.let { Banner(it) }
                    PrimaryButton("ثبت پرداخت", {
                        action.run({
                            if (mode == 0) purchasing.paySupplier(ir.sabou.purchasing.PaySupplier(GlobalId.new(), branch, supplierId, accountId!!, amount!!, date))
                            else purchasing.payAdvance(ir.sabou.purchasing.PaySupplierAdvance(GlobalId.new(), branch, supplierId, accountId!!, amount!!, date, note))
                        }) { amount = null; note = "" }
                    }, enabled = accountId != null && amount != null && !action.busy, busy = action.busy)
                }
            }
        }
    }

    @Composable
    fun SupplierEdit(nav: Nav, supplierId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, supplierId) {
            val s = overview.suppliers().first { it.supplier.id == supplierId }.supplier
            val names = overview.items().associate { it.id to it.name }
            s to buying.supplierItemNames(supplierId).map { (alias, item) -> alias to (names[item] ?: "") }
        }
        Column(Modifier.fillMaxSize()) {
            Header("تأمین‌کننده", onBack = nav.back)
            Page {
                Loaded(data) { (s, aliases) ->
                    var name by rememberSaveable { mutableStateOf(s.name) }
                    var phone by rememberSaveable { mutableStateOf(s.phone) }
                    var active by rememberSaveable { mutableStateOf(s.isActive) }
                    val days = ir.sabou.app.ui.rememberValueList { s.deliveryDays.sorted() }
                    var cutoffMinutes by rememberSaveable { mutableStateOf(s.cutoffMinutes) }
                    var lead by rememberSaveable { mutableStateOf(Fa.number(s.leadDays.toLong())) }
                    var note by rememberSaveable { mutableStateOf(s.note) }
                    var taxId by rememberSaveable { mutableStateOf(s.taxId) }
                    var address by rememberSaveable { mutableStateOf(s.address) }
                    var sheba by rememberSaveable { mutableStateOf(s.sheba) }
                    var orderSms by rememberSaveable { mutableStateOf(s.orderSms) }
                    val id = rememberCommandId(supplierId)
                    val action = rememberAction()
                    val leadDays = Fa.latinDigits(lead).trim().toIntOrNull()
                    FormCard(s.name) {
                        TextInput("نام", name, { name = it })
                        TextInput("تلفن", phone, { phone = it }, keyboard = KeyboardType.Phone)
                        Text("روزهای تحویل", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            weekdayShort.forEachIndexed { i, label ->
                                Text(if (i in days) "✓$label" else label, style = SabouType.bodyStrong,
                                    color = if (i in days) Sabou.colors.primary else Sabou.colors.muted,
                                    modifier = Modifier.clickable { if (i in days) days.remove(i) else days.add(i) }.padding(6.dp))
                            }
                        }
                        if (days.isNotEmpty()) Text(days.sorted().joinToString("، ") { weekdayNames[it] }, style = SabouType.caption, color = Sabou.colors.muted)
                        ir.sabou.app.ui.components.TimeInput("مهلت سفارش (ساعت)", cutoffMinutes, { cutoffMinutes = it }, optional = true, placeholder = "بدون مهلت")
                        TextInput("چند روز قبل از تحویل سفارش بدهیم", lead, { lead = it }, keyboard = KeyboardType.Number,
                            error = if (leadDays == null || leadDays !in 0..14) "عددی بین ۰ تا ۱۴" else null)
                        TextInput("توضیح", note, { note = it }, singleLine = false)
                        TextInput("کد ملی / شناسه‌ی ملی / کد اقتصادی", taxId, { taxId = it }, keyboard = KeyboardType.Number)
                        TextInput("نشانی", address, { address = it }, singleLine = false)
                        TextInput("شماره شبا (برای واریز)", sheba, { sheba = it }, placeholder = "IR…",
                            error = if (sheba.isNotBlank() && !ir.sabou.kernel.Sheba.isValid(sheba)) "شبا معتبر نیست" else null)
                        Row(Modifier.clickable { orderSms = !orderSms }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = orderSms, onCheckedChange = { orderSms = it })
                            Text("سفارش‌ها خودکار به همین شماره پیامک شود", style = SabouType.body, color = Sabou.colors.ink)
                        }
                        if (orderSms && !ir.sabou.kernel.Mobile.isValid(phone)) Text("برای پیامک، شماره همراه (۰۹…) وارد کنید.", style = SabouType.caption, color = Sabou.colors.danger)
                        Row(Modifier.clickable { active = !active }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = active, onCheckedChange = { active = it })
                            Text("فعال", style = SabouType.body, color = Sabou.colors.ink)
                        }
                        action.error?.let { Banner(it) }
                        PrimaryButton("ذخیره", {
                            action.run({ purchasing.updateSupplier(UpdateSupplier(id.value, s.id, name, phone, active, days.toSet(), cutoffMinutes, leadDays!!, note, taxId, address, sheba, orderSms)) }) { nav.back() }
                        }, enabled = name.trim().length >= 2 && leadDays != null && leadDays in 0..14, busy = action.busy)
                    }
                    if (aliases.isNotEmpty()) SCard {
                        Text("نام کالاها در فاکتورهای این تأمین‌کننده", style = SabouType.section, color = Sabou.colors.ink)
                        aliases.forEach { (alias, item) -> KeyValue(alias, item) }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ Orders

    @Composable
    fun Orders(nav: Nav) {
        val session = LocalSession.current
        var filter by rememberSaveable { mutableStateOf(0) }
        val data by load(session) { buying.orders() }
        Column(Modifier.fillMaxSize()) {
            Header("سفارش‌های خرید", onBack = nav.back)
            Page {
                if (session.can(Permission.PURCHASE_ORDER)) {
                    PrimaryButton("سفارش جدید", { nav.go(Route.NewOrder()) })
                    NavRow(R.drawable.ic_count, "پیشنهاد خرید", "بر اساس سطح مطلوب، موجودی و غذاهای برنامه‌ریزی‌شده", onClick = { nav.go(Route.Suggestions) })
                }
                Segmented(listOf("باز", "همه"), filter, { filter = it })
                Loaded(data) { list ->
                    val shown = if (filter == 0) list.filter { it.order.status == OrderStatus.OPEN } else list
                    if (shown.isEmpty()) EmptyState(if (filter == 0) "سفارش بازی نیست." else "هنوز سفارشی ثبت نشده است.")
                    shown.forEach { r ->
                        val o = r.order
                        val status = when (o.status) {
                            OrderStatus.OPEN -> if (o.expectedDate < session.today) "دیر شده · قرار بود ${Fa.date(o.expectedDate)}" else "تحویل ${Fa.dayTitle(o.expectedDate)}"
                            OrderStatus.RECEIVED -> "تحویل شد"
                            OrderStatus.CANCELLED -> "لغو شد"
                        }
                        NavRow(R.drawable.ic_purchase, "${r.supplier} · ${r.number?.let(Fa::digits) ?: Fa.number(o.number)}", "$status · ${r.location}", Fa.rialShort(o.total.rial),
                            tint = if (o.status == OrderStatus.OPEN && o.expectedDate < session.today) Sabou.colors.danger else Sabou.colors.onAccentSoft,
                            tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.OrderDetail(o.id)) })
                    }
                }
            }
        }
    }

    private class OrderRowDraft(item: GlobalId?, qty: Quantity?, price: Money?) {
        var item by mutableStateOf(item)
        var qty by mutableStateOf(qty)
        var price by mutableStateOf(price)
    }

    @Composable
    fun NewOrder(nav: Nav, prefill: Route.NewOrder) {
        val session = LocalSession.current
        val context = LocalContext.current
        Column(Modifier.fillMaxSize()) {
            Header("سفارش خرید", "پیش از تحویل؛ کالا و بدهی هنگام تحویل ثبت می‌شود", onBack = nav.back)
            WithBranch { branch ->
                val data by load(session, branch) {
                    Triple(overview.suppliers().map { it.supplier }.filter { it.isActive }, overview.items().filter { it.isActive && !it.prepared }, overview.locations(branch).filter { it.isActive })
                }
                var supplier by rememberSaveable { mutableStateOf(prefill.supplierId) }
                var locationId by rememberSaveable { mutableStateOf(prefill.locationId) }
                var date by rememberSaveable { mutableStateOf(session.today) }
                var expected by rememberSaveable { mutableStateOf<BusinessDate?>(null) }
                var note by rememberSaveable { mutableStateOf("") }
                val rows = rememberRows<OrderRowDraft>({ listOf(it.item, it.qty, it.price) }, { OrderRowDraft(it[0] as GlobalId?, it[1] as Quantity?, it[2] as Money?) }) {
                    if (prefill.lines.isEmpty()) listOf(OrderRowDraft(null, null, null))
                    else prefill.lines.map { OrderRowDraft(it.itemId, Quantity.of(it.quantity), Money.of(it.unitPrice)) }
                }
                val id = rememberCommandId()
                val action = rememberAction()
                val prices by load(session, supplier, branch) { supplier?.let { buying.lastPrices(it, branch) } ?: emptyMap() }
                val last = prices.orNull().orEmpty()
                Page {
                    Loaded(data) { (sups, items, locations) ->
                        val sup = sups.firstOrNull { it.id == supplier }
                        val next = sup?.nextDelivery(session.today, minutesNow())
                        val expectedDate = expected ?: next?.date ?: session.today.plusDays(1)
                        val loc = locationId ?: locations.firstOrNull()?.id
                        val itemsById = items.associateBy { it.id }
                        FormCard {
                            PickerOrHint("تأمین‌کننده", sups.map { Choice(it.id, it.name, it.phone) }, supplier, { supplier = it; expected = null }, "تأمین‌کننده‌ای تعریف نشده است.")
                            next?.let { Text(deliveryText(it, session.today), style = SabouType.caption, color = Sabou.colors.muted) }
                            if (locations.size > 1) Picker("انبار تحویل", locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                            DateInput("تاریخ سفارش", date, { date = it }, session.today)
                            DateInput("تاریخ تحویل", expectedDate, { expected = it }, session.today)
                            if (sup != null && sup.deliveryDays.isNotEmpty() && ir.sabou.purchasing.Weekdays.index(expectedDate) !in sup.deliveryDays) {
                                Banner("این تأمین‌کننده معمولاً ${Fa.weekday(expectedDate)} تحویل نمی‌دهد.", ChipKind.ACCENT)
                            }
                        }
                        FormCard("اقلام") {
                            rows.forEachIndexed { i, r ->
                                key(r) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("ردیف ${Fa.number(i + 1L)}", style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                            if (rows.size > 1) Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { rows.remove(r) }.padding(6.dp))
                                        }
                                        Picker("کالا", items.map { Choice(it.id, it.name, unitName(it.unit)) }, r.item, { picked ->
                                            r.item = picked
                                            if (r.price == null) last[picked]?.let { r.price = Money.of(it) }
                                        })
                                        val item = r.item?.let { itemsById[it] }
                                        ItemQuantityInput("مقدار", item, { r.qty = it }, value = r.qty)
                                        MoneyInput("قیمت هر ${item?.let { unitName(it.unit) } ?: "واحد"}", r.price, { r.price = it },
                                            hint = r.item?.let { last[it] }?.let { "آخرین خرید: ${Fa.rial(it)}" })
                                        if (item != null && supplier != null && item.approvedSupplierIds.isNotEmpty() && supplier !in item.approvedSupplierIds) {
                                            Banner("این تأمین‌کننده برای «${item.name}» در فهرست مجاز نیست؛ سفارش ثبت نمی‌شود.", ChipKind.DANGER)
                                        }
                                        Divider()
                                    }
                                }
                            }
                            SecondaryButton("افزودن ردیف", { rows.add(OrderRowDraft(null, null, null)) })
                            val total = rows.sumOf { r -> if (r.qty != null && r.price != null) Ratio.mulDiv(r.price!!.rial, r.qty!!.micros, Quantity.SCALE) else 0L }
                            KeyValue("جمع تقریبی (ریال)", Fa.rial(total), strong = true)
                        }
                        FormCard { TextInput("توضیح برای تأمین‌کننده", note, { note = it }, singleLine = false) }
                        action.error?.let { Banner(it) }
                        PrimaryButton("ثبت سفارش", {
                            val cmd = CreatePurchaseOrder(id.value, branch, supplier!!, loc!!, date, expectedDate, rows.map { OrderLine(it.item!!, it.qty!!, it.price!!) }, note)
                            action.run({
                                orders.create(cmd).also { r -> ir.sabou.app.data.SmsOutbox.orderPlaced(context, this, r.resultId) }
                            }) { result -> nav.back(); nav.go(Route.OrderDetail(result.resultId)) }
                        }, enabled = supplier != null && loc != null && rows.isNotEmpty() && rows.all { it.item != null && it.qty != null && it.price != null }, busy = action.busy)
                    }
                }
            }
        }
    }

    @Composable
    fun OrderDetail(nav: Nav, orderId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, orderId) {
            Triple(buying.order(orderId), overview.items().associateBy { it.id }, overview.branches().associate { it.id.value to it.name })
        }
        var reason by rememberSaveable { mutableStateOf("") }
        var confirmCancel by rememberSaveable { mutableStateOf(false) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("سفارش خرید", onBack = nav.back)
            Page {
                Loaded(data) { (row, items, branches) ->
                    val o = row.order
                    SCard {
                        Text("${row.supplier} · ${row.number?.let(Fa::digits) ?: Fa.number(o.number)}", style = SabouType.section, color = Sabou.colors.ink)
                        KeyValue("تاریخ سفارش", Fa.date(o.date))
                        KeyValue("تحویل", "${Fa.dayTitle(o.expectedDate)} · ${row.location}")
                        Divider()
                        o.lines.forEach { l ->
                            val item = items[l.itemId]
                            KeyValue("${item?.name ?: ""} × ${Fa.quantity(l.quantity)} ${item?.let { unitName(it.unit) } ?: ""}", Fa.rial(l.value))
                        }
                        Divider()
                        KeyValue("جمع (ریال)", Fa.rial(o.total), strong = true)
                        if (o.note.isNotBlank()) Text(o.note, style = SabouType.caption, color = Sabou.colors.muted)
                        when (o.status) {
                            OrderStatus.OPEN -> Chip("باز", ChipKind.ACCENT)
                            OrderStatus.RECEIVED -> Chip("تحویل شد", ChipKind.PRIMARY)
                            OrderStatus.CANCELLED -> Chip("لغو شد: ${o.cancelReason ?: ""}", ChipKind.DANGER)
                        }
                    }
                    o.invoiceId?.let { inv -> NavRow(R.drawable.ic_purchase, "فاکتور تحویل", "مشاهده‌ی فاکتور ثبت‌شده", onClick = { nav.go(Route.PurchaseDetail(inv)) }) }
                    if (o.status == OrderStatus.OPEN && session.can(Permission.PURCHASE_RECORD)) {
                        PrimaryButton("کالا رسید: ثبت فاکتور", { nav.go(Route.PurchaseFromOrder(o.id)) })
                    }
                    OrderSmsCard(o.id, o.status == OrderStatus.OPEN && session.can(Permission.PURCHASE_ORDER))
                    Text("برای فرستادن فهرست کامل با قیمت‌ها، PDF سفارش را بسازید و در پیام‌رسان بفرستید.", style = SabouType.caption, color = Sabou.colors.muted)
                    ExportButtons("سفارش-${Fa.latinDigits(Fa.number(o.number))}") {
                        listOf(ReportTables.order(buying.order(orderId), overview.items().associateBy { it.id }, branches[o.scope.branchId.value] ?: ""))
                    }
                    if (o.status == OrderStatus.OPEN && session.can(Permission.PURCHASE_ORDER)) {
                        FormCard("لغو سفارش") {
                            TextInput("دلیل", reason, { reason = it })
                            action.error?.let { Banner(it) }
                            SecondaryButton("لغو سفارش", { confirmCancel = true }, enabled = reason.trim().length >= 3, danger = true)
                        }
                    }
                    if (confirmCancel) Confirm("لغو سفارش؟", "سفارش لغو می‌شود و در فهرست می‌ماند.", "لغو کن",
                        onConfirm = { confirmCancel = false; action.run({ orders.cancel(CancelPurchaseOrder(GlobalId.new(), o.scope, o.id, reason)) }) },
                        onDismiss = { confirmCancel = false }, danger = true)
                }
            }
        }
    }

    /** The order's SMS to its supplier (ADR-0046): what was sent, and sending (again) by hand. */
    @Composable
    private fun OrderSmsCard(orderId: GlobalId, canSend: Boolean) {
        val session = LocalSession.current
        val context = LocalContext.current
        val sms by load(session, orderId) { buying.orderSms(orderId) }
        var sent by remember { mutableStateOf(ir.sabou.app.data.SmsOutbox.log(context).filter { it.ref == ir.sabou.app.data.SmsOutbox.orderRef(orderId) }) }
        var busy by remember { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // An automatic send runs in the background right after saving; look again shortly.
        androidx.compose.runtime.LaunchedEffect(orderId) {
            repeat(6) { kotlinx.coroutines.delay(2_000); sent = ir.sabou.app.data.SmsOutbox.log(context).filter { it.ref == ir.sabou.app.data.SmsOutbox.orderRef(orderId) } }
        }
        val s = sms.orNull() ?: return
        val ready = ir.sabou.app.data.SmsOutbox.ready(context)
        if (!ready && sent.isEmpty() && !s.autoSend) return
        SCard {
            Text("پیامک سفارش", style = SabouType.section, color = Sabou.colors.ink)
            sent.firstOrNull()?.let { e ->
                val time = Fa.digits(java.text.SimpleDateFormat("HH:mm yyyy/MM/dd", java.util.Locale.US).format(java.util.Date(e.at)))
                if (e.error == null) Banner("به ${Fa.digits(e.to)} فرستاده شد · $time", ChipKind.PRIMARY, R.drawable.ic_check)
                else Banner("ارسال نشد ($time): ${e.error}", ChipKind.DANGER)
            }
            Text(s.text, style = SabouType.caption, color = Sabou.colors.muted)
            when {
                s.mobile == null -> Text("شماره همراه تأمین‌کننده ثبت نشده است.", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                !ready -> Text("ارسال پیامک در «من › امنیت و داده‌ها › پیامک» روشن نیست.", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                canSend -> SecondaryButton(if (sent.isEmpty()) "ارسال پیامک به ${Fa.digits(s.mobile!!)}" else "ارسال دوباره", {
                    busy = true
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ir.sabou.app.data.SmsOutbox.send(context, listOf(s.mobile!!), s.text, ir.sabou.app.data.SmsOutbox.orderRef(orderId))
                        }
                        busy = false
                        sent = ir.sabou.app.data.SmsOutbox.log(context).filter { it.ref == ir.sabou.app.data.SmsOutbox.orderRef(orderId) }
                    }
                }, enabled = !busy)
            }
        }
    }

    // ------------------------------------------------------------ Suggested purchases

    private class PlanRow(menu: GlobalId?, portions: Quantity?) {
        var menu by mutableStateOf(menu)
        var portions by mutableStateOf(portions)
    }

    @Composable
    fun Suggestions(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("پیشنهاد خرید", "سطح مطلوب + غذاهای برنامه‌ریزی‌شده − موجودی − سفارش‌های باز", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val setup by load(session, branch) { overview.locations(branch).filter { it.isActive } to overview.menu().filter { it.item.isActive } }
                var locationId by rememberSaveable(branch) { mutableStateOf<GlobalId?>(null) }
                var fromForecast by rememberSaveable { mutableStateOf(true) }
                val plan = rememberRows<PlanRow>({ listOf(it.menu, it.portions) }, { PlanRow(it[0] as GlobalId?, it[1] as Quantity?) }) { emptyList() }
                Page {
                    Loaded(setup) { (locations, menu) ->
                        val loc = locationId ?: locations.firstOrNull()?.id
                        // ADR-0054: what runs out before its delivery, from the sales forecast.
                        val runOuts by load(session, branch) { buying.runOuts(branch, session.today, minutesNow()) }
                        Loaded(runOuts) { list ->
                            if (list.isNotEmpty()) SCard {
                                Text("پیش از تحویل بعدی تمام می‌شود", style = SabouType.section, color = Sabou.colors.danger)
                                list.forEach { r ->
                                    Text("«${r.item.name}» تا ${Fa.dayTitle(r.runsOutOn)} · نیاز ${Fa.quantity(r.need)} · موجودی ${Fa.quantity(r.onHand)}" +
                                        (if (!r.onOrder.isZero) " · در راه ${Fa.quantity(r.onOrder)}" else "") + (r.delivery?.let { " · تحویل ${Fa.dayTitle(it)}" } ?: ""),
                                        style = SabouType.caption, color = Sabou.colors.ink)
                                }
                            }
                        }
                        SCard {
                            if (locations.size > 1) Picker("انبار", locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                            ir.sabou.app.ui.components.Segmented(listOf("از پیش‌بینی فروش", "غذاهای دستی"), if (fromForecast) 0 else 1, { fromForecast = it == 0 })
                            if (fromForecast) Text("نیاز تا تحویل بعدی + بیشترِ سطح مطلوب و نیاز تا تحویل پس از آن، از پیش‌بینی هر غذا (میانگین همان روز هفته در ۴ هفته‌ی اخیر).",
                                style = SabouType.caption, color = Sabou.colors.muted)
                            else Text("غذاهایی که تا تحویل بعدی می‌فروشید (اختیاری):", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            if (!fromForecast) plan.forEach { r ->
                                key(r) {
                                    Picker("غذا", menu.map { Choice(it.item.id, it.item.name) }, r.menu, { r.menu = it })
                                    QuantityInput("تعداد پرس", "پرس", { r.portions = it }, value = r.portions)
                                    Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { plan.remove(r) }.padding(6.dp))
                                }
                            }
                            if (!fromForecast) SecondaryButton("افزودن غذا", { plan.add(PlanRow(null, null)) })
                        }
                        val lines = plan.mapNotNull { r -> if (r.menu != null && r.portions != null) PlanLine(r.menu!!, r.portions!!) else null }
                        if (loc == null) EmptyState("انباری برای این شعبه تعریف نشده است.")
                        else {
                            val result by load(session, loc, lines, fromForecast) {
                                if (fromForecast) buying.forecastSuggestions(loc, session.today, minutesNow()) else buying.suggestions(loc, lines, session.today, minutesNow())
                            }
                            Loaded(result) { suggested ->
                                val groups = suggested.groups
                                // What could not be broken down is said, never silently left out of the order.
                                suggested.problems.forEach { pr ->
                                    Banner("«${pr.name}»: ${pr.detail}", if (pr.kind == ir.sabou.core.RecipeProblem.Kind.CYCLE) ChipKind.DANGER else ChipKind.ACCENT)
                                }
                                if (groups.isEmpty()) EmptyState("فعلاً خریدی لازم نیست. برای کالاها «سطح مطلوب» تعریف کنید یا غذاهای برنامه را وارد کنید.")
                                groups.forEach { g ->
                                    SCard {
                                        Text(g.supplier?.name ?: "بدون تأمین‌کننده‌ی ترجیحی", style = SabouType.section, color = Sabou.colors.ink)
                                        g.delivery?.let { Text(deliveryText(it, session.today), style = SabouType.caption,
                                            color = if (it.orderBy == session.today) Sabou.colors.danger else Sabou.colors.muted) }
                                        g.lines.forEach { s ->
                                            val unit = unitName(s.item.unit)
                                            KeyValue("${s.item.name}: ${Fa.quantity(s.suggested)} $unit", s.value?.let { Fa.rial(it) } ?: "—")
                                            Text("موجودی ${Fa.quantity(s.onHand)} · در راه ${Fa.quantity(s.onOrder)} · مطلوب ${Fa.quantity(s.par)}" + (s.deliveryDay?.let { " (تحویل ${Fa.weekday(it)})" } ?: "") +
                                                (if (!s.planned.isZero) " · ${if (s.forecastCover != null) "نیاز تا تحویل" else "برنامه"} ${Fa.quantity(s.planned)}" else "") +
                                                (s.forecastCover?.takeIf { !it.isZero }?.let { " · نیاز هفته‌ی بعد از تحویل ${Fa.quantity(it)}" } ?: ""), style = SabouType.caption, color = Sabou.colors.muted)
                                        }
                                        Divider()
                                        KeyValue("جمع تقریبی (ریال)", Fa.rial(g.total), strong = true)
                                        if (g.supplier != null && session.can(Permission.PURCHASE_ORDER)) {
                                            SecondaryButton("ساخت سفارش", {
                                                nav.go(Route.NewOrder(g.supplier!!.id, loc, g.lines.map { OrderDraftLine(it.item.id, it.suggested.micros, it.unitPrice ?: 0L) }))
                                            })
                                        }
                                    }
                                }
                                if (groups.isNotEmpty()) ExportButtons("پیشنهاد-خرید") {
                                    listOf(ReportTables.suggestions((if (fromForecast) buying.forecastSuggestions(loc, session.today, minutesNow()) else buying.suggestions(loc, lines, session.today, minutesNow())).groups, locations.firstOrNull { it.id == loc }?.name ?: "", session.today))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
