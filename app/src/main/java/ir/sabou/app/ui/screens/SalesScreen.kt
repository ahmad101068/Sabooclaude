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
import androidx.compose.runtime.saveable.rememberSaveable
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
import ir.sabou.sales.SaleLineInput
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
    /** The sale's document number once posted. */
    val number: String? = null,
    /** Menu price in force per menu item on the day (no entry: the item has no menu price). */
    val prices: Map<GlobalId, Money> = emptyMap(),
)

@Composable
fun SalesScreen(nav: Nav) {
    val session = LocalSession.current
    var date by rememberSaveable { mutableStateOf(session.today) }
    Column(Modifier.fillMaxSize()) {
        WithBranch { branch ->
            val state by load(session, branch, date) {
                val view = overview.salesDay(branch, date)
                SalesData(
                    menu = overview.menu().map { it.item }.filter { it.isActive },
                    kitchens = overview.locations(branch).filter { it.isActive },
                    accounts = view.accounts,
                    customers = view.customers,
                    sale = view.sale,
                    day = view.day,
                    number = view.number,
                    prices = overview.menuPrices(branch, date).mapNotNull { v -> v.price?.let { v.item.id to it } }.toMap(),
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
                            // Keyed by branch and date only: saving a new draft must not reset the form or its error.
                            else -> key(branch, date) { Editor(branch, date, data) }
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

/** A customer's cheque taken for the day's sale, into a cheque box. */
private class ChequeRow(account: GlobalId, amount: Money?, number: String, bank: String, sayad: String, due: BusinessDate, party: String) {
    var account by mutableStateOf(account)
    var amount by mutableStateOf(amount)
    var number by mutableStateOf(number)
    var bank by mutableStateOf(bank)
    var sayad by mutableStateOf(sayad)
    var due by mutableStateOf(due)
    var party by mutableStateOf(party)
    fun fields(): ArrayList<Any?> = arrayListOf(account, amount, number, bank, sayad, due, party)
    fun settlement(): Settlement.Liquid? {
        val a = amount ?: return null
        val no = Fa.latinDigits(number).trim(); val b = bank.trim(); val p = party.trim()
        if (a.isZero || no.isEmpty() || b.isEmpty() || p.isEmpty()) return null
        return Settlement.Liquid(account, a, ir.sabou.treasury.ChequeDetails(no, b, Fa.latinDigits(sayad).trim(), due, p))
    }
    companion object {
        fun of(f: List<Any?>) = ChequeRow(f[0] as GlobalId, f[1] as Money?, f[2] as String, f[3] as String, f[4] as String, f[5] as BusinessDate, f[6] as String)
    }
}

private class SaleForm(sale: DailySale?, data: SalesData, today: BusinessDate) {
    val portions = mutableStateMapOf<GlobalId, Quantity?>().apply { sale?.lines?.forEach { put(it.menuItemId, it.portions) } }
    /** A typed unit price: only where the menu has none, or for an override (with its reason). */
    val unitPrice = mutableStateMapOf<GlobalId, Money?>().apply { sale?.lines?.filter { it.listPrice == null || it.overridden }?.forEach { put(it.menuItemId, it.unitPrice) } }
    val reason = mutableStateMapOf<GlobalId, String>().apply { sale?.lines?.forEach { l -> l.overrideReason?.let { put(l.menuItemId, it) } } }
    private val prices = data.prices
    var kitchen by mutableStateOf(sale?.kitchenLocationId ?: data.kitchens.firstOrNull()?.id)
    var discount by mutableStateOf(sale?.discount)
    var service by mutableStateOf(sale?.serviceCharge)
    var tax by mutableStateOf(sale?.tax)
    /** Covers and bills of the day (optional, 0 = not recorded): average spend per guest and per bill. */
    var guests by mutableStateOf(sale?.guests?.takeIf { it > 0 }?.let { Fa.number(it.toLong()) } ?: "")
    var transactions by mutableStateOf(sale?.transactions?.takeIf { it > 0 }?.let { Fa.number(it.toLong()) } ?: "")
    val liquid = mutableStateMapOf<GlobalId, Money?>().apply {
        sale?.settlements?.filterIsInstance<Settlement.Liquid>()?.filter { it.cheque == null }?.forEach { put(it.treasuryAccountId, it.amount) }
    }
    val cheques = mutableStateListOf<ChequeRow>().apply {
        sale?.settlements?.filterIsInstance<Settlement.Liquid>()?.forEach { s ->
            s.cheque?.let { c -> add(ChequeRow(s.treasuryAccountId, s.amount, c.number, c.bank, c.sayadId, c.dueDate, c.counterparty)) }
        }
    }
    val credits = mutableStateListOf<CreditRow>().apply {
        sale?.settlements?.filterIsInstance<Settlement.Credit>()?.forEach { add(CreditRow(it.customerId, it.amount, it.dueDate)) }
    }
    var step by mutableIntStateOf(0)
    private val defaultDue = today.plusDays(30)

    fun addCredit() { credits.add(CreditRow(null, null, defaultDue)) }
    fun addCheque(box: GlobalId, today: BusinessDate) { cheques.add(ChequeRow(box, null, "", "", "", today, "")) }
    fun chequesComplete() = cheques.all { it.settlement() != null }

    fun listPrice(id: GlobalId): Money? = prices[id]
    /** The price the line will be recorded at: the typed one, else the menu's. */
    fun priceOf(id: GlobalId): Money? = unitPrice[id] ?: prices[id]
    fun overridden(id: GlobalId): Boolean = unitPrice[id] != null && prices[id] != null && unitPrice[id] != prices[id]
    /** Line total as the sales domain computes it (unit price × quantity, half-up to the Rial). */
    fun lineTotal(id: GlobalId): Money? {
        val q = portions[id]?.takeIf { !it.isZero } ?: return null
        return priceOf(id)?.let { runCatching { it.times(q) }.getOrNull() }
    }

    fun lines(): List<SaleLineInput> = portions.entries.mapNotNull { (id, q) ->
        if (q == null || q.isZero) null
        else SaleLineInput(id, q, unitPrice[id], reason[id]?.trim()?.takeIf { overridden(id) })
    }

    /** Every entered line has a price, and every override its reason. */
    fun linesComplete(): Boolean = lines().isNotEmpty() && lines().all { l ->
        val price = priceOf(l.menuItemId)
        price != null && !price.isZero && (!overridden(l.menuItemId) || (reason[l.menuItemId]?.trim()?.length ?: 0) >= 3)
    }

    fun settlements(): List<Settlement> =
        liquid.entries.mapNotNull { (id, m) -> m?.takeIf { !it.isZero }?.let { Settlement.Liquid(id, it) } } +
            cheques.mapNotNull { it.settlement() } +
            credits.mapNotNull { r -> val c = r.customer; val a = r.amount; if (c != null && a != null && !a.isZero) Settlement.Credit(c, a, r.due) else null }

    fun grossTotal() = lines().sumOf { lineTotal(it.menuItemId)?.rial ?: 0L }
    fun payable() = grossTotal() - (discount?.rial ?: 0) + (service?.rial ?: 0) + (tax?.rial ?: 0)
    fun settled() = settlements().sumOf { it.amount.rial }

    fun count(text: String): Int? = if (text.isBlank()) 0 else Fa.parseLong(text)?.takeIf { it in 0..100_000 }?.toInt()
    fun countsValid() = count(guests) != null && count(transactions) != null
}

/** The day's sale form, kept across process death (ADR-0010). */
@Suppress("UNCHECKED_CAST")
private fun saleFormSaver(data: SalesData, today: BusinessDate) = androidx.compose.runtime.saveable.listSaver<SaleForm, Any?>(
    save = { f ->
        listOf(
            HashMap(f.portions), HashMap<GlobalId, Money?>(), f.kitchen, f.discount, f.service, f.tax, HashMap(f.liquid),
            ArrayList(f.credits.map { arrayListOf(it.customer, it.amount, it.due) }), f.step, f.guests, f.transactions,
            ArrayList(f.cheques.map { it.fields() }),
            HashMap(f.unitPrice), HashMap(f.reason),
        )
    },
    restore = { l ->
        runCatching { SaleForm(null, data, today).apply {
            portions.putAll(l[0] as Map<GlobalId, Quantity?>)
            kitchen = l[2] as GlobalId? ?: kitchen
            discount = l[3] as Money?; service = l[4] as Money?; tax = l[5] as Money?
            liquid.putAll(l[6] as Map<GlobalId, Money?>)
            (l[7] as List<List<Any?>>).forEach { c -> credits.add(CreditRow(c[0] as GlobalId?, c[1] as Money?, c[2] as BusinessDate)) }
            step = l[8] as Int
            if (l.size > 10) { guests = l[9] as String; transactions = l[10] as String }
            if (l.size > 11) (l[11] as List<List<Any?>>).forEach { cheques.add(ChequeRow.of(it)) }
            if (l.size > 13) { unitPrice.putAll(l[12] as Map<GlobalId, Money?>); reason.putAll(l[13] as Map<GlobalId, String>) }
        } }.getOrNull()   // a draft that does not fit the form starts it fresh
    },
)

/**
 * One menu item: quantity, the price it sells at and the computed line total. The menu price is used as it is;
 * a person allowed to override types another price with a reason. Without a menu price, the unit price is typed.
 */
@Composable
private fun SaleLineEditor(form: SaleForm, id: GlobalId, canOverride: Boolean) {
    val list = form.listPrice(id)
    var editing by rememberSaveable(id) { mutableStateOf(form.overridden(id)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
        var text by rememberSaveable { mutableStateOf(form.portions[id]?.let { Fa.quantity(it) } ?: "") }
        TextInput("تعداد", text, { text = it; form.portions[id] = Fa.parseQuantity(it) }, Modifier.weight(0.4f),
            keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal,
            error = if (text.isNotBlank() && Fa.parseQuantity(text) == null) "نامعتبر" else null)
        if (list == null || editing) MoneyInput("قیمت واحد", form.unitPrice[id], { form.unitPrice[id] = it }, Modifier.weight(0.6f))
        else Column(Modifier.weight(0.6f).padding(top = 6.dp)) {
            Text("قیمت منو", style = SabouType.caption, color = Sabou.colors.muted)
            Text(Fa.rial(list) + " ریال", style = SabouType.bodyStrong, color = Sabou.colors.ink)
        }
    }
    if (list != null && canOverride) {
        Text(if (editing) "استفاده از قیمت منو" else "تغییر قیمت", style = SabouType.label, color = Sabou.colors.primary,
            modifier = Modifier.clickable { editing = !editing; if (!editing) { form.unitPrice.remove(id); form.reason.remove(id) } }.padding(vertical = 4.dp))
    }
    if (form.overridden(id)) {
        TextInput("دلیل تغییر قیمت (منو: ${Fa.rial(list!!)} ریال)", form.reason[id] ?: "", { form.reason[id] = it },
            error = if ((form.reason[id]?.trim()?.length ?: 0) < 3) "دلیل لازم است" else null)
    }
    if (list == null) Text("این آیتم در منو قیمت ندارد؛ قیمت واحد را وارد کنید یا از «منو و رسپی» قیمت بگذارید.", style = SabouType.caption, color = Sabou.colors.muted)
    form.lineTotal(id)?.let { KeyValue("جمع ردیف", Fa.rial(it) + " ریال") }
}

@Composable
private fun Editor(branch: Scope.Branch, date: BusinessDate, data: SalesData) {
    val session = LocalSession.current
    val form = rememberSaveable(saver = saleFormSaver(data, session.today)) { SaleForm(data.sale, data, session.today) }
    val action = rememberAction()
    val draftId = rememberSaveable { mutableStateOf(GlobalId.new()) }
    val postId = rememberSaveable { mutableStateOf(GlobalId.new()) }

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
                            SaleLineEditor(form, m.id, session.can(Permission.SALES_PRICE_OVERRIDE))
                        }
                    }
                }
                Divider()
                MoneyInput("تخفیف", form.discount, { form.discount = it })
                MoneyInput("حق سرویس", form.service, { form.service = it })
                MoneyInput("مالیات و عوارض", form.tax, { form.tax = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("تعداد مهمان", form.guests, { form.guests = it }, Modifier.weight(1f), keyboard = androidx.compose.ui.text.input.KeyboardType.Number,
                        error = if (form.count(form.guests) == null) "عدد معتبر نیست" else null)
                    TextInput("تعداد فاکتور", form.transactions, { form.transactions = it }, Modifier.weight(1f), keyboard = androidx.compose.ui.text.input.KeyboardType.Number,
                        error = if (form.count(form.transactions) == null) "عدد معتبر نیست" else null)
                }
                Text("اختیاری؛ برای میانگین خرید هر مهمان و هر فاکتور در گزارش پایان روز.", style = SabouType.caption, color = Sabou.colors.muted)
                PrimaryButton("ادامه: تسویه", { form.step = 1 }, enabled = form.linesComplete() && form.countsValid())
            }
            1 -> FormCard("روش‌های تسویه") {
                if (data.accounts.isEmpty()) Banner("برای این شعبه صندوق یا کارت‌خوانی تعریف نشده است.", ChipKind.ACCENT)
                data.accounts.filter { it.kind.isOrdinary }.forEach { a ->
                    key(a.id) {
                        val (tint, tile) = kindColors(a.kind)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IconTile(kindIcon(a.kind), tint, tile, size = 40)
                            MoneyInput("${kindName(a.kind)} · ${a.name}", form.liquid[a.id], { form.liquid[a.id] = it }, Modifier.weight(1f))
                        }
                    }
                }
                val boxes = data.accounts.filter { it.kind == ir.sabou.treasury.TreasuryKind.RECEIVED_CHEQUES }
                form.cheques.forEachIndexed { i, row ->
                    key(row) {
                        Column(
                            Modifier.fillMaxWidth().clip(SabouShapes.field).background(Sabou.colors.bankSoft).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("چک ${Fa.number(i + 1L)}", style = SabouType.bodyStrong, color = Sabou.colors.bank, modifier = Modifier.weight(1f))
                                Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { form.cheques.remove(row) }.padding(6.dp))
                            }
                            if (boxes.size > 1) Picker("صندوق چک", boxes.map { Choice(it.id, it.name) }, row.account, { row.account = it })
                            MoneyInput("مبلغ چک", row.amount, { row.amount = it })
                            TextInput("شماره چک", row.number, { row.number = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                            TextInput("بانک", row.bank, { row.bank = it })
                            TextInput("شناسه صیادی (اختیاری)", row.sayad, { row.sayad = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                            DateInput("سررسید", row.due, { row.due = it }, session.today)
                            TextInput("صاحب حساب", row.party, { row.party = it })
                            if (row.settlement() == null) Text("مبلغ، شماره، بانک و صاحب حساب لازم است.", style = SabouType.caption, color = Sabou.colors.danger)
                        }
                    }
                }
                if (boxes.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(SabouShapes.field).border(1.dp, Sabou.colors.bankSoft, SabouShapes.field)
                        .clickable { form.addCheque(boxes.first().id, session.today) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = Sabou.colors.bank, modifier = Modifier.size(18.dp))
                    Text(" افزودن چک", style = SabouType.bodyStrong, color = Sabou.colors.bank)
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
                            PickerOrHint("مشتری", data.customers.map { Choice(it.id, it.name, "سقف اعتبار ${Fa.rial(it.creditLimit)} ریال") }, row.customer,
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
                PrimaryButton("ادامه: تأیید", { form.step = 2 }, enabled = form.settlements().isNotEmpty() && form.chequesComplete())
            }
            else -> FormCard("تأیید و ثبت") {
                KeyValue("فروش ناخالص", Fa.rial(form.grossTotal()))
                KeyValue("تخفیف", "− " + Fa.rial(form.discount?.rial ?: 0), Sabou.colors.danger)
                KeyValue("حق سرویس", Fa.rial(form.service?.rial ?: 0))
                KeyValue("مالیات و عوارض", Fa.rial(form.tax?.rial ?: 0))
                Divider()
                KeyValue("قابل تسویه (ریال)", Fa.rial(form.payable()), strong = true)
                form.settlements().forEach { s ->
                    val label = when (s) {
                        is Settlement.Liquid -> s.cheque?.let { "چک ${Fa.digits(it.number)} · ${it.bank} · سررسید ${Fa.date(it.dueDate)}" }
                            ?: data.accounts.firstOrNull { it.id == s.treasuryAccountId }?.name ?: "حساب"
                        is Settlement.Credit -> "نسیه · " + (data.customers.firstOrNull { it.id == s.customerId }?.name ?: "مشتری")
                    }
                    KeyValue(label, Fa.rial(s.amount))
                }
                action.error?.let { Banner(it) }
                val kitchen = form.kitchen
                fun draft() = SaveSaleDraft(draftId.value, branch, date, kitchen!!, form.lines(), form.discount ?: Money.ZERO,
                    form.service ?: Money.ZERO, form.tax ?: Money.ZERO, form.settlements(), form.count(form.guests) ?: 0, form.count(form.transactions) ?: 0)
                SecondaryButton("ذخیره پیش‌نویس", {
                    action.run({ salesOps.saveDraft(draft()) }) { draftId.value = GlobalId.new() }
                }, enabled = kitchen != null && !action.busy)
                if (session.can(Permission.SALES_POST)) {
                    PrimaryButton("ثبت نهایی فروش روز", {
                        // Two commands: the draft is saved (and its id retired) even if posting then fails,
                        // so a corrected retry never reuses a command id with different content.
                        action.run({ salesOps.saveDraft(draft()) }) { saved ->
                            draftId.value = GlobalId.new()
                            action.run({ salesOps.post(PostDailySale(postId.value, branch, saved.resultId)) }) { postId.value = GlobalId.new() }
                        }
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
        KeyValue("قابل تسویه", Fa.rial(form.payable()) + " ریال", strong = true)
        if (form.step >= 1) {
            if (remaining == 0L && form.payable() > 0) Banner("تسویه کامل است · مانده: ۰", ChipKind.PRIMARY, R.drawable.ic_check)
            else Banner("مانده تسویه: ${Fa.rial(remaining)} ریال", ChipKind.ACCENT)
        }
    }
}

// ---------------------------------------------------------------- Posted day

@Composable
private fun PostedDay(branch: Scope.Branch, date: BusinessDate, data: SalesData) {
    val session = LocalSession.current
    val sale = data.sale!!
    val action = rememberAction()
    var counted by rememberSaveable { mutableStateOf<Money?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var confirmReverse by remember { mutableStateOf(false) }
    val commandId = rememberSaveable(sale.id) { mutableStateOf(GlobalId.new()) }
    val closed = data.day?.closed == true

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SCard {
            data.number?.let { KeyValue("شماره سند", Fa.digits(it), strong = true) }
            KeyValue("فروش ناخالص", Fa.rial(sale.gross))
            KeyValue("تخفیف", "− " + Fa.rial(sale.discount), Sabou.colors.danger)
            KeyValue("حق سرویس", Fa.rial(sale.serviceCharge))
            KeyValue("مالیات و عوارض", Fa.rial(sale.tax))
            Divider()
            KeyValue("جمع تسویه‌شده (ریال)", Fa.rial(sale.payable), strong = true)
            if (sale.guests > 0) KeyValue("مهمان · میانگین هر نفر", "${Fa.number(sale.guests.toLong())} · ${Fa.rial(sale.netFood.rial / sale.guests)}")
            if (sale.transactions > 0) KeyValue("فاکتور · میانگین هر فاکتور", "${Fa.number(sale.transactions.toLong())} · ${Fa.rial(sale.payable.rial / sale.transactions)}")
            KeyValue("بهای تمام‌شده مواد", Fa.rial(sale.cost))
            if (!sale.netFood.isZero) {
                val pct = sale.cost.rial * 1000 / sale.netFood.rial
                KeyValue("درصد بهای غذا (Food cost)", Fa.digits("${pct / 10}.${pct % 10}").replace('.', '٫') + "٪")
            }
        }
        SCard {
            Text("اقلام فروش", style = SabouType.section, color = Sabou.colors.ink)
            val names = data.menu.associate { it.id to it.name }
            sale.lines.forEach { l ->
                KeyValue(names[l.menuItemId] ?: "آیتم منو", "${Fa.quantity(l.portions)} × ${Fa.rial(l.unitPrice)} = ${Fa.rial(l.gross)}")
                if (l.overridden) Text("قیمت منو ${Fa.rial(l.listPrice!!)} ریال · ${l.overrideReason.orEmpty()}", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
            }
        }
        SCard {
            Text("تسویه", style = SabouType.section, color = Sabou.colors.ink)
            sale.settlements.forEach { s ->
                when (s) {
                    is Settlement.Liquid -> KeyValue(s.cheque?.let { "چک ${Fa.digits(it.number)} · ${it.bank}" } ?: data.accounts.firstOrNull { it.id == s.treasuryAccountId }?.name ?: "حساب",
                        "+ " + Fa.rial(s.amount), Sabou.colors.moneyIn)
                    is Settlement.Credit -> KeyValue("نسیه · " + (data.customers.firstOrNull { it.id == s.customerId }?.name ?: "مشتری"), Fa.rial(s.amount), Sabou.colors.onAccentSoft)
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
