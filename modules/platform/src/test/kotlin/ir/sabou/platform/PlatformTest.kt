package ir.sabou.platform

import ir.sabou.kernel.BranchId
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryEventLog
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import ir.sabou.platform.memory.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private data class Ping(
    override val commandId: GlobalId,
    override val scope: Scope,
    val text: String,
    override val requiredPermission: Permission = Permission.SALES_RECORD,
) : Command {
    override fun fingerprint() = text
}

class PlatformTest {
    private val branchA = BranchId(GlobalId.new())
    private val branchB = BranchId(GlobalId.new())
    private val session = MutableSession()
    private val uow = InMemoryUnitOfWork()
    private val idem = InMemoryIdempotencyStore()
    private val audit = InMemoryAuditStore()
    private val events = InMemoryEventLog()
    private val data = Table<String, String>()
    private val bus = CommandBus(session, uow, idem, audit, events, Clock { 1_000L }) { "epoch-1" }

    init { uow.register(idem, audit, events, data) }

    private fun actor(role: Role, branches: Set<BranchId> = emptySet()) = Actor(GlobalId.new(), role.name, role, branches)

    private fun ping(scope: Scope = Scope.Branch(branchA), text: String = "x", id: GlobalId = GlobalId.new()) = Ping(id, scope, text)

    private fun run(c: Ping, fail: Boolean = false) = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        data.put(cmd.commandId.value, cmd.text)
        ctx.audit(AuditDraft("PING", "PING", cmd.commandId.value, cmd.text))
        ctx.emit("PINGED", mapOf("text" to cmd.text))
        if (fail) throw DomainException(DomainError.InvalidState("PING", "FAIL"))
        cmd.commandId
    }

    private fun code(block: () -> Unit): String = assertFailsWith<DomainException> { block() }.error.code

    @Test fun unauthenticatedIsRejected() {
        assertEquals("AUTHENTICATION_REQUIRED", code { run(ping()) })
    }

    @Test fun permissionIsEnforcedByThePipeline() {
        session.actor = actor(Role.STOREKEEPER, setOf(branchA))
        assertEquals("PERMISSION_DENIED:SALES_RECORD", code { run(ping()) })
    }

    @Test fun branchScopeIsEnforcedByThePipeline() {
        session.actor = actor(Role.CASHIER, setOf(branchA))
        run(ping(Scope.Branch(branchA)))
        assertTrue(code { run(ping(Scope.Branch(branchB))) }.startsWith("SCOPE_DENIED"))
        assertTrue(code { run(ping(Scope.Organization)) }.startsWith("SCOPE_DENIED"))
    }

    @Test fun retriedCommandReturnsSameResultAndConflictingRetryIsRejected() {
        session.actor = actor(Role.OWNER)
        val c = ping(text = "a")
        val first = run(c)
        val second = run(c)
        assertEquals(first.resultId, second.resultId)
        assertTrue(second.replayed)
        assertEquals(1, audit.events.size)
        assertEquals("IDEMPOTENCY_CONFLICT", code { run(c.copy(text = "b")) })
    }

    @Test fun failedCommandLeavesNoTrace() {
        session.actor = actor(Role.OWNER)
        val c = ping()
        code { run(c, fail = true) }
        assertEquals(null, data.get(c.commandId.value))
        assertEquals(0, audit.events.size)
        assertEquals(0, events.events.size)
        assertEquals(null, idem.find(c.commandId.value))
    }

    @Test fun auditChainVerifiesInPagesAndDetectsTampering() {
        session.actor = actor(Role.OWNER)
        repeat(25) { run(ping(text = "t$it")) }
        val trail = AuditTrail(audit)
        val checkpoint = trail.verify(pageSize = 4)!!
        assertEquals(25, checkpoint.sequence)
        // Incremental verification from the checkpoint only reads new events.
        repeat(3) { run(ping(text = "n$it")) }
        assertEquals(28, trail.verify(checkpoint, pageSize = 2)!!.sequence)
        audit.events[10] = audit.events[10].copy(detail = "tampered")
        assertTrue(code { trail.verify(pageSize = 7) }.startsWith("INTEGRITY:AUDIT_EVENT_TAMPERED"))
    }

    @Test fun legitimateRebaseIsHealthyButSilentRollbackIsDetected() {
        session.actor = actor(Role.OWNER)
        val anchors = InMemoryAnchorStore()
        val guard = IntegrityGuard(anchors, audit)
        repeat(3) { run(ping()) }
        guard.recordCheckpoint("epoch-1", 1)
        assertIs<StartupVerdict.Healthy>(guard.verify("epoch-1"))

        // Factory reset / restore: the rebase is recorded first, then the database is replaced (AUD-001).
        guard.recordRebase("epoch-2", "FACTORY_RESET", 2)
        audit.events.clear()
        assertIs<StartupVerdict.Healthy>(guard.verify("epoch-2"))

        // A database swapped in without a rebase (unauthorized rollback) is still detected.
        guard.recordCheckpoint("epoch-2", 3)
        assertIs<StartupVerdict.Healthy>(guard.verify("epoch-2"))
        assertIs<StartupVerdict.RollbackDetected>(guard.verify("epoch-1"))
    }
}
