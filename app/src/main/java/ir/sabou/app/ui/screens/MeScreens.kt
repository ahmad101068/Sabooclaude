package ir.sabou.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.SabouApplication
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.Confirm
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.MoneyInput
import ir.sabou.app.ui.components.NavRow
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.QuantityInput
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.restoreMessage
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.payroll.DefinePayrollPolicy
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.payroll.TaxBracket
import ir.sabou.platform.Permission
import ir.sabou.platform.Role
import ir.sabou.sales.CustomerType
import ir.sabou.sales.RegisterCustomer
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.TreasuryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MeScreens {

    fun roleName(r: Role) = when (r) {
        Role.OWNER -> "مالک"
        Role.MANAGER -> "مدیر شعبه"
        Role.ACCOUNTANT -> "حسابدار"
        Role.CASHIER -> "صندوقدار"
        Role.STOREKEEPER -> "انباردار"
        Role.RESTRICTED -> "فقط مشاهده محدود"
    }

    @Composable
    fun Hub(nav: Nav, onSignOut: () -> Unit) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("من")
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SCard {
                        Text(session.actor.displayName, style = SabouType.section, color = Sabou.colors.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip(roleName(session.actor.role), ChipKind.PRIMARY) }
                        BranchSwitcher()
                    }
                }
                item { SectionTitle("تنظیمات") }
                if (session.can(Permission.USER_MANAGE)) {
                    item { NavRow(R.drawable.ic_branch, "شعب", onClick = { nav.go(Route.Branches) }) }
                    item { NavRow(R.drawable.ic_person, "کاربران و دسترسی‌ها", onClick = { nav.go(Route.Users) }) }
                }
                if (session.can(Permission.TREASURY_ACCOUNT_MANAGE)) item { NavRow(R.drawable.ic_bank, "صندوق‌ها و حساب‌های بانکی", onClick = { nav.go(Route.Accounts) }) }
                if (session.can(Permission.INVENTORY_ITEM_MANAGE)) item { NavRow(R.drawable.ic_operations, "کالاها", onClick = { nav.go(Route.Items) }) }
                if (session.can(Permission.INVENTORY_LOCATION_MANAGE)) item { NavRow(R.drawable.ic_branch, "انبارها و آشپزخانه‌ها", onClick = { nav.go(Route.Locations) }) }
                if (session.can(Permission.RECIPE_MANAGE)) item { NavRow(R.drawable.ic_recipe, "منو و رسپی", onClick = { nav.go(Route.Menu) }) }
                if (session.can(Permission.CUSTOMER_MANAGE)) item { NavRow(R.drawable.ic_person, "مشتریان اعتباری", onClick = { nav.go(Route.Customers) }) }
                if (session.can(Permission.PAYROLL_APPROVE)) item { NavRow(R.drawable.ic_payroll, "پارامترهای قانونی حقوق", "بیمه و مالیات هر سال", onClick = { nav.go(Route.Policies) }) }
                if (session.can(Permission.BACKUP_CREATE)) item { NavRow(R.drawable.ic_backup, "پشتیبان‌گیری و بازیابی", onClick = { nav.go(Route.Backup) }) }
                item { SecondaryButton("خروج از حساب", onSignOut, danger = true) }
            }
        }
    }

    // ------------------------------------------------------------ Branches and users

    @Composable
    fun Branches(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.branches() }
        var name by rememberSaveable { mutableStateOf("") }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("شعب", onBack = nav.back)
            Page {
                Loaded(data) { list -> list.forEach { b -> SCard { Text(b.name, style = SabouType.bodyStrong, color = Sabou.colors.ink) } } }
                FormCard("شعبه جدید") {
                    TextInput("نام شعبه", name, { name = it })
                    action.error?.let { Banner(it) }
                    PrimaryButton("افزودن", { action.run({ identity.createBranch(name) }) { name = "" } }, enabled = name.trim().length >= 2, busy = action.busy)
                }
            }
        }
    }

    @Composable
    fun Users(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.users() to overview.allBranches().filter { it.isActive } }
        var username by rememberSaveable { mutableStateOf("") }
        var display by rememberSaveable { mutableStateOf("") }
        var role by rememberSaveable { mutableStateOf(Role.CASHIER) }
        var pin by remember { mutableStateOf("") }
        val grants = ir.sabou.app.ui.rememberValueList<BranchId> { emptyList() }
        var editing by remember { mutableStateOf<ir.sabou.platform.User?>(null) }
        var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }   // holds an action: not saveable
        val action = rememberAction()
        pending?.let { (title, act) -> Confirm(title, "این تغییر در سوابق ممیزی ثبت می‌شود.", "تأیید", act, { pending = null }) }
        Column(Modifier.fillMaxSize()) {
            Header("کاربران", onBack = nav.back)
            Page {
                action.error?.let { Banner(it) }
                Loaded(data) { (users, branches) ->
                    users.forEach { u ->
                        SCard(onClick = if (u.id != session.actor.userId) ({ editing = u }) else null) {
                            Text("${u.displayName} (${u.username})", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            Text(roleName(u.role) + (if (!u.isActive) " · غیرفعال" else "") +
                                (if (u.role != Role.OWNER) " · " + branches.filter { it.id in u.branchGrants }.joinToString("، ") { it.name } else ""),
                                style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                    editing?.let { u -> key(u.id) { EditUser(u, branches, { editing = null }) { title, act -> pending = title to act } } }
                    FormCard("کاربر جدید") {
                        TextInput("نام نمایشی", display, { display = it })
                        TextInput("نام کاربری (لاتین)", username, { username = it })
                        Picker("نقش", Role.entries.filter { it != Role.OWNER }.map { Choice(it, roleName(it)) }, role, { role = it })
                        GrantsPicker(branches, grants)
                        TextInput("رمز اولیه (۶ تا ۱۲ رقم)", pin, { pin = it }, keyboard = KeyboardType.NumberPassword, secret = true)
                        PrimaryButton("ایجاد کاربر", {
                            action.run({ identity.createUser(username, display, role, grants.toSet(), Fa.latinDigits(pin).toCharArray()) }) {
                                username = ""; display = ""; pin = ""; grants.clear()
                            }
                        }, enabled = username.isNotBlank() && display.isNotBlank() && pin.length >= 6 && grants.isNotEmpty(), busy = action.busy)
                    }
                }
            }
        }
    }

    @Composable
    private fun GrantsPicker(branches: List<ir.sabou.platform.Branch>, grants: MutableList<BranchId>) {
        Text("دسترسی به شعب", style = SabouType.caption, color = Sabou.colors.muted)
        branches.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (b.id in grants) grants.remove(b.id) else grants.add(b.id) }) {
                Checkbox(checked = b.id in grants, onCheckedChange = { on -> if (on) grants.add(b.id) else grants.remove(b.id) })
                Text(b.name, style = SabouType.body, color = Sabou.colors.ink)
            }
        }
    }

    /** Edit role and branches (never touches the PIN or active state), reactivate, deactivate, reset PIN. */
    @Composable
    private fun EditUser(u: ir.sabou.platform.User, branches: List<ir.sabou.platform.Branch>, close: () -> Unit, confirm: (String, () -> Unit) -> Unit) {
        var role by rememberSaveable { mutableStateOf(u.role) }
        val grants = ir.sabou.app.ui.rememberValueList { u.branchGrants.toList() }
        var newPin by remember { mutableStateOf("") }
        val action = rememberAction()
        FormCard("ویرایش ${u.displayName}") {
            Picker("نقش", Role.entries.map { Choice(it, roleName(it)) }, role, { role = it })
            if (role != Role.OWNER) GrantsPicker(branches, grants)
            action.error?.let { Banner(it) }
            PrimaryButton("ذخیره نقش و دسترسی", {
                action.run({ identity.updateUser(u.id, role, if (role == Role.OWNER) emptySet() else grants.toSet()) }) { close() }
            }, enabled = role == Role.OWNER || grants.isNotEmpty(), busy = action.busy)
            TextInput("رمز جدید برای این کاربر", newPin, { newPin = it }, keyboard = KeyboardType.NumberPassword, secret = true)
            SecondaryButton("تعیین رمز جدید", {
                confirm("رمز ${u.displayName} عوض شود؟") { action.run({ identity.resetPin(u.id, Fa.latinDigits(newPin).toCharArray()) }) { close() } }
            }, enabled = newPin.length >= 6)
            if (u.isActive) {
                SecondaryButton("غیرفعال کردن", { confirm("${u.displayName} غیرفعال شود؟") { action.run({ identity.deactivateUser(u.id) }) { close() } } }, danger = true)
            } else {
                SecondaryButton("فعال کردن دوباره", { confirm("${u.displayName} دوباره فعال شود؟") { action.run({ identity.reactivateUser(u.id) }) { close() } } })
            }
            SecondaryButton("بستن", close)
        }
    }

    // ------------------------------------------------------------ Master data

    private val units = StockUnit.entries.map { Choice(it, unitName(it)) }

    @Composable
    fun Items(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.items() }
        var name by rememberSaveable { mutableStateOf("") }
        var unit by rememberSaveable { mutableStateOf(StockUnit.KILOGRAM) }
        var minimum by rememberSaveable { mutableStateOf<Quantity?>(Quantity.ZERO) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("کالاها", onBack = nav.back)
            Page {
                Loaded(data) { list ->
                    if (list.isEmpty()) EmptyState("هنوز کالایی تعریف نشده است.")
                    list.forEach { i -> SCard { KeyValue(i.name, unitName(i.unit) + if (!i.minimumStock.isZero) " · حداقل ${Fa.quantity(i.minimumStock)}" else "") } }
                }
                FormCard("کالای جدید") {
                    TextInput("نام کالا", name, { name = it })
                    Picker("واحد", units, unit, { unit = it })
                    QuantityInput("حداقل موجودی", unitName(unit), { minimum = it }, blankAs = Quantity.ZERO)
                    action.error?.let { Banner(it) }
                    PrimaryButton("افزودن", {
                        action.run({ inventory.createItem(CreateItem(id.value, name, unit, minimum ?: Quantity.ZERO)) }) { name = ""; id.value = GlobalId.new() }
                    }, enabled = name.trim().length >= 2 && minimum != null, busy = action.busy)
                }
            }
        }
    }

    @Composable
    fun Locations(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("انبارها", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { overview.locations(branch) }
                var name by rememberSaveable { mutableStateOf("") }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { list -> list.forEach { l -> SCard { Text(l.name, style = SabouType.bodyStrong, color = Sabou.colors.ink) } } }
                    FormCard("انبار یا آشپزخانه جدید") {
                        TextInput("نام", name, { name = it })
                        action.error?.let { Banner(it) }
                        PrimaryButton("افزودن", {
                            action.run({ inventory.createLocation(CreateLocation(id.value, branch, name)) }) { name = ""; id.value = GlobalId.new() }
                        }, enabled = name.trim().length >= 2, busy = action.busy)
                    }
                }
            }
        }
    }

    private class RecipeRow(item: GlobalId?, qty: Quantity?) {
        var item by mutableStateOf(item)
        var qty by mutableStateOf(qty)
    }

    @Composable
    fun Menu(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) {
            val menu = overview.menu()
            Triple(menu.map { it.item }, overview.items().associateBy { it.id }, menu.associate { it.item.id to it.latest })
        }
        var name by rememberSaveable { mutableStateOf("") }
        var menuItem by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var from by rememberSaveable { mutableStateOf(session.today) }
        val rows = ir.sabou.app.ui.rememberRows<RecipeRow>({ listOf(it.item, it.qty) }, { RecipeRow(it[0] as GlobalId?, it[1] as Quantity?) }) { listOf(RecipeRow(null, null)) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("منو و رسپی", onBack = nav.back)
            Page {
                Loaded(data) { (menu, items, latest) ->
                    if (menu.isEmpty()) EmptyState("هنوز آیتم منویی تعریف نشده است.")
                    menu.forEach { m ->
                        SCard {
                            Text(m.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            val v = latest[m.id]
                            if (v == null) Text("رسپی ندارد — فروش آن ثبت نهایی نمی‌شود.", style = SabouType.caption, color = Sabou.colors.danger)
                            else Text("نسخه ${Fa.number(v.version.toLong())} از ${Fa.date(v.effectiveFrom)}: " +
                                v.lines.joinToString("، ") { "${items[it.itemId]?.name ?: ""} ${Fa.quantity(it.quantityPerPortion)}" },
                                style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                    if (session.can(Permission.RECIPE_MANAGE)) {
                        FormCard("آیتم منوی جدید") {
                            TextInput("نام", name, { name = it })
                            action.error?.let { Banner(it) }
                            PrimaryButton("افزودن", { action.run({ inventory.defineMenuItem(DefineMenuItem(GlobalId.new(), name)) }) { name = "" } },
                                enabled = name.trim().length >= 2, busy = action.busy)
                        }
                        FormCard("نسخه جدید رسپی") {
                            Text("مصرف هر پرس. نسخه‌های قبلی تغییر نمی‌کنند؛ فروش هر روز با نسخه معتبر همان روز محاسبه می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                            Picker("آیتم منو", menu.map { Choice(it.id, it.name) }, menuItem, { menuItem = it })
                            DateInput("معتبر از", from, { from = it }, session.today)
                            rows.forEach { r ->
                                key(r) {
                                    Picker("ماده اولیه", items.values.filter { it.isActive }.map { Choice(it.id, it.name, unitName(it.unit)) }, r.item, { r.item = it })
                                    QuantityInput("مقدار برای یک پرس", r.item?.let { items[it] }?.let { unitName(it.unit) } ?: "", { r.qty = it }, value = r.qty)
                                    Divider()
                                }
                            }
                            SecondaryButton("افزودن ماده", { rows.add(RecipeRow(null, null)) })
                            PrimaryButton("انتشار نسخه", {
                                val lines = rows.map { RecipeLine(it.item!!, it.qty!!) }
                                action.run({ inventory.publishRecipe(PublishRecipe(GlobalId.new(), menuItem!!, from, lines)) }) { rows.clear(); rows.add(RecipeRow(null, null)) }
                            }, enabled = menuItem != null && rows.all { it.item != null && it.qty != null && !it.qty!!.isZero }, busy = action.busy)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Accounts(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.treasury() to overview.branches() }
        var name by rememberSaveable { mutableStateOf("") }
        var kind by rememberSaveable { mutableStateOf(TreasuryKind.CASH) }
        var orgLevel by rememberSaveable { mutableStateOf(false) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("صندوق‌ها و حساب‌ها", onBack = nav.back) { BranchSwitcher() }
            Page {
                Loaded(data) { (balances, _) ->
                    balances.forEach { b -> SCard { KeyValue("${b.account.name} · ${kindName(b.account.kind)}", if (b.account.scope == Scope.Organization) "سازمان" else "شعبه") } }
                }
                FormCard("حساب جدید") {
                    TextInput("نام", name, { name = it }, placeholder = "مثلاً کارت‌خوان ملت")
                    Picker("نوع", TreasuryKind.entries.map { Choice(it, kindName(it)) }, kind, { kind = it })
                    if (session.can(Permission.ORGANIZATION_DATA)) {
                        Row(Modifier.clickable { orgLevel = !orgLevel }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = orgLevel, onCheckedChange = { orgLevel = it })
                            Text("حساب سازمان (مشترک همه شعب، مثل حساب بانکی اصلی)", style = SabouType.body, color = Sabou.colors.ink)
                        }
                    }
                    action.error?.let { Banner(it) }
                    val scope: Scope? = if (orgLevel) Scope.Organization else session.branch
                    PrimaryButton("افزودن", {
                        action.run({ treasury.openAccount(OpenTreasuryAccount(id.value, scope!!, name, kind)) }) { name = ""; id.value = GlobalId.new() }
                    }, enabled = scope != null && name.trim().length >= 2, busy = action.busy)
                }
            }
        }
    }

    @Composable
    fun Customers(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("مشتریان اعتباری", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { overview.customers(branch).map { it.customer to it.owed } }
                var name by rememberSaveable { mutableStateOf("") }
                var phone by rememberSaveable { mutableStateOf("") }
                var type by rememberSaveable { mutableStateOf(CustomerType.COMPANY) }
                var limit by rememberSaveable { mutableStateOf<Money?>(null) }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { list ->
                        list.forEach { (c, owed) -> SCard { KeyValue(c.name, "بدهی ${Fa.toman(owed)} از سقف ${Fa.tomanShort(c.creditLimit.rial)}") } }
                    }
                    FormCard("مشتری جدید") {
                        TextInput("نام", name, { name = it })
                        Picker("نوع", listOf(Choice(CustomerType.COMPANY, "شرکت"), Choice(CustomerType.PERSON, "شخص")), type, { type = it })
                        TextInput("تلفن", phone, { phone = it }, keyboard = KeyboardType.Phone)
                        MoneyInput("سقف اعتبار", limit, { limit = it })
                        action.error?.let { Banner(it) }
                        PrimaryButton("افزودن", {
                            action.run({ salesOps.registerCustomer(RegisterCustomer(id.value, branch, name, type, phone, limit!!)) }) { name = ""; phone = ""; limit = null; id.value = GlobalId.new() }
                        }, enabled = name.trim().length >= 2 && limit != null, busy = action.busy)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ Payroll policies

    private class BracketRow(upTo: Money? = null, rate: String = "") { var upTo by mutableStateOf(upTo); var rate by mutableStateOf(rate) }

    @Composable
    fun Policies(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.policies() }
        val j = Fa.jalali(session.today)
        var version by rememberSaveable { mutableStateOf(Fa.digits(j.year.toString())) }
        var fromDate by rememberSaveable { mutableStateOf(Fa.fromJalali(j.year, 1, 1)) }
        var toDate by rememberSaveable { mutableStateOf(Fa.fromJalali(j.year, 12, Fa.monthLength(j.year, 12))) }
        var hours by rememberSaveable { mutableStateOf("۱۹۲") }
        var overtime by rememberSaveable { mutableStateOf("۱۴۰") }
        var empIns by rememberSaveable { mutableStateOf("۷") }
        var erIns by rememberSaveable { mutableStateOf("۲۰") }
        var unemp by rememberSaveable { mutableStateOf("۳") }
        var exemptNum by rememberSaveable { mutableStateOf("۲") }
        var exemptDen by rememberSaveable { mutableStateOf("۷") }
        var invalid by rememberSaveable { mutableStateOf<String?>(null) }
        var maxInsurable by rememberSaveable { mutableStateOf<Money?>(null) }
        val brackets = ir.sabou.app.ui.rememberRows<BracketRow>({ listOf(it.upTo, it.rate) }, { BracketRow(it[0] as Money?, it[1] as String) }) { listOf(BracketRow(), BracketRow()) }
        var topRate by rememberSaveable { mutableStateOf("") }
        var prorationDays by rememberSaveable { mutableStateOf("۳۰") }
        val action = rememberAction()
        fun pct(t: String) = Fa.parseQuantity(t)?.let { (it.micros * 100 / Quantity.SCALE).toInt() }   // percent → basis points
        Column(Modifier.fillMaxSize()) {
            Header("پارامترهای قانونی حقوق", onBack = nav.back)
            Page {
                Banner("این مقادیر را هر سال پس از تأیید مشاور مالیاتی/بیمه وارد کنید. برای دوره‌ای که پارامتر ندارد، محاسبه حقوق انجام نمی‌شود.", ChipKind.ACCENT)
                Loaded(data) { list ->
                    list.forEach { p ->
                        SCard {
                            KeyValue("نسخه ${p.version}", "${Fa.date(p.from)} تا ${Fa.date(p.to)}")
                            Text("ماه ناقص: تقسیم بر ${Fa.number(p.prorationDays.toLong())} روز", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                }
                FormCard("نسخه جدید") {
                    TextInput("نام نسخه", version, { version = it })
                    DateInput("از", fromDate, { fromDate = it }, session.today)
                    DateInput("تا", toDate, { toDate = it }, session.today)
                    TextInput("ساعت کار موظف ماهانه", hours, { hours = it }, keyboard = KeyboardType.Number)
                    TextInput("ضریب اضافه‌کار (درصد)", overtime, { overtime = it }, keyboard = KeyboardType.Number)
                    TextInput("روزهای مبنای حقوق ماه ناقص", prorationDays, { prorationDays = it }, keyboard = KeyboardType.Number)
                    Text("حقوق کسی که وسط ماه شروع یا تمام کرده = حقوق ماهانه × روزهای کار ÷ این عدد (معمولاً ۳۰).", style = SabouType.caption, color = Sabou.colors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextInput("بیمه سهم کارگر ٪", empIns, { empIns = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                        TextInput("سهم کارفرما ٪", erIns, { erIns = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                        TextInput("بیکاری ٪", unemp, { unemp = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                    }
                    Text("سهم معاف از مالیات در بیمه سهم کارگر (کسر)", style = SabouType.caption, color = Sabou.colors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextInput("صورت", exemptNum, { exemptNum = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                        TextInput("مخرج", exemptDen, { exemptDen = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                    }
                    MoneyInput("سقف دستمزد مشمول بیمه (ماهانه)", maxInsurable, { maxInsurable = it })
                    Text("پله‌های مالیات ماهانه (تا سقف ← نرخ)", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                    brackets.forEach { b ->
                        key(b) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MoneyInput("تا مبلغ", b.upTo, { b.upTo = it }, Modifier.weight(0.65f))
                                TextInput("نرخ ٪", b.rate, { b.rate = it }, Modifier.weight(0.35f), keyboard = KeyboardType.Decimal)
                            }
                        }
                    }
                    SecondaryButton("افزودن پله", { brackets.add(BracketRow()) })
                    TextInput("نرخ مازاد بر آخرین پله ٪", topRate, { topRate = it }, keyboard = KeyboardType.Decimal)
                    action.error?.let { Banner(it) }
                    val minutes = Fa.parseLong(hours)?.let { (it * 60).toInt() }
                    invalid?.let { Banner(it) }
                    val num = Fa.parseLong(exemptNum)?.toInt()
                    val den = Fa.parseLong(exemptDen)?.toInt()
                    val ready = minutes != null && Fa.parseLong(overtime) != null && pct(empIns) != null && pct(erIns) != null && pct(unemp) != null &&
                        num != null && den != null && den > 0 && num in 0..den && Fa.parseLong(prorationDays)?.let { it in 28L..31L } == true &&
                        maxInsurable != null && brackets.all { it.upTo != null && pct(it.rate) != null } && pct(topRate) != null
                    PrimaryButton("ثبت پارامترها", {
                        invalid = null
                        val policy = try { StatutoryPolicy(
                            version = Fa.latinDigits(version.trim()), from = fromDate, to = toDate, standardMonthlyMinutes = minutes!!,
                            overtimeMultiplierPercent = Fa.parseLong(overtime)!!.toInt(),
                            employeeInsuranceBp = pct(empIns)!!, employerInsuranceBp = pct(erIns)!!, unemploymentInsuranceBp = pct(unemp)!!,
                            maxInsurableMonthly = maxInsurable!!, insuranceTaxExemptNumerator = num!!, insuranceTaxExemptDenominator = den!!,
                            taxBrackets = brackets.sortedBy { it.upTo!!.rial }.map { TaxBracket(it.upTo, pct(it.rate)!!) } + TaxBracket(null, pct(topRate)!!),
                            prorationDays = Fa.parseLong(prorationDays)!!.toInt(),
                        ) } catch (e: IllegalArgumentException) {
                            invalid = "مقادیر با هم سازگار نیستند: نرخ‌ها باید بین ۰ و ۱۰۰٪ باشند، پله‌ها صعودی و بدون تکرار، و جمع بیمه سهم کارگر و بالاترین نرخ مالیات کمتر از ۱۰۰٪."
                            return@PrimaryButton
                        }
                        action.run({ payrollPolicies.define(DefinePayrollPolicy(GlobalId.new(), policy)) })
                    }, enabled = ready, busy = action.busy)
                }
            }
        }
    }

    // ------------------------------------------------------------ Backup

    @Composable
    fun Backup(nav: Nav) {
        val session = LocalSession.current
        val container = (LocalContext.current.applicationContext as SabouApplication).container
        val scope = rememberCoroutineScope()
        var password by remember { mutableStateOf("") }
        var password2 by remember { mutableStateOf("") }
        var restorePassword by remember { mutableStateOf("") }
        var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var restoreUri by remember { mutableStateOf<Uri?>(null) }
        var confirmRestore by remember { mutableStateOf(false) }
        var confirmReset by remember { mutableStateOf(false) }

        fun work(ok: String, block: () -> Unit) {
            busy = true; message = null
            scope.launch {
                val error = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
                busy = false
                message = if (error == null) ok to true else restoreMessage(error) to false
            }
        }

        val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                val pw = password.toCharArray()
                work("پشتیبان با موفقیت ذخیره شد. فایل و رمز را جدا از هم نگه دارید.") { container.backup(session.core, pw, uri) }
                password = ""; password2 = ""
            }
        }
        val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

        Column(Modifier.fillMaxSize()) {
            Header("پشتیبان‌گیری و بازیابی", onBack = nav.back)
            Page {
                message?.let { (text, ok) -> Banner(text, if (ok) ChipKind.PRIMARY else ChipKind.DANGER, if (ok) R.drawable.ic_check else R.drawable.ic_alert) }
                FormCard("ساخت فایل پشتیبان") {
                    Text("کل داده‌ها با رمزی که انتخاب می‌کنید رمزگذاری می‌شود. بدون این رمز، فایل قابل بازیابی نیست.", style = SabouType.caption, color = Sabou.colors.muted)
                    TextInput("رمز پشتیبان (حداقل ۱۰ نویسه)", password, { password = it }, secret = true)
                    TextInput("تکرار رمز", password2, { password2 = it }, secret = true, error = if (password2.isNotEmpty() && password2 != password) "یکسان نیست" else null)
                    PrimaryButton("ساخت پشتیبان", { create.launch("sabou-${Fa.latinDigits(Fa.date(session.today)).replace('/', '-')}.sabou") },
                        enabled = password.length >= 10 && password == password2, busy = busy)
                }
                if (session.can(Permission.BACKUP_RESTORE)) {
                    FormCard("بازیابی از پشتیبان") {
                        Text("داده‌های فعلی با محتوای فایل جایگزین می‌شود. فایل پیش از جایگزینی کامل بررسی می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                        SecondaryButton(if (restoreUri == null) "انتخاب فایل" else "فایل انتخاب شد ✓", { open.launch(arrayOf("*/*")) })
                        TextInput("رمز فایل", restorePassword, { restorePassword = it }, secret = true)
                        PrimaryButton("بازیابی", { confirmRestore = true }, enabled = restoreUri != null && restorePassword.length >= 10, busy = busy)
                    }
                }
                if (session.can(Permission.FACTORY_RESET)) {
                    FormCard("پاک کردن همه داده‌ها") {
                        SecondaryButton("بازنشانی کامل", { confirmReset = true }, danger = true)
                    }
                }
            }
        }
        if (confirmRestore) {
            Confirm("بازیابی پشتیبان؟", "همه داده‌های فعلی با پشتیبان جایگزین می‌شود و باید دوباره وارد شوید.", "بازیابی کن", onConfirm = {
                val uri = restoreUri!!
                val pw = restorePassword.toCharArray()
                work("بازیابی انجام شد.") { container.restore(session.core, pw, uri) }
            }, onDismiss = { confirmRestore = false }, danger = true)
        }
        if (confirmReset) {
            ir.sabou.app.ui.EraseConfirm(onConfirm = {
                work("همه داده‌ها پاک شد.") { container.factoryReset(session.core) }
            }, onDismiss = { confirmReset = false })
        }
    }
}
