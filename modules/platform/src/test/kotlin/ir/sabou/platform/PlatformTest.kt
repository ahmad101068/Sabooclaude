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
import ir.sabou.platform.memory.InMemoryDocumentNumberStore
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import ir.sabou.platform.memory.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@IssuesDocument(DocumentSeries.RECEIPT)
private data class Numbered(override val commandId: GlobalId, override val scope: Scope, val number: Boolean) : Command {
    override val requiredPermission = Permission.SALES_RECORD
    override fun fingerprint() = "$number"
}

private data class Ping(
    override val commandId: GlobalId,
    override val scope: Scope,
    val text: String,
    override val requiredPermission: Permission = Permission.SALES_RECORD,
) : Command {
    override fun fingerprint() = text
}

class PlatformTest {
    @Test fun hashesDoNotDependOnTheDeviceLocale() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fa-IR"))
            val persian = AuditHashing.sha256("سابو")
            java.util.Locale.setDefault(java.util.Locale.ROOT)
            assertEquals(AuditHashing.sha256("سابو"), persian)
            assertTrue(persian.matches(Regex("[0-9a-f]{64}")))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    private val branchA = BranchId(GlobalId.new())
    private val branchB = BranchId(GlobalId.new())
    private val session = MutableSession()
    private val uow = InMemoryUnitOfWork()
    private val idem = InMemoryIdempotencyStore()
    private val audit = InMemoryAuditStore()
    private val events = InMemoryEventLog()
    private val data = Table<String, String>()
    private val numbers = InMemoryDocumentNumberStore().also { uow.register(it) }
    private val bus = CommandBus(session, uow, idem, audit, events, Clock { 1_000L }, numbers) { "epoch-1" }

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

    @Test fun deletingTheOldestEventsIsDetectedEvenWhenPositionsAreRenumbered() {
        session.actor = actor(Role.OWNER)
        repeat(5) { run(ping(text = "t$it")) }
        val trail = AuditTrail(audit)
        val checkpoint = trail.verify()!!
        audit.events.removeAt(0); audit.events.removeAt(0)
        assertTrue(code { trail.verify() }.startsWith("INTEGRITY:AUDIT_GAP"))
        // An attacker who also renumbers positions still breaks the genesis link.
        audit.events.replaceAll { it.copy(position = audit.events.indexOf(it) + 1L) }
        assertTrue(code { trail.verify() }.startsWith("INTEGRITY:AUDIT_CHAIN_BROKEN"))
        // And an anchored checkpoint that no longer exists is reported.
        assertTrue(code { trail.verify(checkpoint) }.startsWith("INTEGRITY:AUDIT_CHECKPOINT_MISSING"))
    }

    @Test fun commandsNeedEveryPermissionTheyDeclare() {
        session.actor = actor(Role.CASHIER, setOf(branchA))
        val paying = object : Command {
            override val commandId = GlobalId.new()
            override val scope: Scope = Scope.Branch(branchA)
            override val requiredPermission = Permission.SALES_RECORD
            override val additionalPermissions = setOf(Permission.TREASURY_PAYMENT)
            override fun fingerprint() = "x"
        }
        assertEquals("PERMISSION_DENIED:TREASURY_PAYMENT", code { bus.execute(ModuleId.SALES, paying) { _, _ -> GlobalId.new() } })
    }

    @Test fun legitimateRebaseIsHealthyButSilentRollbackIsDetected() {
        session.actor = actor(Role.OWNER)
        val anchors = InMemoryAnchorStore()
        val guard = IntegrityGuard(anchors, audit)
        repeat(3) { run(ping()) }
        guard.recordCheckpoint("epoch-1", 1)
        assertIs<StartupVerdict.Healthy>(guard.verify("epoch-1"))

        // Factory reset / restore: the rebase is recorded first, then the database is replaced (AUD-001).
        guard.recordRebase("epoch-2", "epoch-1", "FACTORY_RESET", 2)
        audit.events.clear()
        assertIs<StartupVerdict.Healthy>(guard.verify("epoch-2"))

        // A database swapped in without a rebase (unauthorized rollback) is still detected.
        guard.recordCheckpoint("epoch-2", 3)
        assertIs<StartupVerdict.Healthy>(guard.verify("epoch-2"))
        assertIs<StartupVerdict.RollbackDetected>(guard.verify("epoch-1"))
    }

    @Test fun anInterruptedReplacementLeavesEitherDatabaseUsableButNoThirdOne() {
        session.actor = actor(Role.OWNER)
        val anchors = InMemoryAnchorStore()
        val guard = IntegrityGuard(anchors, audit)
        run(ping())
        guard.recordCheckpoint("old", 1)
        guard.recordRebase("new", "old", "RESTORE", 2)
        // Crash before the file swap: the old database still opens. Crash after it: the new one opens.
        assertIs<StartupVerdict.Healthy>(guard.verify("old", anchoredBefore = true))
        assertIs<StartupVerdict.Healthy>(guard.verify("new", anchoredBefore = true))
        assertIs<StartupVerdict.RollbackDetected>(guard.verify("third", anchoredBefore = true))
        assertEquals("new", guard.pendingEpoch())
        // The next startup's checkpoint settles it: only the database that actually opened stays genuine.
        audit.events.clear()
        guard.recordCheckpoint("new", 3)
        assertEquals(null, guard.pendingEpoch())
        assertIs<StartupVerdict.Healthy>(guard.verify("new", anchoredBefore = true))
        assertIs<StartupVerdict.RollbackDetected>(guard.verify("old", anchoredBefore = true))
    }

    @Test fun aMissingAnchorIsAFirstStartOnlyForADatabaseThatWasNeverAnchored() {
        val guard = IntegrityGuard(InMemoryAnchorStore(), audit)
        assertIs<StartupVerdict.Healthy>(guard.verify("e", anchoredBefore = false))
        val verdict = guard.verify("e", anchoredBefore = true)
        assertIs<StartupVerdict.RollbackDetected>(verdict)
        assertEquals("ANCHOR_MISSING", verdict.detail)
    }

    @Test fun aDocumentCommandThatForgetsItsNumberIsRolledBack() {
        session.actor = actor(Role.OWNER)
        val day = ir.sabou.kernel.BusinessDate(20_533)                        // 1405/01/01 = 2026-03-21
        fun numbered(withNumber: Boolean) = bus.execute(ModuleId.SALES, Numbered(GlobalId.new(), Scope.Branch(branchA), withNumber)) { cmd, ctx ->
            val doc = GlobalId.new()
            data.put(doc.value, "receipt")
            if (cmd.number) ctx.number(DocumentSeries.RECEIPT, day, doc)
            doc
        }
        val first = numbered(true).resultId
        assertEquals("در-1405-00001", numbers.of(DocumentSeries.RECEIPT, first)!!.number.text)
        val rows = data.values().size
        assertEquals("INTEGRITY:DOCUMENT_NOT_NUMBERED:RECEIPT:ir.sabou.platform.Numbered", code { numbered(false) })
        assertEquals(rows, data.values().size)                                  // the whole command was rolled back
        val second = numbered(true).resultId
        assertEquals(2, numbers.of(DocumentSeries.RECEIPT, second)!!.number.sequence)   // no number was lost
    }

    @Test fun everySuccessfulCommandLeavesAnAuditRecordEvenIfItsHandlerWroteNone() {
        session.actor = actor(Role.OWNER)
        val before = audit.events.size
        val outcome = bus.execute(ModuleId.SALES, ping()) { cmd, _ -> data.put(cmd.commandId.value, "silent"); cmd.commandId }
        val added = audit.events.drop(before)
        assertEquals(1, added.size)
        assertEquals("COMMAND", added.single().action)
        assertEquals(outcome.resultId.value, added.single().entityId)
    }
}
