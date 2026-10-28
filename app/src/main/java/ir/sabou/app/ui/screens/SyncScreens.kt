package ir.sabou.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.SabouApplication
import ir.sabou.app.data.TwoPhones
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Confirm
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.Messages
import ir.sabou.sync.PairingAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

/** Two phones, one set of books (ADR-0042): the main phone's and the second phone's screens. */
object SyncScreens {
    @Composable
    fun TwoPhonesPage(nav: Nav) {
        val session = LocalSession.current
        val container = (LocalContext.current.applicationContext as SabouApplication).container
        val two = container.twoPhones
        val status by two.status.collectAsState()
        val scope = rememberCoroutineScope()
        val owner = session.actor.isOwner
        var confirm by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var pairHere by remember { mutableStateOf(false) }

        if (status.role == TwoPhones.Role.HUB) {
            LaunchedEffect(Unit) { while (true) { withContext(Dispatchers.IO) { two.refreshHubStatus() }; delay(2_000) } }
        }

        Column(Modifier.fillMaxSize()) {
            Header("دو گوشی، یک دفتر", "همگام‌سازی روی وای‌فای رستوران", onBack = nav.back)
            Page {
                error?.let { Banner(it) }
                if (status.orphaned) {
                    status.problem?.let { Banner(it, ChipKind.DANGER) }
                    if (owner) {
                        Text("گوشی اصلی را روشن کنید و این گوشی را دوباره به آن وصل کنید؛ یا آن را جدا کنید تا خالی شود (داده‌ها روی گوشی اصلی می‌ماند).",
                            style = SabouType.caption, color = Sabou.colors.muted)
                        PairAsSecondPhone(onCancel = null)
                        SecondaryButton("جدا کردن این گوشی", { confirm = "leave" }, danger = true)
                    } else {
                        Text("فقط مالک می‌تواند این گوشی را دوباره وصل یا جدا کند.", style = SabouType.caption, color = Sabou.colors.muted)
                    }
                } else when (status.role) {
                    TwoPhones.Role.NONE -> {
                        SCard {
                            Text("یک گوشی «گوشی اصلی» می‌شود و همه‌ی داده‌ها روی آن می‌ماند. گوشی دوم هر چه ثبت کند، روی گوشی اصلی ثبت می‌شود و هر چه روی گوشی اصلی ثبت شود، چند ثانیه بعد روی گوشی دوم دیده می‌شود. " +
                                "هر دو گوشی باید به یک وای‌فای وصل باشند؛ اینترنت لازم نیست و چیزی از رستوران بیرون نمی‌رود.",
                                style = SabouType.body, color = Sabou.colors.ink)
                            Text("گوشی اصلی را همیشه روشن، به شارژ و روی وای‌فای نگه دارید. پشتیبان‌گیری فقط روی گوشی اصلی انجام می‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                        if (owner) {
                            if (!pairHere) {
                                PrimaryButton("این گوشی، گوشی اصلی باشد", { confirm = "hub" })
                                SecondaryButton("این گوشی را گوشی دوم کن", { pairHere = true })
                            } else {
                                Banner("همه‌ی داده‌های این گوشی با داده‌های گوشی اصلی جایگزین می‌شود. داده‌های فعلی پاک نمی‌شود و کنار گذاشته می‌شود، ولی دیگر در برنامه دیده نمی‌شود.", ChipKind.ACCENT)
                                PairAsSecondPhone(onCancel = { pairHere = false })
                            }
                        } else {
                            Text("فقط مالک می‌تواند این تنظیم را انجام دهد.", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    }
                    TwoPhones.Role.HUB -> {
                        SCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("گوشی اصلی", ChipKind.PRIMARY) }
                            KeyValue("نشانی این گوشی در وای‌فای", status.address?.let(Fa::digits) ?: "وای‌فای وصل نیست")
                            KeyValue("گوشی‌های وصل", Fa.number(status.connectedPeers.size.toLong()))
                        }
                        if (status.peers.isNotEmpty()) {
                            FormCard("گوشی‌های دوم") {
                                status.peers.forEach { p ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(p.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                            Text(if (p.deviceId in status.connectedPeers) "وصل" else "وصل نیست", style = SabouType.caption, color = Sabou.colors.muted)
                                        }
                                        if (owner) SecondaryButton("حذف", { confirm = "remove:${p.deviceId}" }, danger = true)
                                    }
                                }
                            }
                        }
                        status.pairingResult?.let { Banner(it, ChipKind.PRIMARY, R.drawable.ic_check) }
                        if (owner) {
                            if (status.pairingOpen) {
                                FormCard("افزودن گوشی دوم") {
                                    val code = status.pairingCode
                                    if (code == null) {
                                        Text("روی گوشی دوم «اتصال به گوشی اصلی» را بزنید (در صفحه‌ی اول نصب، یا من ← دو گوشی). تا ۵ دقیقه منتظر می‌مانیم.", style = SabouType.body, color = Sabou.colors.ink)
                                        SecondaryButton("انصراف", { two.cancelPairing() })
                                    } else {
                                        Text("«${status.pairingDevice}» می‌خواهد وصل شود. این کد را با کد روی آن گوشی مقایسه کنید:", style = SabouType.body, color = Sabou.colors.ink)
                                        Text(Fa.digits(code.chunked(3).joinToString(" ")), style = SabouType.display, color = Sabou.colors.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                        PrimaryButton("یکی است، وصل شود", { two.answerPairing(true) })
                                        SecondaryButton("یکی نیست", { two.answerPairing(false) }, danger = true)
                                    }
                                }
                            } else {
                                PrimaryButton("افزودن گوشی دوم", { two.openPairing() })
                            }
                            SecondaryButton("خاموش کردن گوشی اصلی", { confirm = "stop" }, danger = true)
                        }
                    }
                    TwoPhones.Role.REPLICA -> {
                        SCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("گوشی دوم", ChipKind.PRIMARY)
                                Chip(if (status.connected) "وصل" else "وصل نیست", if (status.connected) ChipKind.PRIMARY else ChipKind.DANGER)
                            }
                            KeyValue("گوشی اصلی", status.hubName)
                            Text("هر ثبت روی گوشی اصلی انجام می‌شود. وقتی وصل نیست، اطلاعات آخرین همگام‌سازی دیده می‌شود ولی ثبتی انجام نمی‌شود؛ فرم پاک نمی‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
                        }
                        status.problem?.let { Banner(it, ChipKind.ACCENT) }
                        if (owner) SecondaryButton("جدا کردن این گوشی", { confirm = "leave" }, danger = true)
                    }
                }
            }
        }

        fun work(block: () -> Unit) {
            error = null
            scope.launch { withContext(Dispatchers.IO) { runCatching(block) }.onFailure { error = Messages.of(it) } }
        }
        when (val c = confirm) {
            "hub" -> Confirm("گوشی اصلی", "این گوشی گوشی اصلی می‌شود و برای گوشی دوم در وای‌فای در دسترس می‌ماند (یک اعلان دائمی نشان داده می‌شود). آن را روشن و به شارژ نگه دارید.",
                "تأیید", onConfirm = { confirm = null; work { two.becomeHub() } }, onDismiss = { confirm = null })
            "stop" -> Confirm("خاموش کردن گوشی اصلی؟", "گوشی‌های دوم جدا می‌شوند و برای وصل شدن دوباره باید از نو جفت شوند. داده‌ها روی همین گوشی می‌ماند.",
                "خاموش کن", onConfirm = { confirm = null; work { two.stopHub() } }, onDismiss = { confirm = null }, danger = true)
            "leave" -> Confirm("جدا کردن این گوشی؟", "این گوشی از گوشی اصلی جدا و خالی می‌شود (داده‌ها روی گوشی اصلی می‌ماند). برای استفاده‌ی دوباره باید از نو وصل شود یا راه‌اندازی شود.",
                "جدا کن", onConfirm = { confirm = null; work { container.leaveAsSecondPhone() } }, onDismiss = { confirm = null }, danger = true)
            null -> Unit
            else -> if (c.startsWith("remove:")) {
                Confirm("حذف این گوشی؟", "آن گوشی دیگر نمی‌تواند وصل شود تا از نو جفت شود.", "حذف",
                    onConfirm = { confirm = null; work { two.removePeer(c.removePrefix("remove:")) } }, onDismiss = { confirm = null }, danger = true)
            }
        }
    }

    /**
     * Pairing this phone as the second phone: find the main phone (or type its address), compare the code on both
     * screens, then the main phone's owner confirms there and the books are copied here. The app then reopens on
     * the copy and asks for sign-in (with the same usernames and PINs as on the main phone).
     */
    @Composable
    fun PairAsSecondPhone(onCancel: (() -> Unit)? = null) {
        val container = (LocalContext.current.applicationContext as SabouApplication).container
        val two = container.twoPhones
        val scope = rememberCoroutineScope()
        val found = remember { mutableStateListOf<Pair<String, InetSocketAddress>>() }
        var manual by remember { mutableStateOf("") }
        var attempt by remember { mutableStateOf<Pair<PairingAttempt, InetSocketAddress>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        DisposableEffect(Unit) {
            val finder = two.discover { name, address ->
                scope.launch { if (found.none { it.second == address }) found += name to address }
            }
            onDispose { finder.stop(); attempt?.first?.close() }
        }

        fun connect(address: InetSocketAddress) {
            busy = true; error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { two.startPairing(address) } }
                busy = false
                result.onSuccess { attempt = it to address }.onFailure {
                    error = "به گوشی اصلی وصل نشد. روی گوشی اصلی «افزودن گوشی دوم» را زده‌اید؟ هر دو گوشی روی یک وای‌فای هستند؟"
                }
            }
        }

        FormCard("اتصال به گوشی اصلی") {
            error?.let { Banner(it) }
            val current = attempt
            when {
                progress != null -> {
                    Text(progress!!, style = SabouType.body, color = Sabou.colors.ink)
                }
                current == null -> {
                    Text("روی گوشی اصلی: من ← دو گوشی ← «افزودن گوشی دوم». بعد اینجا گوشی اصلی را انتخاب کنید.", style = SabouType.body, color = Sabou.colors.ink)
                    if (found.isEmpty()) Text("در حال جستجو در وای‌فای…", style = SabouType.caption, color = Sabou.colors.muted)
                    found.forEach { (name, address) ->
                        SecondaryButton("گوشی اصلی · ${Fa.digits(address.hostString)}", { connect(address) }, enabled = !busy)
                    }
                    Text("پیدا نشد؟ نشانی‌ای را که روی گوشی اصلی (من ← دو گوشی) نوشته شده وارد کنید:", style = SabouType.caption, color = Sabou.colors.muted)
                    TextInput("نشانی گوشی اصلی", manual, { manual = it }, placeholder = "مثلاً 192.168.1.20")
                    PrimaryButton("اتصال", {
                        val address = TwoPhones.parseAddress(Fa.latinDigits(manual))
                        if (address == null) error = "نشانی درست نیست؛ چهار عدد با نقطه، مثل 192.168.1.20" else connect(address)
                    }, enabled = manual.isNotBlank(), busy = busy)
                    onCancel?.let { SecondaryButton("انصراف", it) }
                }
                else -> {
                    val (pending, address) = current
                    Text("این کد را با کد روی گوشی اصلی مقایسه کنید:", style = SabouType.body, color = Sabou.colors.ink)
                    Text(Fa.digits(pending.code.chunked(3).joinToString(" ")), style = SabouType.display, color = Sabou.colors.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text("اگر یکی است، اینجا «یکی است» را بزنید و روی گوشی اصلی هم «یکی است، وصل شود» را.", style = SabouType.caption, color = Sabou.colors.muted)
                    PrimaryButton("یکی است", {
                        progress = "منتظر تأیید روی گوشی اصلی…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    two.finishPairing(pending, address) { done, total ->
                                        progress = "دریافت داده‌ها از گوشی اصلی: ${Fa.number(if (total > 0) done * 100 / total else 0L)}٪"
                                    }
                                }
                            }
                            result.onFailure {
                                progress = null; attempt = null
                                error = if (it is java.io.IOException) "اتصال انجام نشد (روی گوشی اصلی تأیید نشد یا ارتباط قطع شد). دوباره تلاش کنید." else Messages.of(it)
                            }
                        }
                    })
                    SecondaryButton("یکی نیست / انصراف", { pending.close(); attempt = null }, danger = true)
                }
            }
        }
    }

    /** One line at the top of every page on the second phone while it cannot reach the main phone. */
    @Composable
    fun ReplicaStatusLine() {
        val container = (LocalContext.current.applicationContext as SabouApplication).container
        val status by container.twoPhones.status.collectAsState()
        if (status.orphaned) {
            Banner(status.problem.orEmpty(), ChipKind.DANGER)
        } else if (status.role == TwoPhones.Role.REPLICA && (!status.connected || status.problem != null)) {
            Banner(status.problem ?: "به گوشی اصلی وصل نیست؛ اطلاعات آخرین همگام‌سازی نمایش داده می‌شود و ثبت انجام نمی‌شود.", ChipKind.ACCENT)
        }
    }
}
