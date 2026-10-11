package ir.sabou.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ir.sabou.payroll.StaffDocument
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.Permission

/** An employee's documents: health card, contract, ID… with expiry (ADR-0039). */
object StaffDocScreens {
    @Composable
    fun Documents(nav: Nav, employeeId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, employeeId) { labor.documents(employeeId) to labor.employee(employeeId) }
        var kind by rememberSaveable { mutableStateOf(StaffDocument.Kind.HEALTH_CARD) }
        var title by rememberSaveable { mutableStateOf("") }
        var note by rememberSaveable { mutableStateOf("") }
        var expiresDay by rememberSaveable { mutableStateOf<Long?>(null) }
        val files = remember { mutableStateListOf<AttachmentInput>() }
        val commandId = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("مدارک کارمند", onBack = nav.back)
            Page {
                Loaded(data) { (docs, employee) ->
                    employee?.let { Text(it.name, style = SabouType.section, color = Sabou.colors.ink) }
                    val current = ir.sabou.payroll.StaffDocumentRules.current(docs.map { it.document })
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
                                    left <= ir.sabou.payroll.StaffDocumentRules.WARN_DAYS -> Chip("${Fa.number(left)} روز مانده", ChipKind.ACCENT)
                                    else -> Chip("معتبر", ChipKind.PRIMARY)
                                }
                            }
                            d.expiresOn?.let { Text("اعتبار تا ${Fa.date(it)}", style = SabouType.caption, color = Sabou.colors.muted) }
                            if (d.note.isNotBlank()) Text(d.note, style = SabouType.caption, color = Sabou.colors.muted)
                            AttachmentList(v.files)
                        }
                    }
                    if (session.can(Permission.PERSONNEL_MANAGE) && employee != null) FormCard("مدرک تازه") {
                        Picker("نوع", StaffDocument.Kind.entries.map { Choice(it, Alerts.staffDocName(it)) }, kind, { kind = it })
                        TextInput("عنوان (اختیاری)", title, { title = it })
                        OptionalDateInput("تاریخ اعتبار", expiresDay?.let(::BusinessDate), { expiresDay = it?.epochDay }, session.today)
                        if (kind == StaffDocument.Kind.HEALTH_CARD) Text("کارت بهداشت تاریخ اعتبار لازم دارد؛ دو هفته پیش از پایان آن هشدار داده می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
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
            }
        }
    }
}
