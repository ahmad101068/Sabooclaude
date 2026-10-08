package ir.sabou.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
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
import ir.sabou.app.ui.components.IconTile
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.MoneyInput
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouShapes
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.inventory.Location
import ir.sabou.inventory.MenuItem
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission
import ir.sabou.sales.CloseSalesDay
import ir.sabou.sales.Customer
import ir.sabou.sales.DailySale
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.ReopenSalesDay
import ir.sabou.sales.ReverseDailySale
import ir.sabou.sales.SaleLine
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.SalesDay
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.TreasuryAccount

private class SalesData(
    val menu: List<MenuItem>,
    val kitchens: List<Location>,
    val accounts: List<TreasuryAccount>,
    val customers: List<Customer>,
    val sale: DailySale?,
    val day: SalesDay?,
)

@Composable
fun SalesScreen(nav: Nav) {
    val session = LocalSession.current
    var date by remember { mutableStateOf(session.today) }
    Column(Modifier.fillMaxSize()) {
        WithBranch { branch ->
            val state by load(session, branch, date) {
                SalesData(
                    menu = recipes.menuItems().filter { it.isActive },
                    kitchens = locations.all().filter { it.isActive && it.scope == branch },
                    accounts = treasuryAccounts.all().filter { it.isActive && it.scope == branch },
                    customers = customers.all().filter { it.isActive && it.registeredIn == branch },
                    sale = sales.activeSale(branch, date),
                    day = sales.day(branch, date),
                )
            }
            val d = state.orNull()
            Header("فروش روز", Fa.dayTitle(date)) {
                when {
                    d?.day?.closed == true -> Chip("روز بسته", ChipKind.NEUTRAL)
                    d?.sale?.status == SaleStatus.POSTED -> Chip("ثبت نهایی", ChipKind.PRIMARY)
                    d?.sale != null -> Chip("پیش‌نویس", ChipKind.NEUTRAL)
                    else -> Unit
                }
            }
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SCard { DateInput("تاریخ", date, { date = it }, session.today) } }
                item {
                    Loaded(state) { data ->
                        when {
                            data.menu.isEmpty() -> EmptyState("هنوز منویی تعریف نشده است. از «من ← منو و رسپی» آیتم‌های منو را تعریف کنید.")
                            data.kitchens.isEmpty() -> EmptyState("برای این شعبه آشپزخانه یا انباری تعریف نشده است.")
                            data.sale?.status == SaleStatus.POSTED -> PostedDay(branch, date, data)
                            data.day?.closed == true -> Banner("این روز بسته شده است.", ChipKind.ACCENT)
                            else -> key(date, data.sale?.id) { Editor(branch, date, data) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Editor (draft)

private class CreditRow(customer: GlobalId?, amount: Money?, due: BusinessDate) {
    var customer by mutableStateOf(customer)
    var amount by mutableStateOf(amount)
    var due by mutableStateOf(due)
}

private class SaleForm(sale: DailySale?, data: SalesData, today: BusinessDate) {
    val portions = mutableStateMapOf<GlobalId, Quantity?>().apply { sale?.lines?.forEach { put(it.menuItemId, it.portions) } }
    val gross = mutableStateMapOf<GlobalId, Money?>().apply { sale?.lines?.forEach { put(it.menuItemId, it.gross) } }
    var kitchen by mutableStateOf(sale?.kitchenLocationId ?: data.kitchens.firstOrNull()?.id)
    var discount by mutableStateOf(sale?.discount)
    var service by mutableStateOf(sale?.serviceCharge)
    var tax by mutableStateOf(sale?.tax)
    val liquid = mutableStateMapOf<GlobalId, Money?>().apply {
        sale?.settlements?.filterIsInstance<Settlement.Liquid>()?.forEach { put(it.treasuryAccountId, it.amount) }
    }
    val credits = mutableStateListOf<CreditRow>().apply {
        sale?.settlements?.filterIsInstance<Settlement.Credit>()?.forEach { add(CreditRow(it.customerId, it.amount, it.dueDate)) }
    }
    var step by mutableIntStateOf(0)
    private val defaultDue = today.plusDays(30)

    fun addCredit() { credits.add(CreditRow(null, null, defaultDue)) }

    fun lines(): List<SaleLine> = portions.entries.mapNotNull { (id, q) ->
        val g = gross[id]
        if (q == null || q.isZero || g == null) null else SaleLine(id, q, g)
    }

    fun settlements(): List<Settlement> =
        liquid.entries.mapNotNull { (id, m) -> m?.takeIf { !it.isZero }?.let { Settlement.Liquid(id, it) } } +
            credits.mapNotNull { r -> val c = r.customer; val a = r.amount; if (c != null && a != null && !a.isZero) Settlement.Credit(c, a, r.due) else null }

    fun grossTotal() = lines().sumOf { it.gross.rial }
    fun payable() = grossTotal() - (discount?.rial ?: 0) + (service?.rial ?: 0) + (tax?.rial ?: 0)
    fun settled() = settlements().sumOf { it.amount.rial }
}

@Composable
private fun Editor(branch: Scope.Branch, date: BusinessDate, data: SalesData) {
    val session = LocalSession.current
    val form = remember { SaleForm(data.sale, data, session.today) }
    val action = rememberAction()
    val draftId = remember { mutableStateOf(GlobalId.new()) }
    val postId = remember { mutableStateOf(GlobalId.new()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Steps(form.step) { form.step = it }
        Summary(form)
        when (form.step) {
            0 -> FormCard("اقلام فروش") {
                if (data.kitchens.size > 1) Picker("آشپزخانه مصرف", data.kitchens.map { Choice(it.id, it.name) }, form.kitchen, { form.kitchen = it })
                data.menu.forEach { m ->
                    key(m.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(m.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                var text by remember { mutableStateOf(form.portions[m.id]?.let { Fa.quantity(it) } ?: "") }
                                TextInput("تعداد", text, { text = it; form.portions[m.id] = Fa.parseQuantity(it) }, Modifier.weight(0.4f),
                                    keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
                                MoneyInput("مبلغ", form.gross[m.id], { form.gross[m.id] = it }, Modifier.weight(0.6f))
                            }
                        }
                    }
                }
                Divider()
                MoneyInput("تخفیف", form.discount, { form.discount = it })
                MoneyInput("حق سرویس", form.service, { form.service = it })
                MoneyInput("مالیات و عوارض", form.tax, { form.tax = it })
                PrimaryButton("ادامه: تسویه", { form.step = 1 }, enabled = form.lines().isNotEmpty())
            }
            1 -> FormCard("روش‌های تسویه") {
                if (data.accounts.isEmpty()) Banner("برای این شعبه صندوق یا کارت‌خوانی تعریف نشده است.", ChipKind.ACCENT)
                data.accounts.forEach { a ->
                    key(a.id) {
                        val (tint, tile) = kindColors(a.kind)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IconTile(kindIcon(a.kind), tint, tile, size = 40)
                            MoneyInput("${kindName(a.kind)} · ${a.name}", form.liquid[a.id], { form.liquid[a.id] = it }, Modifier.weight(1f))
                        }
                    }
                }
                form.credits.forEachIndexed { i, row ->
                    key(row) {
                        Column(
                            Modifier.fillMaxWidth().clip(SabouShapes.field).background(Sabou.colors.accentSoft).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("نسیه ${Fa.number(i + 1L)}", style = SabouType.bodyStrong, color = Sabou.colors.onAccentSoft, modifier = Modifier.weight(1f))
                                Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { form.credits.remove(row) }.padding(6.dp))
                            }
                            PickerOrHint("مشتری", data.customers.map { Choice(it.id, it.name, "سقف اعتبار ${Fa.toman(it.creditLimit)} تومان") }, row.customer,
                                { row.customer = it }, "برای این شعبه مشتری اعتباری تعریف نشده است (من ← مشتریان).")
                            MoneyInput("مبلغ نسیه", row.amount, { row.amount = it })
                            DateInput("سررسید", row.due, { row.due = it }, session.today)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(SabouShapes.field).border(1.dp, Sabou.colors.primarySoft, SabouShapes.field)
                        .clickable { form.addCredit() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = Sabou.colors.primary, modifier = Modifier.size(18.dp))
                    Text(" افزودن نسیه", style = SabouType.bodyStrong, color = Sabou.colors.primary)
                }
                SecondaryButton("بازگشت به اقلام", { form.step = 0 })
                PrimaryButton("ادامه: تأیید", { form.step = 2 }, enabled = form.settlements().isNotEmpty())
            }
            else -> FormCard("تأیید و ثبت") {
                KeyValue("فروش ناخالص", Fa.toman(form.grossTotal()))
                KeyValue("تخفیف", "− " + Fa.toman(form.discount?.rial ?: 0), Sabou.colors.danger)
                KeyValue("حق سرویس", Fa.toman(form.service?.rial ?: 0))
                KeyValue("مالیات و عوارض", Fa.toman(form.tax?.rial ?: 0))
                Divider()
                KeyValue("قابل تسویه (تومان)", Fa.toman(form.payable()), strong = true)
                form.settlements().forEach { s ->
                    val label = when (s) {
                        is Settlement.Liquid -> data.accounts.firstOrNull { it.id == s.treasuryAccountId }?.name ?: "حساب"
                        is Settlement.Credit -> "نسیه · " + (data.customers.firstOrNull { it.id == s.customerId }?.name ?: "مشتری")
                    }
                    KeyValue(label, Fa.toman(s.amount))
                }
                action.error?.let { Banner(it) }
                val kitchen = form.kitchen
                fun draft() = SaveSaleDraft(draftId.value, branch, date, kitchen!!, form.lines(), form.discount ?: Money.ZERO,
                    form.service ?: Money.ZERO, form.tax ?: Money.ZERO, form.settlements())
                SecondaryButton("ذخیره پیش‌نویس", {
                    action.run({ salesOps.saveDraft(draft()) }) { draftId.value = GlobalId.new() }
                }, enabled = kitchen != null && !action.busy)
                if (session.can(Permission.SALES_POST)) {
                    PrimaryButton("ثبت نهایی فروش روز", {
                        action.run({
                            val saleId = salesOps.saveDraft(draft()).resultId
                            salesOps.post(PostDailySale(postId.value, branch, saleId))
                        }) { draftId.value = GlobalId.new(); postId.value = GlobalId.new() }
                    }, enabled = kitchen != null && form.payable() == form.settled(), busy = action.busy)
                    Text("با ثبت نهایی، مصرف مواد اولیه به بهای تمام‌شده، درآمد، واریز به صندوق‌ها و طلب مشتریان یک‌جا و با هم ثبت می‌شود.",
                        style = SabouType.caption, color = Sabou.colors.muted)
                } else {
                    Text("ثبت نهایی با مدیر شعبه است؛ پیش‌نویس را ذخیره کنید.", style = SabouType.caption, color = Sabou.colors.muted)
                }
                SecondaryButton("بازگشت به تسویه", { form.step = 1 })
            }
        }
    }
}

@Composable
private fun Steps(step: Int, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("۱. اقلام", "۲. تسویه", "۳. تأیید").forEachIndexed { i, label ->
            val done = i <= step
            Column(Modifier.weight(1f).clickable(enabled = i < step) { onStep(i) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(SabouShapes.chip).background(if (done) Sabou.colors.primary else Sabou.colors.borderStrong))
                Text(label, style = SabouType.label, color = if (done) Sabou.colors.primary else Sabou.colors.muted)
            }
        }
    }
}

@Composable
private fun Summary(form: SaleForm) {
    val remaining = form.payable() - form.settled()
    SCard {
        KeyValue("قابل تسویه", Fa.toman(form.payable()) + " تومان", strong = true)
        if (form.step >= 1) {
            if (remaining == 0L && form.payable() > 0) Banner("تسویه کامل است · مانده: ۰", ChipKind.PRIMARY, R.drawable.ic_check)
            else Banner("مانده تسویه: ${Fa.toman(remaining)} تومان", ChipKind.ACCENT)
        }
    }
}

// ---------------------------------------------------------------- Posted day

@Composable
private fun PostedDay(branch: Scope.Branch, date: BusinessDate, data: SalesData) {
    val session = LocalSession.current
    val sale = data.sale!!
    val action = rememberAction()
    var counted by remember { mutableStateOf<Money?>(null) }
    var reason by remember { mutableStateOf("") }
    var confirmReverse by remember { mutableStateOf(false) }
    val commandId = remember(sale.id) { mutableStateOf(GlobalId.new()) }
    val closed = data.day?.closed == true

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SCard {
            KeyValue("فروش ناخالص", Fa.toman(sale.gross))
            KeyValue("تخفیف", "− " + Fa.toman(sale.discount), Sabou.colors.danger)
            KeyValue("حق سرویس", Fa.toman(sale.serviceCharge))
            KeyValue("مالیات و عوارض", Fa.toman(sale.tax))
            Divider()
            KeyValue("جمع تسویه‌شده (تومان)", Fa.toman(sale.payable), strong = true)
            KeyValue("بهای تمام‌شده مواد", Fa.toman(sale.cost))
            if (!sale.netFood.isZero) {
                val pct = sale.cost.rial * 1000 / sale.netFood.rial
                KeyValue("درصد بهای غذا (Food cost)", Fa.digits("${pct / 10}.${pct % 10}").replace('.', '٫') + "٪")
            }
        }
        SCard {
            Text("تسویه", style = SabouType.section, color = Sabou.colors.ink)
            sale.settlements.forEach { s ->
                when (s) {
                    is Settlement.Liquid -> KeyValue(data.accounts.firstOrNull { it.id == s.treasuryAccountId }?.name ?: "حساب", "+ " + Fa.toman(s.amount), Sabou.colors.moneyIn)
                    is Settlement.Credit -> KeyValue("نسیه · " + (data.customers.firstOrNull { it.id == s.customerId }?.name ?: "مشتری"), Fa.toman(s.amount), Sabou.colors.onAccentSoft)
                }
            }
        }
        action.error?.let { Banner(it) }
        if (!closed && session.can(Permission.SALES_DAY_CLOSE)) {
            FormCard("بستن روز") {
                MoneyInput("نقد شمارش‌شده صندوق", counted, { counted = it }, hint = "پس از بستن، فروش این روز قابل تغییر نیست مگر با بازگشایی مالک.")
                PrimaryButton("بستن روز فروش", {
                    val c = counted ?: return@PrimaryButton
                    action.run({ salesOps.closeDay(CloseSalesDay(commandId.value, branch, date, c)) }) { commandId.value = GlobalId.new() }
                }, enabled = counted != null, busy = action.busy)
            }
        }
        if (closed && session.can(Permission.SALES_DAY_REOPEN)) {
            FormCard("بازگشایی روز") {
                TextInput("دلیل بازگشایی", reason, { reason = it })
                SecondaryButton("بازگشایی روز", {
                    action.run({ salesOps.reopenDay(ReopenSalesDay(commandId.value, branch, date, reason)) }) { commandId.value = GlobalId.new(); reason = "" }
                }, enabled = reason.trim().length >= 3)
            }
        }
        if (!closed && session.can(Permission.SALES_REVERSE)) {
            FormCard("برگشت فروش روز") {
                Text("همه آثار فروش (مصرف انبار، درآمد، واریزها و طلب‌ها) با یک سند برگشتی خنثی می‌شود. سوابق حذف نمی‌شوند.", style = SabouType.caption, color = Sabou.colors.muted)
                TextInput("دلیل برگشت", reason, { reason = it })
                SecondaryButton("برگشت فروش", { confirmReverse = true }, enabled = reason.trim().length >= 3, danger = true)
            }
        }
    }
    if (confirmReverse) {
        Confirm("برگشت فروش این روز؟", "پس از برگشت می‌توانید فروش را دوباره و درست ثبت کنید.", "برگشت بزن", onConfirm = {
            action.run({ salesOps.reverse(ReverseDailySale(commandId.value, branch, sale.id, date, reason)) }) { commandId.value = GlobalId.new(); reason = "" }
        }, onDismiss = { confirmReverse = false }, danger = true)
    }
}
