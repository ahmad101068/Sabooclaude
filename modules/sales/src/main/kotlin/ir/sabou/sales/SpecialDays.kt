package ir.sabou.sales

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
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

/** A day the owner knows will differ (ADR-0041): closed, a holiday, an event; [percent] of a normal day's sales. */
data class SpecialDay(val scope: Scope.Branch, val date: BusinessDate, val kind: Kind, val percent: Int, val note: String) {
    enum class Kind { CLOSED, HOLIDAY, EVENT }
}

interface SpecialDayStore {
    fun of(scope: Scope.Branch): List<SpecialDay>
    fun save(day: SpecialDay)
    fun remove(scope: Scope.Branch, date: BusinessDate)
}

/** Marks a day as closed, a holiday or an event, with its expected share of a normal day; [remove] clears it. */
@NoDocument
data class SetSpecialDay(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val date: BusinessDate,
    val kind: SpecialDay.Kind,
    val percent: Int,
    val note: String,
    val remove: Boolean = false,
) : Command {
    override val requiredPermission = Permission.SHIFT_MANAGE
    override fun fingerprint() = "$scope|${date.epochDay}|$kind|$percent|$note|$remove"
}

class SpecialDayOperations(private val bus: CommandBus, private val store: SpecialDayStore) {
    fun set(c: SetSpecialDay): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        if (cmd.remove) store.remove(cmd.scope, cmd.date)
        else {
            ensure(cmd.kind == SpecialDay.Kind.CLOSED || cmd.percent in 1..500) { DomainError.InvalidInput("percent", "درصد فروش نسبت به روز عادی بین ۱ و ۵۰۰ است.") }
            ensure(cmd.note.length <= 60) { DomainError.InvalidInput("note", "توضیح کوتاه بنویسید.") }
            store.save(SpecialDay(cmd.scope, cmd.date, cmd.kind, if (cmd.kind == SpecialDay.Kind.CLOSED) 0 else cmd.percent, cmd.note.trim()))
        }
        ctx.audit(AuditDraft(if (cmd.remove) "SPECIAL_DAY_REMOVE" else "SPECIAL_DAY_SET", "SPECIAL_DAY", "${cmd.date.epochDay}", "${cmd.kind}:${cmd.percent}"))
        GlobalId.new()
    }
}
