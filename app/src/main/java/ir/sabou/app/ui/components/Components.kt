package ir.sabou.app.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouShapes
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Money

// ---------------------------------------------------------------- Layout

/** Screen header used by every page: optional back button, title, subtitle and a trailing slot. */
@Composable
fun Header(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(44.dp).clip(SabouShapes.tile).background(Sabou.colors.surface)
                    .border(1.dp, Sabou.colors.border, SabouShapes.tile)
                    .clickable(role = Role.Button, onClickLabel = "بازگشت", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "بازگشت", tint = Sabou.colors.ink, modifier = Modifier.size(20.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (onBack != null) SabouType.title.copy(fontSize = SabouType.section.fontSize * 1.25f) else SabouType.title, color = Sabou.colors.ink)
            if (subtitle != null) Text(subtitle, style = SabouType.caption, color = Sabou.colors.muted)
        }
        trailing()
    }
}

/** Scrollable page body with the standard gutter and gap. */
@Composable
fun <T> PageList(rows: List<T>, header: (@Composable () -> Unit)? = null, footer: (@Composable () -> Unit)? = null, row: @Composable (T) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (header != null) item { header() }
        items(rows) { row(it) }
        if (footer != null) item { footer() }
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    }
}

@Composable
fun SCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val base = modifier.fillMaxWidth().clip(SabouShapes.card).background(Sabou.colors.surface).border(1.dp, Sabou.colors.border, SabouShapes.card)
    Column(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = SabouType.label, color = Sabou.colors.muted)
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Sabou.colors.divider))

@Composable
fun IconTile(@DrawableRes icon: Int, tint: Color, background: Color, size: Int = 44) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size((size / 2).dp))
    }
}

/** A tappable list row: icon tile, title + subtitle, trailing value and a chevron. */
@Composable
fun NavRow(@DrawableRes icon: Int, title: String, subtitle: String? = null, trailing: String? = null, tint: Color = Sabou.colors.primary, tile: Color = Sabou.colors.primarySoft, onClick: () -> Unit) {
    SCard(onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, tint, tile)
            Column(Modifier.weight(1f)) {
                Text(title, style = SabouType.bodyStrong, color = Sabou.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = SabouType.caption, color = Sabou.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Text(trailing, style = SabouType.amount, color = Sabou.colors.ink)
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, tint = Sabou.colors.subtle, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = Sabou.colors.ink, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (strong) SabouType.bodyStrong else SabouType.body, color = if (strong) Sabou.colors.ink else Sabou.colors.muted, modifier = Modifier.weight(1f))
        Text(value, style = if (strong) SabouType.amount else SabouType.bodyStrong, color = valueColor)
    }
}

// ---------------------------------------------------------------- Chips and banners

enum class ChipKind { NEUTRAL, PRIMARY, ACCENT, SALES, PURCHASE, TREASURY, DANGER }

@Composable
fun Chip(text: String, kind: ChipKind = ChipKind.NEUTRAL) {
    val c = Sabou.colors
    val (bg, fg) = when (kind) {
        ChipKind.NEUTRAL -> c.track to c.muted
        ChipKind.PRIMARY, ChipKind.SALES -> c.primarySoft to c.primary
        ChipKind.ACCENT -> c.accent to c.onAccent
        ChipKind.PURCHASE -> c.accentSoft to c.onAccentSoft
        ChipKind.TREASURY -> c.divider to c.muted
        ChipKind.DANGER -> c.dangerSoft to c.danger
    }
    Text(text, style = SabouType.label, color = fg, modifier = Modifier.clip(SabouShapes.chip).background(bg).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
fun Banner(text: String, kind: ChipKind = ChipKind.DANGER, @DrawableRes icon: Int = R.drawable.ic_alert) {
    val c = Sabou.colors
    val (bg, fg) = when (kind) {
        ChipKind.PRIMARY, ChipKind.SALES -> c.primarySoft to c.primary
        ChipKind.ACCENT, ChipKind.PURCHASE -> c.accentSoft to c.onAccentSoft
        else -> c.dangerSoft to c.danger
    }
    Row(
        Modifier.fillMaxWidth().clip(SabouShapes.field).background(bg).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, style = SabouType.body, color = fg)
    }
}

@Composable
fun EmptyState(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    SCard {
        Text(text, style = SabouType.body, color = Sabou.colors.muted)
        if (action != null && onAction != null) SecondaryButton(action, onAction)
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Sabou.colors.primary)
    }
}

// ---------------------------------------------------------------- Buttons

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick, enabled = enabled && !busy, shape = SabouShapes.control,
        colors = ButtonDefaults.buttonColors(containerColor = Sabou.colors.primary, contentColor = Sabou.colors.onPrimary),
        modifier = modifier.heightIn(min = 52.dp).fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(color = Sabou.colors.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(text, style = SabouType.section)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = SabouShapes.control,
        border = BorderStroke(1.dp, if (danger) Sabou.colors.danger else Sabou.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Sabou.colors.surface, contentColor = if (danger) Sabou.colors.danger else Sabou.colors.ink),
        modifier = modifier.heightIn(min = 48.dp).fillMaxWidth(),
    ) { Text(text, style = SabouType.bodyStrong) }
}

/** Segmented control (e.g. scope tabs on the treasury page). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sabou.colors.track).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) Sabou.colors.surface else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = if (on) SabouType.bodyStrong else SabouType.body, color = if (on) Sabou.colors.ink else Sabou.colors.muted, maxLines = 1) }
        }
    }
}

// ---------------------------------------------------------------- Inputs

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Sabou.colors.primary, unfocusedBorderColor = Sabou.colors.borderStrong,
    focusedContainerColor = Sabou.colors.field, unfocusedContainerColor = Sabou.colors.field,
    focusedTextColor = Sabou.colors.ink, unfocusedTextColor = Sabou.colors.ink, cursorColor = Sabou.colors.primary,
    errorBorderColor = Sabou.colors.danger,
)

@Composable
fun TextInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    error: String? = null,
    singleLine: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = SabouType.caption.copy(fontSize = SabouType.body.fontSize * 0.93f), color = Sabou.colors.muted)
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = singleLine, isError = error != null,
            textStyle = SabouType.bodyStrong.copy(color = Sabou.colors.ink),
            placeholder = if (placeholder != null) { { Text(placeholder, style = SabouType.body, color = Sabou.colors.subtle) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            shape = SabouShapes.field, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) Text(error, style = SabouType.caption, color = Sabou.colors.danger)
    }
}

/**
 * Amount in Toman. Accepts Persian/Arabic/Latin digits, regroups with "٬" as the user types and
 * reports the parsed value in Rial (null when empty or invalid).
 */
@Composable
fun MoneyInput(label: String, value: Money?, onChange: (Money?) -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    fun shown(v: Money?) = v?.let { Fa.number(it.rial / 10) } ?: ""
    var field by remember { mutableStateOf(TextFieldValue(shown(value))) }
    // The form may reset the amount (e.g. after saving): follow it instead of keeping stale text.
    androidx.compose.runtime.LaunchedEffect(value) {
        if (value == null && field.text.isNotEmpty() && Fa.parseToman(field.text) != null) field = TextFieldValue("")
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label (تومان)", style = SabouType.caption.copy(fontSize = SabouType.body.fontSize * 0.93f), color = Sabou.colors.muted)
        OutlinedTextField(
            value = field,
            onValueChange = { next ->
                val parsed = Fa.parseLong(next.text)
                val text = if (next.text.isBlank()) "" else parsed?.let(Fa::number) ?: field.text
                field = TextFieldValue(text, TextRange(text.length))
                onChange(if (text.isEmpty()) null else Fa.parseToman(text))
            },
            singleLine = true,
            textStyle = SabouType.amount.copy(color = Sabou.colors.ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = SabouShapes.field, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        if (hint != null) Text(hint, style = SabouType.caption, color = Sabou.colors.muted)
    }
}

/** Decimal quantity (e.g. kilograms). */
@Composable
fun QuantityInput(
    label: String,
    unit: String,
    onChange: (ir.sabou.kernel.Quantity?) -> Unit,
    modifier: Modifier = Modifier,
    /** What an empty field means: by default "not entered" (null), never zero (e.g. a stock count). */
    blankAs: ir.sabou.kernel.Quantity? = null,
    /** The form's current value, shown when the field is (re)created, e.g. a restored row of a list. */
    value: ir.sabou.kernel.Quantity? = null,
) {
    // Kept with the form across process death; the parent's value is restored from the same draft.
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(value?.let(Fa::quantity) ?: "") }
    TextInput("$label ($unit)", text, { text = it; onChange(if (it.isBlank()) blankAs else Fa.parseQuantity(it)) }, modifier, keyboard = KeyboardType.Decimal,
        error = if (text.isNotBlank() && Fa.parseQuantity(text) == null) "مقدار معتبر نیست" else null)
}

/**
 * Quantity of a known [item], typed in any of its units — its own unit, a metric sibling (گرم for a کیلوگرم item)
 * or one of its purchase packs. Reports the quantity in the item's stock unit (converted by Units, the one place
 * units change); [value] is in the stock unit too.
 */
@Composable
fun ItemQuantityInput(
    label: String,
    item: ir.sabou.inventory.Item?,
    onChange: (ir.sabou.kernel.Quantity?) -> Unit,
    modifier: Modifier = Modifier,
    blankAs: ir.sabou.kernel.Quantity? = null,
    value: ir.sabou.kernel.Quantity? = null,
) {
    val choices = item?.let(ir.sabou.inventory.Units::choices) ?: listOf(ir.sabou.inventory.EntryUnit.Stock)
    // The unit choice belongs to the item (its packs); the typed text stays when another item is picked.
    var selectedKey by androidx.compose.runtime.saveable.rememberSaveable(item?.id) { mutableStateOf(unitKey(ir.sabou.inventory.EntryUnit.Stock)) }
    val unit = choices.firstOrNull { unitKey(it) == selectedKey } ?: ir.sabou.inventory.EntryUnit.Stock
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(value?.let(Fa::quantity) ?: "") }
    fun stock(t: String, u: ir.sabou.inventory.EntryUnit): ir.sabou.kernel.Quantity? =
        if (t.isBlank()) blankAs
        else Fa.parseQuantity(t)?.let { typed -> if (item == null) typed else runCatching { ir.sabou.inventory.Units.toStock(item, typed, u) }.getOrNull() }
    // A different item means a different stock unit and packs: report the text again, read in the new item's unit.
    var reportedFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(item?.id?.value) }
    if (reportedFor != item?.id?.value) {
        reportedFor = item?.id?.value
        androidx.compose.runtime.SideEffect { onChange(stock(text, ir.sabou.inventory.EntryUnit.Stock)) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (item != null && choices.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { c ->
                    val selected = unitKey(c) == selectedKey
                    Text(
                        unitLabel(item, c), style = SabouType.label,
                        color = if (selected) Sabou.colors.onPrimary else Sabou.colors.ink,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) Sabou.colors.primary else Sabou.colors.track)
                            .clickable { selectedKey = unitKey(c); onChange(stock(text, c)) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        val converted = stock(text, unit)
        val unitText = item?.let { unitLabel(it, unit) }
        TextInput(if (unitText == null) label else "$label ($unitText)", text, { text = it; onChange(stock(it, unit)) }, keyboard = KeyboardType.Decimal,
            error = if (text.isNotBlank() && converted == null) "مقدار معتبر نیست" else null)
        if (item != null && unit != ir.sabou.inventory.EntryUnit.Stock && text.isNotBlank() && converted != null) {
            Text("= ${Fa.quantity(converted)} ${ir.sabou.app.ui.screens.unitName(item.unit)}", style = SabouType.caption, color = Sabou.colors.muted)
        }
    }
}

private fun unitKey(u: ir.sabou.inventory.EntryUnit): String = when (u) {
    ir.sabou.inventory.EntryUnit.Stock -> "S"
    is ir.sabou.inventory.EntryUnit.Metric -> "M:" + u.unit.name
    is ir.sabou.inventory.EntryUnit.Pack -> "P:" + u.name
}

private fun unitLabel(item: ir.sabou.inventory.Item, u: ir.sabou.inventory.EntryUnit): String = when (u) {
    ir.sabou.inventory.EntryUnit.Stock -> ir.sabou.app.ui.screens.unitName(item.unit)
    is ir.sabou.inventory.EntryUnit.Metric -> ir.sabou.app.ui.screens.unitName(u.unit)
    is ir.sabou.inventory.EntryUnit.Pack -> u.name
}

/** Jalali date field: opens a month calendar; quick choices for today and yesterday. */
@Composable
fun DateInput(label: String, date: BusinessDate, onChange: (BusinessDate) -> Unit, today: BusinessDate) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = SabouType.caption.copy(fontSize = SabouType.body.fontSize * 0.93f), color = Sabou.colors.muted)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(SabouShapes.field).background(Sabou.colors.field)
                .border(1.dp, Sabou.colors.borderStrong, SabouShapes.field).clickable(role = Role.Button, onClickLabel = "انتخاب تاریخ") { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Fa.date(date), style = SabouType.bodyStrong, color = Sabou.colors.ink)
            Spacer(Modifier.width(10.dp))
            Text(Fa.weekday(date), style = SabouType.caption, color = Sabou.colors.muted, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_calendar), contentDescription = "تقویم", tint = Sabou.colors.primary, modifier = Modifier.size(20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("امروز" to today, "دیروز" to today.plusDays(-1)).forEach { (name, d) ->
                Box(Modifier.clip(SabouShapes.chip).background(if (d == date) Sabou.colors.primarySoft else Sabou.colors.track).clickable { onChange(d) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(name, style = SabouType.label, color = if (d == date) Sabou.colors.primary else Sabou.colors.muted)
                }
            }
        }
    }
    if (open) JalaliCalendarDialog(date, today, onPick = { onChange(it); open = false }, onDismiss = { open = false })
}

private val weekdayInitials = listOf("ش", "ی", "د", "س", "چ", "پ", "ج")

/** A Persian month view (weeks start on Saturday); arrows move by month, the title row by year. */
@Composable
fun JalaliCalendarDialog(selected: BusinessDate, today: BusinessDate, onPick: (BusinessDate) -> Unit, onDismiss: () -> Unit) {
    val start = Fa.jalali(selected)
    var year by remember { mutableStateOf(start.year) }
    var month by remember { mutableStateOf(start.month) }
    fun shift(months: Int) {
        val index = year * 12 + (month - 1) + months
        year = Math.floorDiv(index, 12); month = Math.floorMod(index, 12) + 1
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(today) }) { Text("امروز", style = SabouType.bodyStrong, color = Sabou.colors.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف", style = SabouType.bodyStrong, color = Sabou.colors.muted) } },
        containerColor = Sabou.colors.surface,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Year row
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CalendarArrow(R.drawable.ic_chevron_right, "سال قبل") { shift(-12) }
                    Text(Fa.digits(year.toString()), style = SabouType.label, color = Sabou.colors.muted,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    CalendarArrow(R.drawable.ic_chevron_left, "سال بعد") { shift(12) }
                }
                // Month row
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CalendarArrow(R.drawable.ic_chevron_right, "ماه قبل") { shift(-1) }
                    Text(Fa.monthNames[month - 1], style = SabouType.section, color = Sabou.colors.ink,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    CalendarArrow(R.drawable.ic_chevron_left, "ماه بعد") { shift(1) }
                }
                Row(Modifier.fillMaxWidth()) {
                    weekdayInitials.forEach {
                        Text(it, style = SabouType.caption, color = Sabou.colors.muted, modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                val first = Fa.fromJalali(year, month, 1)
                val offset = Fa.weekdayIndex(first)          // 0 = Saturday
                val days = Fa.monthLength(year, month)
                val cells = ((offset + days + 6) / 7) * 7
                (0 until cells step 7).forEach { rowStart ->
                    Row(Modifier.fillMaxWidth()) {
                        (rowStart until rowStart + 7).forEach { cell ->
                            val day = cell - offset + 1
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                if (day in 1..days) {
                                    val d = first.plusDays((day - 1).toLong())
                                    val isSelected = d == selected
                                    val isToday = d == today
                                    Box(
                                        Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(if (isSelected) Sabou.colors.primary else Color.Transparent)
                                            .then(if (isToday && !isSelected) Modifier.border(1.dp, Sabou.colors.primary, androidx.compose.foundation.shape.CircleShape) else Modifier)
                                            .clickable(role = Role.Button) { onPick(d) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(Fa.digits(day.toString()), style = SabouType.bodyStrong,
                                            color = when {
                                                isSelected -> Sabou.colors.surface
                                                cell % 7 == 6 -> Sabou.colors.danger   // Friday
                                                else -> Sabou.colors.ink
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun CalendarArrow(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = description, tint = Sabou.colors.ink, modifier = Modifier.size(20.dp))
    }
}

data class Choice<T>(val value: T, val label: String, val detail: String? = null)

/** A field that opens a list to choose from. */
@Composable
fun <T> Picker(label: String, choices: List<Choice<T>>, selected: T?, onSelect: (T) -> Unit, placeholder: String = "انتخاب کنید") {
    var open by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.value == selected }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = SabouType.caption.copy(fontSize = SabouType.body.fontSize * 0.93f), color = Sabou.colors.muted)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(SabouShapes.field).background(Sabou.colors.field)
                .border(1.dp, Sabou.colors.borderStrong, SabouShapes.field).clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current?.label ?: placeholder, style = if (current != null) SabouType.bodyStrong else SabouType.body,
                color = if (current != null) Sabou.colors.ink else Sabou.colors.subtle, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = Sabou.colors.muted, modifier = Modifier.size(18.dp))
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("بستن", style = SabouType.bodyStrong, color = Sabou.colors.primary) } },
            title = { Text(label, style = SabouType.section) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (choices.isEmpty()) item { Text("موردی تعریف نشده است.", style = SabouType.body, color = Sabou.colors.muted) }
                    items(choices) { c ->
                        Column(
                            Modifier.fillMaxWidth().clip(SabouShapes.field)
                                .background(if (c.value == selected) Sabou.colors.primarySoft else Color.Transparent)
                                .clickable { onSelect(c.value); open = false }.padding(12.dp),
                        ) {
                            Text(c.label, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            if (c.detail != null) Text(c.detail, style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                }
            },
            containerColor = Sabou.colors.surface,
        )
    }
}

/** Confirmation dialog for irreversible or important actions. */
@Composable
fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, style = SabouType.bodyStrong, color = if (danger) Sabou.colors.danger else Sabou.colors.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف", style = SabouType.bodyStrong, color = Sabou.colors.muted) } },
        title = { Text(title, style = SabouType.section) },
        text = { Text(text, style = SabouType.body) },
        containerColor = Sabou.colors.surface,
    )
}

@Composable
fun FormCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    SCard {
        if (title != null) Text(title, style = SabouType.section, color = Sabou.colors.ink)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
