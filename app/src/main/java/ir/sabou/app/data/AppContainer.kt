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
            File(context.cacheDir, BACKUP_PLAIN).delete()   // left by versions before payload v2
            File(context.cacheDir, BACKUP_SEALED).delete()
            context.deleteDatabase(RESTORE_CANDIDATE)
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
                StartupVerdict.Healthy -> AppState.Ready(core).also { verifyInBackgroundIfDue(core) }
                is StartupVerdict.RollbackDetected -> AppState.Recovery(verdict.detail)
            }
        } catch (e: Throwable) {
            AppState.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * The daily full audit check (ADR-0004), off the startup path. Called after opening and whenever the
     * app comes to the foreground; a failure moves the app to the recovery screen.
     */
    fun verifyInBackgroundIfDue() {
        (state.value as? AppState.Ready)?.core?.let(::verifyInBackgroundIfDue)
    }

    private fun verifyInBackgroundIfDue(core: SabouCore) {
        if (verifying) return
        if (!runCatching { core.backgroundVerificationDue() }.getOrDefault(false)) return
        verifying = true
        kotlin.concurrent.thread(name = "sabou-audit-verify", isDaemon = true) {
            try {
                // A transient error (e.g. the database being replaced meanwhile) just means: try next time.
                val verdict = runCatching { core.verifyAuditInBackground() }.getOrNull()
                if (verdict is StartupVerdict.RollbackDetected) synchronized(this) {
                    if ((state.value as? AppState.Ready)?.core === core) mutableState.value = AppState.Recovery(verdict.detail)
                }
            } finally {
                verifying = false
            }
        }
    }

    @Volatile private var verifying = false

    // ---------------------------------------------------------------- unfinished forms (ADR-0010)

    private val draftsFile = File(context.noBackupFilesDir, "drafts.bin")

    /** Stores the draft encrypted with a device key; null removes it. */
    fun saveDrafts(draft: android.os.Bundle?) {
        if (draft == null) { draftsFile.delete(); return }
        runCatching {
            val tmp = File(draftsFile.path + ".tmp")
            java.io.FileOutputStream(tmp).use { out -> out.write(keys.seal(ir.sabou.app.ui.Drafts.marshall(draft))); out.fd.sync() }
            check(tmp.renameTo(draftsFile))
        }.onFailure { draftsFile.delete() }   // best effort: a draft is a convenience, never a requirement
    }

    /** Reads and removes the stored draft; anything unreadable is simply dropped. */
    fun takeDrafts(): android.os.Bundle? {
        if (!draftsFile.exists()) return null
        return try {
            ir.sabou.app.ui.Drafts.unmarshall(keys.open(draftsFile.readBytes()), context.classLoader)
        } catch (e: Throwable) {
            null
        } finally {
            draftsFile.delete()
        }
    }

    fun clearDrafts() { draftsFile.delete() }

    /** One background thread keeps saves and clears in the order they were asked for. */
    private val draftWriter = java.util.concurrent.Executors.newSingleThreadExecutor()
    fun saveDraftsLater(draft: android.os.Bundle?) { draftWriter.execute { saveDrafts(draft) } }
    fun clearDraftsLater() { draftWriter.execute { clearDrafts() } }

    /** Internal for the device tests (they swap database files while the app is closed). */
    @Synchronized
    internal fun close() {
        helper?.close()
        helper = null
    }

    /**
     * Writes an encrypted, password-protected backup (format 4) of the whole database to [target].
     * The core checks the signed-in user's permission, verifies the full audit chain and audits it first.
     *
     * No plaintext copy ever touches the disk: the database is exported into a temporary SQLCipher file
     * under a fresh random key, and the backup carries that key and file inside its password encryption
     * (payload v2: MAGIC + key + encrypted database).
     */
    @Synchronized
    fun backup(core: SabouCore, password: CharArray, target: Uri) {
        core.authorizeBackup()
        val db = checkNotNull(helper) { "DATABASE_CLOSED" }.writableDatabase
        val sealed = File(context.cacheDir, BACKUP_SEALED).also { it.delete() }
        val exportKey = randomHexKey()
        try {
            db.execSQL("ATTACH DATABASE ? AS export KEY '$exportKey'", arrayOf<Any?>(sealed.absolutePath))
            try {
                db.query("SELECT sqlcipher_export('export')").use { it.moveToFirst() }
            } finally {
                db.execSQL("DETACH DATABASE export")
            }
            val out = context.contentResolver.openOutputStream(target, "w") ?: error("BACKUP_TARGET_UNAVAILABLE")
            out.use { o ->
                sealed.inputStream().use { file ->
                    val payload = java.io.SequenceInputStream(java.io.ByteArrayInputStream(PAYLOAD_MAGIC + exportKey.toByteArray(Charsets.US_ASCII)), file)
                    StreamingBackupCodec.encrypt(password, payload, o)
                }
            }
        } finally {
            sealed.delete()
        }
    }

    private fun randomHexKey(): String =
        ByteArray(32).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * Writes the decrypted payload to [file]: v2 (MAGIC + key + SQLCipher file) keeps the file encrypted
     * and returns its key; a v1 payload (a plain SQLite file, older backups) is written as is → "".
     */
    private class PayloadSplitter(private val file: java.io.OutputStream) : java.io.OutputStream() {
        private val head = java.io.ByteArrayOutputStream()
        private var decided = false
        var key: String = ""
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (decided) { file.write(b, off, len); return }
            head.write(b, off, len)
            if (head.size() >= HEADER) decide()
        }
        private fun decide() {
            decided = true
            val bytes = head.toByteArray()
            if (bytes.size >= HEADER && bytes.copyOfRange(0, PAYLOAD_MAGIC.size).contentEquals(PAYLOAD_MAGIC)) {
                key = String(bytes, PAYLOAD_MAGIC.size, KEY_CHARS, Charsets.US_ASCII)
                require(key.all { it in '0'..'9' || it in 'a'..'f' }) { "bad_backup_key" }
                file.write(bytes, HEADER, bytes.size - HEADER)
            } else {
                file.write(bytes)
            }
        }
        override fun flush() = file.flush()
        override fun close() {
            if (!decided) decide()
            file.close()
        }
        companion object { const val HEADER = 8 + KEY_CHARS }
    }

    /**
     * Restores a backup: decrypt → open and fully verify the candidate on its own → copy it into a new
     * database encrypted with this device's key → announce the replacement (REBASE anchor) → swap files.
     * Nothing of the current database is touched until the candidate has passed every check.
     */
    @Synchronized
    fun restore(current: SabouCore?, password: CharArray, source: Uri) {
        context.deleteDatabase(RESTORE_CANDIDATE)
        context.deleteDatabase(RESTORE_STAGED)
        val plain = context.getDatabasePath(RESTORE_CANDIDATE).also { it.parentFile?.mkdirs() }
        var payloadKey = ""
        val staged = context.getDatabasePath(RESTORE_STAGED)
        val epoch: String
        try {
            val input = context.contentResolver.openInputStream(source) ?: error("BACKUP_SOURCE_UNAVAILABLE")
            // The candidate stays encrypted on disk under the backup's own key (v2); only v1 backups are plain.
            input.use { i ->
                val splitter = PayloadSplitter(plain.outputStream())
                splitter.use { o -> StreamingBackupCodec.decrypt(password, i, o, MAX_RESTORE_BYTES) }
                payloadKey = splitter.key
            }

            // 1. Verify the candidate in isolation (schema version, full audit chain) with throwaway anchors.
            val candidateHelper = openHelper(RESTORE_CANDIDATE, payloadKey.toByteArray(Charsets.US_ASCII))
            epoch = try {
                val candidate = SabouCore.open(AndroidSqlDatabase(candidateHelper.writableDatabase), InMemoryAnchorStore())
                val verdict = candidate.verifyStartup()
                check(verdict == StartupVerdict.Healthy) { "BACKUP_INTEGRITY:${(verdict as StartupVerdict.RollbackDetected).detail}" }
                candidate.epoch
            } finally {
                candidateHelper.close()
            }

            // 2. Copy into a new database encrypted with the device key. When the app could not even open
            //    (no signed-in core, e.g. the Keystore key was lost), the old key is replaced by a new one.
            val passphrase = try {
                keys.databasePassphrase()
            } catch (e: DeviceKeyUnavailableException) {
                // Replace the key only when it is provably gone (not after a passing Keystore hiccup).
                if (current != null || !DeviceKeys.isPermanentlyLost(e)) throw e
                keys.forgetDatabaseKey()
                keys.databasePassphrase()
            }
            val stagedHelper = openHelper(RESTORE_STAGED, passphrase)
            try {
                val db = stagedHelper.writableDatabase
                db.execSQL("ATTACH DATABASE ? AS candidate KEY '$payloadKey'", arrayOf<Any?>(plain.absolutePath))
                try {
                    db.query("SELECT sqlcipher_export('main', 'candidate')").use { it.moveToFirst() }
                } finally {
                    db.execSQL("DETACH DATABASE candidate")
                }
            } finally {
                stagedHelper.close()
            }
        } catch (e: Throwable) {
            // Nothing of the live database has been touched yet.
            context.deleteDatabase(RESTORE_STAGED)
            throw e
        } finally {
            context.deleteDatabase(RESTORE_CANDIDATE)
        }

        // 3. Announce (authorized and audited by the core when signed in), then swap. From here on the
        //    app always re-opens, whatever happens.
        try {
            if (current != null) current.acceptReplacement(epoch, "RESTORE") else anchorsRebase(epoch, "RESTORE")
        } catch (e: Throwable) {
            context.deleteDatabase(RESTORE_STAGED)
            throw e
        }
        clearDrafts()   // drafts belong to the replaced database
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
        clearDrafts()
        try {
            close()
            context.deleteDatabase(DB_NAME)
            keys.forgetDatabaseKey()   // the new database gets a new key (also recovers from a lost Keystore key)
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
        private const val BACKUP_SEALED = "backup-sealed.db"
        private val PAYLOAD_MAGIC = "SABOUDB2".toByteArray(Charsets.US_ASCII)
        private const val KEY_CHARS = 64
        private const val RESTORE_CANDIDATE = "restore-plain.db"
        private const val RESTORE_STAGED = "sabou-restore.db"
        private const val MAX_RESTORE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
