package ir.sabou.sales

import ir.sabou.kernel.sumOfExact
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission

/**
 * What the tax system (سامانه مودیان) needs to know about the seller (ADR-0037). [sellerTaxId]: the seller's national
 * ID (individual, 10 digits), national ID of the legal entity (11 digits) or economic code (14 digits). [memoryId]:
 * the tax memory ID (شناسه یکتای حافظه مالیاتی, 6 letters/digits). [vatRates]: VAT by the date it applies from,
 * since the rate is set by law and changes.
 */
data class TaxProfile(val sellerTaxId: String, val memoryId: String, val vatRates: List<VatRate>) {
    fun vatOn(date: BusinessDate): Long? = vatRates.filter { it.from <= date }.maxByOrNull { it.from }?.bp
}

data class VatRate(val from: BusinessDate, val bp: Long) : java.io.Serializable

/** A menu item's product/service ID (شناسه کالا/خدمت, 13 digits) and the tax system's unit code. */
data class ItemTaxCode(val menuItemId: GlobalId, val productId: String, val unitCode: String)

interface TaxStore {
    fun profile(): TaxProfile?
    fun saveProfile(profile: TaxProfile)
    fun codes(): List<ItemTaxCode>
    fun saveCode(code: ItemTaxCode)
}

/** Identity numbers as the tax system checks them. */
object TaxIds {
    /** Individual national ID (کد ملی): 10 digits with a mod-11 check digit; not all the same digit. */
    fun isNationalId(id: String): Boolean = id.matches(Regex("\\d{10}")) && ir.sabou.kernel.NationalId.isValid(id)

    /** Legal entity national ID (شناسه ملی): 11 digits with its check digit. */
    fun isLegalId(id: String): Boolean {
        if (!id.matches(Regex("\\d{11}"))) return false
        val d = (id[9] - '0') + 2
        val coefficients = intArrayOf(29, 27, 23, 19, 17, 29, 27, 23, 19, 17)
        var r = (0 until 10).sumOf { ((id[it] - '0') + d) * coefficients[it] } % 11
        if (r == 10) r = 0
        return (id[10] - '0') == r
    }

    /** A seller or buyer ID: a valid national ID, a valid legal ID, or a 14-digit economic code. */
    fun isTaxpayerId(id: String): Boolean = isNationalId(id) || isLegalId(id) || id.matches(Regex("\\d{14}"))

    fun isMemoryId(id: String): Boolean = id.matches(Regex("[A-Z0-9]{6}"))

    fun isProductId(id: String): Boolean = id.matches(Regex("\\d{13}"))

    /**
     * The invoice's tax ID (شماره منحصربه‌فرد مالیاتی): memory ID + days since 1970-01-01 (5 hex) + internal serial
     * (10 hex) + a Verhoeff check digit over the decimal form (memory ID with letters as their character codes, days
     * padded to 6, serial padded to 12). As published by client libraries; verify against the official specification
     * before sending (ADR-0037).
     */
    fun taxId(memoryId: String, date: BusinessDate, serial: Long): String {
        require(isMemoryId(memoryId) && date.epochDay >= 0 && serial in 0..0xFF_FFFF_FFFFL) { "tax_id_input" }
        val days = date.epochDay
        val hex = memoryId + days.toString(16).padStart(5, '0') + serial.toString(16).padStart(10, '0')
        val decimal = memoryId.map { if (it.isDigit()) it.toString() else it.code.toString() }.joinToString("") +
            days.toString().padStart(6, '0') + serial.toString().padStart(12, '0')
        return (hex + Verhoeff.checkDigit(decimal)).uppercase()
    }
}

/** The Verhoeff check digit (dihedral group D5). */
object Verhoeff {
    private val d = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5), intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7), intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3), intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val p = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4), intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7), intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )
    private val inv = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)

    fun checkDigit(number: String): Int {
        var c = 0
        number.reversed().forEachIndexed { i, ch -> c = d[c][p[(i + 1) % 8][ch - '0']] }
        return inv[c]
    }

    fun isValid(number: String): Boolean {
        var c = 0
        number.reversed().forEachIndexed { i, ch -> c = d[c][p[i % 8][ch - '0']] }
        return c == 0
    }
}

/** Sets the seller's tax identity and the VAT rates. Nothing is sent to the tax system. */
@NoDocument
data class SetTaxProfile(override val commandId: GlobalId, val sellerTaxId: String, val memoryId: String, val vatRates: List<VatRate>) : Command {
    override val requiredPermission get() = Permission.TAX_SETTINGS
    override val scope: Scope get() = Scope.Organization
    override fun fingerprint() = "$sellerTaxId|$memoryId|" + vatRates.joinToString(";") { "${it.from.epochDay}:${it.bp}" }
}

/** Sets (or clears, with a blank [productId]) a menu item's tax product ID. */
@NoDocument
data class SetItemTaxCode(override val commandId: GlobalId, val menuItemId: GlobalId, val productId: String, val unitCode: String) : Command {
    override val requiredPermission get() = Permission.TAX_SETTINGS
    override val scope: Scope get() = Scope.Organization
    override val sharedCatalog get() = true
    override fun fingerprint() = "$menuItemId|$productId|$unitCode"
}

/** A customer's tax identity (for invoices with the buyer's details). */
@NoDocument
data class SetCustomerTaxInfo(override val commandId: GlobalId, override val scope: Scope.Branch, val customerId: GlobalId, val taxId: String, val postalCode: String) : Command {
    override val requiredPermission get() = Permission.CUSTOMER_MANAGE
    override fun fingerprint() = "$scope|$customerId|$taxId|$postalCode"
}

class TaxOperations(
    private val bus: CommandBus,
    private val tax: TaxStore,
    private val customers: CustomerStore,
    private val menuItemExists: (GlobalId) -> Boolean,
) {
    fun setProfile(c: SetTaxProfile): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val seller = digits(cmd.sellerTaxId); val memory = cmd.memoryId.trim().uppercase()
        ensure(TaxIds.isTaxpayerId(seller)) { DomainError.InvalidInput("sellerTaxId", "شناسه‌ی فروشنده معتبر نیست (کد ملی ۱۰ رقمی، شناسه‌ی ملی ۱۱ رقمی یا کد اقتصادی ۱۴ رقمی).") }
        ensure(memory.isEmpty() || TaxIds.isMemoryId(memory)) { DomainError.InvalidInput("memoryId", "شناسه‌ی حافظه‌ی مالیاتی ۶ حرف یا رقم انگلیسی است.") }
        ensure(cmd.vatRates.isNotEmpty() && cmd.vatRates.all { it.bp in 0..3_000 } && cmd.vatRates.map { it.from }.distinct().size == cmd.vatRates.size) {
            DomainError.InvalidInput("vat", "نرخ مالیات بر ارزش افزوده بین ۰ و ۳۰٪ و هر تاریخ یک‌بار.")
        }
        tax.saveProfile(TaxProfile(seller, memory, cmd.vatRates.sortedBy { it.from }))
        ctx.audit(AuditDraft("TAX_PROFILE_SET", "TAX_PROFILE", "seller", "memory=${memory.isNotEmpty()};vat=" + cmd.vatRates.joinToString(",") { "${it.from.epochDay}:${it.bp}" }))
        GlobalId.new()
    }

    fun setItemCode(c: SetItemTaxCode): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        ensure(menuItemExists(cmd.menuItemId)) { DomainError.NotFound("MENU_ITEM") }
        val product = digits(cmd.productId)
        ensure(product.isEmpty() || TaxIds.isProductId(product)) { DomainError.InvalidInput("productId", "شناسه‌ی کالا/خدمت ۱۳ رقم است.") }
        val unit = digits(cmd.unitCode)
        ensure(unit.isEmpty() || unit.matches(Regex("\\d{3,6}"))) { DomainError.InvalidInput("unitCode", "کد واحد اندازه‌گیری ۳ تا ۶ رقم است.") }
        tax.saveCode(ItemTaxCode(cmd.menuItemId, product, unit))
        ctx.audit(AuditDraft("ITEM_TAX_CODE_SET", "MENU_ITEM", cmd.menuItemId.value, "$product|$unit"))
        cmd.menuItemId
    }

    fun setCustomerTaxInfo(c: SetCustomerTaxInfo): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val customer = customers.byId(cmd.customerId) ?: throw DomainException(DomainError.NotFound("CUSTOMER"))
        val id = digits(cmd.taxId); val postal = digits(cmd.postalCode)
        ensure(id.isEmpty() || TaxIds.isTaxpayerId(id)) { DomainError.InvalidInput("taxId", "کد ملی، شناسه‌ی ملی یا کد اقتصادی معتبر نیست.") }
        ensure(id.isEmpty() || customer.type != CustomerType.PERSON || TaxIds.isNationalId(id) || id.length == 14) { DomainError.InvalidInput("taxId", "برای شخص حقیقی کد ملی ۱۰ رقمی وارد کنید.") }
        ensure(postal.isEmpty() || postal.matches(Regex("\\d{10}"))) { DomainError.InvalidInput("postalCode", "کد پستی ۱۰ رقم است.") }
        customers.save(customer.copy(taxId = id, postalCode = postal))
        ctx.audit(AuditDraft("CUSTOMER_TAX_INFO", "CUSTOMER", customer.id.value, "id=${id.isNotEmpty()};postal=${postal.isNotEmpty()}"))
        customer.id
    }

    /** Persian/Arabic digits to Latin, spaces and dashes removed. */
    private fun digits(text: String): String = text.trim().map { c ->
        when (c) { in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); else -> c }
    }.filter { it.isLetterOrDigit() }.joinToString("")
}
