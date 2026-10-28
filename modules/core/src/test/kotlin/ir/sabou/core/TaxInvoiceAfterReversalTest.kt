package ir.sabou.core

// Regression for SABOU-AUDIT-005 (independent audit 1405/08/06): a sale with a tax invoice in force is not reversed.
// Question: once an electronic (Moadian) invoice is issued for a sale, can the sale still be reversed — and does the
// invoice then stay "ready to send" (or "accepted") with nothing that cancels it?

import ir.sabou.inventory.DefineMenuItem
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.sales.CustomerType
import ir.sabou.sales.IssueTaxInvoice
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.RecordTaxInvoiceResult
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.ReverseDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.SetCustomerTaxInfo
import ir.sabou.sales.SetItemTaxCode
import ir.sabou.sales.SetTaxProfile
import ir.sabou.sales.Settlement
import ir.sabou.sales.TaxInvoiceStatus
import ir.sabou.sales.VatRate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TaxInvoiceAfterReversalTest {
    private val today = Fa.fromJalali(1405, 8, 6)
    private val now = today.epochDay * 86_400_000L + 9 * 3_600_000L
    private fun id() = GlobalId.new()

    @Test fun aSaleWithALiveTaxInvoiceIsNotReversed() {
        val dir = Files.createTempDirectory("probe-tax")
        val db = DevDatabases.open(dir.resolve("t.db"))
        val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now }, emptyList(), null, datePolicy = SabouCore.restaurantDatePolicy())
        core.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        core.taxOps.setProfile(SetTaxProfile(id(), "0012345679", "A1B2C3", listOf(VatRate(BusinessDate(19_000), 1_000))))
        val dish = core.inventory.defineMenuItem(DefineMenuItem(id(), "پرس کترینگ")).resultId
        core.taxOps.setItemCode(SetItemTaxCode(id(), dish, "1234567890123", "1627"))
        val customer = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت نمونه", CustomerType.PERSON, "021", Money.of(1_000_000_000))).resultId
        core.taxOps.setCustomerTaxInfo(SetCustomerTaxInfo(id(), branch, customer, "0012345679", ""))

        fun creditSale(): GlobalId {
            val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, today, kitchen, listOf(SaleLineInput(dish, Quantity.units(10), Money.of(1_000_000))),
                Money.ZERO, Money.ZERO, Money.of(1_000_000), listOf(Settlement.Credit(customer, Money.of(11_000_000), today.plusDays(30))))).resultId
            core.salesOps.post(PostDailySale(id(), branch, s)); return s
        }
        // Case 1: invoice issued (READY), sale reversed.
        val s1 = creditSale()
        val inv1 = core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s1, now)).resultId
        val r1 = runCatching { core.salesOps.reverse(ReverseDailySale(id(), branch, s1, today, "لغو سفارش")) }.fold({ "ACCEPTED" }, { "REFUSED ${(it as? DomainException)?.error?.code}" })
        val row1 = core.moadian.invoices().single { it.invoice.id == inv1 }.invoice
        println("case 1: reverse sale with READY tax invoice -> $r1 ; sale=${core.sales.sale(s1)!!.status} ; invoice status=${row1.status} (send() selects status READY)")

        // Case 2: invoice accepted by the tax system, sale reversed.
        val s2 = creditSale()
        val inv2 = core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s2, now)).resultId
        core.taxInvoices.record(RecordTaxInvoiceResult(id(), branch, inv2, TaxInvoiceStatus.ACCEPTED, "REF-1", inv2.value, emptyList(), now))
        val r2 = runCatching { core.salesOps.reverse(ReverseDailySale(id(), branch, s2, today, "لغو سفارش")) }.fold({ "ACCEPTED" }, { "REFUSED ${(it as? DomainException)?.error?.code}" })
        val row2 = core.moadian.invoices().single { it.invoice.id == inv2 }.invoice
        println("case 2: reverse sale with ACCEPTED tax invoice -> $r2 ; sale=${core.sales.sale(s2)!!.status} ; invoice status=${row2.status} ; cancellations issued=${core.moadian.invoices().count { it.invoice.refTaxId == row2.taxId }}")
        println("VAT payable (2103) in books after both reversals = ${-core.ledger.balance(ir.sabou.ledger.StandardAccounts.SALES_TAX_PAYABLE, branch).rial}")
        assertEquals("REFUSED INVALID_STATE:DAILY_SALE:HAS_TAX_INVOICE", r1)
        assertEquals("REFUSED INVALID_STATE:DAILY_SALE:HAS_TAX_INVOICE", r2)
        // The way through: a never-sent invoice is withdrawn, then the sale reverses; an accepted one is cancelled first.
        assertEquals("INVALID_STATE:TAX_INVOICE:ACCEPTED", runCatching { core.taxInvoices.withdraw(ir.sabou.sales.WithdrawTaxInvoice(id(), branch, inv2, "پس گرفتن")) }
            .exceptionOrNull().let { (it as DomainException).error.code })
        core.taxInvoices.withdraw(ir.sabou.sales.WithdrawTaxInvoice(id(), branch, inv1, "ارسال نشده بود"))
        assertEquals(TaxInvoiceStatus.WITHDRAWN, core.moadian.invoices().single { it.invoice.id == inv1 }.invoice.status)
        core.salesOps.reverse(ReverseDailySale(id(), branch, s1, today, "لغو سفارش"))
        assertEquals(ir.sabou.sales.SaleStatus.REVERSED, core.sales.sale(s1)!!.status)
        db.close(); dir.toFile().deleteRecursively()
    }
}
