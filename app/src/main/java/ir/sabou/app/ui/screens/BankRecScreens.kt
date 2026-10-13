package ir.sabou.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.NavRow
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.PosFile
import ir.sabou.core.StatementAction
import ir.sabou.kernel.GlobalId
import ir.sabou.platform.Permission
import ir.sabou.treasury.Direction
import ir.sabou.treasury.MatchStatementLine
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.StatementLine
import ir.sabou.treasury.TreasuryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bank statement import and reconciliation (ADR-0035). */
object BankRecScreens {
    @Composable
    fun Statements(nav: Nav) {
        val session = LocalSession.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var accountId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var file by remember { mutableStateOf<PosFile?>(null) }
        var readError by remember { mutableStateOf<String?>(null) }
        val commandId = remember { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { readFile(context, uri) } }
                    .onSuccess { file = it; readError = null; commandId.value = GlobalId.new() }
                    .onFailure { readError = "فایل خوانده نشد." }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Header("مغایرت بانکی", "صورت‌حساب بانک در برابر دفاتر", onBack = nav.back)
            val banks by load(session) { overview.treasury().map { it.account }.filter { it.kind == TreasuryKind.BANK && it.isActive } }
            Page {
                Loaded(banks) { list ->
                    if (list.isEmpty()) EmptyState("حساب بانکی تعریف نشده است.")
                    val account = accountId ?: list.firstOrNull()?.id
                    if (list.size > 1) Picker("حساب بانکی", list.map { Choice(it.id, it.name) }, account, { accountId = it; file = null })
                    if (account != null) {
                        FormCard("صورت‌حساب تازه") {
                            Text("از اینترنت‌بانک صورت‌حساب را به اکسل یا CSV بگیرید (ستون‌های تاریخ، شرح، برداشت/بدهکار، واریز/بستانکار و مانده). سطرها با دریافت و پرداخت‌های همین حساب جور می‌شوند: همان مبلغ، حداکثر ۴ روز فاصله.",
                                style = SabouType.caption, color = Sabou.colors.muted)
                            SecondaryButton(file?.let { "انتخاب دوباره (${it.name})" } ?: "انتخاب فایل", { pick.launch(arrayOf("*/*")) })
                            readError?.let { Banner(it) }
                            file?.let { f ->
                                val preview by load(session, f, account) { bankRec.preview(f, account) }
                                Loaded(preview) { p ->
                                    p.problems.forEach { Banner(it) }
                                    if (p.lines.isNotEmpty()) {
                                        KeyValue("سطرها", Fa.number(p.lines.size.toLong()) + " · ${Fa.date(p.lines.first().date)} تا ${Fa.date(p.lines.last().date)}")
                                        KeyValue("واریز", Fa.rial(p.lines.filter { it.direction == Direction.RECEIPT }.sumOf { it.amount.rial }))
                                        KeyValue("برداشت", Fa.rial(p.lines.filter { it.direction == Direction.PAYMENT }.sumOf { it.amount.rial }))
                                        p.closingBalance?.let { KeyValue("مانده‌ی پایان", Fa.rial(it) + " ریال", strong = true) }
                                        if (p.alreadyImported > 0) Banner(
                                            if (p.alreadyImported == p.lines.size) "همه‌ی سطرهای این فایل قبلاً وارد شده است."
                                            else "${Fa.number(p.alreadyImported.toLong())} سطر این فایل قبلاً (در صورت‌حساب دیگری) وارد شده و کنار گذاشته می‌شود؛ بقیه وارد می‌شود.",
                                            ChipKind.ACCENT,
                                        )
                                    }
                                    action.error?.let { Banner(it) }
                                    PrimaryButton("ورود و تطبیق", {
                                        action.run({ bankRec.import(commandId.value, account, f) }) { r -> file = null; nav.go(Route.StatementDetail(r.resultId)) }
                                    }, enabled = p.ready && session.can(Permission.TREASURY_RECONCILE), busy = action.busy)
                                }
                            }
                        }
                        val statements by load(session, account) { bankRec.statements(account) }
                        Loaded(statements) { st ->
                            st.forEach { s ->
                                val open = s.unmatched.size
                                NavRow(R.drawable.ic_finance, "${Fa.date(s.from)} تا ${Fa.date(s.to)}", "${s.fileName} · ${Fa.number(s.lines.size.toLong())} سطر" +
                                    if (open > 0) " · ${Fa.number(open.toLong())} جورنشده" else " · کامل", onClick = { nav.go(Route.StatementDetail(s.id)) },
                                    tint = if (open > 0) Sabou.colors.onAccentSoft else Sabou.colors.primary,
                                    tile = if (open > 0) Sabou.colors.accentSoft else Sabou.colors.primarySoft)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Detail(nav: Nav, statementId: GlobalId) {
        val session = LocalSession.current
        var selected by rememberSaveable { mutableStateOf<Int?>(null) }
        val data by load(session, statementId) { bankRec.reconciliation(statementId) to overview.treasury().map { it.account }.filter { it.isActive } }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("تطبیق صورت‌حساب", onBack = nav.back)
            Page {
                Loaded(data) { (r, accounts) ->
                    val s = r.statement
                    SCard {
                        KeyValue("دوره", "${Fa.date(s.from)} تا ${Fa.date(s.to)}")
                        KeyValue("جورشده", "${Fa.number(r.matched.toLong())} از ${Fa.number(s.lines.size.toLong())}")
                        Divider()
                        KeyValue("مانده‌ی دفاتر در ${Fa.date(s.to)}", Fa.rial(r.bookBalance))
                        KeyValue("فقط در دفاتر (هنوز به بانک نرسیده)", Fa.rial(r.bookOnly.sumOf { if (it.movement.direction == Direction.RECEIPT) -it.movement.amount.rial else it.movement.amount.rial }))
                        KeyValue("فقط در صورت‌حساب (ثبت‌نشده)", Fa.rial(r.statementOnly.sumOf { if (it.direction == Direction.RECEIPT) it.amount.rial else -it.amount.rial }))
                        KeyValue("مانده‌ی دفاتر از نگاه بانک", Fa.rial(r.adjustedBook), strong = true)
                        s.closingBalance?.let { KeyValue("مانده‌ی صورت‌حساب", Fa.rial(it.rial), strong = true) }
                        r.unexplained?.let { u ->
                            if (u == 0L) Chip("مغایرت توضیح‌داده‌نشده: صفر", ChipKind.PRIMARY)
                            else Banner("${Fa.rial(u)} ریال اختلاف توضیح‌داده‌نشده؛ سطرهای پیش از این صورت‌حساب یا ثبت اشتباه را بررسی کنید.", ChipKind.ACCENT)
                        }
                    }
                    action.error?.let { Banner(it) }
                    if (r.statementOnly.isNotEmpty()) SCard {
                        Text("فقط در صورت‌حساب", style = SabouType.section, color = Sabou.colors.ink)
                        Text("هر سطر را ثبت کنید (کارمزد، تسویه‌ی کارت‌خوان، هزینه یا درآمد) یا با یک ردیف ثبت‌شده جور کنید.", style = SabouType.caption, color = Sabou.colors.muted)
                        r.statementOnly.forEach { line ->
                            LineRow(line, selected == line.index) { selected = if (selected == line.index) null else line.index }
                            if (selected == line.index && session.can(Permission.TREASURY_RECONCILE)) {
                                LineActions(statementId, s.scope, line, accounts.filter { it.id != s.accountId && it.kind.isOrdinary }, action)
                            }
                            Divider()
                        }
                    }
                    if (r.bookOnly.isNotEmpty()) SCard {
                        Text("فقط در دفاتر", style = SabouType.section, color = Sabou.colors.ink)
                        Text("چک‌هایی که هنوز وصول نشده یا واریزهایی که هنوز به حساب ننشسته‌اند. اگر مدتی گذشته و در صورت‌حساب نیامده، ثبت را بررسی کنید.",
                            style = SabouType.caption, color = Sabou.colors.muted)
                        r.bookOnly.forEach { b ->
                            KeyValue("${Fa.date(b.movement.date)} · ${b.description}", (if (b.movement.direction == Direction.RECEIPT) "+" else "−") + Fa.rial(b.movement.amount))
                        }
                    }
                    SCard {
                        Text("جورشده‌ها", style = SabouType.section, color = Sabou.colors.ink)
                        s.lines.filter { it.movementId != null }.forEach { line ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { LineText(line) }
                                if (session.can(Permission.TREASURY_RECONCILE)) Text("جدا کن", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable {
                                    action.run({ bankStatements.match(MatchStatementLine(GlobalId.new(), s.scope, s.id, line.index, null)) })
                                }.padding(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LineRow(line: StatementLine, open: Boolean, onClick: () -> Unit) {
        Row(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { LineText(line) }
            Text(if (open) "بستن" else "ثبت یا جور کردن", style = SabouType.label, color = Sabou.colors.primary)
        }
    }

    @Composable
    private fun LineText(line: StatementLine) {
        Text((if (line.direction == Direction.RECEIPT) "+" else "−") + Fa.rial(line.amount) + " · " + Fa.date(line.date), style = SabouType.bodyStrong,
            color = if (line.direction == Direction.RECEIPT) Sabou.colors.primary else Sabou.colors.ink)
        if (line.description.isNotBlank() || line.reference.isNotBlank()) Text(line.description + if (line.reference.isNotBlank()) " · ${Fa.digits(line.reference)}" else "",
            style = SabouType.caption, color = Sabou.colors.muted)
    }

    @Composable
    private fun LineActions(statementId: GlobalId, scope: ir.sabou.kernel.Scope, line: StatementLine, others: List<ir.sabou.treasury.TreasuryAccount>, action: ir.sabou.app.ui.Action) {
        val session = LocalSession.current
        val candidates by load(session, statementId, line.index) { bankRec.candidates(statementId, line.index) }
        candidates.orNull()?.takeIf { it.isNotEmpty() }?.let { list ->
            Text("جور کردن با ردیف ثبت‌شده:", style = SabouType.caption, color = Sabou.colors.muted)
            list.take(5).forEach { b ->
                NavRow(R.drawable.ic_check, "${Fa.date(b.movement.date)} · ${Fa.rial(b.movement.amount)}", b.description, onClick = {
                    action.run({ bankStatements.match(MatchStatementLine(GlobalId.new(), scope, statementId, line.index, b.movement.id)) })
                })
            }
        }
        var other by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        if (line.direction == Direction.PAYMENT) {
            SecondaryButton("کارمزد بانکی", { action.run({ bankRec.record(GlobalId.new(), statementId, line.index, StatementAction.Pay(PaymentPurpose.BANK_FEES)) }) }, enabled = !action.busy)
            SecondaryButton("سایر هزینه‌ها", { action.run({ bankRec.record(GlobalId.new(), statementId, line.index, StatementAction.Pay(PaymentPurpose.OTHER_EXPENSE)) }) }, enabled = !action.busy)
            SecondaryButton("برداشت مالک", { action.run({ bankRec.record(GlobalId.new(), statementId, line.index, StatementAction.Pay(PaymentPurpose.OWNER_WITHDRAWAL)) }) }, enabled = !action.busy)
        } else {
            SecondaryButton("سایر درآمدها", { action.run({ bankRec.record(GlobalId.new(), statementId, line.index, StatementAction.Receive(ReceiptPurpose.OTHER_INCOME)) }) }, enabled = !action.busy)
            SecondaryButton("آورده‌ی مالک", { action.run({ bankRec.record(GlobalId.new(), statementId, line.index, StatementAction.Receive(ReceiptPurpose.OWNER_CAPITAL)) }) }, enabled = !action.busy)
        }
        if (others.isNotEmpty()) {
            Picker(if (line.direction == Direction.RECEIPT) "انتقال از حساب (مثلاً تسویه‌ی کارت‌خوان)" else "انتقال به حساب", others.map { Choice(it.id, it.name) }, other, { other = it })
            SecondaryButton("ثبت انتقال", { action.run({ bankRec.record(GlobalId.new(), statementId, line.index, StatementAction.Transfer(other!!)) }) }, enabled = other != null && !action.busy)
        }
    }

    /** A picked file's name and bytes (at most the reader's limit). */
    private fun readFile(context: android.content.Context, uri: Uri): PosFile {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "statement"
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
}
