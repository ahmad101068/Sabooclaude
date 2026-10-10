package ir.sabou.platform

import ir.sabou.kernel.BusinessDate
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

    /** Extra permissions this particular command needs (e.g. paying while recording an invoice). */
    val additionalPermissions: Set<Permission> get() = emptySet()

    /**
     * True for shared reference data (items, suppliers, menu, recipes) that every branch uses. Such
     * commands live in the organization scope but need only their permission, not ORGANIZATION_DATA.
     */
    val sharedCatalog: Boolean get() = false

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
    private val numbers: DocumentNumberStore,
    private val epochProvider: () -> String,
) {
    private val issuedSeries = HashSet<DocumentSeries>()
    internal val issued: Set<DocumentSeries> get() = issuedSeries

    /**
     * Issues the next number of [series] for [documentId], dated [date] (its fiscal year picks the series year),
     * in [scope] (the command's scope by default; ignored for organization-wide series). [reverses] names the
     * document a reversal undoes. Runs inside the command's transaction, so a failed command consumes no number.
     */
    fun number(
        series: DocumentSeries,
        date: BusinessDate,
        documentId: GlobalId,
        scope: Scope = this.scope,
        reverses: Pair<DocumentSeries, GlobalId>? = null,
    ): DocumentNumber {
        numbers.of(series, documentId)?.let { issuedSeries += series; return it.number }
        val label = if (series.perBranch) scopeLabel(scope) else "ORG"
        val year = FiscalYear.of(date)
        val number = DocumentNumber(series, year, label, numbers.next(series, year, label))
        val original = reverses?.let { (s, id) -> numbers.of(s, id)?.number }
        numbers.record(NumberedDocument(number, documentId, date, commandId.value, original))
        issuedSeries += series
        audit(AuditDraft("DOCUMENT_NUMBER", series.name, documentId.value, number.text + (original?.let { ";reverses=${it.text}" } ?: "")))
        return number
    }

    /** The number already issued to [documentId] in [series], if any. */
    fun numberOf(series: DocumentSeries, documentId: GlobalId): DocumentNumber? = numbers.of(series, documentId)?.number

    internal var audited = false
        private set

    fun audit(draft: AuditDraft) {
        audit.append(draft, actor, module, scopeLabel(scope), commandId.value, nowEpochMillis, epochProvider())
        audited = true
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
    private val numbers: DocumentNumberStore,
    private val epochProvider: () -> String,
) {
    private val audit = AuditTrail(auditStore)

    fun <C : Command> execute(module: ModuleId, command: C, handler: (C, CommandContext) -> GlobalId): CommandOutcome {
        val actor = session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        (setOf(command.requiredPermission) + command.additionalPermissions).forEach { p ->
            ensure(actor.role.allows(p)) { DomainError.PermissionDenied(p.name) }
        }
        val catalog = command.sharedCatalog && command.scope == Scope.Organization
        ensure(catalog || actor.canAccess(command.scope)) { DomainError.ScopeDenied(CommandContext.scopeLabel(command.scope)) }
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
            val context = CommandContext(actor, module, command.commandId, command.scope, now, audit, events, numbers, epochProvider)
            val result = handler(command, context)
            // A command that issues a document must have numbered it (the whole command rolls back otherwise).
            command::class.java.getAnnotation(IssuesDocument::class.java)?.let { declared ->
                ensure(declared.series in context.issued) { DomainError.IntegrityViolation("DOCUMENT_NOT_NUMBERED:${declared.series}:$type") }
            }
            // Every successful command leaves at least one audit record, even if a handler wrote none itself.
            if (!context.audited) context.audit(AuditDraft("COMMAND", type.substringAfterLast('.'), result.value, ""))
            idempotency.save(IdempotencyRecord(command.commandId.value, type, fingerprint, result.value, now))
            CommandOutcome(result, replayed = false)
        }
    }
}

data class CommandOutcome(val resultId: GlobalId, val replayed: Boolean)
