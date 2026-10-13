package ir.sabou.app.ui.screens

import androidx.compose.runtime.remember
import ir.sabou.payroll.VoidPunch
import ir.sabou.payroll.AddPunch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
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
import ir.sabou.kernel.GlobalId
import ir.sabou.payroll.ClockPunch
import ir.sabou.payroll.ConfirmDayTime
import ir.sabou.payroll.CopyWeek
import ir.sabou.payroll.DeleteShift
import ir.sabou.payroll.Punch
import ir.sabou.payroll.SaveShift
import ir.sabou.platform.Permission

/** Shifts, the shared-phone time clock and the day's planned-vs-clocked time (ADR-0038). */
object ShiftScreens {
    /** «۹:۰۵»; past midnight shows the next day's hour. */
    fun clock(minute: Int): String = Fa.digits("${(minute / 60) % 24}:${(minute % 60).toString().padStart(2, '0')}")

    @Composable
    fun Schedule(nav: Nav) {
        val session = LocalSession.current
        // Weeks start on Saturday.
        val thisWeek = session.today.plusDays(-Fa.weekdayIndex(session.today).toLong())
        var week by rememberSaveable { mutableStateOf(thisWeek) }
        var adding by rememberSaveable { mutableStateOf<Long?>(null) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("برنامه‌ی شیفت", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, week) { labor.week(branch, week) to overview.employees(branch).filter { it.endDate == null && it.isActive } }
                Page {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("‹ هفته‌ی قبل", style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable { week = week.plusDays(-7) }.padding(8.dp))
                        Text("از ${Fa.date(week)}", style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Text("هفته‌ی بعد ›", style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable { week = week.plusDays(7) }.padding(8.dp))
                    }
                    action.error?.let { Banner(it) }
                    Loaded(data) { (days, employees) ->
                        if (employees.isEmpty()) EmptyState("کارمندی ثبت نشده است.")
                        if (days.all { it.shifts.isEmpty() } && session.can(Permission.SHIFT_MANAGE)) {
                            SecondaryButton("کپی برنامه‌ی هفته‌ی قبل", { action.run({ shifts.copyWeek(CopyWeek(GlobalId.new(), branch, week.plusDays(-7), week)) }) }, enabled = !action.busy)
                        }
                        SCard {
                            KeyValue("ساعت کل هفته", Fa.number(days.sumOf { it.minutes }.toLong() / 60))
                            KeyValue("هزینه‌ی نیرو", Fa.rial(days.sumOf { it.cost.rial }) + " ریال")
                            val budget = days.sumOf { it.budget.rial }
                            if (budget > 0) KeyValue("بودجه‌ی نیرو", Fa.rial(budget) + " ریال", if (days.sumOf { it.cost.rial } > budget) Sabou.colors.danger else Sabou.colors.ink)
                        }
                        days.forEach { d ->
                            SCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(Fa.dayTitle(d.date), style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                    d.laborBp?.let { Chip("نیرو " + Fa.percent(it) + " فروش", if (it > 3_500) ChipKind.DANGER else ChipKind.PRIMARY) }
                                }
                                Text("${Fa.number(d.minutes.toLong() / 60)} ساعت · ${Fa.rialShort(d.cost.rial)}" +
                                    (if (d.budget.rial > 0) " از بودجه‌ی ${Fa.rialShort(d.budget.rial)}" else "") +
                                    (if (d.expectedSales.rial > 0) " · فروش مورد انتظار ${Fa.rialShort(d.expectedSales.rial)}" else ""),
                                    style = SabouType.caption, color = Sabou.colors.muted)
                                d.shifts.forEach { s ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${s.employee} · ${clock(s.shift.startMinute)}–${clock(s.shift.endMinute)}" + if (s.shift.station.isNotBlank()) " · ${s.shift.station}" else "",
                                            style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                        if (session.can(Permission.SHIFT_MANAGE)) Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable {
                                            action.run({ shifts.delete(DeleteShift(GlobalId.new(), branch, s.shift.id)) })
                                        }.padding(6.dp))
                                    }
                                }
                                if (session.can(Permission.SHIFT_MANAGE) && employees.isNotEmpty()) {
                                    if (adding == d.date.epochDay) ShiftForm(branch, d.date, employees.map { Choice(it.id, it.name) }) { adding = null }
                                    else Text("+ شیفت", style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable { adding = d.date.epochDay }.padding(vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ShiftForm(branch: ir.sabou.kernel.Scope.Branch, date: ir.sabou.kernel.BusinessDate, employees: List<Choice<GlobalId>>, onDone: () -> Unit) {
        var employee by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var start by rememberSaveable { mutableStateOf<Int?>(null) }
        var end by rememberSaveable { mutableStateOf<Int?>(null) }
        var rest by rememberSaveable { mutableStateOf("") }
        var station by rememberSaveable { mutableStateOf("") }
        val action = rememberAction()
        Picker("کارمند", employees, employee, { employee = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ir.sabou.app.ui.components.TimeInput("از ساعت", start, { start = it }, Modifier.weight(1f))
            ir.sabou.app.ui.components.TimeInput("تا ساعت", end, { end = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput("استراحت (دقیقه)", rest, { rest = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
            TextInput("ایستگاه", station, { station = it }, Modifier.weight(1f), placeholder = "صندوق، کباب‌پز…")
        }
        val s = start; val e = end?.let { if (s != null && it <= s) it + 1_440 else it }
        val b = Fa.latinDigits(rest.trim()).ifBlank { "0" }.toIntOrNull()
        if (s != null && e != null && e - s > 0) Text("${Fa.number((e - s - (b ?: 0)).toLong() / 60)} ساعت" + if (e >= 1_440) " (تا بامداد روز بعد)" else "", style = SabouType.caption, color = Sabou.colors.muted)
        action.error?.let { Banner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("ثبت شیفت", {
                action.run({ shifts.save(SaveShift(GlobalId.new(), branch, null, employee!!, date, s!!, e!!, b!!, station)) }) { onDone() }
            }, Modifier.weight(1f), enabled = employee != null && s != null && e != null && b != null && !action.busy, busy = action.busy)
            SecondaryButton("انصراف", onDone, Modifier.weight(1f))
        }
    }

    /** The shared phone: pick your name, type your PIN. */
    @Composable
    fun TimeClock(nav: Nav) {
        val session = LocalSession.current
        var who by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var pin by rememberSaveable { mutableStateOf("") }
        var done by rememberSaveable { mutableStateOf<String?>(null) }
        val action = rememberAction()
        val settings by load(session) { payrollSettings.current() }
        Column(Modifier.fillMaxSize()) {
            Header("ساعت‌زنی", Fa.dayTitle(session.today), onBack = nav.back)
            WithBranch { branch ->
                val people by load(session, branch, done) { labor.clockList(branch, session.today) }
                Page {
                    done?.let { Banner(it, ChipKind.PRIMARY, ir.sabou.app.R.drawable.ic_check) }
                    Loaded(people) { list ->
                        if (list.isEmpty()) EmptyState("برای هیچ کارمندی رمز ساعت‌زنی تعیین نشده است (کارکنان ← رمز ساعت‌زنی).")
                        val selected = list.firstOrNull { it.first == who }
                        if (selected == null) {
                            list.forEach { (id, name) ->
                                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Sabou.colors.primarySoft)
                                    .clickable { who = id; pin = ""; done = null }, contentAlignment = Alignment.Center) {
                                    Text(name, style = SabouType.section, color = Sabou.colors.primary)
                                }
                            }
                        } else {
                            val punches by load(session, branch, who, done) {
                                labor.punchesToday(branch, session.today, selected.first, java.time.LocalTime.now().let { it.hour * 60 + it.minute })
                            }
                            val inside = punches.orNull()?.lastOrNull()?.kind == Punch.Kind.IN
                            FormCard(selected.second) {
                                punches.orNull()?.forEach { p -> Text((if (p.kind == Punch.Kind.IN) "ورود " else "خروج ") + clock(p.minute), style = SabouType.caption, color = Sabou.colors.muted) }
                                Text("•".repeat(pin.length).ifEmpty { "رمز خود را بزنید" }, style = SabouType.section, color = Sabou.colors.ink)
                                PinPad({ if (pin.length < 6) pin += it }, { pin = pin.dropLast(1) })
                                action.error?.let { Banner(it) }
                                fun punch(photo: ir.sabou.platform.AttachmentInput?) {
                                    val now = java.time.LocalTime.now()
                                    val minute = now.hour * 60 + now.minute
                                    action.run({ shifts.punch(ClockPunch(GlobalId.new(), branch, selected.first, pin, session.today, minute, photo)) }) {
                                        done = "${selected.second}: ${if (inside) "خروج" else "ورود"} ساعت ${clock(minute)} ثبت شد."
                                        who = null; pin = ""
                                    }
                                }
                                // When the restaurant asks for it, the phone takes a photo at the moment of the punch.
                                val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview()) { bitmap ->
                                    bitmap?.let { b ->
                                        val out = java.io.ByteArrayOutputStream()
                                        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                        punch(ir.sabou.platform.AttachmentInput("clock.jpg", "image/jpeg", out.toByteArray()))
                                    }
                                }
                                val photoRequired = settings.orNull()?.clockPhotoRequired == true
                                if (photoRequired) Text("با زدن دکمه دوربین باز می‌شود؛ یک عکس از خودتان بگیرید.", style = SabouType.caption, color = Sabou.colors.muted)
                                PrimaryButton(if (inside) "ثبت خروج" else "ثبت ورود", {
                                    if (photoRequired) camera.launch(null) else punch(null)
                                }, enabled = pin.length >= 4 && !action.busy && settings.orNull() != null, busy = action.busy)
                                SecondaryButton("انصراف", { who = null; pin = "" })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PinPad(onDigit: (Char) -> Unit, onDelete: () -> Unit) {
        listOf("123", "456", "789", "<0").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(10.dp)).background(Sabou.colors.track)
                        .clickable { if (c == '<') onDelete() else onDigit(c) }, contentAlignment = Alignment.Center) {
                        Text(if (c == '<') "پاک" else Fa.digits(c.toString()), style = SabouType.section, color = Sabou.colors.ink)
                    }
                }
            }
        }
    }

    @Composable
    fun DayTime(nav: Nav) {
        val session = LocalSession.current
        var date by rememberSaveable { mutableStateOf(session.today) }
        var replaceManual by rememberSaveable { mutableStateOf(false) }
        var fixing by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("کارکرد روز", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, date) { labor.day(branch, date) }
                val canFix = session.can(Permission.ATTENDANCE_RECORD)
                Page {
                    DateInput("روز", date, { date = it }, session.today)
                    Loaded(data) { rows ->
                        if (rows.isEmpty()) EmptyState("برای این روز شیفت یا ساعت‌زنی‌ای نیست.")
                        rows.forEach { r ->
                            val t = r.time
                            SCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.employee.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                        Text("برنامه ${durationText(t.planned)} · کارکرد ${durationText(t.worked)}" +
                                            (if (t.late > 0) " · تأخیر ${Fa.number(t.late.toLong())} دقیقه" else "") +
                                            (if (t.overtime > 0) " · اضافه‌کار ${durationText(t.overtime)}" else "") +
                                            (if (t.absent > 0) " · کسر ${durationText(t.absent)}" else ""), style = SabouType.caption, color = Sabou.colors.muted)
                                    }
                                    when {
                                        t.openPunch -> Chip("خروج نزده", ChipKind.ACCENT)
                                        t.planned > 0 && t.worked == 0 -> Chip("غایب", ChipKind.DANGER)
                                        t.late > 0 -> Chip("تأخیر", ChipKind.ACCENT)
                                        else -> Chip("درست", ChipKind.PRIMARY)
                                    }
                                }
                                if (r.manual) Text("کارکرد این روز دستی ثبت شده است.", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                                // The punches themselves, so a wrong or missing one can be fixed here.
                                r.punches.forEach { p ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text((if (p.kind == Punch.Kind.IN) "ورود " else "خروج ") + clock(p.minute) + (if (p.minute >= 1_440) " (بامداد روز بعد)" else "") +
                                            (if (p.manualBy != null) " · دستی: ${p.reason}" else ""), style = SabouType.caption, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                        if (canFix && fixing == r.employee.id) VoidLink(branch, date, p)
                                    }
                                    r.photos[p.id]?.let { ir.sabou.app.ui.AttachmentList(it) }
                                }
                                if (canFix) {
                                    if (fixing == r.employee.id) {
                                        AddPunchForm(branch, date, r.employee.id, if (t.openPunch) Punch.Kind.OUT else Punch.Kind.IN)
                                        Text("بستن", style = SabouType.label, color = Sabou.colors.muted, modifier = Modifier.clickable { fixing = null }.padding(vertical = 6.dp))
                                    } else Text("اصلاح ساعت‌زنی", style = SabouType.label, color = Sabou.colors.primary,
                                        modifier = Modifier.clickable { fixing = r.employee.id }.padding(vertical = 6.dp))
                                }
                            }
                        }
                        action.error?.let { Banner(it) }
                        if (rows.isNotEmpty() && canFix) {
                            Text("با تأیید، کارکرد، اضافه‌کار و کسر هر نفر در «حضور و غیاب» ثبت و در حقوق ماه حساب می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                            if (rows.any { it.manual }) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { replaceManual = !replaceManual }) {
                                androidx.compose.material3.Checkbox(checked = replaceManual, onCheckedChange = { replaceManual = it })
                                Text("کارکرد دستی هم با ساعت‌زنی جایگزین شود", style = SabouType.body, color = Sabou.colors.ink)
                            }
                            PrimaryButton("تأیید کارکرد این روز", { action.run({ shifts.confirmDay(ConfirmDayTime(GlobalId.new(), branch, date, replaceManual)) }) },
                                enabled = rows.none { it.time.openPunch } && date <= session.today && !action.busy, busy = action.busy)
                            if (rows.any { it.time.openPunch }) Text("کسی خروج نزده است: روی «اصلاح ساعت‌زنی» او بزنید و خروجش را ثبت کنید.", style = SabouType.caption, color = Sabou.colors.danger)
                        }
                    }
                }
            }
        }
    }

    private fun durationText(minutes: Int) = ir.sabou.app.ui.components.durationText(minutes)

    @Composable
    private fun VoidLink(branch: ir.sabou.kernel.Scope.Branch, date: ir.sabou.kernel.BusinessDate, p: Punch) {
        var asking by remember { mutableStateOf(false) }
        var reason by rememberSaveable(p.id) { mutableStateOf("") }
        val action = rememberAction()
        if (!asking) Text("لغو", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { asking = true }.padding(6.dp))
        if (asking) androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    action.run({ shifts.voidPunch(VoidPunch(GlobalId.new(), branch, p.id, date, reason)) }) { asking = false }
                }, enabled = reason.trim().length >= 3 && !action.busy) { Text("لغو این ساعت‌زنی", style = SabouType.bodyStrong, color = Sabou.colors.danger) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text("انصراف", style = SabouType.bodyStrong, color = Sabou.colors.muted) } },
            containerColor = Sabou.colors.surface,
            title = { Text((if (p.kind == Punch.Kind.IN) "ورود " else "خروج ") + clock(p.minute), style = SabouType.section) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("دلیل", reason, { reason = it }, placeholder = "دو بار زده شد، به‌جای دیگری زد…")
                    action.error?.let { Banner(it) }
                }
            },
        )
    }

    @Composable
    private fun AddPunchForm(branch: ir.sabou.kernel.Scope.Branch, date: ir.sabou.kernel.BusinessDate, employeeId: GlobalId, suggested: Punch.Kind) {
        var kind by rememberSaveable(employeeId) { mutableStateOf(suggested) }
        var minute by rememberSaveable(employeeId) { mutableStateOf<Int?>(null) }
        var nextDay by rememberSaveable(employeeId) { mutableStateOf(false) }
        var reason by rememberSaveable(employeeId) { mutableStateOf("") }
        val action = rememberAction()
        Picker("نوع", listOf(Choice(Punch.Kind.IN, "ورود"), Choice(Punch.Kind.OUT, "خروج")), kind, { kind = it })
        ir.sabou.app.ui.components.TimeInput("ساعت", minute, { minute = it })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { nextDay = !nextDay }) {
            androidx.compose.material3.Checkbox(checked = nextDay, onCheckedChange = { nextDay = it })
            Text("بامداد روز بعد (شیفت شب)", style = SabouType.body, color = Sabou.colors.ink)
        }
        TextInput("دلیل", reason, { reason = it }, placeholder = "خروج نزده بود…")
        action.error?.let { Banner(it) }
        PrimaryButton("ثبت ${if (kind == Punch.Kind.IN) "ورود" else "خروج"} دستی", {
            action.run({ shifts.addPunch(AddPunch(GlobalId.new(), branch, employeeId, date, minute!! + if (nextDay) 1_440 else 0, kind, reason)) }) {
                minute = null; reason = ""; nextDay = false
            }
        }, enabled = minute != null && reason.trim().length >= 3 && !action.busy, busy = action.busy)
    }
}
