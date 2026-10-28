package ir.sabou.core

import ir.sabou.kernel.Clock
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SABOU-AUDIT-001 (second audit): a command from the second phone arrives as a deserialized object, so whatever it
 * stores in a field is the sender's to choose. What decides access — the permission it needs, extra permissions, being
 * shared catalog — must be computed by the main phone's own class (a getter), never read from a stored field.
 */
class CommandIntegrityTest {
    private val guarded = setOf("requiredPermission", "additionalPermissions", "sharedCatalog")

    @Test fun noCommandStoresWhatDecidesItsAccess() {
        DevDatabases.memory().use { db ->
            val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
            val types = core.sync.dispatcher.commandTypes
            assertTrue(types.size > 150, "found ${types.size} command types")
            val offenders = types.flatMap { type ->
                generateSequence(type as Class<*>?) { it.superclass }.takeWhile { it != Any::class.java }
                    .flatMap { c -> c.declaredFields.asSequence() }
                    .filter { f -> f.name in guarded }
                    .map { f -> "${type.simpleName}.${f.name}" }
            }
            assertEquals(emptyList(), offenders, "commands that store an access decision in a field")
        }
    }

    @Test fun anAttachmentsHashIsTheHashOfItsBytesAfterTravelling() {
        val input = AttachmentInput("a.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(input) } }.toByteArray()
        val back = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as AttachmentInput
        assertEquals(input.sha256, back.sha256)
        assertTrue(AttachmentInput::class.java.declaredFields.none { it.name == "sha256" }, "the hash is computed, not carried")
    }
}
