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
}
