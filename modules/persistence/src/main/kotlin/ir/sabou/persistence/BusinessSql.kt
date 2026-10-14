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
import ir.sabou.ledger.AccountCode
import ir.sabou.platform.Attachment
import ir.sabou.platform.AttachmentStore
import ir.sabou.purchasing.AccountLine
import ir.sabou.purchasing.Approval
import ir.sabou.purchasing.ApprovalRule
import ir.sabou.purchasing.Discrepancy
import ir.sabou.purchasing.MatchReview
import ir.sabou.purchasing.ApprovalRuleStore
import ir.sabou.purchasing.InvoiceCategory
import ir.sabou.purchasing.CreditAllocation
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.OrderLine
import ir.sabou.purchasing.OrderStatus
import ir.sabou.purchasing.PurchaseOrder
import ir.sabou.purchasing.ReviewLine
import ir.sabou.purchasing.ReviewResolution
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
    private fun read(d: Doc) = Supplier(
        Codec.id(d.str("id")), d.str("name"), d.str("phone"), d.bool("active"),
        deliveryDays = d.strsOr("deliveryDays").map { it.toInt() }.toSet(), cutoffMinutes = d.longOrNull("cutoff")?.toInt(),
        leadDays = d.intOr("leadDays", 1), note = d.strOr("note", ""),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM suppliers WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM suppliers ORDER BY rowid").map(::read)
    override fun save(supplier: Supplier) = upsert(
        "suppliers", "id",
        mapOf("id" to supplier.id.value, "doc" to Json.encode(mapOf(
            "id" to supplier.id.value, "name" to supplier.name, "phone" to supplier.phone, "active" to supplier.isActive,
            "deliveryDays" to supplier.deliveryDays.sorted().map { it.toString() }, "cutoff" to supplier.cutoffMinutes?.toLong(),
            "leadDays" to supplier.leadDays.toLong(), "note" to supplier.note,
        ))),
    )
    override fun aliases(supplierId: GlobalId): Map<String, GlobalId> =
        db.query("SELECT name, item_id FROM supplier_item_aliases WHERE supplier_id = ?", supplierId.value).associate { it.str("name") to Codec.id(it.str("item_id")) }
    override fun saveAlias(supplierId: GlobalId, name: String, itemId: GlobalId) = upsert(
        "supplier_item_aliases", listOf("supplier_id", "name"), mapOf("supplier_id" to supplierId.value, "name" to name, "item_id" to itemId.value),
    )
}

class SqlPurchaseStore(db: SqlDatabase) : SqlTable(db), PurchaseStore {
    private fun lines(l: List<InvoiceLine>) = l.map { mapOf("item" to it.itemId.value, "qty" to it.quantity.micros, "value" to it.value.rial, "supplierName" to it.supplierItemName.ifEmpty { null }, "expires" to it.expiresOn?.epochDay) }
    private fun linesOf(d: List<Doc>) = d.map { InvoiceLine(Codec.id(it.str("item")), Codec.qty(it.long("qty")), Codec.money(it.long("value")), it.strOr("supplierName", ""), it.longOrNull("expires")?.let(::BusinessDate)) }

    private fun resolutionOf(d: Doc?) = d?.let {
        ReviewResolution(
            Codec.idOrNull(it.strOrNull("item")), it.longOrNull("qty")?.let(Codec::qty), Codec.idOrNull(it.strOrNull("location")),
            it.strOrNull("account")?.let(AccountCode::of), Codec.id(it.str("journal")), Codec.id(it.str("document")), Codec.date(it.long("date")),
        )
    }

    private fun invoiceOf(d: Doc) = PurchaseInvoice(
        Codec.id(d.str("id")), Codec.id(d.str("supplier")), d.str("number"), Codec.branchOf(d.str("scope")), Codec.idOrNull(d.strOrNull("location")),
        Codec.date(d.long("date")), Codec.date(d.long("due")), linesOf(d.docs("lines")), Codec.money(d.long("total")), InvoiceStatus.valueOf(d.str("status")),
        accountLines = d.docsOr("accountLines").map {
            AccountLine(AccountCode.of(it.str("account")), Codec.money(it.long("amount")), it.strOrNull("branch")?.let(Codec::branchOf), it.strOr("memo", ""))
        },
        reviewLines = d.docsOr("reviewLines").map {
            ReviewLine(it.str("name"), it.strOr("qtyNote", ""), Codec.money(it.long("amount")), resolutionOf(it.docOrNull("resolution")))
        },
        note = d.strOr("note", ""), orderId = Codec.idOrNull(d.strOrNull("order")), journalIds = d.strsOr("journals").map(Codec::id),
        recordedBy = Codec.idOrNull(d.strOrNull("recordedBy")), requiredApprovals = d.intOr("requiredApprovals", 0),
        approvals = d.docsOr("approvals").map { Approval(Codec.id(it.str("user")), it.str("name"), it.long("at"), it.boolOr("self", false), it.strOrNull("reason")) },
        discrepancies = d.docsOr("discrepancies").map {
            Discrepancy(Discrepancy.Kind.valueOf(it.str("kind")), Codec.idOrNull(it.strOrNull("item")), it.longOrNull("ordered"), it.longOrNull("invoiced"))
        },
        matchReview = d.docOrNull("matchReview")?.let { MatchReview(Codec.id(it.str("user")), it.str("name"), it.long("at"), it.str("note")) },
        opening = d.boolOr("opening", false),
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
                    "scope" to Codec.scope(invoice.scope), "location" to invoice.locationId?.value, "date" to invoice.date.epochDay,
                    "due" to invoice.dueDate.epochDay, "lines" to lines(invoice.lines), "total" to invoice.total.rial, "status" to invoice.status.name,
                    "accountLines" to invoice.accountLines.map {
                        mapOf("account" to it.account.value, "amount" to it.amount.rial, "branch" to it.branch?.let(Codec::scope), "memo" to it.memo)
                    },
                    "reviewLines" to invoice.reviewLines.map { l ->
                        mapOf("name" to l.supplierItemName, "qtyNote" to l.quantityNote, "amount" to l.amount.rial, "resolution" to l.resolution?.let {
                            mapOf("item" to it.itemId?.value, "qty" to it.quantity?.micros, "location" to it.locationId?.value, "account" to it.account?.value,
                                "journal" to it.journalId.value, "document" to it.documentId.value, "date" to it.date.epochDay)
                        })
                    },
                    "note" to invoice.note, "order" to invoice.orderId?.value, "journals" to invoice.journalIds.map { it.value },
                    "recordedBy" to invoice.recordedBy?.value, "requiredApprovals" to invoice.requiredApprovals.toLong(), "opening" to invoice.opening,
                    "approvals" to invoice.approvals.map { mapOf("user" to it.userId.value, "name" to it.name, "at" to it.atEpochMillis, "self" to it.self, "reason" to it.reason) },
                    "discrepancies" to invoice.discrepancies.map { mapOf("kind" to it.kind.name, "item" to it.itemId?.value, "ordered" to it.ordered, "invoiced" to it.invoiced) },
                    "matchReview" to invoice.matchReview?.let { mapOf("user" to it.userId.value, "name" to it.name, "at" to it.atEpochMillis, "note" to it.note) },
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

    private fun allocationOf(d: Doc) = CreditAllocation(
        Codec.id(d.str("id")), Codec.id(d.str("supplier")), Codec.branchOf(d.str("scope")), Codec.id(d.str("invoice")), Codec.money(d.long("amount")),
        Codec.date(d.long("date")), Codec.idOrNull(d.strOrNull("return")), d.bool("released"),
    )
    override fun allocation(id: GlobalId) = doc("SELECT doc FROM credit_allocations WHERE id = ?", id.value)?.let(::allocationOf)
    override fun allocationsTo(invoiceId: GlobalId) = docs("SELECT doc FROM credit_allocations WHERE invoice_id = ? ORDER BY rowid", invoiceId.value).map(::allocationOf)
    override fun allocationsOf(supplierId: GlobalId, scope: Scope.Branch) =
        docs("SELECT doc FROM credit_allocations WHERE supplier_id = ? AND scope = ? ORDER BY rowid", supplierId.value, Codec.scope(scope)).map(::allocationOf)
    override fun saveAllocation(allocation: CreditAllocation) = upsert(
        "credit_allocations", "id",
        mapOf(
            "id" to allocation.id.value, "supplier_id" to allocation.supplierId.value, "scope" to Codec.scope(allocation.scope), "invoice_id" to allocation.invoiceId.value,
            "doc" to Json.encode(mapOf(
                "id" to allocation.id.value, "supplier" to allocation.supplierId.value, "scope" to Codec.scope(allocation.scope), "invoice" to allocation.invoiceId.value,
                "amount" to allocation.amount.rial, "date" to allocation.date.epochDay, "return" to allocation.returnId?.value, "released" to allocation.released,
            )),
        ),
    )

    private fun refundOf(d: Doc) = ir.sabou.purchasing.SupplierRefund(
        Codec.id(d.str("id")), Codec.id(d.str("supplier")), Codec.branchOf(d.str("scope")), Codec.id(d.str("treasury")), Codec.money(d.long("amount")),
        Codec.date(d.long("date")), Codec.idOrNull(d.strOrNull("bridge")), d.bool("reversed"),
    )
    override fun refund(id: GlobalId) = doc("SELECT doc FROM supplier_refunds WHERE id = ?", id.value)?.let(::refundOf)
    override fun refundsOf(supplierId: GlobalId, scope: Scope.Branch) =
        docs("SELECT doc FROM supplier_refunds WHERE supplier_id = ? AND scope = ? ORDER BY rowid", supplierId.value, Codec.scope(scope)).map(::refundOf)
    override fun saveRefund(refund: ir.sabou.purchasing.SupplierRefund) = upsert(
        "supplier_refunds", "id",
        mapOf(
            "id" to refund.id.value, "supplier_id" to refund.supplierId.value, "scope" to Codec.scope(refund.scope),
            "doc" to Json.encode(mapOf(
                "id" to refund.id.value, "supplier" to refund.supplierId.value, "scope" to Codec.scope(refund.scope), "treasury" to refund.treasuryAccountId.value,
                "amount" to refund.amount.rial, "date" to refund.date.epochDay, "bridge" to refund.bridgeJournalId?.value, "reversed" to refund.reversed,
            )),
        ),
    )

    private fun orderOf(d: Doc) = PurchaseOrder(
        Codec.id(d.str("id")), d.long("number"), Codec.id(d.str("supplier")), Codec.branchOf(d.str("scope")), Codec.id(d.str("location")),
        Codec.date(d.long("date")), Codec.date(d.long("expected")),
        d.docs("lines").map { OrderLine(Codec.id(it.str("item")), Codec.qty(it.long("qty")), Codec.money(it.long("price"))) },
        d.strOr("note", ""), OrderStatus.valueOf(d.str("status")), Codec.idOrNull(d.strOrNull("invoice")), d.strOrNull("cancelReason"),
    )
    override fun order(id: GlobalId) = doc("SELECT doc FROM purchase_orders WHERE id = ?", id.value)?.let(::orderOf)
    override fun orders() = docs("SELECT doc FROM purchase_orders ORDER BY number DESC").map(::orderOf)
    override fun saveOrder(order: PurchaseOrder) = upsert(
        "purchase_orders", "id",
        mapOf(
            "id" to order.id.value, "number" to order.number,
            "doc" to Json.encode(mapOf(
                "id" to order.id.value, "number" to order.number, "supplier" to order.supplierId.value, "scope" to Codec.scope(order.scope),
                "location" to order.locationId.value, "date" to order.date.epochDay, "expected" to order.expectedDate.epochDay,
                "lines" to order.lines.map { mapOf("item" to it.itemId.value, "qty" to it.quantity.micros, "price" to it.unitPrice.rial) },
                "note" to order.note, "status" to order.status.name, "invoice" to order.invoiceId?.value, "cancelReason" to order.cancelReason,
            )),
        ),
    )
    override fun nextOrderNumber(): Long = db.query("SELECT COALESCE(MAX(number), 0) + 1 AS n FROM purchase_orders").single().long("n")
}

class SqlApprovalRuleStore(db: SqlDatabase) : SqlTable(db), ApprovalRuleStore {
    private val meta = DatabaseMeta(db)
    override fun selfApproval(): ir.sabou.purchasing.SelfApprovalPolicy = meta.get(SELF_APPROVAL)?.let { Doc.parse(it) }
        ?.let { ir.sabou.purchasing.SelfApprovalPolicy(it.bool("allowed"), it.longOrNull("max")?.let(Codec::money)) }
        ?: ir.sabou.purchasing.SelfApprovalPolicy()
    override fun saveSelfApproval(policy: ir.sabou.purchasing.SelfApprovalPolicy) =
        meta.put(SELF_APPROVAL, Json.encode(mapOf("allowed" to policy.allowed, "max" to policy.maxAmount?.rial)))
    private companion object { const val SELF_APPROVAL = "purchasing.self_approval" }

    private fun read(d: Doc) = ApprovalRule(
        Codec.id(d.str("id")), d.str("name"), d.strOrNull("branch")?.let(Codec::branchOf), Codec.idOrNull(d.strOrNull("supplier")),
        d.strOrNull("category")?.let(InvoiceCategory::valueOf), Codec.money(d.long("min")), d.int("steps"), d.bool("active"),
    )
    override fun all() = docs("SELECT doc FROM approval_rules ORDER BY rowid").map(::read)
    override fun byId(id: GlobalId) = doc("SELECT doc FROM approval_rules WHERE id = ?", id.value)?.let(::read)
    override fun save(rule: ApprovalRule) = upsert(
        "approval_rules", "id",
        mapOf("id" to rule.id.value, "doc" to Json.encode(mapOf(
            "id" to rule.id.value, "name" to rule.name, "branch" to rule.branch?.let(Codec::scope), "supplier" to rule.supplierId?.value,
            "category" to rule.category?.name, "min" to rule.minAmount.rial, "steps" to rule.steps.toLong(), "active" to rule.isActive,
        ))),
    )
}

/** Attachment bytes live in the encrypted database, so backups carry them. Rows are immutable. */
class SqlAttachmentStore(db: SqlDatabase) : SqlTable(db), AttachmentStore {
    private fun metaOf(r: SqlRow) = Attachment(
        Codec.id(r.str("id")), r.str("owner_type"), Codec.id(r.str("owner_id")), r.str("file_name"), r.str("mime"), r.str("sha256"),
        r.long("size").toInt(), r.long("recorded_at"),
    )
    private val columns = "id, owner_type, owner_id, file_name, mime, sha256, size, recorded_at"
    override fun save(attachment: Attachment, bytes: ByteArray) = db.execute(
        "INSERT INTO attachments ($columns, bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        attachment.id.value, attachment.ownerType, attachment.ownerId.value, attachment.fileName, attachment.mime, attachment.sha256,
        attachment.size.toLong(), attachment.recordedAtEpochMillis, bytes,
    )
    override fun of(ownerType: String, ownerId: GlobalId) =
        db.query("SELECT $columns FROM attachments WHERE owner_type = ? AND owner_id = ? ORDER BY rowid", ownerType, ownerId.value).map(::metaOf)
    override fun meta(id: GlobalId) = db.query("SELECT $columns FROM attachments WHERE id = ?", id.value).firstOrNull()?.let(::metaOf)
    override fun content(id: GlobalId) = db.query("SELECT bytes FROM attachments WHERE id = ?", id.value).firstOrNull()?.bytes("bytes")
}

// ---------------------------------------------------------------- Sales

class SqlCustomerStore(db: SqlDatabase) : SqlTable(db), CustomerStore {
    private fun read(d: Doc) = Customer(
        Codec.id(d.str("id")), d.str("name"), CustomerType.valueOf(d.str("type")), d.str("phone"), Codec.money(d.long("limit")),
        Codec.branchOf(d.str("registeredIn")), d.bool("active"), d.strOr("taxId", ""), d.strOr("postal", ""),
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
                    "taxId" to customer.taxId.ifEmpty { null }, "postal" to customer.postalCode.ifEmpty { null },
                ),
            ),
        ),
    )
}

class SqlSalesStore(db: SqlDatabase) : SqlTable(db), SalesStore {
    private fun saleOf(d: Doc) = DailySale(
        id = Codec.id(d.str("id")), scope = Codec.branchOf(d.str("scope")), date = Codec.date(d.long("date")),
        kitchenLocationId = Codec.id(d.str("kitchen")),
        lines = d.docs("lines").map(::saleLineOf),
        discount = Codec.money(d.long("discount")), serviceCharge = Codec.money(d.long("service")), tax = Codec.money(d.long("tax")),
        settlements = d.docs("settlements").map {
            when (it.str("kind")) {
                "LIQUID" -> Settlement.Liquid(Codec.id(it.str("treasury")), Codec.money(it.long("amount")), it.docOrNull("cheque")?.let(Codec::chequeOf))
                "CREDIT" -> Settlement.Credit(Codec.id(it.str("customer")), Codec.money(it.long("amount")), Codec.date(it.long("due")))
                else -> error("unknown_settlement")
            }
        },
        status = SaleStatus.valueOf(d.str("status")), revenueJournalId = Codec.idOrNull(d.strOrNull("revenueJournal")),
        cost = Codec.money(d.long("cost")), consumed = d.bool("consumed"),
        guests = d.intOr("guests", 0), transactions = d.intOr("transactions", 0), discountReason = d.strOr("discountReason", ""),
    )

    /**
     * A line as recorded. Lines written before menu prices (schema 7 and older) carry only their total; their unit
     * price is the total per portion, shown for information, while the recorded total stays what was posted.
     */
    private fun saleLineOf(d: Doc): SaleLine {
        val portions = Codec.qty(d.long("portions"))
        val gross = Codec.money(d.long("gross"))
        val unit = d.longOrNull("unitPrice")?.let(Codec::money)
            ?: Codec.money(if (portions.isZero) 0 else ir.sabou.kernel.Ratio.mulDiv(gross.rial, ir.sabou.kernel.Quantity.SCALE, portions.micros, ir.sabou.kernel.Rounding.HALF_UP))
        return SaleLine(Codec.id(d.str("menuItem")), portions, unit, gross, d.longOrNull("listPrice")?.let(Codec::money), d.strOrNull("reason"))
    }

    /** Whether any recorded sale (draft, posted or reversed) has a line of [menuItemId]. */
    fun usesMenuItem(menuItemId: GlobalId): Boolean =
        docs("SELECT doc FROM daily_sales WHERE doc LIKE ?", "%${menuItemId.value}%").any { d -> d.docs("lines").any { it.str("menuItem") == menuItemId.value } }

    override fun sale(id: GlobalId) = doc("SELECT doc FROM daily_sales WHERE id = ?", id.value)?.let(::saleOf)
    override fun salesOn(scope: Scope.Branch, date: BusinessDate) =
        docs("SELECT doc FROM daily_sales WHERE scope = ? AND date = ? ORDER BY rowid", Codec.scope(scope), date.epochDay).map(::saleOf)
    fun sales(scope: Scope.Branch, from: BusinessDate, to: BusinessDate): List<DailySale> =
        docs("SELECT doc FROM daily_sales WHERE scope = ? AND date BETWEEN ? AND ? ORDER BY date, rowid", Codec.scope(scope), from.epochDay, to.epochDay).map(::saleOf)

    override fun saveSale(sale: DailySale) = upsert(
        "daily_sales", "id",
        mapOf(
            "id" to sale.id.value, "scope" to Codec.scope(sale.scope), "date" to sale.date.epochDay, "status" to sale.status.name,
            "doc" to Json.encode(
                mapOf(
                    "id" to sale.id.value, "scope" to Codec.scope(sale.scope), "date" to sale.date.epochDay, "kitchen" to sale.kitchenLocationId.value,
                    "lines" to sale.lines.map {
                        mapOf("menuItem" to it.menuItemId.value, "portions" to it.portions.micros, "unitPrice" to it.unitPrice.rial, "gross" to it.gross.rial,
                            "listPrice" to it.listPrice?.rial, "reason" to it.overrideReason)
                    },
                    "discount" to sale.discount.rial, "service" to sale.serviceCharge.rial, "tax" to sale.tax.rial,
                    "settlements" to sale.settlements.map {
                        when (it) {
                            is Settlement.Liquid -> mapOf("kind" to "LIQUID", "treasury" to it.treasuryAccountId.value, "amount" to it.amount.rial, "cheque" to it.cheque?.let(Codec::cheque))
                            is Settlement.Credit -> mapOf("kind" to "CREDIT", "customer" to it.customerId.value, "amount" to it.amount.rial, "due" to it.dueDate.epochDay)
                        }
                    },
                    "status" to sale.status.name, "revenueJournal" to sale.revenueJournalId?.value, "cost" to sale.cost.rial, "consumed" to sale.consumed,
                    "guests" to sale.guests, "transactions" to sale.transactions, "discountReason" to sale.discountReason,
                ),
            ),
        ),
    )

    private fun receivableOf(d: Doc) = Receivable(
        Codec.id(d.str("id")), Codec.id(d.str("customer")), Codec.branchOf(d.str("scope")), Codec.id(d.str("sale")),
        Codec.money(d.long("amount")), Codec.date(d.long("due")), d.bool("voided"),
        d.docsOr("writeOffs").map { ir.sabou.sales.WriteOff(Codec.date(it.long("date")), Codec.money(it.long("amount")), it.strOr("reason", "")) },
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
                    "writeOffs" to receivable.writeOffs.map { mapOf("date" to it.date.epochDay, "amount" to it.amount.rial, "reason" to it.reason) },
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
            SalesDay(Codec.branchOf(it.str("scope")), Codec.date(it.long("date")), it.bool("closed"), it.longOrNull("countedCash")?.let(Codec::money),
                Codec.idOrNull(it.strOrNull("cashAccount")), it.longOr("difference", 0), Codec.idOrNull(it.strOrNull("count")))
        }
    override fun saveDay(day: SalesDay) = upsert(
        "sales_days", listOf("scope", "date"),
        mapOf(
            "scope" to Codec.scope(day.scope), "date" to day.date.epochDay,
            "doc" to Json.encode(mapOf("scope" to Codec.scope(day.scope), "date" to day.date.epochDay, "closed" to day.closed, "countedCash" to day.countedCash?.rial,
                "cashAccount" to day.cashAccountId?.value, "difference" to day.difference, "count" to day.countId?.value)),
        ),
    )
}

// ---------------------------------------------------------------- Payroll

class SqlPersonnelStore(db: SqlDatabase) : SqlTable(db), PersonnelStore {
    private fun employeeOf(d: Doc) = Employee(
        Codec.id(d.str("id")), d.str("name"), d.str("nationalId"), Codec.branchOf(d.str("scope")), Codec.money(d.long("salary")), d.bool("active"),
        startDate = d.longOrNull("start")?.let(Codec::date), endDate = d.longOrNull("end")?.let(Codec::date),
        birthDate = d.longOrNull("birth")?.let(ir.sabou.kernel.JalaliDay::unpack), personnelNumber = d.longOrNull("number")?.toInt() ?: 0,
        salaryChanges = if (d.has("salaries")) d.docs("salaries").map {
            ir.sabou.payroll.SalaryChange(Codec.date(it.long("from")), Codec.money(it.long("rial")), it.strOr("reason", ""), ir.sabou.payroll.PayType.valueOf(it.strOr("type", "MONTHLY")))
        } else emptyList(),
        pastEmployments = if (d.has("past")) d.docs("past").map { ir.sabou.payroll.Employment(it.longOrNull("start")?.let(Codec::date), Codec.date(it.long("end"))) } else emptyList(),
        payType = ir.sabou.payroll.PayType.valueOf(d.strOr("payType", "MONTHLY")),
        profile = d.docOrNull("profile")?.let(::profileOf) ?: ir.sabou.payroll.EmployeeProfile(),
        branchMoves = if (d.has("moves")) d.docs("moves").map { ir.sabou.payroll.BranchMove(Codec.date(it.long("from")), Codec.branchOf(it.str("previous"))) } else emptyList(),
    )
    private fun profileOf(d: Doc) = ir.sabou.payroll.EmployeeProfile(
        d.strOr("phone", ""), d.strOr("address", ""), d.strOr("emergency", ""), d.strOr("father", ""), d.strOr("job", ""), d.strOr("insurance", ""),
        d.strOr("sheba", ""), d.strOrNull("marital")?.let(ir.sabou.payroll.MaritalStatus::valueOf), d.intOr("children", 0),
        d.strOrNull("contract")?.let(ir.sabou.payroll.ContractType::valueOf), d.longOrNull("contractEnd")?.let(Codec::date),
    )
    private fun profileDoc(p: ir.sabou.payroll.EmployeeProfile) = mapOf(
        "phone" to p.phone, "address" to p.address, "emergency" to p.emergencyContact, "father" to p.fatherName, "job" to p.jobTitle,
        "insurance" to p.insuranceNumber, "sheba" to p.sheba, "marital" to p.maritalStatus?.name, "children" to p.children.toLong(),
        "contract" to p.contractType?.name, "contractEnd" to p.contractEnd?.epochDay,
    )
    override fun employee(id: GlobalId) = doc("SELECT doc FROM employees WHERE id = ?", id.value)?.let(::employeeOf)
    override fun employees(scope: Scope.Branch) = docs("SELECT doc FROM employees WHERE scope = ? ORDER BY rowid", Codec.scope(scope)).map(::employeeOf)
    override fun nextPersonnelNumber() = db.query("SELECT COALESCE(MAX(number), 0) + 1 AS n FROM employees").single().long("n").toInt()
    override fun saveEmployee(employee: Employee) = upsert(
        "employees", "id",
        mapOf(
            "id" to employee.id.value, "scope" to Codec.scope(employee.scope),
            // 0 = not numbered (records saved outside registration); NULLs do not collide in the unique index.
            "number" to employee.personnelNumber.takeIf { it > 0 }?.toLong(),
            "doc" to Json.encode(
                mapOf(
                    "id" to employee.id.value, "name" to employee.name, "nationalId" to employee.nationalId, "scope" to Codec.scope(employee.scope),
                    "salary" to employee.monthlySalary.rial, "active" to employee.isActive,
                    "start" to employee.startDate?.epochDay, "end" to employee.endDate?.epochDay,
                    "birth" to employee.birthDate?.packed(), "number" to employee.personnelNumber.toLong(),
                    "salaries" to employee.salaryChanges.map { mapOf("from" to it.from.epochDay, "rial" to it.monthlySalary.rial, "reason" to it.reason, "type" to it.payType.name) },
                    "past" to employee.pastEmployments.map { mapOf("start" to it.start?.epochDay, "end" to it.end.epochDay) },
                    "payType" to employee.payType.name, "profile" to profileDoc(employee.profile),
                    "moves" to employee.branchMoves.map { mapOf("from" to it.from.epochDay, "previous" to Codec.scope(it.previous)) },
                ),
            ),
        ),
    )

    private fun attendanceOf(d: Doc) = AttendanceRecord(
        Codec.id(d.str("id")), Codec.id(d.str("employee")), Codec.date(d.long("date")), d.int("worked"), d.int("overtime"), d.int("absent"),
        ir.sabou.payroll.AttendanceRecord.Source.valueOf(d.strOr("source", "MANUAL")), d.intOr("night", 0),
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
                    "source" to record.source.name, "night" to record.nightMinutes.toLong(),
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
        "lines" to p.lines.map { mapOf("code" to it.code, "title" to it.title, "rial" to it.amount.rial, "kind" to it.kind.name, "ins" to it.insurable, "tax" to it.taxable) },
    )
    private fun slipOf(d: Doc) = Payslip(
        Codec.id(d.str("employee")), Codec.money(d.long("base")), Codec.money(d.long("absence")), Codec.money(d.long("overtime")),
        Codec.money(d.long("gross")), Codec.money(d.long("insurable")), Codec.money(d.long("empIns")), Codec.money(d.long("erIns")),
        Codec.money(d.long("unemp")), Codec.money(d.long("taxable")), Codec.money(d.long("tax")), Codec.money(d.long("net")),
        d.longOrNull("days")?.toInt(),
        if (d.has("lines")) d.docs("lines").map {
            ir.sabou.payroll.PayLine(it.str("code"), it.str("title"), Codec.money(it.long("rial")), ir.sabou.payroll.PayLine.Kind.valueOf(it.str("kind")), it.bool("ins"), it.bool("tax"))
        } else emptyList(),
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

    private fun advanceOf(d: Doc) = ir.sabou.payroll.Advance(
        Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.id(d.str("employee")), Codec.money(d.long("amount")), Codec.date(d.long("date")),
        d.int("installments"), d.strOr("note", ""), Codec.id(d.str("treasury")), d.boolOr("reversed", false),
        if (d.has("repayments")) d.docs("repayments").map {
            ir.sabou.payroll.AdvanceRepayment(Codec.id(it.str("id")), Codec.money(it.long("amount")), Codec.date(it.long("date")), Codec.id(it.str("treasury")), it.boolOr("reversed", false))
        } else emptyList(),
    )
    override fun advance(id: GlobalId) = doc("SELECT doc FROM salary_advances WHERE id = ?", id.value)?.let(::advanceOf)
    override fun advances(employeeId: GlobalId) = docs("SELECT doc FROM salary_advances WHERE employee_id = ? ORDER BY rowid", employeeId.value).map(::advanceOf)
    override fun advancesOf(scope: Scope.Branch) = docs("SELECT doc FROM salary_advances WHERE scope = ? ORDER BY rowid", Codec.scope(scope)).map(::advanceOf)
    override fun saveAdvance(advance: ir.sabou.payroll.Advance) = upsert(
        "salary_advances", "id",
        mapOf(
            "id" to advance.id.value, "scope" to Codec.scope(advance.scope), "employee_id" to advance.employeeId.value,
            "doc" to Json.encode(mapOf(
                "id" to advance.id.value, "scope" to Codec.scope(advance.scope), "employee" to advance.employeeId.value, "amount" to advance.amount.rial,
                "date" to advance.date.epochDay, "installments" to advance.installments.toLong(), "note" to advance.note, "treasury" to advance.treasuryAccountId.value,
                "reversed" to advance.reversed,
                "repayments" to advance.repayments.map { mapOf("id" to it.id.value, "amount" to it.amount.rial, "date" to it.date.epochDay, "treasury" to it.treasuryAccountId.value, "reversed" to it.reversed) },
            )),
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

// ---------------------------------------------------------------- Fixed assets

class SqlAssetStore(db: SqlDatabase) : SqlTable(db), ir.sabou.assets.AssetStore {
    private fun read(d: Doc) = ir.sabou.assets.FixedAsset(
        Codec.id(d.str("id")), d.str("name"), d.strOr("category", ""), Codec.branchOf(d.str("scope")), Codec.money(d.long("cost")), Codec.money(d.long("salvage")),
        Codec.date(d.long("acquired")), ir.sabou.assets.DepreciationMethod.valueOf(d.str("method")), d.longOrNull("lifeMonths")?.toInt(), d.longOrNull("rateBp"),
        Codec.money(d.long("accumulated")), d.longOrNull("through")?.let(Codec::date), ir.sabou.assets.AssetStatus.valueOf(d.str("status")),
        d.longOrNull("disposed")?.let(Codec::date), d.str("sourceType"), Codec.id(d.str("sourceId")),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM fixed_assets WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM fixed_assets ORDER BY rowid").map(::read)
    override fun save(asset: ir.sabou.assets.FixedAsset) = upsert(
        "fixed_assets", "id",
        mapOf("id" to asset.id.value, "scope" to Codec.scope(asset.scope), "doc" to Json.encode(mapOf(
            "id" to asset.id.value, "name" to asset.name, "category" to asset.category, "scope" to Codec.scope(asset.scope), "cost" to asset.cost.rial,
            "salvage" to asset.salvage.rial, "acquired" to asset.acquiredOn.epochDay, "method" to asset.method.name, "lifeMonths" to asset.usefulLifeMonths?.toLong(),
            "rateBp" to asset.rateBp, "accumulated" to asset.accumulated.rial, "through" to asset.depreciatedThrough?.epochDay, "status" to asset.status.name,
            "disposed" to asset.disposedOn?.epochDay, "sourceType" to asset.acquisitionSource, "sourceId" to asset.acquisitionId.value,
        ))),
    )
    private fun readRun(d: Doc) = ir.sabou.assets.DepreciationRun(
        Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.date(d.long("through")), Codec.date(d.long("date")),
        d.docs("lines").map { ir.sabou.assets.DepreciationLine(Codec.id(it.str("asset")), Codec.money(it.long("amount")), it.longOrNull("previous")?.let(Codec::date)) },
        Codec.idOrNull(d.strOrNull("journal")), d.bool("reversed"),
    )
    override fun runs() = docs("SELECT doc FROM depreciation_runs ORDER BY rowid").map(::readRun)
    override fun run(id: GlobalId) = doc("SELECT doc FROM depreciation_runs WHERE id = ?", id.value)?.let(::readRun)
    override fun saveRun(run: ir.sabou.assets.DepreciationRun) = upsert(
        "depreciation_runs", "id",
        mapOf("id" to run.id.value, "doc" to Json.encode(mapOf(
            "id" to run.id.value, "scope" to Codec.scope(run.scope), "through" to run.through.epochDay, "date" to run.date.epochDay,
            "lines" to run.lines.map { mapOf("asset" to it.assetId.value, "amount" to it.amount.rial, "previous" to it.previousThrough?.epochDay) },
            "journal" to run.journalId?.value, "reversed" to run.reversed,
        ))),
    )
}

/** Menu price versions (ADR-0019); append-only, the table refuses updates and deletes. */
class SqlMenuPriceStore(db: SqlDatabase) : SqlTable(db), ir.sabou.sales.MenuPriceStore {
    private val meta = DatabaseMeta(db)
    override fun foodCostTarget(): Long? = meta.get("menu.food_cost_target_bp")?.toLongOrNull()?.takeIf { it > 0 }
    override fun saveFoodCostTarget(bp: Long?) = meta.put("menu.food_cost_target_bp", (bp ?: 0L).toString())
    override fun serviceCharge(): Long? = meta.get("menu.service_charge_bp")?.toLongOrNull()?.takeIf { it > 0 }
    override fun saveServiceCharge(bp: Long?) = meta.put("menu.service_charge_bp", (bp ?: 0L).toString())
    private fun priceOf(d: Doc) = ir.sabou.sales.MenuPrice(
        Codec.id(d.str("id")), Codec.id(d.str("menuItem")), Codec.scopeOf(d.str("scope")), Codec.date(d.long("from")),
        d.longOrNull("price")?.let(Codec::money), d.long("sequence"),
    )
    override fun versions(menuItemId: GlobalId) = docs("SELECT doc FROM menu_prices WHERE menu_item_id = ? ORDER BY sequence", menuItemId.value).map(::priceOf)
    override fun all() = docs("SELECT doc FROM menu_prices ORDER BY sequence").map(::priceOf)
    override fun nextSequence(): Long = db.query("SELECT COALESCE(MAX(sequence), 0) + 1 AS n FROM menu_prices").single().long("n")
    override fun save(price: ir.sabou.sales.MenuPrice) {
        db.execute(
            "INSERT INTO menu_prices (id, menu_item_id, scope, effective_from, sequence, doc) VALUES (?, ?, ?, ?, ?, ?)",
            price.id.value, price.menuItemId.value, Codec.scope(price.scope), price.effectiveFrom.epochDay, price.sequence,
            Json.encode(
                mapOf(
                    "id" to price.id.value, "menuItem" to price.menuItemId.value, "scope" to Codec.scope(price.scope),
                    "from" to price.effectiveFrom.epochDay, "price" to price.unitPrice?.rial, "sequence" to price.sequence,
                ),
            ),
        )
    }
}

class SqlPosImportStore(db: SqlDatabase) : SqlTable(db), ir.sabou.sales.PosImportStore {
    private fun read(d: Doc) = ir.sabou.sales.PosImport(
        Codec.id(d.str("id")), d.str("pos"), Codec.branchOf(d.str("scope")), Codec.date(d.long("date")),
        d.strs("files"), d.strs("hashes"), d.strs("bills"), Codec.id(d.str("sale")), d.bool("active"),
    )
    override fun all() = docs("SELECT doc FROM pos_imports ORDER BY rowid").map(::read)
    override fun ofSale(saleId: GlobalId) = doc("SELECT doc FROM pos_imports WHERE sale_id = ?", saleId.value)?.let(::read)
    override fun save(import: ir.sabou.sales.PosImport) = upsert(
        "pos_imports", "id",
        mapOf(
            "id" to import.id.value, "pos" to import.pos, "scope" to Codec.scope(import.scope), "date" to import.date.epochDay,
            "sale_id" to import.saleId.value, "active" to if (import.active) 1L else 0L,
            "doc" to Json.encode(mapOf(
                "id" to import.id.value, "pos" to import.pos, "scope" to Codec.scope(import.scope), "date" to import.date.epochDay,
                "files" to import.fileNames, "hashes" to import.fileHashes, "bills" to import.invoiceKeys, "sale" to import.saleId.value, "active" to import.active,
            )),
        ),
    )
    override fun aliases(pos: String): Map<String, GlobalId> =
        db.query("SELECT name_key, menu_item_id FROM pos_aliases WHERE pos = ?", pos).associate { it.str("name_key") to Codec.id(it.str("menu_item_id")) }
    override fun saveAlias(pos: String, key: String, menuItemId: GlobalId) {
        db.execute("INSERT INTO pos_aliases (pos, name_key, menu_item_id) VALUES (?, ?, ?) ON CONFLICT(pos, name_key) DO UPDATE SET menu_item_id = excluded.menu_item_id", pos, key, menuItemId.value)
    }
}


/** The seller's tax profile (in the database meta) and the menu's tax product IDs (ADR-0037). */
class SqlTaxStore(db: SqlDatabase) : SqlTable(db), ir.sabou.sales.TaxStore {
    private val meta = DatabaseMeta(db)
    @Suppress("UNCHECKED_CAST")
    override fun profile(): ir.sabou.sales.TaxProfile? = meta.get("tax.profile")?.let { text ->
        val d = Doc(Json.decode(text) as Map<String, Any?>)
        ir.sabou.sales.TaxProfile(d.str("seller"), d.strOr("memory", ""), d.docsOr("vat").map { ir.sabou.sales.VatRate(Codec.date(it.long("from")), it.long("bp")) })
    }
    override fun saveProfile(profile: ir.sabou.sales.TaxProfile) = meta.put("tax.profile", Json.encode(mapOf(
        "seller" to profile.sellerTaxId, "memory" to profile.memoryId,
        "vat" to profile.vatRates.map { mapOf("from" to it.from.epochDay, "bp" to it.bp) },
    )))
    override fun codes() = docs("SELECT doc FROM tax_codes ORDER BY rowid").map { d ->
        ir.sabou.sales.ItemTaxCode(Codec.id(d.str("item")), d.str("product"), d.strOr("unit", ""))
    }
    override fun saveCode(code: ir.sabou.sales.ItemTaxCode) = upsert(
        "tax_codes", "menu_item_id",
        mapOf("menu_item_id" to code.menuItemId.value, "doc" to Json.encode(mapOf("item" to code.menuItemId.value, "product" to code.productId, "unit" to code.unitCode))),
    )
}

/** Shifts, punches and clock PINs (ADR-0038). Punches are never changed or deleted. */
class SqlShiftStore(db: SqlDatabase) : SqlTable(db), ir.sabou.payroll.ShiftStore {
    private fun shiftOf(d: Doc) = ir.sabou.payroll.Shift(Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.id(d.str("employee")), Codec.date(d.long("date")),
        d.long("start").toInt(), d.long("end").toInt(), d.long("break").toInt(), d.strOr("station", ""))
    private fun punchOf(d: Doc) = ir.sabou.payroll.Punch(Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.id(d.str("employee")), Codec.date(d.long("date")),
        d.long("minute").toInt(), ir.sabou.payroll.Punch.Kind.valueOf(d.str("kind")), d.long("at"),
        manualBy = d.strOrNull("manualBy")?.let(Codec::id), reason = d.strOr("reason", ""), voids = d.strOrNull("voids")?.let(Codec::id))
    override fun shift(id: GlobalId) = doc("SELECT doc FROM shifts WHERE id = ?", id.value)?.let(::shiftOf)
    override fun shifts(scope: Scope.Branch, from: BusinessDate, to: BusinessDate) =
        docs("SELECT doc FROM shifts WHERE scope = ? AND date BETWEEN ? AND ? ORDER BY date, rowid", Codec.scope(scope), from.epochDay, to.epochDay).map(::shiftOf)
    override fun saveShift(shift: ir.sabou.payroll.Shift) = upsert(
        "shifts", "id",
        mapOf("id" to shift.id.value, "scope" to Codec.scope(shift.scope), "date" to shift.date.epochDay,
            "doc" to Json.encode(mapOf("id" to shift.id.value, "scope" to Codec.scope(shift.scope), "employee" to shift.employeeId.value, "date" to shift.date.epochDay,
                "start" to shift.startMinute.toLong(), "end" to shift.endMinute.toLong(), "break" to shift.breakMinutes.toLong(), "station" to shift.station))),
    )
    override fun deleteShift(id: GlobalId) { db.execute("DELETE FROM shifts WHERE id = ?", id.value) }
    override fun punches(scope: Scope.Branch, from: BusinessDate, to: BusinessDate) =
        docs("SELECT doc FROM punches WHERE scope = ? AND date BETWEEN ? AND ? ORDER BY date, rowid", Codec.scope(scope), from.epochDay, to.epochDay).map(::punchOf)
    override fun savePunch(punch: ir.sabou.payroll.Punch) = db.execute(
        "INSERT INTO punches (id, scope, date, employee_id, doc) VALUES (?, ?, ?, ?, ?)",
        punch.id.value, Codec.scope(punch.scope), punch.date.epochDay, punch.employeeId.value,
        Json.encode(mapOf("id" to punch.id.value, "scope" to Codec.scope(punch.scope), "employee" to punch.employeeId.value, "date" to punch.date.epochDay,
            "minute" to punch.minute.toLong(), "kind" to punch.kind.name, "at" to punch.atEpochMillis,
            "manualBy" to punch.manualBy?.value, "reason" to punch.reason, "voids" to punch.voids?.value)),
    )
    override fun clockPin(employeeId: GlobalId) = db.query("SELECT hash FROM clock_pins WHERE employee_id = ?", employeeId.value).firstOrNull()?.str("hash")
    override fun saveClockPin(employeeId: GlobalId, hash: String?) {
        if (hash == null) db.execute("DELETE FROM clock_pins WHERE employee_id = ?", employeeId.value)
        else db.execute("INSERT INTO clock_pins (employee_id, hash) VALUES (?, ?) ON CONFLICT(employee_id) DO UPDATE SET hash = excluded.hash", employeeId.value, hash)
    }
    override fun pinFailures(employeeId: GlobalId) = db.query("SELECT fails, locked_until FROM clock_pins WHERE employee_id = ?", employeeId.value).firstOrNull()
        ?.let { ir.sabou.payroll.PinFailures(it.long("fails").toInt(), it.long("locked_until")) } ?: ir.sabou.payroll.PinFailures(0, 0L)
    override fun savePinFailures(employeeId: GlobalId, failures: ir.sabou.payroll.PinFailures) {
        db.execute("UPDATE clock_pins SET fails = ?, locked_until = ? WHERE employee_id = ?", failures.count.toLong(), failures.lockedUntil, employeeId.value)
    }
}

/** Pay elements, one-off payslip lines and leave (ADR-0043). */
class SqlPayElementStore(db: SqlDatabase) : SqlTable(db), ir.sabou.payroll.PayElementStore {
    private fun elementOf(d: Doc) = ir.sabou.payroll.PayElement(
        Codec.id(d.str("id")), d.str("name"), ir.sabou.payroll.PayLine.Kind.valueOf(d.str("kind")), ir.sabou.payroll.PayElement.Basis.valueOf(d.str("basis")),
        d.long("amount"), d.bool("ins"), d.bool("tax"), d.longOrNull("from")?.let(Codec::date), d.bool("active"), d.strsOr("employees").map(Codec::id).toSet(),
    )
    override fun elements() = docs("SELECT doc FROM pay_elements ORDER BY rowid").map(::elementOf)
    override fun saveElement(element: ir.sabou.payroll.PayElement) = upsert("pay_elements", "id", mapOf("id" to element.id.value, "doc" to Json.encode(mapOf(
        "id" to element.id.value, "name" to element.name, "kind" to element.kind.name, "basis" to element.basis.name, "amount" to element.amount,
        "ins" to element.insurable, "tax" to element.taxable, "from" to element.from?.epochDay, "active" to element.active,
        "employees" to element.employees.map { it.value },
    ))))

    private fun adjustmentOf(d: Doc) = ir.sabou.payroll.PayAdjustment(
        Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.id(d.str("employee")), Codec.date(d.long("period")), d.str("title"),
        ir.sabou.payroll.PayLine.Kind.valueOf(d.str("kind")), Codec.money(d.long("amount")), d.bool("ins"), d.bool("tax"),
    )
    override fun adjustments(scope: Scope.Branch, periodFrom: BusinessDate) =
        docs("SELECT doc FROM pay_adjustments WHERE scope = ? AND period = ? ORDER BY rowid", Codec.scope(scope), periodFrom.epochDay).map(::adjustmentOf)
    override fun adjustment(id: GlobalId) = doc("SELECT doc FROM pay_adjustments WHERE id = ?", id.value)?.let(::adjustmentOf)
    override fun saveAdjustment(adjustment: ir.sabou.payroll.PayAdjustment) = upsert("pay_adjustments", "id", mapOf(
        "id" to adjustment.id.value, "scope" to Codec.scope(adjustment.scope), "period" to adjustment.periodFrom.epochDay,
        "doc" to Json.encode(mapOf(
            "id" to adjustment.id.value, "scope" to Codec.scope(adjustment.scope), "employee" to adjustment.employeeId.value, "period" to adjustment.periodFrom.epochDay,
            "title" to adjustment.title, "kind" to adjustment.kind.name, "amount" to adjustment.amount.rial, "ins" to adjustment.insurable, "tax" to adjustment.taxable,
        )),
    ))
    override fun deleteAdjustment(id: GlobalId) { db.execute("DELETE FROM pay_adjustments WHERE id = ?", id.value) }

    private fun leaveOf(d: Doc) = ir.sabou.payroll.Leave(
        Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.id(d.str("employee")), Codec.date(d.long("date")),
        ir.sabou.payroll.Leave.Kind.valueOf(d.str("kind")), d.int("minutes"), d.strOr("note", ""), d.boolOr("cancelled", false),
    )
    override fun leaves(employeeId: GlobalId) = docs("SELECT doc FROM leaves WHERE employee_id = ? ORDER BY date, rowid", employeeId.value).map(::leaveOf)
    override fun leave(id: GlobalId) = doc("SELECT doc FROM leaves WHERE id = ?", id.value)?.let(::leaveOf)
    override fun saveLeave(leave: ir.sabou.payroll.Leave) = upsert("leaves", "id", mapOf(
        "id" to leave.id.value, "employee_id" to leave.employeeId.value, "date" to leave.date.epochDay,
        "doc" to Json.encode(mapOf(
            "id" to leave.id.value, "scope" to Codec.scope(leave.scope), "employee" to leave.employeeId.value, "date" to leave.date.epochDay,
            "kind" to leave.kind.name, "minutes" to leave.minutes.toLong(), "note" to leave.note, "cancelled" to leave.cancelled,
        )),
    ))
}

/** The restaurant's payroll options (one JSON value in the meta table). */
class SqlPayrollSettingsStore(db: SqlDatabase) : ir.sabou.payroll.PayrollSettingsStore {
    private val meta = DatabaseMeta(db)
    override fun get(): ir.sabou.payroll.PayrollSettings {
        val d = meta.get(KEY)?.let { Doc.parse(it) } ?: return ir.sabou.payroll.PayrollSettings()
        val defaults = ir.sabou.payroll.PayrollSettings()
        return ir.sabou.payroll.PayrollSettings(
            deductPlannedBreaks = d.boolOr("deductBreaks", defaults.deductPlannedBreaks),
            overtimeMode = ir.sabou.payroll.OvertimeMode.valueOf(d.strOr("overtimeMode", defaults.overtimeMode.name)),
            overtimeHourlyRate = Codec.money(d.longOr("overtimeRate", 0)),
            overtimeMinimumMinutes = d.intOr("overtimeMinimum", defaults.overtimeMinimumMinutes),
            lateGraceMinutes = d.intOr("lateGrace", defaults.lateGraceMinutes),
            clockPhotoRequired = d.boolOr("clockPhoto", defaults.clockPhotoRequired),
            nightPremiumBp = d.intOr("nightBp", defaults.nightPremiumBp), nightStartMinute = d.intOr("nightStart", defaults.nightStartMinute),
            nightEndMinute = d.intOr("nightEnd", defaults.nightEndMinute), holidayPremiumBp = d.intOr("holidayBp", defaults.holidayPremiumBp),
            leaveDaysPerYear = d.intOr("leaveDays", defaults.leaveDaysPerYear), workdayMinutes = d.intOr("workday", defaults.workdayMinutes),
            leaveCarryDays = d.intOr("leaveCarry", defaults.leaveCarryDays), leavePayoutOnExit = d.boolOr("leavePayout", defaults.leavePayoutOnExit),
            bonusInsurable = d.boolOr("bonusIns", defaults.bonusInsurable), bonusTaxable = d.boolOr("bonusTax", defaults.bonusTaxable),
            eidiEnabled = d.boolOr("eidi", defaults.eidiEnabled), eidiDays = d.intOr("eidiDays", defaults.eidiDays),
            eidiCap = Codec.money(d.longOr("eidiCap", 0)), eidiMonth = d.intOr("eidiMonth", defaults.eidiMonth),
            eidiTaxExempt = Codec.money(d.longOr("eidiExempt", 0)),
            sanavatEnabled = d.boolOr("sanavat", defaults.sanavatEnabled), sanavatDays = d.intOr("sanavatDays", defaults.sanavatDays),
            sanavatYearly = d.boolOr("sanavatYearly", defaults.sanavatYearly),
        )
    }
    override fun save(settings: ir.sabou.payroll.PayrollSettings) = meta.put(KEY, Json.encode(mapOf(
        "deductBreaks" to settings.deductPlannedBreaks, "overtimeMode" to settings.overtimeMode.name, "overtimeRate" to settings.overtimeHourlyRate.rial,
        "overtimeMinimum" to settings.overtimeMinimumMinutes.toLong(), "lateGrace" to settings.lateGraceMinutes.toLong(),
        "clockPhoto" to settings.clockPhotoRequired,
        "nightBp" to settings.nightPremiumBp.toLong(), "nightStart" to settings.nightStartMinute.toLong(), "nightEnd" to settings.nightEndMinute.toLong(),
        "holidayBp" to settings.holidayPremiumBp.toLong(), "leaveDays" to settings.leaveDaysPerYear.toLong(), "workday" to settings.workdayMinutes.toLong(),
        "leaveCarry" to settings.leaveCarryDays.toLong(), "leavePayout" to settings.leavePayoutOnExit, "bonusIns" to settings.bonusInsurable,
        "bonusTax" to settings.bonusTaxable, "eidi" to settings.eidiEnabled, "eidiDays" to settings.eidiDays.toLong(), "eidiCap" to settings.eidiCap.rial,
        "eidiMonth" to settings.eidiMonth.toLong(), "eidiExempt" to settings.eidiTaxExempt.rial, "sanavat" to settings.sanavatEnabled,
        "sanavatDays" to settings.sanavatDays.toLong(), "sanavatYearly" to settings.sanavatYearly,
    )))
    private companion object { const val KEY = "payroll.settings" }
}

/** Staff documents; immutable (a renewal is a new document) (ADR-0039). */
class SqlStaffDocumentStore(db: SqlDatabase) : SqlTable(db), ir.sabou.payroll.StaffDocumentStore {
    private fun read(d: Doc) = ir.sabou.payroll.StaffDocument(Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.id(d.str("employee")),
        ir.sabou.payroll.StaffDocument.Kind.valueOf(d.str("kind")), d.strOr("title", ""), d.longOrNull("expires")?.let(Codec::date), d.strOr("note", ""), d.long("at"),
        replaces = d.strOrNull("replaces")?.let(Codec::id), voided = d.boolOr("voided", false), reason = d.strOr("reason", ""))
    override fun byId(id: GlobalId) = doc("SELECT doc FROM staff_documents WHERE id = ?", id.value)?.let(::read)
    override fun of(employeeId: GlobalId) = docs("SELECT doc FROM staff_documents WHERE employee_id = ? ORDER BY rowid", employeeId.value).map(::read)
    override fun all(scope: Scope.Branch) = docs("SELECT doc FROM staff_documents WHERE scope = ? ORDER BY rowid", Codec.scope(scope)).map(::read)
    override fun save(document: ir.sabou.payroll.StaffDocument) = db.execute(
        "INSERT INTO staff_documents (id, scope, employee_id, doc) VALUES (?, ?, ?, ?)",
        document.id.value, Codec.scope(document.scope), document.employeeId.value,
        Json.encode(mapOf("id" to document.id.value, "scope" to Codec.scope(document.scope), "employee" to document.employeeId.value, "kind" to document.kind.name,
            "title" to document.title, "expires" to document.expiresOn?.epochDay, "note" to document.note, "at" to document.recordedAtEpochMillis,
            "replaces" to document.replaces?.value, "voided" to document.voided, "reason" to document.reason)),
    )
}

/** Special days of the sales forecast (ADR-0041). */
class SqlSpecialDayStore(db: SqlDatabase) : SqlTable(db), ir.sabou.sales.SpecialDayStore {
    override fun of(scope: Scope.Branch) = docs("SELECT doc FROM special_days WHERE scope = ? ORDER BY date", Codec.scope(scope)).map { d ->
        ir.sabou.sales.SpecialDay(Codec.branchOf(d.str("scope")), Codec.date(d.long("date")), ir.sabou.sales.SpecialDay.Kind.valueOf(d.str("kind")), d.long("percent").toInt(), d.strOr("note", ""))
    }
    override fun save(day: ir.sabou.sales.SpecialDay) {
        db.execute("INSERT INTO special_days (scope, date, doc) VALUES (?, ?, ?) ON CONFLICT(scope, date) DO UPDATE SET doc = excluded.doc",
            Codec.scope(day.scope), day.date.epochDay,
            Json.encode(mapOf("scope" to Codec.scope(day.scope), "date" to day.date.epochDay, "kind" to day.kind.name, "percent" to day.percent.toLong(), "note" to day.note)))
    }
    override fun remove(scope: Scope.Branch, date: BusinessDate) { db.execute("DELETE FROM special_days WHERE scope = ? AND date = ?", Codec.scope(scope), date.epochDay) }
}
