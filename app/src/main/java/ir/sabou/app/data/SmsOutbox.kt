package ir.sabou.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Base64
import ir.sabou.app.SabouApplication
import ir.sabou.core.Alert
import ir.sabou.core.SabouCore
import ir.sabou.core.SmsAlertPlan
import ir.sabou.core.SmsChannel
import ir.sabou.core.SmsGateways
import ir.sabou.core.SmsRecipient
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * SMS from this phone (ADR-0046): alerts to the people the owner chose, and orders to suppliers that receive them by
 * SMS. Sent through the phone's SIM card or an SMS service (Kavenegar, SMS.ir) with the owner's API key, which is
 * sealed with this phone's Keystore. Only the main phone sends (a second phone's data is a copy, ADR-0042).
 */
object SmsOutbox {
    private const val PREFS = "sms"
    private const val LOG_MAX = 200

    data class Settings(
        val enabled: Boolean = false,
        val channel: SmsChannel = SmsChannel.SIM,
        val apiKey: String = "",
        val line: String = "",
        val quietNight: Boolean = true,
        val recipients: List<SmsRecipient> = emptyList(),
    )

    data class LogEntry(val at: Long, val to: String, val text: String, val ref: String, val error: String?)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun container(c: Context) = (c.applicationContext as SabouApplication).container

    fun settings(c: Context): Settings {
        val p = prefs(c)
        val key = p.getString("key", null)?.let { sealed ->
            runCatching { String(container(c).unseal(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrDefault("")
        }.orEmpty()
        val recipients = runCatching {
            val a = JSONArray(p.getString("recipients", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val kinds = o.getJSONArray("kinds").let { k -> (0 until k.length()).mapNotNull { j -> runCatching { Alert.Kind.valueOf(k.getString(j)) }.getOrNull() }.toSet() }
                SmsRecipient(GlobalId.parse(o.getString("user")), o.getString("mobile"), kinds)
            }
        }.getOrDefault(emptyList())
        return Settings(
            p.getBoolean("enabled", false),
            runCatching { SmsChannel.valueOf(p.getString("channel", SmsChannel.SIM.name)!!) }.getOrDefault(SmsChannel.SIM),
            key, p.getString("line", "").orEmpty(), p.getBoolean("quiet", true), recipients,
        )
    }

    fun save(c: Context, s: Settings) {
        val recipients = JSONArray().apply {
            s.recipients.forEach { r ->
                put(JSONObject().put("user", r.userId.value).put("mobile", r.mobile).put("kinds", JSONArray(r.kinds.map { it.name })))
            }
        }
        val sealed = if (s.apiKey.isBlank()) null else Base64.encodeToString(container(c).seal(s.apiKey.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs(c).edit()
            .putBoolean("enabled", s.enabled).putString("channel", s.channel.name).putString("key", sealed)
            .putString("line", s.line.trim()).putBoolean("quiet", s.quietNight).putString("recipients", recipients.toString())
            .apply()
        PhoneAlerts.reschedule(c)
    }

    /** A replaced database (restore, factory reset): its people and keys are not these. */
    fun forget(c: Context) = prefs(c).edit().clear().apply()

    /** Sending is possible from this phone: switched on, a way to send, and not a second phone. */
    fun ready(c: Context): Boolean {
        if (container(c).twoPhones.isReplica) return false
        val s = settings(c)
        return s.enabled && (s.channel == SmsChannel.SIM || s.apiKey.isNotBlank())
    }

    fun hasRecipients(c: Context) = ready(c) && settings(c).recipients.isNotEmpty()

    fun simPermitted(c: Context) = c.checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Sends now (call off the main thread); the result is also written to the send log under [ref]. */
    fun send(c: Context, to: List<String>, text: String, ref: String): String? {
        val s = settings(c)
        val error = try {
            when {
                container(c).twoPhones.isReplica -> "پیامک فقط از گوشی اصلی فرستاده می‌شود."
                !s.enabled -> "ارسال پیامک خاموش است."
                s.channel == SmsChannel.SIM -> sendSim(c, to, text)
                else -> sendHttp(s, to, text)
            }
        } catch (e: Exception) {
            e.message ?: "خطای ناشناخته"
        }
        to.forEach { log(c, LogEntry(System.currentTimeMillis(), it, text, ref, error)) }
        return error
    }

    /** Sends on a background thread; nothing waits for it. */
    fun sendLater(c: Context, to: List<String>, text: String, ref: String) {
        val app = c.applicationContext
        kotlin.concurrent.thread(name = "sabou-sms", isDaemon = true) { send(app, to, text, ref) }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun sendSim(c: Context, to: List<String>, text: String): String? {
        if (c.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "اجازه‌ی ارسال پیامک به برنامه داده نشده است."
        val manager = if (android.os.Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        to.forEach { number ->
            val mobile = ir.sabou.kernel.Mobile.normalize(number) ?: return "شماره همراه نامعتبر: $number"
            val parts = manager.divideMessage(text)
            if (parts.size == 1) manager.sendTextMessage(mobile, null, text, null, null)
            else manager.sendMultipartTextMessage(mobile, null, parts, null, null)
        }
        return null
    }

    private fun sendHttp(s: Settings, to: List<String>, text: String): String? {
        if (s.apiKey.isBlank()) return "کلید API سامانه‌ی پیامک وارد نشده است."
        val request = SmsGateways.request(s.channel, s.apiKey, s.line, to, text)
        val conn = java.net.URL(request.url).openConnection() as javax.net.ssl.HttpsURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", request.contentType)
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            SmsGateways.error(s.channel, status, body)
        } catch (e: java.io.IOException) {
            "اتصال به سامانه‌ی پیامک برقرار نشد (${e.javaClass.simpleName})."
        } finally {
            conn.disconnect()
        }
    }

    private fun log(c: Context, e: LogEntry) {
        synchronized(this) {
            val p = prefs(c)
            val a = runCatching { JSONArray(p.getString("log", "[]")) }.getOrDefault(JSONArray())
            a.put(JSONObject().put("at", e.at).put("to", e.to).put("text", e.text).put("ref", e.ref).put("error", e.error ?: JSONObject.NULL))
            val kept = JSONArray()
            for (i in maxOf(0, a.length() - LOG_MAX) until a.length()) kept.put(a.get(i))
            p.edit().putString("log", kept.toString()).apply()
        }
    }

    /** Newest first. */
    fun log(c: Context): List<LogEntry> = runCatching {
        val a = JSONArray(prefs(c).getString("log", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            LogEntry(o.getLong("at"), o.getString("to"), o.getString("text"), o.optString("ref"), if (o.isNull("error")) null else o.getString("error"))
        }.reversed()
    }.getOrDefault(emptyList())

    /** Each recipient's new alerts, computed with that person's permissions; called by the alert job (background). */
    internal fun checkAlerts(c: Context, core: SabouCore) {
        if (!hasRecipients(c)) return
        val s = settings(c)
        val today = BusinessDate(LocalDate.now().toEpochDay())
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        val p = prefs(c)
        s.recipients.forEach { r ->
            val sentKey = "sent:${r.userId.value}:${r.mobile}"
            val sent = p.getStringSet(sentKey, emptySet()).orEmpty()
            val plan = SmsAlertPlan.plan(r, core.alerts.forUser(r.userId, today), sent, s.quietNight && SmsAlertPlan.isQuiet(now))
            // What failed to go stays unsent and is tried at the next check.
            val ok = plan.text == null || send(c, listOf(r.mobile), plan.text!!, "ALERT") == null
            if (ok) p.edit().putStringSet(sentKey, plan.sent).apply()
        }
    }

    /** After an order is placed: if its supplier takes orders by SMS, send it (the caller is on a background thread). */
    fun orderPlaced(c: Context, core: SabouCore, orderId: GlobalId) {
        if (!ready(c)) return
        val sms = runCatching { core.buying.orderSms(orderId) }.getOrNull() ?: return
        if (sms.autoSend && sms.mobile != null) sendLater(c, listOf(sms.mobile!!), sms.text, orderRef(orderId))
    }

    fun orderRef(orderId: GlobalId) = "ORDER:${orderId.value}"
}
