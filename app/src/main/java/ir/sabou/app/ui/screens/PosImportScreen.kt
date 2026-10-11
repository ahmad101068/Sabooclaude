package ir.sabou.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ir.sabou.app.R
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.Segmented
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.PosAccounts
import ir.sabou.core.PosChoices
import ir.sabou.core.PosFile
import ir.sabou.kernel.GlobalId
import ir.sabou.posimport.FileCurrency
import ir.sabou.treasury.TreasuryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Import a day of the point-of-sale system (Sepand) from its Excel/CSV exports (ADR-0026): pick the «sales by
 * item» report (and the «invoices» report for payments), resolve what the files leave open, check, record.
 */
@Composable
fun PosImportScreen(nav: Nav) {
    val session = LocalSession.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<PosFile>>(emptyList()) }
    var readError by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(session.today) }
    var dateChosen by remember { mutableStateOf(false) }
    var currency by remember { mutableStateOf<FileCurrency?>(null) }
    val matches = remember { mutableStateMapOf<String, GlobalId>() }
    val customers = remember { mutableStateMapOf<String, GlobalId>() }
    var cashAccount by remember { mutableStateOf<GlobalId?>(null) }
    var cardAccount by remember { mutableStateOf<GlobalId?>(null) }
    var single by remember { mutableStateOf<GlobalId?>(null) }
    var kitchen by remember { mutableStateOf<GlobalId?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    val action = rememberAction()
    val commandId = remember { mutableStateOf(GlobalId.new()) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { uris.map { readFile(context, it) } } }
            read.onSuccess { files = it; readError = null; matches.clear(); customers.clear(); done = null; commandId.value = GlobalId.new() }
                .onFailure { readError = "فایل خوانده نشد." }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("ورود فروش از صندوق", "سپند · اکسل یا CSV", onBack = nav.back)
        WithBranch { branch ->
            val choices = PosChoices(if (dateChosen) date else null, currency, matches.toMap(), customers.toMap())
            val preview by load(session, branch, files, choices) { if (files.isEmpty()) null else posImports.preview(branch, files, choices) }
            val lists by load(session, branch) {
                Triple(overview.menu().map { it.item }.filter { it.isActive }, overview.locations(branch).filter { it.isActive }, overview.salesDay(branch, session.today))
            }
            Page {
                done?.let { Banner(it, ChipKind.PRIMARY, R.drawable.ic_check) }
                FormCard("فایل‌ها") {
                    Text("از سپند گزارش «فروش کالا» یک روز را به اکسل بفرستید؛ اگر «ریز فاکتورها» با ستون نقد، کارت و اعتباری را هم بیاورید، تسویه‌ها و مشتریان نسیه هم از آن خوانده می‌شود. " +
                        "پیش از ثبت، جمع‌های خود فایل با سطرها کنترل می‌شود و تا مشکلی مانده ثبتی انجام نمی‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                    SecondaryButton(if (files.isEmpty()) "انتخاب فایل‌ها" else "انتخاب دوباره (${Fa.number(files.size.toLong())} فایل)", { pick.launch(arrayOf("*/*")) })
                    readError?.let { Banner(it) }
                }
                val p = preview.orNull()
                val (menu, kitchens, day) = lists.orNull() ?: Triple(emptyList(), emptyList(), null)
                if (p != null) {
                    SCard { p.files.forEach { Text(it, style = SabouType.caption, color = Sabou.colors.ink) } }
                    if (!p.dateFromFile || !p.currencyFromFile) FormCard("آنچه فایل نگفته است") {
                        if (!p.dateFromFile) {
                            DateInput("روز فروش", date, { date = it; dateChosen = true }, session.today)
                            if (!dateChosen) SecondaryButton("همین روز درست است", { dateChosen = true })
                        }
                        if (!p.currencyFromFile) {
                            Text("واحد مبالغ فایل", style = SabouType.caption, color = Sabou.colors.muted)
                            Segmented(FileCurrency.entries.map { it.label }, FileCurrency.entries.indexOf(currency), { currency = FileCurrency.entries[it] })
                        }
                    }
                    if (p.unknown.isNotEmpty()) FormCard("نام‌هایی که با منو جور نشد") {
                        Text("هر نام صندوق را به آیتم منوی خودش وصل کنید؛ دفعه‌ی بعد خودکار جور می‌شود. آیتمی که در منو نیست را اول از «منو و رسپی» تعریف کنید.",
                            style = SabouType.caption, color = Sabou.colors.muted)
                        p.unknown.forEach { l -> Picker(l.name, menu.map { Choice(it.id, it.name) }, matches[l.key], { matches[l.key] = it }) }
                    }
                    SCard {
                        KeyValue("فروش ناخالص", Fa.rial(p.gross) + " ریال")
                        if (p.discount != 0L) KeyValue("تخفیف", "− " + Fa.rial(p.discount), Sabou.colors.danger)
                        if (p.service != 0L) KeyValue("سرویس", Fa.rial(p.service))
                        if (p.tax != 0L) KeyValue("مالیات و عوارض", Fa.rial(p.tax))
                        Divider()
                        KeyValue("قابل تسویه (ریال)", Fa.rial(p.payable), strong = true)
                        if (p.hasInvoices) {
                            KeyValue("فاکتور", "${Fa.number(p.bills.toLong())}" + (if (p.returns > 0) " · برگشت ${Fa.number(p.returns.toLong())}" else "") + (if (p.voided > 0) " · ابطال ${Fa.number(p.voided.toLong())} (حساب نمی‌شود)" else ""))
                            if (p.cash != 0L) KeyValue("نقد", Fa.rial(p.cash))
                            if (p.card != 0L) KeyValue("کارت", Fa.rial(p.card))
                            p.credits.forEach { KeyValue("نسیه · ${it.name}", Fa.rial(it.amount)) }
                        }
                    }
                    val accounts = day?.accounts.orEmpty().filter { it.kind.isOrdinary }
                    FormCard("تسویه و انبار") {
                        if (p.hasInvoices) {
                            if (p.cash > 0) Picker("نقد به صندوق", accounts.filter { it.kind == TreasuryKind.CASH || it.kind == TreasuryKind.PETTY_CASH }.map { Choice(it.id, it.name) }, cashAccount, { cashAccount = it })
                            if (p.card > 0) Picker("کارت به حساب", accounts.filter { it.kind == TreasuryKind.CARD_TERMINAL || it.kind == TreasuryKind.BANK }.map { Choice(it.id, it.name) }, cardAccount, { cardAccount = it })
                            p.credits.filter { it.customer == null && it.name.isNotBlank() }.forEach { c ->
                                Picker("مشتری اعتباری «${c.name}»", day?.customers.orEmpty().map { Choice(it.id, it.name) }, customers[c.key], { customers[c.key] = it })
                            }
                        } else {
                            Text("بدون گزارش «ریز فاکتورها»، کل مبلغ در یک حساب دریافت می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                            Picker("دریافت در حساب", accounts.map { Choice(it.id, it.name) }, single, { single = it })
                        }
                        if (kitchens.size > 1) Picker("مصرف از انبار یا آشپزخانه", kitchens.map { Choice(it.id, it.name) }, kitchen ?: kitchens.first().id, { kitchen = it })
                    }
                    p.problems.forEach { Banner(it) }
                    action.error?.let { Banner(it) }
                    val accountsReady = if (p.hasInvoices) (p.cash <= 0 || cashAccount != null) && (p.card <= 0 || cardAccount != null) else single != null
                    PrimaryButton("ثبت فروش این روز", {
                        action.run({
                            posImports.commit(commandId.value, branch, files, choices, PosAccounts(cashAccount, cardAccount, single, kitchen ?: kitchens.firstOrNull()?.id))
                        }) {
                            done = "فروش روز ${Fa.date(p.date!!)} وارد و ثبت نهایی شد."
                            files = emptyList(); commandId.value = GlobalId.new()
                        }
                    }, enabled = p.ready && accountsReady && !action.busy, busy = action.busy)
                }
            }
        }
    }
}

/** A picked file's name and bytes (at most 10 MB, as the reader allows). */
private fun readFile(context: android.content.Context, uri: Uri): PosFile {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > ir.sabou.posimport.Sheets.MAX_FILE_BYTES) break
        }
        out.toByteArray()
    } ?: error("unreadable")
    return PosFile(name, bytes)
}
