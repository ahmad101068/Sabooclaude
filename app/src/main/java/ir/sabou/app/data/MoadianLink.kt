package ir.sabou.app.data

import android.content.Context
import android.util.Base64
import ir.sabou.app.SabouApplication
import ir.sabou.core.MoadianClient
import ir.sabou.core.MoadianCredentials
import ir.sabou.core.SabouCore
import ir.sabou.kernel.BusinessDate
import java.time.LocalDate

/**
 * The connection to the tax system (ADR-0047): off until the owner turns it on; the taxpayer's private key and
 * certificate are sealed with this phone's Keystore and never enter a backup. Only the main phone sends.
 */
object MoadianLink {
    const val PRODUCTION = "https://tp.tax.gov.ir/requestsmanager/api/v2/"
    private const val PREFS = "moadian"

    data class Settings(val enabled: Boolean = false, val baseUrl: String = PRODUCTION, val keyPem: String = "", val certificatePem: String = "")

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun container(c: Context) = (c.applicationContext as SabouApplication).container

    private fun open(c: Context, name: String): String = prefs(c).getString(name, null)?.let { sealed ->
        runCatching { String(container(c).unseal(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrDefault("")
    }.orEmpty()

    private fun seal(c: Context, text: String): String? =
        if (text.isBlank()) null else Base64.encodeToString(container(c).seal(text.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    fun settings(c: Context) = Settings(
        prefs(c).getBoolean("enabled", false), prefs(c).getString("url", PRODUCTION) ?: PRODUCTION, open(c, "key"), open(c, "cert"),
    )

    /** Checks the key and certificate before saving; returns why they cannot be used, or null. */
    fun save(c: Context, s: Settings): String? {
        if (s.enabled) {
            if (!s.baseUrl.startsWith("https://")) return "نشانی سامانه باید با https:// شروع شود."
            runCatching { MoadianCredentials.parse(s.keyPem, s.certificatePem) }.onFailure { return "کلید خصوصی یا گواهی خوانده نشد (${it.javaClass.simpleName}). فایل‌های PEM را دوباره انتخاب کنید." }
        }
        prefs(c).edit().putBoolean("enabled", s.enabled).putString("url", s.baseUrl.trim())
            .putString("key", seal(c, s.keyPem)).putString("cert", seal(c, s.certificatePem)).apply()
        return null
    }

    fun forget(c: Context) = prefs(c).edit().clear().apply()

    /** A client for [core]'s seller; throws with a Persian message when sending is not possible from here. */
    fun client(c: Context, core: SabouCore): MoadianClient {
        if (container(c).twoPhones.isReplica) throw IllegalStateException("ارسال به سامانه فقط از گوشی اصلی انجام می‌شود.")
        val s = settings(c)
        if (!s.enabled) throw IllegalStateException("اتصال به سامانه‌ی مودیان خاموش است.")
        val memory = core.moadian.readiness(BusinessDate(LocalDate.now().toEpochDay())).profile?.memoryId.orEmpty()
        if (memory.isEmpty()) throw IllegalStateException("شناسه‌ی یکتای حافظه‌ی مالیاتی وارد نشده است.")
        return MoadianClient(s.baseUrl, memory, MoadianCredentials.parse(s.keyPem, s.certificatePem), MoadianClient.Http)
    }

    /** One line for the screen from whatever went wrong while talking to the tax system. */
    fun problem(e: Throwable): String = when (e) {
        is IllegalStateException -> e.message.orEmpty()
        is MoadianClient.ApiException -> "پاسخ سامانه: ${e.message}"
        is java.io.IOException -> "اتصال به سامانه برقرار نشد (${e.javaClass.simpleName}). اینترنت گوشی را بررسی کنید."
        is ir.sabou.kernel.DomainException -> ir.sabou.core.Messages.of(e)
        else -> "خطا: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
    }
}
