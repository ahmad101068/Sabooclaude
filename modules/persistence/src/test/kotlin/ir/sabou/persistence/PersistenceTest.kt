package ir.sabou.persistence

import ir.sabou.inventory.Item
import ir.sabou.inventory.Location
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Attachment
import ir.sabou.purchasing.AccountLine
import ir.sabou.purchasing.CreditAllocation
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.purchasing.OrderLine
import ir.sabou.purchasing.OrderStatus
import ir.sabou.purchasing.PurchaseInvoice
import ir.sabou.purchasing.PurchaseOrder
import ir.sabou.purchasing.PurchaseReturn
import ir.sabou.purchasing.ReviewLine
import ir.sabou.purchasing.ReviewResolution
import ir.sabou.purchasing.Supplier
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistenceTest {
    private fun memoryDb() = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite::memory:"))

    @Test fun jsonRoundTripsPersianTextEscapesAndNesting() {
        val value = mapOf(
            "name" to "پیتزا \"مخصوص\"\n\t\\ ۱۲۳", "n" to -9_000_000_000_000_000L, "ok" to true, "none" to null,
            "list" to listOf(1L, mapOf("x" to "y"), emptyList<Any>()), "ctl" to "\u0001",
        )
        assertEquals(value, Json.decode(Json.encode(value)))
        assertFailsWith<IllegalArgumentException> { Json.decode("{\"a\":1} x") }
    }

    @Test fun migrationIsIdempotentAndRefusesANewerDatabase() {
        val db = memoryDb()
        Schema.migrate(db); Schema.migrate(db)
        assertEquals(Schema.latestVersion.toLong(), db.query("SELECT MAX(version) AS v FROM schema_version").single().long("v"))
        db.execute("INSERT INTO schema_version(version) VALUES (?)", Schema.latestVersion + 1)
        val e = assertFailsWith<IllegalStateException> { Schema.migrate(db) }
        assertTrue(e.message!!.startsWith("DATABASE_NEWER_THAN_APP"))
    }

    @Test fun postedRecordsAreImmutableAtTheDatabaseLevel() {
        val db = memoryDb(); Schema.migrate(db)
        db.execute("INSERT INTO accounts(code, doc) VALUES ('1101', '{}')")
        db.execute("INSERT INTO journal_entries(id, number, date, scope, source_module, source_type, source_id, doc) VALUES ('e', 1, 1, 'ORG', 'X', 'T', 's', '{}')")
        db.execute("INSERT INTO journal_lines(entry_id, line_no, account, scope, date, debit, credit, contributor, memo) VALUES ('e', 0, '1101', 'ORG', 1, 5, 0, 'X', '')")
        listOf(
            "UPDATE journal_lines SET debit = 6", "DELETE FROM journal_lines", "UPDATE journal_entries SET number = 2", "DELETE FROM journal_entries",
        ).forEach { sql -> assertTrue(assertFailsWith<Exception> { db.execute(sql) }.message!!.contains("IMMUTABLE")) }
        // A line must be one-sided and non-negative.
        assertFailsWith<Exception> { db.execute("INSERT INTO journal_lines VALUES ('e', 1, '1101', 'ORG', 1, 5, 5, 'X', '')") }
        assertFailsWith<Exception> { db.execute("INSERT INTO journal_lines VALUES ('e', 2, '1101', 'ORG', 1, -5, 0, 'X', '')") }
        // Unknown account is rejected by the foreign key.
        assertFailsWith<Exception> { db.execute("INSERT INTO journal_lines VALUES ('e', 3, '9999', 'ORG', 1, 5, 0, 'X', '')") }
    }

    @Test fun unitOfWorkRollsBackEverythingAndNestedCallsJoin() {
        val db = memoryDb(); Schema.migrate(db)
        val uow = SqlUnitOfWork(db)
        assertFailsWith<IllegalStateException> {
            uow.transaction {
                db.execute("INSERT INTO meta(key, value) VALUES ('a', '1')")
                uow.transaction { db.execute("INSERT INTO meta(key, value) VALUES ('b', '2')") }
                error("boom")
            }
        }
        assertEquals(0, db.query("SELECT COUNT(*) AS n FROM meta").single().long("n"))
        uow.transaction { db.execute("INSERT INTO meta(key, value) VALUES ('a', '1')") }
        assertEquals(1, db.query("SELECT COUNT(*) AS n FROM meta").single().long("n"))
    }

    @Test fun stockBalanceCannotGoNegativeEvenIfCodeTried() {
        val db = memoryDb(); Schema.migrate(db)
        db.execute("INSERT INTO items(id, doc) VALUES ('i', '{}')"); db.execute("INSERT INTO locations(id, doc) VALUES ('l', '{}')")
        assertFailsWith<Exception> { db.execute("INSERT INTO stock_balances VALUES ('i', 'l', -1, 0)") }
    }

    @Test fun employmentDatesPartialMonthsAndProrationSurviveTheDatabase() {
        val db = memoryDb(); Schema.migrate(db)
        val branch = ir.sabou.kernel.Scope.Branch(ir.sabou.kernel.BranchId(ir.sabou.kernel.GlobalId.new()))
        val people = SqlPersonnelStore(db)
        val e = ir.sabou.payroll.Employee(ir.sabou.kernel.GlobalId.new(), "گارسون", "0499370899", branch, ir.sabou.kernel.Money.of(1_000),
            isActive = false, startDate = ir.sabou.kernel.BusinessDate(20_010), endDate = ir.sabou.kernel.BusinessDate(20_020))
        people.saveEmployee(e)
        assertEquals(e, people.employee(e.id))
        val plain = e.copy(id = ir.sabou.kernel.GlobalId.new(), nationalId = "0084575948", isActive = true, startDate = null, endDate = null)
        people.saveEmployee(plain)
        assertEquals(plain, people.employee(plain.id))

        val policy = ir.sabou.payroll.StatutoryPolicy(
            version = "T", from = ir.sabou.kernel.BusinessDate(20_000), to = ir.sabou.kernel.BusinessDate(20_100), standardMonthlyMinutes = 11_520,
            overtimeMultiplierPercent = 140, employeeInsuranceBp = 700, employerInsuranceBp = 2_000, unemploymentInsuranceBp = 300,
            maxInsurableMonthly = ir.sabou.kernel.Money.of(1_000_000), insuranceTaxExemptNumerator = 2, insuranceTaxExemptDenominator = 7,
            taxBrackets = listOf(ir.sabou.payroll.TaxBracket(null, 0)), prorationDays = 31,
        )
        SqlPolicyStore(db).save(policy)
        assertEquals(listOf(policy), SqlPolicyStore(db).all())

        val runs = SqlPayrollStore(db)
        val slips = listOf(policy.calculate(e.id, ir.sabou.kernel.Money.of(31_000), 0, 0, payableDays = 10), policy.calculate(plain.id, ir.sabou.kernel.Money.of(1_000), 0, 0))
        val run = ir.sabou.payroll.PayrollRun(ir.sabou.kernel.GlobalId.new(), branch, ir.sabou.kernel.BusinessDate(20_000), ir.sabou.kernel.BusinessDate(20_029), "T",
            slips, ir.sabou.payroll.RunStatus.DRAFT, ir.sabou.kernel.GlobalId.new(), null, null)
        runs.saveRun(run)
        assertEquals(run, runs.run(run.id))
        assertEquals(10, runs.run(run.id)!!.payslips.first().payableDays)
    }

    @Test fun upgradingFromVersion1IndexesExistingStockMovements() {
        val db = memoryDb()
        // A database as version 1 left it, with one movement already recorded.
        db.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
        Schema.migrations.first { it.version == 1 }.statements.forEach { db.execute(it) }
        db.execute("INSERT INTO schema_version(version) VALUES (1)")
        val branch = ir.sabou.kernel.Scope.Branch(ir.sabou.kernel.BranchId(ir.sabou.kernel.GlobalId.new()))
        val items = SqlItemStore(db); val locations = SqlLocationStore(db)
        val item = ir.sabou.inventory.Item(ir.sabou.kernel.GlobalId.new(), "برنج", ir.sabou.inventory.StockUnit.KILOGRAM, ir.sabou.kernel.Quantity.ZERO)
        val loc = ir.sabou.inventory.Location(ir.sabou.kernel.GlobalId.new(), "انبار", branch)
        items.save(item); locations.save(loc)
        val old = ir.sabou.inventory.StockMovement(
            ir.sabou.kernel.GlobalId.new(), item.id, loc.id, ir.sabou.inventory.MovementKind.RECEIPT, 5_000_000, 900_000,
            ir.sabou.kernel.BusinessDate(20_000), null,
            ir.sabou.ledger.SourceDocument(ir.sabou.platform.ModuleId.PURCHASING, "PURCHASE_INVOICE", ir.sabou.kernel.GlobalId.new()), null, 1L,
        )
        db.execute("INSERT INTO stock_movements (id, source_type, source_id, reversal_of, doc) VALUES (?, ?, ?, ?, ?)", old.id.value, "PURCHASE_INVOICE",
            old.source.id.value, null,
            Json.encode(mapOf("id" to old.id.value, "item" to item.id.value, "location" to loc.id.value, "kind" to "RECEIPT", "qty" to 5_000_000L,
                "value" to 900_000L, "date" to 20_000L, "journal" to null, "source" to Codec.source(old.source), "reversalOf" to null, "recordedAt" to 1L)))
        // An item stored by version 1 has none of the new fields: it reads with defaults.
        db.execute("UPDATE items SET doc = ? WHERE id = ?", Json.encode(mapOf("id" to item.id.value, "name" to "برنج", "unit" to "KILOGRAM", "minimum" to 0L, "active" to true)), item.id.value)

        Schema.migrate(db)
        val stock = SqlStockStore(db)
        assertEquals(listOf(old), stock.movementsAt(loc.id, ir.sabou.kernel.BusinessDate(20_000), ir.sabou.kernel.BusinessDate(20_000)))
        assertEquals(5_000_000, stock.totalsBefore(loc.id, ir.sabou.kernel.BusinessDate(20_001)).single().quantity)
        assertTrue(stock.totalsBefore(loc.id, ir.sabou.kernel.BusinessDate(20_000)).isEmpty())
        assertEquals(item, items.byId(item.id))
        // New movements are indexed as they are written.
        val next = old.copy(id = ir.sabou.kernel.GlobalId.new(), kind = ir.sabou.inventory.MovementKind.ISSUE, quantityDelta = -1_000_000, valueDelta = -180_000,
            date = ir.sabou.kernel.BusinessDate(20_003))
        stock.insertMovement(next)
        assertEquals(4_000_000, stock.totalsBefore(loc.id, ir.sabou.kernel.BusinessDate(20_004)).single().quantity)
        assertEquals(listOf(next), stock.movementsAt(loc.id, ir.sabou.kernel.BusinessDate(20_001), ir.sabou.kernel.BusinessDate(20_010)))
        // The index is as immutable as the movements.
        assertTrue(assertFailsWith<Exception> { db.execute("DELETE FROM stock_movement_index") }.message!!.contains("IMMUTABLE"))
    }

    @Test fun itemDetailsRecipeYieldAndPrepRecipesSurviveTheDatabase() {
        val db = memoryDb(); Schema.migrate(db)
        val items = SqlItemStore(db)
        val a = ir.sabou.kernel.GlobalId.new()
        val item = ir.sabou.inventory.Item(ir.sabou.kernel.GlobalId.new(), "سس", ir.sabou.inventory.StockUnit.KILOGRAM, ir.sabou.kernel.Quantity.units(2),
            parLevel = ir.sabou.kernel.Quantity.units(6), shelf = "یخچال ۱", allergens = "لبنیات", prepared = true, preferredSupplierId = a, approvedSupplierIds = setOf(a))
        val tomato = item.copy(id = ir.sabou.kernel.GlobalId.new(), name = "گوجه", prepared = false, approvedSupplierIds = emptySet(), preferredSupplierId = null)
        items.save(item); items.save(tomato)
        assertEquals(item, items.byId(item.id))
        val recipes = SqlRecipeStore(db)
        val prep = ir.sabou.inventory.PrepRecipe(ir.sabou.kernel.GlobalId.new(), item.id, 1, ir.sabou.kernel.BusinessDate(20_000), ir.sabou.kernel.Quantity.units(4),
            listOf(ir.sabou.inventory.RecipeLine(tomato.id, ir.sabou.kernel.Quantity.units(5), 90)))
        recipes.savePrepVersion(prep)
        assertEquals(listOf(prep), recipes.prepVersions(item.id))
    }

    // ------------------------------------------------------------ Stage B: purchasing

    private val branchA = Scope.Branch(BranchId(GlobalId.new()))
    private val branchB = Scope.Branch(BranchId(GlobalId.new()))

    private fun seed(db: SqlDatabase): Triple<Supplier, Item, Location> {
        val supplier = Supplier(GlobalId.new(), "لبنیات", "021", deliveryDays = setOf(1, 4), cutoffMinutes = 840, leadDays = 2, note = "تحویل صبح")
        SqlSupplierStore(db).save(supplier)
        val item = Item(GlobalId.new(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)
        SqlItemStore(db).save(item)
        val loc = Location(GlobalId.new(), "انبار", branchA)
        SqlLocationStore(db).save(loc)
        return Triple(supplier, item, loc)
    }

    @Test fun purchasingDocumentsSurviveTheDatabase() {
        val db = memoryDb(); Schema.migrate(db)
        val (supplier, item, loc) = seed(db)
        val suppliers = SqlSupplierStore(db)
        assertEquals(supplier, suppliers.byId(supplier.id))
        suppliers.saveAlias(supplier.id, "موتزارلا", item.id); suppliers.saveAlias(supplier.id, "موتزارلا", item.id)
        assertEquals(mapOf("موتزارلا" to item.id), suppliers.aliases(supplier.id))
        val store = SqlPurchaseStore(db)
        val order = PurchaseOrder(GlobalId.new(), store.nextOrderNumber(), supplier.id, branchA, loc.id, BusinessDate(20_000), BusinessDate(20_002),
            listOf(OrderLine(item.id, Quantity.units(3), Money.of(200_000))), "صبح زود", OrderStatus.OPEN)
        store.saveOrder(order)
        assertEquals(1, order.number); assertEquals(2, store.nextOrderNumber())
        val invoice = PurchaseInvoice(
            GlobalId.new(), supplier.id, "A1", branchA, loc.id, BusinessDate(20_001), BusinessDate(20_030),
            listOf(InvoiceLine(item.id, Quantity.units(3), Money.of(630_000), "Mozzarella")), Money.of(1_030_000), InvoiceStatus.POSTED,
            listOf(AccountLine(StandardAccounts.RENT, Money.of(100_000), null, "اجاره"), AccountLine(StandardAccounts.UTILITIES, Money.of(100_000), branchB, "")),
            listOf(ReviewLine("دستکش", "۲ بسته", Money.of(100_000)),
                ReviewLine("سس", "", Money.of(100_000), ReviewResolution(item.id, Quantity.units(1), loc.id, null, GlobalId.new(), GlobalId.new(), BusinessDate(20_002)))),
            "توضیح", order.id, listOf(GlobalId.new(), GlobalId.new()),
        )
        store.saveInvoice(invoice)
        assertEquals(invoice, store.invoice(invoice.id))
        val noGoods = invoice.copy(id = GlobalId.new(), supplierInvoiceNo = "A2", locationId = null, lines = emptyList(), accountLines = emptyList(), reviewLines = emptyList(), orderId = null, journalIds = emptyList())
        store.saveInvoice(noGoods)
        assertEquals(noGoods, store.invoice(noGoods.id))
        store.saveOrder(order.copy(status = OrderStatus.RECEIVED, invoiceId = invoice.id))
        assertEquals(order.copy(status = OrderStatus.RECEIVED, invoiceId = invoice.id), store.order(order.id))
        val allocation = CreditAllocation(GlobalId.new(), supplier.id, branchA, invoice.id, Money.of(50_000), BusinessDate(20_003), null)
        store.saveAllocation(allocation)
        store.saveAllocation(allocation.copy(released = true))
        assertEquals(listOf(allocation.copy(released = true)), store.allocationsTo(invoice.id))
        assertEquals(listOf(allocation.copy(released = true)), store.allocationsOf(supplier.id, branchA))
        assertTrue(store.allocationsOf(supplier.id, branchB).isEmpty())

        val files = SqlAttachmentStore(db)
        val bytes = ByteArray(300_000) { (it * 7).toByte() }
        val meta = Attachment(GlobalId.new(), "PURCHASE_INVOICE", invoice.id, "فاکتور.jpg", "image/jpeg", "abc", bytes.size, 5L)
        files.save(meta, bytes)
        assertEquals(listOf(meta), files.of("PURCHASE_INVOICE", invoice.id))
        assertEquals(meta, files.meta(meta.id))
        assertContentEquals(bytes, files.content(meta.id))
        assertTrue(assertFailsWith<Exception> { db.execute("UPDATE attachments SET mime = 'x'") }.message!!.contains("IMMUTABLE"))
    }

    @Test fun upgradingFromVersion2RecordsEarlierReturnCreditsAgainstTheirInvoices() {
        val db = memoryDb()
        db.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
        Schema.migrations.filter { it.version <= 2 }.sortedBy { it.version }.forEach { m -> m.statements.forEach { db.execute(it) }; m.backfill?.invoke(db) }
        db.execute("INSERT INTO schema_version(version) VALUES (2)")
        val (supplier, item, loc) = seed(db)
        // Rows exactly as version 2 wrote them (no new fields).
        val invoiceId = GlobalId.new()
        db.execute("INSERT INTO purchase_invoices (id, supplier_id, number, doc) VALUES (?, ?, ?, ?)", invoiceId.value, supplier.id.value, "9",
            Json.encode(mapOf("id" to invoiceId.value, "supplier" to supplier.id.value, "number" to "9", "scope" to Codec.scope(branchA), "location" to loc.id.value,
                "date" to 20_000L, "due" to 20_010L, "lines" to listOf(mapOf("item" to item.id.value, "qty" to 2_000_000L, "value" to 400_000L)), "total" to 400_000L, "status" to "POSTED")))
        val returnId = GlobalId.new()
        db.execute("INSERT INTO purchase_returns (id, invoice_id, doc) VALUES (?, ?, ?)", returnId.value, invoiceId.value,
            Json.encode(mapOf("id" to returnId.value, "invoice" to invoiceId.value, "lines" to listOf(mapOf("item" to item.id.value, "qty" to 1_000_000L, "value" to 200_000L)),
                "credit" to 200_000L, "date" to 20_001L)))
        Schema.migrate(db)
        val store = SqlPurchaseStore(db)
        val inv = store.invoice(invoiceId)!!
        assertEquals(loc.id, inv.locationId)
        assertTrue(inv.accountLines.isEmpty() && inv.reviewLines.isEmpty() && inv.note.isEmpty() && inv.journalIds.isEmpty())
        val allocation = store.allocationsTo(invoiceId).single()
        assertEquals(200_000, allocation.amount.rial)
        assertEquals(returnId, allocation.returnId)
        assertEquals(branchA, allocation.scope)
        assertEquals(listOf(PurchaseReturn(returnId, invoiceId, listOf(InvoiceLine(item.id, Quantity.units(1), Money.of(200_000))), Money.of(200_000), BusinessDate(20_001))),
            store.returns(invoiceId))
    }
}
