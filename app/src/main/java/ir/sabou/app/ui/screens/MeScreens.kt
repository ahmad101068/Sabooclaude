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
import ir.sabou.app.ui.components.ItemQuantityInput
import ir.sabou.app.ui.components.QuantityInput
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.Segmented
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.restoreMessage
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.DeleteMenuItem
import ir.sabou.inventory.UpdateMenuItem
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

    /** The groups of the Me tab, each a page of settings (owner's request, 2026-10: same style as Operations). */
    enum class Group(val title: String, val subtitle: String, val icon: Int) {
        RESTAURANT("رستوران و دسترسی‌ها", "شعب، کاربران، صندوق‌ها و انبارها", R.drawable.ic_branch),
        CATALOG("کالا، منو و مشتریان", "کالاها، منو و رسپی، مشتریان اعتباری", R.drawable.ic_recipe),
        RULES("حقوق، مالیات و بودجه", "پارامترهای حقوق، قانون‌های تأیید، اطلاعات مالیاتی، بودجه", R.drawable.ic_finance),
        SAFETY("امنیت و داده‌ها", "رمز و اعلان‌ها، پشتیبان، دو گوشی", R.drawable.ic_lock),
    }

    private class Entry(val icon: Int, val title: String, val subtitle: String?, val route: Route)

    /** What this person may open in [group]; a group with nothing in it is not shown. */
    private fun entries(group: Group, session: ir.sabou.app.ui.AppSession, replica: Boolean): List<Entry> = when (group) {
        Group.RESTAURANT -> listOfNotNull(
            Entry(R.drawable.ic_branch, "شعب", null, Route.Branches).takeIf { session.can(Permission.USER_MANAGE) },
            Entry(R.drawable.ic_person, "کاربران و دسترسی‌ها", null, Route.Users).takeIf { session.can(Permission.USER_MANAGE) },
            Entry(R.drawable.ic_bank, "صندوق‌ها و حساب‌های بانکی", null, Route.Accounts).takeIf { session.can(Permission.TREASURY_ACCOUNT_MANAGE) },
            Entry(R.drawable.ic_branch, "انبارها و آشپزخانه‌ها", null, Route.Locations).takeIf { session.can(Permission.INVENTORY_LOCATION_MANAGE) },
        )
        Group.CATALOG -> listOfNotNull(
            Entry(R.drawable.ic_operations, "کالاها", null, Route.Items).takeIf { session.can(Permission.INVENTORY_ITEM_MANAGE) },
            Entry(R.drawable.ic_recipe, "منو و رسپی", null, Route.Menu).takeIf { session.can(Permission.RECIPE_MANAGE) },
            Entry(R.drawable.ic_person, "مشتریان اعتباری", null, Route.Customers).takeIf { session.can(Permission.CUSTOMER_MANAGE) },
        )
        Group.RULES -> listOfNotNull(
            Entry(R.drawable.ic_payroll, "پارامترهای قانونی حقوق", "بیمه و مالیات هر سال", Route.Policies).takeIf { session.can(Permission.PAYROLL_APPROVE) },
            Entry(R.drawable.ic_clock, "تنظیمات حقوق و کارکرد", "اضافه‌کار، شب‌کاری، مرخصی، عیدی، سنوات", Route.PayrollSettings).takeIf { session.can(Permission.PAYROLL_APPROVE) },
            Entry(R.drawable.ic_payroll, "عوامل حقوقی", "مسکن، بن، حق اولاد، پایه سنوات، کسور ماهانه", Route.PayElements).takeIf { session.can(Permission.PAYROLL_APPROVE) },
            Entry(R.drawable.ic_check, "قانون‌های تأیید فاکتور", "چه فاکتوری پیش از پرداخت چند تأیید بخواهد", Route.ApprovalRules).takeIf { session.can(Permission.APPROVAL_RULES) },
            Entry(R.drawable.ic_finance, "اطلاعات مالیاتی", "شناسه‌ها و نرخ ارزش افزوده؛ آمادگی سامانه‌ی مودیان", Route.TaxSettings).takeIf { session.can(Permission.TAX_SETTINGS) },
            Entry(R.drawable.ic_finance, "بودجه", "مبلغ ماهانه‌ی هر حساب درآمد و هزینه", Route.Budget).takeIf { session.can(Permission.BUDGET_MANAGE) },
        )
        Group.SAFETY -> listOfNotNull(
            Entry(R.drawable.ic_lock, "امنیت و اعلان‌ها", "تغییر رمز، اثر انگشت، اعلان هشدارها" + if (session.actor.role == Role.OWNER) "، کد بازیابی" else "", Route.Security),
            // Backups are the main phone's: a second phone's data is a copy of it (ADR-0042).
            Entry(R.drawable.ic_backup, "پشتیبان‌گیری و بازیابی", null, Route.Backup).takeIf { session.can(Permission.BACKUP_CREATE) && !replica },
            Entry(R.drawable.ic_transfer, "دو گوشی، یک دفتر", "همگام‌سازی گوشی اصلی و گوشی دوم روی وای‌فای", Route.TwoPhones),
        )
    }

    @Composable
    fun Hub(nav: Nav, onSignOut: () -> Unit) {
        val session = LocalSession.current
        val twoPhones = (LocalContext.current.applicationContext as SabouApplication).container.twoPhones
        Column(Modifier.fillMaxSize()) {
            Header("من")
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SCard {
                        Text(session.actor.displayName, style = SabouType.section, color = Sabou.colors.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip(roleName(session.actor.role), ChipKind.PRIMARY) }
                        BranchSwitcher()
                    }
                }
                Group.entries.forEach { g ->
                    val list = entries(g, session, twoPhones.isReplica)
                    when (list.size) {
                        0 -> Unit
                        // One setting in a group needs no extra page.
                        1 -> item(g.name) { list.single().let { e -> NavRow(e.icon, e.title, e.subtitle, onClick = { nav.go(e.route) }) } }
                        else -> item(g.name) { NavRow(g.icon, g.title, g.subtitle, onClick = { nav.go(Route.MeGroup(g)) }) }
                    }
                }
                item { SecondaryButton("خروج از حساب", onSignOut, danger = true) }
            }
        }
    }

    @Composable
    fun GroupPage(nav: Nav, group: Group) {
        val session = LocalSession.current
        val twoPhones = (LocalContext.current.applicationContext as SabouApplication).container.twoPhones
        Column(Modifier.fillMaxSize()) {
            Header(group.title, onBack = nav.back)
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val list = entries(group, session, twoPhones.isReplica)
                if (list.isEmpty()) item { EmptyState("دسترسی به این بخش ندارید.") }
                list.forEach { e -> item { NavRow(e.icon, e.title, e.subtitle, onClick = { nav.go(e.route) }) } }
            }
        }
    }

    // ------------------------------------------------------------ Branches and users

    @Composable
    fun Branches(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.branches() }
        var name by rememberSaveable { mutableStateOf("") }
        var renaming by rememberSaveable { mutableStateOf<String?>(null) }
        var newName by rememberSaveable { mutableStateOf("") }
        val action = rememberAction()
        val rename = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("شعب", onBack = nav.back)
            Page {
                Loaded(data) { list ->
                    list.forEach { b ->
                        SCard(onClick = if (session.actor.isOwner) ({ renaming = b.id.value.value.toString(); newName = b.name }) else null) {
                            Text(b.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            if (renaming == b.id.value.value.toString()) {
                                TextInput("نام تازه", newName, { newName = it })
                                rename.error?.let { Banner(it) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PrimaryButton("ذخیره", { rename.run({ identity.renameBranch(b.id, newName) }) { renaming = null } },
                                        Modifier.weight(1f), enabled = newName.trim().length >= 2 && !rename.busy, busy = rename.busy)
                                    SecondaryButton("انصراف", { renaming = null }, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
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
        var prepared by rememberSaveable { mutableStateOf(false) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("کالاها", onBack = nav.back)
            Page {
                Loaded(data) { list ->
                    if (list.isEmpty()) EmptyState("هنوز کالایی تعریف نشده است.")
                    var search by rememberSaveable { mutableStateOf("") }
                    if (list.size > 5) TextInput("جستجو (نام، کد یا گروه)", search, { search = it })
                    val q = Fa.latinDigits(search.trim())
                    var lastCategory: String? = null
                    list.filter { q.isBlank() || it.name.contains(q) || it.code.contains(q) || it.category.contains(q) }
                        .sortedWith(compareBy({ it.category.ifBlank { "\uFFFF" } }, { it.name })).forEach { i ->
                        if (i.category != lastCategory && list.any { it.category.isNotBlank() }) {
                            lastCategory = i.category
                            SectionTitle(i.category.ifBlank { "بدون گروه" })
                        }
                        SCard(onClick = { nav.go(Route.ItemEdit(i.id)) }) {
                            KeyValue(i.name + (if (!i.isActive) " (غیرفعال)" else "") + (if (i.prepared) " · تولید داخلی" else ""),
                                unitName(i.unit) + if (!i.minimumStock.isZero) " · حداقل ${Fa.quantity(i.minimumStock)}" else "")
                            val extra = listOfNotNull(
                                i.code.takeIf { it.isNotBlank() }?.let { "کد ${Fa.digits(it)}" },
                                i.parLevel.takeIf { !it.isZero }?.let { "سطح مطلوب ${Fa.quantity(it)}" },
                                i.shelf.takeIf { it.isNotBlank() }?.let { "محل: $it" },
                                i.allergens.takeIf { it.isNotBlank() }?.let { "آلرژن: $it" },
                            )
                            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                    if (list.isNotEmpty()) Text("برای ویرایش سطح مطلوب، محل نگهداری و آلرژن‌ها روی هر کالا بزنید.", style = SabouType.caption, color = Sabou.colors.muted)
                }
                FormCard("کالای جدید") {
                    TextInput("نام کالا", name, { name = it })
                    Picker("واحد", units, unit, { unit = it })
                    QuantityInput("حداقل موجودی", unitName(unit), { minimum = it }, blankAs = Quantity.ZERO)
                    Row(Modifier.clickable { prepared = !prepared }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = prepared, onCheckedChange = { prepared = it })
                        Text("تولید داخلی (سس، خمیر، …) — با رسپی اقلام آماده ساخته می‌شود", style = SabouType.body, color = Sabou.colors.ink)
                    }
                    action.error?.let { Banner(it) }
                    PrimaryButton("افزودن", {
                        action.run({ inventory.createItem(CreateItem(id.value, name, unit, minimum ?: Quantity.ZERO, prepared)) }) { name = ""; prepared = false; id.value = GlobalId.new() }
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
                    var editing by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                    Loaded(data) { list -> list.forEach { l ->
                        SCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(l.name, style = SabouType.bodyStrong, color = if (l.isActive) Sabou.colors.ink else Sabou.colors.muted, modifier = Modifier.weight(1f))
                                if (!l.isActive) Chip("غیرفعال", ChipKind.NEUTRAL)
                            }
                            if (session.can(Permission.INVENTORY_LOCATION_MANAGE)) {
                                if (editing == l.id) key(l.id) { LocationForm(branch, l) { editing = null } }
                                else Text("ویرایش", style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable { editing = l.id }.padding(vertical = 4.dp))
                            }
                        }
                    } }
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

    @Composable
    private fun LocationForm(branch: ir.sabou.kernel.Scope.Branch, l: ir.sabou.inventory.Location, onDone: () -> Unit) {
        var name by rememberSaveable { mutableStateOf(l.name) }
        var active by rememberSaveable { mutableStateOf(l.isActive) }
        val action = rememberAction()
        TextInput("نام", name, { name = it })
        Row(Modifier.clickable { active = !active }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = active, onCheckedChange = { active = it })
            Text("فعال", style = SabouType.body, color = Sabou.colors.ink)
        }
        if (!active && l.isActive) Text("فقط انبار خالی (بدون موجودی، شمارش در انتظار یا کسری باز) غیرفعال می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
        action.error?.let { Banner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("ذخیره", { action.run({ inventory.updateLocation(ir.sabou.inventory.UpdateLocation(GlobalId.new(), branch, l.id, name, active)) }) { onDone() } },
                Modifier.weight(1f), enabled = name.trim().length >= 2 && !action.busy, busy = action.busy)
            SecondaryButton("انصراف", onDone, Modifier.weight(1f))
        }
    }

    private class RecipeRow(item: GlobalId?, qty: Quantity?, yieldText: String = "") {
        var item by mutableStateOf(item)
        var qty by mutableStateOf(qty)
        /** Usable share after trimming and cooking, percent; blank = 100. */
        var yieldText by mutableStateOf(yieldText)
        val yieldPercent: Int? get() = if (yieldText.isBlank()) 100 else Fa.latinDigits(yieldText).trim().toIntOrNull()?.takeIf { it in 1..100 }
    }

    private class MenuData(
        val menu: List<ir.sabou.inventory.MenuItem>,
        val items: Map<GlobalId, ir.sabou.inventory.Item>,
        val latest: Map<GlobalId, ir.sabou.inventory.RecipeVersion?>,
        val prices: Map<GlobalId, ir.sabou.core.MenuPriceView>,
        val costs: Map<GlobalId, ir.sabou.core.FoodCostRow> = emptyMap(),
        val target: Long? = null,
    )

    @Composable
    fun Menu(nav: Nav) {
        val session = LocalSession.current
        val branch = session.branch
        val seesPrices = session.can(Permission.SALES_VIEW) || session.can(Permission.MENU_PRICE_MANAGE)
        val data by load(session, branch) {
            val menu = overview.menu()
            MenuData(menu.map { it.item }, overview.items().associateBy { it.id }, menu.associate { it.item.id to it.latest },
                if (branch != null && seesPrices) overview.menuPrices(branch, session.today).associateBy { it.item.id } else emptyMap(),
                // Recipe cost today against today's price, for those who manage the menu (ADR-0028).
                if (branch != null && (session.can(Permission.RECIPE_MANAGE) || session.can(Permission.MENU_PRICE_MANAGE)))
                    runCatching { reports.foodCosts(branch, session.today).associateBy { it.item.id } }.getOrDefault(emptyMap()) else emptyMap(),
                salesOps.foodCostTarget())
        }
        var name by rememberSaveable { mutableStateOf("") }
        var menuItem by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var from by rememberSaveable { mutableStateOf(session.today) }
        var renaming by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var newName by rememberSaveable { mutableStateOf("") }
        var deleting by remember { mutableStateOf<ir.sabou.inventory.MenuItem?>(null) }
        val rows = ir.sabou.app.ui.rememberRows<RecipeRow>({ listOf(it.item, it.qty, it.yieldText) }, { RecipeRow(it[0] as GlobalId?, it[1] as Quantity?, it[2] as String) }) { listOf(RecipeRow(null, null)) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("منو و رسپی", onBack = nav.back) { if (seesPrices) BranchSwitcher() }
            Page {
                Loaded(data) { d ->
                    val menu = d.menu; val items = d.items; val latest = d.latest
                    if (menu.isEmpty()) EmptyState("هنوز آیتم منویی تعریف نشده است.")
                    menu.forEach { m ->
                        SCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, style = SabouType.bodyStrong, color = if (m.isActive) Sabou.colors.ink else Sabou.colors.muted, modifier = Modifier.weight(1f))
                                if (!m.isActive) Chip("خارج از منو", ChipKind.NEUTRAL)
                            }
                            d.prices[m.id]?.let { p ->
                                val price = p.price
                                if (price == null) Text("قیمت منو ندارد — هنگام فروش قیمت واحد وارد می‌شود.", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                                else KeyValue(if (p.own) "قیمت (ویژه این شعبه)" else "قیمت", Fa.rial(price) + " ریال", strong = true)
                            }
                            d.costs[m.id]?.let { c ->
                                val over = d.target != null && c.costBp >= d.target
                                Text("بهای مواد هر پرس ${Fa.rial(c.cost)} ریال · ${Fa.percent(c.costBp)} قیمت" + if (over) " · بیش از هدف ${Fa.percent(d.target!!)}" else "",
                                    style = SabouType.caption, color = if (over) Sabou.colors.danger else Sabou.colors.muted)
                            }
                            val v = latest[m.id]
                            if (v == null) Text("رسپی ندارد — فروش آن ثبت نهایی نمی‌شود.", style = SabouType.caption, color = Sabou.colors.danger)
                            else Text("نسخه ${Fa.number(v.version.toLong())} از ${Fa.date(v.effectiveFrom)}: " +
                                v.lines.joinToString("، ") { "${items[it.itemId]?.name ?: ""} ${Fa.quantity(it.quantityPerPortion)}" + if (it.yieldPercent < 100) " (بازده ${Fa.number(it.yieldPercent.toLong())}٪)" else "" },
                                style = SabouType.caption, color = Sabou.colors.muted)
                            val allergens = v?.lines.orEmpty().mapNotNull { items[it.itemId]?.allergens?.takeIf { a -> a.isNotBlank() } }
                                .flatMap { it.split('،', ',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                            if (allergens.isNotEmpty()) Text("آلرژن: " + allergens.joinToString("، "), style = SabouType.caption, color = Sabou.colors.danger)
                            if (session.can(Permission.RECIPE_MANAGE)) {
                                if (renaming == m.id) {
                                    TextInput("نام جدید", newName, { newName = it })
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SecondaryButton("ذخیره نام", { action.run({ inventory.updateMenuItem(UpdateMenuItem(GlobalId.new(), m.id, newName, m.isActive)) }) { renaming = null } },
                                            enabled = newName.trim().length >= 2 && !action.busy)
                                        SecondaryButton("انصراف", { renaming = null })
                                    }
                                } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    MenuLink("ویرایش نام") { renaming = m.id; newName = m.name }
                                    if (m.isActive) MenuLink(if (v == null) "تعریف رسپی" else "ویرایش رسپی") {
                                        // A recipe is never changed in place: its lines prefill a new version from today.
                                        menuItem = m.id; from = session.today; rows.clear()
                                        v?.lines?.forEach { rows.add(RecipeRow(it.itemId, it.quantityPerPortion, if (it.yieldPercent < 100) Fa.number(it.yieldPercent.toLong()) else "")) }
                                        if (rows.isEmpty()) rows.add(RecipeRow(null, null))
                                    }
                                    MenuLink(if (m.isActive) "خارج از منو" else "بازگرداندن", Sabou.colors.onAccentSoft) {
                                        action.run({ inventory.updateMenuItem(UpdateMenuItem(GlobalId.new(), m.id, m.name, !m.isActive)) })
                                    }
                                    MenuLink("حذف", Sabou.colors.danger) { deleting = m }
                                }
                            }
                        }
                    }
                    deleting?.let { m ->
                        Confirm("حذف «${m.name}»؟", "فقط آیتمی که هرگز رسپی، قیمت یا فروش نداشته حذف می‌شود؛ وگرنه آن را از منو خارج کنید.", "حذف",
                            onConfirm = { deleting = null; action.run({ inventory.deleteMenuItem(DeleteMenuItem(GlobalId.new(), m.id)) }) },
                            onDismiss = { deleting = null }, danger = true)
                    }
                    if (session.can(Permission.MENU_PRICE_MANAGE) && branch != null && menu.isNotEmpty()) MenuPriceForm(branch, menu, d.prices)
                    if (session.can(Permission.RECIPE_MANAGE)) {
                        FormCard("آیتم منوی جدید") {
                            TextInput("نام", name, { name = it })
                            action.error?.let { Banner(it) }
                            PrimaryButton("افزودن", { action.run({ inventory.defineMenuItem(DefineMenuItem(GlobalId.new(), name)) }) { name = "" } },
                                enabled = name.trim().length >= 2, busy = action.busy)
                        }
                        FormCard("نسخه جدید رسپی") {
                            menuItem?.let { id -> latest[id]?.let { Banner("ویرایش رسپی «${menu.firstOrNull { it.id == id }?.name}»: با انتشار، نسخهٔ ${Fa.number(it.version + 1L)} از تاریخ «معتبر از» جایگزین می‌شود؛ فروش‌های قبلی با نسخهٔ خودشان می‌مانند.", ChipKind.ACCENT) } }
                            Text("مصرف خالص هر پرس. بازده: درصدی از ماده که پس از پاک‌کردن و پخت می‌ماند؛ مصرف انبار = خالص ÷ بازده. نسخه‌های قبلی تغییر نمی‌کنند.", style = SabouType.caption, color = Sabou.colors.muted)
                            Picker("آیتم منو", menu.map { Choice(it.id, it.name) }, menuItem, { menuItem = it })
                            DateInput("معتبر از", from, { from = it }, session.today)
                            rows.forEach { r ->
                                key(r) {
                                    Picker("ماده اولیه", items.values.filter { it.isActive }.map { Choice(it.id, it.name, unitName(it.unit)) }, r.item, { r.item = it })
                                    ItemQuantityInput("مقدار خالص برای یک پرس", r.item?.let { items[it] }, { r.qty = it }, value = r.qty)
                                    TextInput("بازده ٪ (خالی = ۱۰۰)", r.yieldText, { r.yieldText = it }, keyboard = KeyboardType.Number,
                                        error = if (r.yieldPercent == null) "بین ۱ تا ۱۰۰" else null)
                                    Divider()
                                }
                            }
                            SecondaryButton("افزودن ماده", { rows.add(RecipeRow(null, null)) })
                            PrimaryButton("انتشار نسخه", {
                                val lines = rows.map { RecipeLine(it.item!!, it.qty!!, it.yieldPercent!!) }
                                action.run({ inventory.publishRecipe(PublishRecipe(GlobalId.new(), menuItem!!, from, lines)) }) { rows.clear(); rows.add(RecipeRow(null, null)) }
                            }, enabled = menuItem != null && rows.all { it.item != null && it.qty != null && !it.qty!!.isZero && it.yieldPercent != null }, busy = action.busy)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuLink(text: String, color: androidx.compose.ui.graphics.Color = Sabou.colors.primary, onClick: () -> Unit) =
        Text(text, style = SabouType.label, color = color, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp))

    /**
     * New price version of a menu item from a date, for every branch or only the current one (ADR-0019). Earlier
     * versions stay as they were, so past days keep their prices.
     */
    @Composable
    private fun MenuPriceForm(branch: Scope.Branch, menu: List<ir.sabou.inventory.MenuItem>, prices: Map<GlobalId, ir.sabou.core.MenuPriceView>) {
        val session = LocalSession.current
        var item by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var price by rememberSaveable { mutableStateOf<Money?>(null) }
        var from by rememberSaveable { mutableStateOf(session.today) }
        var onlyBranch by rememberSaveable { mutableStateOf(0) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        FormCard("قیمت منو") {
            Text("قیمت هر پرس به ریال. قیمت جدید از تاریخ «معتبر از» اعمال می‌شود و فروش روزهای قبل با قیمت خودشان می‌ماند.", style = SabouType.caption, color = Sabou.colors.muted)
            Picker("آیتم منو", menu.filter { it.isActive }.map { m -> Choice(m.id, m.name, prices[m.id]?.price?.let { Fa.rial(it) + " ریال" }) }, item, { item = it })
            val current = item?.let { prices[it] }
            current?.history?.take(3)?.forEach { v ->
                Text("${Fa.date(v.effectiveFrom)} · ${if (v.scope == Scope.Organization) "همه شعب" else "این شعبه"} · ${v.unitPrice?.let { Fa.rial(it) + " ریال" } ?: "پایان قیمت ویژه"}",
                    style = SabouType.caption, color = Sabou.colors.muted)
            }
            Segmented(listOf("همه شعب", "فقط این شعبه"), onlyBranch, { onlyBranch = it })
            MoneyInput("قیمت هر پرس", price, { price = it })
            DateInput("معتبر از", from, { from = it }, session.today)
            action.error?.let { Banner(it) }
            val scope: Scope = if (onlyBranch == 1) branch else Scope.Organization
            PrimaryButton("ثبت قیمت", {
                action.run({ salesOps.setMenuPrice(ir.sabou.sales.SetMenuPrice(id.value, scope, item!!, from, price!!)) }) { id.value = GlobalId.new(); price = null }
            }, enabled = item != null && price != null && !price!!.isZero, busy = action.busy)
            if (current?.own == true) {
                SecondaryButton("پایان قیمت ویژه این شعبه (از همین تاریخ قیمت همه شعب)", {
                    action.run({ salesOps.setMenuPrice(ir.sabou.sales.SetMenuPrice(id.value, branch, item!!, from, null)) }) { id.value = GlobalId.new() }
                }, enabled = !action.busy)
            }
        }
    }

    /** Sign-in security: change own PIN; for the owner, the one-time recovery code (ADR-0023). */
    @Composable
    fun Security(nav: Nav) {
        val session = LocalSession.current
        val owner = session.actor.role == ir.sabou.platform.Role.OWNER
        val hasCode by load(session) { identity.hasRecoveryCode() }
        var current by rememberSaveable { mutableStateOf("") }
        var next by rememberSaveable { mutableStateOf("") }
        var again by rememberSaveable { mutableStateOf("") }
        var codePin by rememberSaveable { mutableStateOf("") }
        // Shown once, never saved with the form state.
        var issued by remember { mutableStateOf<String?>(null) }
        val change = rememberAction()
        val recovery = rememberAction()
        var changed by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Header("امنیت ورود", onBack = nav.back)
            Page {
                FormCard("تغییر رمز") {
                    TextInput("رمز فعلی", current, { current = it }, keyboard = KeyboardType.NumberPassword, secret = true)
                    TextInput("رمز جدید (۶ تا ۱۲ رقم)", next, { next = it }, keyboard = KeyboardType.NumberPassword, secret = true)
                    TextInput("تکرار رمز جدید", again, { again = it }, keyboard = KeyboardType.NumberPassword, secret = true,
                        error = if (again.isNotEmpty() && again != next) "با رمز جدید یکی نیست" else null)
                    change.error?.let { Banner(it) }
                    if (changed) Banner("رمز عوض شد.", ChipKind.PRIMARY, R.drawable.ic_check)
                    PrimaryButton("تغییر رمز", {
                        change.run({ identity.changeOwnPin(Fa.latinDigits(current).toCharArray(), Fa.latinDigits(next).toCharArray()) }) { current = ""; next = ""; again = ""; changed = true }
                    }, enabled = current.length >= 6 && next.length >= 6 && next == again, busy = change.busy)
                }
                FingerprintCard()
                AlertsCard()
                if (owner) FormCard("کد بازیابی رمز مالک") {
                    Text("اگر رمز مالک فراموش شود، فقط با این کد ۱۶ رقمی می‌شود رمز تازه گذاشت. کد یک بار نمایش داده می‌شود: روی کاغذ بنویسید و جای امنی جدا از گوشی نگه دارید. " +
                        "برنامه فقط اثر یک‌طرفه‌ی آن را نگه می‌دارد. هر کد یک بار کار می‌کند و ساختن کد تازه، کد قبلی را باطل می‌کند.",
                        style = SabouType.caption, color = Sabou.colors.muted)
                    when (hasCode.orNull()) {
                        true -> Banner("کد بازیابی ساخته شده است.", ChipKind.PRIMARY, R.drawable.ic_check)
                        false -> Banner("هنوز کد بازیابی ندارید؛ اگر رمز را فراموش کنید راهی جز بازیابی پشتیبان نمی‌ماند.", ChipKind.ACCENT)
                        null -> Unit
                    }
                    issued?.let { code ->
                        SCard {
                            Text("کد بازیابی شما", style = SabouType.caption, color = Sabou.colors.muted)
                            Text(Fa.digits(code), style = SabouType.title, color = Sabou.colors.ink)
                            Text("همین حالا بنویسید؛ دوباره نمایش داده نمی‌شود.", style = SabouType.caption, color = Sabou.colors.danger)
                        }
                        SecondaryButton("نوشتم", { issued = null })
                    }
                    if (issued == null) {
                        TextInput("رمز فعلی برای تأیید", codePin, { codePin = it }, keyboard = KeyboardType.NumberPassword, secret = true)
                        recovery.error?.let { Banner(it) }
                        PrimaryButton(if (hasCode.orNull() == true) "ساختن کد تازه (کد قبلی باطل می‌شود)" else "ساختن کد بازیابی", {
                            recovery.run<String>({ identity.issueRecoveryCode(Fa.latinDigits(codePin).toCharArray()) }) { code -> issued = code; codePin = "" }
                        }, enabled = codePin.length >= 6, busy = recovery.busy)
                    }
                }
            }
        }
    }

    /** The nightly backup (ADR-0025): folder, password once, status, run now, switch off. */
    @Composable
    private fun AutoBackupCard() {
        val session = LocalSession.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf(ir.sabou.app.data.AutoBackup.status(context)) }
        var folder by remember { mutableStateOf<Uri?>(null) }
        var password by remember { mutableStateOf("") }
        var password2 by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> folder = uri }
        fun runNow() {
            busy = true; message = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { ir.sabou.app.data.AutoBackup.runNow(context) }
                busy = false
                status = ir.sabou.app.data.AutoBackup.status(context)
                message = r.fold({ "پشتیبان ساخته شد: $it" to true }, { restoreMessage(it) to false })
            }
        }
        FormCard("پشتیبان خودکار شبانه") {
            Text("هر شب یک فایل پشتیبان رمزگذاری‌شده در پوشه‌ای که انتخاب می‌کنید (کارت حافظه، پوشه‌ی همگام با فضای ابری، فلش) ساخته می‌شود و ${Fa.number(ir.sabou.app.data.AutoBackup.KEEP.toLong())} نسخه‌ی آخر می‌ماند. " +
                "رمز یک بار گرفته می‌شود و فقط روی همین گوشی، قفل‌شده با کلید امن اندروید، می‌ماند؛ برای بازیابی روی گوشی دیگر همین رمز لازم است — جایی جدا از گوشی بنویسید.",
                style = SabouType.caption, color = Sabou.colors.muted)
            message?.let { (text, ok) -> Banner(text, if (ok) ChipKind.PRIMARY else ChipKind.DANGER) }
            if (status.enabled) {
                Banner("روشن است.", ChipKind.PRIMARY, R.drawable.ic_check)
                status.lastSuccess?.let { KeyValue("آخرین پشتیبان", Fa.digits(java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date(it)))) }
                    ?: Text("هنوز پشتیبانی ساخته نشده است.", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                status.lastError?.let { Banner("آخرین تلاش ناموفق بود: $it", ChipKind.ACCENT) }
                SecondaryButton("پشتیبان همین حالا", { runNow() }, enabled = !busy)
                SecondaryButton("خاموش کردن", { ir.sabou.app.data.AutoBackup.disable(context); status = ir.sabou.app.data.AutoBackup.status(context) }, danger = true)
            } else {
                SecondaryButton(if (folder == null) "انتخاب پوشه‌ی پشتیبان" else "پوشه انتخاب شد ✓", { pick.launch(null) })
                TextInput("رمز پشتیبان (حداقل ۱۰ نویسه)", password, { password = it }, secret = true)
                TextInput("تکرار رمز", password2, { password2 = it }, secret = true, error = if (password2.isNotEmpty() && password2 != password) "یکسان نیست" else null)
                PrimaryButton("روشن کردن و اولین پشتیبان", {
                    val f = folder ?: return@PrimaryButton
                    runCatching { ir.sabou.app.data.AutoBackup.enable(context, session.actor.userId, f, password.toCharArray()) }
                        .onSuccess { password = ""; password2 = ""; status = ir.sabou.app.data.AutoBackup.status(context); runNow() }
                        .onFailure { message = "این پوشه قابل استفاده نیست؛ پوشه‌ی دیگری انتخاب کنید." to false }
                }, enabled = folder != null && password.length >= 10 && password == password2, busy = busy)
            }
        }
    }

    /** Alerts on this phone (ADR-0024): what is announced, and a switch. */
    @Composable
    private fun AlertsCard() {
        val context = LocalContext.current
        var on by remember { mutableStateOf(ir.sabou.app.data.PhoneAlerts.enabled(context)) }
        FormCard("اعلان هشدارها روی این گوشی") {
            Text("برای کاربری که آخر روی این گوشی وارد شده، طبق دسترسی‌هایش: کمبود کالا، کسری مصرف، گران یا ارزان شدن خرید (بیش از ۱۰٪)، چک نزدیک یا گذشته از سررسید، و فاکتور منتظر تأیید. " +
                "هر مورد یک بار اعلام می‌شود و اگر رفع شد و دوباره پیش آمد، دوباره. روی صفحه‌ی قفل فقط «هشدار تازه» دیده می‌شود.",
                style = SabouType.caption, color = Sabou.colors.muted)
            Segmented(listOf("روشن", "خاموش"), if (on) 0 else 1, { on = it == 0; ir.sabou.app.data.PhoneAlerts.setEnabled(context, on) })
            if (on) SecondaryButton("بررسی همین حالا", { ir.sabou.app.data.PhoneAlerts.soon() })
        }
    }

    /** Turn fingerprint sign-in on (after proving the PIN) or off, for the signed-in user on this phone. */
    @Composable
    private fun FingerprintCard() {
        val session = LocalSession.current
        val vault = (LocalContext.current.applicationContext as SabouApplication).container.biometric
        if (!vault.supported) return
        val username by load(session) { identity.currentUsername() ?: "" }
        var enrolled by remember { mutableStateOf(vault.enrolledUser()) }
        var pin by rememberSaveable { mutableStateOf("") }
        var message by remember { mutableStateOf<String?>(null) }
        val action = rememberAction()
        FormCard("ورود با اثر انگشت") {
            Text("رمز شما با کلیدی روی همین گوشی قفل می‌شود که فقط با اثر انگشت باز می‌شود؛ ورود همچنان با همان رمز بررسی می‌شود. اگر اثر انگشت تازه‌ای روی گوشی تعریف شود یا رمزتان عوض شود، دوباره باید فعالش کنید.",
                style = SabouType.caption, color = Sabou.colors.muted)
            val me = username.orNull()
            if (enrolled != null && enrolled == me) {
                Banner("فعال است.", ChipKind.PRIMARY, R.drawable.ic_check)
                SecondaryButton("غیرفعال کردن", { vault.disable(); enrolled = null })
            } else if (me != null) {
                if (enrolled != null) Text("اکنون برای «$enrolled» فعال است؛ فعال کردن برای شما جایگزین آن می‌شود.", style = SabouType.caption, color = Sabou.colors.onAccentSoft)
                TextInput("رمز فعلی", pin, { pin = it }, keyboard = KeyboardType.NumberPassword, secret = true)
                (action.error ?: message)?.let { Banner(it) }
                PrimaryButton("فعال کردن", {
                    val typed = Fa.latinDigits(pin)
                    action.run({ identity.confirmPin(typed.toCharArray()) }) {
                        vault.enable(me, typed.toCharArray(), "تأیید اثر انگشت") { r ->
                            r.onSuccess { enrolled = me; pin = ""; message = null }
                                .onFailure { e -> if (e !is ir.sabou.app.data.BiometricVault.CancelledException) message = "اثر انگشتی روی این گوشی تعریف نشده یا قفل صفحه ندارد." }
                        }
                    }
                }, enabled = pin.length >= 6, busy = action.busy)
            }
        }
    }

    @Composable
    fun Accounts(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.allTreasuryAccounts() to overview.branches() }
        var name by rememberSaveable { mutableStateOf("") }
        var kind by rememberSaveable { mutableStateOf(TreasuryKind.CASH) }
        var editing by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var orgLevel by rememberSaveable { mutableStateOf(false) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("صندوق‌ها و حساب‌ها", onBack = nav.back) { BranchSwitcher() }
            Page {
                Loaded(data) { (balances, _) ->
                    balances.forEach { b ->
                        SCard(onClick = if (session.can(Permission.TREASURY_ACCOUNT_MANAGE)) ({ editing = b.account.id }) else null) {
                            KeyValue("${b.account.name} · ${kindName(b.account.kind)}", (if (b.account.scope == Scope.Organization) "سازمان" else "شعبه") +
                                if (!b.account.isActive) " · بسته" else "", if (b.account.isActive) Sabou.colors.ink else Sabou.colors.muted)
                            if (editing == b.account.id) AccountEdit(b.account) { editing = null }
                        }
                    }
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
    private fun AccountEdit(account: ir.sabou.treasury.TreasuryAccount, onDone: () -> Unit) {
        var name by rememberSaveable { mutableStateOf(account.name) }
        var active by rememberSaveable { mutableStateOf(account.isActive) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        TextInput("نام", name, { name = it })
        Row(Modifier.clickable { active = !active }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = active, onCheckedChange = { active = it })
            Text("فعال (حساب بسته فقط وقتی مانده و چک بازی ندارد)", style = SabouType.body, color = Sabou.colors.ink)
        }
        action.error?.let { Banner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("ذخیره", {
                action.run({ treasury.updateAccount(ir.sabou.treasury.UpdateTreasuryAccount(id.value, account.scope, account.id, name, active)) }) { onDone() }
            }, Modifier.weight(1f), enabled = name.trim().length >= 2 && !action.busy, busy = action.busy)
            SecondaryButton("انصراف", onDone, Modifier.weight(1f))
        }
    }

    @Composable
    private fun CustomerEdit(branch: Scope.Branch, c: ir.sabou.sales.Customer, onDone: () -> Unit) {
        var name by rememberSaveable { mutableStateOf(c.name) }
        var phone by rememberSaveable { mutableStateOf(c.phone) }
        var type by rememberSaveable { mutableStateOf(c.type) }
        var limit by rememberSaveable { mutableStateOf<Money?>(c.creditLimit) }
        var active by rememberSaveable { mutableStateOf(c.isActive) }
        val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
        val action = rememberAction()
        TextInput("نام", name, { name = it })
        Picker("نوع", listOf(Choice(CustomerType.COMPANY, "شرکت"), Choice(CustomerType.PERSON, "شخص")), type, { type = it })
        TextInput("تلفن", phone, { phone = it }, keyboard = KeyboardType.Phone)
        MoneyInput("سقف اعتبار", limit, { limit = it })
        Row(Modifier.clickable { active = !active }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = active, onCheckedChange = { active = it })
            Text("فعال (مشتری غیرفعال نسیه نمی‌خرد)", style = SabouType.body, color = Sabou.colors.ink)
        }
        action.error?.let { Banner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("ذخیره", {
                action.run({ salesOps.updateCustomer(ir.sabou.sales.UpdateCustomer(id.value, branch, c.id, name, type, phone, limit!!, active)) }) { onDone() }
            }, Modifier.weight(1f), enabled = name.trim().length >= 2 && limit != null && !action.busy, busy = action.busy)
            SecondaryButton("انصراف", onDone, Modifier.weight(1f))
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
                var editing by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { list ->
                        list.forEach { (c, owed) ->
                            SCard(onClick = if (session.can(Permission.CUSTOMER_MANAGE)) ({ editing = c.id }) else null) {
                                KeyValue(c.name + if (!c.isActive) " · غیرفعال" else "", "بدهی ${Fa.rial(owed)} از سقف ${Fa.rialShort(c.creditLimit.rial)}")
                                if (editing == c.id) CustomerEdit(branch, c) { editing = null }
                            }
                        }
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
                AutoBackupCard()
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
                work("بازیابی انجام شد.") { container.restore(pw, uri) }
            }, onDismiss = { confirmRestore = false }, danger = true)
        }
        if (confirmReset) {
            ir.sabou.app.ui.EraseConfirm(onConfirm = {
                work("برنامه از نو شروع شد؛ داده‌های قبلی روی دستگاه کنار گذاشته شد.") { container.factoryReset() }
            }, onDismiss = { confirmReset = false })
        }
    }
}
