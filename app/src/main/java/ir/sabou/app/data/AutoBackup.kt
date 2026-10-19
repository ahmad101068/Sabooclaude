package ir.sabou.app.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import ir.sabou.app.SabouApplication
import ir.sabou.kernel.GlobalId
import java.io.File

/**
 * The nightly backup (ADR-0025): an encrypted backup file written to a folder the owner picked (a memory card,
 * a synced folder, a USB drive), keeping the newest [keep]. Its password is set once and stays on this phone,
 * sealed with a Keystore key; the file still needs only that password to be restored on another phone.
 * It runs with nobody signed in, on behalf of the person who switched it on (audited as theirs).
 */
object AutoBackup {
    private const val PREFS = "auto_backup"
    private const val JOB = 4201
    private const val SEALED = "auto-backup.pw"
    const val KEEP = 7
    private const val TEST_EVERY_MS = 7 * 24 * 60 * 60_000L

    data class Status(val enabled: Boolean, val folder: Uri?, val lastSuccess: Long?, val lastError: String?, val lastFile: String?)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun sealedFile(c: Context) = File(c.noBackupFilesDir, SEALED)

    fun status(c: Context): Status {
        val p = prefs(c)
        return Status(
            enabled = p.getString("user", null) != null && sealedFile(c).exists(),
            folder = p.getString("folder", null)?.let(Uri::parse),
            lastSuccess = p.getLong("lastSuccess", 0).takeIf { it > 0 },
            lastError = p.getString("lastError", null),
            lastFile = p.getString("lastFile", null),
        )
    }

    /** Switches the nightly backup on for [userId] into [folder] (a tree the person picked) with [password]. */
    fun enable(c: Context, userId: GlobalId, folder: Uri, password: CharArray) {
        c.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val container = (c.applicationContext as SabouApplication).container
        sealedFile(c).writeBytes(container.seal(String(password).toByteArray(Charsets.UTF_8)))
        prefs(c).edit().putString("user", userId.value).putString("folder", folder.toString()).remove("lastError").apply()
        val info = JobInfo.Builder(JOB, ComponentName(c, AutoBackupJobService::class.java))
            .setPeriodic(24 * 60 * 60_000L)
            .setPersisted(true)
            .setRequiresBatteryNotLow(true)
            .build()
        c.getSystemService(JobScheduler::class.java).schedule(info)
    }

    fun disable(c: Context) = forget(c)

    fun forget(c: Context) {
        c.getSystemService(JobScheduler::class.java).cancel(JOB)
        sealedFile(c).delete()
        val folder = prefs(c).getString("folder", null)
        prefs(c).edit().clear().apply()
        folder?.let { runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
    }

    /** One backup now (the job, or «پشتیبان همین حالا»). Runs on a background thread; records the outcome. */
    fun runNow(c: Context): Result<String> = runCatching {
        val p = prefs(c)
        val user = GlobalId.parse(p.getString("user", null) ?: error("AUTO_BACKUP_OFF"))
        val folder = Uri.parse(p.getString("folder", null) ?: error("AUTO_BACKUP_OFF"))
        val container = (c.applicationContext as SabouApplication).container
        container.ensureOpen()
        val core = (container.state.value as? AppState.Ready)?.core ?: error("DATABASE_NOT_READY")
        val password = String(container.unseal(sealedFile(c).readBytes()), Charsets.UTF_8).toCharArray()
        val tree = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val name = "sabou-auto-${fileStamp()}.sabou"
        val target = DocumentsContract.createDocument(c.contentResolver, tree, "application/octet-stream", name) ?: error("BACKUP_TARGET_UNAVAILABLE")
        try {
            container.backup(core, password, target) { core.authorizeScheduledBackup(user) }
            // Once a week the file just written is opened as a restore would and its audit chain verified, and
            // the result goes into the audit trail for the scorecard (ADR-0062).
            if (System.currentTimeMillis() - p.getLong("lastTest", 0) >= TEST_EVERY_MS) {
                val test = runCatching { container.testRestore(password, target) }
                runCatching { core.recordBackupCheck(user, test.isSuccess, test.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }.orEmpty()) }
                if (test.isSuccess) p.edit().putLong("lastTest", System.currentTimeMillis()).apply()
            }
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, target) }   // never leave half a file
            throw e
        } finally {
            password.fill(' ')
        }
        prune(c, folder)
        name
    }.also { r ->
        prefs(c).edit().apply {
            r.onSuccess { putLong("lastSuccess", System.currentTimeMillis()); putString("lastFile", it); remove("lastError") }
            r.onFailure { putString("lastError", it.message ?: it::class.java.simpleName) }
        }.apply()
    }

    /** Keeps the newest [KEEP] automatic backups in the folder; manual ones are never touched. */
    private fun prune(c: Context, folder: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val autos = ArrayList<Pair<String, String>>()
        c.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1) ?: continue
                if (name.startsWith("sabou-auto-") && name.endsWith(".sabou")) autos += cur.getString(0) to name
            }
        }
        autos.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(folder, id)) }
        }
    }

    /** Sortable and readable: 2026-10-11_0315 (Gregorian, so file managers sort it right). */
    private fun fileStamp(): String = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())

    /** Days since the last successful backup (automatic or not recorded: null). */
    fun daysSinceLast(c: Context): Long? = status(c).lastSuccess?.let { (System.currentTimeMillis() - it) / 86_400_000L }
}

class AutoBackupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        kotlin.concurrent.thread(name = "sabou-auto-backup", isDaemon = false) {
            val result = AutoBackup.runNow(applicationContext)
            // Database busy or folder momentarily unavailable: let the system retry with back-off.
            jobFinished(params, result.isFailure)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
