package ir.sabou.core

import ir.sabou.kernel.sumOfExact
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Actor
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.SpecialDay
import ir.sabou.treasury.ChequeDirection
import ir.sabou.treasury.ChequeStatus
import ir.sabou.treasury.TreasuryKind

/*
 * The 360° scorecard and diagnosis (ADR-0061): twenty dimensions of a restaurant, each measured by indicators
 * computed from the books and records — exact, versioned rules, no model. An indicator without enough data is
 * Unknown (never zero) and asks for the data instead; a finding states its evidence, the money at stake, the likely
 * causes and a suggested action, and never claims a cause it cannot show.
 */

/** Five areas; [weight] is the area's share of the overall score (a suggestion the owner may change later). */
enum class AssessmentArea(val title: String, val weight: Long) {
    FINANCE("مالی و نقدینگی", 30),
    COST_AND_MENU("بهای تمام‌شده، منو و خرید", 25),
    OPERATIONS("انبار، آشپزخانه و سرویس", 20),
    PEOPLE_AND_CUSTOMERS("کارکنان، ایمنی و مشتری", 15),
    GOVERNANCE("انطباق، نظم، داده و ریسک", 10),
}

enum class Dimension(val code: String, val title: String, val area: AssessmentArea) {
    D01("D01", "صحت حسابداری", AssessmentArea.FINANCE),
    D02("D02", "سودآوری", AssessmentArea.FINANCE),
    D03("D03", "بهای تمام‌شده", AssessmentArea.COST_AND_MENU),
    D04("D04", "نقدینگی", AssessmentArea.FINANCE),
    D05("D05", "فروش کانال‌ها", AssessmentArea.COST_AND_MENU),
    D06("D06", "مهندسی منو", AssessmentArea.COST_AND_MENU),
    D07("D07", "بازاریابی و برند", AssessmentArea.PEOPLE_AND_CUSTOMERS),
    D08("D08", "تجربه‌ی مشتری", AssessmentArea.PEOPLE_AND_CUSTOMERS),
    D09("D09", "خرید و تأمین", AssessmentArea.COST_AND_MENU),
    D10("D10", "موجودی و ضایعات", AssessmentArea.OPERATIONS),
    D11("D11", "آشپزخانه و دستور پخت", AssessmentArea.OPERATIONS),
    D12("D12", "سرویس و ظرفیت", AssessmentArea.OPERATIONS),
    D13("D13", "نیروی انسانی", AssessmentArea.PEOPLE_AND_CUSTOMERS),
    D14("D14", "ایمنی غذا", AssessmentArea.PEOPLE_AND_CUSTOMERS),
    D15("D15", "تجهیزات و فضا", AssessmentArea.OPERATIONS),
    D16("D16", "انطباق قانونی", AssessmentArea.GOVERNANCE),
    D17("D17", "نظم مدیریتی", AssessmentArea.GOVERNANCE),
    D18("D18", "فناوری و داده", AssessmentArea.GOVERNANCE),
    D19("D19", "ریسک و تداوم", AssessmentArea.GOVERNANCE),
    D20("D20", "رشد", AssessmentArea.FINANCE),
}

enum class KpiUnit { PERCENT, RIAL, DAYS, WEEKS, COUNT, RIAL_PER_HOUR, /** ×100: 420 = 4.2 of 5 */ RATING }

/**
 * How an indicator is scored: 100 at [good] or better, 0 at [bad] or worse, straight in between. Basis points for
 * percentages, the unit itself otherwise.
 */
data class KpiTarget(val better: Better, val good: Long, val bad: Long) {
    enum class Better { HIGHER, LOWER }

    init { require(if (better == Better.LOWER) bad > good else good > bad) { "kpi_target_order" } }

    fun score(value: Long): Int = when (better) {
        Better.LOWER -> when { value <= good -> 100; value >= bad -> 0; else -> Ratio.mulDiv(bad - value, 100, bad - good).toInt() }
        Better.HIGHER -> when { value >= good -> 100; value <= bad -> 0; else -> Ratio.mulDiv(value - bad, 100, good - bad).toInt() }
    }

    companion object {
        fun lower(good: Long, bad: Long) = KpiTarget(Better.LOWER, good, bad)
        fun higher(good: Long, bad: Long) = KpiTarget(Better.HIGHER, good, bad)
    }
}

/**
 * [OK]: measured. [PARTIAL]: measured, but part of the data is missing ([Kpi.note] says which) — scored, flagged.
 * [UNKNOWN]: not enough data — no value, no score, a data request instead. [NOT_APPLICABLE]: does not apply to this
 * restaurant (no credit customers, no employees) — left out of the score and of its coverage.
 */
enum class KpiQuality { OK, PARTIAL, UNKNOWN, NOT_APPLICABLE }

/**
 * One indicator with its evidence: the [numerator] and [denominator] it is made of, its [source], its rule
 * [version]. A [target] of null: shown for information, not scored.
 */
data class Kpi(
    val id: String,
    val dimension: Dimension,
    val title: String,
    val value: Long?,
    val unit: KpiUnit,
    val target: KpiTarget?,
    val quality: KpiQuality,
    val source: String,
    val numerator: Long? = null,
    val denominator: Long? = null,
    val note: String = "",
    val version: Int = 1,
) {
    val score: Int? get() = if ((quality == KpiQuality.OK || quality == KpiQuality.PARTIAL) && value != null && target != null) target.score(value) else null
}

data class DimensionResult(val dimension: Dimension, val kpis: List<Kpi>) {
    /** The average of its scored indicators; null when none could be scored. */
    val score: Int? get() = kpis.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.let { (it.sum() / it.size) }
    val applicable: Boolean get() = kpis.isEmpty() || kpis.any { it.quality != KpiQuality.NOT_APPLICABLE }
}

data class AreaResult(val area: AssessmentArea, val score: Int?, val dimensions: List<DimensionResult>)

/** One part of the data-quality score: [bp] of what was expected is there. */
data class QualityPart(val title: String, val bp: Long, val detail: String)

/**
 * A problem worth acting on. [exposureLow]..[exposureHigh]: Rial at stake in the period (equal when exact; null
 * when it cannot be priced). [causes]: what the figures point at, never a verdict. [action]: a suggestion for a
 * person to decide on — nothing is done by the report.
 */
data class Finding(
    val key: String,
    val severity: Severity,
    val dimension: Dimension,
    val title: String,
    val evidence: List<String>,
    val exposureLow: Long?,
    val exposureHigh: Long?,
    val causes: List<String>,
    val action: String,
    val confidence: Confidence,
    /** The indicator an action on this finding is measured by (ADR-0062). */
    val kpiId: String? = null,
) {
    /** [CRITICAL] is shown before the score, whatever the score (food safety, books out of balance). */
    enum class Severity(val title: String) { CRITICAL("بحرانی"), HIGH("زیاد"), MEDIUM("متوسط"), LOW("کم") }
    enum class Confidence(val title: String) { HIGH("زیاد"), MEDIUM("متوسط"), LOW("کم") }
}

/**
 * The owner's choices (ADR-0062): a target per indicator id (100 at [KpiTarget.good], 0 at [KpiTarget.bad]) and a
 * weight per area. What is not set keeps the rules' default. [revision] counts the changes and is printed on every
 * report beside the rules version, so two reports with different settings never look alike.
 */
data class AssessmentSettings(
    val targets: Map<String, KpiTarget> = emptyMap(),
    val weights: Map<AssessmentArea, Long> = emptyMap(),
    val revision: Int = 0,
    /** What each sales channel costs, basis points of its net sales: delivery apps' commission, couriers (D05). */
    val channelCosts: Map<ir.sabou.sales.SalesChannel, Long> = emptyMap(),
) {
    fun weight(area: AssessmentArea): Long = weights[area] ?: area.weight
}

/**
 * Sets every target and weight at once (what is left out goes back to the default). [targets]: indicator id →
 * [good, bad]; [weights]: area name → 0..100.
 */
@NoDocument
data class SetAssessmentSettings(
    override val commandId: GlobalId,
    val targets: Map<String, List<Long>>,
    val weights: Map<String, Long>,
    /** Channel name → basis points of its net sales (commission, courier). */
    val channelCosts: Map<String, Long> = emptyMap(),
) : Command {
    override val requiredPermission = Permission.BUDGET_MANAGE
    override val scope: Scope = Scope.Organization
    override fun fingerprint() = targets.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" } + "|" +
        weights.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" } + "|" +
        channelCosts.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
}

/** Data the report needs and does not have, and what to record to get it. */
data class DataRequest(val dimension: Dimension, val text: String)

data class Assessment(
    val branch: Scope.Branch,
    val branchName: String,
    val from: BusinessDate,
    val to: BusinessDate,
    val baseFrom: BusinessDate,
    val baseTo: BusinessDate,
    val dimensions: List<DimensionResult>,
    val areas: List<AreaResult>,
    /** Weighted over the dimensions that could be scored; null when they cover less than [Assessments.MIN_COVERAGE_BP]. */
    val score: Int?,
    /** Share of the applicable weight that could be scored, basis points. */
    val coverageBp: Long,
    val quality: List<QualityPart>,
    val findings: List<Finding>,
    val dataRequests: List<DataRequest>,
    val rulesVersion: Int = Assessments.RULES_VERSION,
    val settings: AssessmentSettings = AssessmentSettings(),
    /** Open actions, and those closed in the last [Assessments.ACTION_DAYS] days (ADR-0062). */
    val actions: List<AssessmentAction> = emptyList(),
    /** Findings kept quiet because someone marked them a false alarm. */
    val silenced: List<String> = emptyList(),
) {
    val kpis: List<Kpi> get() = dimensions.flatMap { it.kpis }
    val critical: List<Finding> get() = findings.filter { it.severity == Finding.Severity.CRITICAL }
    /** The average of the data-quality parts; null without any. */
    val dataQualityBp: Long? get() = quality.takeIf { it.isNotEmpty() }?.let { q -> q.sumOf { it.bp } / q.size }
}

/** The 360° scorecard and diagnosis of one branch for a period, against the period of the same length before it. */
class Assessments internal constructor(private val core: SabouCore) {
    private fun actor(): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (!a.role.allows(Permission.LEDGER_VIEW)) throw DomainException(DomainError.PermissionDenied(Permission.LEDGER_VIEW.name))
        return a
    }

    private val meta = ir.sabou.persistence.DatabaseMeta(core.db)
    private val store = AssessmentStore(core.db)

    // ------------------------------------------------------------ actions (ADR-0062)

    fun actions(branch: Scope.Branch): List<AssessmentAction> {
        val a = actor(); if (!a.canAccess(branch)) throw DomainException(DomainError.PermissionDenied("BRANCH"))
        return store.actions(branch)
    }

    fun openAction(c: OpenAssessmentAction): CommandOutcome = core.bus.execute(ModuleId.PLATFORM, c) { cmd, ctx ->
        AssessmentStore.check(cmd.title.isNotBlank() && cmd.action.isNotBlank(), "action", "عارضه و اقدام را بنویسید.")
        AssessmentStore.check(cmd.owner.isNotBlank(), "owner", "مسئول اقدام را بنویسید.")
        AssessmentStore.check(cmd.due >= cmd.today, "due", "مهلت نمی‌تواند پیش از امروز باشد.")
        AssessmentStore.check(cmd.cost >= 0, "cost", "هزینه منفی نمی‌شود.")
        AssessmentStore.check(cmd.kpiId == null || cmd.kpiId in DIRECTIONS, "kpi", "شاخص ناشناخته است.")
        val action = AssessmentAction(GlobalId.new(), cmd.scope, cmd.findingKey, cmd.kpiId, cmd.title.trim(), cmd.action.trim(), cmd.owner.trim(), cmd.due, cmd.cost,
            cmd.today, AssessmentAction.Status.OPEN)
        store.save(action)
        ctx.audit(AuditDraft("ACTION_OPENED", "ASSESSMENT_ACTION", action.id.value, "finding=${cmd.findingKey};due=${cmd.due.epochDay};cost=${cmd.cost}"))
        action.id
    }

    /**
     * Closes an action and measures it: the finding's indicator from the day the action was opened to the day it is
     * closed, against as many days just before it was opened. Never a day after the close (no data yet).
     */
    fun closeAction(c: CloseAssessmentAction): CommandOutcome = core.bus.execute(ModuleId.PLATFORM, c) { cmd, ctx ->
        val action = store.action(cmd.actionId)?.takeIf { it.scope == cmd.scope } ?: throw DomainException(DomainError.NotFound("ACTION"))
        if (action.status != AssessmentAction.Status.OPEN) throw DomainException(DomainError.InvalidState("ACTION", "CLOSED"))
        AssessmentStore.check(cmd.status == AssessmentAction.Status.DONE || cmd.status == AssessmentAction.Status.DROPPED, "status", "انجام شد یا کنار گذاشته شد.")
        AssessmentStore.check(cmd.today >= action.opened, "today", "بستن پیش از باز شدن اقدام نیست.")
        val days = cmd.today.epochDay - action.opened.epochDay + 1
        fun value(from: BusinessDate, to: BusinessDate) = action.kpiId?.let { id ->
            try { of(cmd.scope, from, to).kpis.firstOrNull { it.id == id }?.value } catch (e: DomainException) { null }
        }
        val before = value(action.opened.plusDays(-days), action.opened.plusDays(-1))
        val after = value(action.opened, action.opened.plusDays(days - 1))
        store.save(action.copy(status = cmd.status, closed = cmd.today, note = cmd.note.trim(), before = before, after = after))
        ctx.audit(AuditDraft("ACTION_CLOSED", "ASSESSMENT_ACTION", action.id.value, "status=${cmd.status};before=$before;after=$after"))
        action.id
    }

    fun markFalseAlarm(c: MarkFalseAlarm): CommandOutcome = core.bus.execute(ModuleId.PLATFORM, c) { cmd, ctx ->
        AssessmentStore.check(cmd.note.isNotBlank(), "note", "بنویسید چرا این هشدار اشتباه است.")
        val action = AssessmentAction(GlobalId.new(), cmd.scope, cmd.findingKey, FINDING_KPI[cmd.findingKey], cmd.title, "هشدار اشتباه", ctx.actor.displayName,
            cmd.today, 0, cmd.today, AssessmentAction.Status.FALSE_ALARM, cmd.today, cmd.note.trim())
        store.save(action)
        ctx.audit(AuditDraft("FINDING_FALSE_ALARM", "ASSESSMENT_ACTION", action.id.value, "finding=${cmd.findingKey}"))
        action.id
    }

    // ------------------------------------------------------------ field records (ADR-0062)

    /** Live records of [kind] in a period, for the dashboard (its caller checks the permission). */
    internal fun recordsIn(branch: Scope.Branch, kind: FieldRecord.Kind, from: BusinessDate, to: BusinessDate): List<FieldRecord> =
        store.records(branch, kind).filter { !it.voided && it.date in from..to }

    fun records(branch: Scope.Branch, kind: FieldRecord.Kind): List<FieldRecord> {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (!a.role.allows(kind.permission) && !a.role.allows(Permission.LEDGER_VIEW)) throw DomainException(DomainError.PermissionDenied(kind.permission.name))
        if (!a.canAccess(branch)) throw DomainException(DomainError.PermissionDenied("BRANCH"))
        return store.records(branch, kind)
    }

    fun record(c: RecordField): CommandOutcome = core.bus.execute(ModuleId.PLATFORM, c) { cmd, ctx ->
        val subject = cmd.subject.trim()
        fun need(ok: Boolean, field: String, message: String) = AssessmentStore.check(ok, field, message)
        when (cmd.kind) {
            FieldRecord.Kind.TEMPERATURE -> {
                need(subject.isNotEmpty(), "subject", "نام یخچال یا فریزر را بنویسید.")
                need(cmd.value != null && cmd.value in -400..300, "value", "دما بین ۴۰- و ۳۰ درجه است.")
                need(cmd.value2 == null || cmd.value2 in 0..1, "value2", "یخچال یا فریزر.")
            }
            FieldRecord.Kind.FEEDBACK -> need(cmd.value != null && cmd.value in 1..5, "value", "امتیاز مشتری از ۱ تا ۵ است.")
            FieldRecord.Kind.CAMPAIGN -> {
                need(subject.isNotEmpty(), "subject", "نام کمپین را بنویسید.")
                need(cmd.endDate != null && cmd.endDate >= cmd.date, "endDate", "پایان کمپین پیش از شروع آن نیست.")
                need((cmd.value ?: 0) >= 0, "value", "هزینه منفی نمی‌شود.")
            }
            FieldRecord.Kind.BREAKDOWN -> {
                need(subject.isNotEmpty(), "subject", "نام دستگاه را بنویسید.")
                need((cmd.value ?: 0) >= 0 && (cmd.value2 ?: 0) >= 0, "value", "هزینه و زمان منفی نمی‌شوند.")
            }
            FieldRecord.Kind.PORTION -> {
                need(subject.isNotEmpty(), "subject", "بنویسید چه چیزی وزن شد.")
                need((cmd.value ?: 0) in 1..MAX_PORTION_GRAMS && (cmd.value2 ?: 0) in 1..MAX_PORTION_GRAMS, "value", "وزن رسپی و وزن واقعی را به گرم بنویسید (حداکثر ۱۰ کیلو).")
            }
            FieldRecord.Kind.PERMIT -> {
                need(subject.isNotEmpty(), "subject", "نام مجوز را بنویسید.")
                need(cmd.endDate != null, "endDate", "تاریخ اعتبار مجوز را بنویسید.")
            }
        }
        val r = FieldRecord(GlobalId.new(), cmd.scope, cmd.kind, cmd.date, subject, cmd.value, cmd.value2, cmd.endDate, cmd.note.trim(), ctx.actor.displayName)
        store.save(r)
        ctx.audit(AuditDraft("FIELD_RECORD", "FIELD_RECORD", r.id.value, "kind=${cmd.kind};date=${cmd.date.epochDay}"))
        r.id
    }

    fun voidRecord(c: VoidFieldRecord): CommandOutcome = core.bus.execute(ModuleId.PLATFORM, c) { cmd, ctx ->
        val r = store.record(cmd.recordId)?.takeIf { it.scope == cmd.scope && it.kind == cmd.kind } ?: throw DomainException(DomainError.NotFound("FIELD_RECORD"))
        if (r.voided) throw DomainException(DomainError.InvalidState("FIELD_RECORD", "VOIDED"))
        AssessmentStore.check(cmd.reason.isNotBlank(), "reason", "دلیل باطل کردن را بنویسید.")
        store.save(r.copy(voided = true, note = (r.note + " · باطل: " + cmd.reason.trim()).trim(' ', '·')))
        ctx.audit(AuditDraft("FIELD_RECORD_VOID", "FIELD_RECORD", r.id.value, cmd.reason.trim()))
        r.id
    }

    /** The owner's targets and weights (ADR-0062); defaults where none was set. */
    fun settings(): AssessmentSettings {
        val raw = meta.get(SETTINGS_KEY) ?: return AssessmentSettings()
        @Suppress("UNCHECKED_CAST")
        val m = ir.sabou.persistence.Json.decode(raw) as Map<String, Any?>
        // An indicator or area a later rules version dropped is ignored, never a broken report.
        val targets = (m["targets"] as Map<String, List<Long>>).filterKeys { it in DIRECTIONS }
            .mapNotNull { (id, gb) -> runCatching { id to KpiTarget(better(id), gb[0], gb[1]) }.getOrNull() }.toMap()
        val weights = (m["weights"] as Map<String, Long>).mapNotNull { (k, v) -> AssessmentArea.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap()
        val channels = (m["channels"] as Map<String, Long>?).orEmpty().mapNotNull { (k, v) -> ir.sabou.sales.SalesChannel.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap()
        return AssessmentSettings(targets, weights, (m["revision"] as Long).toInt(), channels)
    }

    fun setSettings(c: SetAssessmentSettings): CommandOutcome = core.bus.execute(ModuleId.PLATFORM, c) { cmd, ctx ->
        cmd.targets.forEach { (id, gb) ->
            if (id !in DIRECTIONS) throw DomainException(DomainError.InvalidInput("target", "شاخص «$id» هدف‌پذیر نیست."))
            if (gb.size != 2) throw DomainException(DomainError.InvalidInput("target", "هر هدف دو عدد دارد: خوب و بد."))
            val ok = if (better(id) == KpiTarget.Better.LOWER) gb[1] > gb[0] else gb[0] > gb[1]
            if (!ok) throw DomainException(DomainError.InvalidInput("target", "در «$id» حد بد باید " +
                (if (better(id) == KpiTarget.Better.LOWER) "بیشتر" else "کمتر") + " از هدف باشد."))
        }
        cmd.weights.forEach { (area, w) ->
            if (AssessmentArea.entries.none { it.name == area }) throw DomainException(DomainError.InvalidInput("weight", "حوزه‌ی «$area» نیست."))
            if (w !in 0..100) throw DomainException(DomainError.InvalidInput("weight", "وزن هر حوزه بین ۰ و ۱۰۰ است."))
        }
        cmd.channelCosts.forEach { (channel, bp) ->
            if (ir.sabou.sales.SalesChannel.entries.none { it.name == channel }) throw DomainException(DomainError.InvalidInput("channel", "کانال «$channel» نیست."))
            if (bp !in 0..9_000) throw DomainException(DomainError.InvalidInput("channel", "هزینه‌ی کانال بین ۰ و ۹۰ درصد فروش آن است."))
        }
        if (AssessmentArea.entries.sumOf { a -> cmd.weights[a.name] ?: a.weight } <= 0) throw DomainException(DomainError.InvalidInput("weight", "دست‌کم یک حوزه وزن می‌خواهد."))
        val revision = settings().revision + 1
        meta.put(SETTINGS_KEY, ir.sabou.persistence.Json.encode(mapOf("targets" to cmd.targets.toSortedMap(), "weights" to cmd.weights.toSortedMap(), "revision" to revision.toLong(),
            "channels" to cmd.channelCosts.filterValues { it > 0 }.toSortedMap())))
        ctx.audit(AuditDraft("ASSESSMENT_SETTINGS", "ASSESSMENT", "settings", "revision=$revision;targets=${cmd.targets.size};weights=${cmd.weights.size}"))
        GlobalId.new()
    }

    /**
     * Last week's card of every branch [userId] may see, as that person would see it, for the Saturday PDF the nightly
     * backup writes beside its files (ADR-0062). Empty when the person may not read the books or no branch sold.
     */
    fun weeklyTables(userId: GlobalId, today: BusinessDate): List<ReportTable> {
        val user = core.users.byId(userId)?.takeIf { it.isActive } ?: return emptyList()
        val actor = user.toActor()
        if (!actor.role.allows(Permission.LEDGER_VIEW)) return emptyList()
        return core.session.readAs(actor) {
            core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.flatMap { b ->
                val a = of(Scope.Branch(b.id), today.plusDays(-7), today.plusDays(-1))
                if (a.kpis.any { it.id == "D18.DAYS" && it.value != null }) ReportTables.assessment(a) else emptyList()
            }
        }
    }

    /** A read this role may not do yields null: the indicator is then Unknown, the report still complete. */
    private fun <T> safe(block: () -> T): T? = try { block() } catch (e: DomainException) { null }

    fun of(branch: Scope.Branch, from: BusinessDate, to: BusinessDate): Assessment {
        val a = actor()
        if (!a.canAccess(branch)) throw DomainException(DomainError.PermissionDenied("BRANCH"))
        if (to < from) throw DomainException(DomainError.InvalidState("PERIOD", "TO_BEFORE_FROM"))
        val branchName = core.branches.all().firstOrNull { it.id == branch.branchId }?.name ?: ""
        val (baseFrom, baseTo) = core.profitBridges.previous(from, to)
        val settings = settings()
        val r = Reader(branch, from, to, baseFrom, baseTo, settings)

        // The owner's target replaces the rule's default (ADR-0062); an indicator shown for information stays so.
        val kpis = r.kpis().map { k -> settings.targets[k.id]?.takeIf { k.target != null }?.let { k.copy(target = it) } ?: k }
        val dimensions = Dimension.entries.map { d -> DimensionResult(d, kpis.filter { it.dimension == d }) }
        val areas = AssessmentArea.entries.map { area -> dimensions.filter { it.dimension.area == area }.let { ds ->
            val scored = ds.mapNotNull { it.score }
            AreaResult(area, scored.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }, ds)
        } }

        // Each area's weight is shared equally by its dimensions; weights are kept ×1,000 to stay whole.
        fun weight(d: DimensionResult) = settings.weight(d.dimension.area) * 1_000 / Dimension.entries.count { it.area == d.dimension.area }
        val applicable = dimensions.filter { it.applicable }
        val total = applicable.sumOf { weight(it) }
        val scored = applicable.filter { it.score != null }
        val scoredWeight = scored.sumOf { weight(it) }
        val coverageBp = if (total == 0L) 0 else Ratio.mulDiv(scoredWeight, 10_000, total)
        val score = if (scoredWeight == 0L || coverageBp < MIN_COVERAGE_BP) null
            else Ratio.mulDiv(scored.sumOf { weight(it) * it.score!! }, 1, scoredWeight).toInt()

        val actions = store.actions(branch)
        val quiet = actions.filter { it.status == AssessmentAction.Status.FALSE_ALARM && to.epochDay - it.opened.epochDay in 0 until FALSE_ALARM_DAYS }.map { it.findingKey }.toSet()
        val all = r.findings(kpis).map { it.copy(kpiId = FINDING_KPI[it.key]) }
        val findings = all.filter { it.key !in quiet }.sortedWith(compareBy<Finding> { it.severity.ordinal }.thenByDescending { it.exposureHigh ?: -1L })
        val measured = kpis.filter { it.value != null }.map { it.dimension }.toSet()
        val requests = kpis.filter { it.quality == KpiQuality.UNKNOWN && it.note.isNotBlank() }.map { DataRequest(it.dimension, it.note) } +
            NOT_RECORDED.filter { it.first !in measured }.map { (d, text) -> DataRequest(d, text) }
        val shown = actions.filter { it.status == AssessmentAction.Status.OPEN || (it.closed != null && to.epochDay - it.closed.epochDay in 0 until ACTION_DAYS) }
        return Assessment(branch, branchName, from, to, baseFrom, baseTo, dimensions, areas, score, coverageBp, r.quality(), findings, requests.distinct(), settings = settings,
            actions = shown, silenced = all.filter { it.key in quiet }.map { it.title })
    }

    /** One report's reads, each done once. */
    private inner class Reader(
        val branch: Scope.Branch, val from: BusinessDate, val to: BusinessDate, val baseFrom: BusinessDate, val baseTo: BusinessDate,
        val settings: AssessmentSettings,
    ) {
        val days = (from.epochDay..to.epochDay).map { BusinessDate(it) }
        val pnl = core.reports.profitAndLoss(from, to, branch)
        val basePnl = core.reports.profitAndLoss(baseFrom, baseTo, branch)
        val sales get() = pnl.ratios.sales
        val target = core.salesOps.foodCostTarget()
        val foodGood = settings.targets["D03.FOOD"]?.good ?: target ?: DEFAULT_FOOD_COST_BP
        val mix by lazy { safe { core.reports.productMix(branch, from, to) } }
        val engineering by lazy { safe { core.reports.menuEngineering(branch, from, to) } }
        /** Approved counts of the branch in the period: without one, usage is not measured and not worth reading. */
        val counted by lazy {
            val places = core.locations.all().filter { it.scope == branch }.map { it.id }.toSet()
            core.stockCounts.all().any { it.status == ir.sabou.inventory.CountStatus.POSTED && it.date in from..to && it.locationId in places }
        }
        val usage by lazy { if (!counted) null else safe { core.reports.actualVsTheoretical(branch, null, from, to) } }
        val customers by lazy { safe { core.cashFlows.customers(branch, to) } }
        val cash by lazy { safe { core.cashFlows.forecast(branch, to) } }
        val bridge by lazy { safe { core.profitBridges.of(branch, from, to, baseFrom, baseTo) } }
        val priceChanges by lazy { safe { core.buying.priceChanges(from, to, PRICE_RISE_BP) }?.filter { (it.changeBp ?: 0) >= PRICE_RISE_BP } }
        val employees by lazy { safe { core.overview.employees(branch) }?.filter { it.employedDays(to, to) == 1 } }
        val documents by lazy { safe { core.labor.documentIssues(branch, to) } }
        val invoices by lazy { core.purchases.invoices().filter { it.scope == branch && it.status == InvoiceStatus.POSTED } }
        val salesByDay by lazy { days.associateWith { d -> core.sales.salesOn(branch, d) } }
        val closedDays by lazy { core.specialDays.of(branch).filter { it.kind == SpecialDay.Kind.CLOSED }.map { it.date }.toSet() }
        val firstSale by lazy {
            // The first day the branch recorded a sale: the days before it are not «missing».
            generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.firstOrNull { d -> salesByDay[d]?.any { it.status == SaleStatus.POSTED } == true }
        }

        fun channelCost(rows: List<ChannelRow>) = rows.sumOfExact { r -> settings.channelCosts[r.channel]?.let { Ratio.mulDiv(r.net.rial, it, 10_000) } ?: 0L }

        fun account(code: ir.sabou.ledger.AccountCode, p: ProfitAndLoss = pnl) = (p.costOfSales + p.expenses).filter { it.account.code == code }.sumOf { it.amount }

        fun kpis(): List<Kpi> = (baseKpis() + recordKpis()).sortedBy { it.dimension.ordinal }

        fun baseKpis(): List<Kpi> = buildList {
            // ---- D01 accounting
            val trial = safe { core.overview.trialBalance() }
            add(if (trial == null) unknown("D01.TRIAL", Dimension.D01, "تراز آزمایشی", KpiUnit.RIAL, "تراز آزمایشی", "")
                else Kpi("D01.TRIAL", Dimension.D01, "اختلاف تراز آزمایشی (باید صفر باشد)", kotlin.math.abs(trial.sumOf { it.second }), KpiUnit.RIAL,
                    KpiTarget.lower(0, 1), KpiQuality.OK, "جمع مانده‌ی بدهکار و بستانکار همه‌ی حساب‌ها"))
            val drafts = salesByDay.values.sumOf { l -> l.count { it.status == SaleStatus.DRAFT } }
            val openDays = salesByDay.count { (d, l) -> l.any { it.status == SaleStatus.POSTED } && core.sales.day(branch, d)?.closed != true }
            add(Kpi("D01.OPEN", Dimension.D01, "پیش‌نویس فروش و روز بسته‌نشده", (drafts + openDays).toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 5), KpiQuality.OK,
                "فروش‌های دوره", note = "${Fa.number(drafts.toLong())} پیش‌نویس، ${Fa.number(openDays.toLong())} روز باز"))
            val banks = core.treasuryAccounts.all().filter { it.kind == TreasuryKind.BANK && it.isActive && it.scope == branch }
            add(if (banks.isEmpty()) na("D01.BANK", Dimension.D01, "مغایرت بانکی", "حساب بانکی برای این شعبه تعریف نشده")
                else {
                    val unreconciled = banks.count { bank ->
                        val last = core.bankStatementStore.of(bank.id).maxByOrNull { it.to }
                        last == null || last.to < to || safe { core.bankRec.reconciliation(last.id) }?.let { it.statementOnly.isNotEmpty() || (it.unexplained ?: 0L) != 0L } ?: true
                    }
                    Kpi("D01.BANK", Dimension.D01, "حساب بانکی تطبیق‌نشده تا پایان دوره", unreconciled.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 2), KpiQuality.OK,
                        "صورت‌حساب‌های بانکی واردشده", unreconciled.toLong(), banks.size.toLong())
                })

            // ---- D02 profitability
            val revenue = pnl.totals.revenue
            if (revenue <= 0) {
                add(unknown("D02.MARGIN", Dimension.D02, "حاشیه‌ی سود عملیاتی", KpiUnit.PERCENT, "سود و زیان", "در این دوره فروشی در دفاتر ثبت نشده است."))
            } else {
                val partial = pnl.provisional || account(StandardAccounts.SALARIES) == 0L
                val why = listOfNotNull(if (pnl.provisional) "کسری انبار باز هست" else null, if (account(StandardAccounts.SALARIES) == 0L) "حقوق دوره هنوز ثبت نشده" else null).joinToString("؛ ")
                add(Kpi("D02.MARGIN", Dimension.D02, "حاشیه‌ی سود عملیاتی", bp(pnl.totals.profit, revenue), KpiUnit.PERCENT, KpiTarget.higher(1_500, 0),
                    if (partial) KpiQuality.PARTIAL else KpiQuality.OK, "سود و زیان (درآمد منهای بهای فروش و هزینه‌ها)", pnl.totals.profit, revenue, why))
                add(Kpi("D02.GROSS", Dimension.D02, "حاشیه‌ی سود ناخالص", bp(pnl.totals.grossProfit, revenue), KpiUnit.PERCENT, KpiTarget.higher(6_500, 5_000),
                    if (pnl.provisional) KpiQuality.PARTIAL else KpiQuality.OK, "سود و زیان (درآمد منهای بهای فروش)", pnl.totals.grossProfit, revenue,
                    if (pnl.provisional) "کسری انبار باز هست" else ""))
            }

            // ---- D03 cost of sales
            if (sales <= 0) {
                add(unknown("D03.FOOD", Dimension.D03, "بهای غذا به فروش", KpiUnit.PERCENT, "سود و زیان", "فروش غذا در این دوره ثبت نشده است."))
            } else {
                val r = pnl.ratios
                add(Kpi("D03.FOOD", Dimension.D03, "بهای غذا به فروش (food cost)", r.foodBp, KpiUnit.PERCENT, KpiTarget.lower(foodGood, foodGood + 1_000),
                    if (pnl.provisional) KpiQuality.PARTIAL else KpiQuality.OK, "بهای فروش، ضایعات، پذیرایی و مغایرت انبار ÷ فروش غذا", r.foodCost, r.sales,
                    (if (target == null && settings.targets["D03.FOOD"] == null) "هدف تعیین نشده؛ ${Fa.percent(DEFAULT_FOOD_COST_BP)} فرض شد" else "") + if (pnl.provisional) "؛ کسری انبار باز هست" else ""))
                add(Kpi("D03.PRIME", Dimension.D03, "بهای اصلی (غذا + نیروی کار) به فروش", r.primeBp, KpiUnit.PERCENT, KpiTarget.lower(6_000, 7_000),
                    if (r.labor == 0L) KpiQuality.PARTIAL else KpiQuality.OK, "بهای غذا و حقوق و بیمه ÷ فروش غذا", r.prime, r.sales,
                    if (r.labor == 0L) "حقوق این دوره هنوز ثبت نشده" else ""))
                val waste = account(StandardAccounts.WASTE)
                add(Kpi("D03.WASTE", Dimension.D03, "ضایعات به فروش", bp(waste, sales), KpiUnit.PERCENT, KpiTarget.lower(100, 400), KpiQuality.OK, "حساب ضایعات ÷ فروش غذا", waste, sales))
                val u = usage
                add(when {
                    u == null && counted -> unknown("D03.VARIANCE", Dimension.D03, "مصرف بی‌توضیح به فروش", KpiUnit.PERCENT, "مصرف واقعی در برابر تئوریک", "")
                    u == null || !u.measuredByCount -> unknown("D03.VARIANCE", Dimension.D03, "مصرف بی‌توضیح به فروش", KpiUnit.PERCENT, "مصرف واقعی در برابر تئوریک",
                        "در این دوره انبارگردانی تأییدشده‌ای نیست؛ بدون شمارش، مصرف واقعی سنجیده نمی‌شود.")
                    else -> Kpi("D03.VARIANCE", Dimension.D03, "مصرف بی‌توضیح به فروش", bp(u.unexplainedValue, sales), KpiUnit.PERCENT, KpiTarget.lower(50, 300),
                        if (u.countedRows < u.rows.size) KpiQuality.PARTIAL else KpiQuality.OK, "کسری شمارش‌های تأییدشده ÷ فروش غذا", u.unexplainedValue, sales,
                        if (u.countedRows < u.rows.size) "${Fa.number(u.countedRows.toLong())} از ${Fa.number(u.rows.size.toLong())} قلم شمرده شده" else "")
                })
            }

            // ---- D04 liquidity
            val cr = customers
            val outstanding = cr?.rows?.sumOf { it.outstanding } ?: 0L
            add(when {
                cr == null -> unknown("D04.OVERDUE", Dimension.D04, "سهم طلب سررسیدگذشته", KpiUnit.PERCENT, "مشتریان نسیه", "")
                outstanding <= 0L -> na("D04.OVERDUE", Dimension.D04, "سهم طلب سررسیدگذشته", "طلب بازی از مشتریان نیست")
                else -> cr.rows.sumOf { it.overdue }.let { od -> Kpi("D04.OVERDUE", Dimension.D04, "سهم طلب سررسیدگذشته", bp(od, outstanding), KpiUnit.PERCENT,
                    KpiTarget.lower(0, 3_000), KpiQuality.OK, "طلب سررسیدگذشته ÷ کل طلب از مشتریان", od, outstanding) }
            })
            val f = cash
            add(when {
                f == null -> unknown("D04.CASH", Dimension.D04, "هفته‌های بدون کسری نقدینگی", KpiUnit.WEEKS, "پیش‌بینی نقدینگی", "")
                f.assumptions.liquidBp == null -> unknown("D04.CASH", Dimension.D04, "هفته‌های بدون کسری نقدینگی", KpiUnit.WEEKS, "پیش‌بینی نقدینگی",
                    "برای پیش‌بینی نقدینگی، سابقه‌ی فروش ثبت‌شده لازم است.")
                else -> Kpi("D04.CASH", Dimension.D04, "هفته‌های بدون کسری نقدینگی (از ${Fa.number(f.weeks.size.toLong())})",
                    ((f.firstShortWeek?.index ?: (f.weeks.size + 1)) - 1).toLong(), KpiUnit.WEEKS, KpiTarget.higher(f.weeks.size.toLong(), 2),
                    KpiQuality.OK, "پیش‌بینی ۱۳هفته‌ای نقدینگی (سناریوی پایه)",
                    note = listOfNotNull(if (!f.assumptions.payrollKnown) "حقوق تأییدشده‌ای نیست" else null, if (!f.assumptions.overheadsBudgeted) "بودجه‌ی هزینه‌های جاری ندارید" else null).joinToString("؛ "))
            })
            if (f != null && f.assumptions.creditBp != null && f.assumptions.creditBp > 0) {
                add(Kpi("D04.DSO", Dimension.D04, "روزهای وصول طلب", f.assumptions.collectionDays.toLong(), KpiUnit.DAYS, KpiTarget.lower(15, 45), KpiQuality.OK,
                    "فاصله‌ی فروش نسیه تا وصول، وزنی بر مبلغ"))
            }

            // ---- D05 channels (information)
            val channels = mix?.channels.orEmpty()
            add(if (channels.isEmpty()) unknown("D05.TOP", Dimension.D05, "سهم بزرگ‌ترین کانال فروش", KpiUnit.PERCENT, "فروش به تفکیک کانال",
                    "کانال فروش (حضوری، بیرون‌بر، پیک، شرکتی) در فروش‌ها ثبت نشده است؛ با ورود فروش از سپند ثبت می‌شود.")
                else Kpi("D05.TOP", Dimension.D05, "سهم بزرگ‌ترین کانال: ${channelName(channels.first().channel)}", channels.first().shareBp, KpiUnit.PERCENT, null,
                    KpiQuality.OK, "فروش خالص غذا به تفکیک کانال", channels.first().net.rial, channels.sumOfExact { it.net.rial }))
            // What the channels cost (commission, couriers), at the rates the owner set.
            val paid = channels.filter { it.channel != null && it.channel != ir.sabou.sales.SalesChannel.DINE_IN }
            if (paid.isNotEmpty()) {
                val net = channels.sumOfExact { it.net.rial }
                val unset = paid.filter { settings.channelCosts[it.channel] == null }
                add(if (unset.size == paid.size) unknown("D05.COMMISSION", Dimension.D05, "هزینه‌ی کانال‌ها به فروش", KpiUnit.PERCENT, "تنظیمات کارنامه",
                        "درصد کمیسیون و هزینه‌ی پیک کانال‌های ${unset.joinToString("، ") { channelName(it.channel) }} را در «کارنامه ← تغییر وزن حوزه‌ها» وارد کنید.")
                    else channelCost(paid).let { cost ->
                        Kpi("D05.COMMISSION", Dimension.D05, "هزینه‌ی کانال‌ها (کمیسیون، پیک) به فروش", bp(cost, net), KpiUnit.PERCENT, KpiTarget.lower(300, 1_500),
                            if (unset.isEmpty()) KpiQuality.OK else KpiQuality.PARTIAL, "فروش هر کانال × درصد هزینه‌ی آن (برآورد)", cost, net,
                            if (unset.isEmpty()) "" else "برای ${unset.joinToString("، ") { channelName(it.channel) }} درصدی وارد نشده")
                    })
            }

            // ---- D06 menu engineering
            val me = engineering
            val classified = me?.rows?.filter { it.menuClass != null }.orEmpty()
            add(if (me == null || classified.isEmpty()) unknown("D06.DOGS", Dimension.D06, "سهم فروش غذاهای کم‌سود و کم‌فروش", KpiUnit.PERCENT, "مهندسی منو",
                    "برای طبقه‌بندی منو، غذاها رسپی و بهای مواد می‌خواهند.")
                else {
                    val dogs = classified.filter { it.menuClass == MenuClass.DOG }.sumOfExact { it.mix.gross.rial }
                    Kpi("D06.DOGS", Dimension.D06, "سهم فروش غذاهای کم‌سود و کم‌فروش", bp(dogs, me.mix.gross.rial), KpiUnit.PERCENT, KpiTarget.lower(500, 2_000),
                        if (classified.size < me.rows.size) KpiQuality.PARTIAL else KpiQuality.OK, "مهندسی منو (کاساوانا و اسمیت)", dogs, me.mix.gross.rial,
                        if (classified.size < me.rows.size) "${Fa.number((me.rows.size - classified.size).toLong())} غذا بی‌بها مانده" else "")
                })
            if (target != null) {
                val over = safe { core.reports.foodCosts(branch, to) }
                add(if (over == null) unknown("D06.OVER", Dimension.D06, "غذای بالای هدف بهای مواد", KpiUnit.COUNT, "بهای غذا با قیمت امروز", "")
                    else Kpi("D06.OVER", Dimension.D06, "غذای بالای هدف بهای مواد (${Fa.percent(target)})", over.count { it.costBp >= target }.toLong(), KpiUnit.COUNT,
                        KpiTarget.lower(0, 5), KpiQuality.OK, "بهای رسپی با میانگین بهای امروز ÷ قیمت منو", over.count { it.costBp >= target }.toLong(), over.size.toLong()))
            }

            // ---- D07, D08, D12, D15: not recorded by the app (see NOT_RECORDED)

            // ---- D09 purchasing
            val periodInvoices = invoices.filter { it.date in from..to }
            add(if (periodInvoices.isEmpty()) unknown("D09.PRICES", Dimension.D09, "اقلام با افزایش قیمت خرید", KpiUnit.COUNT, "فاکتورهای خرید",
                    "در این دوره فاکتور خریدی ثبت نشده است.")
                else priceChanges?.map { it.item.id }?.distinct()?.size?.toLong().let { n ->
                    if (n == null) unknown("D09.PRICES", Dimension.D09, "اقلام با افزایش قیمت خرید", KpiUnit.COUNT, "فاکتورهای خرید", "")
                    else Kpi("D09.PRICES", Dimension.D09, "اقلام با افزایش قیمت بیش از ${Fa.percent(PRICE_RISE_BP)}", n, KpiUnit.COUNT, KpiTarget.lower(0, 5), KpiQuality.OK,
                        "قیمت هر قلم در برابر خرید قبلی آن")
                })
            val matches = safe { core.books.matchReviews() }?.count { it.invoice.scope == branch }
            add(if (matches == null) unknown("D09.MATCH", Dimension.D09, "فاکتور ناهمخوان با سفارش", KpiUnit.COUNT, "تطبیق فاکتور", "")
                else Kpi("D09.MATCH", Dimension.D09, "فاکتور ناهمخوان با سفارش، بررسی‌نشده", matches.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 3), KpiQuality.OK, "تطبیق سفارش، رسید و فاکتور"))

            // Orders received in the period (ADR-0062): on time = invoiced by the expected date; complete = the
            // invoice found no quantity short or item missing against the order.
            val received = core.purchases.orders().filter { it.scope == branch && it.status == ir.sabou.purchasing.OrderStatus.RECEIVED }
                .mapNotNull { o -> o.invoiceIds.mapNotNull { core.purchases.invoice(it) }.minByOrNull { it.date }?.let { o to it } }
                .filter { (_, inv) -> inv.date in from..to }
            if (received.isEmpty()) {
                add(unknown("D09.ONTIME", Dimension.D09, "سفارش‌های رسیده سر موعد", KpiUnit.PERCENT, "سفارش‌های خرید",
                    "سفارش خرید ثبت‌شده‌ای در این دوره نرسیده؛ با ثبت سفارش و فاکتورِ متصل به آن، تحویل تأمین‌کنندگان سنجیده می‌شود."))
            } else {
                val onTime = received.count { (o, inv) -> inv.date <= o.expectedDate }.toLong()
                add(Kpi("D09.ONTIME", Dimension.D09, "سفارش‌های رسیده سر موعد", bp(onTime, received.size.toLong()), KpiUnit.PERCENT, KpiTarget.higher(9_500, 7_000),
                    KpiQuality.OK, "تاریخ فاکتور در برابر تاریخ مورد انتظار سفارش", onTime, received.size.toLong()))
                val complete = received.count { (_, inv) -> inv.discrepancies.none { it.kind == ir.sabou.purchasing.Discrepancy.Kind.QUANTITY || it.kind == ir.sabou.purchasing.Discrepancy.Kind.NOT_DELIVERED } }.toLong()
                add(Kpi("D09.FULL", Dimension.D09, "سفارش‌های رسیده‌ی کامل", bp(complete, received.size.toLong()), KpiUnit.PERCENT, KpiTarget.higher(9_500, 8_000),
                    KpiQuality.OK, "مقدار فاکتور در برابر مقدار سفارش", complete, received.size.toLong()))
            }

            // ---- D10 inventory
            val shortages = safe { core.overview.openShortages(branch) }
            add(if (shortages == null) unknown("D10.SHORT", Dimension.D10, "کسری مصرف باز", KpiUnit.COUNT, "کسری‌ها", "")
                else Kpi("D10.SHORT", Dimension.D10, "کسری مصرف باز", shortages.size.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 5), KpiQuality.OK, "فروش بیش از موجودی دفتری"))
            val due = safe { core.overview.countsDue(branch, to) }
            add(when {
                due == null -> unknown("D10.COUNTS", Dimension.D10, "شمارش عقب‌افتاده", KpiUnit.COUNT, "برنامه‌ی شمارش", "")
                due.isEmpty() -> unknown("D10.COUNTS", Dimension.D10, "شمارش عقب‌افتاده", KpiUnit.COUNT, "برنامه‌ی شمارش", "برنامه‌ی انبارگردانی (روزانه، هفتگی، ماهانه) تعریف نشده است.")
                else -> Kpi("D10.COUNTS", Dimension.D10, "شمارش عقب‌افتاده", due.count { it.due }.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 3), KpiQuality.OK,
                    "برنامه‌های شمارش", due.count { it.due }.toLong(), due.size.toLong())
            })
            val lots = safe { core.overview.lots(branch, to) }
            add(when {
                lots == null -> unknown("D10.EXPIRED", Dimension.D10, "کالای تاریخ‌گذشته در انبار", KpiUnit.COUNT, "تاریخ انقضا", "")
                lots.isEmpty() -> na("D10.EXPIRED", Dimension.D10, "کالای تاریخ‌گذشته در انبار", "کالای تاریخ‌دار در انبار نیست")
                else -> Kpi("D10.EXPIRED", Dimension.D10, "کالای تاریخ‌گذشته در انبار", lots.count { it.lot.daysLeft(to) < 0 }.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 3),
                    KpiQuality.OK, "بسته‌های تاریخ‌دار باز", lots.count { it.lot.daysLeft(to) < 0 }.toLong(), lots.size.toLong())
            })

            // ---- D11 kitchen: dishes sold with a recipe
            val rows = mix?.rows.orEmpty()
            val portions = rows.sumOf { it.portions.micros }
            add(if (portions <= 0) unknown("D11.RECIPES", Dimension.D11, "پرس‌های دارای رسپی", KpiUnit.PERCENT, "اقلام فروش", "در این دوره فروش غذا ثبت نشده است.")
                else rows.filter { it.unitCost != null }.sumOf { it.portions.micros }.let { withRecipe ->
                    Kpi("D11.RECIPES", Dimension.D11, "پرس‌های فروخته‌شده‌ی دارای رسپی و بها", bp(withRecipe, portions), KpiUnit.PERCENT, KpiTarget.higher(10_000, 7_000),
                        KpiQuality.OK, "پرس‌های غذاهای دارای رسپی ÷ همه‌ی پرس‌ها")
                })

            // ---- D13 labour
            val labor = pnl.ratios.labor
            add(when {
                sales <= 0 -> unknown("D13.LABOR", Dimension.D13, "هزینه‌ی نیروی کار به فروش", KpiUnit.PERCENT, "سود و زیان", "")
                labor == 0L -> unknown("D13.LABOR", Dimension.D13, "هزینه‌ی نیروی کار به فروش", KpiUnit.PERCENT, "سود و زیان",
                    "حقوق این دوره هنوز تأیید نشده؛ با تأیید حقوق ماه سنجیده می‌شود.")
                else -> Kpi("D13.LABOR", Dimension.D13, "هزینه‌ی نیروی کار به فروش", pnl.ratios.laborBp, KpiUnit.PERCENT, KpiTarget.lower(2_500, 3_500), KpiQuality.OK,
                    "حقوق و بیمه‌ی کارفرما ÷ فروش غذا", labor, sales)
            })
            val att = safe { core.reports.attendance(branch, from, to) }
            val minutes = att?.sumOf { it.workedMinutes } ?: 0L
            if (minutes > 0 && sales > 0) {
                val baseAtt = safe { core.reports.attendance(branch, baseFrom, baseTo) }?.sumOf { it.workedMinutes } ?: 0L
                val perHour = Ratio.mulDiv(sales, 60, minutes)
                val basePerHour = if (baseAtt > 0 && basePnl.ratios.sales > 0) Ratio.mulDiv(basePnl.ratios.sales, 60, baseAtt) else null
                add(Kpi("D13.PER_HOUR", Dimension.D13, "فروش به ازای هر ساعت کار", perHour, KpiUnit.RIAL_PER_HOUR, null, KpiQuality.OK, "فروش غذا ÷ ساعت‌های کارکرد ثبت‌شده",
                    sales, minutes / 60, basePerHour?.let { b -> Ratio.changeBp(b, perHour)?.let { "در برابر دوره‌ی قبل ${signedPercent(it)}" } } ?: ""))
            }

            // ---- D14 food safety: health cards
            val staff = employees
            add(when {
                staff == null || documents == null -> unknown("D14.HEALTH", Dimension.D14, "کارت بهداشت معتبر", KpiUnit.PERCENT, "مدارک کارکنان", "")
                staff.isEmpty() -> na("D14.HEALTH", Dimension.D14, "کارت بهداشت معتبر", "کارمندی ثبت نشده")
                else -> {
                    val bad = documents!!.filter { it.issue.kind == ir.sabou.payroll.StaffDocument.Kind.HEALTH_CARD && (it.issue.expired || it.issue.missing) }.map { it.employee.id }.toSet()
                    Kpi("D14.HEALTH", Dimension.D14, "کارکنان با کارت بهداشت معتبر", bp((staff.size - bad.size).toLong(), staff.size.toLong()), KpiUnit.PERCENT,
                        KpiTarget.higher(10_000, 8_000), KpiQuality.OK, "مدارک کارکنان", (staff.size - bad.size).toLong(), staff.size.toLong())
                }
            })

            // ---- D16 compliance
            if (core.taxStore.profile() != null) {
                val readiness = safe { core.moadian.readiness(to) }
                add(if (readiness == null) unknown("D16.TAX", Dimension.D16, "آمادگی سامانه‌ی مودیان", KpiUnit.COUNT, "مودیان", "")
                    else Kpi("D16.TAX", Dimension.D16, "کمبود اطلاعات سامانه‌ی مودیان", readiness.problems.size.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 3), KpiQuality.OK,
                        "آمادگی صورت‌حساب الکترونیکی", note = readiness.problems.take(2).joinToString(" ")))
            }
            if (!staff.isNullOrEmpty() && documents != null) {
                val contracts = documents!!.count { it.issue.kind == ir.sabou.payroll.StaffDocument.Kind.CONTRACT && (it.issue.expired || it.issue.missing) }
                add(Kpi("D16.CONTRACTS", Dimension.D16, "قرارداد کار منقضی یا ثبت‌نشده", contracts.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 3), KpiQuality.OK, "مدارک کارکنان",
                    contracts.toLong(), staff!!.size.toLong()))
            }

            // ---- D17 management discipline: what is late
            val late = backlog()
            add(Kpi("D17.BACKLOG", Dimension.D17, "کارهای عقب‌افتاده", late.sumOf { it.second }.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 8), KpiQuality.OK,
                "تأییدها، فاکتورها و چک‌های سررسیدگذشته، شمارش‌های در انتظار", note = late.filter { it.second > 0 }.joinToString("، ") { "${it.first} ${Fa.number(it.second.toLong())}" }))

            // ---- D18 data
            val expected = days.filter { d -> firstSale != null && d >= firstSale!! && d !in closedDays }
            add(if (expected.isEmpty()) unknown("D18.DAYS", Dimension.D18, "روزهای دارای فروش ثبت‌شده", KpiUnit.PERCENT, "فروش‌های روزانه", "در این دوره فروشی ثبت نشده است.")
                else expected.count { d -> salesByDay[d]?.any { it.status == SaleStatus.POSTED } == true }.toLong().let { n ->
                    Kpi("D18.DAYS", Dimension.D18, "روزهای دارای فروش ثبت‌شده", bp(n, expected.size.toLong()), KpiUnit.PERCENT, KpiTarget.higher(10_000, 8_000), KpiQuality.OK,
                        "روزهای کاری (بی روزهای تعطیل) از نخستین فروش", n, expected.size.toLong())
                })
            add(if (portions <= 0) unknown("D18.COSTED", Dimension.D18, "پرس‌های دارای بهای واقعی", KpiUnit.PERCENT, "اقلام فروش", "")
                else rows.sumOf { it.costedPortions.micros }.let { c ->
                    Kpi("D18.COSTED", Dimension.D18, "پرس‌های دارای بهای واقعی هنگام فروش", bp(c, portions), KpiUnit.PERCENT, KpiTarget.higher(10_000, 8_000), KpiQuality.OK,
                        "پرس‌هایی که بهای موادشان هنگام ثبت فروش ثبت شد ÷ همه‌ی پرس‌ها")
                })
            val forecast = safe { core.forecasts.sales(branch, to.plusDays(1), 1) }
            add(if (forecast?.wapeBp == null) unknown("D18.FORECAST", Dimension.D18, "خطای پیش‌بینی فروش (WAPE)", KpiUnit.PERCENT, "پیش‌بینی فروش",
                    "برای سنجش پیش‌بینی، دست‌کم چند هفته فروش ثبت‌شده لازم است.")
                else Kpi("D18.FORECAST", Dimension.D18, "خطای پیش‌بینی فروش (WAPE)", forecast.wapeBp, KpiUnit.PERCENT, KpiTarget.lower(1_500, 4_000), KpiQuality.OK,
                    "آزمون پیش‌بینی روی ۲۸ روز گذشته", note = forecast.biasBp?.let { "سوگیری ${signedPercent(it)}" } ?: ""))

            // ---- D19 risk: concentration
            add(when {
                cr == null -> unknown("D19.CUSTOMER", Dimension.D19, "سهم بزرگ‌ترین مشتری نسیه", KpiUnit.PERCENT, "مشتریان نسیه", "")
                cr.rows.isEmpty() || cr.topShareBp == null -> na("D19.CUSTOMER", Dimension.D19, "سهم بزرگ‌ترین مشتری نسیه", "فروش نسیه ندارید")
                else -> Kpi("D19.CUSTOMER", Dimension.D19, "سهم بزرگ‌ترین مشتری نسیه («${cr.rows.first().customer.name}») از فروش", cr.topShareBp, KpiUnit.PERCENT,
                    KpiTarget.lower(1_500, 4_000), KpiQuality.OK, "فروش نسیه‌ی ${Fa.number(CashFlows.CUSTOMER_DAYS.toLong())} روز گذشته", cr.rows.first().sales, cr.totalSales)
            })
            val bySupplier = periodInvoices.groupBy { it.supplierId }.mapValues { (_, l) -> l.sumOfExact { it.total.rial } }
            val purchased = bySupplier.values.sum()
            add(if (purchased <= 0) na("D19.SUPPLIER", Dimension.D19, "سهم بزرگ‌ترین تأمین‌کننده", "خریدی در دوره نیست")
                else bySupplier.maxBy { it.value }.let { (id, amount) ->
                    Kpi("D19.SUPPLIER", Dimension.D19, "سهم بزرگ‌ترین تأمین‌کننده («${core.suppliers.byId(id)?.name.orEmpty()}») از خرید", bp(amount, purchased), KpiUnit.PERCENT,
                        KpiTarget.lower(3_000, 6_000), KpiQuality.OK, "فاکتورهای خرید دوره", amount, purchased)
                })

            // ---- D20 growth
            val baseSales = basePnl.ratios.sales
            add(if (baseSales <= 0 || sales <= 0) unknown("D20.GROWTH", Dimension.D20, "رشد فروش نسبت به دوره‌ی قبل", KpiUnit.PERCENT, "سود و زیان",
                    "برای سنجش رشد، فروش دوره‌ی قبل (${Fa.date(baseFrom)} تا ${Fa.date(baseTo)}) لازم است.")
                else Kpi("D20.GROWTH", Dimension.D20, "رشد فروش نسبت به دوره‌ی قبل", Ratio.changeBp(baseSales, sales), KpiUnit.PERCENT, KpiTarget.higher(500, -1_000), KpiQuality.OK,
                    "فروش غذا در برابر ${Fa.date(baseFrom)} تا ${Fa.date(baseTo)}", sales, baseSales))
            // The same weeks a year earlier (364 days: the same weekdays), when the books go back that far.
            val lastYear = core.reports.profitAndLoss(from.plusDays(-364), to.plusDays(-364), branch).ratios.sales
            add(if (lastYear <= 0 || sales <= 0) unknown("D20.YOY", Dimension.D20, "رشد فروش نسبت به سال قبل", KpiUnit.PERCENT, "سود و زیان",
                    "فروش همین روزهای سال قبل در برنامه نیست؛ پس از یک سال کار با برنامه سنجیده می‌شود.")
                else Kpi("D20.YOY", Dimension.D20, "رشد فروش نسبت به همین روزهای سال قبل", Ratio.changeBp(lastYear, sales), KpiUnit.PERCENT, KpiTarget.higher(1_000, -1_000),
                    KpiQuality.OK, "فروش غذا در برابر ${Fa.date(from.plusDays(-364))} تا ${Fa.date(to.plusDays(-364))}", sales, lastYear))
        }

        val records by lazy { store.records(branch).filter { !it.voided } }
        fun recs(kind: FieldRecord.Kind) = records.filter { it.kind == kind }
        fun recordsIn(kind: FieldRecord.Kind) = recs(kind).filter { it.date in from..to }

        /** Indicators from the facts recorded by hand (ADR-0062). */
        fun recordKpis(): List<Kpi> = buildList {
            // D07: the last campaign that ended in the period, its daily sales against as many days just before it.
            val campaign = recs(FieldRecord.Kind.CAMPAIGN).filter { it.endDate != null && it.endDate in from..to }.maxByOrNull { it.endDate!! }
            add(if (campaign == null) unknown("D07.LIFT", Dimension.D07, "رشد فروش روزهای کمپین", KpiUnit.PERCENT, "کمپین‌ها",
                    "کمپینی که در این دوره تمام شده باشد ثبت نشده؛ کمپین‌ها را در «ثبت‌های روزانه» ثبت کنید.")
                else {
                    val len = campaign.endDate!!.epochDay - campaign.date.epochDay + 1
                    val during = core.reports.profitAndLoss(campaign.date, campaign.endDate, branch).ratios.sales
                    val before = core.reports.profitAndLoss(campaign.date.plusDays(-len), campaign.date.plusDays(-1), branch).ratios.sales
                    val lift = Ratio.changeBp(before, during)
                    if (lift == null) unknown("D07.LIFT", Dimension.D07, "رشد فروش روزهای کمپین", KpiUnit.PERCENT, "کمپین‌ها", "پیش از کمپین «${campaign.subject}» فروشی ثبت نشده است.")
                    else Kpi("D07.LIFT", Dimension.D07, "رشد فروش روزهای کمپین «${campaign.subject}»", lift, KpiUnit.PERCENT, KpiTarget.higher(1_000, 0), KpiQuality.OK,
                        "فروش غذا در روزهای کمپین در برابر همان تعداد روز پیش از آن", during, before,
                        "هزینه‌ی کمپین ${Fa.rial(campaign.value ?: 0)} ریال؛ رشد فروش مساوی سود نیست")
                })

            // D08: customers' ratings.
            val feedback = recordsIn(FieldRecord.Kind.FEEDBACK)
            if (feedback.size < MIN_FEEDBACK) {
                add(unknown("D08.RATING", Dimension.D08, "میانگین رضایت مشتری", KpiUnit.RATING, "نظر مشتریان",
                    "در این دوره ${Fa.number(feedback.size.toLong())} نظر ثبت شده؛ دست‌کم ${Fa.number(MIN_FEEDBACK.toLong())} نظر لازم است («ثبت‌های روزانه»)."))
            } else {
                val sum = feedback.sumOf { it.value!! }
                add(Kpi("D08.RATING", Dimension.D08, "میانگین رضایت مشتری", Ratio.mulDiv(sum, 100, feedback.size.toLong()), KpiUnit.RATING, KpiTarget.higher(450, 300),
                    KpiQuality.OK, "امتیاز ۱ تا ۵ نظرهای ثبت‌شده", sum, feedback.size.toLong()))
                val complaints = feedback.count { it.value!! <= 2 }.toLong()
                add(Kpi("D08.COMPLAINTS", Dimension.D08, "سهم نظرهای ناراضی (امتیاز ۱ یا ۲)", bp(complaints, feedback.size.toLong()), KpiUnit.PERCENT,
                    KpiTarget.lower(500, 2_000), KpiQuality.OK, "نظرهای ثبت‌شده", complaints, feedback.size.toLong()))
            }

            // D11: portions weighed against the recipe.
            val portions = recordsIn(FieldRecord.Kind.PORTION)
            if (portions.size >= MIN_PORTIONS) {
                val within = portions.count { kotlin.math.abs(it.value2!! - it.value!!) * 10_000 <= PORTION_TOLERANCE_BP * it.value }.toLong()
                add(Kpi("D11.PORTION", Dimension.D11, "پرس‌های نمونه در محدوده‌ی ±${Fa.percent(PORTION_TOLERANCE_BP)} رسپی", bp(within, portions.size.toLong()), KpiUnit.PERCENT,
                    KpiTarget.higher(9_000, 6_000), KpiQuality.OK, "وزن نمونه‌ی پرس در برابر رسپی", within, portions.size.toLong()))
            } else add(unknown("D11.PORTION", Dimension.D11, "پرس‌های نمونه در محدوده‌ی رسپی", KpiUnit.PERCENT, "وزن نمونه‌ی پرس",
                "در این دوره کمتر از ${Fa.number(MIN_PORTIONS.toLong())} پرس وزن شده؛ هفته‌ای چند پرس را وزن و ثبت کنید."))

            // D14: fridge and freezer temperatures.
            val temps = recordsIn(FieldRecord.Kind.TEMPERATURE)
            if (recs(FieldRecord.Kind.TEMPERATURE).isEmpty()) {
                add(unknown("D14.TEMP_DAYS", Dimension.D14, "روزهای دارای ثبت دما", KpiUnit.PERCENT, "دمای یخچال و فریزر",
                    "دمای یخچال و فریزر ثبت نمی‌شود؛ روزی یک بار در «ثبت‌های روزانه» ثبت کنید."))
            } else {
                val first = maxOf(from.epochDay, recs(FieldRecord.Kind.TEMPERATURE).minOf { it.date.epochDay })
                val expected = (to.epochDay - first + 1).coerceAtLeast(1)
                val withReading = temps.map { it.date }.distinct().size.toLong()
                add(Kpi("D14.TEMP_DAYS", Dimension.D14, "روزهای دارای ثبت دما", bp(withReading, expected), KpiUnit.PERCENT, KpiTarget.higher(10_000, 7_000), KpiQuality.OK,
                    "ثبت‌های دما از نخستین ثبت", withReading, expected))
                add(Kpi("D14.TEMP_BAD", Dimension.D14, "ثبت‌های دمای خارج از محدوده", temps.count { it.unsafe }.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 3), KpiQuality.OK,
                    "یخچال ۱- تا ۵ درجه، فریزر ۱۸- درجه یا سردتر", temps.count { it.unsafe }.toLong(), temps.size.toLong()))
            }

            // D15: breakdowns.
            add(if (recs(FieldRecord.Kind.BREAKDOWN).isEmpty()) unknown("D15.BREAKDOWNS", Dimension.D15, "خرابی تجهیزات", KpiUnit.COUNT, "خرابی‌ها",
                    "خرابی و تعمیر تجهیزات ثبت نمی‌شود؛ هر خرابی را در «ثبت‌های روزانه» ثبت کنید.")
                else recordsIn(FieldRecord.Kind.BREAKDOWN).let { b ->
                    Kpi("D15.BREAKDOWNS", Dimension.D15, "خرابی تجهیزات در دوره", b.size.toLong(), KpiUnit.COUNT, KpiTarget.lower(0, 4), KpiQuality.OK, "خرابی‌های ثبت‌شده",
                        note = "هزینه‌ی تعمیر ${Fa.rial(b.sumOf { it.value ?: 0 })} ریال")
                })

            // D16: permits — the latest record of each one.
            val permits = latestPermits()
            add(if (permits.isEmpty()) unknown("D16.PERMITS", Dimension.D16, "مجوزهای منقضی یا رو به پایان", KpiUnit.COUNT, "مجوزها",
                    "پروانه‌ی کسب و مجوزها ثبت نشده‌اند؛ هر کدام را با تاریخ اعتبارش ثبت کنید.")
                else permits.count { it.endDate!!.epochDay - to.epochDay <= PERMIT_WARN_DAYS }.toLong().let { n ->
                    Kpi("D16.PERMITS", Dimension.D16, "مجوزهای منقضی یا رو به پایان (${Fa.number(PERMIT_WARN_DAYS)} روز)", n, KpiUnit.COUNT, KpiTarget.lower(0, 2), KpiQuality.OK,
                        "مجوزهای ثبت‌شده", n, permits.size.toLong())
                })

            // D18 / D19: backups (ADR-0025) and their weekly verification, from the audit trail.
            val backups = core.backupHistory()
            add(backups.daysSince(backups.lastTaken)?.let { d ->
                Kpi("D18.BACKUP", Dimension.D18, "روز از آخرین پشتیبان", d, KpiUnit.DAYS, KpiTarget.lower(1, 7), KpiQuality.OK, "رویدادهای پشتیبان در دفتر ممیزی")
            } ?: unknown("D18.BACKUP", Dimension.D18, "روز از آخرین پشتیبان", KpiUnit.DAYS, "رویدادهای پشتیبان",
                "پشتیبانی از این دفاتر گرفته نشده؛ «پشتیبان خودکار شبانه» را روشن کنید."))
            add(backups.daysSince(backups.lastVerified)?.let { d ->
                Kpi("D19.RESTORE", Dimension.D19, "روز از آخرین آزمایش بازیابی پشتیبان", d, KpiUnit.DAYS, KpiTarget.lower(8, 45), KpiQuality.OK,
                    "باز کردن فایل پشتیبان و بررسی کامل زنجیره‌ی ممیزی")
            } ?: unknown("D19.RESTORE", Dimension.D19, "روز از آخرین آزمایش بازیابی پشتیبان", KpiUnit.DAYS, "آزمایش بازیابی",
                "هنوز هیچ پشتیبانی آزمایش نشده؛ پشتیبان خودکار هر هفته یکی را باز و بررسی می‌کند."))

            // D17: actions past their due date.
            val actions = store.actions(branch).filter { it.status != AssessmentAction.Status.FALSE_ALARM }
            if (actions.isNotEmpty()) {
                val late = actions.count { it.late(to) }.toLong()
                add(Kpi("D17.ACTIONS", Dimension.D17, "اقدام‌های گذشته از مهلت", late, KpiUnit.COUNT, KpiTarget.lower(0, 3), KpiQuality.OK, "اقدام‌های ثبت‌شده برای عارضه‌ها",
                    late, actions.count { it.status == AssessmentAction.Status.OPEN }.toLong()))
            }
        }

        fun latestPermits() = recs(FieldRecord.Kind.PERMIT).groupBy { it.subject }.values.map { l -> l.maxWith(compareBy({ it.date.epochDay }, { it.endDate?.epochDay ?: 0 })) }

        /** Findings from the facts recorded by hand. */
        fun recordFindings(): List<Finding> = buildList {
            // The latest reading of each fridge and freezer, if it is unsafe and recent.
            recs(FieldRecord.Kind.TEMPERATURE).filter { it.date <= to }.groupBy { it.subject }.values.map { it.last() }
                .filter { it.unsafe && to.epochDay - it.date.epochDay <= 1 }.forEach { t ->
                    add(Finding("TEMPERATURE:${t.subject}", Finding.Severity.CRITICAL, Dimension.D14, "دمای «${t.subject}» ${celsius(t.value!!)} است",
                        listOf("ثبت ${Fa.date(t.date)} به دست ${t.by}" + (if (t.note.isNotBlank()) ": ${t.note}" else "")), null, null,
                        listOf("در باز مانده", "خرابی کمپرسور یا ترموستات", "پر بودن بیش از اندازه"),
                        "مواد را به جای سرد دیگری ببرید، دستگاه را بررسی کنید و دوباره دما را ثبت کنید. مواد مشکوک را مصرف نکنید.", Finding.Confidence.HIGH))
                }
            latestPermits().forEach { p ->
                val left = p.endDate!!.epochDay - to.epochDay
                if (left < 0) add(Finding("PERMIT:${p.subject}", Finding.Severity.CRITICAL, Dimension.D16, "«${p.subject}» از ${Fa.date(p.endDate)} منقضی است",
                    listOf("آخرین ثبت: ${Fa.date(p.date)}"), null, null, emptyList(), "برای تمدید اقدام کنید و مجوز تازه را ثبت کنید.", Finding.Confidence.HIGH))
                else if (left <= PERMIT_URGENT_DAYS) add(Finding("PERMIT:${p.subject}", Finding.Severity.HIGH, Dimension.D16,
                    "«${p.subject}» ${Fa.number(left)} روز دیگر منقضی می‌شود", listOf("اعتبار تا ${Fa.date(p.endDate)}"), null, null, emptyList(),
                    "تمدید را از همین هفته شروع کنید.", Finding.Confidence.HIGH))
            }
            recordsIn(FieldRecord.Kind.BREAKDOWN).groupBy { it.subject }.filter { it.value.size >= 2 }.forEach { (equipment, list) ->
                val cost = list.sumOf { it.value ?: 0 }
                add(Finding("BREAKDOWN:$equipment", Finding.Severity.MEDIUM, Dimension.D15, "«$equipment» ${Fa.number(list.size.toLong())} بار در این دوره خراب شد",
                    list.map { "${Fa.date(it.date)}" + (it.value?.let { v -> " · ${Fa.rial(v)} ریال" } ?: "") + (if (it.note.isNotBlank()) " · ${it.note}" else "") },
                    cost, cost, listOf("تعمیر ناقص", "فرسودگی", "استفاده‌ی نادرست"),
                    "هزینه‌ی تعمیرهای پی‌درپی را با خرید دستگاه تازه یا قرارداد سرویس دوره‌ای مقایسه کنید.", Finding.Confidence.MEDIUM))
            }
            recordsIn(FieldRecord.Kind.PORTION).takeIf { it.size >= MIN_PORTIONS }?.let { l ->
                val over = Ratio.mulDivSigned(l.sumOf { it.value2!! - it.value!! }, 10_000, l.sumOf { it.value!! })
                if (over >= PORTION_TOLERANCE_BP) add(Finding("PORTIONS_HEAVY", Finding.Severity.MEDIUM, Dimension.D11, "پرس‌ها به‌طور میانگین ${Fa.percent(over)} سنگین‌تر از رسپی‌اند",
                    l.sortedByDescending { (it.value2!! - it.value!!) * 10_000 / it.value }.take(3).map { "«${it.subject}»: رسپی ${Fa.number(it.value!!)} گرم، وزن‌شده ${Fa.number(it.value2!!)} گرم" },
                    null, null, listOf("نبود ترازو یا پیمانه در خط پخت", "رسپی به‌روز نیست"), "ترازو یا پیمانه در خط پخت بگذارید و رسپی را با آشپزها مرور کنید.", Finding.Confidence.MEDIUM))
            }
            recordsIn(FieldRecord.Kind.FEEDBACK).takeIf { it.size >= MIN_FEEDBACK }?.let { l ->
                val unhappy = l.filter { it.value!! <= 2 }
                if (unhappy.size * 10_000L >= COMPLAINT_BP * l.size) add(Finding("COMPLAINTS", Finding.Severity.MEDIUM, Dimension.D08,
                    "${Fa.number(unhappy.size.toLong())} نظر ناراضی از ${Fa.number(l.size.toLong())}", unhappy.filter { it.note.isNotBlank() }.takeLast(3).map { "${Fa.date(it.date)}: ${it.note}" },
                    null, null, emptyList(), "شکایت‌های تکراری را با سرآشپز و سالن مرور کنید و با مشتریان ناراضی تماس بگیرید.", Finding.Confidence.MEDIUM))
            }
            val backups = core.backupHistory()
            if (backups.lastFailure != null) add(Finding("BACKUP_UNREADABLE", Finding.Severity.CRITICAL, Dimension.D19, "آخرین آزمایش بازیابی پشتیبان ناموفق بود",
                listOf(backups.lastFailure), null, null, listOf("فایل ناقص یا خراب در پوشه‌ی مقصد", "رمز پشتیبان عوض شده"),
                "همین حالا یک پشتیبان دستی بگیرید و پوشه‌ی پشتیبان خودکار را بررسی کنید.", Finding.Confidence.HIGH))
            val age = backups.daysSince(backups.lastTaken)
            if ((age == null || age > BACKUP_LATE_DAYS) && salesByDay.values.any { l -> l.any { it.status == SaleStatus.POSTED } }) add(Finding("BACKUP_OLD", Finding.Severity.HIGH, Dimension.D18,
                age?.let { "${Fa.number(it)} روز است پشتیبان گرفته نشده" } ?: "از این دفاتر هیچ پشتیبانی نیست",
                listOf("با گم شدن یا خرابی گوشی، همه‌ی ثبت‌های پس از آخرین پشتیبان از دست می‌رود."), null, null, emptyList(),
                "«پشتیبان خودکار شبانه» را در «من ← پشتیبان» روی پوشه‌ای بیرون از این گوشی روشن کنید.", Finding.Confidence.HIGH))
            store.actions(branch).filter { it.late(to) }.takeIf { it.isNotEmpty() }?.let { late ->
                add(Finding("ACTIONS_LATE", Finding.Severity.LOW, Dimension.D17, "${Fa.number(late.size.toLong())} اقدام از مهلتش گذشته",
                    late.take(5).map { "${it.title}: ${it.owner} · مهلت ${Fa.date(it.due)}" }, null, null, emptyList(), "وضعیت این اقدام‌ها را روشن کنید یا مهلت تازه بگذارید.", Finding.Confidence.HIGH))
            }
        }

        /** What is late, by kind, for D17. */
        fun backlog(): List<Pair<String, Int>> {
            val approvals = safe { core.books.pendingApprovals() }?.count { it.invoice.invoice.scope == branch } ?: 0
            // One read of what is still owed on each invoice (not one per invoice).
            val owed = safe { core.overview.invoices() }?.filter { !it.outstanding.isZero }?.map { it.invoice.id }?.toSet().orEmpty()
            val bills = invoices.count { it.dueDate < to && it.id in owed }
            val cheques = core.cheques.all().count { c ->
                c.scope == branch && c.dueDate < to &&
                    ((c.direction == ChequeDirection.RECEIVED && c.status == ChequeStatus.IN_HAND) || (c.direction == ChequeDirection.ISSUED && c.status == ChequeStatus.ISSUED))
            }
            val locations = core.locations.all().filter { it.scope == branch }.map { it.id }.toSet()
            val counts = core.stockCounts.all().count { it.status == ir.sabou.inventory.CountStatus.PENDING && it.locationId in locations }
            return listOf("تأیید فاکتور" to approvals, "فاکتور سررسیدگذشته" to bills, "چک سررسیدگذشته" to cheques, "شمارش در انتظار تأیید" to counts)
        }

        fun quality(): List<QualityPart> = buildList {
            val expected = days.filter { d -> firstSale != null && d >= firstSale!! && d !in closedDays }
            if (expected.isNotEmpty()) {
                val n = expected.count { d -> salesByDay[d]?.any { it.status == SaleStatus.POSTED } == true }
                add(QualityPart("کامل بودن فروش روزانه", bp(n.toLong(), expected.size.toLong()), "${Fa.number(n.toLong())} از ${Fa.number(expected.size.toLong())} روز کاری"))
            }
            val rows = mix?.rows.orEmpty(); val portions = rows.sumOf { it.portions.micros }
            if (portions > 0) {
                add(QualityPart("رسپی و بهای غذاها", bp(rows.filter { it.unitCost != null }.sumOf { it.portions.micros }, portions), "سهم پرس‌های دارای رسپی"))
                add(QualityPart("بهای واقعی هنگام فروش", bp(rows.sumOf { it.costedPortions.micros }, portions), "سهم پرس‌هایی که بهای واقعی دارند"))
            }
            if (!counted && (mix?.rows?.isNotEmpty() == true)) add(QualityPart("انبارگردانی", 0, "شمارشی در دوره نیست"))
            usage?.takeIf { it.rows.isNotEmpty() }?.let { u ->
                add(QualityPart("انبارگردانی", bp(u.countedRows.toLong(), u.rows.size.toLong()), if (u.measuredByCount) "${Fa.number(u.countedRows.toLong())} از ${Fa.number(u.rows.size.toLong())} قلم شمرده شده" else "شمارشی در دوره نیست"))
            }
            if (pnl.openShortages > 0) add(QualityPart("کسری‌های تعیین‌تکلیف‌شده", 0, "${Fa.number(pnl.openShortages.toLong())} کسری باز؛ بهای غذا قطعی نیست"))
        }

        fun findings(kpis: List<Kpi>): List<Finding> = baseFindings(kpis) + recordFindings()

        fun baseFindings(kpis: List<Kpi>): List<Finding> = buildList {
            fun kpi(id: String) = kpis.firstOrNull { it.id == id }

            kpi("D01.TRIAL")?.takeIf { (it.value ?: 0) != 0L }?.let {
                add(Finding("BOOKS_OFF_BALANCE", Finding.Severity.CRITICAL, Dimension.D01, "دفاتر تراز نیست",
                    listOf("جمع مانده‌ی همه‌ی حساب‌ها ${Fa.rial(it.value!!)} ریال است و باید صفر باشد."), it.value, it.value,
                    listOf("سندی ناقص یا دست‌کاری‌شده در پایگاه داده"), "پیش از هر تصمیمی، پشتیبان بگیرید و با پشتیبانی نرم‌افزار بررسی کنید.", Finding.Confidence.HIGH))
            }

            kpi("D14.HEALTH")?.takeIf { (it.score ?: 100) < 100 }?.let {
                val names = documents.orEmpty().filter { d -> d.issue.kind == ir.sabou.payroll.StaffDocument.Kind.HEALTH_CARD && (d.issue.expired || d.issue.missing) }
                add(Finding("HEALTH_CARDS", Finding.Severity.CRITICAL, Dimension.D14, "کارت بهداشت ${Fa.number(names.size.toLong())} نفر معتبر نیست",
                    names.take(5).map { d -> "${d.employee.name}: " + if (d.issue.missing) "کارت ثبت نشده" else "منقضی از ${d.issue.document?.expiresOn?.let(Fa::date) ?: "—"}" },
                    null, null, emptyList(), "تا تمدید کارت بهداشت، این افراد با غذا کار نکنند؛ مدرک تازه را در «کارکنان» ثبت کنید.", Finding.Confidence.HIGH))
            }

            // Profit fell: the bridge names the causes, each to the Rial.
            // The profit bridge is read only when the books show a drop worth explaining (same profit figures).
            val drop = basePnl.totals.profit > 0 && pnl.totals.profit < basePnl.totals.profit &&
                Ratio.mulDiv(basePnl.totals.profit - pnl.totals.profit, 10_000, basePnl.totals.profit) >= PROFIT_DROP_BP
            val b = if (drop) bridge else null
            if (b != null && b.base.profit > 0 && b.change < 0 && Ratio.mulDiv(-b.change, 10_000, b.base.profit) >= PROFIT_DROP_BP) {
                val losses = b.steps.filter { it.amount < 0 && it.kind != BridgeStep.Kind.ROUNDING }.sortedBy { it.amount }
                val sure = !b.provisional && (b.current.costedBp ?: 0) >= 9_000
                add(Finding("PROFIT_DROP", Finding.Severity.HIGH, Dimension.D02,
                    "سود ${Fa.percent(Ratio.mulDiv(-b.change, 10_000, b.base.profit))} کمتر از دوره‌ی قبل",
                    listOf("سود ${Fa.rial(b.base.profit)} ← ${Fa.rial(b.current.profit)} ریال (${Fa.date(baseFrom)} تا ${Fa.date(baseTo)} در برابر این دوره)"),
                    -b.change, -b.change,
                    losses.take(3).map { s -> "${ProfitBridges.stepName(s.kind)}: ${Fa.rial(s.amount)} ریال" + s.details.take(2).joinToString("، ", " (", ")") { "${it.name} ${Fa.rial(it.amount)}" }.takeIf { s.details.isNotEmpty() }.orEmpty() },
                    losses.firstOrNull()?.let { actionFor(it.kind) } ?: "گزارش «چرا سود تغییر کرد» را ببینید.",
                    if (sure) Finding.Confidence.HIGH else Finding.Confidence.MEDIUM))
            }

            // Food cost above target.
            kpi("D03.FOOD")?.takeIf { it.value != null && it.value > foodGood + FOOD_COST_SLACK_BP }?.let { k ->
                val excess = Ratio.mulDiv(k.value!! - foodGood, sales, 10_000)
                val dishes = safe { core.reports.foodCosts(branch, to) }.orEmpty().filter { it.costBp > foodGood }.sortedByDescending { it.costBp }.take(3)
                val rises = priceChanges.orEmpty().sortedByDescending { it.changeBp ?: 0 }.take(3)
                add(Finding("FOOD_COST", if (k.value > foodGood + 500) Finding.Severity.HIGH else Finding.Severity.MEDIUM, Dimension.D03,
                    "بهای غذا ${Fa.percent(k.value)} فروش؛ هدف ${Fa.percent(foodGood)}",
                    listOf("${Fa.rial(k.numerator ?: 0)} ریال بهای غذا بر ${Fa.rial(k.denominator ?: 0)} ریال فروش غذا"),
                    excess, excess,
                    buildList {
                        dishes.forEach { add("«${it.item.name}»: بهای مواد ${Fa.percent(it.costBp)} قیمت منو") }
                        rises.forEach { add("«${it.item.name}» گران‌تر ${signedPercent(it.changeBp ?: 0)} از ${it.supplier}") }
                        kpi("D03.WASTE")?.value?.takeIf { it > 100 }?.let { add("ضایعات ${Fa.percent(it)} فروش") }
                        kpi("D03.VARIANCE")?.value?.takeIf { it > 50 }?.let { add("مصرف بی‌توضیح ${Fa.percent(it)} فروش") }
                    },
                    "قیمت منو یا رسپی پرهزینه‌ترین غذاها را بازبینی کنید و برای اقلام گران‌شده قیمت تأمین‌کننده‌ی دیگر را بگیرید.",
                    if (pnl.provisional) Finding.Confidence.MEDIUM else Finding.Confidence.HIGH))
            }

            // Stock counts found more missing than the recipes explain.
            val u = usage
            if (u != null && u.measuredByCount && sales > 0 && u.unexplainedValue > 0 && bp(u.unexplainedValue, sales) >= UNEXPLAINED_BP) {
                val top = u.rows.filter { it.unexplained.value > 0 }.sortedByDescending { it.unexplained.value }.take(3)
                add(Finding("UNEXPLAINED_USAGE", Finding.Severity.MEDIUM, Dimension.D10, "مصرف بی‌توضیح ${Fa.rial(u.unexplainedValue)} ریال",
                    top.map { "«${it.item.name}»: ${Fa.quantity(ir.sabou.kernel.Quantity.of(it.unexplained.quantity))} (${Fa.rial(it.unexplained.value)} ریال)" },
                    u.unexplainedValue, u.unexplainedValue,
                    listOf("ضایعات یا پذیرایی ثبت‌نشده", "پرس بزرگ‌تر از رسپی", "خطای شمارش یا خرید ثبت‌نشده", "خروج کالای ثبت‌نشده"),
                    "وزن چند پرس را نمونه‌گیری کنید، همین اقلام را دوباره بشمارید و ضایعات را روزانه ثبت کنید. این گزارش علت قطعی را نمی‌داند.",
                    Finding.Confidence.MEDIUM))
            }

            // Cash runs short.
            cash?.takeIf { it.assumptions.liquidBp != null }?.firstShortWeek?.let { w ->
                val low = cash!!.lowest!!
                val outs = low.byKind.filter { it.value < 0 }.entries.sortedBy { it.value }.take(3)
                add(Finding("CASH_SHORT", Finding.Severity.HIGH, Dimension.D04, "کمبود نقدینگی در هفته‌ی ${Fa.number(w.index.toLong())} (تا ${Fa.date(w.to)})",
                    listOf("کمترین مانده‌ی پیش‌بینی‌شده ${Fa.rial(low.closing)} ریال در هفته‌ی ${Fa.number(low.index.toLong())}"),
                    null, -low.closing,
                    outs.map { "${cashKind(it.key)}: ${Fa.rial(-it.value)} ریال" } +
                        listOfNotNull(customers?.rows?.sumOf { it.overdue }?.takeIf { it > 0 }?.let { "طلب سررسیدگذشته‌ی وصول‌نشده ${Fa.rial(it)} ریال" }),
                    "طلب‌های سررسیدگذشته را وصول کنید و پرداخت‌های بزرگ آن هفته را با تأمین‌کننده جابه‌جا کنید؛ «پیش‌بینی نقدینگی» را با سناریوی محتاط هم ببینید.",
                    Finding.Confidence.MEDIUM))
            }

            // Customers owe past their due date.
            customers?.rows?.filter { it.overdue > 0 }?.sortedByDescending { it.overdue }?.takeIf { it.isNotEmpty() }?.let { late ->
                val total = late.sumOf { it.overdue }
                add(Finding("RECEIVABLES_LATE", if ((kpi("D04.OVERDUE")?.value ?: 0) >= 3_000) Finding.Severity.HIGH else Finding.Severity.MEDIUM, Dimension.D04,
                    "طلب سررسیدگذشته ${Fa.rial(total)} ریال", late.take(3).map { "${it.customer.name}: ${Fa.rial(it.overdue)} ریال" + (it.delayDays?.let { d -> " · معمولاً ${Fa.number(d.toLong())} روز دیرتر" } ?: "") },
                    total, total, emptyList(), "با این مشتریان تماس بگیرید و تا وصول، فروش نسیه‌ی تازه را محدود کنید.", Finding.Confidence.HIGH))
            }

            // One customer is too much of the business.
            kpi("D19.CUSTOMER")?.takeIf { (it.value ?: 0) >= CONCENTRATION_BP }?.let { k ->
                add(Finding("CUSTOMER_CONCENTRATION", Finding.Severity.MEDIUM, Dimension.D19, k.title.replace(" از فروش", "") + " ${Fa.percent(k.value!!)}",
                    listOf("${Fa.rial(k.numerator ?: 0)} از ${Fa.rial(k.denominator ?: 0)} ریال فروش ${Fa.number(CashFlows.CUSTOMER_DAYS.toLong())} روز گذشته"),
                    null, k.numerator, listOf("از دست رفتن یا دیرکرد این مشتری بخش بزرگی از فروش و نقدینگی را می‌برد."),
                    "قرارداد و ضمانت این مشتری را مرور کنید و فروش به مشتریان دیگر را گسترش دهید.", Finding.Confidence.HIGH))
            }

            // Menu: what to do with each class (a suggestion; the owner decides).
            engineering?.rows?.filter { it.menuClass == MenuClass.DOG || it.menuClass == MenuClass.PLOWHORSE }?.sortedByDescending { it.mix.shareBp }?.take(5)?.takeIf { it.isNotEmpty() }?.let { rows ->
                add(Finding("MENU", Finding.Severity.LOW, Dimension.D06, "پیشنهاد بازنگری منو برای ${Fa.number(rows.size.toLong())} غذا",
                    rows.map { "«${it.mix.name}»: ${menuClassName(it.menuClass!!)} · سهم فروش ${Fa.percent(it.mix.shareBp)}" + (it.mix.unitMargin?.let { m -> " · سود هر پرس ${Fa.rial(m)}" } ?: "") },
                    null, null, emptyList(),
                    rows.joinToString("؛ ") { "«${it.mix.name}»: ${menuDecision(it.menuClass!!)}" }, Finding.Confidence.MEDIUM))
            }

            // A channel that costs a fifth of what it sells (ADR-0062).
            mix?.channels.orEmpty().filter { r -> r.channel != null && (settings.channelCosts[r.channel] ?: 0) >= CHANNEL_COST_BP && r.net.rial > 0 }.forEach { r ->
                val cost = channelCost(listOf(r))
                add(Finding("CHANNEL_COST:${r.channel!!.name}", Finding.Severity.MEDIUM, Dimension.D05,
                    "${channelName(r.channel)}: ${Fa.percent(settings.channelCosts.getValue(r.channel))} فروشش هزینه دارد",
                    listOf("فروش این کانال ${Fa.rial(r.net)} ریال (${Fa.percent(r.shareBp)} فروش)؛ هزینه‌ی برآوردی ${Fa.rial(cost)} ریال"), cost, cost,
                    listOf("کمیسیون سامانه‌ی سفارش", "هزینه‌ی پیک"), "قیمت منوی این کانال را جدا ببینید یا کمیسیون را دوباره مذاکره کنید؛ سفارش مستقیم را تشویق کنید.",
                    Finding.Confidence.MEDIUM))
            }

            // Suppliers raised prices.
            priceChanges?.takeIf { it.isNotEmpty() }?.let { rises ->
                val worst = rises.groupBy { it.item.id }.values.map { l -> l.maxBy { it.changeBp ?: 0 } }.sortedByDescending { it.changeBp ?: 0 }
                add(Finding("PRICE_RISES", if (worst.size >= 5) Finding.Severity.MEDIUM else Finding.Severity.LOW, Dimension.D09,
                    "${Fa.number(worst.size.toLong())} قلم بیش از ${Fa.percent(PRICE_RISE_BP)} گران‌تر خریده شد",
                    worst.take(5).map { "«${it.item.name}»: ${Fa.rial(it.previousPrice)} ← ${Fa.rial(it.price)} (${signedPercent(it.changeBp ?: 0)}) · ${it.supplier}" },
                    null, null, emptyList(), "قیمت تأمین‌کننده‌ی دیگر را بگیرید و اگر گرانی ماندنی است، رسپی یا قیمت غذاهای وابسته را بازبینی کنید.", Finding.Confidence.HIGH))
            }

            // Expired goods in store.
            kpi("D10.EXPIRED")?.takeIf { (it.value ?: 0) > 0 }?.let { k ->
                add(Finding("EXPIRED_STOCK", Finding.Severity.HIGH, Dimension.D14, "${Fa.number(k.value!!)} بسته‌ی تاریخ‌گذشته در انبار",
                    core.overview.lots(branch, to).filter { it.lot.daysLeft(to) < 0 }.take(5).map { "«${it.item.name}» ${Fa.quantity(it.lot.remaining)} در ${it.location.name} · ${Fa.date(it.lot.expiresOn)}" },
                    null, null, emptyList(), "این کالاها مصرف نشوند؛ ضایعات ثبت کنید تا موجودی و بهای غذا درست شود.", Finding.Confidence.HIGH))
            }

            // Late work.
            kpi("D17.BACKLOG")?.takeIf { (it.value ?: 0) > 0 }?.let { k ->
                add(Finding("BACKLOG", if ((k.value ?: 0) >= 8) Finding.Severity.MEDIUM else Finding.Severity.LOW, Dimension.D17, "${Fa.number(k.value!!)} کار عقب‌افتاده",
                    listOf(k.note), null, null, emptyList(), "تأییدها و پرداخت‌های سررسیدگذشته را امروز تعیین تکلیف کنید.", Finding.Confidence.HIGH))
            }

            // The period's figures are not final.
            kpi("D01.OPEN")?.takeIf { (it.value ?: 0) > 0 }?.let { k ->
                add(Finding("OPEN_SALES", Finding.Severity.LOW, Dimension.D01, "ارقام دوره هنوز قطعی نیست", listOf(k.note), null, null, emptyList(),
                    "پیش‌نویس‌ها را ثبت نهایی کنید و روزهای فروش را ببندید.", Finding.Confidence.HIGH))
            }
            if (pnl.openShortages > 0) {
                add(Finding("SHORTAGES", Finding.Severity.MEDIUM, Dimension.D10, "${Fa.number(pnl.openShortages.toLong())} کسری انبار باز",
                    listOf("فروش بیش از موجودی دفتری مصرف کرده؛ بهای آن هنوز در سود نیامده و سود بیش از واقع است."), null, null, emptyList(),
                    "خرید ثبت‌نشده را ثبت کنید یا انبارگردانی کنید تا کسری بسته شود.", Finding.Confidence.HIGH))
            }
        }
    }

    private fun bp(part: Long, whole: Long): Long = if (whole <= 0) 0 else Ratio.mulDivSigned(part, 10_000, whole)

    private fun unknown(id: String, d: Dimension, title: String, unit: KpiUnit, source: String, request: String) =
        Kpi(id, d, title, null, unit, null, KpiQuality.UNKNOWN, source, note = request)

    private fun na(id: String, d: Dimension, title: String, why: String) = Kpi(id, d, title, null, KpiUnit.COUNT, null, KpiQuality.NOT_APPLICABLE, "", note = why)

    companion object {
        /** Bumped whenever an indicator, target or rule changes meaning (shown on every report). */
        const val RULES_VERSION = 2
        const val SETTINGS_KEY = "assessment.settings"

        /** Every indicator whose target the owner may set, with the direction that is better. */
        val DIRECTIONS: Map<String, KpiTarget.Better> = (
            listOf("D02.MARGIN", "D02.GROSS", "D04.CASH", "D09.ONTIME", "D09.FULL", "D11.RECIPES", "D14.HEALTH", "D18.DAYS", "D18.COSTED", "D20.GROWTH", "D20.YOY",
                "D07.LIFT", "D08.RATING", "D11.PORTION", "D14.TEMP_DAYS")
                .associateWith { KpiTarget.Better.HIGHER } +
            listOf("D01.TRIAL", "D01.OPEN", "D01.BANK", "D03.FOOD", "D03.PRIME", "D03.WASTE", "D03.VARIANCE", "D04.OVERDUE", "D04.DSO", "D06.DOGS", "D06.OVER",
                "D09.PRICES", "D09.MATCH", "D10.SHORT", "D10.COUNTS", "D10.EXPIRED", "D13.LABOR", "D16.TAX", "D16.CONTRACTS", "D17.BACKLOG", "D18.FORECAST",
                "D19.CUSTOMER", "D19.SUPPLIER", "D08.COMPLAINTS", "D14.TEMP_BAD", "D15.BREAKDOWNS", "D16.PERMITS", "D17.ACTIONS", "D18.BACKUP", "D19.RESTORE", "D05.COMMISSION").associateWith { KpiTarget.Better.LOWER })

        fun better(id: String): KpiTarget.Better = DIRECTIONS[id] ?: throw DomainException(DomainError.InvalidInput("target", id))
        /** No overall score below half of the weight measured: the card says «داده‌ی کافی نیست» instead. */
        const val MIN_COVERAGE_BP = 5_000L
        const val DEFAULT_FOOD_COST_BP = 3_200L
        const val FOOD_COST_SLACK_BP = 200L
        const val PROFIT_DROP_BP = 1_000L
        const val PRICE_RISE_BP = 1_000L
        const val UNEXPLAINED_BP = 100L
        const val CONCENTRATION_BP = 3_000L
        const val FALSE_ALARM_DAYS = 30L
        const val ACTION_DAYS = 90L
        const val MIN_FEEDBACK = 5
        const val MIN_PORTIONS = 3
        const val PORTION_TOLERANCE_BP = 500L
        const val COMPLAINT_BP = 2_000L
        const val PERMIT_WARN_DAYS = 30L
        const val PERMIT_URGENT_DAYS = 14L
        const val BACKUP_LATE_DAYS = 7L
        const val CHANNEL_COST_BP = 2_000L
        const val MAX_PORTION_GRAMS = 10_000L

        /** The indicator by which an action on a finding is measured. */
        val FINDING_KPI = mapOf(
            "BOOKS_OFF_BALANCE" to "D01.TRIAL", "OPEN_SALES" to "D01.OPEN", "PROFIT_DROP" to "D02.MARGIN", "FOOD_COST" to "D03.FOOD",
            "UNEXPLAINED_USAGE" to "D03.VARIANCE", "CASH_SHORT" to "D04.CASH", "RECEIVABLES_LATE" to "D04.OVERDUE", "MENU" to "D06.DOGS",
            "PRICE_RISES" to "D09.PRICES", "SHORTAGES" to "D10.SHORT", "EXPIRED_STOCK" to "D10.EXPIRED", "PORTIONS_HEAVY" to "D11.PORTION",
            "HEALTH_CARDS" to "D14.HEALTH", "BACKLOG" to "D17.BACKLOG", "ACTIONS_LATE" to "D17.ACTIONS", "CUSTOMER_CONCENTRATION" to "D19.CUSTOMER",
            "COMPLAINTS" to "D08.COMPLAINTS", "BACKUP_OLD" to "D18.BACKUP", "BACKUP_UNREADABLE" to "D19.RESTORE",
        )

        fun celsius(tenths: Long): String = (if (tenths < 0) "−" else "") + Fa.number(kotlin.math.abs(tenths) / 10) +
            (if (kotlin.math.abs(tenths) % 10 != 0L) "٫" + Fa.number(kotlin.math.abs(tenths) % 10) else "") + " درجه"

        /** What the app does not record; each dimension says what would measure it. */
        val NOT_RECORDED = listOf(
            Dimension.D07 to "کمپین‌ها ثبت نمی‌شوند؛ هر کمپین را با تاریخ و هزینه‌اش در «ثبت‌های روزانه» ثبت کنید.",
            Dimension.D08 to "نظر مشتریان ثبت نمی‌شود؛ امتیاز ۱ تا ۵ هر نظر را در «ثبت‌های روزانه» ثبت کنید.",
            Dimension.D12 to "زمان آماده‌سازی و تحویل سفارش از صندوق وارد نمی‌شود؛ بدون آن سرعت سرویس سنجیده نمی‌شود.",
            Dimension.D14 to "دمای یخچال و فریزر ثبت نمی‌شود؛ روزی یک بار در «ثبت‌های روزانه» ثبت کنید.",
            Dimension.D15 to "خرابی و تعمیر تجهیزات ثبت نمی‌شود؛ هر خرابی را در «ثبت‌های روزانه» ثبت کنید.",
        )

        fun channelName(c: ir.sabou.sales.SalesChannel?) = ReportTables.channelName(c)

        fun menuClassName(c: MenuClass) = when (c) {
            MenuClass.STAR -> "ستاره"; MenuClass.PLOWHORSE -> "پرفروش کم‌سود"; MenuClass.PUZZLE -> "پرسود کم‌فروش"; MenuClass.DOG -> "کم‌فروش کم‌سود"
        }

        /** The menu-engineering decision for each class: promote, reprice/reformulate, retire. */
        fun menuDecision(c: MenuClass) = when (c) {
            MenuClass.STAR -> "حفظ کیفیت و برجسته کردن"
            MenuClass.PLOWHORSE -> "افزایش تدریجی قیمت یا کاهش بهای رسپی"
            MenuClass.PUZZLE -> "معرفی بیشتر و جای بهتر در منو"
            MenuClass.DOG -> "حذف، یا جایگزینی با غذای پرسودتر"
        }

        fun actionFor(kind: BridgeStep.Kind) = when (kind) {
            BridgeStep.Kind.VOLUME -> "فروش کم شده: روزها و کانال‌های افت را در «پیش‌بینی فروش» و «اقلام پرفروش» ببینید."
            BridgeStep.Kind.MIX -> "سهم غذاهای کم‌سود بیشتر شده: غذاهای پرسود را برجسته‌تر کنید."
            BridgeStep.Kind.PRICES -> "قیمت فروش پایین آمده یا تخفیف در قیمت رفته: قیمت‌های منو را بازبینی کنید."
            BridgeStep.Kind.INGREDIENT_COSTS -> "بهای مواد بالا رفته: قیمت تأمین‌کنندگان را مقایسه و رسپی یا قیمت منو را بازبینی کنید."
            BridgeStep.Kind.DISCOUNTS -> "تخفیف بیشتر شده: دلیل تخفیف‌ها و کسی که می‌دهد را بازبینی کنید."
            BridgeStep.Kind.UNTRACED_COST -> "کسری‌های انبار و فروش بی‌بها: خریدها را کامل ثبت و انبارگردانی کنید."
            BridgeStep.Kind.FOOD_LOSSES -> "ضایعات و مغایرت انبار بالا رفته: ضایعات روزانه و شمارش را پیگیری کنید."
            BridgeStep.Kind.LABOR -> "هزینه‌ی نیروی کار بالا رفته: شیفت‌ها را با فروش هر ساعت تطبیق دهید."
            BridgeStep.Kind.OTHER_COSTS -> "سایر هزینه‌ها بالا رفته: بزرگ‌ترین حساب‌ها را در «سود و زیان» ببینید."
            BridgeStep.Kind.OTHER_INCOME -> "درآمد سرویس و سایر درآمدها کم شده است."
            BridgeStep.Kind.ROUNDING -> ""
        }

        fun cashKind(k: CashItem.Kind) = when (k) {
            CashItem.Kind.CASH_SALES -> "فروش نقدی"; CashItem.Kind.CREDIT_SALES -> "فروش نسیه"; CashItem.Kind.RECEIVABLE -> "وصول طلب"
            CashItem.Kind.CHEQUE_IN -> "چک دریافتی"; CashItem.Kind.SUPPLIER -> "فاکتور تأمین‌کنندگان"; CashItem.Kind.CHEQUE_OUT -> "چک پرداختی"
            CashItem.Kind.PURCHASES -> "خریدهای آینده"; CashItem.Kind.PAYROLL -> "حقوق"; CashItem.Kind.PAYROLL_DUES -> "بیمه و مالیات حقوق"
            CashItem.Kind.OVERHEADS -> "هزینه‌های جاری"
        }

        /** The value as the report shows it; «نامعلوم» without one. */
        fun valueText(k: Kpi): String = k.value?.let { v ->
            when (k.unit) {
                KpiUnit.PERCENT -> (if (v < 0) "−" else "") + Fa.percent(kotlin.math.abs(v))
                KpiUnit.RIAL -> Fa.rial(v) + " ریال"
                KpiUnit.DAYS -> Fa.number(v) + " روز"
                KpiUnit.WEEKS -> Fa.number(v) + " هفته"
                KpiUnit.COUNT -> Fa.number(v)
                KpiUnit.RIAL_PER_HOUR -> Fa.rial(v) + " ریال در ساعت"
                KpiUnit.RATING -> Fa.number(v / 100) + (if (v % 100 != 0L) "٫" + Fa.number((v % 100) / 10) else "") + " از ۵"
            }
        } ?: if (k.quality == KpiQuality.NOT_APPLICABLE) "ندارد" else "نامعلوم"

        fun targetText(k: Kpi): String = k.target?.let { t ->
            val unit = k.copy(value = t.good)
            (if (t.better == KpiTarget.Better.LOWER) "حداکثر " else "حداقل ") + valueText(unit)
        } ?: "—"

        fun qualityName(q: KpiQuality) = when (q) {
            KpiQuality.OK -> "کامل"; KpiQuality.PARTIAL -> "ناقص"; KpiQuality.UNKNOWN -> "داده‌ی کافی نیست"; KpiQuality.NOT_APPLICABLE -> "موضوعیت ندارد"
        }

        fun signedPercent(bp: Long) = (if (bp > 0) "+" else if (bp < 0) "−" else "") + Fa.percent(kotlin.math.abs(bp))
    }
}
