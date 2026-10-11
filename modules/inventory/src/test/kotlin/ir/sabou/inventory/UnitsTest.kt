package ir.sabou.inventory

import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnitsTest {
    private val flour = Item(GlobalId.new(), "آرد", StockUnit.KILOGRAM, Quantity.ZERO,
        packs = listOf(PackUnit("کیسه ۱۰ کیلویی", Quantity.units(10)), PackUnit("کیسه ۴۰ کیلویی", Quantity.units(40))))
    private val cola = Item(GlobalId.new(), "نوشابه", StockUnit.PIECE, Quantity.ZERO, packs = listOf(PackUnit("باکس", Quantity.units(24))))
    private val oil = Item(GlobalId.new(), "روغن", StockUnit.LITER, Quantity.ZERO)

    @Test fun anItemOffersItsUnitItsMetricSiblingAndItsPacks() {
        assertEquals(listOf(EntryUnit.Stock, EntryUnit.Metric(StockUnit.GRAM), EntryUnit.Pack("کیسه ۱۰ کیلویی"), EntryUnit.Pack("کیسه ۴۰ کیلویی")), Units.choices(flour))
        assertEquals(listOf(EntryUnit.Stock, EntryUnit.Pack("باکس")), Units.choices(cola))           // pieces have no metric sibling
        assertEquals(listOf(EntryUnit.Stock, EntryUnit.Metric(StockUnit.MILLILITER)), Units.choices(oil))
    }

    @Test fun conversionsAreExact() {
        assertEquals(Quantity.units(30), Units.toStock(flour, Quantity.units(3), EntryUnit.Pack("کیسه ۱۰ کیلویی")))
        assertEquals(Quantity.of(250_000), Units.toStock(flour, Quantity.units(250), EntryUnit.Metric(StockUnit.GRAM)))   // 250 g = 0.25 kg
        assertEquals(Quantity.units(48), Units.toStock(cola, Quantity.units(2), EntryUnit.Pack("باکس")))
        assertEquals(Quantity.of(1_500_000), Units.toStock(oil, Quantity.units(1_500), EntryUnit.Metric(StockUnit.MILLILITER)))
        assertEquals(Quantity.of(500_000), Units.toStock(flour, Quantity.of(50_000), EntryUnit.Pack("کیسه ۱۰ کیلویی")))   // 0.05 sack = 0.5 kg
        // Round trip for display.
        assertEquals(Quantity.units(3), Units.fromStock(flour, Quantity.units(30), EntryUnit.Pack("کیسه ۱۰ کیلویی")))
        assertEquals(Quantity.units(250), Units.fromStock(flour, Quantity.of(250_000), EntryUnit.Metric(StockUnit.GRAM)))
    }

    @Test fun nonsenseIsRefused() {
        assertFailsWith<DomainException> { Units.toStock(cola, Quantity.units(1), EntryUnit.Metric(StockUnit.GRAM)) }
        assertFailsWith<DomainException> { Units.toStock(flour, Quantity.units(1), EntryUnit.Pack("ندارد")) }
        // Too small to exist in the stock unit (0.0001 g of a kg item) is an error, not a silent zero.
        assertFailsWith<DomainException> { Units.toStock(flour, Quantity.of(100), EntryUnit.Metric(StockUnit.GRAM)) }
        assertFailsWith<DomainException> { Units.validatePacks(listOf(PackUnit("کیسه", Quantity.ZERO))) }
        assertFailsWith<DomainException> { Units.validatePacks(listOf(PackUnit("کیسه", Quantity.units(1)), PackUnit(" کیسه ", Quantity.units(2)))) }
    }
}
