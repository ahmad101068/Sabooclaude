package ir.sabou.inventory

import ir.sabou.inventory.memory.InMemoryItemStore
import ir.sabou.inventory.memory.InMemoryLocationStore
import ir.sabou.inventory.memory.InMemoryRecipeStore
import ir.sabou.inventory.memory.InMemoryStockStore
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LedgerAccessRegistry
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.StandardAccounts
import ir.sabou.ledger.memory.InMemoryAccountStore
import ir.sabou.ledger.memory.InMemoryJournalStore
import ir.sabou.ledger.memory.InMemoryPeriodStore
import ir.sabou.platform.Actor
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryEventLog
import ir.sabou.platform.memory.InMemoryDocumentNumberStore
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ir.sabou.inventory.memory.InMemoryCountPlanStore
import ir.sabou.inventory.memory.InMemoryStockCountStore
import ir.sabou.kernel.JalaliCalendar

/** Repeating counts: period starts, plan sheets that must be complete, due until counted (ADR-0031). */
class CountPlansTest {
    private val branchA = Scope.Branch(BranchId(GlobalId.new()))
    private val session = MutableSession(Actor(GlobalId.new(), "owner", Role.OWNER, emptySet()))
    private val uow = InMemoryUnitOfWork()
    private val items = InMemoryItemStore()
    private val locations = InMemoryLocationStore()
    private val stock = InMemoryStockStore()
    private val recipes = InMemoryRecipeStore()
    private val plans = InMemoryCountPlanStore()
    private val countStore = InMemoryStockCountStore()
    private val numbers = InMemoryDocumentNumberStore().also { uow.register(it) }
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }, numbers) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, InMemoryAccountStore(StandardAccounts.chart()), InMemoryJournalStore(), InMemoryPeriodStore())
    private val gateway = InventoryGateway(ledger, registry.issue(ModuleId.INVENTORY), items, locations, stock)
    private val ops = InventoryOperations(bus, gateway, items, locations, recipes)
    private val counts = StockCountOperations(bus, gateway, countStore, plans)
    private val planOps = CountPlanOperations(bus, gateway, plans, countStore)

    init { uow.register(items, locations, stock, recipes, plans, countStore) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun d(y: Int, m: Int, day: Int) = JalaliCalendar.date(y, m, day)

    @Test fun periodsStartOnTheScheduledDay() {
        val daily = CountFrequency(CountFrequency.Kind.DAILY)
        assertEquals(d(1405, 7, 20), daily.periodStart(d(1405, 7, 20)))
        // 1405/07/20 is a Monday (index 2). Weekly on Saturday (0): the period began two days before.
        assertEquals(2, JalaliCalendar.weekdayIndex(d(1405, 7, 20)))
        assertEquals(d(1405, 7, 18), CountFrequency(CountFrequency.Kind.WEEKLY, 0).periodStart(d(1405, 7, 20)))
        assertEquals(d(1405, 7, 20), CountFrequency(CountFrequency.Kind.WEEKLY, 2).periodStart(d(1405, 7, 20)))
        assertEquals(d(1405, 7, 14), CountFrequency(CountFrequency.Kind.WEEKLY, 3).periodStart(d(1405, 7, 20)))
        // Monthly on the 31st: Mehr has 30 days, so its count falls on the 30th; before that, Shahrivar's 31st.
        val endOfMonth = CountFrequency(CountFrequency.Kind.MONTHLY, 31)
        assertEquals(d(1405, 6, 31), endOfMonth.periodStart(d(1405, 7, 29)))
        assertEquals(d(1405, 7, 30), endOfMonth.periodStart(d(1405, 7, 30)))
        // Monthly on the 5th, in Farvardin before the 5th: Esfand of the year before.
        assertEquals(d(1404, 12, 5), CountFrequency(CountFrequency.Kind.MONTHLY, 5).periodStart(d(1405, 1, 3)))
        assertFailsWith<IllegalArgumentException> { CountFrequency(CountFrequency.Kind.WEEKLY, 7) }
    }

    @Test fun aPlanCountMustCoverItsItemsAndClearsTheDueUntilTheNextPeriod() {
        val meat = ops.createItem(CreateItem(GlobalId.new(), "گوشت", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val cheese = ops.createItem(CreateItem(GlobalId.new(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        ops.createItem(CreateItem(GlobalId.new(), "نمک", StockUnit.KILOGRAM, Quantity.ZERO))
        val store = ops.createLocation(CreateLocation(GlobalId.new(), branchA, "سردخانه")).resultId
        val today = d(1405, 7, 20)
        val plan = planOps.save(SaveCountPlan(GlobalId.new(), branchA, null, "گران‌ها", store, listOf(meat, cheese), CountFrequency(CountFrequency.Kind.DAILY))).resultId
        assertEquals("INVALID_STATE:COUNT_PLAN:DUPLICATE_NAME", code {
            planOps.save(SaveCountPlan(GlobalId.new(), branchA, null, "گران‌ها", store, emptyList(), CountFrequency(CountFrequency.Kind.WEEKLY, 0)))
        })
        assertTrue(planOps.status(branchA, today).single().due)

        assertEquals("INVALID_INPUT:lines", code {
            counts.submit(SubmitStockCount(GlobalId.new(), branchA, store, today, listOf(CountEntry(meat, Quantity.ZERO)), planId = plan))
        })
        val first = counts.submit(SubmitStockCount(GlobalId.new(), branchA, store, today, listOf(CountEntry(meat, Quantity.ZERO), CountEntry(cheese, Quantity.ZERO)), planId = plan)).resultId
        assertEquals(plan, countStore.byId(first)!!.planId)
        assertTrue(!planOps.status(branchA, today).single().due)
        assertTrue(planOps.status(branchA, today.plusDays(1)).single().due)              // daily: due again tomorrow

        // A rejected count does not count as done.
        counts.reject(RejectStockCount(GlobalId.new(), branchA, first, "دوباره بشمارید"))
        assertTrue(planOps.status(branchA, today).single().due)

        // A retired plan is no longer due.
        planOps.save(SaveCountPlan(GlobalId.new(), branchA, plan, "گران‌ها", store, listOf(meat, cheese), CountFrequency(CountFrequency.Kind.DAILY), isActive = false))
        assertEquals(emptyList(), planOps.status(branchA, today))
    }
}
