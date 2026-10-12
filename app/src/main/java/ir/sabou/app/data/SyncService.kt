package ir.sabou.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import ir.sabou.app.MainActivity
import ir.sabou.app.R
import ir.sabou.app.SabouApplication
import ir.sabou.sync.SyncHub

/**
 * Keeps the main phone serving the second phone while the app is in the background (ADR-0042): a foreground
 * service with a permanent notification, holding the Wi-Fi awake, and announcing itself on the network.
 */
class SyncService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var registration: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION, notification)
        val app = application as SabouApplication
        val container = app.container
        if (container.twoPhones.role != TwoPhones.Role.HUB) { stopSelf(); return START_NOT_STICKY }
        kotlin.concurrent.thread(name = "sabou-sync-start", isDaemon = true) {
            container.ensureOpen()
            runCatching {
                val port = container.twoPhones.hub().start(SyncHub.DEFAULT_PORT)
                container.twoPhones.hubStarted()
                announce(container.twoPhones.hub().hubId, port)
            }
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                @Suppress("DEPRECATION")
                getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sabou:sync").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        if (wakeLock == null) {
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sabou:sync").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        return START_STICKY
    }

    private fun announce(hubId: String, port: Int) {
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = TwoPhones.serviceName(hubId)
            serviceType = TwoPhones.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    override fun onDestroy() {
        registration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        registration = null
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        val container = (application as SabouApplication).container
        if (container.twoPhones.role != TwoPhones.Role.HUB) runCatching { container.twoPhones.hub().stop() }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "گوشی اصلی", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle("سابو · گوشی اصلی")
            .setContentText("گوشی دوم از طریق همین گوشی کار می‌کند؛ این گوشی را روشن و روی وای‌فای نگه دارید.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "sabou-sync"
        private const val NOTIFICATION = 4310

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}

/** After a restart of the main phone, it serves again without anyone opening the app. */
class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as SabouApplication).container
        if (container.twoPhones.role == TwoPhones.Role.HUB) SyncService.start(context)
    }
}
