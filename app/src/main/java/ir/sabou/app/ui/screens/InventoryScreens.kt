package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.ExportButtons
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.ItemQuantityInput
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.ReportTables
import ir.sabou.core.StockRecords
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.Item
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import ir.sabou.platform.Permission

/** Inventory review (ADR-0044): an item's stock card, the documents of the branch with reversal, line editors. */
object InventoryScreens {

    private fun monthStart(today: BusinessDate) = Fa.jalali(today).let { Fa.fromJalali(it.year, it.month, 1) }

    /** کاردکس: every movement of one item at one location, with the balance after each. */
    @Composable
    fun StockCard(nav: Nav, itemId: GlobalId, locationId: GlobalId) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(monthStart(session.today)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        val data by load(session, itemId, locationId, from, to) { stockRecords.card(itemId, locationId, from, to) }
        Column(Modifier.fillMaxSize()) {
            Header("کاردکس کالا", onBack = nav.back)
            Page {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { DateInput("از", from, { from = it }, session.today) }
                    Column(Modifier.weight(1f)) { DateInput("تا", to, { to = it }, session.today) }
                }
                Loaded(data) { card ->
                    val unit = unitName(card.item.unit)
                    SCard {
                        Text("${card.item.name} · ${card.location}", style = SabouType.section, color = Sabou.colors.ink)
                        KeyValue("مانده‌ی ابتدای دوره", "${Fa.quantity(Quantity.of(card.openingQuantity))} $unit · ${Fa.rial(card.openingValue)} ریال")
                        KeyValue("مانده‌ی پایان دوره", "${Fa.quantity(Quantity.of(card.closingQuantity))} $unit · ${Fa.rial(card.closingValue)} ریال", strong = true)
                    }
                    if (card.rows.isEmpty()) EmptyState("در این دوره گردشی نداشته است.")
                    SCard {
                        card.rows.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.title, style = SabouType.bodyStrong, color = if (r.reversal) Sabou.colors.muted else Sabou.colors.ink)
                                    Text(Fa.date(r.date) + (r.number?.let { " · ${Fa.digits(it)}" } ?: "") + " · مانده ${Fa.quantity(Quantity.of(r.balanceQuantity))}",
                                        style = SabouType.caption, color = Sabou.colors.muted)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text((if (r.quantity >= 0) "+" else "−") + Fa.quantity(Quantity.of(kotlin.math.abs(r.quantity))) + " $unit", style = SabouType.bodyStrong,
                                        color = if (r.quantity >= 0) Sabou.colors.primary else Sabou.colors.danger)
                                    Text(Fa.rial(r.value), style = SabouType.caption, color = Sabou.colors.muted)
                                }
                            }
                            Divider()
                        }
                    }
                    ExportButtons("کاردکس-${card.item.name}") { listOf(ReportTables.stockCard(card, from, to)) }
                }
            }
        }
    }

    /** The branch's waste, transfer, production and opening documents; one entered by mistake is taken back here. */
    @Composable
    fun Documents(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(monthStart(session.today)) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        var changed by rememberSaveable { mutableStateOf(0) }
        var reversing by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        Column(Modifier.fillMaxSize()) {
            Header("اسناد انبار", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, from, to, changed) { stockRecords.documents(branch, from, to) }
                Page {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { DateInput("از", from, { from = it }, session.today) }
                        Column(Modifier.weight(1f)) { DateInput("تا", to, { to = it }, session.today) }
                    }
                    Loaded(data) { list ->
                        if (list.isEmpty()) EmptyState("در این بازه ضایعات، انتقال، تولید یا موجودی اول دوره‌ای ثبت نشده است.")
                        list.forEach { v ->
                            val d = v.document
                            SCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(StockRecords.typeName(d.type) + (v.number?.let { " · ${Fa.digits(it)}" } ?: ""), style = SabouType.bodyStrong,
                                        color = if (v.reversed) Sabou.colors.muted else Sabou.colors.ink, modifier = Modifier.weight(1f))
                                    if (v.reversed) Chip("برگشت‌خورده", ChipKind.NEUTRAL)
                                }
                                Text(Fa.date(d.date) + (if (d.reason.isNotBlank()) " · ${wasteReasonName(d.reason)}" else "") + (if (d.dish.isNotBlank()) " · ${d.dish}" else ""),
                                    style = SabouType.caption, color = Sabou.colors.muted)
                                v.lines.forEach { l ->
                                    val unit = l.item?.let { unitName(it.unit) }.orEmpty()
                                    KeyValue("${l.item?.name.orEmpty()} · ${l.location}",
                                        (if (l.quantity >= 0) "+" else "−") + Fa.quantity(Quantity.of(kotlin.math.abs(l.quantity))) + " $unit · ${Fa.rial(kotlin.math.abs(l.value))}")
                                }
                                d.planned?.let { Text("برنامه ${Fa.quantity(it)} بود؛ مقدار به‌دست‌آمده همان است که وارد انبار شد.", style = SabouType.caption, color = Sabou.colors.onAccentSoft) }
                                if (d.note.isNotBlank()) Text(d.note, style = SabouType.caption, color = Sabou.colors.muted)
                                if (d.reversedReason.isNotBlank()) Text("دلیل برگشت: ${d.reversedReason}", style = SabouType.caption, color = Sabou.colors.danger)
                                if (!v.reversed && session.can(Permission.INVENTORY_ADJUST)) {
                                    if (reversing == d.id) ReverseForm(branch, d.type, d.id, session.today) { reversing = null; changed++ }
                                    else Text("برگشت (ثبت اشتباه)", style = SabouType.label, color = Sabou.colors.danger,
                                        modifier = Modifier.clickable { reversing = d.id }.padding(vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ReverseForm(branch: ir.sabou.kernel.Scope.Branch, type: String, documentId: GlobalId, today: BusinessDate, onDone: () -> Unit) {
        var reason by rememberSaveable { mutableStateOf("") }
        var date by rememberSaveable { mutableStateOf(today) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Text("همه‌ی ردیف‌های سند با همان ارزش برمی‌گردند؛ کالا باید هنوز همان‌جا باشد. سند اصلی می‌ماند و برگشت با دلیل ثبت می‌شود.",
            style = SabouType.caption, color = Sabou.colors.muted)
        TextInput("دلیل برگشت", reason, { reason = it }, placeholder = "مثلاً مقدار اشتباه وارد شد")
        DateInput("تاریخ برگشت", date, { date = it }, today)
        action.error?.let { Banner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("ثبت برگشت", {
                action.run({ inventory.reverseDocument(ir.sabou.inventory.ReverseInventoryDocument(id.value, branch, type, documentId, date, reason)) }) { onDone() }
            }, Modifier.weight(1f), enabled = reason.trim().length >= 3 && !action.busy, busy = action.busy)
            SecondaryButton("انصراف", onDone, Modifier.weight(1f))
        }
    }

    fun wasteReasonName(name: String) = when (name) {
        "SPOILAGE" -> "فساد"; "EXPIRED" -> "تاریخ گذشته"; "PREPARATION" -> "ضایعات آماده‌سازی"; "DAMAGE" -> "آسیب‌دیدگی"; "OTHER" -> "سایر"
        "COMPLIMENTARY" -> "پذیرایی مهمان"; "STAFF_MEAL" -> "غذای پرسنل"; "DONATION" -> "اهدایی"; else -> name
    }

    /**
     * Items and quantities of a document: pick, type in any of the item's units, add; each line shows what the location
     * holds ([available]) and can be removed.
     */
    @Composable
    fun ItemLines(items: List<Item>, lines: MutableList<IssueLine>, available: (GlobalId) -> Quantity?) {
        var itemId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var qty by rememberSaveable { mutableStateOf<Quantity?>(null) }
        var round by rememberSaveable { mutableStateOf(0) }
        val item = items.firstOrNull { it.id == itemId }
        Picker("کالا", items.map { Choice(it.id, it.name, unitName(it.unit) + (available(it.id)?.let { a -> " · موجود ${Fa.quantity(a)}" } ?: "")) }, itemId, { itemId = it })
        key(round) { ItemQuantityInput("مقدار", item, { qty = it }) }
        item?.let { i -> available(i.id)?.let { Text("موجود در این انبار: ${Fa.quantity(it)} ${unitName(i.unit)}", style = SabouType.caption, color = Sabou.colors.muted) } }
        SecondaryButton("افزودن به فهرست", {
            lines.add(IssueLine(itemId!!, qty!!)); itemId = null; qty = null; round++
        }, enabled = itemId != null && qty != null && !qty!!.isZero)
        lines.forEachIndexed { i, l ->
            val li = items.firstOrNull { x -> x.id == l.itemId }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${li?.name.orEmpty()} · ${Fa.quantity(l.quantity)} ${li?.let { u -> unitName(u.unit) }.orEmpty()}", style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { lines.removeAt(i) }.padding(6.dp))
            }
        }
    }

    /** The holding of each item at [locationId], for the line editors. */
    @Composable
    fun availability(locationId: GlobalId?): Map<GlobalId, Quantity> {
        val session = LocalSession.current
        val data by load(session, locationId) { locationId?.let { overview.stock(it) }.orEmpty() }
        return data.orNull().orEmpty().associate { it.itemId to it.quantity }
    }
}
