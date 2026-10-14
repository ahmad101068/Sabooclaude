package ir.sabou.core

import ir.sabou.inventory.DefineMenuItem
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.sales.CancelTaxInvoice
import ir.sabou.sales.CustomerType
import ir.sabou.sales.IssueTaxInvoice
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.SetCustomerTaxInfo
import ir.sabou.sales.SetItemTaxCode
import ir.sabou.sales.SetTaxProfile
import ir.sabou.sales.Settlement
import ir.sabou.sales.TaxIds
import ir.sabou.sales.TaxInvoiceKind
import ir.sabou.sales.TaxInvoiceMath
import ir.sabou.sales.TaxInvoiceStatus
import ir.sabou.sales.VatRate
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Electronic invoices: issuing, the tax system's protocol against a fake server, and recording its answers (ADR-0047). */
class MoadianSendTest {
    private val dir = Files.createTempDirectory("moadian-send")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("m.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 20)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun taxIdsMatchThePublishedClientLibraries() {
        // Reference values computed with the Python and PHP client libraries' algorithm (memory ID + days hex + serial hex + Verhoeff).
        assertEquals("A1B2C30510400000030399", TaxIds.taxId("A1B2C3", BusinessDate(20_740), 12_345))
        assertEquals("ZX9K4M04D5A00000000017", TaxIds.taxId("ZX9K4M", BusinessDate(19_802), 1))
        assertEquals("12345604C53003ADE68B12", TaxIds.taxId("123456", BusinessDate(19_539), 987_654_321))
    }

    @Test fun discountsAreSpreadExactly() {
        assertEquals(listOf(334L, 333L, 333L), TaxInvoiceMath.spread(1_000, listOf(1, 1, 1)))
        assertEquals(listOf(0L, 0L), TaxInvoiceMath.spread(0, listOf(5, 7)))
        assertEquals(listOf(250L, 750L), TaxInvoiceMath.spread(1_000, listOf(1_000_000, 3_000_000)))
    }

    @Test fun credentialsAreReadFromPemInBothKeyFormats() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8 = pair.private.encoded
        val pem8 = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs8) + "\n-----END PRIVATE KEY-----"
        // PKCS#1 is the octet string inside PKCS#8 (2048-bit: 26 bytes of wrapping).
        val pem1 = "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs8.copyOfRange(26, pkcs8.size)) + "\n-----END RSA PRIVATE KEY-----"
        val a = MoadianCredentials.parse(pem8, TestKeys.certificate)
        val b = MoadianCredentials.parse(pem1, TestKeys.certificate)
        assertTrue(a.privateKey.encoded.contentEquals(pkcs8))
        assertTrue(b.privateKey.encoded.contentEquals(pkcs8))
        assertTrue(a.certificateBase64.startsWith("MII"))
        assertFailsWith<Exception> { MoadianCredentials.parse(pem8, "not a certificate") }
    }

    @Test fun anInvoiceIsIssuedSentInquiredAndCancelled() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val kebab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        val tea = core.inventory.defineMenuItem(DefineMenuItem(id(), "چای")).resultId
        val company = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آرین", CustomerType.COMPANY, "021", rial(100_000_000))).resultId
        // 3 kebabs at 2,000,000 and 2 teas at 500,000; 700,000 discount; VAT 10 %.
        fun sale(): GlobalId {
            val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen,
                listOf(SaleLineInput(kebab, Quantity.units(3), rial(2_000_000)), SaleLineInput(tea, Quantity.units(2), rial(500_000))),
                rial(700_000), Money.ZERO, rial(630_000),
                listOf(Settlement.Liquid(cash, rial(930_000)), Settlement.Credit(company, rial(6_000_000), day.plusDays(30))), discountReason = "قرارداد")).resultId
            core.salesOps.post(PostDailySale(id(), branch, s))
            return s
        }
        val s = sale()
        // Nothing can be issued until the seller, the buyer and the dishes have their tax identities.
        assertEquals("INVALID_STATE:TAX_PROFILE:MISSING_MEMORY_ID", code { core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s, 1_790_000_000_000L)) })
        core.taxOps.setProfile(SetTaxProfile(id(), "0012345679", "A1B2C3", listOf(VatRate(JalaliCalendar.date(1404, 1, 1), 1_000))))
        assertEquals("INVALID_STATE:CUSTOMER:NO_TAX_ID", code { core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s, 1_790_000_000_000L)) })
        core.taxOps.setCustomerTaxInfo(SetCustomerTaxInfo(id(), branch, company, "10101614561", "1234567890"))
        assertEquals("INVALID_STATE:MENU_ITEM:NO_PRODUCT_ID:کباب", code { core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s, 1_790_000_000_000L)) })
        core.taxOps.setItemCode(SetItemTaxCode(id(), kebab, "2720000123456", "1627"))
        core.taxOps.setItemCode(SetItemTaxCode(id(), tea, "2720000654321", "1627"))
        assertEquals(1, core.moadian.candidates().size)

        val invoiceId = core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s, 1_790_000_000_000L)).resultId
        assertEquals("INVALID_STATE:TAX_INVOICE:ALREADY_ISSUED", code { core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s, 1_790_000_000_000L)) })
        assertTrue(core.moadian.candidates().isEmpty())
        val inv = core.moadian.invoices().single().invoice
        assertEquals(TaxIds.taxId("A1B2C3", day, 1), inv.taxId)
        // Discount spread 600,000 / 100,000 by gross; VAT 10 % of each net line.
        assertEquals(listOf(600_000L, 100_000L), inv.lines.map { it.discount.rial })
        assertEquals(listOf(540_000L, 90_000L), inv.lines.map { it.vat.rial })
        assertEquals(6_930_000L, inv.total.rial)
        assertEquals(3, inv.settlementMethod)
        assertEquals(930_000L, inv.cash.rial); assertEquals(6_000_000L, inv.credit.rial)
        assertEquals(2, inv.buyerType)

        val json = MiniJson.parse(core.moadian.payload(invoiceId)) as Map<*, *>
        val header = json["header"] as Map<*, *>
        assertEquals(inv.taxId, header["taxid"]); assertEquals(1L, header["inty"]); assertEquals(1L, header["ins"])
        assertEquals("0012345679", header["tins"]); assertEquals("10101614561", header["tinb"]); assertEquals(6_930_000L, header["tbill"])
        assertEquals("0000000001", header["inno"])
        val body = json["body"] as List<*>
        assertEquals("2720000123456", (body[0] as Map<*, *>)["sstid"]); assertEquals(3L, (body[0] as Map<*, *>)["am"]); assertEquals(10L, (body[0] as Map<*, *>)["vra"])

        val server = FakeTaxServer()
        val client = MoadianClient("https://tp.example/requestsmanager/api/v2", "A1B2C3", server.clientCredentials, server, now = { 1_790_000_000_000L })
        val sent = core.moadian.send(client)
        assertEquals(1, sent.sent)
        assertEquals(TaxInvoiceStatus.SENT, core.moadian.invoices().single().invoice.status)
        // What reached the server: a valid token, and the invoice signed by us and encrypted for the server.
        assertEquals(inv.taxId, ((server.lastInvoice!!["header"] as Map<*, *>)["taxid"]))
        assertTrue(server.tokensVerified >= 2)

        server.answer = "SUCCESS"
        assertEquals(1, core.moadian.inquire(client).accepted)
        assertEquals(TaxInvoiceStatus.ACCEPTED, core.moadian.invoices().single().invoice.status)

        // Cancelling: a new invoice (ins 3) referring to the original; when accepted, the original is cancelled.
        val cancelId = core.taxInvoices.cancel(CancelTaxInvoice(id(), branch, invoiceId, 1_790_000_100_000L)).resultId
        val cancel = core.moadian.invoices().first { it.invoice.id == cancelId }.invoice
        assertEquals(TaxInvoiceKind.CANCELLATION, cancel.kind); assertEquals(inv.taxId, cancel.refTaxId); assertEquals(2L, cancel.serial)
        assertEquals(3L, ((MiniJson.parse(core.moadian.payload(cancelId)) as Map<*, *>)["header"] as Map<*, *>)["ins"])
        core.moadian.send(client); core.moadian.inquire(client)
        assertEquals(TaxInvoiceStatus.CANCELLED, core.moadian.invoices().first { it.invoice.id == invoiceId }.invoice.status)
        // A cancelled sale can be issued again.
        assertEquals(TaxInvoiceStatus.ACCEPTED, core.moadian.invoices().first { it.invoice.id == cancelId }.invoice.status)

        // A refusal is recorded with the server's message, and the sale can be issued again with a new serial.
        val s2 = sale()
        val second = core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s2, 1_790_000_200_000L)).resultId
        server.answer = "FAILED"
        core.moadian.send(client)
        val report = core.moadian.inquire(client)
        assertEquals(1, report.rejected)
        val refused = core.moadian.invoices().first { it.invoice.id == second }.invoice
        assertEquals(TaxInvoiceStatus.REJECTED, refused.status)
        assertTrue(refused.messages.single().contains("شناسه کالا"))
        assertEquals(1, core.moadian.candidates().size)
        val again = core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s2, 1_790_000_300_000L)).resultId
        assertEquals(4L, core.moadian.invoices().first { it.invoice.id == again }.invoice.serial)
    }

    @Test fun aSaleWhoseVatIsNotTheLegalRateIsRefused() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val kebab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        val company = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آرین", CustomerType.COMPANY, "021", rial(100_000_000))).resultId
        core.taxOps.setProfile(SetTaxProfile(id(), "0012345679", "A1B2C3", listOf(VatRate(JalaliCalendar.date(1404, 1, 1), 1_000))))
        core.taxOps.setCustomerTaxInfo(SetCustomerTaxInfo(id(), branch, company, "10101614561", ""))
        core.taxOps.setItemCode(SetItemTaxCode(id(), kebab, "2720000123456", "1627"))
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(kebab, Quantity.units(1), rial(2_000_000))),
            Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Credit(company, rial(2_000_000), day.plusDays(30))))).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
        assertEquals("INVALID_STATE:SALE:VAT_MISMATCH:200000", code { core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s, 1_790_000_000_000L)) })
        assertTrue(Messages.of(assertFailsWith<DomainException> { core.taxInvoices.issue(IssueTaxInvoice(id(), branch, s, 1_790_000_000_000L)) }.error).contains("جور نیست"))
    }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code

    /** The tax system as the client libraries describe it: nonce, server key, encrypted signed invoices, inquiry. */
    private class FakeTaxServer : MoadianClient.Transport {
        private val serverKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val clientKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val clientCredentials = MoadianCredentials(clientKeys.private, "MIIBfake")
        var answer = "PENDING"
        var lastInvoice: Map<*, *>? = null
        var tokensVerified = 0
        private val received = mutableMapOf<String, String>()   // reference → uid
        private var nonces = 0

        override fun exchange(method: String, url: String, headers: Map<String, String>, body: String?): MoadianClient.Response {
            val path = url.substringAfter("/api/v2/").substringBefore('?')
            if (path != "nonce") verifyToken(headers["Authorization"] ?: return MoadianClient.Response(401, """{"errors":[{"code":"401","message":"no token"}]}"""))
            return when (path) {
                "nonce" -> MoadianClient.Response(200, """{"nonce":"n${++nonces}","expDate":1}""")
                "server-information" -> MoadianClient.Response(200,
                    """{"serverTime":1,"publicKeys":[{"key":"${Base64.getEncoder().encodeToString(serverKeys.public.encoded)}","id":"kid-1","algorithm":"RSA","purpose":1}]}""")
                "invoice" -> {
                    val packets = MiniJson.parse(body!!) as List<*>
                    val results = packets.map { p ->
                        val packet = p as Map<*, *>
                        val uid = (packet["header"] as Map<*, *>)["requestTraceId"] as String
                        assertEquals("A1B2C3", (packet["header"] as Map<*, *>)["fiscalId"])
                        lastInvoice = MiniJson.parse(verify(decrypt(packet["payload"] as String))) as Map<*, *>
                        val ref = "ref-${received.size + 1}"
                        received[ref] = uid
                        """{"uid":"$uid","referenceNumber":"$ref","errorCode":null,"errorDetail":null}"""
                    }
                    MoadianClient.Response(200, """{"timestamp":1,"result":[${results.joinToString(",")}]}""")
                }
                "inquiry-by-reference-id" -> {
                    val refs = java.net.URLDecoder.decode(url.substringAfter("referenceIds="), "UTF-8").split(",")
                    val items = refs.map { ref ->
                        val data = if (answer == "FAILED") """{"error":[{"code":"0101","message":"شناسه کالا معتبر نیست"}],"warning":[]}""" else """{"error":[],"warning":[]}"""
                        """{"referenceNumber":"$ref","uid":"${received[ref]}","status":"$answer","data":$data}"""
                    }
                    MoadianClient.Response(200, "[" + items.joinToString(",") + "]")
                }
                else -> MoadianClient.Response(404, """{"errors":[{"code":"404","message":"no such path"}]}""")
            }
        }

        private fun verifyToken(auth: String) {
            val payload = MiniJson.parse(verify(auth.removePrefix("Bearer "))) as Map<*, *>
            assertEquals("A1B2C3", payload["clientId"])
            assertTrue((payload["nonce"] as String).startsWith("n"))
            tokensVerified++
        }

        /** Checks the JWS (RS256 with the client's key, sigT critical) and returns its payload. */
        private fun verify(jws: String): String {
            val (h, p, s) = jws.split(".")
            val header = MiniJson.parse(String(Base64.getUrlDecoder().decode(h), Charsets.UTF_8)) as Map<*, *>
            assertEquals("RS256", header["alg"]); assertEquals(listOf("sigT"), header["crit"]); assertEquals(listOf("MIIBfake"), header["x5c"])
            assertTrue((header["sigT"] as String).endsWith("Z"))
            val ok = Signature.getInstance("SHA256withRSA").apply { initVerify(clientKeys.public); update("$h.$p".toByteArray(Charsets.US_ASCII)) }.verify(Base64.getUrlDecoder().decode(s))
            assertTrue(ok, "signature")
            return String(Base64.getUrlDecoder().decode(p), Charsets.UTF_8)
        }

        /** Opens the JWE (RSA-OAEP-256 + A256GCM, header as additional data). */
        private fun decrypt(jwe: String): String {
            val parts = jwe.split(".")
            assertEquals(5, parts.size)
            val header = MiniJson.parse(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)) as Map<*, *>
            assertEquals("RSA-OAEP-256", header["alg"]); assertEquals("A256GCM", header["enc"]); assertEquals("kid-1", header["kid"])
            val d = Base64.getUrlDecoder()
            val aes = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(Cipher.DECRYPT_MODE, serverKeys.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            }.doFinal(d.decode(parts[1]))
            val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, d.decode(parts[2])))
                updateAAD(parts[0].toByteArray(Charsets.US_ASCII))
            }
            return String(gcm.doFinal(d.decode(parts[3]) + d.decode(parts[4])), Charsets.UTF_8)
        }
    }
}
