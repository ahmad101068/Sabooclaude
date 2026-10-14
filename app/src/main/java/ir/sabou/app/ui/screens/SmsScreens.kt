package ir.sabou.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import ir.sabou.app.R
import ir.sabou.app.data.PhoneAlerts
import ir.sabou.app.data.SmsOutbox
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
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
import ir.sabou.app.ui.components.Segmented
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Alert
import ir.sabou.core.Fa
import ir.sabou.core.SmsChannel
import ir.sabou.core.SmsRecipient
import ir.sabou.core.SmsText
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SMS settings (ADR-0046): how this phone sends, who receives which alerts, a test and the send log. Owner only. */
object SmsScreens {
    private val channels = listOf(SmsChannel.SIM, SmsChannel.KAVENEGAR, SmsChannel.SMS_IR)

    @Composable
    fun Settings(nav: Nav) {
        val context = LocalContext.current
        val session = LocalSession.current
        val saved = remember { SmsOutbox.settings(context) }
        var enabled by rememberSaveable { mutableStateOf(saved.enabled) }
        var channel by rememberSaveable { mutableStateOf(saved.channel) }
        var apiKey by rememberSaveable { mutableStateOf(saved.apiKey) }
        var line by rememberSaveable { mutableStateOf(saved.line) }
        var quiet by rememberSaveable { mutableStateOf(saved.quietNight) }
        val recipients = remember { mutableStateListOf<SmsRecipient>().apply { addAll(saved.recipients) } }
        var permitted by remember { mutableStateOf(SmsOutbox.simPermitted(context)) }
        var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        var log by remember { mutableStateOf(SmsOutbox.log(context)) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
        val users by load(session) { overview.users().filter { it.isActive } }
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        val replica = (context.applicationContext as ir.sabou.app.SabouApplication).container.twoPhones.isReplica

        fun current() = SmsOutbox.Settings(enabled, channel, apiKey, line, quiet, recipients.toList())
        val lineOk = channel != SmsChannel.SMS_IR || Fa.latinDigits(line).trim().matches(Regex("\\d{4,20}"))
        val canSave = channel == SmsChannel.SIM || (apiKey.isNotBlank() && lineOk)

        Column(Modifier.fillMaxSize()) {
            Header("پیامک", "هشدارها به همراه افراد و سفارش‌ها به تأمین‌کننده", onBack = nav.back)
            Page {
                if (replica) Banner("این گوشی دوم است؛ پیامک فقط از گوشی اصلی فرستاده می‌شود.", ChipKind.ACCENT)
                FormCard("روش ارسال") {
                    Segmented(listOf("روشن", "خاموش"), if (enabled) 0 else 1, { enabled = it == 0 })
                    Segmented(listOf("سیم‌کارت گوشی", "کاوه‌نگار", "SMS.ir"), channels.indexOf(channel), { channel = channels[it] })
                    when (channel) {
                        SmsChannel.SIM -> {
                            Text("پیامک‌ها با سیم‌کارت همین گوشی و به هزینه‌ی همان خط فرستاده می‌شوند؛ اینترنت لازم نیست. گوشی باید روشن و آنتن‌دار باشد.",
                                style = SabouType.caption, color = Sabou.colors.muted)
                            if (!permitted) SecondaryButton("اجازه‌ی ارسال پیامک", { ask.launch(android.Manifest.permission.SEND_SMS) })
                            else Banner("اجازه‌ی ارسال پیامک داده شده است.", ChipKind.PRIMARY, R.drawable.ic_check)
                        }
                        else -> {
                            Text("از پنل سامانه‌ی پیامک، کلید API و شماره‌ی خط خود را بردارید. کلید فقط روی همین گوشی و رمزشده نگه داشته می‌شود و در پشتیبان نمی‌آید. ارسال به اینترنت گوشی نیاز دارد.",
                                style = SabouType.caption, color = Sabou.colors.muted)
                            TextInput("کلید API", apiKey, { apiKey = it }, secret = true)
                            TextInput(if (channel == SmsChannel.SMS_IR) "شماره خط" else "شماره خط (اختیاری؛ خالی = خط پیش‌فرض)", line, { line = it }, keyboard = KeyboardType.Number,
                                error = if (!lineOk) "شماره خط لازم است" else null)
                        }
                    }
                    Row(Modifier.clickable { quiet = !quiet }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = quiet, onCheckedChange = { quiet = it })
                        Text("شب‌ها (۲۳ تا ۷) هشدار پیامک نشود؛ صبح فرستاده می‌شود", style = SabouType.body, color = Sabou.colors.ink)
                    }
                    message?.let { (text, ok) -> Banner(text, if (ok) ChipKind.PRIMARY else ChipKind.DANGER, if (ok) R.drawable.ic_check else R.drawable.ic_alert) }
                    PrimaryButton("ذخیره", {
                        SmsOutbox.save(context, current())
                        message = "ذخیره شد." to true
                    }, enabled = canSave)
                }
                TestCard(enabled && canSave && !replica, busy) { mobile ->
                    SmsOutbox.save(context, current())
                    busy = true
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { SmsOutbox.send(context, listOf(mobile), "سابو: پیامک آزمایشی. ارسال پیامک برقرار است.", "TEST") }
                        busy = false
                        message = (error?.let { "ارسال نشد: $it" } ?: "فرستاده شد؛ رسیدن آن را روی گوشی گیرنده ببینید.") to (error == null)
                        log = SmsOutbox.log(context)
                    }
                }
                RecipientsCard(users.orNull().orEmpty(), recipients) {
                    SmsOutbox.save(context, current())
                    message = "گیرندگان ذخیره شد." to true
                    PhoneAlerts.soon()
                }
                SCard {
                    Text("سابقه‌ی ارسال", style = SabouType.section, color = Sabou.colors.ink)
                    if (log.isEmpty()) EmptyState("هنوز پیامکی فرستاده نشده است.")
                    log.take(40).forEach { e ->
                        val time = Fa.digits(java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date(e.at)))
                        KeyValue("${Fa.digits(e.to)} · $time", if (e.error == null) "فرستاده شد" else "ناموفق", if (e.error == null) Sabou.colors.primary else Sabou.colors.danger)
                        Text(e.error ?: e.text.lineSequence().first(), style = SabouType.caption, color = Sabou.colors.muted)
                        Divider()
                    }
                }
            }
        }
    }

    @Composable
    private fun TestCard(enabled: Boolean, busy: Boolean, onSend: (String) -> Unit) {
        var mobile by rememberSaveable { mutableStateOf("") }
        val normalized = Mobile.normalize(mobile)
        FormCard("پیامک آزمایشی") {
            TextInput("شماره همراه", mobile, { mobile = it }, keyboard = KeyboardType.Phone, placeholder = "۰۹…",
                error = if (mobile.isNotBlank() && normalized == null) "شماره همراه معتبر نیست" else null)
            PrimaryButton("ارسال آزمایشی", { onSend(normalized!!) }, enabled = enabled && normalized != null, busy = busy)
        }
    }

    @Composable
    private fun RecipientsCard(users: List<ir.sabou.platform.User>, recipients: MutableList<SmsRecipient>, onChanged: () -> Unit) {
        var user by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var mobile by rememberSaveable { mutableStateOf("") }
        val kinds = remember { mutableStateListOf<Alert.Kind>().apply { addAll(Alert.Kind.entries) } }
        val names = users.associate { it.id to it.displayName }
        val normalized = Mobile.normalize(mobile)
        FormCard("گیرندگان هشدار") {
            Text("هر گیرنده هشدارهایی را می‌گیرد که کاربر انتخاب‌شده طبق دسترسی‌هایش در برنامه می‌بیند؛ هر مورد یک بار، و اگر رفع شد و دوباره پیش آمد، دوباره. چند هشدار هم‌زمان در یک پیامک می‌آیند (حداکثر ${Fa.number(5)} بخش).",
                style = SabouType.caption, color = Sabou.colors.muted)
            recipients.toList().forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${names[r.userId] ?: "کاربر حذف‌شده"} · ${Fa.digits(r.mobile)}", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                        Text(if (r.kinds.size == Alert.Kind.entries.size) "همه‌ی هشدارها" else r.kinds.joinToString("، ") { PhoneAlerts.kindName(it) },
                            style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    Text("حذف", style = SabouType.label, color = Sabou.colors.danger, modifier = Modifier.clickable { recipients.remove(r); onChanged() })
                }
                Divider()
            }
            Picker("کاربر", users.map { Choice(it.id, it.displayName, it.username) }, user, { user = it })
            TextInput("شماره همراه", mobile, { mobile = it }, keyboard = KeyboardType.Phone, placeholder = "۰۹…",
                error = if (mobile.isNotBlank() && normalized == null) "شماره همراه معتبر نیست" else null)
            Text("کدام هشدارها", style = SabouType.bodyStrong, color = Sabou.colors.ink)
            Alert.Kind.entries.forEach { k ->
                Row(Modifier.clickable { if (k in kinds) kinds.remove(k) else kinds.add(k) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = k in kinds, onCheckedChange = { on -> if (on) kinds.add(k) else kinds.remove(k) })
                    Text(PhoneAlerts.kindName(k), style = SabouType.body, color = Sabou.colors.ink)
                }
            }
            SecondaryButton("افزودن گیرنده", {
                recipients.removeAll { it.userId == user && it.mobile == normalized }
                recipients.add(SmsRecipient(user!!, normalized!!, kinds.toSet()))
                mobile = ""; user = null
                onChanged()
            }, enabled = user != null && normalized != null && kinds.isNotEmpty())
            Text("هزینه: هر بخش پیامک فارسی ${Fa.number(70)} نویسه است (پیامک چندبخشی ${Fa.number(67)} نویسه در هر بخش). نمونه: یک هشدار معمولی ${Fa.number(SmsText.parts("ب".repeat(110)).toLong())} بخش.",
                style = SabouType.caption, color = Sabou.colors.muted)
        }
    }
}
