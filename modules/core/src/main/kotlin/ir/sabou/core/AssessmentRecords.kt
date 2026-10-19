package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.persistence.Json
import ir.sabou.persistence.SqlDatabase
import ir.sabou.platform.Command
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission

/*
 * ADR-0062: what the owner does about a finding (actions, with their measured effect) and the few facts the books do
 * not hold but the scorecard needs (field records: temperatures, customer feedback, campaigns, breakdowns, portion
 * weights, permits). Both are records, never journals: they change no figure of the books.
 */

/**
 * What was decided about a finding. [before] / [after]: the finding's indicator over the same number of days before
 * the action was opened and from then until it was closed — the measured effect, not a promise. A [Status.FALSE_ALARM]
 * keeps the same finding quiet for [Assessments.FALSE_ALARM_DAYS] days.
 */
data class AssessmentAction(
    val id: GlobalId,
    val scope: Scope.Branch,
    val findingKey: String,
    val kpiId: String?,
    val title: String,
    val action: String,
    val owner: String,
    val due: BusinessDate,
    val cost: Long,
    val opened: BusinessDate,
    val status: Status,
    val closed: BusinessDate? = null,
    val note: String = "",
    val before: Long? = null,
    val after: Long? = null,
) {
    enum class Status(val title: String) { OPEN("باز"), DONE("انجام شد"), DROPPED("کنار گذاشته شد"), FALSE_ALARM("هشدار اشتباه") }

    fun late(today: BusinessDate) = status == Status.OPEN && due < today

    /** Whether the indicator moved the better way; null without both figures. */
    val improved: Boolean? get() = if (before == null || after == null || kpiId == null) null
        else if (Assessments.DIRECTIONS[kpiId] == ir.sabou.core.KpiTarget.Better.LOWER) after < before else after > before
}

/** A fact recorded by hand for the scorecard. The meaning of [value], [value2] and [endDate] depends on [kind]. */
data class FieldRecord(
    val id: GlobalId,
    val scope: Scope.Branch,
    val kind: Kind,
    val date: BusinessDate,
    val subject: String,
    val value: Long?,
    val value2: Long?,
    val endDate: BusinessDate?,
    val note: String,
    val by: String,
    val voided: Boolean = false,
) {
    /**
     * TEMPERATURE: [subject] the fridge or freezer, [value] tenths of °C, [value2] 1 for a freezer.
     * FEEDBACK: [value] the customer's rating 1–5, [note] what they said.
     * CAMPAIGN: [subject] its name, [date]..[endDate] its days, [value] its cost in Rial.
     * BREAKDOWN: [subject] the equipment, [value] repair cost in Rial, [value2] minutes out of use.
     * PORTION: [subject] what was weighed, [value] grams in the recipe, [value2] grams weighed.
     * PERMIT: [subject] the licence or permit, [endDate] its expiry.
     */
    enum class Kind(val title: String, val permission: Permission) {
        TEMPERATURE("دمای یخچال و فریزر", Permission.INVENTORY_COUNT),
        FEEDBACK("نظر مشتری", Permission.SALES_RECORD),
        CAMPAIGN("کمپین تبلیغاتی", Permission.MENU_PRICE_MANAGE),
        BREAKDOWN("خرابی تجهیزات", Permission.INVENTORY_COUNT),
        PORTION("وزن نمونه‌ی پرس", Permission.INVENTORY_COUNT),
        PERMIT("مجوز و پروانه", Permission.BUDGET_MANAGE),
    }

    val freezer: Boolean get() = value2 == 1L

    /** A temperature outside the safe range: a fridge above 5 °C or below −1 °C, a freezer above −18 °C. */
    val unsafe: Boolean get() = kind == Kind.TEMPERATURE && value != null && (if (freezer) value > -180 else value > 50 || value < -10)
}

@NoDocument
data class OpenAssessmentAction(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val findingKey: String,
    val kpiId: String?,
    val title: String,
    val action: String,
    val owner: String,
    val due: BusinessDate,
    val cost: Long,
    val today: BusinessDate,
) : Command {
    override val requiredPermission = Permission.BUDGET_MANAGE
    override fun fingerprint() = "$scope|$findingKey|$kpiId|$title|$action|$owner|${due.epochDay}|$cost|${today.epochDay}"
}

/** [status]: DONE or DROPPED. A false alarm is [MarkFalseAlarm]. */
@NoDocument
data class CloseAssessmentAction(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val actionId: GlobalId,
    val status: AssessmentAction.Status,
    val note: String,
    val today: BusinessDate,
) : Command {
    override val requiredPermission = Permission.BUDGET_MANAGE
    override fun fingerprint() = "$scope|$actionId|$status|$note|${today.epochDay}"
}

@NoDocument
data class MarkFalseAlarm(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val findingKey: String,
    val title: String,
    val note: String,
    val today: BusinessDate,
) : Command {
    override val requiredPermission = Permission.BUDGET_MANAGE
    override fun fingerprint() = "$scope|$findingKey|$note|${today.epochDay}"
}

@NoDocument
data class RecordField(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val kind: FieldRecord.Kind,
    val date: BusinessDate,
    val subject: String,
    val value: Long?,
    val value2: Long? = null,
    val endDate: BusinessDate? = null,
    val note: String = "",
) : Command {
    override val requiredPermission: Permission get() = kind.permission
    override fun fingerprint() = "$scope|$kind|${date.epochDay}|$subject|$value|$value2|${endDate?.epochDay}|$note"
}

/** A record entered by mistake stays on file, marked void with the reason. */
@NoDocument
data class VoidFieldRecord(override val commandId: GlobalId, override val scope: Scope.Branch, val recordId: GlobalId, val kind: FieldRecord.Kind, val reason: String) : Command {
    override val requiredPermission: Permission get() = kind.permission
    override fun fingerprint() = "$scope|$recordId|$reason"
}

/** The two tables, as JSON documents like every other store (migration 27). */
internal class AssessmentStore(private val db: SqlDatabase) {
    fun actions(scope: Scope.Branch): List<AssessmentAction> =
        db.query("SELECT doc FROM assessment_actions WHERE scope = ? ORDER BY rowid", key(scope)).map { actionOf(it.str("doc")) }

    fun action(id: GlobalId): AssessmentAction? = db.query("SELECT doc FROM assessment_actions WHERE id = ?", id.value).firstOrNull()?.let { actionOf(it.str("doc")) }

    fun save(a: AssessmentAction) {
        val doc = Json.encode(mapOf(
            "id" to a.id.value, "scope" to key(a.scope), "finding" to a.findingKey, "kpi" to a.kpiId, "title" to a.title, "action" to a.action,
            "owner" to a.owner, "due" to a.due.epochDay, "cost" to a.cost, "opened" to a.opened.epochDay, "status" to a.status.name,
            "closed" to a.closed?.epochDay, "note" to a.note, "before" to a.before, "after" to a.after,
        ))
        db.execute("INSERT INTO assessment_actions (id, scope, doc) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET doc = excluded.doc", a.id.value, key(a.scope), doc)
    }

    fun records(scope: Scope.Branch, kind: FieldRecord.Kind? = null): List<FieldRecord> =
        (if (kind == null) db.query("SELECT doc FROM field_records WHERE scope = ? ORDER BY date, rowid", key(scope))
        else db.query("SELECT doc FROM field_records WHERE scope = ? AND kind = ? ORDER BY date, rowid", key(scope), kind.name)).map { recordOf(it.str("doc")) }

    fun record(id: GlobalId): FieldRecord? = db.query("SELECT doc FROM field_records WHERE id = ?", id.value).firstOrNull()?.let { recordOf(it.str("doc")) }

    fun save(r: FieldRecord) {
        val doc = Json.encode(mapOf(
            "id" to r.id.value, "scope" to key(r.scope), "kind" to r.kind.name, "date" to r.date.epochDay, "subject" to r.subject, "value" to r.value,
            "value2" to r.value2, "end" to r.endDate?.epochDay, "note" to r.note, "by" to r.by, "voided" to r.voided,
        ))
        db.execute("INSERT INTO field_records (id, scope, kind, date, doc) VALUES (?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET doc = excluded.doc",
            r.id.value, key(r.scope), r.kind.name, r.date.epochDay, doc)
    }

    private fun key(scope: Scope.Branch) = scope.branchId.value.value

    @Suppress("UNCHECKED_CAST")
    private fun map(text: String) = Json.decode(text) as Map<String, Any?>

    private fun actionOf(text: String): AssessmentAction = map(text).let { d ->
        AssessmentAction(
            GlobalId.parse(d["id"] as String), Scope.Branch(ir.sabou.kernel.BranchId(GlobalId.parse(d["scope"] as String))), d["finding"] as String, d["kpi"] as String?,
            d["title"] as String, d["action"] as String, d["owner"] as String, BusinessDate(d["due"] as Long), d["cost"] as Long, BusinessDate(d["opened"] as Long),
            AssessmentAction.Status.valueOf(d["status"] as String), (d["closed"] as Long?)?.let(::BusinessDate), d["note"] as String,
            d["before"] as Long?, d["after"] as Long?,
        )
    }

    private fun recordOf(text: String): FieldRecord = map(text).let { d ->
        FieldRecord(
            GlobalId.parse(d["id"] as String), Scope.Branch(ir.sabou.kernel.BranchId(GlobalId.parse(d["scope"] as String))), FieldRecord.Kind.valueOf(d["kind"] as String),
            BusinessDate(d["date"] as Long), d["subject"] as String, d["value"] as Long?, d["value2"] as Long?, (d["end"] as Long?)?.let(::BusinessDate),
            d["note"] as String, d["by"] as String, d["voided"] as Boolean,
        )
    }

    companion object {
        fun check(ok: Boolean, field: String, message: String) { if (!ok) throw DomainException(DomainError.InvalidInput(field, message)) }
    }
}
