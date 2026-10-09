package ir.sabou.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.data.AppContainer
import ir.sabou.app.data.AppState
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Confirm
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Loading
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.screens.FinanceScreens
import ir.sabou.app.ui.screens.HomeScreen
import ir.sabou.app.ui.screens.MeScreens
import ir.sabou.app.ui.screens.OperationsScreens
import ir.sabou.app.ui.screens.SalesScreen
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.Messages
import ir.sabou.core.SabouCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppRoot(container: AppContainer, ui: UiState) {
    val state by container.state.collectAsState()
    val rootScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { container.ensureOpen() } }

    // Leaving the app always asks first. Screens deeper in the tree register their own (higher-priority)
    // back handlers, so this one only runs at the root: the Home tab, sign-in, setup or recovery.
    val activity = androidx.activity.compose.LocalActivity.current
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    if (confirmExit) {
        Confirm(
            title = "خروج از برنامه", text = "از سابو خارج می‌شوید؟", confirm = "خروج",
            onConfirm = { activity?.finish() }, onDismiss = { confirmExit = false },
        )
    }

    Box(Modifier.fillMaxSize().background(Sabou.colors.ground).safeDrawingPadding().imePadding()) {
        when (val s = state) {
            AppState.Opening -> Splash()
            is AppState.Failed -> RecoveryScreen(
                container, s.detail, title = "برنامه باز نشد",
                explanation = "پایگاه داده یا کلید امن دستگاه در دسترس نیست. دوباره تلاش کنید؛ اگر تکرار شد، از پشتیبان بازیابی کنید.",
                retry = { container.open() },
            )
            is AppState.Recovery -> RecoveryScreen(
                container, s.detail, title = "بررسی یکپارچگی ناموفق بود",
                explanation = "داده‌های این دستگاه با آخرین وضعیت ثبت‌شده هم‌خوانی ندارد. ممکن است پایگاه داده با نسخه قدیمی‌تری جایگزین یا دست‌کاری شده باشد.",
            )
            is AppState.Ready -> {
                val session = ui.session
                when {
                    session != null && session.core === s.core -> Shell(ui, session)
                    else -> Gate(s.core, container) { actor ->
                        rootScope.launch {
                            val (allowed, draft) = withContext(Dispatchers.IO) {
                                s.core.identity.accessibleBranches(actor).map { it.id } to container.takeDrafts()
                            }
                            ui.session?.close()   // e.g. the session of a database replaced by restore or reset
                            val session = AppSession(s.core, actor, allowed.firstOrNull()) { ui.signOut(); container.clearDraftsLater() }
                            // Back to the unfinished form this user left when the system closed the app.
                            // A draft that cannot be decoded is dropped, never a crash.
                            if (draft != null) runCatching { ui.restoreDraft(draft, session, allowed.toSet(), System.currentTimeMillis()) }
                                .onFailure { ui.discardRestoredDraft() }
                            ui.session = session
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(Sabou.colors.primary), contentAlignment = Alignment.Center) {
            Text("س", style = SabouType.display, color = Sabou.colors.accent)
        }
        Spacer(Modifier.height(16.dp))
        Text("سابو", style = SabouType.title, color = Sabou.colors.ink)
        Text("در حال باز کردن و بررسی یکپارچگی داده‌ها…", style = SabouType.caption, color = Sabou.colors.muted)
        Loading()
    }
}

/**
 * Shown when the database does not continue the recorded history (e.g. it was replaced with an older
 * copy) or the audit chain failed. Nothing is lost silently: the user restores a backup or starts over.
 */
@Composable
private fun RecoveryScreen(container: AppContainer, detail: String, title: String, explanation: String, retry: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }

    Page {
        Spacer(Modifier.height(24.dp))
        Text(title, style = SabouType.title, color = Sabou.colors.ink)
        Banner(explanation)
        Text("کد: $detail", style = SabouType.caption, color = Sabou.colors.muted)
        if (retry != null) PrimaryButton("تلاش دوباره", { scope.launch(Dispatchers.IO) { retry() } })
        RestoreCard(container)
        FormCard("شروع از نو") {
            Text("همه داده‌های این دستگاه پاک می‌شود و برنامه مثل نصب اول باز می‌شود.", style = SabouType.body, color = Sabou.colors.muted)
            SecondaryButton("پاک کردن همه داده‌ها", { confirmReset = true }, danger = true)
        }
    }
    if (confirmReset) {
        EraseConfirm(onConfirm = { scope.launch(Dispatchers.IO) { container.factoryReset(null) } }, onDismiss = { confirmReset = false })
    }
}

/**
 * Erasing everything is reachable without signing in (the PINs live in the database that may be unreadable),
 * so it is made hard to do by accident or in passing: type the word, then wait for the countdown.
 */
@Composable
internal fun EraseConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf(ERASE_WAIT_SECONDS) }
    LaunchedEffect(Unit) {
        while (seconds > 0) { kotlinx.coroutines.delay(1_000); seconds-- }
    }
    // Arabic and Persian keyboards differ in kaf/yeh: both spellings count.
    val ready = typed.trim().replace('ك', 'ک').replace('ي', 'ی') == ERASE_WORD && seconds == 0
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("پاک کردن همه داده‌ها", style = SabouType.section, color = Sabou.colors.danger) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("همه فروش‌ها، حساب‌ها، انبار و حقوق این دستگاه برای همیشه پاک می‌شود. اگر فایل پشتیبان دارید، به‌جای این کار «بازیابی» را بزنید.",
                    style = SabouType.body)
                TextInput("برای تأیید بنویسید: $ERASE_WORD", typed, { typed = it })
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDismiss(); onConfirm() }, enabled = ready) {
                Text(if (seconds > 0) "پاک کن (${Fa.number(seconds.toLong())})" else "پاک کن", style = SabouType.bodyStrong,
                    color = if (ready) Sabou.colors.danger else Sabou.colors.subtle)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("انصراف", style = SabouType.bodyStrong, color = Sabou.colors.muted) } },
        containerColor = Sabou.colors.surface,
    )
}

private const val ERASE_WORD = "پاک"
private const val ERASE_WAIT_SECONDS = 10

/** Restore from a backup file (recovery screen, and first run on a new or reinstalled device). */
@Composable
private fun RestoreCard(container: AppContainer, title: String = "بازیابی از فایل پشتیبان") {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri = it }
    FormCard(title) {
        SecondaryButton(if (uri == null) "انتخاب فایل پشتیبان" else "فایل انتخاب شد ✓", { picker.launch(arrayOf("*/*")) })
        TextInput("رمز فایل پشتیبان", password, { password = it }, secret = true)
        error?.let { Banner(it) }
        PrimaryButton("بازیابی", {
            val source = uri ?: return@PrimaryButton
            busy = true; error = null
            scope.launch {
                error = withContext(Dispatchers.IO) {
                    runCatching { container.restore(null, password.toCharArray(), source) }.exceptionOrNull()?.let(::restoreMessage)
                }
                busy = false
            }
        }, enabled = uri != null && password.length >= 10, busy = busy)
    }
}

internal fun restoreMessage(e: Throwable): String = when {
    e is ir.sabou.backup.BackupFormatException && e.message == "authentication_failed" -> "رمز اشتباه است یا فایل آسیب دیده است."
    e is ir.sabou.backup.BackupFormatException -> "این فایل پشتیبان معتبر سابو نیست."
    e.message?.startsWith("BACKUP_INTEGRITY") == true -> "یکپارچگی داده‌های این فایل پشتیبان تأیید نشد."
    e.message?.startsWith("DATABASE_NEWER_THAN_APP") == true -> "این پشتیبان با نسخه جدیدتری از برنامه ساخته شده است؛ ابتدا برنامه را به‌روز کنید."
    else -> Messages.of(e)
}

/** First run (create the owner and the first branch) or sign-in. */
@Composable
private fun Gate(core: SabouCore, container: AppContainer, onSignedIn: (ir.sabou.platform.Actor) -> Unit) {
    var needsBootstrap by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(core) { needsBootstrap = withContext(Dispatchers.IO) { core.identity.needsBootstrap() } }
    when (needsBootstrap) {
        null -> Splash()
        true -> Bootstrap(core, container, onSignedIn)
        false -> Login(core, onSignedIn)
    }
}

@Composable
private fun Bootstrap(core: SabouCore, container: AppContainer, onSignedIn: (ir.sabou.platform.Actor) -> Unit) {
    val scope = rememberCoroutineScope()
    var branch by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page {
        Spacer(Modifier.height(24.dp))
        Text("به سابو خوش آمدید", style = SabouType.title, color = Sabou.colors.ink)
        Text("مدیریت فروش، صندوق، خرید و انبار، حسابداری و حقوق رستوران — روی همین دستگاه و رمزگذاری‌شده.", style = SabouType.body, color = Sabou.colors.muted)
        FormCard("رستوران") {
            TextInput("نام اولین شعبه", branch, { branch = it }, placeholder = "مثلاً شعبه ونک")
        }
        FormCard("حساب مالک") {
            TextInput("نام شما", name, { name = it })
            TextInput("نام کاربری (حروف لاتین)", username, { username = it }, placeholder = "owner")
            TextInput("رمز عددی ۶ تا ۱۲ رقم", pin, { pin = it }, keyboard = KeyboardType.NumberPassword, secret = true)
            TextInput("تکرار رمز", pin2, { pin2 = it }, keyboard = KeyboardType.NumberPassword, secret = true,
                error = if (pin2.isNotEmpty() && Fa.latinDigits(pin2) != Fa.latinDigits(pin)) "تکرار رمز یکسان نیست" else null)
        }
        error?.let { Banner(it) }
        PrimaryButton("شروع", {
            busy = true; error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        // One transaction: owner, first branch, a cash box and a kitchen — or nothing.
                        core.bootstrap(branch, name, username, Fa.latinDigits(pin).toCharArray())
                        checkNotNull(core.session.currentActor())
                    }
                }
                busy = false
                result.onSuccess(onSignedIn).onFailure { error = Messages.of(it) }
            }
        }, enabled = branch.isNotBlank() && name.isNotBlank() && username.isNotBlank() && pin.length >= 6 && Fa.latinDigits(pin) == Fa.latinDigits(pin2), busy = busy)
        Spacer(Modifier.height(8.dp))
        Text("از قبل فایل پشتیبان سابو دارید؟ (گوشی جدید یا نصب دوباره)", style = SabouType.body, color = Sabou.colors.muted)
        RestoreCard(container, title = "بازیابی اطلاعات قبلی")
    }
}

@Composable
private fun Login(core: SabouCore, onSignedIn: (ir.sabou.platform.Actor) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Sabou.colors.primary), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = Sabou.colors.accent, modifier = Modifier.size(30.dp))
        }
        Text("ورود", style = SabouType.title, color = Sabou.colors.ink)
        FormCard {
            TextInput("نام کاربری", username, { username = it })
            TextInput("رمز", pin, { pin = it }, keyboard = KeyboardType.NumberPassword, secret = true)
            error?.let { Banner(it) }
            PrimaryButton("ورود", {
                busy = true; error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { core.identity.login(username, Fa.latinDigits(pin).toCharArray()) } }
                    busy = false
                    result.onSuccess { pin = ""; onSignedIn(it) }.onFailure {
                        error = if ((it as? ir.sabou.kernel.DomainException)?.error == ir.sabou.kernel.DomainError.AuthenticationRequired)
                            "نام کاربری یا رمز درست نیست." else Messages.of(it)
                    }
                }
            }, enabled = username.isNotBlank() && pin.length >= 6, busy = busy)
        }
    }
}

// ---------------------------------------------------------------- Signed-in shell

private data class TabSpec(val route: Route.Tab, val label: String, val icon: Int, val needsAny: List<ir.sabou.platform.Permission> = emptyList())

private val tabs = listOf(
    TabSpec(Route.Home, "خانه", R.drawable.ic_home),
    TabSpec(Route.Sales, "فروش", R.drawable.ic_sales, listOf(ir.sabou.platform.Permission.SALES_VIEW)),
    TabSpec(Route.Operations, "عملیات", R.drawable.ic_operations, OperationsScreens.allPerms),
    TabSpec(Route.Finance, "مالی", R.drawable.ic_finance, listOf(
        ir.sabou.platform.Permission.TREASURY_VIEW, ir.sabou.platform.Permission.LEDGER_VIEW,
        ir.sabou.platform.Permission.SALES_VIEW, ir.sabou.platform.Permission.PURCHASE_VIEW,
    )),
    TabSpec(Route.Me, "من", R.drawable.ic_me),
)

@Composable
private fun Shell(ui: UiState, session: AppSession) {
    // Back walks the page stack, then returns to the Home tab; only on Home does it reach the exit prompt.
    BackHandler(enabled = ui.stack.size > 1 || ui.stack.first() != Route.Home) { if (!ui.back()) ui.go(Route.Home) }
    val route = ui.current
    val nav = Nav(go = ui::go, back = { ui.back() })
    // Each page keeps its form values in its own registry, saved when the app goes to the background.
    val registry = remember(route, ui.stack.size) {
        androidx.compose.runtime.saveable.SaveableStateRegistry(ui.takeRestoredPage(), Drafts::canBeSaved)
    }
    androidx.compose.runtime.DisposableEffect(registry) {
        ui.pageRegistry = registry
        onDispose { if (ui.pageRegistry === registry) ui.pageRegistry = null }
    }
    session.notice?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { session.notice = null },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { session.notice = null }) { Text("متوجه شدم", style = SabouType.bodyStrong, color = Sabou.colors.primary) } },
            title = { Text("ثبت قبلی انجام نشد", style = SabouType.section) },
            text = { Text("$message\nآن کار را دوباره انجام دهید.", style = SabouType.body) },
            containerColor = Sabou.colors.surface,
        )
    }
    CompositionLocalProvider(LocalSession provides session) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(androidx.compose.runtime.saveable.LocalSaveableStateRegistry provides registry) {
                    when (route) {
                        Route.Home -> HomeScreen(nav)
                        Route.Sales -> SalesScreen(nav)
                        Route.Operations -> OperationsScreens.Hub(nav)
                        Route.Finance -> FinanceScreens.Hub(nav)
                        Route.Me -> MeScreens.Hub(nav, onSignOut = ui::signOut)
                        else -> Pages(route, nav)
                    }
                }
            }
            val activeTab = ui.stack.first()
            Row(
                Modifier.fillMaxWidth().background(Sabou.colors.surface).border(width = 1.dp, color = Sabou.colors.border).padding(horizontal = 6.dp, vertical = 8.dp),
            ) {
                // A role only sees the tabs it can use (AUD-012).
                tabs.filter { t -> t.needsAny.isEmpty() || t.needsAny.any { session.actor.role.allows(it) } }.forEach { t ->
                    val on = t.route == activeTab
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Tab) { ui.go(t.route) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(
                            Modifier.width(52.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
                                .background(if (on) Sabou.colors.primarySoft else androidx.compose.ui.graphics.Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(t.icon), contentDescription = null, tint = if (on) Sabou.colors.primary else Sabou.colors.muted, modifier = Modifier.size(22.dp)) }
                        Text(t.label, style = if (on) SabouType.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else SabouType.label,
                            color = if (on) Sabou.colors.primary else Sabou.colors.muted)
                    }
                }
            }
        }
    }
}

/** Navigation handed to screens. */
class Nav(val go: (Route) -> Unit, val back: () -> Unit)

@Composable
private fun Pages(route: Route, nav: Nav) {
    when (route) {
        Route.Receipt -> FinanceScreens.ReceiptForm(nav)
        Route.Payment -> FinanceScreens.PaymentForm(nav)
        Route.Transfer -> FinanceScreens.TransferForm(nav)
        Route.Reconcile -> FinanceScreens.ReconcileForm(nav)
        Route.Receivables -> FinanceScreens.Receivables(nav)
        is Route.Collect -> FinanceScreens.CollectForm(nav, route.receivableId)
        Route.TrialBalance -> FinanceScreens.TrialBalance(nav)
        is Route.AccountHistory -> FinanceScreens.AccountHistory(nav, route.accountId)
        Route.Stock -> OperationsScreens.Stock(nav)
        Route.Waste -> OperationsScreens.Waste(nav)
        Route.Count -> OperationsScreens.Count(nav)
        Route.StockTransfer -> OperationsScreens.StockTransfer(nav)
        Route.Recipes -> OperationsScreens.Recipes(nav)
        Route.Purchases -> OperationsScreens.Purchases(nav)
        Route.NewPurchase -> OperationsScreens.NewPurchase(nav)
        is Route.PurchaseDetail -> OperationsScreens.PurchaseDetail(nav, route.invoiceId)
        Route.Suppliers -> OperationsScreens.Suppliers(nav)
        Route.Personnel -> OperationsScreens.Personnel(nav)
        Route.Attendance -> OperationsScreens.Attendance(nav)
        Route.Payroll -> OperationsScreens.Payroll(nav)
        Route.Branches -> MeScreens.Branches(nav)
        Route.Users -> MeScreens.Users(nav)
        Route.Items -> MeScreens.Items(nav)
        Route.Locations -> MeScreens.Locations(nav)
        Route.Menu -> MeScreens.Menu(nav)
        Route.Accounts -> MeScreens.Accounts(nav)
        Route.Customers -> MeScreens.Customers(nav)
        Route.Policies -> MeScreens.Policies(nav)
        Route.Backup -> MeScreens.Backup(nav)
        Route.Home, Route.Sales, Route.Operations, Route.Finance, Route.Me -> Unit
    }
}
