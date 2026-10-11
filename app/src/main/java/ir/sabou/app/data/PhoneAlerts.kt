package ir.sabou.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import ir.sabou.app.MainActivity
import ir.sabou.app.R
import ir.sabou.app.SabouApplication
import ir.sabou.core.Alert
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import java.time.LocalDate

/**
 * Phone notifications of alerts (ADR-0024). The phone's last signed-in user receives their alerts — computed with
 * their permissions, even when nobody is signed in — from a periodic system job and right after anything is saved.
 * Each situation is announced once (its key is remembered) and again only if it comes back after being resolved.
 */
object PhoneAlerts {
    private const val PREFS = "alerts"
    private const val USER = "user"
    private const val ENABLED = "enabled"
    private const val SENT = "sent"
    private const val PERIODIC_JOB = 4101
    private const val SOON_JOB = 4102
    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean(ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(ENABLED, on).apply()
        if (on) schedule(c) else c.getSystemService(JobScheduler::class.java).cancel(PERIODIC_JOB)
    }

    /** The phone's user is whoever signed in last here. */
    fun onSignedIn(c: Context, userId: GlobalId) {
        val p = prefs(c)
        // A different person on this phone starts with nothing announced.
        if (p.getString(USER, null) != userId.value) p.edit().putString(USER, userId.value).putStringSet(SENT, emptySet()).apply()
        if (enabled(c)) schedule(c)
    }

    /** Forget the user (factory reset, restore): no alerts until someone signs in again. */
    fun forget(c: Context) {
        prefs(c).edit().clear().apply()
        c.getSystemService(JobScheduler::class.java).cancelAll()
    }

    private fun schedule(c: Context) {
        val info = JobInfo.Builder(PERIODIC_JOB, ComponentName(c, AlertJobService::class.java))
            .setPeriodic(60 * 60_000L)
            .setPersisted(true)
            .build()
        c.getSystemService(JobScheduler::class.java).schedule(info)
    }

    /** Check shortly (after something was saved): a purchase may have made a price jump or a sale a shortage. */
    fun soon() {
        val c = app ?: return
        if (!enabled(c) || prefs(c).getString(USER, null) == null) return
        val info = JobInfo.Builder(SOON_JOB, ComponentName(c, AlertJobService::class.java))
            .setMinimumLatency(3_000L).setOverrideDeadline(60_000L).build()
        c.getSystemService(JobScheduler::class.java).schedule(info)
    }

    /** Computes the user's alerts and posts the new ones. Runs on a background thread. */
    internal fun check(c: Context) {
        if (!enabled(c)) return
        val p = prefs(c)
        val user = p.getString(USER, null)?.let { runCatching { GlobalId.parse(it) }.getOrNull() } ?: return
        val container = (c.applicationContext as SabouApplication).container
        container.ensureOpen()
        val core = (container.state.value as? AppState.Ready)?.core ?: return
        val alerts = core.alerts.forUser(user, BusinessDate(LocalDate.now().toEpochDay()))
        val sent = p.getStringSet(SENT, emptySet()).orEmpty()
        val fresh = alerts.filter { it.key !in sent }
        // Keys of resolved situations are dropped, so a recurrence is announced again.
        p.edit().putStringSet(SENT, alerts.map { it.key }.toSet()).apply()
        if (fresh.isNotEmpty()) post(c, fresh)
    }

    private fun post(c: Context, alerts: List<Alert>) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = c.getSystemService(NotificationManager::class.java)
        Alert.Kind.entries.forEach { kind ->
            manager.createNotificationChannel(NotificationChannel(channel(kind), channelName(kind), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alerts.forEach { a ->
            // On the lock screen only «سابو: هشدار تازه» shows; the details need the phone unlocked.
            val public = android.app.Notification.Builder(c, channel(a.kind)).setSmallIcon(R.drawable.ic_alert).setContentTitle("سابو").setContentText("هشدار تازه").build()
            val n = android.app.Notification.Builder(c, channel(a.kind))
                .setSmallIcon(R.drawable.ic_alert)
                .setContentTitle(a.title)
                .setContentText(a.text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(a.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .build()
            manager.notify(a.key.hashCode(), n)
        }
    }

    private fun channel(kind: Alert.Kind) = "alerts.${kind.name.lowercase()}"

    private fun channelName(kind: Alert.Kind) = when (kind) {
        Alert.Kind.LOW_STOCK -> "کمبود کالا"
        Alert.Kind.PRICE_CHANGE -> "تغییر قیمت خرید"
        Alert.Kind.CHEQUE_DUE -> "سررسید چک"
        Alert.Kind.SHORTAGE -> "کسری مصرف"
        Alert.Kind.APPROVAL -> "فاکتور در انتظار تأیید"
        Alert.Kind.FOOD_COST -> "بهای مواد غذاها"
    }
}

/** The system job that runs the alert check (periodic, and shortly after something is saved). */
class AlertJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        kotlin.concurrent.thread(name = "sabou-alerts", isDaemon = true) {
            // A check that cannot run now (database busy or being replaced) just waits for the next one.
            runCatching { PhoneAlerts.check(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
