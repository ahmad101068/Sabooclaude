package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import ir.sabou.app.ui.components.BirthDateInput
import ir.sabou.app.ui.components.TimeInput
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
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.Item
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

/** «هر روز» / «هر هفته، شنبه» / «هر ماه، روز ۵» */
internal fun countFrequencyText(f: ir.sabou.inventory.CountFrequency): String = when (f.kind) {
    ir.sabou.inventory.CountFrequency.Kind.DAILY -> "هر روز"
    ir.sabou.inventory.CountFrequency.Kind.WEEKLY -> "هر هفته، ${WEEKDAYS[f.day]}"
    ir.sabou.inventory.CountFrequency.Kind.MONTHLY -> "هر ماه، روز ${Fa.number(f.day.toLong())}"
}

internal val WEEKDAYS = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه")

/** Goods expiring within this many days are listed on the stock screen. */
private const val EXPIRY_CARD_DAYS = 3L

object OperationsScreens {

    // ------------------------------------------------------------ Hub (design: Operations)

    /** Who sees which group: the hub only lists what the role may open (AUD-012). */
    private val inventoryPerms = listOf(Permission.INVENTORY_VIEW, Permission.INVENTORY_COUNT, Permission.INVENTORY_WASTE, Permission.INVENTORY_TRANSFER, Permission.RECIPE_MANAGE, Permission.INVENTORY_PRODUCE)
    private val purchasePerms = listOf(Permission.PURCHASE_VIEW, Permission.SUPPLIER_MANAGE, Permission.PURCHASE_ORDER)
    private val personnelPerms = listOf(Permission.PERSONNEL_VIEW, Permission.PERSONNEL_MANAGE, Permission.ATTENDANCE_RECORD, Permission.PAYROLL_CALCULATE, Permission.PAYROLL_APPROVE, Permission.PAYROLL_PAY)
    // The time clock and shifts count too: a cashier who only clocks staff in still needs this tab.
    val allPerms = inventoryPerms + purchasePerms + personnelPerms + listOf(Permission.SHIFT_MANAGE, Permission.TIME_CLOCK)

    /**
     * Four doors, then the rooms behind each (owner's request, 2026-10): انبار، خرید، پرسنل، گزارش‌ها. Each door shows
     * one line of what needs attention there; a role sees only the doors it can use.
     */
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
        val staffPerms = personnelPerms + listOf(Permission.SHIFT_MANAGE, Permission.TIME_CLOCK)
        val reports = FinanceScreens.reportPerms.any { session.can(it) }
        val groups = listOf(any(inventoryPerms), any(purchasePerms), any(staffPerms), reports)
        // A role with one group only (a storekeeper's stock, a cashier's time clock) skips the doors and opens its room.
        if (groups.count { it } == 1) {
            when (groups.indexOf(true)) {
                0 -> InventoryHub(nav, root = true)
                1 -> PurchaseHub(nav, root = true)
                2 -> StaffHub(nav, root = true)
                else -> ReportsScreens.Hub(nav)
            }
            return
        }
        Column(Modifier.fillMaxSize()) {
            Header("عملیات") { BranchSwitcher() }
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (any(inventoryPerms)) item {
                    NavRow(R.drawable.ic_operations, "انبار و آشپزخانه",
                        low?.let { if (it > 0) "${Fa.number(it.toLong())} کالا زیر حداقل" else "موجودی، انبارگردانی، ضایعات، رسپی و آماده‌سازی" }
                            ?: "موجودی، انبارگردانی، ضایعات، رسپی و آماده‌سازی",
                        onClick = { nav.go(Route.InventoryHub) })
                }
                if (any(purchasePerms)) item {
                    NavRow(R.drawable.ic_purchase, "خرید", payable?.let { "بدهی ${Fa.rialShort(it.rial)} · فاکتور، سفارش و تأمین‌کنندگان" } ?: "فاکتور، سفارش و تأمین‌کنندگان",
                        tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.PurchaseHub) })
                }
                if (any(staffPerms)) item {
                    NavRow(R.drawable.ic_person, "پرسنل", "کارکنان، شیفت، ساعت‌زنی، کارکرد و حقوق",
                        tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.StaffHub) })
                }
                if (reports) item {
                    NavRow(R.drawable.ic_count, "گزارش‌ها", "سود و زیان، پایان روز، مصرف، منو، پیش‌بینی و کارکرد", onClick = { nav.go(Route.Reports) })
                }
            }
        }
    }

    @Composable
    private fun SubHub(nav: Nav, title: String, root: Boolean, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Header(title, onBack = if (root) null else nav.back) { BranchSwitcher() }
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }

    @Composable
    fun InventoryHub(nav: Nav, root: Boolean = false) {
        val session = LocalSession.current
        val low by load(session, session.branch) { if (session.can(Permission.INVENTORY_VIEW)) overview.lowStock().size else null }
        val lowCount = low.orNull()
        val kitchen = session.can(Permission.RECIPE_MANAGE) || session.can(Permission.INVENTORY_PRODUCE)
        SubHub(nav, "انبار و آشپزخانه", root) {
            item { SectionTitle("انبار") }
            if (session.can(Permission.INVENTORY_VIEW)) item { NavRow(R.drawable.ic_operations, "موجودی", lowCount?.let { if (it > 0) "${Fa.number(it.toLong())} کالا زیر حداقل" else "همه کالاها بالای حداقل" }, onClick = { nav.go(Route.Stock) }) }
            if (session.can(Permission.INVENTORY_COUNT) || session.can(Permission.INVENTORY_ADJUST)) item { NavRow(R.drawable.ic_count, "انبارگردانی", "شمارش، بررسی و تأیید اختلاف با دلیل", onClick = { nav.go(Route.Count) }) }
            if (session.can(Permission.INVENTORY_TRANSFER)) item { NavRow(R.drawable.ic_transfer, "انتقال", "بین انبارها و شعب", onClick = { nav.go(Route.StockTransfer) }) }
            if (session.can(Permission.INVENTORY_VIEW)) item { NavRow(R.drawable.ic_transfer, "درخواست کالا", "آشپزخانه یا شعبه درخواست می‌دهد، انبار ارسال می‌کند", onClick = { nav.go(Route.StockRequests) }) }
            if (session.can(Permission.INVENTORY_TRANSFER)) item { NavRow(R.drawable.ic_transfer, "انتقال بین شعب", "کالای در راه؛ تحویل گرفتن با مقدار رسیده", onClick = { nav.go(Route.StockTransits) }) }
            if (session.can(Permission.INVENTORY_COUNT)) item { NavRow(R.drawable.ic_calendar, "ثبت‌های روزانه", "دمای یخچال و فریزر، خرابی دستگاه، وزن نمونه‌ی پرس", onClick = { nav.go(Route.FieldRecords) }) }
            if (session.can(Permission.INVENTORY_WASTE)) item { NavRow(R.drawable.ic_waste, "ضایعات", "ثبت با دلیل؛ چند کالا یا یک غذا", onClick = { nav.go(Route.Waste) }) }
            if (session.can(Permission.INVENTORY_VIEW)) item { NavRow(R.drawable.ic_count, "اسناد انبار", "ضایعات، انتقال، تولید و موجودی اول دوره؛ برگشت سند اشتباه", onClick = { nav.go(Route.StockDocuments) }) }
            if (kitchen) item { SectionTitle("آشپزخانه") }
            if (session.can(Permission.INVENTORY_PRODUCE)) item { NavRow(R.drawable.ic_calendar, "برنامه‌ی آماده‌سازی امروز", "چه چیزی، چقدر، به چه ترتیبی", onClick = { nav.go(Route.PrepPlan) }) }
            if (session.can(Permission.INVENTORY_PRODUCE)) item { NavRow(R.drawable.ic_recipe, "تولید اقلام آماده", "مواد از انبار کم و قلم آماده اضافه می‌شود", onClick = { nav.go(Route.Production) }) }
            if (session.can(Permission.RECIPE_MANAGE)) item { NavRow(R.drawable.ic_recipe, "رسپی و بهای تمام‌شده", "نسخه‌های رسپی آیتم‌های منو", onClick = { nav.go(Route.Recipes) }) }
            if (session.can(Permission.RECIPE_MANAGE)) item { NavRow(R.drawable.ic_recipe, "رسپی اقلام آماده", "سس، خمیر و هر چه در آشپزخانه ساخته می‌شود", onClick = { nav.go(Route.PrepRecipes) }) }
        }
    }

    @Composable
    fun PurchaseHub(nav: Nav, root: Boolean = false) {
        val session = LocalSession.current
        val payable by load(session, session.branch) { if (session.can(Permission.PURCHASE_VIEW)) overview.payables() else null }
        SubHub(nav, "خرید", root) {
            if (session.can(Permission.PURCHASE_VIEW)) item { NavRow(R.drawable.ic_purchase, "خرید و دریافت کالا", payable.orNull()?.let { "بدهی ${Fa.rialShort(it.rial)}" } ?: "فاکتورهای خرید", tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.Purchases) }) }
            if (session.can(Permission.PURCHASE_ORDER) || session.can(Permission.PURCHASE_VIEW)) item { NavRow(R.drawable.ic_purchase, "سفارش خرید", "پیشنهاد خرید، سفارش و تحویل", tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.Orders) }) }
            item { NavRow(R.drawable.ic_supplier, "تأمین‌کنندگان", "فهرست و مانده حساب", tint = Sabou.colors.onAccentSoft, tile = Sabou.colors.accentSoft, onClick = { nav.go(Route.Suppliers) }) }
        }
    }

    @Composable
    fun StaffHub(nav: Nav, root: Boolean = false) {
        val session = LocalSession.current
        SubHub(nav, "پرسنل", root) {
            if (session.can(Permission.TIME_CLOCK)) item { NavRow(R.drawable.ic_clock, "ساعت‌زنی", "ورود و خروج کارکنان با رمز خودشان", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.TimeClock) }) }
            if (session.can(Permission.ATTENDANCE_RECORD)) item { NavRow(R.drawable.ic_clock, "کارکرد امروز", "برنامه در برابر ساعت‌زنی؛ تأیید برای حقوق", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.DayTime) }) }
            if (session.can(Permission.SHIFT_MANAGE)) item { NavRow(R.drawable.ic_calendar, "برنامه‌ی شیفت", "هفتگی، با هزینه‌ی نیرو در برابر بودجه و فروش", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Schedule) }) }
            if (session.can(Permission.PERSONNEL_VIEW) || session.can(Permission.PERSONNEL_MANAGE)) item { NavRow(R.drawable.ic_person, "کارکنان", "ثبت، مشخصات و مدارک", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Personnel) }) }
            if (session.can(Permission.ATTENDANCE_RECORD) || session.can(Permission.PERSONNEL_VIEW)) item { NavRow(R.drawable.ic_clock, "حضور و غیاب", "ثبت روزانه کارکرد", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Attendance) }) }
            if (listOf(Permission.PAYROLL_CALCULATE, Permission.PAYROLL_APPROVE, Permission.PAYROLL_PAY).any { session.can(it) }) item { NavRow(R.drawable.ic_payroll, "حقوق", "محاسبه، تأیید و پرداخت", tint = Sabou.colors.moneyIn, tile = Sabou.colors.moneyInSoft, onClick = { nav.go(Route.Payroll) }) }
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
                        val shortages by load(session, branch) { overview.openShortages(branch) }
                        shortages.orNull()?.takeIf { it.isNotEmpty() }?.let { open ->
                            SCard {
                                Text("کسری مصرف باز", style = SabouType.section, color = Sabou.colors.onAccentSoft)
                                Text("فروش بیش از موجودی دفتری مصرف کرده است. با ثبت خرید، انتقال یا تولید همین کالا در همین انبار خودکار تسویه می‌شود؛ انبارگردانی آن را با برآورد بهای آخرین خرید می‌بندد.",
                                    style = SabouType.caption, color = Sabou.colors.muted)
                                open.forEach { v ->
                                    KeyValue("${v.item.name} · ${v.location.name}", "${Fa.quantity(v.shortage.open)} ${unitName(v.item.unit)} · ${Fa.date(v.shortage.date)}")
                                }
                            }
                        }
                        val expiring by load(session, branch) { overview.lots(branch, session.today, EXPIRY_CARD_DAYS) }
                        expiring.orNull()?.takeIf { it.isNotEmpty() }?.let { lots ->
                            SCard {
                                Text("تاریخ انقضا", style = SabouType.section, color = Sabou.colors.ink)
                                Text("اول همین‌ها را مصرف کنید (اول منقضی، اول مصرف). تاریخ‌گذشته را با دلیل «تاریخ گذشته» ضایعات ثبت کنید.",
                                    style = SabouType.caption, color = Sabou.colors.muted)
                                lots.forEach { v ->
                                    val left = v.lot.daysLeft(session.today)
                                    val text = when { left < 0 -> "گذشته (${Fa.date(v.lot.expiresOn)})"; left == 0L -> "امروز"; left == 1L -> "فردا"; else -> "${Fa.number(left)} روز دیگر" }
                                    KeyValue("${v.item.name} · ${Fa.quantity(v.lot.remaining)} ${unitName(v.item.unit)} · ${v.location.name}", text,
                                        valueColor = if (left <= 0) Sabou.colors.danger else Sabou.colors.ink)
                                }
                                if (session.can(Permission.INVENTORY_WASTE) && lots.any { it.lot.daysLeft(session.today) < 0 }) SecondaryButton("ثبت ضایعات", { nav.go(Route.Waste) })
                            }
                        }
                        var search by rememberSaveable { mutableStateOf("") }
                        TextInput("جستجوی کالا (نام یا کد)", search, { search = it })
                        Loaded(balances) { list ->
                            val byItem = list.associateBy { it.itemId }
                            val q0 = Fa.latinDigits(search.trim())
                            val shown = d.items.filter { q0.isBlank() || it.name.contains(q0) || it.code.contains(q0) || it.category.contains(q0) }
                                .sortedWith(compareBy({ it.category.ifBlank { "\uFFFF" } }, { it.name }))
                            var lastCategory: String? = null
                            SCard {
                                shown.forEach { item ->
                                    if (item.category != lastCategory && shown.any { it.category.isNotBlank() }) {
                                        lastCategory = item.category
                                        Text(item.category.ifBlank { "بدون گروه" }, style = SabouType.bodyStrong, color = Sabou.colors.primary)
                                    }
                                    val b = byItem[item.id]
                                    val q = b?.quantity ?: Quantity.ZERO
                                    val low = loc != null && item.watchedAt(loc, b != null) && q < item.minimumAt(loc)
                                    Row(Modifier.clickable(enabled = loc != null) { nav.go(Route.StockCard(item.id, loc!!)) }, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                            val avg = if (b != null && q.micros > 0 && b.value.rial >= 0) " · میانگین ${Fa.rial(ir.sabou.kernel.Ratio.mulDiv(b.value.rial, Quantity.SCALE, q.micros))} ریال" else ""
                                            Text("ارزش ${Fa.rial(b?.value?.rial ?: 0)} ریال$avg", style = SabouType.caption, color = Sabou.colors.muted)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("${Fa.quantity(q)} ${unitName(item.unit)}", style = SabouType.bodyStrong, color = if (low) Sabou.colors.danger else Sabou.colors.ink)
                                            if (low) Chip("زیر حداقل", ChipKind.DANGER)
                                        }
                                    }
                                    Divider()
                                }
                            }
                            Text("روی هر کالا بزنید تا کاردکس آن (همه‌ی ورود و خروج‌ها با مانده) باز شود.", style = SabouType.caption, color = Sabou.colors.muted)
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
        var date by rememberSaveable { mutableStateOf(session.today) }
        var round by rememberSaveable { mutableStateOf(0) }
        val lines = ir.sabou.app.ui.rememberRows<ReceiptLine>({ listOf(it.itemId, it.quantity, it.value) }, { ReceiptLine(it[0] as GlobalId, it[1] as Quantity, it[2] as Money) }) { emptyList() }
        val action = rememberAction()
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        FormCard("موجودی اول دوره") {
            Text("کالایی که از قبل در انبار دارید با ارزش آن ثبت می‌شود (طرف مقابل: سرمایه). چند کالا را در یک سند بیاورید.", style = SabouType.caption, color = Sabou.colors.muted)
            DateInput("تاریخ (مثلاً اول سال)", date, { date = it }, session.today)
            Picker("کالا", d.items.map { Choice(it.id, it.name, unitName(it.unit)) }, itemId, { itemId = it })
            key(round) {
                ItemQuantityInput("مقدار", d.items.firstOrNull { it.id == itemId }, { qty = it })
                MoneyInput("ارزش کل این کالا", value, { value = it })
            }
            SecondaryButton("افزودن به فهرست", {
                lines.add(ReceiptLine(itemId!!, qty!!, value!!)); itemId = null; qty = null; value = null; round++
            }, enabled = itemId != null && qty != null && !qty!!.isZero && value != null && lines.none { it.itemId == itemId })
            lines.forEachIndexed { i, l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.items.firstOrNull { it.id == l.itemId }?.name.orEmpty()} · ${Fa.quantity(l.quantity)} · ${Fa.rial(l.value)}", style = SabouType.body,
                        color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                    Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { lines.removeAt(i) }.padding(6.dp))
                }
            }
            action.error?.let { Banner(it) }
            PrimaryButton("ثبت ${Fa.number(lines.size.toLong())} کالا", {
                action.run({ inventory.openingStock(RecordOpeningStock(id.value, branch, locationId!!, lines.toList(), date)) }) { done() }
            }, enabled = locationId != null && lines.isNotEmpty() && !action.busy, busy = action.busy)
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
                val dishes by load(session) { runCatching { overview.menu() }.getOrDefault(emptyList()).filter { it.item.isActive && it.latest != null }.map { it.item } }
                var locationId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var byDish by rememberSaveable { mutableStateOf(false) }
                var dishId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var portions by rememberSaveable { mutableStateOf("") }
                val lines = ir.sabou.app.ui.rememberRows<IssueLine>({ listOf(it.itemId, it.quantity) }, { IssueLine(it[0] as GlobalId, it[1] as Quantity) }) { emptyList() }
                var reason by rememberSaveable { mutableStateOf(WasteReason.SPOILAGE) }
                var note by rememberSaveable { mutableStateOf("") }
                var date by rememberSaveable { mutableStateOf(session.today) }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(inv) { d ->
                        val loc = locationId ?: d.locations.firstOrNull()?.id
                        val available = InventoryScreens.availability(loc)
                        val count = Fa.parseQuantity(portions)
                        FormCard {
                            if (d.locations.size > 1) Picker("انبار", d.locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                            Picker("چه چیزی", listOf(Choice(false, "کالاها"), Choice(true, "غذای آماده (مواد رسپی کم می‌شود)")), byDish, { byDish = it })
                            if (byDish) {
                                Picker("غذا", dishes.orNull().orEmpty().map { Choice(it.id, it.name) }, dishId, { dishId = it })
                                TextInput("تعداد پرس", portions, { portions = it }, keyboard = KeyboardType.Decimal)
                            } else InventoryScreens.ItemLines(d.items, lines) { available[it] }
                            Picker("دلیل", wasteReasons, reason, { reason = it })
                            TextInput("توضیح", note, { note = it })
                            DateInput("تاریخ", date, { date = it }, session.today)
                            action.error?.let { Banner(it) }
                            val ready = loc != null && (if (byDish) dishId != null && count != null && !count.isZero else lines.isNotEmpty()) &&
                                (reason != WasteReason.OTHER || note.isNotBlank())
                            PrimaryButton("ثبت ضایعات", {
                                action.run({
                                    inventory.waste(if (byDish) RecordWaste(id.value, branch, loc!!, null, null, reason, note, date, dishId = dishId, portions = count)
                                        else RecordWaste(id.value, branch, loc!!, null, null, reason, note, date, lines = lines.toList()))
                                }) { nav.back() }
                            }, enabled = ready && !action.busy, busy = action.busy)
                            Text("ضایعات به بهای میانگین از انبار کم و به هزینه ضایعات منظور می‌شود. سند اشتباه را از «اسناد انبار» برگشت بزنید.",
                                style = SabouType.caption, color = Sabou.colors.muted)
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
            Header("انبارگردانی", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val inv by invData(branch)
                val pending by load(session, branch) { overview.stockCounts(branch).count { it.count.status == ir.sabou.inventory.CountStatus.PENDING } }
                val due by load(session, branch) { overview.countsDue(branch, session.today) }
                var locationId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var planId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                Page {
                    NavRow(R.drawable.ic_count, "سابقه و تأیید انبارگردانی‌ها", pending.orNull()?.takeIf { it > 0 }?.let { "${Fa.number(it.toLong())} شمارش در انتظار تأیید" }
                        ?: "همه‌ی شمارش‌ها با اختلاف و دلیل", onClick = { nav.go(Route.CountHistory) })
                    if (session.can(Permission.INVENTORY_ADJUST)) NavRow(R.drawable.ic_calendar, "برنامه‌های شمارش", "روزانه برای کالاهای گران، هفتگی یا ماهانه برای بقیه",
                        onClick = { nav.go(Route.CountPlans) })
                    val plans = due.orNull().orEmpty()
                    val plan = plans.firstOrNull { it.plan.id == planId }?.plan
                    if (plans.isNotEmpty()) SCard {
                        Text("کدام شمارش؟", style = SabouType.section, color = Sabou.colors.ink)
                        Row(Modifier.fillMaxWidth().clickable { planId = null }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("شمارش آزاد (هر کالایی که شمردید)", style = if (plan == null) SabouType.bodyStrong else SabouType.body,
                                color = if (plan == null) Sabou.colors.primary else Sabou.colors.ink, modifier = Modifier.weight(1f))
                        }
                        plans.forEach { p ->
                            Row(Modifier.fillMaxWidth().clickable { planId = p.plan.id; locationId = p.plan.locationId }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.plan.name, style = if (plan?.id == p.plan.id) SabouType.bodyStrong else SabouType.body,
                                        color = if (plan?.id == p.plan.id) Sabou.colors.primary else Sabou.colors.ink)
                                    Text(countFrequencyText(p.plan.frequency) + (p.lastCount?.let { " · آخرین ${Fa.date(it)}" } ?: ""), style = SabouType.caption, color = Sabou.colors.muted)
                                }
                                Chip(if (p.due) "موعد شمارش" else "شمرده شد", if (p.due) ChipKind.ACCENT else ChipKind.PRIMARY)
                            }
                        }
                    }
                    Loaded(inv) { d ->
                        val loc = plan?.locationId ?: locationId ?: d.locations.firstOrNull()?.id
                        if (d.locations.size > 1 && plan == null) Picker("انبار", d.locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                        if (loc != null && session.can(Permission.INVENTORY_COUNT)) key(loc, plan?.id) { CountSheet(nav, branch, loc, plan) }
                    }
                }
            }
        }
    }

    @Composable
    private fun CountSheet(nav: Nav, branch: Scope.Branch, locationId: GlobalId, plan: ir.sabou.inventory.CountPlan?) {
        val session = LocalSession.current
        val sheet by load(session, locationId, plan?.id) { overview.countSheet(locationId, plan?.id) }
        var shelfFilter by rememberSaveable(locationId, plan?.id) { mutableStateOf<String?>(null) }
        val counted = ir.sabou.app.ui.rememberValueMap<GlobalId, Quantity?>()
        var note by rememberSaveable { mutableStateOf("") }
        val id = ir.sabou.app.ui.rememberCommandId(locationId)
        var confirm by rememberSaveable { mutableStateOf(false) }
        val action = rememberAction()
        Loaded(sheet) { list ->
            val blind = list.all { it.book == null }
            val shelves = list.map { it.item.shelf }.distinct()
            val done = list.count { counted[it.item.id] != null }
            FormCard(plan?.name ?: "مقدار شمارش‌شده") {
                Text(
                    (if (plan != null) "همه‌ی کالاهای این برنامه باید شمرده شوند؛ کالای نبوده را صفر بزنید. " else "فقط کالاهایی را که شمردید وارد کنید. ") +
                        "ترتیب فهرست به محل نگهداری است. " +
                        if (blind) "موجودی دفتری نمایش داده نمی‌شود؛ آنچه را می‌بینید بشمارید." else "",
                    style = SabouType.caption, color = Sabou.colors.muted,
                )
                Text("ثبت شمارش موجودی را عوض نمی‌کند؛ اختلاف‌ها پس از بررسی و تأیید مدیر (با دلیل) ثبت می‌شود.", style = SabouType.caption, color = Sabou.colors.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("شمرده ${Fa.number(done.toLong())} از ${Fa.number(list.size.toLong())}", style = SabouType.bodyStrong,
                        color = if (done == list.size) Sabou.colors.primary else Sabou.colors.ink, modifier = Modifier.weight(1f))
                    if (plan != null && done < list.size) Text("باقی را صفر بزن", style = SabouType.label, color = Sabou.colors.primary,
                        modifier = Modifier.clickable { list.forEach { if (counted[it.item.id] == null) counted[it.item.id] = Quantity.ZERO } }.padding(6.dp))
                }
                if (shelves.size > 1) Picker("فقط این محل", listOf(Choice<String?>(null, "همه‌ی محل‌ها")) + shelves.map { Choice<String?>(it, it.ifBlank { "بدون محل" }) },
                    shelfFilter, { shelfFilter = it })
                var lastShelf: String? = null
                list.filter { shelfFilter == null || it.item.shelf == shelfFilter }.forEach { line ->
                    val item = line.item
                    if (item.shelf != lastShelf) {
                        lastShelf = item.shelf
                        Text(item.shelf.ifBlank { "بدون محل" }, style = SabouType.bodyStrong, color = Sabou.colors.primary)
                    }
                    key(item.id) {
                        ItemQuantityInput(item.name + (line.book?.let { " — دفتری ${Fa.quantity(it)}" } ?: ""), item, { counted[item.id] = it }, value = counted[item.id])
                    }
                }
                TextInput("توضیح (اختیاری)", note, { note = it }, singleLine = false)
                action.error?.let { Banner(it) }
                PrimaryButton("ثبت شمارش برای تأیید", { confirm = true }, enabled = if (plan != null) done == list.size else counted.values.any { it != null }, busy = action.busy)
            }
        }
        if (confirm) {
            Confirm("ثبت شمارش؟", "شمارش برای بررسی مدیر ثبت می‌شود و تا تأیید، موجودی تغییر نمی‌کند.", "ثبت", onConfirm = {
                confirm = false
                val lines = counted.entries.mapNotNull { (k, v) -> v?.let { ir.sabou.inventory.CountEntry(k, it) } }
                action.run({ counts.submit(ir.sabou.inventory.SubmitStockCount(id.value, branch, locationId, session.today, lines, note, plan?.id)) }) { r ->
                    nav.back(); nav.go(Route.CountDetail(r.resultId))
                }
            }, onDismiss = { confirm = false })
        }
    }

    @Composable
    fun CountHistory(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("سابقه انبارگردانی", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { overview.stockCounts(branch) }
                Page {
                    Loaded(data) { list ->
                        if (list.isEmpty()) EmptyState("هنوز انبارگردانی‌ای ثبت نشده است.")
                        list.forEach { v ->
                            val c = v.count
                            val value = c.lines.sumOf { it.postedValue ?: 0 }
                            val sub = "${Fa.date(c.date)} · ${c.countedByName} · ${ReportTables.countStatusName(c.status)}" +
                                (if (v.showsBook) " · ${Fa.number(c.differences.size.toLong())} اختلاف" else "")
                            NavRow(R.drawable.ic_count, v.location, sub, if (c.status == ir.sabou.inventory.CountStatus.POSTED) Fa.rialShort(value) else null,
                                tint = if (c.status == ir.sabou.inventory.CountStatus.PENDING) Sabou.colors.onAccentSoft else Sabou.colors.primary,
                                tile = if (c.status == ir.sabou.inventory.CountStatus.PENDING) Sabou.colors.accentSoft else Sabou.colors.primarySoft,
                                onClick = { nav.go(Route.CountDetail(c.id)) })
                        }
                    }
                }
            }
        }
    }

    private val varianceReasons = ir.sabou.inventory.VarianceReason.entries.map { Choice(it, ir.sabou.inventory.StockCountOperations.reasonName(it)) }

    @Composable
    fun CountDetail(nav: Nav, countId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, countId) { overview.stockCount(countId) to overview.items().associateBy { it.id } }
        val reasons = ir.sabou.app.ui.rememberValueMap<GlobalId, ir.sabou.inventory.VarianceReason?>()
        val notes = ir.sabou.app.ui.rememberValueMap<GlobalId, String>()
        var rejectReason by rememberSaveable { mutableStateOf("") }
        var confirm by rememberSaveable { mutableStateOf(0) }   // 1 approve, 2 reject
        val id = ir.sabou.app.ui.rememberCommandId(countId)
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("انبارگردانی", onBack = nav.back)
            Page {
                Loaded(data) { (v, items) ->
                    val c = v.count
                    val pending = c.status == ir.sabou.inventory.CountStatus.PENDING
                    val reviewer = pending && session.can(Permission.INVENTORY_ADJUST)
                    SCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.location, style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                            Chip(ReportTables.countStatusName(c.status), when (c.status) {
                                ir.sabou.inventory.CountStatus.PENDING -> ChipKind.ACCENT
                                ir.sabou.inventory.CountStatus.POSTED -> ChipKind.PRIMARY
                                ir.sabou.inventory.CountStatus.REJECTED -> ChipKind.DANGER
                            })
                        }
                        v.number?.let { KeyValue("شماره سند", Fa.digits(it), strong = true) }
                        KeyValue("تاریخ شمارش", Fa.date(c.date))
                        KeyValue("شمارش", c.countedByName)
                        c.reviewedByName?.let { KeyValue(if (c.status == ir.sabou.inventory.CountStatus.REJECTED) "رد" else "تأیید", it) }
                        c.rejectReason?.let { Text("دلیل رد: $it", style = SabouType.caption, color = Sabou.colors.danger) }
                        if (c.note.isNotBlank()) Text(c.note, style = SabouType.caption, color = Sabou.colors.muted)
                        if (c.status == ir.sabou.inventory.CountStatus.POSTED) KeyValue("ارزش کل اختلاف (ریال)", Fa.rial(c.lines.sumOf { it.postedValue ?: 0 }), strong = true)
                        if (!v.showsBook) Text("تا تأیید، اختلاف‌ها فقط به بررسی‌کننده نشان داده می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                        if (v.stale.isNotEmpty()) Banner("از زمان این شمارش، از ${v.stale.joinToString("، ")} بیش از آنچه شمرده شد مصرف یا جابه‌جا شده است؛ این شمارش دیگر قابل تأیید نیست. آن را رد کنید و دوباره بشمارید.")
                    }
                    val shown = if (v.showsBook) c.lines.sortedBy { it.difference == 0L } else c.lines
                    shown.forEach { l ->
                        val item = items[l.itemId]
                        val unit = item?.let { unitName(it.unit) } ?: ""
                        SCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item?.name ?: "", style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                if (v.showsBook && l.difference != 0L) Chip((if (l.difference > 0) "+" else "−") + Fa.quantity(Quantity.of(kotlin.math.abs(l.difference))) + " $unit",
                                    if (l.difference < 0) ChipKind.DANGER else ChipKind.ACCENT)
                            }
                            Text((if (v.showsBook) "دفتری ${Fa.quantity(l.bookAtCount)} · " else "") + "شمارش‌شده ${Fa.quantity(l.counted)} $unit" +
                                (l.postedValue?.let { " · ارزش ${Fa.rial(it)}" } ?: ""), style = SabouType.caption, color = Sabou.colors.muted)
                            if (l.note.isNotBlank()) Text("یادداشت شمارنده: ${l.note}", style = SabouType.caption, color = Sabou.colors.muted)
                            l.reason?.let { Text("دلیل: ${ir.sabou.inventory.StockCountOperations.reasonName(it)}" + if (l.reasonNote.isNotBlank()) " · ${l.reasonNote}" else "",
                                style = SabouType.caption, color = Sabou.colors.ink) }
                            if (reviewer && l.difference != 0L) {
                                Picker("دلیل اختلاف", varianceReasons, reasons[l.itemId], { reasons[l.itemId] = it })
                                TextInput("توضیح" + if (reasons[l.itemId] == ir.sabou.inventory.VarianceReason.OTHER) " (لازم)" else " (اختیاری)",
                                    notes[l.itemId] ?: "", { notes[l.itemId] = it })
                            }
                        }
                    }
                    action.error?.let { Banner(it) }
                    if (reviewer) {
                        val missing = c.differences.any { reasons[it.itemId] == null ||
                            (reasons[it.itemId] == ir.sabou.inventory.VarianceReason.OTHER && (notes[it.itemId] ?: "").trim().length < 3) }
                        PrimaryButton(if (c.differences.isEmpty()) "تأیید (بدون اختلاف)" else "تأیید و ثبت اختلاف‌ها", { confirm = 1 }, enabled = !missing, busy = action.busy)
                        if (missing) Text("برای هر اختلاف دلیل را انتخاب کنید.", style = SabouType.caption, color = Sabou.colors.muted)
                        FormCard("رد شمارش") {
                            TextInput("دلیل رد", rejectReason, { rejectReason = it }, placeholder = "مثلاً دوباره شمرده شود")
                            SecondaryButton("رد کن", { confirm = 2 }, enabled = rejectReason.trim().length >= 3, danger = true)
                        }
                    }
                    if (confirm == 1) Confirm("تأیید انبارگردانی؟", "موجودی و حساب مغایرت انبار با این اختلاف‌ها اصلاح می‌شود.", "تأیید", onConfirm = {
                        confirm = 0
                        val map = c.differences.associate { it.itemId to ir.sabou.inventory.LineReason(reasons[it.itemId]!!, notes[it.itemId] ?: "") }
                        action.run({ counts.approve(ir.sabou.inventory.ApproveStockCount(id.value, c.scope, c.id, map)) })
                    }, onDismiss = { confirm = 0 })
                    if (confirm == 2) Confirm("رد شمارش؟", "موجودی تغییر نمی‌کند و دلیل رد ثبت می‌شود.", "رد کن", onConfirm = {
                        confirm = 0
                        action.run({ counts.reject(ir.sabou.inventory.RejectStockCount(id.value, c.scope, c.id, rejectReason)) })
                    }, onDismiss = { confirm = 0 }, danger = true)
                    ExportButtons("انبارگردانی") { listOf(ReportTables.stockCount(overview.stockCount(countId), overview.items().associateBy { it.id })) }
                }
            }
        }
    }

    @Composable
    fun StockTransfer(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("انتقال کالا", onBack = nav.back)
            WithBranch { branch ->
                val data by load(session, branch) {
                    Triple(overview.items().filter { it.isActive }, overview.locations(branch).filter { it.isActive }, stockRecords.allLocations())
                }
                val mine by load(session) { overview.branches().map { ir.sabou.kernel.Scope.Branch(it.id) }.toSet() }
                var inTransit by rememberSaveable { mutableStateOf(true) }
                var source by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var target by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                val lines = ir.sabou.app.ui.rememberRows<IssueLine>({ listOf(it.itemId, it.quantity) }, { IssueLine(it[0] as GlobalId, it[1] as Quantity) }) { emptyList() }
                var note by rememberSaveable { mutableStateOf("") }
                var date by rememberSaveable { mutableStateOf(session.today) }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { (items, own, all) ->
                        FormCard {
                            Picker("از انبار", own.map { Choice(it.id, it.name) }, source, { source = it })
                            Picker("به انبار", all.filter { it.id != source }.map { Choice(it.id, it.name, if (it.scope != branch) "شعبه دیگر" else null) }, target, { target = it })
                            Divider()
                            val available = InventoryScreens.availability(source)
                            InventoryScreens.ItemLines(items, lines) { if (source == null) null else available[it] ?: Quantity.ZERO }
                            TextInput("توضیح", note, { note = it })
                            DateInput("تاریخ", date, { date = it }, session.today)
                            val dest = all.firstOrNull { it.id == target }
                            val otherBranch = dest != null && dest.scope != branch
                            // A branch this person cannot open confirms the goods itself.
                            val mustTransit = otherBranch && mine.orNull()?.contains(dest!!.scope) != true
                            if (otherBranch) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !mustTransit) { inTransit = !inTransit }) {
                                    androidx.compose.material3.Checkbox(checked = inTransit || mustTransit, onCheckedChange = { inTransit = it }, enabled = !mustTransit)
                                    Text("در راه: شعبه‌ی مقصد هنگام رسیدن تحویل می‌گیرد", style = SabouType.body, color = Sabou.colors.ink)
                                }
                                Text(if (inTransit || mustTransit) "کالا همین حالا از این انبار کم می‌شود و وقتی شعبه‌ی مقصد مقدار رسیده را تأیید کرد به موجودی آن اضافه می‌شود؛ کسری بین راه، مغایرت انبار مقصد است."
                                    else "کالا همین حالا به موجودی شعبه‌ی مقصد اضافه می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                            }
                            action.error?.let { Banner(it) }
                            PrimaryButton("ثبت انتقال", {
                                action.run({ inventory.transfer(TransferStock(id.value, branch, source!!, target!!, lines.toList(), date, note, inTransit = otherBranch && (inTransit || mustTransit))) }) { nav.back() }
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
                val issues by load(session, branch) { labor.documentIssues(branch, session.today) }
                var name by rememberSaveable { mutableStateOf("") }
                var nationalId by rememberSaveable { mutableStateOf("") }
                var salary by rememberSaveable { mutableStateOf<Money?>(null) }
                var payType by rememberSaveable { mutableStateOf(ir.sabou.payroll.PayType.MONTHLY) }
                var midMonth by rememberSaveable { mutableStateOf(false) }
                var start by rememberSaveable { mutableStateOf(session.today) }
                var birth by rememberSaveable { mutableStateOf<ir.sabou.kernel.JalaliDay?>(null) }
                var birthOk by rememberSaveable { mutableStateOf(true) }
                var birthKey by rememberSaveable { mutableStateOf(0) }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { list ->
                        if (list.isEmpty()) EmptyState("کارمندی برای این شعبه ثبت نشده است.")
                        // Current staff first, then those who left; each card opens the person's page.
                        list.sortedWith(compareBy({ it.endDate != null }, { it.personnelNumber })).forEach { e ->
                            SCard(onClick = { nav.go(Route.Employee(e.id)) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.name, style = SabouType.bodyStrong, color = if (e.endDate == null) Sabou.colors.ink else Sabou.colors.muted, modifier = Modifier.weight(1f))
                                    if (e.personnelNumber > 0) Chip("شماره ${Fa.number(e.personnelNumber.toLong())}", ChipKind.PRIMARY)
                                    if (e.endDate != null) Chip("پایان همکاری ${Fa.date(e.endDate!!)}", ChipKind.NEUTRAL)
                                }
                                KeyValue("حقوق ماهانه", Fa.rial(e.salaryOn(session.today)))
                                // Each document that needs attention by name: which one, and what is wrong with it.
                                issues.orNull().orEmpty().filter { it.employee.id == e.id }.forEach { v ->
                                    Text("⚠ " + StaffDocScreens.issueText(v.issue), style = SabouType.label,
                                        color = if (v.issue.missing || v.issue.expired) Sabou.colors.danger else Sabou.colors.onAccentSoft)
                                }
                            }
                        }
                    }
                    if (session.can(Permission.PERSONNEL_MANAGE)) {
                        FormCard("کارمند جدید") {
                            TextInput("نام و نام خانوادگی", name, { name = it })
                            TextInput("کد ملی", nationalId, { nationalId = it }, keyboard = KeyboardType.Number)
                            Picker("نوع پرداخت", ir.sabou.payroll.PayType.entries.map { Choice(it, EmployeeScreens.payTypeName(it)) }, payType, { payType = it })
                            MoneyInput(EmployeeScreens.rateLabel(payType), salary, { salary = it })
                            key(birthKey) { BirthDateInput("تاریخ تولد (اختیاری)", birth) { v, ok -> birth = v; birthOk = ok } }
                            Text("شماره پرسنلی خودکار داده می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { midMonth = !midMonth }) {
                                androidx.compose.material3.Checkbox(checked = midMonth, onCheckedChange = { midMonth = it })
                                Text("از وسط ماه شروع به کار کرده (حقوق ماه اول به نسبت روزها)", style = SabouType.body, color = Sabou.colors.ink)
                            }
                            if (midMonth) DateInput("تاریخ شروع کار", start, { start = it }, session.today)
                            action.error?.let { Banner(it) }
                            PrimaryButton("ثبت", {
                                action.run({ payroll.registerEmployee(RegisterEmployee(id.value, branch, name, nationalId, salary!!, startDate = start.takeIf { midMonth }, birthDate = birth, payType = payType)) }) {
                                    name = ""; nationalId = ""; salary = null; midMonth = false; birth = null; birthOk = true; birthKey++; id.value = GlobalId.new()
                                }
                            }, enabled = name.isNotBlank() && nationalId.isNotBlank() && salary != null && birthOk, busy = action.busy)
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
        // Hours and minutes are picked, not typed (a number keypad has no ":"); empty = none.
        var worked by rememberSaveable { mutableStateOf(record?.workedMinutes?.takeIf { it > 0 }) }
        var overtime by rememberSaveable { mutableStateOf(record?.overtimeMinutes?.takeIf { it > 0 }) }
        var absent by rememberSaveable { mutableStateOf(record?.absentMinutes?.takeIf { it > 0 }) }
        val action = rememberAction()
        FormCard(name) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeInput("کارکرد", worked, { worked = it }, Modifier.weight(1f), duration = true, optional = true, placeholder = "—")
                TimeInput("اضافه‌کار", overtime, { overtime = it }, Modifier.weight(1f), duration = true, optional = true, placeholder = "—")
                TimeInput("غیبت", absent, { absent = it }, Modifier.weight(1f), duration = true, optional = true, placeholder = "—")
            }
            action.error?.let { Banner(it) }
            if (LocalSession.current.can(Permission.ATTENDANCE_RECORD)) SecondaryButton(if (record == null) "ثبت" else "اصلاح", {
                action.run({ payroll.recordAttendance(RecordAttendance(GlobalId.new(), branch, employeeId, date, worked ?: 0, overtime ?: 0, absent ?: 0)) })
            }, enabled = !action.busy)
        }
    }

    /** One-off lines of the month: bonuses, commissions, fines (before the month is approved). */
    @Composable
    private fun PayAdjustments(branch: Scope.Branch, periodFrom: BusinessDate) {
        val session = LocalSession.current
        var changed by rememberSaveable { mutableStateOf(0) }
        val data by load(session, branch, periodFrom, changed) { labor.adjustments(branch, periodFrom) to overview.employees(branch).filter { it.endDate == null || it.endDate!! >= periodFrom } }
        val settings by load(session) { payrollSettings.current() }
        var adding by rememberSaveable { mutableStateOf(false) }
        val action = rememberAction()
        FormCard("پاداش و کسورات این ماه") {
            Loaded(data) { (list, people) ->
                if (list.isEmpty()) Text("پاداش یا کسری ثبت نشده است.", style = SabouType.body, color = Sabou.colors.muted)
                list.forEach { (a, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$name · ${a.title} · " + (if (a.kind == ir.sabou.payroll.PayLine.Kind.EARNING) "" else "−") + Fa.rial(a.amount) +
                            (if (a.insurable) " · مشمول بیمه" else "") + (if (a.taxable) " · مشمول مالیات" else ""),
                            style = SabouType.caption, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                        Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable(enabled = !action.busy) {
                            action.run({ payroll.removeAdjustment(ir.sabou.payroll.RemovePayAdjustment(GlobalId.new(), branch, a.id)) }) { changed++ }
                        }.padding(6.dp))
                    }
                }
                action.error?.let { Banner(it) }
                val defaults = settings.orNull()
                if (adding && defaults != null) AdjustmentForm(branch, periodFrom, people, defaults) { adding = false; changed++ }
                else Text("+ پاداش یا کسر", style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable { adding = true }.padding(vertical = 6.dp))
                Text("بعد از ثبت، حقوق این ماه را دوباره محاسبه کنید.", style = SabouType.caption, color = Sabou.colors.muted)
            }
        }
    }

    @Composable
    private fun AdjustmentForm(branch: Scope.Branch, periodFrom: BusinessDate, people: List<ir.sabou.payroll.Employee>, defaults: ir.sabou.payroll.PayrollSettings, onDone: () -> Unit) {
        var who by rememberSaveable { mutableStateOf<GlobalId?>(null) }      // null = everyone
        var title by rememberSaveable { mutableStateOf("پاداش") }
        var kind by rememberSaveable { mutableStateOf(ir.sabou.payroll.PayLine.Kind.EARNING) }
        var byPercent by rememberSaveable { mutableStateOf(false) }
        var amount by rememberSaveable { mutableStateOf<Money?>(null) }
        var percent by rememberSaveable { mutableStateOf("") }
        var insurable by rememberSaveable { mutableStateOf(defaults.bonusInsurable) }
        var taxable by rememberSaveable { mutableStateOf(defaults.bonusTaxable) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        val pct = Fa.parseQuantity(percent)?.let { (it.micros / 10_000).toInt() }   // percent → basis points
        Picker("برای", listOf(Choice<GlobalId?>(null, "همه‌ی کارکنان")) + people.map { Choice<GlobalId?>(it.id, it.name) }, who, { who = it })
        Picker("نوع", listOf(Choice(ir.sabou.payroll.PayLine.Kind.EARNING, "پاداش / مزایا"), Choice(ir.sabou.payroll.PayLine.Kind.DEDUCTION, "کسر")), kind, { kind = it })
        TextInput("شرح", title, { title = it })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { byPercent = !byPercent }) {
            androidx.compose.material3.Checkbox(checked = byPercent, onCheckedChange = { byPercent = it })
            Text("درصدی از حقوق هر نفر", style = SabouType.body, color = Sabou.colors.ink)
        }
        if (byPercent) TextInput("درصد", percent, { percent = it }, keyboard = KeyboardType.Decimal, placeholder = "مثلاً ۵۰ برای نیم‌ماه")
        else MoneyInput("مبلغ برای هر نفر", amount, { amount = it })
        if (kind == ir.sabou.payroll.PayLine.Kind.EARNING) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { insurable = !insurable }) {
                androidx.compose.material3.Checkbox(checked = insurable, onCheckedChange = { insurable = it })
                Text("مشمول بیمه", style = SabouType.body, color = Sabou.colors.ink)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { taxable = !taxable }) {
                androidx.compose.material3.Checkbox(checked = taxable, onCheckedChange = { taxable = it })
                Text("مشمول مالیات", style = SabouType.body, color = Sabou.colors.ink)
            }
        }
        action.error?.let { Banner(it) }
        PrimaryButton("ثبت", {
            val ids = who?.let { listOf(it) } ?: people.map { it.id }
            action.run({
                payroll.addAdjustment(ir.sabou.payroll.AddPayAdjustment(id.value, branch, ids, periodFrom, title, kind,
                    amount.takeIf { !byPercent }, pct.takeIf { byPercent }, insurable && kind == ir.sabou.payroll.PayLine.Kind.EARNING, taxable && kind == ir.sabou.payroll.PayLine.Kind.EARNING))
            }) { onDone() }
        }, enabled = title.isNotBlank() && people.isNotEmpty() && (if (byPercent) pct != null && pct > 0 else amount != null) && !action.busy, busy = action.busy)
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
                            Text("محاسبه طبق پارامترهای قانونی ثبت‌شده برای آن سال و عوامل حقوقی انجام می‌شود. تأیید با فرد دیگری است؛ اگر کس دیگری اجازه‌ی تأیید ندارد، خودتان تأیید می‌کنید.",
                                style = SabouType.caption, color = Sabou.colors.muted)
                        }
                        key(branch, fromDate) { PayAdjustments(branch, fromDate) }
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
                                    KeyValue(d.names[p.employeeId] ?: "", "خالص ${Fa.rial(p.net)}")
                                    p.payableDays?.let { days ->
                                        Text("ماه ناقص: ${Fa.number(days.toLong())} روز کار · حقوق این ماه ${Fa.rial(p.baseSalary)}", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                                    }
                                    Text("ناخالص ${Fa.rial(p.gross)} · بیمه ${Fa.rial(p.employeeInsurance)} · مالیات ${Fa.rial(p.incomeTax)}",
                                        style = SabouType.caption, color = Sabou.colors.muted)
                                    if (p.lines.isNotEmpty()) Text(p.lines.joinToString(" · ") { (if (it.kind == ir.sabou.payroll.PayLine.Kind.EARNING) "" else "−") + "${it.title} ${Fa.rial(it.amount)}" },
                                        style = SabouType.caption, color = Sabou.colors.muted)
                                    val unpaid = d.unpaid[run.id]?.get(p.employeeId)
                                    if (unpaid != null && !unpaid.isZero && session.can(Permission.PAYROLL_PAY)) {
                                        // One id per person and run until the payment succeeds (SABOU-AUDIT-011).
                                        val payId = ir.sabou.app.ui.rememberCommandId(run.id, p.employeeId)
                                        SecondaryButton("پرداخت ${Fa.rial(unpaid)} ریال", {
                                            action.run({ payroll.pay(PaySalary(payId.value, branch, run.id, p.employeeId, payAccount!!, unpaid, session.today)) }) {
                                                payId.value = GlobalId.new()
                                            }
                                        }, enabled = payAccount != null && !action.busy)
                                    }
                                }
                                key(run.id) {
                                    Text("لیست حقوق، پیش‌نویس لیست بیمه و مالیات، و فیش‌ها (هر فیش در یک صفحه‌ی PDF)", style = SabouType.caption, color = Sabou.colors.muted)
                                    ExportButtons("حقوق-${Fa.latinDigits(Fa.date(run.from)).replace('/', '-')}") {
                                        val place = overview.branches().firstOrNull { it.id == branch.branchId }?.name ?: "شعبه"
                                        // The insurance and tax sheets need national IDs and insurance numbers (personnel access).
                                        val people = runCatching { overview.employees(branch) }.getOrDefault(emptyList()).associateBy { it.id }
                                        listOf(ReportTables.payrollRun(run, d.names, place)) +
                                            (if (people.isNotEmpty()) listOf(ReportTables.insuranceList(run, people, place), ReportTables.taxList(run, people, place)) else emptyList()) +
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
                                KeyValue("بیمه", Fa.rial(d.insurance) + " ریال")
                                KeyValue("مالیات حقوق", Fa.rial(d.tax) + " ریال")
                                if (session.can(Permission.PAYROLL_PAY)) {
                                    // Kept until each payment succeeds (SABOU-AUDIT-011).
                                    val insuranceId = ir.sabou.app.ui.rememberCommandId(branch, "insurance")
                                    val taxId = ir.sabou.app.ui.rememberCommandId(branch, "tax")
                                    if (!d.insurance.isZero) SecondaryButton("پرداخت بیمه", {
                                        action.run({ payroll.remit(RemitLiability(insuranceId.value, branch, LiabilityKind.INSURANCE, payAccount!!, d.insurance, session.today)) }) {
                                            insuranceId.value = GlobalId.new()
                                        }
                                    }, enabled = payAccount != null && !action.busy)
                                    if (!d.tax.isZero) SecondaryButton("پرداخت مالیات", {
                                        action.run({ payroll.remit(RemitLiability(taxId.value, branch, LiabilityKind.INCOME_TAX, payAccount!!, d.tax, session.today)) }) {
                                            taxId.value = GlobalId.new()
                                        }
                                    }, enabled = payAccount != null && !action.busy)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
