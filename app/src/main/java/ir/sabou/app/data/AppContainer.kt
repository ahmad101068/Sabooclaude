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

    /** Opens once per process; later calls (e.g. after an activity re-creation) do nothing. */
    @Synchronized
    fun ensureOpen() {
        if (helper == null && state.value is AppState.Opening) {
            // A crash in the middle of a backup or restore must not leave a plaintext copy behind.
            File(context.cacheDir, BACKUP_PLAIN).delete()
            context.deleteDatabase(RESTORE_PLAIN)
            context.deleteDatabase(RESTORE_STAGED)
            open()
        }
    }

    @Synchronized
    fun open(newEpoch: String? = null) {
        close()   // re-opening (retry, after restore/reset) never leaks the previous connection
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

    /**
     * Writes an encrypted, password-protected backup (format 4) of the whole database to [target].
     * The core checks the signed-in user's permission, verifies the full audit chain and audits it first.
     */
    @Synchronized
    fun backup(core: SabouCore, password: CharArray, target: Uri) {
        core.authorizeBackup()
        val db = checkNotNull(helper) { "DATABASE_CLOSED" }.writableDatabase
        val plain = File(context.cacheDir, BACKUP_PLAIN).also { it.delete() }
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
        context.deleteDatabase(RESTORE_PLAIN)
        context.deleteDatabase(RESTORE_STAGED)
        val plain = context.getDatabasePath(RESTORE_PLAIN).also { it.parentFile?.mkdirs() }
        val staged = context.getDatabasePath(RESTORE_STAGED)
        val epoch: String
        try {
            val input = context.contentResolver.openInputStream(source) ?: error("BACKUP_SOURCE_UNAVAILABLE")
            input.use { i -> plain.outputStream().use { o -> StreamingBackupCodec.decrypt(password, i, o, MAX_RESTORE_BYTES) } }

            // 1. Verify the candidate in isolation (schema version, full audit chain) with throwaway anchors.
            val candidateHelper = openHelper(RESTORE_PLAIN, ByteArray(0))
            epoch = try {
                val candidate = SabouCore.open(AndroidSqlDatabase(candidateHelper.writableDatabase), InMemoryAnchorStore())
                val verdict = candidate.verifyStartup()
                check(verdict == StartupVerdict.Healthy) { "BACKUP_INTEGRITY:${(verdict as StartupVerdict.RollbackDetected).detail}" }
                candidate.epoch
            } finally {
                candidateHelper.close()
            }

            // 2. Copy into a new database encrypted with the device key.
            val stagedHelper = openHelper(RESTORE_STAGED, keys.databasePassphrase())
            try {
                val db = stagedHelper.writableDatabase
                db.execSQL("ATTACH DATABASE ? AS plaintext KEY ''", arrayOf<Any?>(plain.absolutePath))
                try {
                    db.query("SELECT sqlcipher_export('main', 'plaintext')").use { it.moveToFirst() }
                } finally {
                    db.execSQL("DETACH DATABASE plaintext")
                }
            } finally {
                stagedHelper.close()
            }
        } catch (e: Throwable) {
            // Nothing of the live database has been touched yet.
            context.deleteDatabase(RESTORE_STAGED)
            throw e
        } finally {
            context.deleteDatabase(RESTORE_PLAIN)
        }

        // 3. Announce (authorized and audited by the core when signed in), then swap. From here on the
        //    app always re-opens, whatever happens.
        try {
            if (current != null) current.acceptReplacement(epoch, "RESTORE") else anchorsRebase(epoch, "RESTORE")
        } catch (e: Throwable) {
            context.deleteDatabase(RESTORE_STAGED)
            throw e
        }
        try {
            close()
            // rename(2) replaces the live file atomically; only its side files are removed first.
            listOf("-journal", "-wal", "-shm").forEach { File(context.getDatabasePath(DB_NAME).path + it).delete() }
            check(staged.renameTo(context.getDatabasePath(DB_NAME))) { "RESTORE_SWAP_FAILED" }
        } finally {
            open()
        }
    }

    /** Erases all data. The new database's epoch is announced first, so the next start is healthy. */
    @Synchronized
    fun factoryReset(current: SabouCore?) {
        val epoch = SabouCore.newEpoch()
        if (current != null) current.acceptReplacement(epoch, "FACTORY_RESET") else anchorsRebase(epoch, "FACTORY_RESET")
        try {
            close()
            context.deleteDatabase(DB_NAME)
        } finally {
            open(newEpoch = epoch)
        }
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
            // Never let the framework delete a database it considers corrupt: keep it for recovery.
            override fun onCorruption(db: SupportSQLiteDatabase) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
        return SupportOpenHelperFactory(passphrase, null, false).create(configuration)
    }

    companion object {
        const val DB_NAME = "sabou.db"
        private const val BACKUP_PLAIN = "backup-plain.db"
        private const val RESTORE_PLAIN = "restore-plain.db"
        private const val RESTORE_STAGED = "sabou-restore.db"
        private const val MAX_RESTORE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
