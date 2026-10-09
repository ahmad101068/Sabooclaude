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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
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
import ir.sabou.core.ReportTables
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
import ir.sabou.payroll.EndEmployment
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.payroll.RemitLiability
import ir.sabou.payroll.ReversePayroll
import ir.sabou.payroll.RunStatus
import ir.sabou.platform.Permission

object OperationsScreens {

    // ------------------------------------------------------------ Hub (design: Operations)

    /** Who sees which group: the hub only lists what the role may open (AUD-012). */
    private val inventoryPerms = listOf(Permission.INVENTORY_VIEW, Permission.INVENTORY_COUNT, Permission.INVENTORY_WASTE, Permission.INVENTORY_TRANSFER, Permission.RECIPE_MANAGE, Permission.INVENTORY_PRODUCE)
    private val purchasePerms = listOf(Permission.PURCHASE_VIEW, Permission.SUPPLIER_MANAGE, Permission.PURCHASE_ORDER)
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
                if (session.can(Permission.RECIPE_MANAGE)) item { NavRow(R.drawable.ic_recipe, "رسپی اقلام آماده", "سس، خمیر و هر چه در آشپزخانه ساخته می‌شود", onClick = { nav.go(Route.PrepRecipes) }) }
                if (session.can(Permission.INVENTORY_PRODUCE)) item { NavRow(R.drawable.ic_recipe, "تولید اقلام آماده", "مواد از انبار کم و قلم آماده اضافه می‌شود", onClick = { nav.go(Route.Production) }) }
                if (any(purchasePerms)) item { SectionTitle("خرید") }
                if (session.can(Permission.PURCHASE_VIEW)) item { NavRow(R.drawable.ic_purchase, "خرید و دریافت کالا", payable?.let { "بدهی ${Fa.tomanShort(it.rial)}" }, tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.Purchases) }) }
                if (session.can(Permission.PURCHASE_ORDER) || session.can(Permission.PURCHASE_VIEW)) item { NavRow(R.drawable.ic_purchase, "سفارش خرید", "پیشنهاد خرید، سفارش و تحویل", tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.Orders) }) }
                if (any(purchasePerms)) item { NavRow(R.drawable.ic_supplier, "تأمین‌کنندگان", "فهرست و مانده حساب", tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.Suppliers) }) }
                if (any(personnelPerms)) item { SectionTitle("پرسنل") }
                if (session.can(Permission.PERSONNEL_VIEW) || session.can(Permission.PERSONNEL_MANAGE)) item { NavRow(R.drawable.ic_person, "کارکنان", "ثبت و مشخصات", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Personnel) }) }
                if (session.can(Permission.ATTENDANCE_RECORD) || session.can(Permission.PERSONNEL_VIEW)) item { NavRow(R.drawable.ic_clock, "حضور و غیاب", "ثبت روزانه کارکرد", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Attendance) }) }
                if (listOf(Permission.PAYROLL_CALCULATE, Permission.PAYROLL_APPROVE, Permission.PAYROLL_PAY).any { session.can(it) }) item { NavRow(R.drawable.ic_payroll, "حقوق", "محاسبه، تأیید و پرداخت", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Payroll) }) }
                if (FinanceScreens.reportPerms.any { session.can(it) }) item { SectionTitle("گزارش‌ها") }
                if (FinanceScreens.reportPerms.any { session.can(it) }) item { NavRow(R.drawable.ic_count, "گزارش‌ها", "مصرف واقعی و تئوریک، پایان روز، کارکرد و …", onClick = { nav.go(Route.Reports) }) }
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
                var locationId by rememberSaveable(branch) { mutableStateOf<GlobalId?>(null) }
                var opening by rememberSaveable { mutableStateOf(false) }
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
                            if (loc != null) ExportButtons("موجودی-انبار") {
                                val place = d.locations.firstOrNull { it.id == loc }?.name ?: "انبار"
                                val items = overview.items().associateBy { it.id }
                                listOf(ReportTables.stock(place, overview.stock(loc).mapNotNull { b -> items[b.itemId]?.let { it to b } }, session.today))
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
        var itemId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var qty by rememberSaveable { mutableStateOf<Quantity?>(null) }
        var value by rememberSaveable { mutableStateOf<Money?>(null) }
        val action = rememberAction()
        val id = rememberSaveable { GlobalId.new() }
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
        Choice(WasteReason.COMPLIMENTARY, "پذیرایی مهمان", "جدا از ضایعات"), Choice(WasteReason.STAFF_MEAL, "غذای پرسنل", "جدا از ضایعات"),
        Choice(WasteReason.DONATION, "اهدایی", "جدا از ضایعات"),
    )

    @Composable
    fun Waste(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("ثبت ضایعات", onBack = nav.back)
            WithBranch { branch ->
                val inv by invData(branch)
                var locationId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var itemId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var qty by rememberSaveable { mutableStateOf<Quantity?>(null) }
                var reason by rememberSaveable { mutableStateOf(WasteReason.SPOILAGE) }
                var note by rememberSaveable { mutableStateOf("") }
                var date by rememberSaveable { mutableStateOf(session.today) }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
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
                var locationId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
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
        val counted = ir.sabou.app.ui.rememberValueMap<GlobalId, Quantity?>()
        val id = rememberSaveable { GlobalId.new() }
        var confirm by remember { mutableStateOf(false) }
        val action = rememberAction()
        Loaded(balances) { list ->
            val book = list.associateBy({ it.itemId }, { it.quantity })
            FormCard("مقدار شمارش‌شده") {
                Text("فقط کالاهایی را که شمردید وارد کنید؛ اختلاف به حساب مغایرت انبار ثبت می‌شود. ترتیب فهرست به محل نگهداری است.", style = SabouType.caption, color = Sabou.colors.muted)
                var lastShelf: String? = null
                items.sortedWith(compareBy({ it.shelf.isBlank() }, { it.shelf }, { it.name })).forEach { item ->
                    if (item.shelf != lastShelf) {
                        lastShelf = item.shelf
                        Text(item.shelf.ifBlank { "بدون محل" }, style = SabouType.bodyStrong, color = Sabou.colors.primary)
                    }
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
                var source by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var target by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var itemId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var qty by rememberSaveable { mutableStateOf<Quantity?>(null) }
                val lines = ir.sabou.app.ui.rememberRows<IssueLine>({ listOf(it.itemId, it.quantity) }, { IssueLine(it[0] as GlobalId, it[1] as Quantity) }) { emptyList() }
                var note by rememberSaveable { mutableStateOf("") }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
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

    // ------------------------------------------------------------ Personnel and payroll

    @Composable
    fun Personnel(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("کارکنان", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { overview.employees(branch) }
                var name by rememberSaveable { mutableStateOf("") }
                var nationalId by rememberSaveable { mutableStateOf("") }
                var salary by rememberSaveable { mutableStateOf<Money?>(null) }
                var midMonth by rememberSaveable { mutableStateOf(false) }
                var start by rememberSaveable { mutableStateOf(session.today) }
                var ending by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var lastDay by rememberSaveable { mutableStateOf(session.today) }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val endId = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { list ->
                        if (list.isEmpty()) EmptyState("کارمندی برای این شعبه ثبت نشده است.")
                        list.forEach { e ->
                            SCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.name, style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                    if (e.endDate != null) Chip("پایان همکاری ${Fa.date(e.endDate!!)}", ChipKind.NEUTRAL)
                                }
                                KeyValue("حقوق ماهانه", Fa.toman(e.monthlySalary))
                                Text("کد ملی ${Fa.digits(e.nationalId)}" + (e.startDate?.let { " · شروع کار ${Fa.date(it)}" } ?: ""),
                                    style = SabouType.caption, color = Sabou.colors.muted)
                                if (e.endDate == null && e.isActive && session.can(Permission.PERSONNEL_MANAGE)) {
                                    if (ending == e.id) {
                                        DateInput("آخرین روز کار", lastDay, { lastDay = it }, session.today)
                                        Text("حقوق ماه آخر به نسبت روزهای کار تا این تاریخ حساب می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                                        action.error?.let { Banner(it) }
                                        PrimaryButton("ثبت پایان همکاری", {
                                            action.run({ payroll.endEmployment(EndEmployment(endId.value, branch, e.id, lastDay)) }) {
                                                ending = null; endId.value = GlobalId.new()
                                            }
                                        }, busy = action.busy)
                                        SecondaryButton("انصراف", { ending = null })
                                    } else {
                                        SecondaryButton("پایان همکاری", { ending = e.id; lastDay = session.today; endId.value = GlobalId.new() })
                                    }
                                }
                            }
                        }
                    }
                    if (session.can(Permission.PERSONNEL_MANAGE)) {
                        FormCard("کارمند جدید") {
                            TextInput("نام و نام خانوادگی", name, { name = it })
                            TextInput("کد ملی", nationalId, { nationalId = it }, keyboard = KeyboardType.Number)
                            MoneyInput("حقوق پایه ماهانه", salary, { salary = it })
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { midMonth = !midMonth }) {
                                androidx.compose.material3.Checkbox(checked = midMonth, onCheckedChange = { midMonth = it })
                                Text("از وسط ماه شروع به کار کرده (حقوق ماه اول به نسبت روزها)", style = SabouType.body, color = Sabou.colors.ink)
                            }
                            if (midMonth) DateInput("تاریخ شروع کار", start, { start = it }, session.today)
                            if (ending == null) action.error?.let { Banner(it) }
                            PrimaryButton("ثبت", {
                                action.run({ payroll.registerEmployee(RegisterEmployee(id.value, branch, name, nationalId, salary!!, startDate = start.takeIf { midMonth })) }) {
                                    name = ""; nationalId = ""; salary = null; midMonth = false; id.value = GlobalId.new()
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
        var date by rememberSaveable { mutableStateOf(session.today) }
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
        var worked by rememberSaveable { mutableStateOf(hours(record?.workedMinutes)) }
        var overtime by rememberSaveable { mutableStateOf(hours(record?.overtimeMinutes)) }
        var absent by rememberSaveable { mutableStateOf(hours(record?.absentMinutes)) }
        val action = rememberAction()
        FormCard(name) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("کارکرد (ساعت)", worked, { worked = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                TextInput("اضافه‌کار", overtime, { overtime = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                TextInput("غیبت", absent, { absent = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            }
            action.error?.let { Banner(it) }
            val w = minutes(worked); val o = minutes(overtime); val a = minutes(absent)
            if (LocalSession.current.can(Permission.ATTENDANCE_RECORD)) SecondaryButton(if (record == null) "ثبت" else "اصلاح", {
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
        var period by rememberSaveable { mutableStateOf(months.first().value) }
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
                var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }   // holds an action: not saveable
                pending?.let { (title, act) -> Confirm(title, "سند حقوق این دوره برگشت می‌خورد؛ می‌توانید دوباره محاسبه و تأیید کنید.", "برگشت بزن", act, { pending = null }, danger = true) }
                val calcId = rememberSaveable { mutableStateOf(GlobalId.new()) }
                var payAccount by rememberSaveable { mutableStateOf<GlobalId?>(null) }
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
                                    p.payableDays?.let { days ->
                                        Text("ماه ناقص: ${Fa.number(days.toLong())} روز کار · حقوق این ماه ${Fa.toman(p.baseSalary)}", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                                    }
                                    Text("ناخالص ${Fa.toman(p.gross)} · بیمه ${Fa.toman(p.employeeInsurance)} · مالیات ${Fa.toman(p.incomeTax)}",
                                        style = SabouType.caption, color = Sabou.colors.muted)
                                    val unpaid = d.unpaid[run.id]?.get(p.employeeId)
                                    if (unpaid != null && !unpaid.isZero && session.can(Permission.PAYROLL_PAY)) {
                                        SecondaryButton("پرداخت ${Fa.toman(unpaid)} تومان", {
                                            action.run({ payroll.pay(PaySalary(GlobalId.new(), branch, run.id, p.employeeId, payAccount!!, unpaid, session.today)) })
                                        }, enabled = payAccount != null && !action.busy)
                                    }
                                }
                                key(run.id) {
                                    Text("لیست و فیش‌ها (هر فیش در یک صفحه‌ی PDF)", style = SabouType.caption, color = Sabou.colors.muted)
                                    ExportButtons("حقوق-${Fa.latinDigits(Fa.date(run.from)).replace('/', '-')}") {
                                        val place = overview.branches().firstOrNull { it.id == branch.branchId }?.name ?: "شعبه"
                                        listOf(ReportTables.payrollRun(run, d.names, place)) +
                                            run.payslips.map { ReportTables.payslip(run, it, d.names[it.employeeId].orEmpty(), place) }
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
