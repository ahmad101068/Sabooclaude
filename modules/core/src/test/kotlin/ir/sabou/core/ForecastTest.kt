package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.SetSpecialDay
import ir.sabou.sales.Settlement
import ir.sabou.sales.SpecialDay
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Weekday pattern of recent weeks, special days, trend and back-tested error (ADR-0041). */
class ForecastTest {
    private val dir = Files.createTempDirectory("forecast")
    private val db = DevDatabases.open(dir.resolve("f.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val from = JalaliCalendar.date(1405, 7, 20)
    private fun id() = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun aSteadyHistoryHasNoErrorAndNoBias() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val dish = core.inventory.defineMenuItem(DefineMenuItem(id(), "غذا")).resultId
        for (back in 84 downTo 1) {
            val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, from.plusDays(-back.toLong()), kitchen, listOf(SaleLineInput(dish, Quantity.units(1), Money.of(10_000_000))),
                Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, Money.of(10_000_000))))).resultId
            core.salesOps.post(PostDailySale(id(), branch, sale))
        }
        val f = core.forecasts.sales(branch, from, 7)
        assertEquals(0L, f.errorBp); assertEquals(0L, f.wapeBp); assertEquals(0L, f.biasBp)
    }

    @Test fun weekdaysSpecialDaysAndError() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val flour = core.inventory.createItem(CreateItem(id(), "آرد", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val dish = core.inventory.defineMenuItem(DefineMenuItem(id(), "غذا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), dish, BusinessDate(19_000), listOf(RecipeLine(flour, Quantity.of(1_000)))))
        // 8 weeks: Fridays 20,000,000, other days 10,000,000; the last 4 weeks 10 % more. Closed on one Tuesday.
        for (back in 56 downTo 1) {
            val d = from.plusDays(-back.toLong())
            if (back == 13) continue                                         // a Tuesday (closed)
            val base = if (JalaliCalendar.weekdayIndex(d) == 6) 20_000_000L else 10_000_000L
            val amount = if (back <= 28) base * 11 / 10 else base
            val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, d, kitchen, listOf(SaleLineInput(dish, Quantity.units(1), Money.of(amount))),
                Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, Money.of(amount))))).resultId
            core.salesOps.post(PostDailySale(id(), branch, sale))
        }
        val f = core.forecasts.sales(branch, from, 14)
        val friday = f.days.first { JalaliCalendar.weekdayIndex(it.date) == 6 }
        // Weighted: last 4 Fridays 22,000,000 (weights 8+7+6+5), the 4 before 20,000,000 (4+3+2+1): ≈ 21,444,444.
        assertEquals(21_444_444L, friday.expected)
        assertEquals(8, friday.samples)
        assertTrue(friday.low < friday.expected && friday.high > friday.expected)
        assertTrue(f.trendBp in 10_900L..11_100L, "trend ${f.trendBp}")                  // ≈ +10 %
        assertTrue(f.errorBp != null && f.errorBp!! < 1_000, "error ${f.errorBp}")       // under 10 % on this regular history
        // ADR-0055: sales rose 10 % four weeks ago and the weighted mean follows with a lag, so the back-test runs
        // below actual (negative bias). The bias can never exceed the weighted error, and both stay small.
        val wape = f.wapeBp!!; val bias = f.biasBp!!
        assertTrue(bias < 0, "bias $bias")
        assertTrue(kotlin.math.abs(bias) <= wape && wape < 1_000, "wape $wape bias $bias")

        val saturday = f.days.first { JalaliCalendar.weekdayIndex(it.date) == 0 }
        core.specialDayOps.set(SetSpecialDay(id(), branch, saturday.date, SpecialDay.Kind.CLOSED, 0, "تعمیرات"))
        core.specialDayOps.set(SetSpecialDay(id(), branch, friday.date, SpecialDay.Kind.HOLIDAY, 150, "عید"))
        val again = core.forecasts.sales(branch, from, 14)
        assertEquals(0L, again.days.single { it.date == saturday.date }.expected)
        assertEquals(32_166_666L, again.days.single { it.date == friday.date }.expected)
        assertEquals(emptyList(), core.prep.forecast(branch, saturday.date))
    }

    /**
     * Only recorded days count, and as they were (review of the source, 2026-10): an open day that sold nothing (its
     * day closed with no sales) is a real zero; a day nobody recorded is unknown and left out; a past holiday is
     * scaled back to a normal day before it is averaged.
     */
    @Test fun recordedZeroDaysCountUnrecordedDaysDoNotAndPastHolidaysAreScaledBack() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val flour = core.inventory.createItem(CreateItem(id(), "آرد", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val dish = core.inventory.defineMenuItem(DefineMenuItem(id(), "غذا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), dish, BusinessDate(19_000), listOf(RecipeLine(flour, Quantity.of(1_000)))))
        fun sell(d: BusinessDate, portions: Long) {
            val amount = Money.of(portions * 1_000_000)
            val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, d, kitchen, listOf(SaleLineInput(dish, Quantity.units(portions), Money.of(1_000_000))),
                Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, amount)))).resultId
            core.salesOps.post(PostDailySale(id(), branch, sale))
        }
        for (w in 1..8) {
            val d = from.plusDays(-7L * w)
            when (w) {
                // Last week: open, nothing sold, the day closed with the cash counted.
                1 -> core.salesOps.closeDay(ir.sabou.sales.CloseSalesDay(id(), branch, d, cash, Money.of(core.overview.treasury().single().balance)))
                2 -> Unit                                                                  // never recorded
                3 -> {                                                                     // a holiday at 200 %: twice a normal day
                    core.specialDayOps.set(SetSpecialDay(id(), branch, d, SpecialDay.Kind.HOLIDAY, 200, "عید"))
                    sell(d, 14)
                }
                else -> sell(d, 7)
            }
        }
        val day = core.forecasts.sales(branch, from, 1).days.single()
        // Weights 8 (zero), 6 (holiday scaled back to 7,000,000) and 5+4+3+2+1 (7,000,000); week 2 is not a sample.
        assertEquals(7, day.samples)
        assertEquals(147_000_000L / 29, day.expected)
        // Dishes over 4 weeks: (0 + 7 + 7) / 3 recorded days = 4.67 → 5 portions. Before this change: (14 + 7) / 2 → 11.
        val dish4 = core.prep.forecast(branch, from, weeks = 4).single()
        assertEquals(Quantity.units(5), dish4.portions)
        assertEquals(3, dish4.weeks)
    }
}
