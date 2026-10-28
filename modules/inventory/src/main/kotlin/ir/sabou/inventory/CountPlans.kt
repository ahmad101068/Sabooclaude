package ir.sabou.inventory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission

/**
 * A count that repeats (ADR-0031): which items of one location are counted, and how often. Expensive or
 * easily-lost items (meat, cheese, saffron) are counted daily, the rest weekly or monthly — counting everything every
 * day is not practical, and counting nothing until month-end hides losses for a month.
 */
data class CountPlan(
    val id: GlobalId,
    val name: String,
    val scope: Scope.Branch,
    val locationId: GlobalId,
    /** Items to count, in the sheet's shelf order; empty = every active item. */
    val itemIds: List<GlobalId>,
    val frequency: CountFrequency,
    val isActive: Boolean = true,
)

/**
 * [day]: for WEEKLY the weekday (0 = Saturday … 6 = Friday); for MONTHLY the day of the Jalali month (1..31; a
 * shorter month uses its last day); unused for DAILY.
 */
data class CountFrequency(val kind: Kind, val day: Int = 0) : java.io.Serializable {
    enum class Kind { DAILY, WEEKLY, MONTHLY }

    init {
        require(when (kind) { Kind.DAILY -> day == 0; Kind.WEEKLY -> day in 0..6; Kind.MONTHLY -> day in 1..31 }) { "count_frequency_day" }
    }

    /** The day the period containing [date] starts: the latest scheduled day on or before [date]. */
    fun periodStart(date: BusinessDate): BusinessDate = when (kind) {
        Kind.DAILY -> date
        Kind.WEEKLY -> date.plusDays(-Math.floorMod(JalaliCalendar.weekdayIndex(date) - day, 7).toLong())
        Kind.MONTHLY -> {
            val ymd = JalaliCalendar.of(date)
            fun scheduled(y: Int, m: Int) = JalaliCalendar.date(y, m, minOf(day, JalaliCalendar.monthLength(y, m)))
            val thisMonth = scheduled(ymd.year, ymd.month)
            if (thisMonth <= date) thisMonth else if (ymd.month == 1) scheduled(ymd.year - 1, 12) else scheduled(ymd.year, ymd.month - 1)
        }
    }
}

interface CountPlanStore {
    fun byId(id: GlobalId): CountPlan?
    fun all(): List<CountPlan>
    fun save(plan: CountPlan)
}

/** Creates ([planId] null) or changes a count plan; a plan is retired with [isActive] false, never deleted. */
@NoDocument
data class SaveCountPlan(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val planId: GlobalId?,
    val name: String,
    val locationId: GlobalId,
    val itemIds: List<GlobalId>,
    val frequency: CountFrequency,
    val isActive: Boolean = true,
) : Command {
    override val requiredPermission get() = Permission.INVENTORY_ADJUST
    override fun fingerprint() = "$scope|$planId|${name.trim()}|$locationId|${itemIds.joinToString(",")}|${frequency.kind}:${frequency.day}|$isActive"
}

/** A plan's current period and its latest submitted count; [due] when nothing was submitted since the period began. */
data class CountDue(val plan: CountPlan, val periodStart: BusinessDate, val lastCount: BusinessDate?) {
    val due: Boolean get() = lastCount == null || lastCount < periodStart
}

class CountPlanOperations(
    private val bus: CommandBus,
    private val gateway: InventoryGateway,
    private val plans: CountPlanStore,
    private val counts: StockCountStore,
) {
    fun save(c: SaveCountPlan): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..60) { DomainError.InvalidInput("name", "نام برنامه‌ی شمارش الزامی است.") }
        val location = gateway.location(cmd.locationId)
        ensure(location.scope == cmd.scope) { DomainError.InvalidInput("scope", "انبار متعلق به این شعبه نیست.") }
        ensure(cmd.itemIds.distinct().size == cmd.itemIds.size && cmd.itemIds.size <= 500) { DomainError.InvalidInput("items", "هر کالا فقط یک‌بار بیاید.") }
        cmd.itemIds.forEach { gateway.item(it) }
        val existing = cmd.planId?.let { plans.byId(it) ?: throw DomainException(DomainError.NotFound("COUNT_PLAN")) }
        ensure(existing == null || existing.scope == cmd.scope) { DomainError.InvalidInput("scope", "این برنامه متعلق به این شعبه نیست.") }
        ensure(plans.all().none { it.id != existing?.id && it.isActive && it.scope == cmd.scope && it.name == name }) { DomainError.InvalidState("COUNT_PLAN", "DUPLICATE_NAME") }
        val plan = CountPlan(existing?.id ?: GlobalId.new(), name, cmd.scope, location.id, cmd.itemIds, cmd.frequency, cmd.isActive)
        plans.save(plan)
        ctx.audit(AuditDraft(if (existing == null) "COUNT_PLAN_CREATE" else "COUNT_PLAN_UPDATE", "COUNT_PLAN", plan.id.value,
            "$name;${cmd.frequency.kind}:${cmd.frequency.day};items=${cmd.itemIds.size};active=${cmd.isActive}"))
        plan.id
    }

    /** Active plans of [scope] with their current period; [CountDue.lastCount] is the latest submitted count of the plan. */
    fun status(scope: Scope.Branch, today: BusinessDate): List<CountDue> {
        val submitted = counts.all().filter { it.planId != null && it.status != CountStatus.REJECTED }.groupBy { it.planId }
        return plans.all().filter { it.isActive && it.scope == scope }.map { plan ->
            CountDue(plan, plan.frequency.periodStart(today), submitted[plan.id]?.maxOfOrNull { it.date })
        }
    }

    /** The items a plan's count must cover: its list, or every active item. */
    fun itemsOf(plan: CountPlan): List<GlobalId> = plan.itemIds.ifEmpty { gateway.activeItemIds() }
}
