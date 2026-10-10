package ir.sabou.app

import android.content.Context
import ir.sabou.app.data.AppContainer
import ir.sabou.app.data.DeviceKeys
import java.io.File

/**
 * Test-only: wipes everything the app keeps (database, key, anchors, quarantine, drafts) the way clearing the
 * app's storage would, then opens [container] on a first-start state. Production code has no such shortcut:
 * there, replacing data always goes through the authorized, quarantining reset.
 */
object TestData {
    fun startClean(context: Context, container: AppContainer) {
        container.close()
        context.deleteDatabase(AppContainer.DB_NAME)
        File(context.noBackupFilesDir, "integrity.anchors").delete()
        File(context.noBackupFilesDir, "quarantine").deleteRecursively()
        File(context.noBackupFilesDir, "drafts.bin").delete()
        DeviceKeys(context).forgetDatabaseKey()
        container.open()
    }
}
