package ir.sabou.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.export.ChequePrint
import ir.sabou.app.export.ChequeTemplates
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where each line goes on a bank's cheque leaf (ADR-0049): a test print on plain paper shows the leaf's outline, a
 * millimetre ruler and each field's name and position; hold it against a real leaf and move what is off.
 */
object ChequePrintScreens {
    @Composable
    fun Setup(nav: Nav, bank: String) {
        val context = LocalContext.current
        val session = LocalSession.current
        var template by remember { mutableStateOf(ChequeTemplates.forBank(context, bank)) }
        var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        val scope = rememberCoroutineScope()
        val testPrint = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val t = template
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        (context.contentResolver.openOutputStream(uri, "w") ?: error("EXPORT_TARGET_UNAVAILABLE")).use {
                            ChequePrint.write(context, ChequePrint.Fields(session.today, 125_500_000, "نمونه‌ی نام گیرنده"), it, t, guides = true)
                        }
                    }
                }
                message = if (r.isSuccess) "فایل آزمایشی ساخته شد. با «اندازه‌ی واقعی» (بدون بزرگ‌نمایی) روی کاغذ چاپ کنید." to true else "فایل ساخته نشد." to false
            }
        }

        Column(Modifier.fillMaxSize()) {
            Header("محل چاپ چک", bank.ifBlank { "پیش‌فرض" }, onBack = nav.back)
            Page {
                Text("۱. چاپ آزمایشی را روی کاغذ معمولی و با اندازه‌ی واقعی بگیرید و پشت یک برگ چک بگذارید (رو به نور).\n" +
                    "۲. اگر همه‌ی نوشته‌ها با هم جابه‌جا هستند، «جابه‌جایی کل» را تغییر دهید؛ اگر فقط یک خط، عددهای همان خط را.\n" +
                    "۳. عددها میلی‌متر هستند و از گوشه‌ی بالا-راست برگ اندازه گرفته می‌شوند: «از راست» و «از بالا».",
                    style = SabouType.caption, color = Sabou.colors.muted)
                FormCard("جابه‌جایی کل") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("بالا", { template = template.copy(offsetY = template.offsetY - 1f) }, Modifier.weight(1f))
                        SecondaryButton("پایین", { template = template.copy(offsetY = template.offsetY + 1f) }, Modifier.weight(1f))
                        SecondaryButton("راست", { template = template.copy(offsetX = template.offsetX - 1f) }, Modifier.weight(1f))
                        SecondaryButton("چپ", { template = template.copy(offsetX = template.offsetX + 1f) }, Modifier.weight(1f))
                    }
                    Text("هر بار یک میلی‌متر · اکنون ${mm(template.offsetX)} به چپ و ${mm(template.offsetY)} به پایین", style = SabouType.caption, color = Sabou.colors.muted)
                }
                FormCard("خط‌ها") {
                    ChequePrint.Field.entries.forEach { f ->
                        val (x, y) = f.of(template)
                        Text(f.title, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MmInput("از راست", x, { template = f.set(template, it to y) }, Modifier.weight(1f))
                            MmInput("از بالا", y, { template = f.set(template, x to it) }, Modifier.weight(1f))
                        }
                    }
                }
                FormCard("برگ و نوشته") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MmInput("پهنای برگ", template.widthMm, { template = template.copy(widthMm = it) }, Modifier.weight(1f), 100f..250f)
                        MmInput("ارتفاع برگ", template.heightMm, { template = template.copy(heightMm = it) }, Modifier.weight(1f), 50f..150f)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MmInput("طول خط", template.lineWidthMm, { template = template.copy(lineWidthMm = it) }, Modifier.weight(1f), 30f..200f)
                        MmInput("اندازه‌ی قلم (pt)", template.fontPt, { template = template.copy(fontPt = it) }, Modifier.weight(1f), 6f..16f)
                    }
                }
                message?.let { (text, ok) -> Banner(text, if (ok) ChipKind.PRIMARY else ChipKind.DANGER, if (ok) R.drawable.ic_check else R.drawable.ic_alert) }
                SecondaryButton("چاپ آزمایشی روی کاغذ (PDF)", { testPrint.launch("cheque-test.pdf") })
                PrimaryButton("ذخیره برای «${bank.ifBlank { "پیش‌فرض" }}»", {
                    ChequeTemplates.save(context, bank, template)
                    message = "ذخیره شد؛ چک‌های این بانک با همین محل‌ها چاپ می‌شوند." to true
                })
                if (bank.isNotBlank()) SecondaryButton("ذخیره به‌عنوان پیش‌فرض بانک‌های دیگر", {
                    ChequeTemplates.save(context, "", template)
                    message = "پیش‌فرض ذخیره شد." to true
                })
                SecondaryButton("بازگشت به محل‌های اولیه", {
                    ChequeTemplates.reset(context, bank)
                    template = ChequeTemplates.forBank(context, bank)
                    message = "محل‌های اولیه برگشت." to true
                }, danger = true)
            }
        }
    }

    private fun mm(v: Float) = Fa.digits(if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v))

    /** Millimetres with one decimal; values outside [range] are not applied. */
    @Composable
    private fun MmInput(label: String, value: Float, onValue: (Float) -> Unit, modifier: Modifier, range: ClosedFloatingPointRange<Float> = 0f..250f) {
        fun parse(t: String) = Fa.latinDigits(t.trim()).replace('٫', '.').replace('/', '.').toFloatOrNull()
        var text by remember { mutableStateOf(mm(value)) }
        // Changed from outside (reset): show the new value; typing «12.» keeps what is typed.
        androidx.compose.runtime.LaunchedEffect(value) { if (parse(text) != value) text = mm(value) }
        val parsed = parse(text)
        TextInput(label, text, {
            text = it
            parse(it)?.takeIf { v -> v in range }?.let(onValue)
        }, modifier, keyboard = KeyboardType.Decimal, error = if (parsed == null || parsed !in range) "نامعتبر" else null)
    }
}
