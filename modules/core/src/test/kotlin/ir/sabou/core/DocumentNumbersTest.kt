package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.persistence.Schema
import ir.sabou.platform.Command
import ir.sabou.platform.DocumentSeries
import ir.sabou.platform.IssuesDocument
import ir.sabou.platform.NoDocument
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.ReverseTreasuryDocument
import ir.sabou.treasury.TreasuryOperations
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Document numbers (ADR-0016) end to end on a real SQLite file. */
class DocumentNumbersTest {
    private val dir = Files.createTempDirectory("numbers")
    private val file = dir.resolve("n.db")
    private val open = mutableListOf<JdbcSqlDatabase>()
    private val clock = Clock { 1_790_000_000_000L }
    private val day = JalaliCalendar.date(1405, 7, 18)

    private fun boot(): SabouCore = SabouCore.open(JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:$file")).also { open += it }, InMemoryAnchorStore(), clock)

    @AfterTest fun close() { open.forEach { it.close() }; dir.toFile().deleteRecursively() }

    private fun ready(): Triple<SabouCore, Scope.Branch, GlobalId> {
        val core = boot()
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val cash = core.overview.treasury().single().account.id
        return Triple(core, branch, cash)
    }

    private fun receipt(core: SabouCore, branch: Scope.Branch, cash: GlobalId, amount: Long, date: BusinessDate = day) =
        core.treasury.receipt(RecordReceipt(GlobalId.new(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, Money.of(amount), date, "آورده")).resultId

    @Test fun documentsAreNumberedPerSeriesBranchAndFiscalYearWithoutGaps() {
        val (core, branch, cash) = ready()
        val r1 = receipt(core, branch, cash, 10_000_000)
        val r2 = receipt(core, branch, cash, 5_000_000)
        val p1 = core.treasury.payment(RecordPayment(GlobalId.new(), branch, cash, PaymentPurpose.RENT, Money.of(1_000_000), day, "اجاره")).resultId
        assertEquals("در-1405-00001", core.numbers.of(r1)!!.text)
        assertEquals("در-1405-00002", core.numbers.of(r2)!!.text)
        assertEquals("پر-1405-00001", core.numbers.of(p1)!!.text)       // its own series

        // A command that fails consumes no number.
        assertFailsWith<DomainException> {
            core.treasury.payment(RecordPayment(GlobalId.new(), branch, cash, PaymentPurpose.RENT, Money.of(999_000_000_000), day, "بیش از موجودی"))
        }
        val p2 = core.treasury.payment(RecordPayment(GlobalId.new(), branch, cash, PaymentPurpose.RENT, Money.of(1_000_000), day, "اجاره"))
        assertEquals("پر-1405-00002", core.numbers.of(p2.resultId)!!.text)

        // A retried command (same id) is not numbered twice.
        val retryId = GlobalId.new()
        val once = core.treasury.receipt(RecordReceipt(retryId, branch, cash, ReceiptPurpose.OTHER_INCOME, Money.of(1_000), day, "x"))
        val again = core.treasury.receipt(RecordReceipt(retryId, branch, cash, ReceiptPurpose.OTHER_INCOME, Money.of(1_000), day, "x"))
        assertEquals(once.resultId, again.resultId)
        assertEquals("در-1405-00003", core.numbers.of(once.resultId)!!.text)
        assertEquals("در-1405-00004", core.numbers.of(receipt(core, branch, cash, 1_000))!!.text)

        // A new fiscal year starts again at 1.
        val nextYear = receipt(core, branch, cash, 1_000, JalaliCalendar.date(1406, 1, 1))
        assertEquals("در-1406-00001", core.numbers.of(nextYear)!!.text)

        // Another branch counts separately.
        val other = Scope.Branch(core.identity.createBranch("شعبه دو"))
        val otherCash = core.treasury.openAccount(ir.sabou.treasury.OpenTreasuryAccount(GlobalId.new(), other, "صندوق دو", ir.sabou.treasury.TreasuryKind.CASH)).resultId
        assertEquals("در-1405-00001", core.numbers.of(receipt(core, other, otherCash, 1_000))!!.text)
    }

    @Test fun theJournalIsNumberedPerFiscalYearForTheWholeOrganization() {
        val (core, branch, cash) = ready()
        receipt(core, branch, cash, 10_000_000)
        receipt(core, branch, cash, 10_000_000)
        val journals = core.journals.all()
        val numbers = journals.map { core.numbers.of(DocumentSeries.JOURNAL, it.id)!!.sequence }
        assertEquals((1L..journals.size).toList(), numbers.sorted())
    }

    @Test fun aReversalIsADocumentOfItsOwnThatNamesWhatItReverses() {
        val (core, branch, cash) = ready()
        val r = receipt(core, branch, cash, 10_000_000)
        val commandId = GlobalId.new()
        core.treasury.reverse(ReverseTreasuryDocument(commandId, branch, TreasuryOperations.RECEIPT, r, day, "ثبت اشتباه"))
        // The reversal document is numbered under its command's id and points at the receipt's number.
        val record = core.numbers.record(DocumentSeries.REVERSAL, commandId)!!
        assertEquals("بر-1405-00001", record.number.text)
        assertEquals("در-1405-00001", record.reverses!!.text)
        // The reversal journal's description names the original journal by its legal number.
        assertTrue(core.journals.all().last().description.contains("سح-1405-"))
    }

    @Test fun aPurchaseInvoiceAndItsImmediatePaymentAreBothNumbered() {
        val (core, branch, cash) = ready()
        receipt(core, branch, cash, 100_000_000)
        val item = core.inventory.createItem(CreateItem(GlobalId.new(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val kitchen = core.overview.locations(branch).single().id
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(GlobalId.new(), "لبنیات", "021")).resultId
        val invoice = core.purchasing.postInvoice(PostPurchaseInvoice(GlobalId.new(), branch, supplier, "A-77", kitchen, day, day,
            listOf(InvoiceLine(item, Quantity.units(10), Money.of(30_000_000))), ir.sabou.purchasing.ImmediatePayment(cash, Money.of(10_000_000)))).resultId
        assertEquals("فخ-1405-00001", core.numbers.of(invoice)!!.text)
        assertEquals(1, core.db.query("SELECT 1 AS x FROM document_numbers WHERE series = 'PAYMENT'").size)
    }

    @Test fun concurrentDocumentsGetUniqueConsecutiveNumbers() {
        val (core, branch, cash) = ready()
        val pool = Executors.newFixedThreadPool(8)
        repeat(200) { pool.execute { receipt(core, branch, cash, 1_000) } }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.MINUTES)
        val seqs = core.db.query("SELECT sequence FROM document_numbers WHERE series = 'RECEIPT' ORDER BY sequence").map { it.long("sequence") }
        assertEquals((1L..200L).toList(), seqs)
    }

    @Test fun migrationSevenGivesExistingJournalsTheirLegalNumbers() {
        val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:$file")).also { open += it }
        Schema.migrate(db, 6)
        // Three journals of a database written before numbering: two in 1405, one in 1406.
        listOf(1L to JalaliCalendar.date(1405, 5, 1), 2L to JalaliCalendar.date(1405, 12, 29), 3L to JalaliCalendar.date(1406, 1, 1)).forEach { (n, d) ->
            db.execute(
                "INSERT INTO journal_entries (id, number, date, scope, source_module, source_type, source_id, reversal_of, doc) VALUES (?, ?, ?, 'ORG', 'LEDGER_MANUAL', 'X', ?, NULL, '{}')",
                GlobalId.new().value, n, d.epochDay, GlobalId.new().value,
            )
        }
        Schema.migrate(db)
        val rows = db.query("SELECT fiscal_year, sequence FROM document_numbers WHERE series = 'JOURNAL' ORDER BY fiscal_year, sequence")
            .map { it.long("fiscal_year") to it.long("sequence") }
        assertEquals(listOf(1405L to 1L, 1405L to 2L, 1406L to 1L), rows)
        assertEquals(2L, db.query("SELECT last FROM document_sequences WHERE series = 'JOURNAL' AND fiscal_year = 1405").single().long("last"))
    }

    /**
     * Architecture rule: every command of the product declares whether it issues a numbered document. A new
     * command without a declaration fails this test, so numbering cannot be forgotten by omission.
     */
    @Test fun everyCommandDeclaresWhetherItIssuesADocument() {
        val commands = productClasses().filter { Command::class.java.isAssignableFrom(it) && !it.isInterface }
        assertTrue(commands.size >= 70, "found only ${commands.size} commands")
        val undeclared = commands.filter { it.getAnnotation(IssuesDocument::class.java) == null && it.getAnnotation(NoDocument::class.java) == null }
        val both = commands.filter { it.getAnnotation(IssuesDocument::class.java) != null && it.getAnnotation(NoDocument::class.java) != null }
        assertEquals(emptyList(), undeclared.map { it.name }, "commands without @IssuesDocument or @NoDocument")
        assertEquals(emptyList(), both.map { it.name })
    }

    /** Classes of the production modules on the test classpath (directories or jars), never test classes. */
    private fun productClasses(): List<Class<*>> {
        val names = ArrayList<String>()
        System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
            .filter { e -> listOf("test", "Test").none { e.path.contains(it) } }
            .forEach { entry ->
                if (entry.isDirectory) {
                    entry.walkTopDown().filter { it.extension == "class" }.forEach { names += it.relativeTo(entry).path.removeSuffix(".class").replace(File.separatorChar, '.') }
                } else if (entry.name.endsWith(".jar")) {
                    JarFile(entry).use { jar -> jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.forEach { names += it.removeSuffix(".class").replace('/', '.') } }
                }
            }
        return names.filter { it.startsWith("ir.sabou.") }.mapNotNull { runCatching { Class.forName(it, false, javaClass.classLoader) }.getOrNull() }
    }
}
