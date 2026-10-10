package ir.sabou.inventory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity

/**
 * Prepared items as a graph: an edge X → Y when X's prep recipe uses the prepared item Y. Recipes are kept
 * acyclic (a recipe closing a cycle is refused when published, see [cycleWith]); the expansion still detects a
 * cycle in older data and reports it instead of looping.
 */
class PrepGraph(private val recipes: RecipeStore, private val isPrepared: (GlobalId) -> Boolean) {
    private val book = RecipeBook(recipes)

    /** What could not be broken down, and why. */
    sealed interface Problem {
        val itemId: GlobalId
        data class NoRecipe(override val itemId: GlobalId) : Problem
        /** [path] starts and ends with the same item. */
        data class Cycle(override val itemId: GlobalId, val path: List<GlobalId>) : Problem
    }

    data class Expansion(val need: Map<GlobalId, Long>, val problems: List<Problem>)

    /**
     * Breaks the need of prepared items (micro-units) down into what they are made of, after using what is
     * [available] of each. Prepared items are visited in topological order, so an item used by several recipes is
     * broken down once, for its whole need. Returned need keeps the prepared items' totals too.
     */
    fun expand(initial: Map<GlobalId, Long>, date: BusinessDate, available: (GlobalId) -> Long): Expansion {
        val need = HashMap(initial)
        val problems = ArrayList<Problem>()
        // Prepared items reachable from the need, with their recipe on [date].
        val recipeOf = HashMap<GlobalId, PrepRecipe?>()
        val stack = ArrayDeque(initial.keys.filter(isPrepared))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (id in recipeOf) continue
            val recipe = recipes.prepVersions(id).filter { it.effectiveFrom <= date }.maxByOrNull { it.effectiveFrom }
            recipeOf[id] = recipe
            if (recipe == null) problems += Problem.NoRecipe(id)
            recipe?.lines?.map { it.itemId }?.filter(isPrepared)?.forEach { if (it !in recipeOf) stack.addLast(it) }
        }
        val uses = recipeOf.mapValues { (_, r) -> r?.lines.orEmpty().map { it.itemId }.filter { it in recipeOf }.toSet() }

        // Kahn: an item is processed once every recipe using it has been.
        val usedBy = HashMap<GlobalId, Int>().apply { recipeOf.keys.forEach { put(it, 0) } }
        uses.values.forEach { set -> set.forEach { usedBy[it] = usedBy.getValue(it) + 1 } }
        val ready = ArrayDeque(recipeOf.keys.filter { usedBy[it] == 0 })
        val done = HashSet<GlobalId>()
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            done += id
            val recipe = recipeOf[id]
            val shortfall = maxOf(0L, (need[id] ?: 0L) - available(id))
            if (recipe != null && shortfall > 0) {
                book.prepRequirements(id, date, Quantity.of(shortfall)).forEach { need[it.itemId] = (need[it.itemId] ?: 0L) + it.quantity.micros }
            }
            uses.getValue(id).forEach { child -> usedBy[child] = usedBy.getValue(child) - 1; if (usedBy[child] == 0) ready.addLast(child) }
        }
        val stuck = recipeOf.keys - done
        // Left over: items on a cycle and those only a cycle uses. Report each cycle once.
        val reported = HashSet<GlobalId>()
        for (id in stuck) {
            if (id in reported) continue
            val cycle = findCycle(id) { n -> uses[n].orEmpty().filter { it in stuck } } ?: continue
            if (cycle.any { it in reported }) continue
            reported += cycle
            problems += Problem.Cycle(id, cycle)
        }
        return Expansion(need, problems)
    }

    /**
     * The cycle that publishing [lines] as [itemId]'s recipe from [from] would close, or null. Every version of
     * another item's recipe that is (or will be) in force on or after [from] counts.
     */
    fun cycleWith(itemId: GlobalId, lines: List<RecipeLine>, from: BusinessDate): List<GlobalId>? {
        val edges = HashMap<GlobalId, Set<GlobalId>>()
        fun usesOf(id: GlobalId): Set<GlobalId> = edges.getOrPut(id) {
            if (id == itemId) return@getOrPut lines.map { it.itemId }.filter(isPrepared).toSet()
            val versions = recipes.prepVersions(id).sortedBy { it.effectiveFrom }
            versions.filterIndexed { i, v -> i == versions.lastIndex || versions[i + 1].effectiveFrom > from }
                .flatMap { v -> v.lines.map { it.itemId } }.filter(isPrepared).toSet()
        }
        return findCycle(itemId, ::usesOf)?.takeIf { it.first() == itemId }
    }

    /** A path from [start] back to itself through [next], depth first; null if there is none. */
    private fun findCycle(start: GlobalId, next: (GlobalId) -> Collection<GlobalId>): List<GlobalId>? {
        val path = ArrayList<GlobalId>()
        val onPath = HashSet<GlobalId>()
        val finished = HashSet<GlobalId>()
        fun visit(id: GlobalId): List<GlobalId>? {
            path += id; onPath += id
            for (n in next(id)) {
                if (n == start) return path + start
                if (n !in onPath && n !in finished) visit(n)?.let { return it }
            }
            path.removeAt(path.lastIndex); onPath -= id; finished += id
            return null
        }
        return visit(start)
    }
}
