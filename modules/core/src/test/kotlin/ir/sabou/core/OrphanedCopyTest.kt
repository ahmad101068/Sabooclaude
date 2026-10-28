package ir.sabou.core

import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent audit (ChatGPT, 1405/08/06), finding 001: a second phone's copy whose pairing file was lost must not
 * become a second set of books. The database itself says it is a copy; such a copy is locked against every command.
 */
class OrphanedCopyTest {
    private val day = Fa.fromJalali(1405, 8, 6)
    private val dir = Files.createTempDirectory("sabou-orphan")
    private val db = DevDatabases.open(dir.resolve("o.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { day.epochDay * 86_400_000L + 9 * 3_600_000L }, emptyList(), null)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun aCopyWithoutItsPairingRecordsNothing() {
        core.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val cash = core.overview.treasury().single().account.id
        assertFalse(core.sync.isCopy(), "a phone's own books are not a copy")
        core.sync.startAt(core.sync.head())                         // what installing a copy from the main phone leaves
        assertTrue(core.sync.isCopy())
        core.sync.lockOrphanedCopy()
        val journals = core.overview.trialBalance()
        val e = assertFailsWith<DomainException> {
            core.treasury.receipt(RecordReceipt(GlobalId.new(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, Money.of(1_000_000), day, "آورده"))
        }
        assertEquals("INVALID_STATE:DEVICE:PAIRING_LOST", e.error.code)
        assertEquals(journals, core.overview.trialBalance())
        assertTrue(core.overview.treasury().isNotEmpty())           // what it holds can still be read
    }
}
