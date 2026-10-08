package ir.sabou.app.data

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import ir.sabou.backup.StreamingBackupCodec
import ir.sabou.core.SabouCore
import ir.sabou.kernel.Clock
import ir.sabou.platform.StartupVerdict
import ir.sabou.platform.memory.InMemoryAnchorStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

sealed interface AppState {
    data object Opening : AppState
    data class Ready(val core: SabouCore) : AppState
    /** The database does not continue the anchored history, or the audit chain failed. */
    data class Recovery(val detail: String) : AppState
    data class Failed(val detail: String) : AppState
}

/**
 * Owns the encrypted database and the single SabouCore. All methods block: call them off the main thread.
 */
class AppContainer(private val context: Context) {
    private val keys = DeviceKeys(context)
    private val anchors = FileAnchorStore(File(context.noBackupFilesDir, "integrity.anchors"), keys)
    private var helper: SupportSQLiteOpenHelper? = null

    private val mutableState = MutableStateFlow<AppState>(AppState.Opening)
    val state: StateFlow<AppState> = mutableState

    init {
        System.loadLibrary("sqlcipher")
    }

    @Synchronized
    fun open(newEpoch: String? = null) {
        mutableState.value = AppState.Opening
        mutableState.value = try {
            val db = openHelper(DB_NAME, keys.databasePassphrase()).also { helper = it }.writableDatabase
            val core = SabouCore.open(AndroidSqlDatabase(db), anchors, Clock.SYSTEM, newDatabaseEpoch = newEpoch)
            when (val verdict = runCatching { core.verifyStartup() }.getOrElse { StartupVerdict.RollbackDetected(it.message ?: "ANCHOR") }) {
                StartupVerdict.Healthy -> AppState.Ready(core)
                is StartupVerdict.RollbackDetected -> AppState.Recovery(verdict.detail)
            }
        } catch (e: Throwable) {
            AppState.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    @Synchronized
    private fun close() {
        helper?.close()
        helper = null
    }

    /** Writes an encrypted, password-protected backup (format 4) of the whole database to [target]. */
    @Synchronized
    fun backup(password: CharArray, target: Uri) {
        val db = checkNotNull(helper) { "DATABASE_CLOSED" }.writableDatabase
        val plain = File(context.cacheDir, "backup-plain.db").also { it.delete() }
        try {
            db.execSQL("ATTACH DATABASE ? AS plaintext KEY ''", arrayOf<Any?>(plain.absolutePath))
            try {
                db.query("SELECT sqlcipher_export('plaintext')").use { it.moveToFirst() }
            } finally {
                db.execSQL("DETACH DATABASE plaintext")
            }
            val out = context.contentResolver.openOutputStream(target, "w") ?: error("BACKUP_TARGET_UNAVAILABLE")
            out.use { o -> plain.inputStream().use { i -> StreamingBackupCodec.encrypt(password, i, o) } }
        } finally {
            plain.delete()
        }
    }

    /**
     * Restores a backup: decrypt → open and fully verify the candidate on its own → copy it into a new
     * database encrypted with this device's key → announce the replacement (REBASE anchor) → swap files.
     * Nothing of the current database is touched until the candidate has passed every check.
     */
    @Synchronized
    fun restore(current: SabouCore?, password: CharArray, source: Uri) {
        val plainName = "restore-plain.db"
        val stagedName = "sabou-restore.db"
        context.deleteDatabase(plainName)
        context.deleteDatabase(stagedName)
        val plain = context.getDatabasePath(plainName).also { it.parentFile?.mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(source) ?: error("BACKUP_SOURCE_UNAVAILABLE")
            input.use { i -> plain.outputStream().use { o -> StreamingBackupCodec.decrypt(password, i, o, MAX_RESTORE_BYTES) } }

            // 1. Verify the candidate in isolation (schema version, audit chain) with throwaway anchors.
            val candidateHelper = openHelper(plainName, ByteArray(0))
            val epoch = try {
                val candidate = SabouCore.open(AndroidSqlDatabase(candidateHelper.writableDatabase), InMemoryAnchorStore())
                val verdict = candidate.verifyStartup()
                check(verdict == StartupVerdict.Healthy) { "BACKUP_INTEGRITY:${(verdict as StartupVerdict.RollbackDetected).detail}" }
                candidate.epoch
            } finally {
                candidateHelper.close()
            }

            // 2. Copy into a new database encrypted with the device key.
            val stagedHelper = openHelper(stagedName, keys.databasePassphrase())
            try {
                val staged = stagedHelper.writableDatabase
                staged.execSQL("ATTACH DATABASE ? AS plaintext KEY ''", arrayOf<Any?>(plain.absolutePath))
                try {
                    staged.query("SELECT sqlcipher_export('main', 'plaintext')").use { it.moveToFirst() }
                } finally {
                    staged.execSQL("DETACH DATABASE plaintext")
                }
            } finally {
                stagedHelper.close()
            }

            // 3. Announce, then swap.
            if (current != null) current.acceptReplacement(epoch, "RESTORE") else anchorsRebase(epoch, "RESTORE")
            close()
            context.deleteDatabase(DB_NAME)
            check(context.getDatabasePath(stagedName).renameTo(context.getDatabasePath(DB_NAME))) { "RESTORE_SWAP_FAILED" }
        } finally {
            context.deleteDatabase(plainName)
            context.deleteDatabase(stagedName)
        }
        open()
    }

    /** Erases all data. The new database's epoch is announced first, so the next start is healthy. */
    @Synchronized
    fun factoryReset(current: SabouCore?) {
        val epoch = SabouCore.newEpoch()
        if (current != null) current.acceptReplacement(epoch, "FACTORY_RESET") else anchorsRebase(epoch, "FACTORY_RESET")
        close()
        context.deleteDatabase(DB_NAME)
        open(newEpoch = epoch)
    }

    private fun anchorsRebase(epoch: String, reason: String) =
        ir.sabou.platform.IntegrityGuard(anchors, ir.sabou.platform.memory.InMemoryAuditStore()).recordRebase(epoch, reason, System.currentTimeMillis())

    private fun openHelper(name: String, passphrase: ByteArray): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }
            override fun onCreate(db: SupportSQLiteDatabase) = Unit   // schema is owned by ir.sabou.persistence.Schema
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
        return SupportOpenHelperFactory(passphrase, null, false).create(configuration)
    }

    companion object {
        const val DB_NAME = "sabou.db"
        private const val MAX_RESTORE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
