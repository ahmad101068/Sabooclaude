package ir.sabou.core

import ir.sabou.inventory.MenuItem
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.platform.Actor
import ir.sabou.platform.Permission
import ir.sabou.sales.Customer
import ir.sabou.sales.CustomerType
import ir.sabou.sales.ItemTaxCode
import ir.sabou.sales.TaxProfile

/**
 * How ready the data is for the tax system (سامانه مودیان, ADR-0037): the seller's identity and VAT rate, a product
 * ID on every active dish, the tax ID of every active company customer. Nothing is sent from here.
 */
data class TaxReadiness(
    val profile: TaxProfile?,
    val vatToday: Long?,
    val codes: Map<ir.sabou.kernel.GlobalId, ItemTaxCode>,
    val dishesWithoutId: List<MenuItem>,
    val companiesWithoutId: List<Customer>,
) {
    val problems: List<String> get() = buildList {
        if (profile == null) add("شناسه‌ی مالیاتی فروشنده و نرخ مالیات بر ارزش افزوده تعیین نشده است.")
        else {
            if (profile.memoryId.isEmpty()) add("شناسه‌ی یکتای حافظه‌ی مالیاتی (از کارپوشه‌ی سامانه‌ی مودیان) وارد نشده است.")
            if (vatToday == null) add("نرخ مالیات بر ارزش افزوده برای امروز تعیین نشده است.")
        }
        if (dishesWithoutId.isNotEmpty()) add("${dishesWithoutId.size} آیتم منو شناسه‌ی کالا/خدمت ندارد.")
        if (companiesWithoutId.isNotEmpty()) add("${companiesWithoutId.size} مشتری حقوقی شناسه‌ی ملی یا کد اقتصادی ندارد.")
    }
    val ready: Boolean get() = problems.isEmpty()
}

/** A posted sale that can get an invoice with the buyer's details. */
data class TaxCandidate(val sale: ir.sabou.sales.DailySale, val customer: Customer, val branch: String)

data class TaxInvoiceRow(val invoice: ir.sabou.sales.TaxInvoice, val customer: String, val branch: String)

/** What a send or an inquiry did, for the screen. */
data class TaxSendReport(val accepted: Int, val sent: Int, val rejected: Int, val waiting: Int, val problems: List<String>)

class Moadian internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    fun readiness(today: BusinessDate): TaxReadiness {
        actor(Permission.TAX_SETTINGS, Permission.LEDGER_VIEW)
        val profile = core.taxStore.profile()
        val codes = core.taxStore.codes().filter { it.productId.isNotEmpty() }.associateBy { it.menuItemId }
        return TaxReadiness(
            profile, profile?.vatOn(today), codes,
            core.recipes.menuItems().filter { it.isActive && it.id !in codes },
            core.customers.all().filter { it.isActive && it.type == CustomerType.COMPANY && it.taxId.isEmpty() },
        )
    }

    /** Posted sales on account to a customer with a tax ID that have no live invoice yet (newest first). */
    fun candidates(): List<TaxCandidate> {
        val a = actor(Permission.TAX_SETTINGS)
        val live = core.taxInvoiceStore.all().filter { it.kind == ir.sabou.sales.TaxInvoiceKind.ORIGINAL && it.status != ir.sabou.sales.TaxInvoiceStatus.REJECTED }.map { it.saleId }.toSet()
        val branches = core.branches.all().associate { it.id to it.name }
        return core.customers.all().filter { it.taxId.isNotEmpty() }.flatMap { c ->
            core.sales.receivablesOfCustomer(c.id).map { it.saleId }.distinct().mapNotNull { core.sales.sale(it) }
                .filter { it.status == ir.sabou.sales.SaleStatus.POSTED && it.id !in live && a.canAccess(it.scope) }
                .map { TaxCandidate(it, c, branches[it.scope.branchId].orEmpty()) }
        }.sortedByDescending { it.sale.date }
    }

    fun invoices(): List<TaxInvoiceRow> {
        val a = actor(Permission.TAX_SETTINGS)
        val names = core.customers.all().associate { it.id to it.name }
        val branches = core.branches.all().associate { it.id to it.name }
        return core.taxInvoiceStore.all().filter { a.canAccess(it.scope) }.sortedByDescending { it.serial }
            .map { TaxInvoiceRow(it, names[it.customerId].orEmpty(), branches[it.scope.branchId].orEmpty()) }
    }

    /** The invoice's JSON exactly as it is (or would be) sent. */
    fun payload(id: ir.sabou.kernel.GlobalId): String {
        val a = actor(Permission.TAX_SETTINGS)
        val inv = core.taxInvoiceStore.byId(id)?.takeIf { a.canAccess(it.scope) } ?: throw DomainException(DomainError.NotFound("TAX_INVOICE"))
        return MoadianPayload.invoice(inv)
    }

    /**
     * Sends the issued invoices [ids] (all ready ones when null) and records each receipt. Network calls happen
     * outside any transaction; each result is its own command. Call on a background thread.
     */
    fun send(client: MoadianClient, ids: Set<ir.sabou.kernel.GlobalId>? = null, now: Long = System.currentTimeMillis()): TaxSendReport {
        actor(Permission.TAX_SETTINGS)
        val ready = invoices().map { it.invoice }.filter { it.status == ir.sabou.sales.TaxInvoiceStatus.READY && (ids == null || it.id in ids) }
        if (ready.isEmpty()) return TaxSendReport(0, 0, 0, 0, emptyList())
        val receipts = client.send(ready.map { it.id.value to MoadianPayload.invoice(it) })
        var sent = 0; var rejected = 0
        val problems = mutableListOf<String>()
        ready.zip(receipts).forEach { (inv, r) ->
            if (r.referenceNumber != null) {
                record(inv, ir.sabou.sales.TaxInvoiceStatus.SENT, r.referenceNumber, r.uid, emptyList(), now); sent++
            } else {
                record(inv, ir.sabou.sales.TaxInvoiceStatus.REJECTED, null, r.uid, listOfNotNull(r.error), now); rejected++
                problems += "${Fa.digits(inv.taxId)}: ${r.error.orEmpty()}"
            }
        }
        return TaxSendReport(0, sent, rejected, 0, problems)
    }

    /** Asks for the result of every sent invoice and records the answers that are final. */
    fun inquire(client: MoadianClient, now: Long = System.currentTimeMillis()): TaxSendReport {
        actor(Permission.TAX_SETTINGS)
        val sent = invoices().map { it.invoice }.filter { it.status == ir.sabou.sales.TaxInvoiceStatus.SENT && it.referenceNumber != null }
        if (sent.isEmpty()) return TaxSendReport(0, 0, 0, 0, emptyList())
        val answers = sent.chunked(10).flatMap { batch -> client.inquire(batch.map { it.referenceNumber!! }) }.associateBy { it.referenceNumber }
        var accepted = 0; var rejected = 0; var waiting = 0
        val problems = mutableListOf<String>()
        sent.forEach { inv ->
            val a = answers[inv.referenceNumber]
            when {
                a == null -> waiting++
                a.accepted -> { record(inv, ir.sabou.sales.TaxInvoiceStatus.ACCEPTED, inv.referenceNumber, a.uid, a.warnings, now); accepted++ }
                a.rejected -> { record(inv, ir.sabou.sales.TaxInvoiceStatus.REJECTED, inv.referenceNumber, a.uid, a.errors + a.warnings, now); rejected++
                    problems += "${Fa.digits(inv.taxId)}: ${a.errors.joinToString("؛ ")}" }
                else -> waiting++
            }
        }
        return TaxSendReport(accepted, 0, rejected, waiting, problems)
    }

    private fun record(inv: ir.sabou.sales.TaxInvoice, status: ir.sabou.sales.TaxInvoiceStatus, ref: String?, uid: String?, messages: List<String>, now: Long) {
        core.taxInvoices.record(ir.sabou.sales.RecordTaxInvoiceResult(ir.sabou.kernel.GlobalId.new(), inv.scope, inv.id, status, ref, uid, messages, now))
    }
}
