package ir.sabou.platform

import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryBranchStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.InMemoryUserStore
import kotlin.test.Test
import kotlin.test.assertEquals
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
    private val identity = IdentityService(users, branches, session, uow, InMemoryAuditStore(), clock) { "e1" }
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
        assertEquals("INVALID_STATE:USER:LOCKED", code { identity.login("owner", "123456".toCharArray()) })
        now += 31_000
        identity.login("owner", "123456".toCharArray())
        now += 31 * 60_000
        assertNull(session.currentActor())
    }
}
