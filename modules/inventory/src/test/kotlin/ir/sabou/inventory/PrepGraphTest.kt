package ir.sabou.inventory

import ir.sabou.inventory.memory.InMemoryRecipeStore
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrepGraphTest {
    private val recipes = InMemoryRecipeStore()
    private val day = BusinessDate(20_000)
    private val a = GlobalId.new(); private val b = GlobalId.new(); private val c = GlobalId.new(); private val raw = GlobalId.new()
    private val prepared = setOf(a, b, c)
    private val graph = PrepGraph(recipes) { it in prepared }

    private fun recipe(item: GlobalId, uses: List<GlobalId>, from: BusinessDate = BusinessDate(19_000)) =
        recipes.savePrepVersion(PrepRecipe(GlobalId.new(), item, 1, from, Quantity.units(1), uses.map { RecipeLine(it, Quantity.units(1)) }))

    @Test fun aCycleAlreadyInTheDataIsReportedInsteadOfLooping() {
        // Written directly, as older data could be: a → b → a, and c made from raw.
        recipe(a, listOf(b)); recipe(b, listOf(a)); recipe(c, listOf(raw))
        val result = graph.expand(mapOf(a to Quantity.units(1).micros, c to Quantity.units(2).micros), day) { 0L }
        val cycle = result.problems.filterIsInstance<PrepGraph.Problem.Cycle>().single()
        assertEquals(cycle.path.first(), cycle.path.last())
        assertEquals(setOf(a, b), cycle.path.toSet())
        assertEquals(Quantity.units(2).micros, result.need[raw])       // the rest is still broken down
    }

    @Test fun whatIsAlreadyPreparedIsUsedFirst() {
        recipe(a, listOf(raw))
        val result = graph.expand(mapOf(a to Quantity.units(5).micros), day) { if (it == a) Quantity.units(3).micros else 0L }
        assertEquals(Quantity.units(2).micros, result.need[raw])
    }

    @Test fun aVersionThatEndsBeforeTheNewRecipeDoesNotCount() {
        recipe(b, listOf(a), from = BusinessDate(19_000))
        recipe(b, listOf(raw), from = BusinessDate(19_500))                 // b stopped using a
        assertNull(graph.cycleWith(a, listOf(RecipeLine(b, Quantity.units(1))), BusinessDate(19_600)))
        val cycle = graph.cycleWith(a, listOf(RecipeLine(b, Quantity.units(1))), BusinessDate(19_100))!!
        assertTrue(cycle.first() == a && cycle.last() == a)
    }
}
