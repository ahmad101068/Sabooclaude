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
 * guarantee. Records are never overwritten: a renewed card is a new document (the old one stays in the history);
 * a correction is a new record that [replaces] the wrong one, and a removal is a [voided] record replacing it, so
 * every earlier version stays. The latest live document of each kind is the one in force.
 */
data class StaffDocument(
    val id: GlobalId,
    val scope: Scope.Branch,
    val employeeId: GlobalId,
    val kind: Kind,
    val title: String,
    val expiresOn: BusinessDate?,
    val note: String,
    /** For a renewal's order: a correction keeps the time of the document it corrects. */
    val recordedAtEpochMillis: Long,
    /** The document this record corrects or removes. */
    val replaces: GlobalId? = null,
    /** A removal: the replaced document was entered by mistake. */
    val voided: Boolean = false,
    /** Why it was removed. */
    val reason: String = "",
) {
    enum class Kind(val requiredForEveryone: Boolean = false) { HEALTH_CARD(true), CONTRACT(true), ID_CARD, INSURANCE, GUARANTEE, OTHER }
}

interface StaffDocumentStore {
    fun byId(id: GlobalId): StaffDocument?
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

    /** Documents neither corrected nor removed since. */
    fun live(documents: List<StaffDocument>): List<StaffDocument> {
        val replaced = documents.mapNotNull { it.replaces }.toSet()
        return documents.filter { !it.voided && it.id !in replaced }
    }

    /** [document] and every earlier version it corrected, newest first. */
    fun versions(document: StaffDocument, documents: List<StaffDocument>): List<StaffDocument> {
        val byId = documents.associateBy { it.id }
        return generateSequence(document) { d -> d.replaces?.let(byId::get) }.toList()
    }

    /** The latest live document of each kind. */
    fun current(documents: List<StaffDocument>): Map<StaffDocument.Kind, StaffDocument> =
        live(documents).groupBy { it.kind }.mapValues { (_, l) -> l.maxBy { it.recordedAtEpochMillis } }

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
    override val requiredPermission get() = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$employeeId|$kind|$title|${expiresOn?.epochDay}|$note|" + files.joinToString(";") { it.fingerprint() }
}

/** Corrects a document entered wrongly (kind, title, expiry, note); [files] are added to those it already had. */
@NoDocument
data class CorrectStaffDocument(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val documentId: GlobalId,
    val kind: StaffDocument.Kind,
    val title: String,
    val expiresOn: BusinessDate?,
    val note: String,
    val files: List<AttachmentInput>,
) : Command {
    override val requiredPermission get() = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$documentId|$kind|$title|${expiresOn?.epochDay}|$note|" + files.joinToString(";") { it.fingerprint() }
}

/** Removes a document entered by mistake; it stays in the history with the reason. */
@NoDocument
data class RemoveStaffDocument(override val commandId: GlobalId, override val scope: Scope.Branch, val documentId: GlobalId, val reason: String) : Command {
    override val requiredPermission get() = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$documentId|$reason"
}

@NoDocument
data class SetEmployeePhoto(override val commandId: GlobalId, override val scope: Scope.Branch, val employeeId: GlobalId, val photo: AttachmentInput) : Command {
    override val requiredPermission get() = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$employeeId|${photo.fingerprint()}"
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
        validate(cmd.kind, cmd.title, cmd.expiresOn, cmd.note)
        ensure(cmd.files.isNotEmpty() || cmd.note.isNotBlank()) { DomainError.InvalidInput("files", "عکس یا PDF مدرک، یا دست‌کم توضیحی لازم است.") }
        val document = StaffDocument(GlobalId.new(), cmd.scope, employee.id, cmd.kind, cmd.title.trim(), cmd.expiresOn, cmd.note.trim(), ctx.nowEpochMillis)
        documents.save(document)
        if (cmd.files.isNotEmpty()) Attachments.attach(ctx, attachments, OWNER, document.id, cmd.files)
        ctx.audit(AuditDraft("STAFF_DOCUMENT_ADD", "EMPLOYEE", employee.id.value, "kind=${cmd.kind};expires=${cmd.expiresOn?.epochDay};files=${cmd.files.size}"))
        document.id
    }

    fun correct(c: CorrectStaffDocument): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val old = liveDocument(cmd.documentId, cmd.scope)
        validate(cmd.kind, cmd.title, cmd.expiresOn, cmd.note)
        val document = StaffDocument(GlobalId.new(), cmd.scope, old.employeeId, cmd.kind, cmd.title.trim(), cmd.expiresOn, cmd.note.trim(),
            old.recordedAtEpochMillis, replaces = old.id)
        documents.save(document)
        if (cmd.files.isNotEmpty()) Attachments.attach(ctx, attachments, OWNER, document.id, cmd.files)
        ctx.audit(AuditDraft("STAFF_DOCUMENT_CORRECT", "EMPLOYEE", old.employeeId.value,
            "doc=${old.id.value};kind=${old.kind}->${cmd.kind};expires=${old.expiresOn?.epochDay}->${cmd.expiresOn?.epochDay};files=+${cmd.files.size}"))
        document.id
    }

    fun remove(c: RemoveStaffDocument): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val old = liveDocument(cmd.documentId, cmd.scope)
        val reason = cmd.reason.trim()
        ensure(reason.length in 3..200) { DomainError.InvalidInput("reason", "دلیل حذف را بنویسید.") }
        val tombstone = StaffDocument(GlobalId.new(), cmd.scope, old.employeeId, old.kind, old.title, old.expiresOn, old.note,
            old.recordedAtEpochMillis, replaces = old.id, voided = true, reason = reason)
        documents.save(tombstone)
        ctx.audit(AuditDraft("STAFF_DOCUMENT_REMOVE", "EMPLOYEE", old.employeeId.value, "doc=${old.id.value};kind=${old.kind};reason=$reason"))
        tombstone.id
    }

    private fun validate(kind: StaffDocument.Kind, title: String, expiresOn: BusinessDate?, note: String) {
        ensure(title.length <= 80 && note.length <= 300) { DomainError.InvalidInput("text", "متن بیش از حد طولانی است.") }
        ensure(kind != StaffDocument.Kind.HEALTH_CARD || expiresOn != null) { DomainError.InvalidInput("expiresOn", "تاریخ اعتبار کارت بهداشت لازم است.") }
    }

    /** A document of this branch that has not been corrected or removed since (only the latest version is changed). */
    private fun liveDocument(id: GlobalId, scope: Scope.Branch): StaffDocument {
        val doc = documents.byId(id) ?: throw DomainException(DomainError.NotFound("STAFF_DOCUMENT"))
        // The person's branch now (documents stay with the person when they move to another branch).
        ensure(personnel.employee(doc.employeeId)?.scope == scope) { DomainError.InvalidInput("scope", "این کارمند متعلق به این شعبه نیست.") }
        ensure(doc in StaffDocumentRules.live(documents.of(doc.employeeId))) { DomainError.InvalidState("STAFF_DOCUMENT", "ALREADY_CHANGED") }
        return doc
    }

    /** The person's photo for their file (the latest one is shown). */
    fun setPhoto(c: SetEmployeePhoto): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = personnel.employee(cmd.employeeId) ?: throw DomainException(DomainError.NotFound("EMPLOYEE"))
        ensure(employee.scope == cmd.scope) { DomainError.InvalidInput("scope", "این کارمند متعلق به این شعبه نیست.") }
        ensure(cmd.photo.mime.startsWith("image/")) { DomainError.InvalidInput("photo", "عکس لازم است.") }
        Attachments.attach(ctx, attachments, PHOTO, employee.id, listOf(cmd.photo))
        ctx.audit(AuditDraft("EMPLOYEE_PHOTO", "EMPLOYEE", employee.id.value, ""))
        employee.id
    }

    companion object {
        const val OWNER = "STAFF_DOCUMENT"
        const val PHOTO = "EMPLOYEE_PHOTO"
    }
}
