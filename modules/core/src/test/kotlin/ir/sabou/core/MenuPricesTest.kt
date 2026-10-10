package ir.sabou.core

import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.SetMenuPrice
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Menu prices (ADR-0019) on a real SQLite file: persisted, append-only, and old sale lines still read. */
class MenuPricesTest {
    private val dir = Files.createTempDirectory("prices")
    private val file = dir.resolve("m.db")
    private val open = mutableListOf<JdbcSqlDatabase>()
    private val anchors = InMemoryAnchorStore()
    private val clock = Clock { 1_790_000_000_000L }
    private val day = JalaliCalendar.date(1405, 7, 18)

    private fun boot(): SabouCore = SabouCore.open(JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:$file")).also { open += it }, anchors, clock)

    @AfterTest fun close() { open.forEach { it.close() }; dir.toFile().deleteRecursively() }

    @Test fun pricesAndPricedLinesSurviveARestartAndVersionsCannotBeEdited() {
        val core = boot()
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).firstOrNull()?.id
            ?: core.inventory.createLocation(CreateLocation(GlobalId.new(), branch, "آشپزخانه")).resultId
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(GlobalId.new(), "پیتزا")).resultId
        core.salesOps.setMenuPrice(SetMenuPrice(GlobalId.new(), Scope.Organization, pizza, day, Money.of(4_500_000)))
        core.salesOps.setMenuPrice(SetMenuPrice(GlobalId.new(), branch, pizza, day, Money.of(4_800_000)))
        val sale = core.salesOps.saveDraft(SaveSaleDraft(GlobalId.new(), branch, day, kitchen,
            listOf(SaleLineInput(pizza, Quantity.units(3), Money.of(4_000_000), "تخفیف افتتاحیه")), Money.ZERO, Money.ZERO, Money.ZERO, emptyList())).resultId

        open.forEach { it.close() }; open.clear()
        val again = boot().also { it.identity.login("owner", "123456".toCharArray()) }
        val view = again.overview.menuPrices(branch, day).single()
        assertEquals(Money.of(4_800_000), view.price)
        assertTrue(view.own)
        assertEquals(Money.of(4_500_000), view.organization)
        assertEquals(2, view.history.size)
        val line = again.sales.sale(sale)!!.lines.single()
        assertEquals(Money.of(4_000_000), line.unitPrice)
        assertEquals(Money.of(12_000_000), line.gross)
        assertEquals(Money.of(4_800_000), line.listPrice)
        assertEquals("تخفیف افتتاحیه", line.overrideReason)

        // A price version is never changed in place: the table refuses it.
        assertFailsWith<Exception> { again.db.execute("UPDATE menu_prices SET doc = '{}'") }
        assertFailsWith<Exception> { again.db.execute("DELETE FROM menu_prices") }
    }

    @Test fun aLineRecordedBeforeMenuPricesReadsWithItsTotalUnchanged() {
        val core = boot()
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val id = GlobalId.new()
        // The shape schema 7 wrote: a total per line, no unit price.
        val doc = """{"id":"${id.value}","scope":"BRANCH:${branch.branchId.value.value}","date":${day.epochDay},"kitchen":"${GlobalId.new().value}",""" +
            """"lines":[{"menuItem":"${GlobalId.new().value}","portions":3000000,"gross":1000000}],"discount":0,"service":0,"tax":0,"settlements":[],""" +
            """"status":"POSTED","revenueJournal":null,"cost":0,"consumed":false,"guests":0,"transactions":0}"""
        core.db.execute("INSERT INTO daily_sales (id, scope, date, status, doc) VALUES (?, ?, ?, 'POSTED', ?)", id.value, "BRANCH:${branch.branchId.value.value}", day.epochDay, doc)
        val line = core.sales.sale(id)!!.lines.single()
        assertEquals(Money.of(1_000_000), line.gross)               // what was posted stays the total
        assertEquals(Money.of(333_333), line.unitPrice)             // per portion, for information
        assertEquals(null, line.listPrice)
    }
}
