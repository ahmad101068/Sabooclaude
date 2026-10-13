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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.AttachmentList
import ir.sabou.app.ui.AttachmentPicker
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.OptionalDateInput
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Alerts
import ir.sabou.core.Fa
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.payroll.AddStaffDocument
import ir.sabou.payroll.CorrectStaffDocument
import ir.sabou.payroll.DocumentIssue
import ir.sabou.payroll.RemoveStaffDocument
import ir.sabou.payroll.StaffDocument
import ir.sabou.payroll.StaffDocumentRules
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.Permission

/** An employee's documents: health card, contract, ID… with expiry (ADR-0039). Wrong entries are corrected or removed. */
object StaffDocScreens {

    /** «کارت بهداشت: ثبت نشده», «قرارداد: ۵ روز مانده», «کارت بهداشت: منقضی شده (۱۴۰۵/۰۷/۰۱)». */
    fun issueText(issue: DocumentIssue): String {
        val name = Alerts.staffDocName(issue.kind)
        val left = issue.daysLeft
        return when {
            issue.missing -> "$name: ثبت نشده"
            issue.expired -> "$name: منقضی شده" + (issue.document?.expiresOn?.let { " (${Fa.date(it)})" } ?: "")
            left == 0L -> "$name: امروز منقضی می‌شود"
            else -> "$name: ${Fa.number(left ?: 0)} روز مانده"
        }
    }

    @Composable
    fun Documents(nav: Nav, employeeId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, employeeId) { labor.documents(employeeId) to labor.employee(employeeId) }
        val manage = session.can(Permission.PERSONNEL_MANAGE)
        var kind by rememberSaveable { mutableStateOf(StaffDocument.Kind.HEALTH_CARD) }
        var editing by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var removing by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        Column(Modifier.fillMaxSize()) {
            Header("مدارک کارمند", onBack = nav.back)
            Page {
                Loaded(data) { (docs, employee) ->
                    Text(employee.name, style = SabouType.section, color = Sabou.colors.ink)
                    // What needs attention, by name, each with its fix.
                    val issues = StaffDocumentRules.issues(employee, docs.map { it.document }, session.today)
                    if (issues.isNotEmpty()) SCard {
                        Text("نیاز به رسیدگی", style = SabouType.bodyStrong, color = Sabou.colors.danger)
                        issues.forEach { issue ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(issueText(issue), style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                if (manage) Text(if (issue.missing) "ثبت" else "ثبت مدرک تازه", style = SabouType.label, color = Sabou.colors.primary,
                                    modifier = Modifier.clickable { kind = issue.kind; editing = null }.padding(6.dp))
                            }
                        }
                    }
                    val current = StaffDocumentRules.current(docs.map { it.document })
                    if (docs.isEmpty()) EmptyState("مدرکی ثبت نشده است.")
                    docs.forEach { v ->
                        val d = v.document
                        val inForce = current[d.kind]?.id == d.id
                        SCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Alerts.staffDocName(d.kind) + if (d.title.isNotBlank()) " · ${d.title}" else "", style = SabouType.bodyStrong,
                                    color = if (inForce) Sabou.colors.ink else Sabou.colors.muted, modifier = Modifier.weight(1f))
                                val left = d.expiresOn?.let { it.epochDay - session.today.epochDay }
                                when {
                                    !inForce -> Chip("قدیمی", ChipKind.NEUTRAL)
                                    left == null -> {}
                                    left < 0 -> Chip("منقضی", ChipKind.DANGER)
                                    left <= StaffDocumentRules.WARN_DAYS -> Chip("${Fa.number(left)} روز مانده", ChipKind.ACCENT)
                                    else -> Chip("معتبر", ChipKind.PRIMARY)
                                }
                            }
                            d.expiresOn?.let { Text("اعتبار تا ${Fa.date(it)}", style = SabouType.caption, color = Sabou.colors.muted) }
                            if (d.note.isNotBlank()) Text(d.note, style = SabouType.caption, color = Sabou.colors.muted)
                            if (v.corrections > 0) Text("${Fa.number(v.corrections.toLong())} بار اصلاح شده (نسخه‌های قبلی در سوابق ممیزی)", style = SabouType.caption, color = Sabou.colors.muted)
                            AttachmentList(v.files)
                            if (manage) when {
                                editing == d.id -> key(d.id) { CorrectForm(d) { editing = null } }
                                removing == d.id -> key(d.id) { RemoveForm(d) { removing = null } }
                                else -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("اصلاح", style = SabouType.label, color = Sabou.colors.primary,
                                        modifier = Modifier.clickable { editing = d.id; removing = null }.padding(vertical = 6.dp))
                                    Text("حذف", style = SabouType.label, color = Sabou.colors.danger,
                                        modifier = Modifier.clickable { removing = d.id; editing = null }.padding(vertical = 6.dp))
                                }
                            }
                        }
                    }
                    if (manage) NewDocumentForm(employee, kind) { kind = it }
                }
            }
        }
    }

    @Composable
    private fun NewDocumentForm(employee: ir.sabou.payroll.Employee, kind: StaffDocument.Kind, onKind: (StaffDocument.Kind) -> Unit) {
        val session = LocalSession.current
        var title by rememberSaveable { mutableStateOf("") }
        var note by rememberSaveable { mutableStateOf("") }
        var expiresDay by rememberSaveable { mutableStateOf<Long?>(null) }
        val files = remember { mutableStateListOf<AttachmentInput>() }
        val commandId = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        FormCard("مدرک تازه") {
            Picker("نوع", StaffDocument.Kind.entries.map { Choice(it, Alerts.staffDocName(it)) }, kind, onKind)
            TextInput("عنوان (اختیاری)", title, { title = it })
            OptionalDateInput("تاریخ اعتبار", expiresDay?.let(::BusinessDate), { expiresDay = it?.epochDay }, session.today)
            if (kind == StaffDocument.Kind.HEALTH_CARD) Text("کارت بهداشت تاریخ اعتبار لازم دارد؛ دو هفته پیش از پایان آن به گوشی هشدار داده می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
            TextInput("توضیح", note, { note = it })
            files.forEach { Text("📎 ${it.fileName}", style = SabouType.caption, color = Sabou.colors.ink) }
            AttachmentPicker("افزودن عکس یا PDF") { files.add(it) }
            action.error?.let { Banner(it) }
            PrimaryButton("ثبت مدرک", {
                action.run({ staffDocuments.add(AddStaffDocument(commandId.value, employee.scope, employee.id, kind, title, expiresDay?.let(::BusinessDate), note, files.toList())) }) {
                    title = ""; note = ""; expiresDay = null; files.clear(); commandId.value = GlobalId.new()
                }
            }, enabled = (files.isNotEmpty() || note.isNotBlank()) && (kind != StaffDocument.Kind.HEALTH_CARD || expiresDay != null) && !action.busy, busy = action.busy)
        }
    }

    @Composable
    private fun CorrectForm(d: StaffDocument, onDone: () -> Unit) {
        val session = LocalSession.current
        var kind by rememberSaveable { mutableStateOf(d.kind) }
        var title by rememberSaveable { mutableStateOf(d.title) }
        var note by rememberSaveable { mutableStateOf(d.note) }
        var expiresDay by rememberSaveable { mutableStateOf(d.expiresOn?.epochDay) }
        val files = remember { mutableStateListOf<AttachmentInput>() }
        val commandId = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Picker("نوع", StaffDocument.Kind.entries.map { Choice(it, Alerts.staffDocName(it)) }, kind, { kind = it })
        TextInput("عنوان (اختیاری)", title, { title = it })
        OptionalDateInput("تاریخ اعتبار", expiresDay?.let(::BusinessDate), { expiresDay = it?.epochDay }, session.today)
        TextInput("توضیح", note, { note = it })
        Text("فایل‌های فعلی می‌مانند؛ فایل تازه به آن‌ها اضافه می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
        files.forEach { Text("📎 ${it.fileName}", style = SabouType.caption, color = Sabou.colors.ink) }
        AttachmentPicker("افزودن عکس یا PDF") { files.add(it) }
        action.error?.let { Banner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("ذخیره‌ی اصلاح", {
                action.run({ staffDocuments.correct(CorrectStaffDocument(commandId.value, d.scope, d.id, kind, title, expiresDay?.let(::BusinessDate), note, files.toList())) }) { onDone() }
            }, Modifier.weight(1f), enabled = (kind != StaffDocument.Kind.HEALTH_CARD || expiresDay != null) && !action.busy, busy = action.busy)
            SecondaryButton("انصراف", onDone, Modifier.weight(1f))
        }
    }

    @Composable
    private fun RemoveForm(d: StaffDocument, onDone: () -> Unit) {
        var reason by rememberSaveable { mutableStateOf("") }
        val commandId = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Text("مدرک از فهرست برداشته می‌شود ولی با دلیل در سوابق ممیزی می‌ماند. برای کارت تمدیدشده «مدرک تازه» ثبت کنید، نه حذف.", style = SabouType.caption, color = Sabou.colors.muted)
        TextInput("دلیل حذف", reason, { reason = it }, placeholder = "مثلاً برای کارمند دیگری ثبت شده بود")
        action.error?.let { Banner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("حذف مدرک", {
                action.run({ staffDocuments.remove(RemoveStaffDocument(commandId.value, d.scope, d.id, reason)) }) { onDone() }
            }, Modifier.weight(1f), enabled = reason.trim().length >= 3 && !action.busy, busy = action.busy)
            SecondaryButton("انصراف", onDone, Modifier.weight(1f))
        }
    }
}
