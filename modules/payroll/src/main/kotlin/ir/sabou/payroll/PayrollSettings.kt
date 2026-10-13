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
) : java.io.Serializable {
    fun validate() {
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
    override val requiredPermission = Permission.PAYROLL_APPROVE
    override val scope: Scope = Scope.Organization
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
