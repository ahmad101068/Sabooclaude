package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CountEntry
import ir.sabou.inventory.CountFrequency
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.SaveCountPlan
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.SubmitStockCount
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Quantity
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A count plan kept in the database, its sheet, its due alert, cleared by its count (ADR-0031). */
class CountPlanTest {
    private val dir = Files.createTempDirectory("plan")
    private val db = DevDatabases.open(dir.resolve("c.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 20)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun aWeeklyPlanIsDueOnItsDayUntilCounted() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = core.overview.branches().single().let { ir.sabou.kernel.Scope.Branch(it.id) }
        val store = core.overview.locations(branch).single().id
        val saffron = core.inventory.createItem(CreateItem(GlobalId.new(), "زعفران", StockUnit.GRAM, Quantity.ZERO)).resultId
        core.inventory.createItem(CreateItem(GlobalId.new(), "برنج", StockUnit.KILOGRAM, Quantity.ZERO))
        val plan = core.countPlanOps.save(SaveCountPlan(GlobalId.new(), branch, null, "زعفران", store, listOf(saffron),
            CountFrequency(CountFrequency.Kind.WEEKLY, JalaliCalendar.weekdayIndex(day)))).resultId
        assertEquals(listOf(saffron), core.overview.countPlans(branch).single().itemIds)
        assertEquals(listOf("زعفران"), core.overview.countSheet(store, plan).map { it.item.name })
        val owner = core.overview.me().userId
        assertTrue(core.alerts.forUser(owner, day).any { it.kind == Alert.Kind.COUNT_DUE })
        assertTrue(core.alerts.forUser(owner, day.plusDays(-1)).none { it.kind == Alert.Kind.COUNT_DUE && "زعفران" in it.title && it.key.endsWith(":${day.epochDay}") })

        core.counts.submit(SubmitStockCount(GlobalId.new(), branch, store, day, listOf(CountEntry(saffron, Quantity.ZERO)), planId = plan))
        assertTrue(core.alerts.forUser(owner, day).none { it.kind == Alert.Kind.COUNT_DUE })
        assertTrue(core.overview.countsDue(branch, day.plusDays(7)).single().due)
    }
}
