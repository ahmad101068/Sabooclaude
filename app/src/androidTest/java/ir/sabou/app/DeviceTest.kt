package ir.sabou.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.sabou.app.data.AppContainer
import ir.sabou.app.data.AppState
import ir.sabou.app.data.DeviceKeys
import ir.sabou.app.ui.Drafts
import ir.sabou.backup.BackupFormatException
import ir.sabou.core.SabouCore
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a real Android system (emulator in CI): SQLCipher, the Keystore, the signed anchors,
 * backup/restore and the encrypted drafts — everything the JVM tests cannot reach.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A container on a freshly erased database, signed in as a new owner. */
    private fun fresh(): Pair<AppContainer, SabouCore> {
        val container = AppContainer(context)
        container.factoryReset(null)
        val core = ready(container)
        assertTrue(core.identity.needsBootstrap())
        core.bootstrap("شعبه آزمایشی", "مالک", "owner", "123456".toCharArray())
        return container to core
    }

    private fun ready(container: AppContainer): SabouCore {
        val state = container.state.value
        if (state !is AppState.Ready) fail("expected Ready, was $state")
        return (state as AppState.Ready).core
    }

    @Test fun encryptedDatabaseAndKeystoreKeySurviveAReopen() {
        val (container, _) = fresh()
        container.open()                                   // close + open: the wrapped key is unwrapped again
        val core = ready(container)
        assertFalse(core.identity.needsBootstrap())
        core.identity.login("owner", "123456".toCharArray())
        // The file on disk is not a plain SQLite database.
        val header = ByteArray(16).also { b -> context.getDatabasePath(AppContainer.DB_NAME).inputStream().use { it.read(b) } }
        assertFalse(String(header, Charsets.US_ASCII).startsWith("SQLite format 3"))
    }

    @Test fun backupAndRestoreRoundTripWithoutPlaintextOnDisk() {
        val (container, core) = fresh()
        val file = File(context.cacheDir, "test-backup.sabou").also { it.delete() }
        container.backup(core, "password-123".toCharArray(), Uri.fromFile(file))
        assertTrue(file.length() > 0)
        // Nothing unencrypted or temporary is left behind.
        assertFalse(File(context.cacheDir, "backup-plain.db").exists())
        assertFalse(File(context.cacheDir, "backup-sealed.db").exists())

        container.factoryReset(core)
        assertTrue(ready(container).identity.needsBootstrap())

        try {
            container.restore(null, "wrong-password".toCharArray(), Uri.fromFile(file))
            fail("a wrong password must be refused")
        } catch (e: BackupFormatException) {
            assertEquals("authentication_failed", e.message)
        }
        assertTrue(ready(container).identity.needsBootstrap())   // untouched by the failed attempt

        container.restore(null, "password-123".toCharArray(), Uri.fromFile(file))
        val restored = ready(container)
        assertFalse(restored.identity.needsBootstrap())
        restored.identity.login("owner", "123456".toCharArray())
        assertFalse(context.getDatabasePath("restore-plain.db").exists())
        file.delete()
    }

    @Test fun anOlderCopyOfTheDatabaseIsDetected() {
        val (container, _) = fresh()
        val live = context.getDatabasePath(AppContainer.DB_NAME)
        container.close()
        val old = live.readBytes()
        container.open()
        val again = ready(container)
        again.identity.login("owner", "123456".toCharArray())
        // An audited action moves the history (and the signed checkpoint) forward.
        container.backup(again, "password-123".toCharArray(), Uri.fromFile(File(context.cacheDir, "x.sabou")))
        container.open()
        ready(container)
        container.close()
        live.writeBytes(old)                               // roll the database back behind the app's back
        container.open()
        assertTrue(container.state.value is AppState.Recovery)
        container.factoryReset(null)                       // the way out (besides restoring a backup)
        assertTrue(ready(container).identity.needsBootstrap())
        File(context.cacheDir, "x.sabou").delete()
    }

    @Test fun formDraftsRoundTripEncryptedThroughAParcel() {
        val keys = DeviceKeys(context)
        val id = GlobalId.new()
        val registry = SaveableStateRegistry(null, Drafts::canBeSaved)
        registry.registerProvider("money") { mutableStateOf<Money?>(Money.of(1_200_000)) }
        registry.registerProvider("row") { arrayListOf(id, BusinessDate(20_000), Quantity.of(1_500_000), null) }
        registry.registerProvider("text") { mutableStateOf("پیش‌نویس") }

        val sealed = keys.seal(Drafts.marshall(Drafts.toBundle(registry.performSave())))
        val values = Drafts.fromBundle(Drafts.unmarshall(keys.open(sealed), context.classLoader))
        val restored = SaveableStateRegistry(values, Drafts::canBeSaved)

        assertEquals(Money.of(1_200_000), (restored.consumeRestored("money") as MutableState<*>).value)
        assertEquals(listOf(id, BusinessDate(20_000), Quantity.of(1_500_000), null), restored.consumeRestored("row"))
        assertEquals("پیش‌نویس", (restored.consumeRestored("text") as MutableState<*>).value)
        // Lambdas are never accepted as saved state.
        assertFalse(Drafts.canBeSaved(mutableStateOf<() -> Unit>({})))
    }

    @Test fun storedDraftsAreReadOnceAndATamperedFileIsDropped() {
        val container = AppContainer(context)
        val bundle = android.os.Bundle().apply { putString("user", "u"); putLong("at", 1L) }
        container.saveDrafts(bundle)
        assertEquals("u", container.takeDrafts()?.getString("user"))
        assertNull(container.takeDrafts())                 // consumed

        container.saveDrafts(bundle)
        val file = File(context.noBackupFilesDir, "drafts.bin")
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertNull(container.takeDrafts())
        assertFalse(file.exists())
    }
}
