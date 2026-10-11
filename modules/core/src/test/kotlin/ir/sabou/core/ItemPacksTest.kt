package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.EntryUnit
import ir.sabou.inventory.PackUnit
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.Units
import ir.sabou.inventory.UpdateItem
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** An item's purchase packs survive a restart and drive quantity entry; editing other fields keeps them. */
class ItemPacksTest {
    private val dir = Files.createTempDirectory("packs")
    private val file = dir.resolve("p.db")
    private val open = mutableListOf<JdbcSqlDatabase>()
    private val anchors = InMemoryAnchorStore()
    private val clock = Clock { 1_790_000_000_000L }

    private fun boot(): SabouCore = SabouCore.open(JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:$file")).also { open += it }, anchors, clock)

    @AfterTest fun close() { open.forEach { it.close() }; dir.toFile().deleteRecursively() }

    @Test fun packsArePersistedAndKeptByEditsThatDoNotNameThem() {
        val core = boot()
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val sack = PackUnit("کیسه", Quantity.units(10))
        val id = core.inventory.createItem(CreateItem(GlobalId.new(), "برنج", StockUnit.KILOGRAM, Quantity.ZERO, packs = listOf(sack))).resultId

        val item = core.overview.items().single { it.id == id }
        assertEquals(listOf(sack), item.packs)
        assertEquals(Quantity.units(25), Units.toStock(item, Quantity.of(2_500_000), EntryUnit.Pack("کیسه")))
        assertEquals(Quantity.of(250_000), Units.toStock(item, Quantity.units(250), EntryUnit.Metric(StockUnit.GRAM)))

        // An edit that leaves packs out keeps them; one that names them replaces them.
        core.inventory.updateItem(UpdateItem(GlobalId.new(), id, "برنج ایرانی", Quantity.ZERO, Quantity.ZERO, "", "", null, emptySet(), true))
        assertEquals(listOf(sack), core.overview.items().single { it.id == id }.packs)
        core.inventory.updateItem(UpdateItem(GlobalId.new(), id, "برنج ایرانی", Quantity.ZERO, Quantity.ZERO, "", "", null, emptySet(), true,
            packs = listOf(sack, PackUnit("گونی", Quantity.units(50)))))

        open.forEach { it.close() }; open.clear()
        val reopened = boot().also { it.identity.login("owner", "123456".toCharArray()) }
        val again = reopened.overview.items().single { it.id == id }
        assertEquals(listOf("کیسه", "گونی"), again.packs.map { it.name })
        assertEquals(Quantity.units(50), again.packs[1].contains)
    }

    @Test fun invalidPacksAreRefused() {
        val core = boot()
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        listOf(
            listOf(PackUnit("کیسه", Quantity.ZERO)),
            listOf(PackUnit(" ", Quantity.units(1))),
            listOf(PackUnit("کیسه", Quantity.units(1)), PackUnit("کیسه ", Quantity.units(2))),
        ).forEach { packs ->
            assertFailsWith<DomainException> { core.inventory.createItem(CreateItem(GlobalId.new(), "آرد", StockUnit.KILOGRAM, Quantity.ZERO, packs = packs)) }
        }
    }
}
