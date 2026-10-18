package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.payroll.AddStaffDocument
import ir.sabou.payroll.CorrectStaffDocument
import ir.sabou.payroll.RemoveStaffDocument
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.payroll.StaffDocument
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Health cards and contracts: missing, expiring, renewed (ADR-0039). */
class StaffDocumentsTest {
    private val dir = Files.createTempDirectory("staffdocs")
    private val db = DevDatabases.open(dir.resolve("s.db"))
    private val day = JalaliCalendar.date(1405, 7, 20)
    private var now = day.epochDay * 86_400_000L
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now })
    private fun id() = GlobalId.new()
    private val photo = AttachmentInput("card.jpg", "image/jpeg", ByteArray(1_000) { it.toByte() })

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun missingExpiringAndRenewed() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val cook = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "رضا", "0012345679", Money.of(150_000_000), JalaliCalendar.date(1405, 1, 1))).resultId
        assertEquals(setOf(StaffDocument.Kind.HEALTH_CARD, StaffDocument.Kind.CONTRACT), core.labor.documentIssues(branch, day).filter { it.issue.missing }.map { it.issue.kind }.toSet())

        assertEquals("INVALID_INPUT:expiresOn", assertFailsWith<DomainException> {
            core.staffDocuments.add(AddStaffDocument(id(), branch, cook, StaffDocument.Kind.HEALTH_CARD, "", null, "", listOf(photo)))
        }.error.code)
        core.staffDocuments.add(AddStaffDocument(id(), branch, cook, StaffDocument.Kind.HEALTH_CARD, "", day.plusDays(10), "", listOf(photo)))
        core.staffDocuments.add(AddStaffDocument(id(), branch, cook, StaffDocument.Kind.CONTRACT, "قرارداد ۱۴۰۵", null, "", listOf(photo)))
        val issues = core.labor.documentIssues(branch, day)
        assertEquals(listOf(StaffDocument.Kind.HEALTH_CARD to 10L), issues.map { it.issue.kind to it.issue.daysLeft })
        val owner = core.overview.me().userId
        assertTrue(core.alerts.forUser(owner, day).any { it.kind == Alert.Kind.STAFF_DOCUMENT && "رضا" in it.title })
        assertEquals(1, core.labor.documents(cook).first { it.document.kind == StaffDocument.Kind.HEALTH_CARD }.files.size)

        // Renewed: the new card is the one in force; the old stays in the history.
        now += 60_000
        core.staffDocuments.add(AddStaffDocument(id(), branch, cook, StaffDocument.Kind.HEALTH_CARD, "", day.plusDays(375), "", listOf(photo)))
        assertEquals(emptyList(), core.labor.documentIssues(branch, day))
        assertEquals(3, core.labor.documents(cook).size)
    }

    @Test fun aWrongDocumentIsCorrectedOrRemovedAndTheEarlierVersionStays() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val cook = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "رضا", "0012345679", Money.of(150_000_000), JalaliCalendar.date(1405, 1, 1))).resultId
        val card = core.staffDocuments.add(AddStaffDocument(id(), branch, cook, StaffDocument.Kind.HEALTH_CARD, "", day.plusDays(5), "", listOf(photo))).resultId
        // The expiry was typed wrong: corrected, with the same photo kept and one more added.
        val fixed = core.staffDocuments.correct(CorrectStaffDocument(id(), branch, card, StaffDocument.Kind.HEALTH_CARD, "", day.plusDays(200), "", listOf(photo))).resultId
        assertEquals(emptyList(), core.labor.documentIssues(branch, day).filter { it.issue.kind == StaffDocument.Kind.HEALTH_CARD })
        val view = core.labor.documents(cook).single()
        assertEquals(fixed, view.document.id); assertEquals(2, view.files.size); assertEquals(1, view.corrections)
        // Only the latest version can be changed.
        assertEquals("INVALID_STATE:STAFF_DOCUMENT:ALREADY_CHANGED", assertFailsWith<DomainException> {
            core.staffDocuments.remove(RemoveStaffDocument(id(), branch, card, "اشتباه"))
        }.error.code)
        // Entered for the wrong person: removed with a reason; the card is missing again.
        core.staffDocuments.remove(RemoveStaffDocument(id(), branch, fixed, "برای کارمند دیگری بود"))
        assertEquals(emptyList(), core.labor.documents(cook))
        assertTrue(core.labor.documentIssues(branch, day).any { it.issue.kind == StaffDocument.Kind.HEALTH_CARD && it.issue.missing })
    }
}
