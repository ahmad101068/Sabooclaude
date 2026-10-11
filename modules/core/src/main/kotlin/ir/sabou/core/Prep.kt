package ir.sabou.core

import ir.sabou.inventory.Item
import ir.sabou.inventory.MenuItem
import ir.sabou.inventory.PrepGraph
import ir.sabou.inventory.RecipeBook
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.Actor
import ir.sabou.platform.Permission
import ir.sabou.sales.SaleStatus

/** Expected portions of a dish on a day: the average of the same weekday in the last weeks ([weeks] with sales). */
data class DishForecast(val item: MenuItem, val portions: Quantity, val weeks: Int)

/**
 * One prepared item to make today (ADR-0033): [required] by the planned dishes (and by other prepared items),
 * [par] kept in stock, [onHand] now; [toMake] = required + par − on hand, never below zero.
 */
data class PrepTask(val item: Item, val required: Quantity, val par: Quantity, val onHand: Quantity, val toMake: Quantity)

/** A raw ingredient the plan needs more of than is in stock. */
data class PrepShortage(val item: Item, val needed: Quantity, val onHand: Quantity)

/** Tasks in the order to make them (what others use first), what is missing, and what could not be broken down. */
data class PrepPlan(val tasks: List<PrepTask>, val shortages: List<PrepShortage>, val problems: List<RecipeProblem>)

/** A dish and the allergens of everything in it, through prepared items at any depth. */
data class DishAllergens(val item: MenuItem, val allergens: List<String>)

/** The kitchen's day: expected dishes, what to prepare, allergens (ADR-0033). */
class Prep internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun Actor.require(scope: Scope) {
        if (!canAccess(scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(scope)))
    }

    /**
     * Portions per dish expected on [date]: the average of posted sales on the same weekday over the previous
     * [weeks] weeks, counting only weeks that had sales (a closed day does not pull the average down), rounded up.
     */
    fun forecast(branch: Scope.Branch, date: BusinessDate, weeks: Int = FORECAST_WEEKS): List<DishForecast> {
        actor(Permission.INVENTORY_PRODUCE, Permission.INVENTORY_VIEW, Permission.SALES_VIEW).require(branch)
        val days = (1..weeks).map { date.plusDays(-7L * it) }
            .map { d -> core.sales.salesOn(branch, d).filter { it.status == SaleStatus.POSTED } }
            .filter { it.isNotEmpty() }
        if (days.isEmpty()) return emptyList()
        val totals = HashMap<GlobalId, Long>()
        days.flatten().forEach { sale -> sale.lines.forEach { l -> totals[l.menuItemId] = (totals[l.menuItemId] ?: 0L) + l.portions.micros } }
        return totals.mapNotNull { (id, micros) ->
            val item = core.recipes.menuItem(id)?.takeIf { it.isActive } ?: return@mapNotNull null
            val average = (micros + days.size - 1) / days.size
            DishForecast(item, Quantity.of(((average + Quantity.SCALE - 1) / Quantity.SCALE) * Quantity.SCALE), days.size)
        }.sortedByDescending { it.portions.micros }
    }

    /** What to prepare at [locationId] on [date] for [plan], deepest sub-recipes first. */
    fun plan(locationId: GlobalId, date: BusinessDate, plan: List<PlanLine>): PrepPlan {
        val location = core.locations.byId(locationId) ?: throw DomainException(DomainError.NotFound("LOCATION"))
        actor(Permission.INVENTORY_PRODUCE, Permission.INVENTORY_VIEW).require(location.scope)
        val items = core.items.all().associateBy { it.id }
        val book = RecipeBook(core.recipes)
        fun onHand(id: GlobalId) = core.stock.balance(id, locationId).quantity.micros
        val problems = ArrayList<RecipeProblem>()
        val dishes = HashMap<GlobalId, Long>()
        plan.filter { !it.portions.isZero }.forEach { p ->
            val lines = runCatching { book.requirements(p.menuItemId, date, p.portions) }.getOrNull()
            if (lines == null) problems += RecipeProblem(core.recipes.menuItem(p.menuItemId)?.name ?: "آیتم منو", RecipeProblem.Kind.NO_RECIPE,
                "رسپی این آیتم منو در این تاریخ نیست؛ در برنامه نیامده است.")
            lines?.forEach { dishes[it.itemId] = (dishes[it.itemId] ?: 0L) + it.quantity.micros }
        }
        // Prepared items kept at a par are made up to it even when no dish needs them today.
        val initial = HashMap(dishes)
        items.values.filter { it.isActive && it.prepared && !it.parFor(date).isZero }.forEach { initial[it.id] = (initial[it.id] ?: 0L) + it.parFor(date).micros }
        val expansion = PrepGraph(core.recipes) { items[it]?.prepared == true }.expand(initial, date, ::onHand)
        expansion.problems.forEach { pr ->
            problems += when (pr) {
                is PrepGraph.Problem.NoRecipe -> RecipeProblem(items[pr.itemId]?.name ?: "کالای آماده", RecipeProblem.Kind.NO_RECIPE, "رسپی تولید ندارد.")
                is PrepGraph.Problem.Cycle -> RecipeProblem(items[pr.itemId]?.name ?: "کالای آماده", RecipeProblem.Kind.CYCLE,
                    "رسپی‌ها چرخه دارند: " + pr.path.joinToString(" ← ") { items[it]?.name ?: "؟" })
            }
        }
        val need = expansion.need
        val depth = HashMap<GlobalId, Int>()
        fun depthOf(id: GlobalId, seen: Set<GlobalId> = emptySet()): Int = depth.getOrPut(id) {
            if (id in seen) return 0
            val lines = core.recipes.prepVersions(id).filter { it.effectiveFrom <= date }.maxByOrNull { it.effectiveFrom }?.lines.orEmpty()
            1 + (lines.filter { items[it.itemId]?.prepared == true }.maxOfOrNull { depthOf(it.itemId, seen + id) } ?: 0)
        }
        val tasks = need.keys.mapNotNull { items[it] }.filter { it.prepared }.map { item ->
            val par = item.parFor(date).micros
            val required = (need[item.id] ?: 0L) - par
            val toMake = maxOf(0L, (need[item.id] ?: 0L) - onHand(item.id))
            PrepTask(item, Quantity.of(maxOf(0L, required)), Quantity.of(par), Quantity.of(onHand(item.id)), Quantity.of(toMake))
        }.filter { !it.toMake.isZero }
            .sortedWith(compareBy<PrepTask> { depthOf(it.item.id) }.thenBy { it.item.shelf }.thenBy { it.item.name })
        val shortages = need.filter { (id, q) -> items[id]?.prepared == false && q > onHand(id) }
            .map { (id, q) -> PrepShortage(items.getValue(id), Quantity.of(q), Quantity.of(onHand(id))) }
            .sortedBy { it.item.name }
        return PrepPlan(tasks, shortages, problems)
    }

    /** Every active dish with the allergens of its current recipe, through prepared items at any depth. */
    fun allergens(date: BusinessDate): List<DishAllergens> {
        actor(Permission.RECIPE_MANAGE, Permission.INVENTORY_VIEW, Permission.SALES_VIEW, Permission.MENU_PRICE_MANAGE)
        val items = core.items.all().associateBy { it.id }
        val book = RecipeBook(core.recipes)
        val memo = HashMap<GlobalId, Set<String>>()
        fun of(itemId: GlobalId, seen: Set<GlobalId>): Set<String> = memo[itemId] ?: run {
            val item = items[itemId] ?: return emptySet()
            val own = split(item.allergens)
            val inner = if (item.prepared && itemId !in seen) {
                core.recipes.prepVersions(itemId).filter { it.effectiveFrom <= date }.maxByOrNull { it.effectiveFrom }?.lines.orEmpty()
                    .flatMap { of(it.itemId, seen + itemId) }.toSet()
            } else emptySet()
            (own + inner).also { memo[itemId] = it }
        }
        return core.recipes.menuItems().filter { it.isActive }.map { dish ->
            val lines = runCatching { book.versionOn(dish.id, date).lines }.getOrDefault(emptyList())
            DishAllergens(dish, lines.flatMap { of(it.itemId, emptySet()) }.distinct().sortedWith(PERSIAN))
        }.sortedWith(compareBy(PERSIAN) { it.item.name })
    }

    companion object {
        const val FORECAST_WEEKS = 4

        /** Persian alphabetical order (گ before ل, unlike code points); the JVM has no Persian collation rules. */
        private val PERSIAN: Comparator<String> = Comparator { a, b -> Fa.compareAlphabetical(a, b) }

        /** «گلوتن، لبنیات , بادام‌زمینی» → its parts, trimmed, without repeats. */
        fun split(text: String): Set<String> = text.split('،', ',', '؛', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
