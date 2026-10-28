package ir.sabou.core

// Regression for SABOU-AUDIT-006 (independent audit 1405/08/06): the time of a punch is the main phone's clock.
// Question: is the time of a clock punch taken from the clock of the phone that runs the command, or from the request?

import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.payroll.ClockPunch
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.payroll.SetClockPin
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockPunchTimeTest {
    @Test fun aPunchIsRecordedAtTheMainPhonesTimeNotTheRequests() {
        val day = JalaliCalendar.date(1405, 8, 6)
        // The main phone's clock: 14:00 UTC = 17:30 in Tehran, on that day.
        val now = day.epochDay * 86_400_000L + 14 * 3_600_000L
        val dir = Files.createTempDirectory("probe-punch")
        val db = DevDatabases.open(dir.resolve("p.db"))
        val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now }, emptyList(), null, datePolicy = SabouCore.restaurantDatePolicy())
        core.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val ali = core.payroll.registerEmployee(RegisterEmployee(GlobalId.new(), branch, "علی", "0012345679", Money.of(192_000_000), JalaliCalendar.date(1405, 1, 1))).resultId
        core.shifts.setPin(SetClockPin(GlobalId.new(), branch, ali, "2468"))
        // At 17:30 the employee "clocks in" at 06:00 and "out" at 23:59 the same day.
        val r1 = runCatching { core.shifts.punch(ClockPunch(GlobalId.new(), branch, ali, "2468", day, 6 * 60)) }.fold({ "ACCEPTED" }, { "REFUSED " + (it as DomainException).error.code })
        val r2 = runCatching { core.shifts.punch(ClockPunch(GlobalId.new(), branch, ali, "2468", day, 23 * 60 + 59)) }.fold({ "ACCEPTED" }, { "REFUSED " + (it as DomainException).error.code })
        println("clock of the phone running the command: 17:30 Tehran; punch IN at 06:00 -> $r1 ; punch OUT at 23:59 (in the future) -> $r2")
        println("punches recorded: " + core.shiftStore.punches(branch, day, day).map { "${it.kind}@${it.minute / 60}:${"%02d".format(it.minute % 60)}" })
        assertEquals("REFUSED INVALID_INPUT:time", r1)
        assertEquals("REFUSED INVALID_INPUT:time", r2)
        // A phone a minute ahead is accepted, and the punch carries the main phone's 17:30.
        core.shifts.punch(ClockPunch(GlobalId.new(), branch, ali, "2468", day, 17 * 60 + 31))
        assertEquals(listOf(17 * 60 + 30), core.shiftStore.punches(branch, day, day).map { it.minute })
        db.close(); dir.toFile().deleteRecursively()
    }
}
