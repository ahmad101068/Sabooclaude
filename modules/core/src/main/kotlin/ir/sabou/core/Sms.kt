package ir.sabou.core

import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Mobile

/**
 * SMS (ADR-0046): alerts to chosen people's mobiles and orders to suppliers. The phone sends either through its own
 * SIM card or through an Iranian SMS service with the owner's API key; this file holds what does not need Android —
 * the texts, which alerts go to whom, and the services' HTTP requests and replies.
 */
enum class SmsChannel { SIM, KAVENEGAR, SMS_IR }

/** One person receiving alerts by SMS: computed with [userId]'s own permissions, only the chosen [kinds]. */
data class SmsRecipient(val userId: GlobalId, val mobile: String, val kinds: Set<Alert.Kind>)

data class SmsHttpRequest(val url: String, val headers: Map<String, String>, val contentType: String, val body: String)

/** The text of an order for its supplier and where it goes ([mobile] null when the supplier has no mobile number). */
data class OrderSms(val mobile: String?, val text: String, val autoSend: Boolean)

object SmsText {
    /** Persian text is sent as UCS-2: 70 characters in one message, 67 per part when longer. */
    fun parts(text: String): Int {
        val gsm = text.all { it.code < 128 }
        val single = if (gsm) 160 else 70
        val part = if (gsm) 153 else 67
        return if (text.length <= single) 1 else (text.length + part - 1) / part
    }

    /** Alerts in one message: a single alert in full, several by title, cut to [maxChars] with «و n مورد دیگر». */
    fun alerts(alerts: List<Alert>, maxChars: Int = ALERT_MAX_CHARS): String {
        require(alerts.isNotEmpty())
        if (alerts.size == 1) return cut("سابو · ${alerts[0].title}\n${alerts[0].text}", maxChars)
        return fitLines("سابو · ${Fa.number(alerts.size.toLong())} هشدار تازه", alerts.map { "• ${it.title}" }, maxChars) { n -> "… و ${Fa.number(n.toLong())} مورد دیگر" }
    }

    /** [head] then as many [lines] as fit in [maxChars]; the rest summarized by [more]. */
    fun fitLines(head: String, lines: List<String>, maxChars: Int, more: (Int) -> String): String {
        for (k in lines.size downTo 0) {
            val text = (listOf(head) + lines.take(k) + listOfNotNull(if (k < lines.size) more(lines.size - k) else null)).joinToString("\n")
            if (text.length <= maxChars) return text
        }
        return cut(head, maxChars)
    }

    private fun cut(text: String, maxChars: Int) = if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"

    fun unit(u: StockUnit) = when (u) {
        StockUnit.GRAM -> "گرم"
        StockUnit.KILOGRAM -> "کیلوگرم"
        StockUnit.MILLILITER -> "میلی‌لیتر"
        StockUnit.LITER -> "لیتر"
        StockUnit.PIECE -> "عدد"
        StockUnit.PACK -> "بسته"
    }

    /** Five parts at most for alerts (each part is charged). */
    const val ALERT_MAX_CHARS = 335
    /** Ten parts at most for an order; a longer one says the full list is in the PDF. */
    const val ORDER_MAX_CHARS = 670
}

/** Which alerts go by SMS now: the same once-per-situation rule as the phone's notifications (ADR-0024). */
object SmsAlertPlan {
    data class Result(val text: String?, val sent: Set<String>)

    /**
     * [alerts] are the recipient's current alerts, [sent] the keys already sent. Returns the message for the new ones
     * (null if none) and the keys to remember once it is sent. At night ([quiet]) nothing is sent and nothing is
     * remembered, so the morning's first check sends what is still there.
     */
    fun plan(recipient: SmsRecipient, alerts: List<Alert>, sent: Set<String>, quiet: Boolean): Result {
        val mine = alerts.filter { it.kind in recipient.kinds }
        if (quiet) return Result(null, sent)
        val fresh = mine.filter { it.key !in sent }
        // Keys of resolved situations are dropped, so a recurrence is sent again.
        return Result(if (fresh.isEmpty()) null else SmsText.alerts(fresh), mine.map { it.key }.toSet())
    }

    /** 23:00 to 07:00 is quiet. */
    fun isQuiet(minutesOfDay: Int) = minutesOfDay >= 23 * 60 || minutesOfDay < 7 * 60
}

/** HTTP requests and replies of the Iranian SMS services. */
object SmsGateways {
    /**
     * Kavenegar: POST form to `https://api.kavenegar.com/v1/{API-KEY}/sms/send.json` with receptor (comma separated),
     * message and an optional sender line; reply `{"return":{"status":200,...}}`.
     * SMS.ir: POST JSON to `https://api.sms.ir/v1/send/bulk` with header `X-API-KEY`, body lineNumber, messageText,
     * mobiles; reply `{"status":1,...}`.
     */
    fun request(channel: SmsChannel, apiKey: String, line: String, to: List<String>, text: String): SmsHttpRequest {
        val numbers = to.map { Mobile.normalize(it) ?: throw IllegalArgumentException("شماره همراه نامعتبر: $it") }
        return when (channel) {
            SmsChannel.KAVENEGAR -> SmsHttpRequest(
                "https://api.kavenegar.com/v1/${enc(apiKey.trim())}/sms/send.json",
                mapOf("Accept" to "application/json"),
                "application/x-www-form-urlencoded; charset=UTF-8",
                listOfNotNull("receptor" to numbers.joinToString(","), "message" to text, line.trim().takeIf { it.isNotEmpty() }?.let { "sender" to it })
                    .joinToString("&") { (k, v) -> "$k=${enc(v)}" },
            )
            SmsChannel.SMS_IR -> {
                val lineNumber = Fa.latinDigits(line).trim()
                require(lineNumber.matches(Regex("\\d{4,20}"))) { "شماره خط پیامکی لازم است." }
                SmsHttpRequest(
                    "https://api.sms.ir/v1/send/bulk",
                    mapOf("Accept" to "application/json", "X-API-KEY" to apiKey.trim()),
                    "application/json; charset=UTF-8",
                    "{\"lineNumber\":$lineNumber,\"messageText\":${json(text)},\"mobiles\":[${numbers.joinToString(",") { json(it) }}],\"sendDateTime\":null}",
                )
            }
            SmsChannel.SIM -> throw IllegalArgumentException("SIM is sent by the phone, not over HTTP")
        }
    }

    /** null when the service accepted the message; otherwise what went wrong, for the send log. */
    fun error(channel: SmsChannel, httpStatus: Int, body: String): String? {
        val status = Regex("\"status\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
        val message = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)?.let(::unescape)
        val ok = when (channel) {
            SmsChannel.KAVENEGAR -> httpStatus == 200 && status == 200
            SmsChannel.SMS_IR -> httpStatus == 200 && status == 1
            SmsChannel.SIM -> httpStatus == 200
        }
        if (ok) return null
        return listOfNotNull("کد $httpStatus" + (status?.let { "/$it" } ?: ""), message?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun json(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                    'u' -> if (i + 5 < s.length) { s.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }; i += 4 }
                    else -> out.append(n)
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
