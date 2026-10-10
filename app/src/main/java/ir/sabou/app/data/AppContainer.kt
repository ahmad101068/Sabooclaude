package ir.sabou.app.data

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import ir.sabou.backup.BackupFormatException
import ir.sabou.backup.StreamingBackupCodec
import ir.sabou.core.SabouCore
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.platform.IntegrityGuard
import ir.sabou.platform.StartupVerdict
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.platform.memory.InMemoryAuditStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

sealed interface AppState {
    data object Opening : AppState
    data class Ready(val core: SabouCore) : AppState
    /**
     * The database opens but does not continue the anchored history, or its audit chain failed. Its [core] is
     * available only so that an owner can sign in and authorize a restore or reset; no business screen uses it.
     */
    data class Recovery(val detail: String, val core: SabouCore) : AppState
    /**
     * The database could not be opened. [permanent]: the key is provably lost or the file is not a readable
     * database — only then may data be replaced without signing in. Otherwise the failure may pass (retry).
     */
    data class Failed(val detail: String, val permanent: Boolean) : AppState
}

/**
 * Owns the encrypted database and the single SabouCore. All methods block: call them off the main thread.
 *
 * Replacing the database (restore, factory reset) follows one crash-safe protocol:
 * 1. authorize, announce the new epoch as a PENDING rebase (old and new database both accepted) and seal the
 *    current core, so no write can start or be in progress;
 * 2. keep the current database in quarantine (with its wrapped key) — data is never deleted;
 * 3. switch files with an atomic rename (restore) or move the old file away (reset);
 * 4. open; the first healthy startup records a checkpoint that settles which database is genuine.
 * A crash at any point leaves either the old or the new database, and either one opens normally.
 */
class AppContainer(private val context: Context) {
    private val keys = DeviceKeys(context)
    private val anchors = FileAnchorStore(File(context.noBackupFilesDir, "integrity.anchors"), keys)
    private val quarantine = File(context.noBackupFilesDir, QUARANTINE_DIR)
    private var helper: SupportSQLiteOpenHelper? = null

    private val mutableState = MutableStateFlow<AppState>(AppState.Opening)
    val state: StateFlow<AppState> = mutableState

    /** Test hook: called after each step of a replacement; a device test throws here to simulate a crash. */
    @Volatile internal var faultPoint: (String) -> Unit = {}

    init {
        System.loadLibrary("sqlcipher")
    }

    /** Opens once per process; later calls (e.g. after an activity re-creation) do nothing. */
    @Synchronized
    fun ensureOpen() {
        if (helper == null && state.value is AppState.Opening) {
            // A crash in the middle of a backup or restore must not leave a copy behind. The live database is
            // never among these: an unfinished restore simply keeps the database that is still in place.
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
            // A brand-new database takes the epoch announced by an unfinished reset (crash after the old file
            // was moved away), so the interrupted reset completes instead of looking like a swap.
            val epoch = newEpoch ?: runCatching { IntegrityGuard(anchors, InMemoryAuditStore()).pendingEpoch() }.getOrNull()
            val db = openHelper(DB_NAME, keys.databasePassphrase()).also { helper = it }.writableDatabase
            val core = SabouCore.open(AndroidSqlDatabase(db), anchors, Clock.SYSTEM, newDatabaseEpoch = epoch)
            when (val verdict = runCatching { core.verifyStartup() }.getOrElse { StartupVerdict.RollbackDetected(it.message ?: "ANCHOR") }) {
                StartupVerdict.Healthy -> AppState.Ready(core).also { verifyInBackgroundIfDue(core) }
                is StartupVerdict.RollbackDetected -> AppState.Recovery(verdict.detail, core)
            }
        } catch (e: Throwable) {
            AppState.Failed(e.message ?: e::class.java.simpleName, permanent = isPermanentOpenFailure(e))
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
        verifying = true
        kotlin.concurrent.thread(name = "sabou-audit-verify", isDaemon = true) {
            try {
                // Even "is it due" reads the database: never on the main thread (a backup may hold it).
                if (!runCatching { core.backgroundVerificationDue() }.getOrDefault(false)) return@thread
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

    /** Drafts are only restored by the same app version: saved classes and form layouts may change. */
    private val appVersion: Long by lazy {
        runCatching {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        }.getOrDefault(-1L)
    }

    /** One background thread: saves, clears and reads happen in the order they were asked for. */
    private val draftWriter = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Encodes the draft now (on the caller's thread, the main thread, so it is one consistent moment of the
     * form) and encrypts and writes it in the background.
     */
    fun saveDraftsLater(draft: android.os.Bundle) {
        val bytes = runCatching {
            draft.putLong(DRAFT_VERSION, appVersion)
            ir.sabou.app.ui.Drafts.marshall(draft)
        }.getOrNull()
        draftWriter.execute { if (bytes == null) draftsFile.delete() else writeDraft(bytes) }
    }

    private fun writeDraft(bytes: ByteArray) {
        runCatching {
            val tmp = File(draftsFile.path + ".tmp")
            java.io.FileOutputStream(tmp).use { out -> out.write(keys.seal(bytes)); out.fd.sync() }
            check(tmp.renameTo(draftsFile))
        }.onFailure { draftsFile.delete() }   // best effort: a draft is a convenience, never a requirement
    }

    /** Test hook and synchronous variant of [saveDraftsLater]. */
    internal fun saveDrafts(draft: android.os.Bundle) {
        saveDraftsLater(draft)
        draftWriter.submit {}.get()
    }

    /**
     * Reads and removes the stored draft (after any save still queued). Anything unreadable, or written by
     * another app version, is dropped.
     */
    fun takeDrafts(): android.os.Bundle? = draftWriter.submit(java.util.concurrent.Callable {
        if (!draftsFile.exists()) return@Callable null
        try {
            ir.sabou.app.ui.Drafts.unmarshall(keys.open(draftsFile.readBytes()), context.classLoader)
                .takeIf { it.getLong(DRAFT_VERSION, Long.MIN_VALUE) == appVersion }
        } catch (e: Throwable) {
            null
        } finally {
            draftsFile.delete()
        }
    }).get()

    /** Removes the draft after anything queued (restore, reset: it belongs to the replaced database). */
    fun clearDrafts() { draftWriter.submit { draftsFile.delete() }.get() }
    fun clearDraftsLater() { draftWriter.execute { draftsFile.delete() } }

    /** Internal for the device tests (they swap database files while the app is closed). */
    @Synchronized
    internal fun close() {
        helper?.close()
        helper = null
    }

    /** The core of the current database, whether healthy or in recovery. */
    private fun currentCore(): SabouCore? = when (val s = state.value) {
        is AppState.Ready -> s.core
        is AppState.Recovery -> s.core
        else -> null
    }

    /**
     * Who may replace the database. With a readable database the core decides (signed-in user with the
     * permission, or a database without users yet). Without one, only a provably unreadable database may be
     * replaced without signing in; a failure that may pass must be retried, never answered by erasing.
     */
    private fun announceReplacement(newEpoch: String, reason: String) {
        val core = currentCore()
        if (core != null) {
            core.acceptReplacement(newEpoch, reason)
            return
        }
        val failed = state.value as? AppState.Failed
        if (failed == null || !failed.permanent) throw DomainException(DomainError.InvalidState("DATABASE", "NOT_REPLACEABLE_NOW"))
        IntegrityGuard(anchors, InMemoryAuditStore()).recordRebase(newEpoch, "", reason, System.currentTimeMillis())
    }

    /**
     * Writes an encrypted, password-protected backup (format 4) of the whole database to [target].
     * The core checks the signed-in user's permission, verifies the full audit chain and audits it first; the
     * export then runs with every write held back, so the copy is one consistent moment of the books.
     *
     * No plaintext copy ever touches the disk: the database is exported into a temporary SQLCipher file
     * under a fresh random key, and the backup carries that key and file inside its password encryption
     * (payload: MAGIC + key + encrypted database).
     */
    @Synchronized
    fun backup(core: SabouCore, password: CharArray, target: Uri) {
        core.authorizeBackup()
        val db = checkNotNull(helper) { "DATABASE_CLOSED" }.writableDatabase
        val sealed = File(context.cacheDir, BACKUP_SEALED).also { it.delete() }
        val exportKey = randomHexKey()
        try {
            core.exclusive {
                db.execSQL("ATTACH DATABASE ? AS export KEY '$exportKey'", arrayOf<Any?>(sealed.absolutePath))
                try {
                    db.query("SELECT sqlcipher_export('export')").use { it.moveToFirst() }
                } finally {
                    db.execSQL("DETACH DATABASE export")
                }
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
     * Writes the decrypted payload (MAGIC + key + SQLCipher file) to [file] and keeps the key. Anything else is
     * refused: the old plain-SQLite payload (v1) would have to be written to disk unencrypted, and this product
     * has no backups of that kind to restore.
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
            if (bytes.size < HEADER || !bytes.copyOfRange(0, PAYLOAD_MAGIC.size).contentEquals(PAYLOAD_MAGIC)) {
                throw BackupFormatException("unsupported_payload")
            }
            key = String(bytes, PAYLOAD_MAGIC.size, KEY_CHARS, Charsets.US_ASCII)
            if (!key.all { it in '0'..'9' || it in 'a'..'f' }) throw BackupFormatException("bad_backup_key")
            file.write(bytes, HEADER, bytes.size - HEADER)
        }
        override fun flush() = file.flush()
        override fun close() {
            try {
                if (!decided) decide()
            } finally {
                file.close()
            }
        }
        companion object { const val HEADER = 8 + KEY_CHARS }
    }

    /**
     * Restores a backup: decrypt → verify the candidate on its own (schema, full audit chain) → copy it into a
     * new database encrypted with this device's key → announce + seal → quarantine the current database →
     * atomic rename → open. Nothing of the current database is touched until the candidate has passed every
     * check, and the current database is kept in quarantine afterwards.
     */
    @Synchronized
    fun restore(password: CharArray, source: Uri) {
        context.deleteDatabase(RESTORE_CANDIDATE)
        context.deleteDatabase(RESTORE_STAGED)
        val candidateFile = context.getDatabasePath(RESTORE_CANDIDATE).also { it.parentFile?.mkdirs() }
        val staged = context.getDatabasePath(RESTORE_STAGED)
        val epoch: String
        var keyReplaced = false
        try {
            var payloadKey = ""
            val input = context.contentResolver.openInputStream(source) ?: error("BACKUP_SOURCE_UNAVAILABLE")
            // The candidate stays encrypted on disk under the backup's own key.
            input.use { i ->
                val splitter = PayloadSplitter(candidateFile.outputStream())
                splitter.use { o -> StreamingBackupCodec.decrypt(password, i, o, MAX_RESTORE_BYTES) }
                payloadKey = splitter.key
            }

            // 1. Verify the candidate in isolation: schema version and the whole audit chain.
            val candidateHelper = openHelper(RESTORE_CANDIDATE, payloadKey.toByteArray(Charsets.US_ASCII))
            epoch = try {
                val candidate = SabouCore.open(AndroidSqlDatabase(candidateHelper.writableDatabase), InMemoryAnchorStore())
                val verdict = candidate.verifyAuditFull()
                check(verdict == StartupVerdict.Healthy) { "BACKUP_INTEGRITY:${(verdict as StartupVerdict.RollbackDetected).detail}" }
                candidate.epoch
            } finally {
                candidateHelper.close()
            }

            // 2. Copy into a new database encrypted with the device key. A device whose key is provably lost
            //    (the only case in which the database could not be opened at all) gets a new key.
            val passphrase = try {
                keys.databasePassphrase()
            } catch (e: DeviceKeyUnavailableException) {
                if (currentCore() != null || !DeviceKeys.isPermanentlyLost(e)) throw e
                keys.forgetDatabaseKey()   // the unreadable file itself is still quarantined in step 4
                keyReplaced = true
                keys.databasePassphrase()
            }
            val stagedHelper = openHelper(RESTORE_STAGED, passphrase)
            try {
                val db = stagedHelper.writableDatabase
                db.execSQL("ATTACH DATABASE ? AS candidate KEY '$payloadKey'", arrayOf<Any?>(candidateFile.absolutePath))
                try {
                    db.query("SELECT sqlcipher_export('main', 'candidate')").use { it.moveToFirst() }
                } finally {
                    db.execSQL("DETACH DATABASE candidate")
                }
            } finally {
                stagedHelper.close()
            }
        } catch (e: Throwable) {
            context.deleteDatabase(RESTORE_STAGED)   // nothing of the live database has been touched
            throw e
        } finally {
            context.deleteDatabase(RESTORE_CANDIDATE)
        }

        // 3. Announce and seal (authorized and audited by the core). Refused → nothing happened.
        try {
            announceReplacement(epoch, "RESTORE")
        } catch (e: Throwable) {
            context.deleteDatabase(RESTORE_STAGED)
            throw e
        }
        // 4. From here on the app always re-opens, and whichever file is in place opens normally.
        try {
            faultPoint("announced")
            clearDrafts()   // drafts belong to the replaced database
            close()
            keepInQuarantine("RESTORE", move = false, withKey = !keyReplaced)
            faultPoint("quarantined")
            // A leftover journal of the old file must not be applied to the new one (the file was closed cleanly).
            listOf("-journal", "-wal", "-shm").forEach { File(context.getDatabasePath(DB_NAME).path + it).delete() }
            check(staged.renameTo(context.getDatabasePath(DB_NAME))) { "RESTORE_SWAP_FAILED" }
            faultPoint("swapped")
        } finally {
            open()
        }
    }

    /**
     * Starts over with an empty database. The current database is moved to quarantine with its key — never
     * deleted — and the new database's epoch is announced first, so the next start is healthy even if the
     * process dies half-way.
     */
    @Synchronized
    fun factoryReset() {
        val epoch = SabouCore.newEpoch()
        announceReplacement(epoch, "FACTORY_RESET")
        try {
            faultPoint("announced")
            clearDrafts()
            close()
            keepInQuarantine("FACTORY_RESET", move = true)
            faultPoint("quarantined")
            keys.forgetDatabaseKey()   // the new database gets a new key (also recovers from a lost Keystore key)
            faultPoint("keyForgotten")
        } finally {
            open(newEpoch = epoch)
        }
    }

    /**
     * Keeps the current database file (and its still-wrapped key) under no-backup storage. [move] renames the
     * file away (reset: the live name must become free); otherwise it is copied (restore: the live file is
     * replaced atomically afterwards). Only the newest [QUARANTINE_KEEP] entries are kept.
     */
    private fun keepInQuarantine(reason: String, move: Boolean, withKey: Boolean = true) {
        val live = context.getDatabasePath(DB_NAME)
        if (!live.exists()) return
        val dir = File(quarantine, "${System.currentTimeMillis()}-$reason").also { check(it.mkdirs() || it.isDirectory) { "QUARANTINE_UNAVAILABLE" } }
        val target = File(dir, DB_NAME)
        if (move) {
            check(live.renameTo(target)) { "QUARANTINE_MOVE_FAILED" }
        } else {
            val tmp = File(dir, "$DB_NAME.tmp")
            live.inputStream().use { i -> java.io.FileOutputStream(tmp).use { o -> i.copyTo(o); o.fd.sync() } }
            check(tmp.renameTo(target)) { "QUARANTINE_COPY_FAILED" }
        }
        if (withKey) keys.wrappedDatabaseKey()?.let { File(dir, "key.wrapped").writeText(it) }
        File(dir, "reason.txt").writeText(reason)
        quarantine.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(QUARANTINE_KEEP)?.forEach { it.deleteRecursively() }
    }

    /** Quarantined databases, newest first (for support and the device tests). */
    internal fun quarantined(): List<File> =
        quarantine.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()

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
        private const val BACKUP_SEALED = "backup-sealed.db"
        private val PAYLOAD_MAGIC = "SABOUDB2".toByteArray(Charsets.US_ASCII)
        private const val KEY_CHARS = 64
        private const val DRAFT_VERSION = "app_version"
        private const val RESTORE_CANDIDATE = "restore-candidate.db"
        private const val RESTORE_STAGED = "sabou-restore.db"
        private const val MAX_RESTORE_BYTES = 2L * 1024 * 1024 * 1024
        private const val QUARANTINE_DIR = "quarantine"
        private const val QUARANTINE_KEEP = 3

        /**
         * A failure that will not pass by retrying: the Keystore key is gone, or the file is not a database this
         * key can read. Anything else (a busy file, a full disk, a passing Keystore error) may pass.
         */
        internal fun isPermanentOpenFailure(e: Throwable): Boolean = DeviceKeys.isPermanentlyLost(e) ||
            generateSequence(e) { it.cause }.any {
                // By name: SQLCipher ships its own copies of the framework's SQLite exception classes.
                it.javaClass.simpleName in PERMANENT_SQLITE_ERRORS ||
                    it.message?.contains("file is not a database", ignoreCase = true) == true
            }

        private val PERMANENT_SQLITE_ERRORS = setOf("SQLiteDatabaseCorruptException", "SQLiteNotADatabaseException")
    }
}
