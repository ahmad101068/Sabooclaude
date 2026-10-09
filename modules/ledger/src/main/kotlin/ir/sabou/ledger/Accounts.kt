package ir.sabou.ledger

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.platform.ModuleId

@JvmInline
value class AccountCode private constructor(val value: String) {
    override fun toString() = value

    companion object {
        fun of(raw: String): AccountCode {
            val v = raw.trim()
            if (!v.matches(Regex("[1-9][0-9]{3}"))) throw DomainException(DomainError.InvalidInput("account", "کد حساب باید چهار رقمی باشد."))
            return AccountCode(v)
        }
    }
}

enum class AccountType(val debitNormal: Boolean) {
    ASSET(true), LIABILITY(false), EQUITY(false), REVENUE(false), EXPENSE(true)
}

/**
 * [postingModules] empty = open account (any module, including manual journals).
 * Non-empty = control account: only lines contributed by those modules are accepted (AUD-010).
 */
data class Account(
    val code: AccountCode,
    val name: String,
    val type: AccountType,
    val postingModules: Set<ModuleId>,
    val isActive: Boolean = true,
    val isSystem: Boolean = true,
) {
    val isControl: Boolean get() = postingModules.isNotEmpty()
}

object StandardAccounts {
    val CASH = AccountCode.of("1101")
    val BANK = AccountCode.of("1102")
    val PETTY_CASH = AccountCode.of("1103")
    val CARD_CLEARING = AccountCode.of("1104")
    val SALES_CLEARING = AccountCode.of("1190")
    val RECEIVABLE = AccountCode.of("1201")
    val INVENTORY = AccountCode.of("1301")
    /** Supplier invoice lines whose item is not known yet; cleared when someone assigns them. */
    val PURCHASES_PENDING_REVIEW = AccountCode.of("1302")
    val EMPLOYEE_ADVANCE = AccountCode.of("1401")
    val INTER_BRANCH = AccountCode.of("1901")
    val PAYABLE = AccountCode.of("2101")
    val PAYROLL_PAYABLE = AccountCode.of("2102")
    val SALES_TAX_PAYABLE = AccountCode.of("2103")
    val INSURANCE_PAYABLE = AccountCode.of("2104")
    val GOODS_RECEIVED_NOT_INVOICED = AccountCode.of("2105")
    val PAYROLL_TAX_PAYABLE = AccountCode.of("2106")
    val CAPITAL = AccountCode.of("3101")
    val RETAINED_EARNINGS = AccountCode.of("3201")
    val FOOD_SALES = AccountCode.of("4101")
    val OTHER_INCOME = AccountCode.of("4102")
    val SERVICE_INCOME = AccountCode.of("4103")
    val COGS = AccountCode.of("5101")
    val SALARIES = AccountCode.of("6101")
    val RENT = AccountCode.of("6102")
    val UTILITIES = AccountCode.of("6103")
    val WASTE = AccountCode.of("6104")
    val OTHER_EXPENSE = AccountCode.of("6105")
    val INVENTORY_VARIANCE = AccountCode.of("6106")
    val EMPLOYER_INSURANCE = AccountCode.of("6107")
    val CASH_OVER_SHORT = AccountCode.of("6108")
    /** Food given away: complimentary dishes, staff meals, donations (part of food cost, not waste). */
    val COMPS = AccountCode.of("6109")

    fun chart(): List<Account> {
        val t = ModuleId.TREASURY; val s = ModuleId.SALES; val p = ModuleId.PURCHASING
        val i = ModuleId.INVENTORY; val y = ModuleId.PAYROLL
        fun a(code: AccountCode, name: String, type: AccountType, vararg owners: ModuleId) = Account(code, name, type, owners.toSet())
        return listOf(
            a(CASH, "صندوق", AccountType.ASSET, t),
            a(BANK, "بانک", AccountType.ASSET, t),
            a(PETTY_CASH, "تنخواه‌گردان", AccountType.ASSET, t),
            a(CARD_CLEARING, "وجوه کارت‌خوان", AccountType.ASSET, t),
            a(SALES_CLEARING, "حساب واسط تسویه فروش", AccountType.ASSET, s),
            a(RECEIVABLE, "حساب‌های دریافتنی", AccountType.ASSET, s),
            a(INVENTORY, "موجودی مواد و کالا", AccountType.ASSET, i),
            a(PURCHASES_PENDING_REVIEW, "خرید در انتظار بررسی", AccountType.ASSET, p),
            a(EMPLOYEE_ADVANCE, "مساعده پرسنل", AccountType.ASSET, y),
            a(INTER_BRANCH, "حساب جاری بین شعب", AccountType.ASSET, t, i, p, s, y),
            a(PAYABLE, "حساب‌های پرداختنی", AccountType.LIABILITY, p),
            a(PAYROLL_PAYABLE, "حقوق پرداختنی", AccountType.LIABILITY, y),
            a(SALES_TAX_PAYABLE, "مالیات و عوارض فروش پرداختنی", AccountType.LIABILITY, s),
            a(INSURANCE_PAYABLE, "بیمه پرداختنی", AccountType.LIABILITY, y),
            a(GOODS_RECEIVED_NOT_INVOICED, "کالای دریافتی فاکتورنشده", AccountType.LIABILITY, p, i),
            a(PAYROLL_TAX_PAYABLE, "مالیات حقوق پرداختنی", AccountType.LIABILITY, y),
            a(CAPITAL, "سرمایه", AccountType.EQUITY),
            a(RETAINED_EARNINGS, "سود انباشته", AccountType.EQUITY),
            a(FOOD_SALES, "فروش غذا و نوشیدنی", AccountType.REVENUE, s),
            a(OTHER_INCOME, "سایر درآمدها", AccountType.REVENUE),
            a(SERVICE_INCOME, "درآمد خدمات", AccountType.REVENUE, s),
            a(COGS, "بهای تمام‌شده فروش", AccountType.EXPENSE, i),
            a(SALARIES, "حقوق و دستمزد", AccountType.EXPENSE, y),
            a(RENT, "اجاره", AccountType.EXPENSE),
            a(UTILITIES, "آب، برق و گاز", AccountType.EXPENSE),
            a(WASTE, "ضایعات", AccountType.EXPENSE, i),
            a(OTHER_EXPENSE, "سایر هزینه‌ها", AccountType.EXPENSE),
            a(INVENTORY_VARIANCE, "مغایرت انبار", AccountType.EXPENSE, i),
            a(EMPLOYER_INSURANCE, "بیمه سهم کارفرما", AccountType.EXPENSE, y),
            a(CASH_OVER_SHORT, "کسر و اضافه صندوق", AccountType.EXPENSE, t),
            a(COMPS, "پذیرایی، غذای پرسنل و اهدایی", AccountType.EXPENSE, i),
        )
    }
}
