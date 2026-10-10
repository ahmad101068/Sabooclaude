package ir.sabou.platform

import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryBranchStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.InMemoryUserStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityTest {
    private var now = 1_000_000L
    private val clock = Clock { now }
    private val users = InMemoryUserStore()
    private val branches = InMemoryBranchStore()
    private val uow = InMemoryUnitOfWork().also { it.register(users, branches) }
    private val session = Session(users, clock)
    /** Device uptime: advances with [elapsed]; [boot] changes on a simulated reboot. */
    private var elapsed = 1_000L
    private var boot = "boot-1"
    private val deviceTime = object : DeviceTime {
        override fun bootId() = boot
        override fun elapsedMillis() = elapsed
    }
    private val identity = IdentityService(users, branches, session, uow, InMemoryAuditStore(), clock, deviceTime) { "e1" }
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code

    @Test fun firstUserIsOwnerAndOnlyOnce() {
        assertTrue(identity.needsBootstrap())
        identity.bootstrapOwner("owner", "مالک", "123456".toCharArray())
        assertEquals(Role.OWNER, session.currentActor()!!.role)
        assertEquals("INVALID_STATE:USERS:ALREADY_BOOTSTRAPPED", code { identity.bootstrapOwner("x", "x", "123456".toCharArray()) })
    }

    @Test fun onlyTheOwnerManagesUsersAndTheLastOwnerStays() {
        val ownerId = identity.bootstrapOwner("owner", "مالک", "123456".toCharArray())
        val branch = identity.createBranch("ونک")
        identity.createUser("cashier1", "صندوقدار", Role.CASHIER, setOf(branch), "654321".toCharArray())
        assertEquals("INVALID_STATE:USER:LAST_OWNER", code { identity.deactivateUser(ownerId) })
        identity.logout()
        identity.login("cashier1", "654321".toCharArray())
        assertEquals("PERMISSION_DENIED:USER_MANAGE", code { identity.createBranch("تجریش") })
    }

    @Test fun wrongPinsLockTheAccountAndSessionsExpire() {
        identity.bootstrapOwner("owner", "مالک", "123456".toCharArray())
        identity.logout()
        repeat(5) { code { identity.login("owner", "000000".toCharArray()) } }
        assertEquals("INVALID_STATE:USER:LOCKED:30", code { identity.login("owner", "123456".toCharArray()) })   // 30 s after the 5th
        now += 31_000; elapsed += 31_000
        identity.login("owner", "123456".toCharArray())
        now += 31 * 60_000
        assertNull(session.currentActor())
    }

    @Test fun editingAUserChangesOnlyRoleAndGrantsAndPinCanBeResetOrChanged() {
        val ownerId = identity.bootstrapOwner("owner", "مالک", "123456".toCharArray())
        val branch = identity.createBranch("ونک")
        val id = identity.createUser("ali", "علی", Role.CASHIER, setOf(branch), "111111".toCharArray())
        identity.deactivateUser(id)
        identity.updateUser(id, Role.MANAGER, setOf(branch))
        val edited = users.byId(id)!!
        assertEquals(Role.MANAGER, edited.role)
        assertFalse(edited.isActive)                              // editing never re-activates
        identity.reactivateUser(id)
        identity.resetPin(id, "222222".toCharArray())
        assertEquals("LAST_OWNER", code { identity.updateUser(ownerId, Role.MANAGER, setOf(branch)) }.substringAfterLast(':'))
        assertEquals("INVALID_INPUT:pin", code { identity.resetPin(id, "۱۲۳۴۵۶".toCharArray()) })   // UI normalizes digits first
        identity.logout()
        identity.login("ali", "222222".toCharArray())
        assertEquals("INVALID_INPUT:pin", code { identity.changeOwnPin("000000".toCharArray(), "333333".toCharArray()) })
        identity.changeOwnPin("222222".toCharArray(), "333333".toCharArray())
        identity.logout()
        identity.login("ali", "333333".toCharArray())
    }

    @Test fun guessingTheCurrentPinLocksTheAccount() {
        identity.bootstrapOwner("owner", "مالک", "123456".toCharArray())
        repeat(4) { assertEquals("INVALID_INPUT:pin", code { identity.changeOwnPin("000000".toCharArray(), "333333".toCharArray()) }) }
        assertLocked(code { identity.changeOwnPin("000000".toCharArray(), "333333".toCharArray()) })
        assertNull(session.currentActor())                                   // signed out
        assertLocked(code { identity.login("owner", "123456".toCharArray()) })
    }

    @Test fun changingTheDeviceClockDoesNotShortenALock() {
        identity.bootstrapOwner("owner", "مالک", "123456".toCharArray())
        identity.logout()
        repeat(5) { code { identity.login("owner", "000000".toCharArray()) } }
        // Clock moved a day forward: same boot, uptime unchanged → still locked.
        now += 86_400_000
        assertLocked(code { identity.login("owner", "123456".toCharArray()) })
        // Clock set back before the lock began → still locked, even after a reboot.
        now -= 2 * 86_400_000; boot = "boot-2"; elapsed = 5_000
        assertLocked(code { identity.login("owner", "123456".toCharArray()) })
        // Real time passing on the monotonic clock ends it.
        now += 86_400_000; boot = "boot-1"; elapsed = 1_000 + 31_000
        identity.login("owner", "123456".toCharArray())
    }

    @Test fun manyWrongPinsLockAStaffAccountUntilTheOwnerResetsIt() {
        identity.bootstrapOwner("owner", "مالک", "123456".toCharArray())
        val branch = identity.createBranch("ونک")
        val ali = identity.createUser("ali", "علی", Role.CASHIER, setOf(branch), "111111".toCharArray())
        identity.logout()
        repeat(IdentityService.OWNER_RESET_AFTER) {
            code { identity.login("ali", "000000".toCharArray()) }
            now += 16 * 60_000; elapsed += 16 * 60_000             // waiting out every temporary lock
        }
        assertEquals("INVALID_STATE:USER:LOCKED_UNTIL_RESET", code { identity.login("ali", "111111".toCharArray()) })
        now += 30L * 86_400_000; elapsed += 30L * 86_400_000       // no amount of time unlocks it
        assertEquals("INVALID_STATE:USER:LOCKED_UNTIL_RESET", code { identity.login("ali", "111111".toCharArray()) })
        identity.login("owner", "123456".toCharArray())
        identity.resetPin(ali, "222222".toCharArray())
        identity.logout()
        identity.login("ali", "222222".toCharArray())
    }

    @Test fun theOwnerIsNeverLockedForGood() {
        identity.bootstrapOwner("owner", "مالک", "123456".toCharArray())
        identity.logout()
        repeat(IdentityService.OWNER_RESET_AFTER + 3) {
            code { identity.login("owner", "000000".toCharArray()) }
            now += 16 * 60_000; elapsed += 16 * 60_000
        }
        identity.login("owner", "123456".toCharArray())
    }

    private fun assertLocked(code: String) = assertTrue(code.startsWith("INVALID_STATE:USER:LOCKED:"), code)
}
