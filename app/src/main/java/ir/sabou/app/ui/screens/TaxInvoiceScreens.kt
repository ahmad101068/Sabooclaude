package ir.sabou.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ir.sabou.app.R
import ir.sabou.app.data.MoadianLink
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Confirm
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.Segmented
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.TaxSendReport
import ir.sabou.kernel.GlobalId
import ir.sabou.sales.CancelTaxInvoice
import ir.sabou.sales.IssueTaxInvoice
import ir.sabou.sales.TaxInvoiceKind
import ir.sabou.sales.TaxInvoiceStatus

/**
 * Electronic invoices with the buyer's details and sending them to the tax system (ADR-0047). Owner only
 * ([ir.sabou.platform.Permission.TAX_SETTINGS]); sending is off until the owner connects the taxpayer's key.
 */
object TaxInvoiceScreens {
    @Composable
    fun Invoices(nav: Nav) {
        val session = LocalSession.current
        val context = LocalContext.current
        val data by load(session) { moadian.candidates() to moadian.invoices() }
        val action = rememberAction()
        var report by remember { mutableStateOf<String?>(null) }
        var cancelling by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        val connected = remember(session.version) { MoadianLink.settings(context).enabled }

        fun describe(r: TaxSendReport) = listOfNotNull(
            r.sent.takeIf { it > 0 }?.let { "${Fa.number(it.toLong())} صورتحساب فرستاده شد" },
            r.accepted.takeIf { it > 0 }?.let { "${Fa.number(it.toLong())} پذیرفته شد" },
            r.rejected.takeIf { it > 0 }?.let { "${Fa.number(it.toLong())} رد شد" },
            r.waiting.takeIf { it > 0 }?.let { "${Fa.number(it.toLong())} هنوز در حال بررسی است" },
        ).joinToString("، ").ifEmpty { "چیزی برای ارسال یا استعلام نبود." } + r.problems.joinToString("") { "\n• $it" }

        Column(Modifier.fillMaxSize()) {
            Header("صورتحساب الکترونیکی", "با مشخصات خریدار؛ ارسال به سامانه‌ی مودیان", onBack = nav.back)
            Page {
                Text("برای فروشی که (تمام یا بخشی از آن) نسیه‌ی مشتری دارای کد ملی/شناسه‌ی ملی است، صورتحساب نوع اول صادر می‌شود. صورتحساب پس از صدور تغییر نمی‌کند؛ برای اصلاح، ابطال و دوباره صادر کنید.",
                    style = SabouType.caption, color = Sabou.colors.muted)
                ConnectionCard()
                action.error?.let { Banner(it) }
                report?.let { Banner(it, ChipKind.ACCENT, R.drawable.ic_alert) }
                Loaded(data) { (candidates, invoices) ->
                    SCard {
                        Text("فروش‌های آماده‌ی صدور", style = SabouType.section, color = Sabou.colors.ink)
                        if (candidates.isEmpty()) EmptyState("فروش نسیه‌ای به مشتری دارای شناسه نیست.")
                        candidates.take(30).forEach { c ->
                            KeyValue("${c.customer.name} · ${Fa.date(c.sale.date)}", Fa.rial(c.sale.payable))
                            Text(c.branch, style = SabouType.caption, color = Sabou.colors.muted)
                            SecondaryButton("صدور صورتحساب", {
                                action.run({ taxInvoices.issue(IssueTaxInvoice(GlobalId.new(), c.sale.scope, c.sale.id, System.currentTimeMillis())) }) { report = "صورتحساب صادر شد." }
                            }, enabled = !action.busy)
                            Divider()
                        }
                    }
                    SCard {
                        Text("صورتحساب‌ها", style = SabouType.section, color = Sabou.colors.ink)
                        if (invoices.isEmpty()) EmptyState("هنوز صورتحسابی صادر نشده است.")
                        val ready = invoices.count { it.invoice.status == TaxInvoiceStatus.READY }
                        val waiting = invoices.count { it.invoice.status == TaxInvoiceStatus.SENT }
                        if (ready > 0) PrimaryButton("ارسال ${Fa.number(ready.toLong())} صورتحساب به سامانه", {
                            report = null
                            action.run({
                                runCatching { moadian.send(MoadianLink.client(context, this)) }.fold({ describe(it) }, { MoadianLink.problem(it) })
                            }) { report = it }
                        }, enabled = connected && !action.busy, busy = action.busy)
                        if (waiting > 0) SecondaryButton("استعلام نتیجه (${Fa.number(waiting.toLong())})", {
                            report = null
                            action.run({
                                runCatching { moadian.inquire(MoadianLink.client(context, this)) }.fold({ describe(it) }, { MoadianLink.problem(it) })
                            }) { report = it }
                        }, enabled = connected && !action.busy)
                        if ((ready > 0 || waiting > 0) && !connected) Text("برای ارسال، اتصال به سامانه را بالا روشن کنید.", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                        invoices.forEach { row ->
                            val inv = row.invoice
                            KeyValue((if (inv.kind == TaxInvoiceKind.CANCELLATION) "ابطال · " else "") + "${row.customer} · ${Fa.date(inv.date)}", Fa.rial(inv.total))
                            Text("شماره‌ی مالیاتی ${inv.taxId}" + (inv.referenceNumber?.let { "\nپیگیری $it" } ?: ""), style = SabouType.caption, color = Sabou.colors.muted)
                            when (inv.status) {
                                TaxInvoiceStatus.READY -> Chip("صادرشده؛ ارسال نشده", ChipKind.ACCENT)
                                TaxInvoiceStatus.SENT -> Chip("فرستاده شد؛ منتظر نتیجه", ChipKind.ACCENT)
                                TaxInvoiceStatus.ACCEPTED -> Chip("پذیرفته شد", ChipKind.PRIMARY)
                                TaxInvoiceStatus.REJECTED -> Chip("رد شد", ChipKind.DANGER)
                                TaxInvoiceStatus.CANCELLED -> Chip("ابطال شد", ChipKind.NEUTRAL)
                                TaxInvoiceStatus.WITHDRAWN -> Chip("پس گرفته شد", ChipKind.NEUTRAL)
                            }
                            inv.messages.forEach { Text("• $it", style = SabouType.caption, color = if (inv.status == TaxInvoiceStatus.REJECTED) Sabou.colors.danger else Sabou.colors.muted) }
                            // Never sent: it can be taken back (before reversing its sale, or to issue it anew).
                            if (inv.status == TaxInvoiceStatus.READY) {
                                SecondaryButton("پس گرفتن (ارسال نشده)", { action.run({ taxInvoices.withdraw(ir.sabou.sales.WithdrawTaxInvoice(GlobalId.new(), inv.scope, inv.id, "پس گرفته شد پیش از ارسال")) }) },
                                    enabled = !action.busy)
                            }
                            if (inv.kind == TaxInvoiceKind.ORIGINAL && inv.status == TaxInvoiceStatus.ACCEPTED &&
                                invoices.none { it.invoice.refTaxId == inv.taxId && it.invoice.status.live }) {
                                SecondaryButton("ابطال", { cancelling = inv.id }, danger = true, enabled = !action.busy)
                            }
                            Divider()
                        }
                        cancelling?.let { target ->
                            val inv = invoices.firstOrNull { it.invoice.id == target }?.invoice
                            if (inv != null) Confirm("ابطال صورتحساب؟", "صورتحساب ابطال برای سامانه صادر می‌شود؛ پس از پذیرش، فروش دوباره قابل صدور است.", "صدور ابطال",
                                onConfirm = { cancelling = null; action.run({ taxInvoices.cancel(CancelTaxInvoice(GlobalId.new(), inv.scope, inv.id, System.currentTimeMillis())) }) },
                                onDismiss = { cancelling = null }, danger = true)
                        }
                    }
                }
            }
        }
    }

    /** The taxpayer's key, certificate and the API address; a connection test. */
    @Composable
    private fun ConnectionCard() {
        val context = LocalContext.current
        val saved = remember { MoadianLink.settings(context) }
        var enabled by rememberSaveable { mutableStateOf(saved.enabled) }
        var url by rememberSaveable { mutableStateOf(saved.baseUrl) }
        var key by rememberSaveable { mutableStateOf(saved.keyPem) }
        var cert by rememberSaveable { mutableStateOf(saved.certificatePem) }
        var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        val action = rememberAction()
        fun read(uri: Uri?): String? = uri?.let { u -> runCatching { context.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
        val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> read(u)?.let { key = it } }
        val pickCert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> read(u)?.let { cert = it } }
        FormCard("اتصال به سامانه‌ی مودیان") {
            Text("کلید خصوصی و گواهی امضای «شناسه‌ی یکتای حافظه» را از کارپوشه‌ی سامانه‌ی مودیان (روش ارسال: مستقیم توسط مودی) بسازید و فایل‌ها را اینجا انتخاب کنید. کلید فقط روی همین گوشی و رمزشده می‌ماند و در پشتیبان نمی‌آید.",
                style = SabouType.caption, color = Sabou.colors.muted)
            Banner("این ارسال طبق دستورالعمل فنی منتشرشده و کتابخانه‌های عمومی ساخته شده و هنوز با سامانه‌ی واقعی آزموده نشده است. اولین صورتحساب را پس از ارسال در کارپوشه بررسی کنید.", ChipKind.ACCENT)
            Segmented(listOf("روشن", "خاموش"), if (enabled) 0 else 1, { enabled = it == 0 })
            if (enabled) {
                TextInput("نشانی سامانه", url, { url = it })
                SecondaryButton(if (key.isBlank()) "انتخاب فایل کلید خصوصی (.pem / .key)" else "کلید خصوصی انتخاب شد ✓", { pickKey.launch(arrayOf("*/*")) })
                SecondaryButton(if (cert.isBlank()) "انتخاب فایل گواهی (.crt / .pem)" else "گواهی انتخاب شد ✓", { pickCert.launch(arrayOf("*/*")) })
            }
            message?.let { (text, ok) -> Banner(text, if (ok) ChipKind.PRIMARY else ChipKind.DANGER, if (ok) R.drawable.ic_check else R.drawable.ic_alert) }
            action.error?.let { Banner(it) }
            PrimaryButton("ذخیره", {
                val error = MoadianLink.save(context, MoadianLink.Settings(enabled, url, key, cert))
                message = (error ?: "ذخیره شد.") to (error == null)
            }, enabled = !enabled || (key.isNotBlank() && cert.isNotBlank()))
            if (enabled && saved.enabled) SecondaryButton("آزمون اتصال", {
                message = null
                action.run({
                    runCatching { MoadianLink.client(context, this).fiscalInformation() }.fold(
                        { info -> "اتصال برقرار است." + listOfNotNull(info["nameTrade"], info["fiscalStatus"], info["economicCode"]).joinToString(" · ", " ") to true },
                        { MoadianLink.problem(it) to false },
                    )
                }) { message = it }
            }, enabled = !action.busy)
        }
    }
}
