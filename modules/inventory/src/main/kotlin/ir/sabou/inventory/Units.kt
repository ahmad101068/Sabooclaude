package ir.sabou.inventory

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Rounding

/** What a unit measures. Units convert only within one dimension. */
enum class Dimension { MASS, VOLUME, COUNT }

/**
 * A purchase pack of one item, e.g. «کیسه ۱۰ کیلویی» containing 10 kg, or «کارتن» containing 24 pieces.
 * [contains] is in the item's stock unit.
 */
data class PackUnit(val name: String, val contains: Quantity)

/** The unit a quantity is typed in: the item's own unit, a metric sibling (گرم for کیلوگرم), or one of its packs. */
sealed interface EntryUnit {
    data object Stock : EntryUnit
    data class Metric(val unit: StockUnit) : EntryUnit
    data class Pack(val name: String) : EntryUnit
}

/**
 * The one place quantities change unit. Stock, recipes and documents always hold quantities in the item's stock
 * unit; screens let people type in the unit they think in (a sack, grams for a recipe) and convert here, exactly
 * (integer micro-units, half-up to the nearest micro-unit of the stock unit).
 */
object Units {
    fun dimension(unit: StockUnit): Dimension = when (unit) {
        StockUnit.GRAM, StockUnit.KILOGRAM -> Dimension.MASS
        StockUnit.MILLILITER, StockUnit.LITER -> Dimension.VOLUME
        StockUnit.PIECE, StockUnit.PACK -> Dimension.COUNT
    }

    /** Size in the smallest unit of the dimension (gram, millilitre); count units have no metric size. */
    private fun base(unit: StockUnit): Long? = when (unit) {
        StockUnit.GRAM, StockUnit.MILLILITER -> 1
        StockUnit.KILOGRAM, StockUnit.LITER -> 1_000
        StockUnit.PIECE, StockUnit.PACK -> null
    }

    /** The units an item's quantity may be typed in, its own unit first. */
    fun choices(item: Item): List<EntryUnit> = buildList {
        add(EntryUnit.Stock)
        StockUnit.entries.filter { it != item.unit && base(it) != null && dimension(it) == dimension(item.unit) }.forEach { add(EntryUnit.Metric(it)) }
        item.packs.forEach { add(EntryUnit.Pack(it.name)) }
    }

    /** [amount] typed in [unit] → the same quantity in the item's stock unit. */
    fun toStock(item: Item, amount: Quantity, unit: EntryUnit): Quantity {
        val micros = when (unit) {
            EntryUnit.Stock -> return amount
            is EntryUnit.Metric -> {
                val from = base(unit.unit); val to = base(item.unit)
                if (from == null || to == null || dimension(unit.unit) != dimension(item.unit)) throw DomainException(DomainError.InvalidInput("unit", "این واحد برای این کالا قابل تبدیل نیست."))
                Ratio.mulDiv(amount.micros, from, to, Rounding.HALF_UP)
            }
            is EntryUnit.Pack -> {
                val pack = item.packs.firstOrNull { it.name == unit.name } ?: throw DomainException(DomainError.NotFound("PACK_UNIT"))
                Ratio.mulDiv(amount.micros, pack.contains.micros, Quantity.SCALE, Rounding.HALF_UP)
            }
        }
        if (!amount.isZero && micros == 0L) throw DomainException(DomainError.InvalidInput("quantity", "مقدار برای واحد کالا بیش از حد کوچک است."))
        return Quantity.of(micros)
    }

    /** A quantity in the stock unit expressed in [unit] (for showing what was typed, or a count by packs). */
    fun fromStock(item: Item, stock: Quantity, unit: EntryUnit): Quantity = when (unit) {
        EntryUnit.Stock -> stock
        is EntryUnit.Metric -> Quantity.of(Ratio.mulDiv(stock.micros, base(item.unit)!!, base(unit.unit)!!, Rounding.HALF_UP))
        is EntryUnit.Pack -> {
            val pack = item.packs.firstOrNull { it.name == unit.name } ?: throw DomainException(DomainError.NotFound("PACK_UNIT"))
            Quantity.of(Ratio.mulDiv(stock.micros, Quantity.SCALE, pack.contains.micros, Rounding.HALF_UP))
        }
    }

    /** Validates an item's packs: named, positive, distinct, at most [MAX_PACKS]. */
    fun validatePacks(packs: List<PackUnit>): List<PackUnit> {
        val clean = packs.map { it.copy(name = it.name.trim()) }
        if (clean.size > MAX_PACKS) throw DomainException(DomainError.InvalidInput("packs", "حداکثر $MAX_PACKS بسته‌بندی برای هر کالا."))
        clean.forEach {
            if (it.name.length !in 1..40) throw DomainException(DomainError.InvalidInput("packs", "نام بسته‌بندی الزامی است."))
            if (it.contains.micros <= 0L) throw DomainException(DomainError.InvalidInput("packs", "محتوای بسته‌بندی باید بیشتر از صفر باشد."))
        }
        if (clean.map { it.name }.distinct().size != clean.size) throw DomainException(DomainError.InvalidInput("packs", "نام بسته‌بندی تکراری است."))
        return clean
    }

    const val MAX_PACKS = 10
}
