package ir.sabou.persistence

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.payroll.AttendanceRecord
import ir.sabou.payroll.Employee
import ir.sabou.payroll.LiabilityKind
import ir.sabou.payroll.PayrollRun
import ir.sabou.payroll.PayrollStore
import ir.sabou.payroll.Payslip
import ir.sabou.payroll.PolicyStore
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.payroll.TaxBracket
import ir.sabou.payroll.PersonnelStore
import ir.sabou.payroll.Remittance
import ir.sabou.payroll.RunStatus
import ir.sabou.payroll.SalaryPayment
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.purchasing.PurchaseInvoice
import ir.sabou.purchasing.PurchaseReturn
import ir.sabou.purchasing.PurchaseStore
import ir.sabou.purchasing.Supplier
import ir.sabou.purchasing.SupplierPayment
import ir.sabou.purchasing.SupplierStore
import ir.sabou.sales.Collection
import ir.sabou.sales.Customer
import ir.sabou.sales.CustomerStore
import ir.sabou.sales.CustomerType
import ir.sabou.sales.DailySale
import ir.sabou.sales.Receivable
import ir.sabou.sales.SaleLine
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.SalesDay
import ir.sabou.sales.SalesStore
import ir.sabou.sales.Settlement

// ---------------------------------------------------------------- Purchasing

class SqlSupplierStore(db: SqlDatabase) : SqlTable(db), SupplierStore {
    private fun read(d: Doc) = Supplier(Codec.id(d.str("id")), d.str("name"), d.str("phone"), d.bool("active"))
    override fun byId(id: GlobalId) = doc("SELECT doc FROM suppliers WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM suppliers ORDER BY rowid").map(::read)
    override fun save(supplier: Supplier) = upsert(
        "suppliers", "id",
        mapOf("id" to supplier.id.value, "doc" to Json.encode(mapOf("id" to supplier.id.value, "name" to supplier.name, "phone" to supplier.phone, "active" to supplier.isActive))),
    )
}

class SqlPurchaseStore(db: SqlDatabase) : SqlTable(db), PurchaseStore {
    private fun lines(l: List<InvoiceLine>) = l.map { mapOf("item" to it.itemId.value, "qty" to it.quantity.micros, "value" to it.value.rial) }
    private fun linesOf(d: List<Doc>) = d.map { InvoiceLine(Codec.id(it.str("item")), Codec.qty(it.long("qty")), Codec.money(it.long("value"))) }

    private fun invoiceOf(d: Doc) = PurchaseInvoice(
        Codec.id(d.str("id")), Codec.id(d.str("supplier")), d.str("number"), Codec.branchOf(d.str("scope")), Codec.id(d.str("location")),
        Codec.date(d.long("date")), Codec.date(d.long("due")), linesOf(d.docs("lines")), Codec.money(d.long("total")), InvoiceStatus.valueOf(d.str("status")),
    )

    override fun invoice(id: GlobalId) = doc("SELECT doc FROM purchase_invoices WHERE id = ?", id.value)?.let(::invoiceOf)
    override fun invoiceByNumber(supplierId: GlobalId, normalizedNo: String) =
        doc("SELECT doc FROM purchase_invoices WHERE supplier_id = ? AND number = ?", supplierId.value, normalizedNo)?.let(::invoiceOf)
    override fun invoices() = docs("SELECT doc FROM purchase_invoices ORDER BY rowid").map(::invoiceOf)
    override fun saveInvoice(invoice: PurchaseInvoice) = upsert(
        "purchase_invoices", "id",
        mapOf(
            "id" to invoice.id.value, "supplier_id" to invoice.supplierId.value,
            // The unique (supplier, number) column holds only POSTED numbers; a reversed one is retired.
            "number" to if (invoice.status == InvoiceStatus.POSTED) invoice.supplierInvoiceNo else "${invoice.supplierInvoiceNo}#reversed#${invoice.id.value}",
            "doc" to Json.encode(
                mapOf(
                    "id" to invoice.id.value, "supplier" to invoice.supplierId.value, "number" to invoice.supplierInvoiceNo,
                    "scope" to Codec.scope(invoice.scope), "location" to invoice.locationId.value, "date" to invoice.date.epochDay,
                    "due" to invoice.dueDate.epochDay, "lines" to lines(invoice.lines), "total" to invoice.total.rial, "status" to invoice.status.name,
                ),
            ),
        ),
    )

    private fun paymentOf(d: Doc) = SupplierPayment(
        Codec.id(d.str("id")), Codec.id(d.str("invoice")), Codec.id(d.str("treasury")), Codec.money(d.long("amount")),
        Codec.date(d.long("date")), Codec.idOrNull(d.strOrNull("bridge")), d.bool("reversed"),
    )
    override fun payment(id: GlobalId) = doc("SELECT doc FROM supplier_payments WHERE id = ?", id.value)?.let(::paymentOf)
    override fun payments(invoiceId: GlobalId) = docs("SELECT doc FROM supplier_payments WHERE invoice_id = ? ORDER BY rowid", invoiceId.value).map(::paymentOf)
    override fun savePayment(payment: SupplierPayment) = upsert(
        "supplier_payments", "id",
        mapOf(
            "id" to payment.id.value, "invoice_id" to payment.invoiceId.value,
            "doc" to Json.encode(
                mapOf(
                    "id" to payment.id.value, "invoice" to payment.invoiceId.value, "treasury" to payment.treasuryAccountId.value,
                    "amount" to payment.amount.rial, "date" to payment.date.epochDay, "bridge" to payment.bridgeJournalId?.value, "reversed" to payment.reversed,
                ),
            ),
        ),
    )

    override fun returns(invoiceId: GlobalId) = docs("SELECT doc FROM purchase_returns WHERE invoice_id = ? ORDER BY rowid", invoiceId.value).map { d ->
        PurchaseReturn(Codec.id(d.str("id")), Codec.id(d.str("invoice")), linesOf(d.docs("lines")), Codec.money(d.long("credit")), Codec.date(d.long("date")))
    }
    override fun saveReturn(purchaseReturn: PurchaseReturn) = db.execute(
        "INSERT INTO purchase_returns (id, invoice_id, doc) VALUES (?, ?, ?)",
        purchaseReturn.id.value, purchaseReturn.invoiceId.value,
        Json.encode(
            mapOf(
                "id" to purchaseReturn.id.value, "invoice" to purchaseReturn.invoiceId.value, "lines" to lines(purchaseReturn.lines),
                "credit" to purchaseReturn.credit.rial, "date" to purchaseReturn.date.epochDay,
            ),
        ),
    )
}

// ---------------------------------------------------------------- Sales

class SqlCustomerStore(db: SqlDatabase) : SqlTable(db), CustomerStore {
    private fun read(d: Doc) = Customer(
        Codec.id(d.str("id")), d.str("name"), CustomerType.valueOf(d.str("type")), d.str("phone"), Codec.money(d.long("limit")),
        Codec.branchOf(d.str("registeredIn")), d.bool("active"),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM customers WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM customers ORDER BY rowid").map(::read)
    override fun save(customer: Customer) = upsert(
        "customers", "id",
        mapOf(
            "id" to customer.id.value,
            "doc" to Json.encode(
                mapOf(
                    "id" to customer.id.value, "name" to customer.name, "type" to customer.type.name, "phone" to customer.phone,
                    "limit" to customer.creditLimit.rial, "registeredIn" to Codec.scope(customer.registeredIn), "active" to customer.isActive,
                ),
            ),
        ),
    )
}

class SqlSalesStore(db: SqlDatabase) : SqlTable(db), SalesStore {
    private fun saleOf(d: Doc) = DailySale(
        id = Codec.id(d.str("id")), scope = Codec.branchOf(d.str("scope")), date = Codec.date(d.long("date")),
        kitchenLocationId = Codec.id(d.str("kitchen")),
        lines = d.docs("lines").map { SaleLine(Codec.id(it.str("menuItem")), Codec.qty(it.long("portions")), Codec.money(it.long("gross"))) },
        discount = Codec.money(d.long("discount")), serviceCharge = Codec.money(d.long("service")), tax = Codec.money(d.long("tax")),
        settlements = d.docs("settlements").map {
            when (it.str("kind")) {
                "LIQUID" -> Settlement.Liquid(Codec.id(it.str("treasury")), Codec.money(it.long("amount")))
                "CREDIT" -> Settlement.Credit(Codec.id(it.str("customer")), Codec.money(it.long("amount")), Codec.date(it.long("due")))
                else -> error("unknown_settlement")
            }
        },
        status = SaleStatus.valueOf(d.str("status")), revenueJournalId = Codec.idOrNull(d.strOrNull("revenueJournal")),
        cost = Codec.money(d.long("cost")), consumed = d.bool("consumed"),
        guests = d.intOr("guests", 0), transactions = d.intOr("transactions", 0),
    )

    override fun sale(id: GlobalId) = doc("SELECT doc FROM daily_sales WHERE id = ?", id.value)?.let(::saleOf)
    override fun activeSale(scope: Scope.Branch, date: BusinessDate) =
        doc("SELECT doc FROM daily_sales WHERE scope = ? AND date = ? AND status <> 'REVERSED' ORDER BY rowid LIMIT 1", Codec.scope(scope), date.epochDay)?.let(::saleOf)
    fun sales(scope: Scope.Branch, from: BusinessDate, to: BusinessDate): List<DailySale> =
        docs("SELECT doc FROM daily_sales WHERE scope = ? AND date BETWEEN ? AND ? ORDER BY date, rowid", Codec.scope(scope), from.epochDay, to.epochDay).map(::saleOf)

    override fun saveSale(sale: DailySale) = upsert(
        "daily_sales", "id",
        mapOf(
            "id" to sale.id.value, "scope" to Codec.scope(sale.scope), "date" to sale.date.epochDay, "status" to sale.status.name,
            "doc" to Json.encode(
                mapOf(
                    "id" to sale.id.value, "scope" to Codec.scope(sale.scope), "date" to sale.date.epochDay, "kitchen" to sale.kitchenLocationId.value,
                    "lines" to sale.lines.map { mapOf("menuItem" to it.menuItemId.value, "portions" to it.portions.micros, "gross" to it.gross.rial) },
                    "discount" to sale.discount.rial, "service" to sale.serviceCharge.rial, "tax" to sale.tax.rial,
                    "settlements" to sale.settlements.map {
                        when (it) {
                            is Settlement.Liquid -> mapOf("kind" to "LIQUID", "treasury" to it.treasuryAccountId.value, "amount" to it.amount.rial)
                            is Settlement.Credit -> mapOf("kind" to "CREDIT", "customer" to it.customerId.value, "amount" to it.amount.rial, "due" to it.dueDate.epochDay)
                        }
                    },
                    "status" to sale.status.name, "revenueJournal" to sale.revenueJournalId?.value, "cost" to sale.cost.rial, "consumed" to sale.consumed,
                    "guests" to sale.guests, "transactions" to sale.transactions,
                ),
            ),
        ),
    )

    private fun receivableOf(d: Doc) = Receivable(
        Codec.id(d.str("id")), Codec.id(d.str("customer")), Codec.branchOf(d.str("scope")), Codec.id(d.str("sale")),
        Codec.money(d.long("amount")), Codec.date(d.long("due")), d.bool("voided"),
    )
    override fun receivable(id: GlobalId) = doc("SELECT doc FROM receivables WHERE id = ?", id.value)?.let(::receivableOf)
    override fun receivablesOfSale(saleId: GlobalId) = docs("SELECT doc FROM receivables WHERE sale_id = ? ORDER BY rowid", saleId.value).map(::receivableOf)
    override fun receivablesOfCustomer(customerId: GlobalId) =
        docs("SELECT doc FROM receivables WHERE customer_id = ? ORDER BY rowid", customerId.value).map(::receivableOf)
    override fun saveReceivable(receivable: Receivable) = upsert(
        "receivables", "id",
        mapOf(
            "id" to receivable.id.value, "sale_id" to receivable.saleId.value, "customer_id" to receivable.customerId.value,
            "doc" to Json.encode(
                mapOf(
                    "id" to receivable.id.value, "customer" to receivable.customerId.value, "scope" to Codec.scope(receivable.scope),
                    "sale" to receivable.saleId.value, "amount" to receivable.amount.rial, "due" to receivable.dueDate.epochDay, "voided" to receivable.voided,
                ),
            ),
        ),
    )

    private fun collectionOf(d: Doc) = Collection(
        Codec.id(d.str("id")), Codec.id(d.str("receivable")), Codec.id(d.str("treasury")), Codec.money(d.long("amount")),
        Codec.date(d.long("date")), d.bool("reversed"),
    )
    override fun collection(id: GlobalId) = doc("SELECT doc FROM collections WHERE id = ?", id.value)?.let(::collectionOf)
    override fun collections(receivableId: GlobalId) =
        docs("SELECT doc FROM collections WHERE receivable_id = ? ORDER BY rowid", receivableId.value).map(::collectionOf)
    override fun saveCollection(collection: Collection) = upsert(
        "collections", "id",
        mapOf(
            "id" to collection.id.value, "receivable_id" to collection.receivableId.value,
            "doc" to Json.encode(
                mapOf(
                    "id" to collection.id.value, "receivable" to collection.receivableId.value, "treasury" to collection.treasuryAccountId.value,
                    "amount" to collection.amount.rial, "date" to collection.date.epochDay, "reversed" to collection.reversed,
                ),
            ),
        ),
    )

    override fun day(scope: Scope.Branch, date: BusinessDate) =
        doc("SELECT doc FROM sales_days WHERE scope = ? AND date = ?", Codec.scope(scope), date.epochDay)?.let {
            SalesDay(Codec.branchOf(it.str("scope")), Codec.date(it.long("date")), it.bool("closed"), it.longOrNull("countedCash")?.let(Codec::money))
        }
    override fun saveDay(day: SalesDay) = upsert(
        "sales_days", listOf("scope", "date"),
        mapOf(
            "scope" to Codec.scope(day.scope), "date" to day.date.epochDay,
            "doc" to Json.encode(mapOf("scope" to Codec.scope(day.scope), "date" to day.date.epochDay, "closed" to day.closed, "countedCash" to day.countedCash?.rial)),
        ),
    )
}

// ---------------------------------------------------------------- Payroll

class SqlPersonnelStore(db: SqlDatabase) : SqlTable(db), PersonnelStore {
    private fun employeeOf(d: Doc) = Employee(
        Codec.id(d.str("id")), d.str("name"), d.str("nationalId"), Codec.branchOf(d.str("scope")), Codec.money(d.long("salary")), d.bool("active"),
        startDate = d.longOrNull("start")?.let(Codec::date), endDate = d.longOrNull("end")?.let(Codec::date),
    )
    override fun employee(id: GlobalId) = doc("SELECT doc FROM employees WHERE id = ?", id.value)?.let(::employeeOf)
    override fun employees(scope: Scope.Branch) = docs("SELECT doc FROM employees WHERE scope = ? ORDER BY rowid", Codec.scope(scope)).map(::employeeOf)
    override fun saveEmployee(employee: Employee) = upsert(
        "employees", "id",
        mapOf(
            "id" to employee.id.value, "scope" to Codec.scope(employee.scope),
            "doc" to Json.encode(
                mapOf(
                    "id" to employee.id.value, "name" to employee.name, "nationalId" to employee.nationalId, "scope" to Codec.scope(employee.scope),
                    "salary" to employee.monthlySalary.rial, "active" to employee.isActive,
                    "start" to employee.startDate?.epochDay, "end" to employee.endDate?.epochDay,
                ),
            ),
        ),
    )

    private fun attendanceOf(d: Doc) = AttendanceRecord(
        Codec.id(d.str("id")), Codec.id(d.str("employee")), Codec.date(d.long("date")), d.int("worked"), d.int("overtime"), d.int("absent"),
    )
    override fun attendance(employeeId: GlobalId, from: BusinessDate, to: BusinessDate) =
        docs("SELECT doc FROM attendance WHERE employee_id = ? AND date BETWEEN ? AND ? ORDER BY date", employeeId.value, from.epochDay, to.epochDay).map(::attendanceOf)
    override fun attendanceOn(employeeId: GlobalId, date: BusinessDate) =
        doc("SELECT doc FROM attendance WHERE employee_id = ? AND date = ?", employeeId.value, date.epochDay)?.let(::attendanceOf)
    override fun saveAttendance(record: AttendanceRecord) = upsert(
        "attendance", "id",
        mapOf(
            "id" to record.id.value, "employee_id" to record.employeeId.value, "date" to record.date.epochDay,
            "doc" to Json.encode(
                mapOf(
                    "id" to record.id.value, "employee" to record.employeeId.value, "date" to record.date.epochDay,
                    "worked" to record.workedMinutes, "overtime" to record.overtimeMinutes, "absent" to record.absentMinutes,
                ),
            ),
        ),
    )
}

class SqlPayrollStore(db: SqlDatabase) : SqlTable(db), PayrollStore {
    private fun slip(p: Payslip) = mapOf(
        "employee" to p.employeeId.value, "base" to p.baseSalary.rial, "absence" to p.absenceDeduction.rial, "overtime" to p.overtimePay.rial,
        "gross" to p.gross.rial, "insurable" to p.insurableBase.rial, "empIns" to p.employeeInsurance.rial, "erIns" to p.employerInsurance.rial,
        "unemp" to p.unemploymentInsurance.rial, "taxable" to p.taxableIncome.rial, "tax" to p.incomeTax.rial, "net" to p.net.rial,
        "days" to p.payableDays?.toLong(),
    )
    private fun slipOf(d: Doc) = Payslip(
        Codec.id(d.str("employee")), Codec.money(d.long("base")), Codec.money(d.long("absence")), Codec.money(d.long("overtime")),
        Codec.money(d.long("gross")), Codec.money(d.long("insurable")), Codec.money(d.long("empIns")), Codec.money(d.long("erIns")),
        Codec.money(d.long("unemp")), Codec.money(d.long("taxable")), Codec.money(d.long("tax")), Codec.money(d.long("net")),
        d.longOrNull("days")?.toInt(),
    )
    private fun runOf(d: Doc) = PayrollRun(
        Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.date(d.long("from")), Codec.date(d.long("to")), d.str("policy"),
        d.docs("payslips").map(::slipOf), RunStatus.valueOf(d.str("status")), Codec.id(d.str("calculatedBy")),
        Codec.idOrNull(d.strOrNull("approvedBy")), Codec.idOrNull(d.strOrNull("accrual")),
    )

    override fun run(id: GlobalId) = doc("SELECT doc FROM payroll_runs WHERE id = ?", id.value)?.let(::runOf)
    override fun runs(scope: Scope.Branch) = docs("SELECT doc FROM payroll_runs WHERE scope = ? ORDER BY rowid", Codec.scope(scope)).map(::runOf)
    override fun saveRun(run: PayrollRun) = upsert(
        "payroll_runs", "id",
        mapOf(
            "id" to run.id.value, "scope" to Codec.scope(run.scope),
            "doc" to Json.encode(
                mapOf(
                    "id" to run.id.value, "scope" to Codec.scope(run.scope), "from" to run.from.epochDay, "to" to run.to.epochDay,
                    "policy" to run.policyVersion, "payslips" to run.payslips.map(::slip), "status" to run.status.name,
                    "calculatedBy" to run.calculatedBy.value, "approvedBy" to run.approvedBy?.value, "accrual" to run.accrualJournalId?.value,
                ),
            ),
        ),
    )

    private fun paymentOf(d: Doc) = SalaryPayment(
        Codec.id(d.str("id")), Codec.id(d.str("run")), Codec.id(d.str("employee")), Codec.id(d.str("treasury")),
        Codec.money(d.long("amount")), Codec.date(d.long("date")), d.bool("reversed"),
    )
    override fun payment(id: GlobalId) = doc("SELECT doc FROM salary_payments WHERE id = ?", id.value)?.let(::paymentOf)
    override fun payments(runId: GlobalId) = docs("SELECT doc FROM salary_payments WHERE run_id = ? ORDER BY rowid", runId.value).map(::paymentOf)
    override fun savePayment(payment: SalaryPayment) = upsert(
        "salary_payments", "id",
        mapOf(
            "id" to payment.id.value, "run_id" to payment.runId.value,
            "doc" to Json.encode(
                mapOf(
                    "id" to payment.id.value, "run" to payment.runId.value, "employee" to payment.employeeId.value,
                    "treasury" to payment.treasuryAccountId.value, "amount" to payment.amount.rial, "date" to payment.date.epochDay, "reversed" to payment.reversed,
                ),
            ),
        ),
    )

    override fun remittances(scope: Scope.Branch, kind: LiabilityKind) =
        docs("SELECT doc FROM remittances WHERE scope = ? AND kind = ? ORDER BY rowid", Codec.scope(scope), kind.name).map { d ->
            Remittance(Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), LiabilityKind.valueOf(d.str("kind")), Codec.money(d.long("amount")), Codec.date(d.long("date")))
        }
    override fun saveRemittance(remittance: Remittance) = db.execute(
        "INSERT INTO remittances (id, scope, kind, doc) VALUES (?, ?, ?, ?)",
        remittance.id.value, Codec.scope(remittance.scope), remittance.kind.name,
        Json.encode(
            mapOf(
                "id" to remittance.id.value, "scope" to Codec.scope(remittance.scope), "kind" to remittance.kind.name,
                "amount" to remittance.amount.rial, "date" to remittance.date.epochDay,
            ),
        ),
    )
}

class SqlPolicyStore(db: SqlDatabase) : SqlTable(db), PolicyStore {
    override fun all() = docs("SELECT doc FROM payroll_policies ORDER BY from_day").map { d ->
        StatutoryPolicy(
            version = d.str("version"), from = Codec.date(d.long("from")), to = Codec.date(d.long("to")),
            standardMonthlyMinutes = d.int("minutes"), overtimeMultiplierPercent = d.int("overtime"),
            employeeInsuranceBp = d.int("empIns"), employerInsuranceBp = d.int("erIns"), unemploymentInsuranceBp = d.int("unemp"),
            maxInsurableMonthly = Codec.money(d.long("maxInsurable")),
            insuranceTaxExemptNumerator = d.int("exemptNum"), insuranceTaxExemptDenominator = d.int("exemptDen"),
            taxBrackets = d.docs("brackets").map { TaxBracket(it.longOrNull("upTo")?.let(Codec::money), it.int("bp")) },
            prorationDays = d.longOrNull("prorationDays")?.toInt() ?: 30,   // policies saved before this field: 30
        )
    }

    /** Immutable (trigger): a defined version is never changed. */
    override fun save(policy: StatutoryPolicy) = db.execute(
        "INSERT INTO payroll_policies (version, from_day, to_day, doc) VALUES (?, ?, ?, ?)",
        policy.version, policy.from.epochDay, policy.to.epochDay,
        Json.encode(
            mapOf(
                "version" to policy.version, "from" to policy.from.epochDay, "to" to policy.to.epochDay,
                "minutes" to policy.standardMonthlyMinutes, "overtime" to policy.overtimeMultiplierPercent,
                "empIns" to policy.employeeInsuranceBp, "erIns" to policy.employerInsuranceBp, "unemp" to policy.unemploymentInsuranceBp,
                "maxInsurable" to policy.maxInsurableMonthly.rial, "exemptNum" to policy.insuranceTaxExemptNumerator,
                "exemptDen" to policy.insuranceTaxExemptDenominator,
                "brackets" to policy.taxBrackets.map { mapOf("upTo" to it.upToMonthly?.rial, "bp" to it.rateBasisPoints) },
                "prorationDays" to policy.prorationDays,
            ),
        ),
    )
}
