package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.BirthDateInput
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.MoneyInput
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliDay
import ir.sabou.kernel.Money
import ir.sabou.payroll.ChangeSalary
import ir.sabou.payroll.Employee
import ir.sabou.payroll.EndEmployment
import ir.sabou.payroll.OvertimeMode
import ir.sabou.payroll.PayrollSettings
import ir.sabou.payroll.RehireEmployee
import ir.sabou.payroll.SavePayrollSettings
import ir.sabou.payroll.SetClockPin
import ir.sabou.payroll.UpdateEmployeeDetails
import ir.sabou.payroll.UpdateEmployeeProfile
import ir.sabou.payroll.EmployeeProfile
import ir.sabou.payroll.MaritalStatus
import ir.sabou.payroll.ContractType
import ir.sabou.payroll.PayType
import ir.sabou.kernel.BusinessDate
import ir.sabou.platform.Permission

/** One employee's page: details, salary and its history, clock PIN, documents, end or return (personnel analysis 2026-10). */
object EmployeeScreens {

    fun jalali(d: JalaliDay) = "${Fa.digits(d.day.toString())} ${Fa.monthNames[d.month - 1]} ${Fa.digits(d.year.toString())}"

    @Composable
    fun Detail(nav: Nav, employeeId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, employeeId) { labor.employee(employeeId) }
        val manage = session.can(Permission.PERSONNEL_MANAGE)
        var open by rememberSaveable { mutableStateOf<String?>(null) }   // which form is open
        val photoAction = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("پرونده‌ی کارمند", onBack = nav.back)
            Page {
                Loaded(data) { e ->
                    val issues by load(session, e.scope, e.id) { labor.documentIssues(e.scope, session.today).filter { it.employee.id == e.id } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.name, style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                        if (e.personnelNumber > 0) Chip("شماره پرسنلی ${Fa.number(e.personnelNumber.toLong())}", ChipKind.PRIMARY)
                    }
                    if (e.endDate != null) Chip("پایان همکاری ${Fa.date(e.endDate!!)}", ChipKind.NEUTRAL)

                    SectionTitle("مشخصات")
                    SCard {
                        KeyValue("کد ملی", Fa.digits(e.nationalId))
                        KeyValue("تاریخ تولد", e.birthDate?.let(::jalali) ?: "ثبت نشده")
                        KeyValue("شروع کار", e.startDate?.let(Fa::date) ?: "—")
                        e.pastEmployments.forEach { p -> KeyValue("همکاری قبلی", (p.start?.let(Fa::date) ?: "—") + " تا " + Fa.date(p.end)) }
                        e.branchMoves.forEach { m -> KeyValue("انتقال از شعبه‌ی دیگر", "از ${Fa.date(m.from)}") }
                        if (manage) {
                            if (open == "details") DetailsForm(e) { open = null }
                            else Link("ویرایش مشخصات") { open = "details" }
                        }
                        val photo by load(session, e.id, open) { labor.photo(e.id) }
                        photo.orNull()?.let { ir.sabou.app.ui.AttachmentList(listOf(it)) }
                        if (manage) ir.sabou.app.ui.AttachmentPicker(if (photo.orNull() == null) "افزودن عکس" else "تغییر عکس") { file ->
                            photoAction.run({ staffDocuments.setPhoto(ir.sabou.payroll.SetEmployeePhoto(GlobalId.new(), e.scope, e.id, file)) }) { open = null }
                        }
                        photoAction.error?.let { Banner(it) }
                    }

                    SectionTitle("پرونده")
                    SCard {
                        val p = e.profile
                        KeyValue("سمت", p.jobTitle.ifBlank { "—" })
                        KeyValue("تلفن", Fa.digits(p.phone).ifBlank { "—" })
                        KeyValue("تماس اضطراری", p.emergencyContact.ifBlank { "—" })
                        KeyValue("نام پدر", p.fatherName.ifBlank { "—" })
                        KeyValue("نشانی", p.address.ifBlank { "—" })
                        KeyValue("شماره بیمه", Fa.digits(p.insuranceNumber).ifBlank { "—" })
                        KeyValue("شبا", p.sheba.ifBlank { "—" })
                        KeyValue("وضعیت تأهل", when (p.maritalStatus) { MaritalStatus.MARRIED -> "متأهل"; MaritalStatus.SINGLE -> "مجرد"; null -> "—" })
                        KeyValue("فرزند", Fa.number(p.children.toLong()))
                        KeyValue("قرارداد", (p.contractType?.let(::contractName) ?: "—") + (p.contractEnd?.let { " تا ${Fa.date(it)}" } ?: ""))
                        if (manage) {
                            if (open == "profile") ProfileForm(e) { open = null }
                            else Link("ویرایش پرونده") { open = "profile" }
                        }
                    }

                    SectionTitle("حقوق")
                    SCard {
                        KeyValue(rateLabel(e.payTypeOn(session.today)), Fa.rial(e.salaryOn(session.today)) + " ریال", strong = true)
                        // The salary history: the first salary, then each change with its date and reason.
                        Text("سابقه", style = SabouType.caption, color = Sabou.colors.muted)
                        Text("${e.startDate?.let(Fa::date) ?: "از ابتدا"}: ${Fa.rial(e.monthlySalary)} (${payTypeName(e.payType)})", style = SabouType.caption, color = Sabou.colors.ink)
                        e.salaryChanges.forEach { c ->
                            val future = c.from > session.today
                            Text("${Fa.date(c.from)}: ${Fa.rial(c.monthlySalary)} (${payTypeName(c.payType)})" + (if (c.reason.isNotBlank()) " · ${c.reason}" else "") + if (future) " (از این تاریخ)" else "",
                                style = SabouType.caption, color = if (future) Sabou.colors.primary else Sabou.colors.ink)
                        }
                        if (manage && e.endDate == null) {
                            if (open == "salary") SalaryForm(e) { open = null }
                            else Link("تغییر حقوق") { open = "salary" }
                        }
                    }

                    if (session.can(Permission.PAYROLL_PAY) || session.can(Permission.PERSONNEL_VIEW) || manage) {
                        SectionTitle("مساعده و وام")
                        SCard { Advances(e, open == "advance", { open = if (it) "advance" else null }) }
                    }

                    SectionTitle("مدارک")
                    SCard(onClick = { nav.go(Route.StaffDocuments(e.id)) }) {
                        val list = issues.orNull().orEmpty()
                        if (list.isEmpty()) Text("همه‌ی مدارک لازم ثبت و معتبر است.", style = SabouType.body, color = Sabou.colors.ink)
                        list.forEach { v ->
                            Text("⚠ " + StaffDocScreens.issueText(v.issue), style = SabouType.label,
                                color = if (v.issue.missing || v.issue.expired) Sabou.colors.danger else Sabou.colors.onAccentSoft)
                        }
                        Text("مدارک و پرونده‌ها ←", style = SabouType.label, color = Sabou.colors.primary)
                    }

                    if (manage && e.endDate == null) {
                        SectionTitle("ساعت‌زنی")
                        SCard { PinForm(e) }
                    }

                    if (manage) {
                        SectionTitle("همکاری")
                        SCard {
                            when {
                                e.endDate != null && open == "rehire" -> RehireForm(e) { open = null }
                                e.endDate != null -> Link("بازگشت به کار") { open = "rehire" }
                                open == "end" -> EndForm(e) { open = null }
                                open == "transfer" -> TransferForm(e) { open = null }
                                else -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Link("پایان همکاری") { open = "end" }
                                    Link("انتقال به شعبه‌ی دیگر") { open = "transfer" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun payTypeName(t: PayType) = when (t) { PayType.MONTHLY -> "ماهانه"; PayType.DAILY -> "روزمزد"; PayType.HOURLY -> "ساعتی" }
    fun rateLabel(t: PayType) = when (t) { PayType.MONTHLY -> "حقوق ماهانه"; PayType.DAILY -> "دستمزد هر روز کار"; PayType.HOURLY -> "دستمزد هر ساعت" }
    fun contractName(t: ContractType) = when (t) {
        ContractType.PERMANENT -> "دائم"; ContractType.FIXED_TERM -> "موقت"; ContractType.TRIAL -> "آزمایشی"; ContractType.PART_TIME -> "پاره‌وقت"
    }

    @Composable
    private fun ProfileForm(e: Employee, onDone: () -> Unit) {
        val session = LocalSession.current
        val p = e.profile
        var job by rememberSaveable { mutableStateOf(p.jobTitle) }
        var phone by rememberSaveable { mutableStateOf(p.phone) }
        var emergency by rememberSaveable { mutableStateOf(p.emergencyContact) }
        var father by rememberSaveable { mutableStateOf(p.fatherName) }
        var address by rememberSaveable { mutableStateOf(p.address) }
        var insurance by rememberSaveable { mutableStateOf(p.insuranceNumber) }
        var sheba by rememberSaveable { mutableStateOf(p.sheba) }
        var marital by rememberSaveable { mutableStateOf(p.maritalStatus) }
        var children by rememberSaveable { mutableStateOf(Fa.number(p.children.toLong())) }
        var contract by rememberSaveable { mutableStateOf(p.contractType) }
        var contractEnd by rememberSaveable { mutableStateOf(p.contractEnd?.epochDay) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        val kids = Fa.latinDigits(children).trim().ifBlank { "0" }.toIntOrNull()
        val shebaOk = sheba.isBlank() || ir.sabou.kernel.Sheba.isValid(sheba)
        TextInput("سمت", job, { job = it }, placeholder = "سرآشپز، گارسون…")
        TextInput("تلفن همراه", phone, { phone = it }, keyboard = KeyboardType.Phone)
        TextInput("تماس اضطراری (نام و شماره)", emergency, { emergency = it })
        TextInput("نام پدر", father, { father = it })
        TextInput("نشانی", address, { address = it }, singleLine = false)
        TextInput("شماره بیمه", insurance, { insurance = it }, keyboard = KeyboardType.Number)
        TextInput("شماره شبا", sheba, { sheba = it }, placeholder = "IR…", error = if (!shebaOk) "شبا درست نیست (IR و ۲۴ رقم)" else null)
        Picker("وضعیت تأهل", listOf(Choice(MaritalStatus.SINGLE, "مجرد"), Choice(MaritalStatus.MARRIED, "متأهل")), marital, { marital = it })
        TextInput("تعداد فرزند", children, { children = it }, keyboard = KeyboardType.Number, error = if (kids == null || kids !in 0..20) "عدد معتبر وارد کنید" else null)
        Picker("نوع قرارداد", ContractType.entries.map { Choice(it, contractName(it)) }, contract, { contract = it })
        ir.sabou.app.ui.components.OptionalDateInput("پایان قرارداد", contractEnd?.let(::BusinessDate), { contractEnd = it?.epochDay }, session.today)
        action.error?.let { Banner(it) }
        Buttons("ذخیره‌ی پرونده", action.busy, shebaOk && kids != null && kids in 0..20, {
            val profile = EmployeeProfile(phone, address, emergency, father, job, insurance, sheba, marital, kids!!, contract, contractEnd?.let(::BusinessDate))
            action.run({ payroll.updateProfile(UpdateEmployeeProfile(id.value, e.scope, e.id, profile)) }) { onDone() }
        }, onDone)
    }

    @Composable
    private fun Advances(e: Employee, adding: Boolean, onAdding: (Boolean) -> Unit) {
        val session = LocalSession.current
        val pay = session.can(Permission.PAYROLL_PAY)
        var changed by rememberSaveable { mutableStateOf(0) }
        val data by load(session, e.id, changed) { labor.advances(e.id) }
        val accounts by load(session, e.scope, pay) { if (pay) overview.paymentAccounts(e.scope) else emptyList() }
        val list = data.orNull().orEmpty()
        if (list.isEmpty()) Text("مساعده یا وامی ثبت نشده است.", style = SabouType.body, color = Sabou.colors.muted)
        list.forEach { v ->
            val a = v.advance
            Text((if (a.isLoan) "وام ${Fa.number(a.installments.toLong())} قسطی" else "مساعده") + " · ${Fa.date(a.date)} · ${Fa.rial(a.amount)}" +
                (if (a.reversed) " · لغو شده" else " · مانده ${Fa.rial(v.outstanding)}") + (if (a.note.isNotBlank()) " · ${a.note}" else ""),
                style = SabouType.body, color = if (a.reversed || v.outstanding.isZero) Sabou.colors.muted else Sabou.colors.ink)
            if (pay && !a.reversed && !v.outstanding.isZero) AdvanceActions(e, v, accounts.orNull().orEmpty()) { changed++ }
        }
        if (pay && e.endDate == null) {
            if (adding) GiveAdvanceForm(e, accounts.orNull().orEmpty()) { onAdding(false); changed++ }
            else Link("+ مساعده یا وام") { onAdding(true) }
            Text("هر ماه یک قسط از حقوق کم می‌شود (مساعده: یک‌جا از حقوق همان ماه)؛ مانده‌ی بدهی هنگام پایان همکاری از آخرین حقوق کم می‌شود.",
                style = SabouType.caption, color = Sabou.colors.muted)
        }
    }

    @Composable
    private fun GiveAdvanceForm(e: Employee, accounts: List<ir.sabou.treasury.TreasuryAccount>, onDone: () -> Unit) {
        val session = LocalSession.current
        var amount by rememberSaveable { mutableStateOf<Money?>(null) }
        var account by rememberSaveable { mutableStateOf(accounts.firstOrNull()?.id) }
        var date by rememberSaveable { mutableStateOf(session.today) }
        var installments by rememberSaveable { mutableStateOf("۱") }
        var note by rememberSaveable { mutableStateOf("") }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        val count = Fa.latinDigits(installments).trim().toIntOrNull()
        MoneyInput("مبلغ", amount, { amount = it })
        Picker("پرداخت از", accounts.map { Choice(it.id, it.name) }, account, { account = it })
        DateInput("تاریخ", date, { date = it }, session.today)
        TextInput("تعداد قسط (۱ = مساعده)", installments, { installments = it }, keyboard = KeyboardType.Number,
            error = if (count == null || count !in 1..60) "عددی بین ۱ تا ۶۰" else null)
        TextInput("توضیح", note, { note = it })
        action.error?.let { Banner(it) }
        Buttons("پرداخت", action.busy, amount != null && account != null && count != null && count in 1..60, {
            action.run({ payroll.giveAdvance(ir.sabou.payroll.GiveAdvance(id.value, e.scope, e.id, account!!, amount!!, date, count!!, note)) }) { onDone() }
        }, { onDone() })
    }

    @Composable
    private fun AdvanceActions(e: Employee, v: ir.sabou.payroll.AdvanceView, accounts: List<ir.sabou.treasury.TreasuryAccount>, onDone: () -> Unit) {
        val session = LocalSession.current
        var mode by rememberSaveable(v.advance.id) { mutableStateOf<String?>(null) }
        var amount by rememberSaveable(v.advance.id) { mutableStateOf<Money?>(v.outstanding) }
        var account by rememberSaveable(v.advance.id) { mutableStateOf(accounts.firstOrNull()?.id) }
        var reason by rememberSaveable(v.advance.id) { mutableStateOf("") }
        val action = rememberAction()
        when (mode) {
            "repay" -> {
                MoneyInput("مبلغ بازپرداخت نقدی", amount, { amount = it })
                Picker("دریافت در", accounts.map { Choice(it.id, it.name) }, account, { account = it })
                action.error?.let { Banner(it) }
                Buttons("ثبت بازپرداخت", action.busy, amount != null && account != null, {
                    action.run({ payroll.repayAdvance(ir.sabou.payroll.RepayAdvance(GlobalId.new(), e.scope, v.advance.id, account!!, amount!!, session.today)) }) { mode = null; onDone() }
                }, { mode = null })
            }
            "cancel" -> {
                TextInput("دلیل لغو", reason, { reason = it })
                action.error?.let { Banner(it) }
                Buttons("لغو مساعده", action.busy, reason.trim().length >= 3, {
                    action.run({ payroll.reverseAdvance(ir.sabou.payroll.ReverseAdvance(GlobalId.new(), e.scope, v.advance.id, session.today, reason)) }) { mode = null; onDone() }
                }, { mode = null })
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Link("بازپرداخت نقدی") { mode = "repay" }
                if (v.deducted.isZero && v.advance.repayments.isEmpty()) Link("لغو (ثبت اشتباه)") { mode = "cancel" }
            }
        }
    }

    @Composable
    private fun TransferForm(e: Employee, onDone: () -> Unit) {
        val session = LocalSession.current
        val branches by load(session) { overview.branches().filter { it.isActive && it.id != e.scope.branchId } }
        var to by rememberSaveable { mutableStateOf<ir.sabou.kernel.BranchId?>(null) }
        var from by rememberSaveable { mutableStateOf(session.today) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Picker("شعبه‌ی مقصد", branches.orNull().orEmpty().map { Choice(it.id, it.name) }, to, { to = it })
        DateInput("از تاریخ", from, { from = it }, session.today)
        Text("انتقال از روز بعد از آخرین ماه حقوق تأییدشده در این شعبه است؛ مدارک و سوابق با کارمند می‌روند.", style = SabouType.caption, color = Sabou.colors.muted)
        action.error?.let { Banner(it) }
        Buttons("ثبت انتقال", action.busy, to != null, {
            action.run({ payroll.transfer(ir.sabou.payroll.TransferEmployee(id.value, e.scope, e.id, ir.sabou.kernel.Scope.Branch(to!!), from)) }) { onDone() }
        }, onDone)
    }

    @Composable
    private fun Link(text: String, onClick: () -> Unit) =
        Text(text, style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp))

    @Composable
    private fun Buttons(primary: String, busy: Boolean, enabled: Boolean, onPrimary: () -> Unit, onCancel: () -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(primary, onPrimary, Modifier.weight(1f), enabled = enabled && !busy, busy = busy)
            SecondaryButton("انصراف", onCancel, Modifier.weight(1f))
        }
    }

    @Composable
    private fun DetailsForm(e: Employee, onDone: () -> Unit) {
        var name by rememberSaveable { mutableStateOf(e.name) }
        var nationalId by rememberSaveable { mutableStateOf(Fa.digits(e.nationalId)) }
        var birth by rememberSaveable { mutableStateOf(e.birthDate) }
        var birthOk by rememberSaveable { mutableStateOf(true) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        TextInput("نام و نام خانوادگی", name, { name = it })
        TextInput("کد ملی", nationalId, { nationalId = it }, keyboard = KeyboardType.Number)
        BirthDateInput("تاریخ تولد", birth) { v, ok -> birth = v; birthOk = ok }
        action.error?.let { Banner(it) }
        Buttons("ذخیره", action.busy, name.isNotBlank() && nationalId.isNotBlank() && birthOk, {
            action.run({ payroll.updateEmployeeDetails(UpdateEmployeeDetails(id.value, e.scope, e.id, name, nationalId, birth)) }) { onDone() }
        }, onDone)
    }

    @Composable
    private fun SalaryForm(e: Employee, onDone: () -> Unit) {
        val session = LocalSession.current
        // A raise usually starts with a month: the first day of next month is offered.
        val next = remember(session.today) { Fa.jalali(session.today).let { j -> if (j.month == 12) Fa.fromJalali(j.year + 1, 1, 1) else Fa.fromJalali(j.year, j.month + 1, 1) } }
        var amount by rememberSaveable { mutableStateOf<Money?>(null) }
        var from by rememberSaveable { mutableStateOf(next) }
        var reason by rememberSaveable { mutableStateOf("") }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        var type by rememberSaveable { mutableStateOf(e.payTypeOn(session.today)) }
        Picker("نوع پرداخت", PayType.entries.map { Choice(it, payTypeName(it)) }, type, { type = it })
        MoneyInput(rateLabel(type) + "ی تازه", amount, { amount = it })
        DateInput("از تاریخ", from, { from = it }, session.today)
        TextInput("دلیل", reason, { reason = it }, placeholder = "افزایش سالانه، ارتقا…")
        Text("ماه‌های قبل از این تاریخ با حقوق قبلی حساب می‌شوند؛ اگر تاریخ وسط ماه باشد، آن ماه روز به روز حساب می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
        action.error?.let { Banner(it) }
        Buttons("ثبت تغییر حقوق", action.busy, amount != null, {
            action.run({ payroll.changeSalary(ChangeSalary(id.value, e.scope, e.id, from, amount!!, reason, type)) }) { onDone() }
        }, onDone)
    }

    @Composable
    private fun PinForm(e: Employee) {
        var pin by rememberSaveable { mutableStateOf("") }
        val action = rememberAction()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInput("رمز ساعت‌زنی (۴ تا ۶ رقم)", pin, { pin = it }, Modifier.weight(1f), keyboard = KeyboardType.NumberPassword, secret = true)
            Text("ثبت", style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable(enabled = pin.length >= 4 && !action.busy) {
                action.run({ shifts.setPin(SetClockPin(GlobalId.new(), e.scope, e.id, pin)) }) { pin = "" }
            }.padding(8.dp))
        }
        Text("پنج رمز اشتباه پشت سر هم ساعت‌زنی این نفر را ۵ دقیقه قفل می‌کند و هر اشتباه بعدی مدت قفل را دو برابر؛ رمز تازه قفل را باز می‌کند.",
            style = SabouType.caption, color = Sabou.colors.muted)
        action.error?.let { Banner(it) }
    }

    @Composable
    private fun EndForm(e: Employee, onDone: () -> Unit) {
        val session = LocalSession.current
        var lastDay by rememberSaveable { mutableStateOf(session.today) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        DateInput("آخرین روز کار", lastDay, { lastDay = it }, session.today)
        Text("حقوق ماه آخر به نسبت روزهای کار تا این تاریخ حساب می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
        action.error?.let { Banner(it) }
        Buttons("ثبت پایان همکاری", action.busy, true, {
            action.run({ payroll.endEmployment(EndEmployment(id.value, e.scope, e.id, lastDay)) }) { onDone() }
        }, onDone)
    }

    @Composable
    private fun RehireForm(e: Employee, onDone: () -> Unit) {
        val session = LocalSession.current
        var start by rememberSaveable { mutableStateOf(session.today) }
        var amount by rememberSaveable { mutableStateOf<Money?>(e.salaryOn(e.endDate ?: session.today)) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        DateInput("شروع دوباره", start, { start = it }, session.today)
        MoneyInput("حقوق ماهانه", amount, { amount = it })
        Text("همان شماره پرسنلی و همان پرونده؛ همکاری قبلی در سابقه می‌ماند.", style = SabouType.caption, color = Sabou.colors.muted)
        action.error?.let { Banner(it) }
        Buttons("ثبت بازگشت به کار", action.busy, amount != null, {
            action.run({ payroll.rehire(RehireEmployee(id.value, e.scope, e.id, start, amount!!)) }) { onDone() }
        }, onDone)
    }

    // ------------------------------------------------------------ Payroll settings

    /** The restaurant's own payroll options: breaks, overtime, lateness (owner). */
    @Composable
    fun Settings(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { payrollSettings.current() }
        Column(Modifier.fillMaxSize()) {
            Header("تنظیمات حقوق و کارکرد", onBack = nav.back)
            Page {
                Loaded(data) { current -> SettingsForm(current) }
            }
        }
    }

    @Composable
    private fun SettingsForm(current: PayrollSettings) {
        var deduct by rememberSaveable { mutableStateOf(current.deductPlannedBreaks) }
        var photo by rememberSaveable { mutableStateOf(current.clockPhotoRequired) }
        var mode by rememberSaveable { mutableStateOf(current.overtimeMode) }
        var rate by rememberSaveable { mutableStateOf<Money?>(current.overtimeHourlyRate.takeIf { it.rial > 0 }) }
        var minimum by rememberSaveable { mutableStateOf(Fa.number(current.overtimeMinimumMinutes.toLong())) }
        var grace by rememberSaveable { mutableStateOf(Fa.number(current.lateGraceMinutes.toLong())) }
        val action = rememberAction()
        var saved by rememberSaveable { mutableStateOf(false) }
        val min = Fa.latinDigits(minimum).trim().toIntOrNull()
        val lateGrace = Fa.latinDigits(grace).trim().toIntOrNull()
        FormCard("کارکرد") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { deduct = !deduct }) {
                Checkbox(checked = deduct, onCheckedChange = { deduct = it })
                Text("استراحت برنامه‌ی شیفت از کارکرد کم شود", style = SabouType.body, color = Sabou.colors.ink)
            }
            Text("خاموش (پیش‌فرض): کل زمان حضور کارکرد است. روشن: استراحت شیفت از کارکرد کم می‌شود، مگر کسی برای استراحت خروج زده باشد؛ و فقط از کارکردی که از استراحت بیشتر است.",
                style = SabouType.caption, color = Sabou.colors.muted)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { photo = !photo }) {
                Checkbox(checked = photo, onCheckedChange = { photo = it })
                Text("هنگام ساعت‌زنی عکس گرفته شود", style = SabouType.body, color = Sabou.colors.ink)
            }
            Text("برای جلوگیری از ساعت زدن به‌جای دیگری؛ عکس در «کارکرد روز» کنار همان ساعت دیده می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
            TextInput("تأخیر تا چند دقیقه نادیده گرفته شود", grace, { grace = it }, keyboard = KeyboardType.Number,
                error = if (lateGrace == null || lateGrace !in 0..60) "عددی بین ۰ تا ۶۰" else null)
        }
        FormCard("اضافه‌کار") {
            Picker("نحوه‌ی محاسبه", listOf(
                Choice(OvertimeMode.STATUTORY, "طبق قانون", "حقوق ماهانه ÷ ساعت کار ماه × ضریب اضافه‌کار (پارامترهای قانونی)"),
                Choice(OvertimeMode.FIXED_HOURLY, "مبلغ ثابت برای هر ساعت"),
                Choice(OvertimeMode.NONE, "پرداخت نمی‌شود", "فقط ثبت می‌شود"),
            ), mode, { mode = it })
            if (mode == OvertimeMode.FIXED_HOURLY) MoneyInput("مبلغ هر ساعت اضافه‌کار", rate, { rate = it })
            TextInput("اضافه‌کار کمتر از چند دقیقه در روز حساب نشود", minimum, { minimum = it }, keyboard = KeyboardType.Number,
                error = if (min == null || min !in 0..240) "عددی بین ۰ تا ۲۴۰" else null)
        }
        if (saved) Banner("ذخیره شد. لیست‌های حقوقی که هنوز تأیید نشده‌اند را دوباره محاسبه کنید.", ChipKind.PRIMARY)
        action.error?.let { Banner(it) }
        PrimaryButton("ذخیره", {
            val s = current.copy(deductPlannedBreaks = deduct, overtimeMode = mode, overtimeHourlyRate = rate ?: Money.ZERO,
                overtimeMinimumMinutes = min!!, lateGraceMinutes = lateGrace!!, clockPhotoRequired = photo)
            action.run({ payrollSettings.save(SavePayrollSettings(GlobalId.new(), s)) }) { saved = true }
        }, enabled = min != null && min in 0..240 && lateGrace != null && lateGrace in 0..60 && (mode != OvertimeMode.FIXED_HOURLY || rate != null) && !action.busy, busy = action.busy)
    }
}
