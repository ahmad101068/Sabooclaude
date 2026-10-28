package ir.sabou.payroll

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission

/** How overtime is paid. */
enum class OvertimeMode {
    /** Monthly salary ÷ the standard month of the statutory policy × its overtime multiplier (۱۴۰٪ by law). */
    STATUTORY,
    /** A fixed amount per hour, the same for everyone. */
    FIXED_HOURLY,
    /** Not paid (overtime is still recorded). */
    NONE,
}

/**
 * The restaurant's own payroll choices (not the yearly legal figures, which are [StatutoryPolicy]). Every option
 * starts in the conservative state; the owner turns on what the restaurant uses.
 */
data class PayrollSettings(
    /** Take a shift's planned break off the clocked time (when the person did not clock out for it). Off by default. */
    val deductPlannedBreaks: Boolean = false,
    val overtimeMode: OvertimeMode = OvertimeMode.STATUTORY,
    /** Per hour, for [OvertimeMode.FIXED_HOURLY]. */
    val overtimeHourlyRate: Money = Money.ZERO,
    /** Daily overtime shorter than this is not counted (a few minutes after the shift). */
    val overtimeMinimumMinutes: Int = 0,
    /** Lateness within this many minutes is not reported. */
    val lateGraceMinutes: Int = 10,
    /** The time clock takes a photo with every punch (against punching for someone else). Off by default. */
    val clockPhotoRequired: Boolean = false,

    // Premiums on top of the hourly rate (basis points; 0 = not paid). By law: night 35 %, Fridays and holidays 40 %.
    val nightPremiumBp: Int = 0,
    val nightStartMinute: Int = 22 * 60,
    val nightEndMinute: Int = 6 * 60,
    val holidayPremiumBp: Int = 0,

    // Leave
    val leaveDaysPerYear: Int = 26,
    /** One day of leave, in minutes (44 hours over 6 days = 7:20). */
    val workdayMinutes: Int = 440,
    /** Unused annual leave carried into the next year, at most. */
    val leaveCarryDays: Int = 9,
    /** Unused annual leave is paid in the last month of work. */
    val leavePayoutOnExit: Boolean = true,

    // Bonuses: what a new bonus line is by default.
    val bonusInsurable: Boolean = true,
    val bonusTaxable: Boolean = true,

    // Eidi (عیدی و پاداش سالانه): [eidiDays] days of salary for a full year, paid in [eidiMonth] (or on leaving).
    val eidiEnabled: Boolean = false,
    val eidiDays: Int = 60,
    /** The legal ceiling (three times the minimum wage, entered by the owner each year); zero = none. */
    val eidiCap: Money = Money.ZERO,
    val eidiMonth: Int = 12,
    /** The part of eidi free of income tax (entered each year); the rest is taxable. */
    val eidiTaxExempt: Money = Money.ZERO,

    // Severance (سنوات): [sanavatDays] days of the last salary per year of service, paid on leaving (or yearly).
    val sanavatEnabled: Boolean = false,
    val sanavatDays: Int = 30,
    val sanavatYearly: Boolean = false,
) : java.io.Serializable {
    fun validate() {
        ensure(listOf(nightPremiumBp, holidayPremiumBp).all { it in 0..20_000 } && nightStartMinute in 0 until 1_440 && nightEndMinute in 0 until 1_440) {
            DomainError.InvalidInput("premium", "درصد یا ساعت معتبر نیست.")
        }
        ensure(leaveDaysPerYear in 0..60 && workdayMinutes in 60..720 && leaveCarryDays in 0..60) { DomainError.InvalidInput("leave", "مقدارهای مرخصی معتبر نیستند.") }
        ensure(eidiDays in 0..365 && eidiMonth in 1..12 && sanavatDays in 0..365) { DomainError.InvalidInput("eidi", "مقدارهای عیدی یا سنوات معتبر نیستند.") }
        ensure(overtimeMode != OvertimeMode.FIXED_HOURLY || overtimeHourlyRate.rial > 0) {
            DomainError.InvalidInput("overtimeHourlyRate", "مبلغ هر ساعت اضافه‌کار را وارد کنید.")
        }
        ensure(overtimeHourlyRate.rial >= 0 && overtimeMinimumMinutes in 0..240 && lateGraceMinutes in 0..60) {
            DomainError.InvalidInput("settings", "مقدارها معتبر نیستند.")
        }
    }
}

interface PayrollSettingsStore {
    fun get(): PayrollSettings
    fun save(settings: PayrollSettings)
}

@NoDocument
data class SavePayrollSettings(override val commandId: GlobalId, val settings: PayrollSettings) : Command {
    override val requiredPermission get() = Permission.PAYROLL_APPROVE
    override val scope: Scope get() = Scope.Organization
    override fun fingerprint() = settings.toString()
}

class PayrollSettingsAdministration(private val bus: CommandBus, private val store: PayrollSettingsStore) {
    fun current(): PayrollSettings = store.get()

    fun save(c: SavePayrollSettings): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        cmd.settings.validate()
        val before = store.get()
        store.save(cmd.settings)
        ctx.audit(AuditDraft("PAYROLL_SETTINGS", "PAYROLL_SETTINGS", "ORG", "$before -> ${cmd.settings}"))
        GlobalId.new()
    }
}

class InMemoryPayrollSettingsStore(private var settings: PayrollSettings = PayrollSettings()) : PayrollSettingsStore {
    override fun get() = settings
    override fun save(settings: PayrollSettings) { this.settings = settings }
}
