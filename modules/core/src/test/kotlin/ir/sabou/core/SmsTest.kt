package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Mobile
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.CreatePurchaseOrder
import ir.sabou.purchasing.OrderLine
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.UpdateSupplier
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SMS texts, alert planning and the services' requests (ADR-0046). */
class SmsTest {
    private val dir = Files.createTempDirectory("sms")
    private val db = DevDatabases.open(dir.resolve("s.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 18)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun mobileNumbersAreNormalized() {
        assertEquals("09121234567", Mobile.normalize("۰۹۱۲ ۱۲۳ ۴۵۶۷"))
        assertEquals("09121234567", Mobile.normalize("+98 912 123 4567"))
        assertEquals("09121234567", Mobile.normalize("00989121234567"))
        assertEquals("09121234567", Mobile.normalize("9121234567"))
        assertNull(Mobile.normalize("021-88888888"))
        assertNull(Mobile.normalize("0912123456"))
    }

    @Test fun alertsAreSentOncePerSituationAndHeldAtNight() {
        val me = SmsRecipient(GlobalId.new(), "09121234567", setOf(Alert.Kind.LOW_STOCK, Alert.Kind.CHEQUE_DUE))
        val low = Alert("LOW:1", Alert.Kind.LOW_STOCK, "کمبود «پنیر»", "۲ کیلو مانده")
        val price = Alert("PRICE:1", Alert.Kind.PRICE_CHANGE, "گران‌تر", "…")
        val cheque = Alert("CHEQUE:1:due", Alert.Kind.CHEQUE_DUE, "چک نزدیک سررسید", "…")

        val first = SmsAlertPlan.plan(me, listOf(low, price), emptySet(), quiet = false)
        assertEquals("سابو · کمبود «پنیر»\n۲ کیلو مانده", first.text)           // a kind not chosen is left out
        assertEquals(setOf("LOW:1"), first.sent)
        assertNull(SmsAlertPlan.plan(me, listOf(low, price), first.sent, quiet = false).text)  // once
        // At night nothing goes and nothing is remembered; the morning sends it.
        val night = SmsAlertPlan.plan(me, listOf(low, cheque), first.sent, quiet = true)
        assertNull(night.text); assertEquals(first.sent, night.sent)
        val morning = SmsAlertPlan.plan(me, listOf(low, cheque), night.sent, quiet = false)
        assertTrue(morning.text!!.contains("چک نزدیک سررسید")); assertFalse(morning.text!!.contains("پنیر"))
        // Resolved, then back: sent again.
        val resolved = SmsAlertPlan.plan(me, listOf(cheque), morning.sent, quiet = false)
        assertEquals(setOf("CHEQUE:1:due"), resolved.sent)
        assertTrue(SmsAlertPlan.plan(me, listOf(low, cheque), resolved.sent, quiet = false).text!!.contains("پنیر"))
        assertTrue(SmsAlertPlan.isQuiet(23 * 60)); assertTrue(SmsAlertPlan.isQuiet(6 * 60 + 59)); assertFalse(SmsAlertPlan.isQuiet(7 * 60))
    }

    @Test fun manyAlertsFitInFiveParts() {
        val many = (1..40).map { Alert("K$it", Alert.Kind.LOW_STOCK, "کمبود «کالای شماره‌ی $it با نام بلند»", "…") }
        val text = SmsText.alerts(many)
        assertTrue(text.length <= SmsText.ALERT_MAX_CHARS)
        assertTrue(text.startsWith("سابو · ۴۰ هشدار تازه"))
        assertTrue(text.contains("مورد دیگر"))
        assertEquals(5, SmsText.parts("ب".repeat(335)))
        assertEquals(1, SmsText.parts("ب".repeat(70)))
        assertEquals(2, SmsText.parts("ب".repeat(71)))
        assertEquals(1, SmsText.parts("a".repeat(160)))
    }

    @Test fun gatewayRequestsAndReplies() {
        val k = SmsGateways.request(SmsChannel.KAVENEGAR, " KEY/1 ", "10004346", listOf("۰۹۱۲۱۲۳۴۵۶۷", "+989350000000"), "سلام & خوش")
        assertEquals("https://api.kavenegar.com/v1/KEY%2F1/sms/send.json", k.url)
        assertEquals("receptor=09121234567%2C09350000000&message=" + java.net.URLEncoder.encode("سلام & خوش", "UTF-8") + "&sender=10004346", k.body)
        assertNull(SmsGateways.error(SmsChannel.KAVENEGAR, 200, """{"return":{"status":200,"message":"تایید شد"},"entries":[{"messageid":1,"status":1}]}"""))
        assertEquals("کد 401/401 · کلید نامعتبر", SmsGateways.error(SmsChannel.KAVENEGAR, 401, """{"return":{"status":401,"message":"کلید نامعتبر"},"entries":null}"""))

        val s = SmsGateways.request(SmsChannel.SMS_IR, "abc", "۳۰۰۰۱۲۳۴", listOf("09121234567"), "خط \"یک\"\nدو")
        assertEquals("https://api.sms.ir/v1/send/bulk", s.url)
        assertEquals("abc", s.headers["X-API-KEY"])
        assertEquals("""{"lineNumber":30001234,"messageText":"خط \"یک\"\nدو","mobiles":["09121234567"],"sendDateTime":null}""", s.body)
        assertNull(SmsGateways.error(SmsChannel.SMS_IR, 200, """{"status":1,"message":"موفق","data":{"packId":"x"}}"""))
        assertEquals("کد 200/0 · اعتبار کافی نیست", SmsGateways.error(SmsChannel.SMS_IR, 200, """{"status":0,"message":"اعتبار کافی نیست"}"""))
    }

    @Test fun anOrderBecomesASupplierSmsAndOrderSmsNeedsAMobile() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cheese = core.inventory.createItem(CreateItem(GlobalId.new(), "پنیر", StockUnit.KILOGRAM, Quantity.units(5))).resultId
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(GlobalId.new(), "لبنیات", "021-8888")).resultId
        // A landline cannot receive orders by SMS.
        val refused = runCatching { core.purchasing.updateSupplier(UpdateSupplier(GlobalId.new(), supplier, "لبنیات", "021-8888", true, emptySet(), null, 1, "", orderSms = true)) }
        assertEquals("INVALID_INPUT:phone", (refused.exceptionOrNull() as DomainException).error.code)
        core.purchasing.updateSupplier(UpdateSupplier(GlobalId.new(), supplier, "لبنیات", "0912 123 4567", true, emptySet(), null, 1, "", orderSms = true))
        assertTrue(core.overview.suppliers().single().supplier.orderSms)
        // Leaving it out keeps it.
        core.purchasing.updateSupplier(UpdateSupplier(GlobalId.new(), supplier, "لبنیات", "0912 123 4567", true, emptySet(), null, 1, "تست"))
        assertTrue(core.overview.suppliers().single().supplier.orderSms)

        val order = core.orders.create(CreatePurchaseOrder(GlobalId.new(), branch, supplier, kitchen, day, day.plusDays(1),
            listOf(OrderLine(cheese, Quantity.units(2), Money.of(3_000_000))), "صبح زود")).resultId
        val sms = core.buying.orderSms(order)
        assertEquals("09121234567", sms.mobile)
        assertTrue(sms.autoSend)
        assertTrue(sms.text.startsWith("سفارش خرید شعبه یک"), sms.text)
        assertTrue(sms.text.contains("- پنیر: ۲ کیلوگرم"), sms.text)
        assertTrue(sms.text.contains("صبح زود"))
        assertTrue(sms.text.length <= SmsText.ORDER_MAX_CHARS)
    }
}
