package ir.sabou.platform

import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure

/**
 * A state-changing request. Commands are plain data: they carry their own id (for idempotency),
 * the permission they need and the scope they touch. Handlers never check these themselves.
 */
interface Command {
    val commandId: GlobalId
    val requiredPermission: Permission
    val scope: Scope

    /** Canonical content used to detect a retried command with different data. */
    fun fingerprint(): String
}

/** Everything a handler may use. Created only by [CommandBus]. */
class CommandContext internal constructor(
    val actor: Actor,
    val module: ModuleId,
    val commandId: GlobalId,
    val scope: Scope,
    val nowEpochMillis: Long,
    private val audit: AuditTrail,
    private val events: EventLog,
    private val epochProvider: () -> String,
) {
    fun audit(draft: AuditDraft) {
        audit.append(draft, actor, module, scopeLabel(scope), commandId.value, nowEpochMillis, epochProvider())
    }

    fun emit(type: String, payload: Map<String, String>) {
        events.append(
            DomainEvent(
                eventId = GlobalId.new().value, commandId = commandId.value, module = module, type = type,
                scope = scopeLabel(scope), occurredAtEpochMillis = nowEpochMillis, payload = payload,
            ),
        )
    }

    /** Re-checks scope for records a command touches besides its declared scope (e.g. both transfer ends). */
    fun requireScope(other: Scope) {
        ensure(actor.canAccess(other)) { DomainError.ScopeDenied(scopeLabel(other)) }
    }

    companion object {
        fun scopeLabel(scope: Scope): String = when (scope) {
            Scope.Organization -> "ORG"
            is Scope.Branch -> "BRANCH:${scope.branchId.value.value}"
        }
    }
}

/**
 * The only way to change business data. The order of checks is fixed and cannot be skipped:
 * authenticate → permission → scope → (transaction: idempotency → handler → audit → events).
 * This replaces the opt-in checks of the previous code base (AUD-004, AUD-012).
 */
class CommandBus(
    private val session: SessionPort,
    private val unitOfWork: UnitOfWork,
    private val idempotency: IdempotencyStore,
    auditStore: AuditStore,
    private val events: EventLog,
    private val clock: Clock,
    private val epochProvider: () -> String,
) {
    private val audit = AuditTrail(auditStore)

    fun <C : Command> execute(module: ModuleId, command: C, handler: (C, CommandContext) -> GlobalId): CommandOutcome {
        val actor = session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        ensure(actor.role.allows(command.requiredPermission)) { DomainError.PermissionDenied(command.requiredPermission.name) }
        ensure(actor.canAccess(command.scope)) { DomainError.ScopeDenied(CommandContext.scopeLabel(command.scope)) }
        val type = command::class.qualifiedName ?: command::class.java.name
        val fingerprint = AuditHashing.sha256(type + "\u001F" + command.fingerprint())
        return unitOfWork.transaction {
            idempotency.find(command.commandId.value)?.let { previous ->
                ensure(previous.commandType == type && previous.fingerprint == fingerprint) {
                    DomainError.IdempotencyConflict(command.commandId.value)
                }
                return@transaction CommandOutcome(GlobalId.parse(previous.resultId), replayed = true)
            }
            val now = clock.nowEpochMillis()
            val context = CommandContext(actor, module, command.commandId, command.scope, now, audit, events, epochProvider)
            val result = handler(command, context)
            idempotency.save(IdempotencyRecord(command.commandId.value, type, fingerprint, result.value, now))
            CommandOutcome(result, replayed = false)
        }
    }
}

data class CommandOutcome(val resultId: GlobalId, val replayed: Boolean)
