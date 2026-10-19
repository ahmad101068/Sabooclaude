package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.Segmented
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.rememberCommandId
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Assessments
import ir.sabou.core.Fa
import ir.sabou.core.FieldRecord
import ir.sabou.core.RecordField
import ir.sabou.core.VoidFieldRecord
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope

/**
 * ADR-0062: the facts the scorecard needs and the books do not hold, each in under a minute — fridge and freezer
 * temperatures, customer feedback, campaigns, breakdowns, portion weights and permits.
 */
object FieldRecordScreens {

    @Composable
    fun Records(nav: Nav) {
        val session = LocalSession.current
        val kinds = FieldRecord.Kind.entries.filter { session.can(it.permission) }
        var kind by rememberSaveable { mutableStateOf(kinds.firstOrNull() ?: FieldRecord.Kind.TEMPERATURE) }
        Column(Modifier.fillMaxSize()) {
            Header("ثبت‌های روزانه", "برای کارنامه", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                Page {
                    if (kinds.isEmpty()) { Banner("نقش شما ثبتی در این بخش ندارد.", ChipKind.ACCENT); return@Page }
                    Picker("چه چیزی ثبت می‌کنید؟", kinds.map { Choice(it, it.title) }, kind, { kind = it })
                    Text(help(kind), style = SabouType.caption, color = Sabou.colors.muted)
                    val data by load(session, branch, kind) { assessments.records(branch, kind).sortedByDescending { it.date.epochDay }.take(30) }
                    // The names used before, one tap away: the same fridge stays one fridge.
                    val known = data.orNull().orEmpty().filter { !it.voided && it.subject.isNotBlank() }.map { it.subject }.distinct().take(6)
                    Form(branch, kind, known)
                    SectionTitle("ثبت‌های اخیر")
                    Loaded(data) { list ->
                        if (list.isEmpty()) Text("هنوز چیزی ثبت نشده.", style = SabouType.body, color = Sabou.colors.muted)
                        list.forEach { r -> RecordRow(branch, r) }
                    }
                }
            }
        }
    }

    private fun help(kind: FieldRecord.Kind) = when (kind) {
        FieldRecord.Kind.TEMPERATURE -> "روزی یک بار، هر یخچال و فریزر. یخچال باید بین ۱- و ۵ درجه و فریزر ۱۸- درجه یا سردتر باشد."
        FieldRecord.Kind.FEEDBACK -> "هر نظر مشتری با امتیاز ۱ (خیلی ناراضی) تا ۵ (خیلی راضی)."
        FieldRecord.Kind.CAMPAIGN -> "هر تبلیغ یا تخفیف ویژه با روزهای اجرا و هزینه‌اش؛ کارنامه فروش آن روزها را با پیش از آن مقایسه می‌کند."
        FieldRecord.Kind.BREAKDOWN -> "هر خرابی دستگاه، با هزینه‌ی تعمیر و مدتی که از کار افتاد."
        FieldRecord.Kind.PORTION -> "هفته‌ای چند پرس را وزن کنید: وزن رسپی و وزن واقعی، به گرم."
        FieldRecord.Kind.PERMIT -> "پروانه‌ی کسب و هر مجوز دیگر با تاریخ پایان اعتبارش؛ تمدید را با ثبت تازه وارد کنید."
    }

    private fun number(text: String): Long? = Fa.latinDigits(text.trim()).replace("٬", "").replace(",", "").toLongOrNull()

    /** Tenths of a degree from «۴٫۵» or «-۱۸». */
    private fun tenths(text: String): Long? = Fa.latinDigits(text.trim()).replace("٫", ".").replace("/", ".").replace("−", "-").toBigDecimalOrNull()
        ?.movePointRight(1)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()

    @Composable
    private fun Form(branch: Scope.Branch, kind: FieldRecord.Kind, known: List<String>) {
        val session = LocalSession.current
        val action = rememberAction()
        val id = rememberCommandId(kind)
        var date by rememberSaveable(kind) { mutableStateOf(session.today) }
        var end by rememberSaveable(kind) { mutableStateOf(session.today.plusDays(if (kind == FieldRecord.Kind.PERMIT) 365 else 6)) }
        var subject by rememberSaveable(kind) { mutableStateOf("") }
        var a by rememberSaveable(kind) { mutableStateOf("") }
        var b by rememberSaveable(kind) { mutableStateOf("") }
        var freezer by rememberSaveable(kind) { mutableStateOf(0) }
        var rating by rememberSaveable(kind) { mutableStateOf(4) }
        var note by rememberSaveable(kind) { mutableStateOf("") }
        SCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateInput(if (kind == FieldRecord.Kind.CAMPAIGN) "شروع" else "تاریخ", date, { date = it }, session.today)
                if (known.isNotEmpty() && kind != FieldRecord.Kind.FEEDBACK) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    known.forEach { k -> Text(k, style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable { subject = k }) }
                }
                val command: RecordField? = when (kind) {
                    FieldRecord.Kind.TEMPERATURE -> {
                        Segmented(listOf("یخچال", "فریزر"), freezer, { freezer = it })
                        TextInput("نام دستگاه (مثلاً یخچال آشپزخانه)", subject, { subject = it })
                        TextInput("دما (درجه، مثلاً ۴ یا ۱۸-)", a, { a = it }, keyboard = KeyboardType.Text)
                        tenths(a)?.let { RecordField(id.value, branch, kind, date, subject, it, freezer.toLong(), note = note) }
                    }
                    FieldRecord.Kind.FEEDBACK -> {
                        Segmented(listOf("۱", "۲", "۳", "۴", "۵"), rating - 1, { rating = it + 1 })
                        RecordField(id.value, branch, kind, date, "", rating.toLong(), note = note)
                    }
                    FieldRecord.Kind.CAMPAIGN -> {
                        DateInput("پایان", end, { end = it }, session.today)
                        TextInput("نام کمپین", subject, { subject = it })
                        TextInput("هزینه (ریال)", a, { a = it }, keyboard = KeyboardType.Number)
                        RecordField(id.value, branch, kind, date, subject, number(a) ?: 0, endDate = end, note = note)
                    }
                    FieldRecord.Kind.BREAKDOWN -> {
                        TextInput("دستگاه", subject, { subject = it })
                        TextInput("هزینه‌ی تعمیر (ریال، اختیاری)", a, { a = it }, keyboard = KeyboardType.Number)
                        TextInput("مدت از کار افتادن (دقیقه، اختیاری)", b, { b = it }, keyboard = KeyboardType.Number)
                        RecordField(id.value, branch, kind, date, subject, number(a), number(b), note = note)
                    }
                    FieldRecord.Kind.PORTION -> {
                        TextInput("چه چیزی (مثلاً گوشت کباب برگ)", subject, { subject = it })
                        TextInput("وزن در رسپی (گرم)", a, { a = it }, keyboard = KeyboardType.Number)
                        TextInput("وزن واقعی (گرم)", b, { b = it }, keyboard = KeyboardType.Number)
                        if (number(a) != null && number(b) != null) RecordField(id.value, branch, kind, date, subject, number(a), number(b), note = note) else null
                    }
                    FieldRecord.Kind.PERMIT -> {
                        TextInput("نام مجوز (مثلاً پروانه‌ی کسب)", subject, { subject = it })
                        DateInput("اعتبار تا", end, { end = it }, session.today)
                        RecordField(id.value, branch, kind, date, subject, null, endDate = end, note = note)
                    }
                }
                TextInput("توضیح (اختیاری)", note, { note = it }, singleLine = kind != FieldRecord.Kind.FEEDBACK)
                action.error?.let { Banner(it) }
                PrimaryButton("ثبت", {
                    command?.let { c -> action.run({ assessments.record(c) }) {
                        // Temperatures and portions are taken of the same things every day: keep the name.
                        if (kind != FieldRecord.Kind.TEMPERATURE && kind != FieldRecord.Kind.PORTION) subject = ""
                        a = ""; b = ""; note = ""; id.value = GlobalId.new()
                    } }
                }, Modifier.fillMaxWidth(), enabled = command != null && !action.busy, busy = action.busy)
            }
        }
    }

    @Composable
    private fun RecordRow(branch: Scope.Branch, r: FieldRecord) {
        val action = rememberAction()
        var voiding by rememberSaveable(r.id.value) { mutableStateOf(false) }
        var reason by rememberSaveable(r.id.value) { mutableStateOf("") }
        val id = rememberCommandId(r.id.value)
        SCard {
            KeyValue(title(r), Fa.date(r.date), if (r.unsafe) Sabou.colors.danger else if (r.voided) Sabou.colors.muted else Sabou.colors.ink)
            Text(listOfNotNull(r.by, r.note.takeIf { it.isNotBlank() }, if (r.voided) "باطل شده" else null).joinToString(" · "),
                style = SabouType.caption, color = Sabou.colors.muted)
            if (!r.voided) {
                if (!voiding) Text("باطل کردن", style = SabouType.label, color = Sabou.colors.danger,
                    modifier = Modifier.clickable { voiding = true })
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Divider()
                    TextInput("دلیل", reason, { reason = it })
                    action.error?.let { Banner(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("باطل کن", { action.run({ assessments.voidRecord(VoidFieldRecord(id.value, branch, r.id, r.kind, reason)) }) },
                            Modifier.weight(1f), enabled = reason.isNotBlank() && !action.busy, danger = true)
                        SecondaryButton("انصراف", { voiding = false }, Modifier.weight(1f))
                    }
                }
            }
        }
    }

    private fun title(r: FieldRecord): String = when (r.kind) {
        FieldRecord.Kind.TEMPERATURE -> "${r.subject}: ${Assessments.celsius(r.value ?: 0)}" + if (r.unsafe) " (خارج از محدوده)" else ""
        FieldRecord.Kind.FEEDBACK -> "امتیاز ${Fa.number(r.value ?: 0)} از ۵"
        FieldRecord.Kind.CAMPAIGN -> "${r.subject} تا ${r.endDate?.let(Fa::date) ?: "—"} · ${Fa.rial(r.value ?: 0)} ریال"
        FieldRecord.Kind.BREAKDOWN -> r.subject + (r.value?.let { " · ${Fa.rial(it)} ریال" } ?: "") + (r.value2?.let { " · ${Fa.number(it)} دقیقه" } ?: "")
        FieldRecord.Kind.PORTION -> "${r.subject}: رسپی ${Fa.number(r.value ?: 0)} گرم، واقعی ${Fa.number(r.value2 ?: 0)} گرم"
        FieldRecord.Kind.PERMIT -> "${r.subject}: اعتبار تا ${r.endDate?.let(Fa::date) ?: "—"}"
    }
}
