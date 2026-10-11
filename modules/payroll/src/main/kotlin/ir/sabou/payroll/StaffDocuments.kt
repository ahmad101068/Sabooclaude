package ir.sabou.payroll

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.AttachmentStore
import ir.sabou.platform.Attachments
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission

/**
 * A staff document (ADR-0039): the health card a food worker must hold, the contract, ID, insurance record, a
 * guarantee. Documents are never edited: a renewed card is a new document, the old one stays in the history. The
 * latest of each kind is the one in force.
 */
data class StaffDocument(
    val id: GlobalId,
    val scope: Scope.Branch,
    val employeeId: GlobalId,
    val kind: Kind,
    val title: String,
    val expiresOn: BusinessDate?,
    val note: String,
    val recordedAtEpochMillis: Long,
) {
    enum class Kind(val requiredForEveryone: Boolean = false) { HEALTH_CARD(true), CONTRACT(true), ID_CARD, INSURANCE, GUARANTEE, OTHER }
}

interface StaffDocumentStore {
    fun of(employeeId: GlobalId): List<StaffDocument>
    fun all(scope: Scope.Branch): List<StaffDocument>
    fun save(document: StaffDocument)
}

/** What needs attention for one employee: a required document missing, or one expired / expiring. */
data class DocumentIssue(val employeeId: GlobalId, val kind: StaffDocument.Kind, val document: StaffDocument?, val daysLeft: Long?) {
    val missing: Boolean get() = document == null
    val expired: Boolean get() = daysLeft != null && daysLeft < 0
}

object StaffDocumentRules {
    /** A document expiring within this many days is flagged (time to renew a health card). */
    const val WARN_DAYS = 30L

    /** The latest document of each kind for [employeeId]. */
    fun current(documents: List<StaffDocument>): Map<StaffDocument.Kind, StaffDocument> =
        documents.groupBy { it.kind }.mapValues { (_, l) -> l.maxBy { it.recordedAtEpochMillis } }

    fun issues(employee: Employee, documents: List<StaffDocument>, today: BusinessDate): List<DocumentIssue> {
        if (employee.employedDays(today, today) != 1) return emptyList()
        val current = current(documents.filter { it.employeeId == employee.id })
        val missing = StaffDocument.Kind.entries.filter { it.requiredForEveryone && it !in current }.map { DocumentIssue(employee.id, it, null, null) }
        val expiring = current.values.mapNotNull { d ->
            d.expiresOn?.let { e -> (e.epochDay - today.epochDay).takeIf { it <= WARN_DAYS }?.let { DocumentIssue(employee.id, d.kind, d, it) } }
        }
        return missing + expiring
    }
}

@NoDocument
data class AddStaffDocument(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val kind: StaffDocument.Kind,
    val title: String,
    val expiresOn: BusinessDate?,
    val note: String,
    val files: List<AttachmentInput>,
) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$employeeId|$kind|$title|${expiresOn?.epochDay}|$note|" + files.joinToString(";") { it.fingerprint() }
}

class StaffDocumentOperations(
    private val bus: CommandBus,
    private val personnel: PersonnelStore,
    private val documents: StaffDocumentStore,
    private val attachments: AttachmentStore,
) {
    fun add(c: AddStaffDocument): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = personnel.employee(cmd.employeeId) ?: throw DomainException(DomainError.NotFound("EMPLOYEE"))
        ensure(employee.scope == cmd.scope) { DomainError.InvalidInput("scope", "این کارمند متعلق به این شعبه نیست.") }
        ensure(cmd.title.length <= 80 && cmd.note.length <= 300) { DomainError.InvalidInput("text", "متن بیش از حد طولانی است.") }
        ensure(cmd.kind != StaffDocument.Kind.HEALTH_CARD || cmd.expiresOn != null) { DomainError.InvalidInput("expiresOn", "تاریخ اعتبار کارت بهداشت لازم است.") }
        ensure(cmd.files.isNotEmpty() || cmd.note.isNotBlank()) { DomainError.InvalidInput("files", "عکس یا PDF مدرک، یا دست‌کم توضیحی لازم است.") }
        val document = StaffDocument(GlobalId.new(), cmd.scope, employee.id, cmd.kind, cmd.title.trim(), cmd.expiresOn, cmd.note.trim(), ctx.nowEpochMillis)
        documents.save(document)
        if (cmd.files.isNotEmpty()) Attachments.attach(ctx, attachments, OWNER, document.id, cmd.files)
        ctx.audit(AuditDraft("STAFF_DOCUMENT_ADD", "EMPLOYEE", employee.id.value, "kind=${cmd.kind};expires=${cmd.expiresOn?.epochDay};files=${cmd.files.size}"))
        document.id
    }

    companion object {
        const val OWNER = "STAFF_DOCUMENT"
    }
}
