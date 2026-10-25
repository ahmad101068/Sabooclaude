package ir.sabou.core

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.platform.Actor
import ir.sabou.platform.DocumentSeries
import ir.sabou.platform.Permission
import ir.sabou.purchasing.InvoiceStatus

/** One search result: what it is, how to show it, and which record to open (ADR-0040). */
data class SearchHit(val kind: Kind, val id: GlobalId, val title: String, val detail: String) {
    enum class Kind { CUSTOMER, SUPPLIER, ITEM, MENU_ITEM, EMPLOYEE, PURCHASE_INVOICE, PURCHASE_ORDER, STOCK_COUNT, DAILY_SALE, CHEQUE, ASSET, DOCUMENT }
}

/**
 * One search box over the records a person may see (ADR-0040): names (customers, suppliers, items, dishes, staff,
 * assets), supplier invoice numbers, document numbers («فخ-1405-00012» or just «12»), cheque numbers, Sayad IDs and
 * counterparties, and amounts (an invoice or cheque of exactly that many Rial). Text is compared after the same
 * normalization as imports: Persian/Arabic letters and digits, no spaces or zero-width marks.
 */
class Search internal constructor(private val core: SabouCore) {
    private fun actor(): Actor = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)

    fun find(query: String, limit: Int = 40): List<SearchHit> {
        val a = actor()
        val q = key(query)
        val c = compact(query)
        if (q.length < 2 && c.toLongOrNull() == null) return emptyList()
        fun can(vararg p: Permission) = p.any { a.role.allows(it) }
        fun visible(scope: Scope) = a.canAccess(scope)
        fun has(text: String) = key(text).contains(q)
        // «12,345,000», «۱۲٬۳۴۵٬۰۰۰» or plain digits: also an amount.
        val number = ir.sabou.posimport.Text.amount(query) ?: c.toLongOrNull()
        val out = ArrayList<SearchHit>()

        if (can(Permission.CUSTOMER_MANAGE, Permission.SALES_VIEW)) core.customers.all().filter { visible(it.registeredIn) && (has(it.name) || has(it.phone) || (it.taxId.isNotEmpty() && has(it.taxId))) }
            .forEach { out += SearchHit(SearchHit.Kind.CUSTOMER, it.id, it.name, "مشتری" + if (it.phone.isNotBlank()) " · ${Fa.digits(it.phone)}" else "") }
        if (can(Permission.PURCHASE_VIEW, Permission.SUPPLIER_MANAGE)) core.suppliers.all().filter { has(it.name) || has(it.phone) }
            .forEach { out += SearchHit(SearchHit.Kind.SUPPLIER, it.id, it.name, "تأمین‌کننده" + if (it.phone.isNotBlank()) " · ${Fa.digits(it.phone)}" else "") }
        if (can(Permission.INVENTORY_VIEW, Permission.INVENTORY_ITEM_MANAGE)) core.items.all().filter { has(it.name) || (it.shelf.isNotBlank() && has(it.shelf)) }
            .forEach { out += SearchHit(SearchHit.Kind.ITEM, it.id, it.name, "کالا" + if (it.shelf.isNotBlank()) " · ${it.shelf}" else "") }
        if (can(Permission.RECIPE_MANAGE, Permission.SALES_VIEW, Permission.MENU_PRICE_MANAGE)) core.recipes.menuItems().filter { has(it.name) }
            .forEach { out += SearchHit(SearchHit.Kind.MENU_ITEM, it.id, it.name, "آیتم منو" + if (!it.isActive) " · غیرفعال" else "") }
        if (can(Permission.PERSONNEL_VIEW, Permission.PERSONNEL_MANAGE)) core.branches.all().map { Scope.Branch(it.id) }.filter(::visible)
            .flatMap { core.personnel.employees(it) }.filter { has(it.name) || has(it.nationalId) }
            .forEach { out += SearchHit(SearchHit.Kind.EMPLOYEE, it.id, it.name, "کارمند") }
        if (can(Permission.ASSET_VIEW, Permission.ASSET_MANAGE)) core.assetStore.all().filter { visible(it.scope) && has(it.name) }
            .forEach { out += SearchHit(SearchHit.Kind.ASSET, it.id, it.name, "دارایی ثابت") }

        if (can(Permission.PURCHASE_VIEW)) {
            val names = core.suppliers.all().associate { it.id to it.name }
            core.purchases.invoices().filter { visible(it.scope) && (compact(it.supplierInvoiceNo) == c || (number != null && it.total.rial == number)) }
                .forEach { out += SearchHit(SearchHit.Kind.PURCHASE_INVOICE, it.id, "فاکتور ${Fa.digits(it.supplierInvoiceNo)} · ${names[it.supplierId].orEmpty()}",
                    "${Fa.date(it.date)} · ${Fa.rial(it.total)} ریال" + if (it.status == InvoiceStatus.REVERSED) " · برگشت‌خورده" else "") }
        }
        if (can(Permission.CHEQUE_MANAGE, Permission.BANK_VIEW)) core.cheques.all().filter { c ->
            visible(c.scope) && (compact(c.details.number) == compact(query) || (c.details.sayadId.isNotBlank() && compact(c.details.sayadId) == compact(query)) ||
                has(c.details.counterparty) || (number != null && c.amount.rial == number))
        }.forEach { out += SearchHit(SearchHit.Kind.CHEQUE, it.id, "چک ${Fa.digits(it.details.number)} · ${it.details.counterparty}", "${Fa.date(it.dueDate)} · ${Fa.rial(it.amount)} ریال") }

        documentNumber(query)?.let { (series, sequence) ->
            core.documentNumbers.bySequence(sequence).filter { series == null || it.number.series == series }.forEach { n ->
                documentHit(n.number.series, n.documentId, a)?.let { hit -> out += hit.copy(detail = "${Fa.digits(n.number.text)} · ${hit.detail}") }
            }
        }
        // Exact matches first (a name that is the query), then by kind as listed.
        return out.distinctBy { it.kind to it.id }.sortedBy { if (key(it.title) == q) 0 else 1 }.take(limit)
    }

    /** «فخ-1405-00012» → (PURCHASE_INVOICE, 12); «12» → (any series, 12). */
    private fun documentNumber(query: String): Pair<DocumentSeries?, Long>? {
        val t = ir.sabou.posimport.Text.normalize(query)
        Regex("^(\\S{2})\\s*-\\s*(\\d{4})\\s*-\\s*(\\d+)$").find(t)?.let { m ->
            val series = DocumentSeries.entries.firstOrNull { it.code == m.groupValues[1] } ?: return null
            return series to m.groupValues[3].toLong()
        }
        return t.toLongOrNull()?.takeIf { it in 1..999_999 }?.let { null to it }
    }

    /** A numbered document the actor may open, as a hit; null when it is not one of the openable kinds or not visible. */
    private fun documentHit(series: DocumentSeries, id: GlobalId, a: Actor): SearchHit? = when (series) {
        DocumentSeries.PURCHASE_INVOICE -> core.purchases.invoice(id)?.takeIf { a.role.allows(Permission.PURCHASE_VIEW) && a.canAccess(it.scope) }
            ?.let { SearchHit(SearchHit.Kind.PURCHASE_INVOICE, id, "فاکتور خرید ${Fa.digits(it.supplierInvoiceNo)}", Fa.date(it.date)) }
        DocumentSeries.PURCHASE_ORDER -> core.purchases.order(id)?.takeIf { a.role.allows(Permission.PURCHASE_VIEW) && a.canAccess(it.scope) }
            ?.let { SearchHit(SearchHit.Kind.PURCHASE_ORDER, id, "سفارش خرید", Fa.date(it.date)) }
        DocumentSeries.STOCK_COUNT -> core.stockCounts.byId(id)?.takeIf { a.role.allows(Permission.INVENTORY_COUNT) || a.role.allows(Permission.INVENTORY_ADJUST) }?.takeIf { a.canAccess(it.scope) }
            ?.let { SearchHit(SearchHit.Kind.STOCK_COUNT, id, "انبارگردانی", Fa.date(it.date)) }
        DocumentSeries.DAILY_SALE -> core.sales.sale(id)?.takeIf { a.role.allows(Permission.SALES_VIEW) && a.canAccess(it.scope) }
            ?.let { SearchHit(SearchHit.Kind.DAILY_SALE, id, "فروش", Fa.date(it.date)) }
        DocumentSeries.ASSET -> core.assetStore.byId(id)?.takeIf { a.role.allows(Permission.ASSET_VIEW) && a.canAccess(it.scope) }
            ?.let { SearchHit(SearchHit.Kind.ASSET, id, it.name, "خرید دارایی") }
        else -> if (a.role.allows(Permission.LEDGER_VIEW)) SearchHit(SearchHit.Kind.DOCUMENT, id, series.title, "") else null
    }

    private fun key(text: String): String = ir.sabou.posimport.Text.key(text)

    /** Letters and digits only: «A-778» = «a 778», «۰۲۱-۴۴۵۵» = «0214455». */
    private fun compact(text: String): String = key(text).filter { it.isLetterOrDigit() }
}
