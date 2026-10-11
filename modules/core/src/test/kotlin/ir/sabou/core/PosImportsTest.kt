package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.posimport.FileCurrency
import ir.sabou.posimport.Text
import ir.sabou.sales.CustomerType
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.SaleStatus
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.TreasuryKind
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A POS day imported end to end on a real SQLite file (ADR-0026). */
class PosImportsTest {
    private val dir = Files.createTempDirectory("pos")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("p.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 18)
    private lateinit var branch: Scope.Branch
    private var cash: GlobalId = GlobalId.new()
    private var card: GlobalId = GlobalId.new()
    private val menu = HashMap<String, GlobalId>()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private fun fixture(name: String): PosFile {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        return PosFile(name, File(root, "modules/posimport/src/test/resources/sepand/$name").readBytes())
    }

    private fun setUp() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        cash = core.overview.treasury().single().account.id
        card = core.treasury.openAccount(OpenTreasuryAccount(GlobalId.new(), branch, "کارت‌خوان", TreasuryKind.CARD_TERMINAL)).resultId
        val flour = core.inventory.createItem(CreateItem(GlobalId.new(), "آرد", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        // Menu names as the restaurant wrote them (Persian ی/ک); the POS writes some with Arabic letters.
        listOf("پیتزا مخصوص", "پیتزا پپرونی", "برگر کلاسیک", "نوشابه قوطی", "سالاد سزار").forEach { name ->
            val id = core.inventory.defineMenuItem(DefineMenuItem(GlobalId.new(), name)).resultId
            core.inventory.publishRecipe(PublishRecipe(GlobalId.new(), id, BusinessDate(19_000), listOf(RecipeLine(flour, Quantity.of(100_000)))))
            menu[name] = id
        }
    }

    @Test fun aSalesByItemReportAloneBecomesAPostedSaleOnceTheDayIsChosen() {
        setUp()
        val files = listOf(fixture("F1_sepand_item_sales_clean.xlsx"))
        val first = core.posImports.preview(branch, files, PosChoices())
        assertFalse(first.ready)
        assertTrue(first.problems.any { "روز" in it })                    // the file has no date: the person chooses
        val p = core.posImports.preview(branch, files, PosChoices(date = day))
        assertEquals(emptyList(), p.problems)
        assertEquals(FileCurrency.RIAL, p.currency)
        assertEquals(598_890_000L + 1_210_000, p.gross)                   // before discounts
        assertEquals(1_210_000L, p.discount)
        assertEquals(598_890_000L, p.payable)
        assertTrue(p.lines.all { it.menuItem != null })                   // «برگر كلاسيك» matched «برگر کلاسیک»

        val saleId = core.posImports.commit(GlobalId.new(), branch, files, PosChoices(date = day), PosAccounts(single = cash)).resultId
        val sale = core.sales.sale(saleId)!!
        assertEquals(SaleStatus.POSTED, sale.status)
        assertEquals(Money.of(598_890_000), sale.payable)
        assertEquals(598_890_000L, core.overview.treasury().single { it.account.id == cash }.balance)
        assertEquals(598_890_000L, core.ledger.balance(StandardAccounts.FOOD_SALES).rial.let { -it })
        // The same day, file or bills cannot come in twice.
        assertTrue(core.posImports.preview(branch, files, PosChoices(date = day)).alreadyImported)
        assertEquals("INVALID_STATE:POS_IMPORT:NOT_READY", assertFailsWith<DomainException> {
            core.posImports.commit(GlobalId.new(), branch, files, PosChoices(date = day), PosAccounts(single = cash))
        }.error.code)
        // Reversing the imported sale lets a corrected file in.
        core.salesOps.reverse(ir.sabou.sales.ReverseDailySale(GlobalId.new(), branch, saleId, day, "فایل اشتباه"))
        assertTrue(core.posImports.preview(branch, files, PosChoices(date = day)).ready)
    }

    @Test fun itemsAndInvoicesTogetherSplitThePaymentsAndTheCreditCustomer() {
        setUp()
        val arian = core.salesOps.registerCustomer(RegisterCustomer(GlobalId.new(), branch, "شرکت آرین", CustomerType.COMPANY, "021", Money.of(100_000_000))).resultId
        // A matching pair in Toman, as CSV: 2 pizzas and 1 unknown name.
        val items = "از تاریخ : 1405/07/18 تا تاریخ : 1405/07/18\nنام کالا,تعداد,فی (تومان),مبلغ (تومان),تخفیف,مبلغ خالص\nپیتزا مخصوص,2,450000,900000,0,900000\nسالاد ویژه,1,240000,240000,0,240000\n"
        val bills = "شماره فاکتور,تاریخ,مشتری,جمع کل,تخفیف,سرویس,مالیات,قابل پرداخت,نقدی,کارتخوان,اعتباری,وضعیت\n" +
            "1,1405/07/18,,450000,50000,0,0,400000,400000,0,0,عادی\n" +
            "2,1405/07/18,شرکت آرین,690000,0,69000,0,759000,0,259000,500000,عادی\n" +
            "3,1405/07/18,,300000,0,0,0,300000,300000,0,0,ابطال شده\n"
        val files = listOf(PosFile("items.csv", items.toByteArray()), PosFile("bills.csv", bills.toByteArray()))
        val p = core.posImports.preview(branch, files, PosChoices())
        assertEquals(FileCurrency.TOMAN, p.currency)
        assertEquals(day, p.date)
        assertEquals(listOf("سالاد ویژه"), p.unknown.map { it.name })
        val choices = PosChoices(matches = mapOf(Text.key("سالاد ویژه") to menu.getValue("سالاد سزار")))
        val ready = core.posImports.preview(branch, files, choices)
        assertEquals(emptyList(), ready.problems)
        assertEquals(11_400_000L, ready.gross)                              // Rial
        assertEquals(500_000L, ready.discount); assertEquals(690_000L, ready.service)
        assertEquals(4_000_000L, ready.cash); assertEquals(2_590_000L, ready.card)
        assertEquals(arian, ready.credits.single().customer!!.id)
        assertEquals(1, ready.voided)

        val saleId = core.posImports.commit(GlobalId.new(), branch, files, choices, PosAccounts(cash = cash, card = card)).resultId
        val sale = core.sales.sale(saleId)!!
        assertEquals(Money.of(11_590_000), sale.payable)
        assertEquals(5_000_000L, core.salesOps.customerBalance(arian).rial)
        assertEquals(2_590_000L, core.overview.treasury().single { it.account.id == card }.balance)
        // The match is remembered for the next day's file.
        assertEquals(menu.getValue("سالاد سزار"), core.posImportStore.aliases(PosImports.SEPAND)[Text.key("سالاد ویژه")])
    }

    @Test fun twoReportsThatDoNotAgreeAreNotImported() {
        setUp()
        val p = core.posImports.preview(branch, listOf(fixture("F1_sepand_item_sales_clean.xlsx"), fixture("F3_sepand_invoices.xlsx")), PosChoices(date = day))
        assertTrue(p.problems.any { "برابر نیست" in it })
    }
}
