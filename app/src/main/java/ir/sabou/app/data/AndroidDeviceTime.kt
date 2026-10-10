package ir.sabou.app.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import ir.sabou.platform.DeviceTime

/**
 * Uptime since boot (not changed by setting the clock) and the device's boot count, so a login lock measured
 * here cannot be shortened by changing the date or time.
 */
class AndroidDeviceTime(private val context: Context) : DeviceTime {
    override fun bootId(): String =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()

    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}
