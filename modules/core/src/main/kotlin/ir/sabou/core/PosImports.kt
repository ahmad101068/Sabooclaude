package ir.sabou.core

import ir.sabou.inventory.MenuItem
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission
import ir.sabou.posimport.FileCurrency
import ir.sabou.posimport.ImportFileException
import ir.sabou.posimport.InvoiceReport
import ir.sabou.posimport.InvoiceStatus
import ir.sabou.posimport.ItemSalesReport
import ir.sabou.posimport.Parsed
import ir.sabou.posimport.PosReports
import ir.sabou.posimport.Sheets
import ir.sabou.posimport.Text
import ir.sabou.sales.Customer
import ir.sabou.sales.ImportPosSale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import java.math.BigInteger
import java.security.MessageDigest

/** A file the person picked. */
class PosFile(val name: String, val bytes: ByteArray)

/** What the person decided where the files were silent or unknown. */
data class PosChoices(
    val date: BusinessDate? = null,
    val currency: FileCurrency? = null,
    /** Normalized POS item name → menu item, for names not matched yet. */
    val matches: Map<String, GlobalId> = emptyMap(),
    /** POS customer name → customer, for credit bills. */
    val customers: Map<String, GlobalId> = emptyMap(),
)

/** Where the money goes. With an invoices file: cash and card; without it: everything into [single]. */
data class PosAccounts(val cash: GlobalId? = null, val card: GlobalId? = null, val single: GlobalId? = null, val kitchen: GlobalId? = null)

data class PosLine(val name: String, val key: String, val code: String?, val quantity: Quantity, val gross: Long, val menuItem: MenuItem?, val hasRecipe: Boolean)
data class PosCredit(val name: String, val key: String, val amount: Long, val customer: Customer?)

/** Everything the import would record, and what still stops it ([problems]). Amounts in Rial. */
data class PosPreview(
    val files: List<String>,
    val date: BusinessDate?,
    val dateFromFile: Boolean,
    val currency: FileCurrency?,
    val currencyFromFile: Boolean,
    val lines: List<PosLine>,
    val gross: Long,
    val discount: Long,
    val service: Long,
    val tax: Long,
    val payable: Long,
    val hasInvoices: Boolean,
    val bills: Int,
    val voided: Int,
    val returns: Int,
    val cash: Long,
    val card: Long,
    val credits: List<PosCredit>,
    val alreadyImported: Boolean,
    val problems: List<String>,
) {
    val unknown: List<PosLine> get() = lines.filter { it.menuItem == null }
    val ready: Boolean get() = problems.isEmpty()
}

/**
 * Imports a day of a point-of-sale system from its Excel/CSV exports (ADR-0026): a «sales by item» report
 * (required: the menu lines, so stock is consumed by recipe) and optionally an «invoices» report (service, tax,
 * discounts and how it was paid). Nothing is recorded until every problem is resolved; then the day is drafted
 * and posted in one command, with protection against importing it twice.
 */
class PosImports internal constructor(private val core: SabouCore) {
    fun preview(branch: Scope.Branch, files: List<PosFile>, choices: PosChoices, pos: String = SEPAND): PosPreview {
        val actor = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (!actor.role.allows(Permission.SALES_POST)) throw DomainException(DomainError.PermissionDenied(Permission.SALES_POST.name))
        if (!actor.canAccess(branch)) throw DomainException(DomainError.ScopeDenied("branch"))
        val problems = ArrayList<String>()
        var items: ItemSalesReport? = null
        var invoices: InvoiceReport? = null
        val described = ArrayList<String>()
        for (f in files) {
            val parsed = try { PosReports.parse(Sheets.read(f.name, f.bytes)) } catch (e: ImportFileException) { problems += "${f.name}: ${e.message}"; continue }
            when (parsed) {
                is Parsed.Items -> {
                    if (items != null) problems += "دو فایل «فروش کالا» انتخاب شده است."
                    items = parsed.report; described += "${f.name}: فروش کالا، ${Fa.number(parsed.report.lines.size.toLong())} سطر"
                    parsed.report.problems.forEach { problems += "${f.name}: $it" }
                }
                is Parsed.Invoices -> {
                    if (invoices != null) problems += "دو فایل «ریز فاکتورها» انتخاب شده است."
                    invoices = parsed.report; described += "${f.name}: ریز فاکتورها، ${Fa.number(parsed.report.invoices.size.toLong())} فاکتور"
                    parsed.report.problems.forEach { problems += "${f.name}: $it" }
                }
            }
        }
        if (items == null) problems += "گزارش «فروش کالا» لازم است (اقلام هر غذا برای مصرف انبار از روی رسپی)."

        // The money unit: the files', else the person's; never guessed.
        val stated = listOfNotNull(items?.currency, invoices?.currency).distinct()
        if (stated.size > 1) problems += "واحد مبالغ دو فایل با هم یکی نیست."
        val currency = stated.singleOrNull() ?: choices.currency
        if (currency == null) problems += FileCurrency.UNKNOWN
        val f = currency?.rialPerUnit ?: 1L

        val fileDates = listOfNotNull(items?.date, invoices?.date).distinct()
        if (fileDates.size > 1) problems += "تاریخ دو فایل با هم یکی نیست."
        val date = fileDates.singleOrNull() ?: choices.date
        if (date == null) problems += "فایل تاریخ ندارد؛ روز فروش را انتخاب کنید."

        // Menu lines: remembered names, then the person's choices, then the menu's own names.
        val menu = core.recipes.menuItems().filter { it.isActive }
        val byKey = menu.associateBy { Text.key(it.name) }
        val aliases = core.posImportStore.aliases(pos)
        val lines = items?.lines.orEmpty().map { l ->
            val key = Text.key(l.name)
            val item = (choices.matches[key] ?: aliases[key])?.let { id -> menu.firstOrNull { it.id == id } } ?: byKey[key]
            val recipe = item != null && date != null && runCatching { core.recipeBook.versionOn(item.id, date) }.isSuccess
            PosLine(l.name, key, l.code, Quantity.of(l.quantityMicros), l.gross * f, item, recipe)
        }
        lines.filter { it.menuItem == null }.takeIf { it.isNotEmpty() }?.let { problems += "${Fa.number(it.size.toLong())} قلم با منو جور نشد: ${it.joinToString("، ") { l -> l.name }}" }
        lines.filter { it.menuItem != null && !it.hasRecipe }.forEach { problems += "«${it.menuItem!!.name}» در این تاریخ رسپی ندارد؛ از «منو و رسپی» رسپی‌اش را تعریف کنید." }
        lines.filter { it.quantity.micros <= 0 || it.gross < 0 }.forEach { problems += "«${it.name}»: تعداد یا مبلغ منفی است؛ برگشت‌ها را در فاکتورها بیاورید." }
        lines.filter { it.menuItem != null }.groupBy { it.menuItem!!.id }.values.filter { it.size > 1 }
            .forEach { same -> problems += "چند نام صندوق به «${same.first().menuItem!!.name}» وصل شده‌اند: ${same.joinToString("، ") { it.name }}" }

        val gross = lines.sumOf { it.gross }
        val itemDiscount = items?.lines.orEmpty().sumOf { it.discount } * f
        val itemTax = items?.lines.orEmpty().sumOf { it.tax } * f
        val counted = invoices?.counted.orEmpty()
        val customers = core.customers.all().filter { it.isActive && it.registeredIn == branch }
        val credits = counted.filter { (it.credit ?: 0) != 0L }.groupBy { Text.key(it.customer) }.map { (key, bills) ->
            val name = bills.first().customer.orEmpty()
            val chosen = choices.customers[key]?.let { id -> customers.firstOrNull { it.id == id } } ?: customers.firstOrNull { Text.key(it.name) == key && key.isNotEmpty() }
            PosCredit(name, key, bills.sumOf { it.credit ?: 0 } * f, chosen)
        }
        val (discount, service, tax, payable) = if (invoices != null) {
            val total = counted.sumOf { it.total } * f
            if (items != null && total != gross) problems += "جمع فروش کالا (${Fa.rial(gross)} ریال) با جمع فاکتورها پیش از تخفیف (${Fa.rial(total)} ریال) برابر نیست؛ دو گزارش باید از یک روز و یک صندوق باشند."
            listOf(counted.sumOf { it.discount } * f, counted.sumOf { it.service } * f, counted.sumOf { it.tax } * f, counted.sumOf { it.payable } * f)
        } else listOf(itemDiscount, 0L, itemTax, gross - itemDiscount + itemTax)
        val cash = counted.sumOf { it.cash ?: 0 } * f
        val card = counted.sumOf { it.card ?: 0 } * f
        if (invoices != null) {
            if (cash < 0 || card < 0) problems += "جمع نقد یا کارت این روز منفی است؛ برگشت‌ها بیش از فروش است."
            if (cash + card + credits.sumOf { it.amount } != payable) problems += "روش‌های پرداخت فاکتورها با جمع قابل پرداخت برابر نیست."
            credits.filter { it.name.isBlank() }.forEach { _ -> problems += "فاکتور نسیه بدون نام مشتری هست." }
            credits.filter { it.name.isNotBlank() && it.customer == null }.forEach { problems += "مشتری اعتباری «${it.name}» در این شعبه پیدا نشد؛ آن را انتخاب یا تعریف کنید." }
        }
        if (discount < 0 || discount > gross) problems += "تخفیف این روز معتبر نیست."
        val already = date != null && core.posImportStore.all().any { it.active && it.pos == pos && it.scope == branch && it.date == date }
        if (already) problems += "فروش این روز از همین صندوق قبلاً وارد شده است."
        return PosPreview(
            described, date, fileDates.isNotEmpty(), currency, stated.isNotEmpty(), lines, gross, discount, service, tax, payable,
            invoices != null, counted.count { it.status == InvoiceStatus.NORMAL }, invoices?.invoices.orEmpty().count { it.status == InvoiceStatus.VOID },
            counted.count { it.status == InvoiceStatus.RETURN }, cash, card, credits, already, problems,
        )
    }

    /** Records the day: re-checks everything, then drafts and posts it in one command. */
    fun commit(commandId: GlobalId, branch: Scope.Branch, files: List<PosFile>, choices: PosChoices, accounts: PosAccounts, pos: String = SEPAND): ir.sabou.platform.CommandOutcome {
        val p = preview(branch, files, choices, pos)
        if (!p.ready) throw DomainException(DomainError.InvalidState("POS_IMPORT", "NOT_READY"))
        val date = p.date!!
        val kitchen = accounts.kitchen ?: core.locations.all().firstOrNull { it.scope == branch && it.isActive }?.id
            ?: throw DomainException(DomainError.InvalidInput("location", "آشپزخانه یا انباری برای این شعبه نیست."))

        // Unit price per menu item. Totals are the POS's: a price that does not divide evenly is rounded up and
        // the few Rial over go to the day's discount, so the payable stays exactly the POS's.
        var rounding = 0L
        val saleLines = p.lines.map { l ->
            val unit = BigInteger.valueOf(l.gross).multiply(BigInteger.valueOf(Quantity.SCALE))
                .add(BigInteger.valueOf(l.quantity.micros - 1)).divide(BigInteger.valueOf(l.quantity.micros)).toLong()
            rounding += Money.of(unit).times(l.quantity).rial - l.gross
            val listed = core.salesOps.priceList.unitPriceOn(l.menuItem!!.id, branch, date)
            SaleLineInput(l.menuItem.id, l.quantity, Money.of(unit), if (listed != null && listed.rial != unit) "قیمت صندوق ${posName(pos)}" else null)
        }
        val settlements: List<Settlement> = if (p.hasInvoices) buildList<Settlement> {
            if (p.cash > 0) add(Settlement.Liquid(accounts.cash ?: throw DomainException(DomainError.InvalidInput("account", "صندوق نقد را انتخاب کنید.")), Money.of(p.cash)))
            if (p.card > 0) add(Settlement.Liquid(accounts.card ?: throw DomainException(DomainError.InvalidInput("account", "حساب کارت‌خوان را انتخاب کنید.")), Money.of(p.card)))
            p.credits.forEach { add(Settlement.Credit(it.customer!!.id, Money.of(it.amount), date)) }
        } else listOf(Settlement.Liquid(accounts.single ?: throw DomainException(DomainError.InvalidInput("account", "حساب دریافت فروش را انتخاب کنید.")), Money.of(p.payable)))
        val sale = SaveSaleDraft(GlobalId.new(), branch, date, kitchen, saleLines, Money.of(p.discount + rounding), Money.of(p.service), Money.of(p.tax), settlements,
            transactions = p.bills)
        val learned = p.lines.filter { it.key in choices.matches }.associate { it.key to it.menuItem!!.id }
        val bills = if (p.hasInvoices) files.flatMap { f ->
            runCatching { (PosReports.parse(Sheets.read(f.name, f.bytes)) as? Parsed.Invoices)?.report?.invoices.orEmpty() }.getOrDefault(emptyList())
        }.map { "${it.number}|${date.epochDay}" } else emptyList()
        return core.salesOps.importPosDay(ImportPosSale(commandId, branch, pos, files.map { it.name }, files.map { sha256(it.bytes) }, bills, sale, learned))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun posName(pos: String) = if (pos == SEPAND) "سپند" else pos

    companion object {
        const val SEPAND = "SEPAND"
    }
}
