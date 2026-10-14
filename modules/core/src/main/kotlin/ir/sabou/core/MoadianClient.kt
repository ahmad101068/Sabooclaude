package ir.sabou.core

import ir.sabou.sales.TaxInvoice
import ir.sabou.sales.TaxInvoiceKind
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * The tax system's API (سامانه مودیان, «requestsmanager/api/v2», ADR-0047), as implemented by the published client
 * libraries: every call carries a token — a JWS (RS256, with the taxpayer's certificate in `x5c` and the signing time
 * in `sigT`) over `{nonce, clientId}`; an invoice is signed the same way and then encrypted for the tax system as a
 * JWE (RSA-OAEP-256 + A256GCM) with the server's public key from `server-information`.
 *
 * Nothing here touches the books: [MoadianSender] turns the answers into [ir.sabou.sales.RecordTaxInvoiceResult].
 */
class MoadianClient(
    private val baseUrl: String,
    /** The tax memory ID (شناسه یکتای حافظه مالیاتی), the API's `clientId` / `fiscalId`. */
    private val memoryId: String,
    private val credentials: MoadianCredentials,
    private val transport: Transport,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** One HTTP exchange; [query] is already encoded into the path by the client. */
    fun interface Transport {
        fun exchange(method: String, url: String, headers: Map<String, String>, body: String?): Response
    }

    data class Response(val status: Int, val body: String)

    /** HTTPS through the platform's `HttpURLConnection` (works on the JVM and on Android). */
    object Http : Transport {
        override fun exchange(method: String, url: String, headers: Map<String, String>, body: String?): Response {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                return Response(status, text)
            } finally {
                conn.disconnect()
            }
        }
    }

    data class ServerKey(val id: String, val key: PublicKey)

    /** One invoice's receipt: the tax system's `uid` (ours) and `referenceNumber` (theirs), or why it was refused. */
    data class Receipt(val uid: String, val referenceNumber: String?, val error: String?)

    /** One invoice's processing result. */
    data class Inquiry(val referenceNumber: String, val uid: String?, val status: String, val errors: List<String>, val warnings: List<String>) {
        val accepted: Boolean get() = status == "SUCCESS"
        val rejected: Boolean get() = status == "FAILED"
    }

    class ApiException(message: String) : Exception(message)

    private val base = baseUrl.trimEnd('/') + "/"
    private var serverKey: ServerKey? = null

    fun nonce(): String {
        val r = call("GET", "nonce?timeToLive=30", token = false)
        return (r as? Map<*, *>)?.get("nonce") as? String ?: throw ApiException("nonce_missing")
    }

    fun serverInformation(): ServerKey {
        serverKey?.let { return it }
        val r = call("GET", "server-information") as? Map<*, *> ?: throw ApiException("server_information")
        val k = (r["publicKeys"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: throw ApiException("server_key_missing")
        val der = Base64.getDecoder().decode((k["key"] as String).replace(Regex("\\s"), ""))
        return ServerKey(k["id"].toString(), KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))).also { serverKey = it }
    }

    /** The taxpayer's record at the tax system (name, economic code, status) — a connection test. */
    fun fiscalInformation(): Map<*, *> = call("GET", "fiscal-information?memoryId=${enc(memoryId)}") as? Map<*, *> ?: emptyMap<String, Any>()

    /** Sends invoices (JSON from [MoadianPayload]); one receipt per invoice, in order. */
    fun send(invoices: List<Pair<String, String>>): List<Receipt> {
        require(invoices.isNotEmpty())
        val key = serverInformation()
        val packets = invoices.map { (uid, json) ->
            MiniJson.obj("payload" to MiniJson.Raw(MiniJson.str(encrypt(sign(json), key))),
                "header" to MiniJson.Raw(MiniJson.obj("requestTraceId" to MiniJson.Raw(MiniJson.str(uid)), "fiscalId" to MiniJson.Raw(MiniJson.str(memoryId)))))
        }
        val r = call("POST", "invoice", body = "[" + packets.joinToString(",") + "]") as? Map<*, *> ?: throw ApiException("send_result")
        val results = (r["result"] as? List<*>).orEmpty().map { it as Map<*, *> }
        return invoices.mapIndexed { i, (uid, _) ->
            val res = results.firstOrNull { it["uid"] == uid } ?: results.getOrNull(i)
            Receipt(uid, res?.get("referenceNumber") as? String,
                listOfNotNull(res?.get("errorCode")?.toString(), res?.get("errorDetail")?.toString()).joinToString(" ").ifBlank { null }
                    ?: if (res?.get("referenceNumber") == null) "پاسخی برای این صورتحساب نیامد." else null)
        }
    }

    fun inquire(referenceNumbers: List<String>): List<Inquiry> {
        require(referenceNumbers.isNotEmpty())
        val r = call("GET", "inquiry-by-reference-id?referenceIds=${enc(referenceNumbers.joinToString(","))}")
        val list = (r as? List<*>) ?: ((r as? Map<*, *>)?.get("result") as? List<*>).orEmpty()
        return list.map { it as Map<*, *> }.map { m ->
            val data = m["data"] as? Map<*, *>
            Inquiry(m["referenceNumber"].toString(), m["uid"] as? String, m["status"]?.toString().orEmpty(), messages(data?.get("error")), messages(data?.get("warning")))
        }
    }

    private fun messages(list: Any?): List<String> = (list as? List<*>).orEmpty().map { e ->
        (e as? Map<*, *>)?.let { listOfNotNull(it["code"]?.toString(), it["message"]?.toString()).joinToString(": ") } ?: e.toString()
    }

    /** The bearer token: a JWS over the nonce and our memory ID. */
    fun token(nonce: String): String = sign(MiniJson.obj("nonce" to MiniJson.Raw(MiniJson.str(nonce)), "clientId" to MiniJson.Raw(MiniJson.str(memoryId))))

    /** JWS compact, RS256, protected header `typ`, `alg`, `x5c`, `sigT`, `crit` = [sigT], `cty`. */
    fun sign(payloadJson: String): String {
        val sigT = java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(now()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        val header = MiniJson.obj(
            "typ" to MiniJson.Raw(MiniJson.str("JWT")), "alg" to MiniJson.Raw(MiniJson.str("RS256")),
            "x5c" to MiniJson.Raw("[" + MiniJson.str(credentials.certificateBase64) + "]"),
            "sigT" to MiniJson.Raw(MiniJson.str(sigT)), "crit" to MiniJson.Raw("[\"sigT\"]"), "cty" to MiniJson.Raw(MiniJson.str("text/plain")),
        )
        val input = b64(header.toByteArray(Charsets.UTF_8)) + "." + b64(payloadJson.toByteArray(Charsets.UTF_8))
        val s = Signature.getInstance("SHA256withRSA").apply { initSign(credentials.privateKey); update(input.toByteArray(Charsets.US_ASCII)) }
        return input + "." + b64(s.sign())
    }

    /** JWE compact: RSA-OAEP-256 wraps a fresh AES-256 key; A256GCM encrypts with the header as additional data. */
    fun encrypt(plain: String, key: ServerKey): String {
        val header = b64(MiniJson.obj("alg" to MiniJson.Raw("\"RSA-OAEP-256\""), "enc" to MiniJson.Raw("\"A256GCM\""), "kid" to MiniJson.Raw(MiniJson.str(key.id))).toByteArray(Charsets.UTF_8))
        val aes = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.ENCRYPT_MODE, key.key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }
        val wrapped = rsa.doFinal(aes)
        val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            updateAAD(header.toByteArray(Charsets.US_ASCII))
        }
        val out = gcm.doFinal(plain.toByteArray(Charsets.UTF_8))
        val cipherText = out.copyOfRange(0, out.size - 16)
        val tag = out.copyOfRange(out.size - 16, out.size)
        return listOf(header, b64(wrapped), b64(iv), b64(cipherText), b64(tag)).joinToString(".")
    }

    private fun call(method: String, path: String, body: String? = null, token: Boolean = true): Any? {
        val headers = linkedMapOf("Accept" to "application/json", "Content-Type" to "application/json")
        if (token) headers["Authorization"] = "Bearer " + token(nonce())
        val r = transport.exchange(method, base + path, headers, body)
        val parsed = runCatching { MiniJson.parse(r.body) }.getOrNull()
        if (r.status !in 200..299) {
            val err = ((parsed as? Map<*, *>)?.get("errors") as? List<*>)?.firstOrNull() as? Map<*, *>
            throw ApiException(listOfNotNull("HTTP ${r.status}", err?.get("code")?.toString(), err?.get("message")?.toString()).joinToString(" · "))
        }
        return parsed
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

/** The taxpayer's private key and certificate, read from the PEM text the owner pastes or picks. */
class MoadianCredentials(val privateKey: PrivateKey, val certificateBase64: String) {
    companion object {
        /** [keyPem]: «PRIVATE KEY» (PKCS#8) or «RSA PRIVATE KEY» (PKCS#1); [certificatePem]: the certificate (PEM or bare base64). */
        fun parse(keyPem: String, certificatePem: String): MoadianCredentials {
            val pkcs1 = keyPem.contains("BEGIN RSA PRIVATE KEY")
            val der = Base64.getMimeDecoder().decode(body(keyPem))
            val pkcs8 = if (pkcs1) wrapPkcs1(der) else der
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val cert = body(certificatePem).replace(Regex("\\s"), "")
            // Must be a certificate, not just any text.
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(Base64.getDecoder().decode(cert).inputStream())
            return MoadianCredentials(key, cert)
        }

        private fun body(pem: String) = pem.lines().filter { !it.startsWith("-----") }.joinToString("").trim()

        /** PKCS#1 RSAPrivateKey inside a PKCS#8 PrivateKeyInfo (version 0, rsaEncryption, NULL). */
        private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val octet = byteArrayOf(0x04) + length(pkcs1.size) + pkcs1
            val content = version + algorithm + octet
            return byteArrayOf(0x30) + length(content.size) + content
        }

        private fun length(n: Int): ByteArray = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
    }
}

/** The invoice as the tax system's JSON (header, body, payments). Amounts in rial. */
object MoadianPayload {
    fun invoice(inv: TaxInvoice): String {
        val r = { v: Long -> MiniJson.Raw(v.toString()) }
        val s = { v: String? -> MiniJson.Raw(if (v == null) "null" else MiniJson.str(v)) }
        val buyerIsLegacyEconomicCode = inv.buyerTaxId.length == 14
        val header = MiniJson.obj(
            "taxid" to s(inv.taxId),
            "indatim" to r(inv.issuedAt),
            "indati2m" to r(inv.issuedAt),
            "inty" to r(1),
            "inno" to s(inv.internalNumber),
            "irtaxid" to s(inv.refTaxId),
            "inp" to r(1),
            "ins" to r(if (inv.kind == TaxInvoiceKind.CANCELLATION) 3 else 1),
            "tins" to s(inv.sellerTaxId),
            "tob" to r(inv.buyerType.toLong()),
            "bid" to s(if (buyerIsLegacyEconomicCode) null else inv.buyerTaxId),
            "tinb" to s(inv.buyerTaxId),
            "bpc" to s(inv.buyerPostalCode.ifEmpty { null }),
            "tprdis" to r(inv.gross.rial),
            "tdis" to r(inv.discount.rial),
            "tadis" to r(inv.net.rial),
            "tvam" to r(inv.vat.rial),
            "todam" to r(0),
            "tbill" to r(inv.total.rial),
            "setm" to r(inv.settlementMethod.toLong()),
            "cap" to r(inv.cash.rial),
            "insp" to r(inv.credit.rial),
        )
        val body = inv.lines.joinToString(",", "[", "]") { l ->
            MiniJson.obj(
                "sstid" to s(l.productId),
                "sstt" to s(l.description),
                "am" to MiniJson.Raw(decimal(l.quantity.micros, 6)),
                "mu" to s(l.unitCode),
                "fee" to r(l.unitPrice.rial),
                "prdis" to r(l.gross.rial),
                "dis" to r(l.discount.rial),
                "adis" to r(l.net.rial),
                "vra" to MiniJson.Raw(decimal(l.vatBp, 2)),
                "vam" to r(l.vat.rial),
                "tsstam" to r(l.total.rial),
            )
        }
        return MiniJson.obj("header" to MiniJson.Raw(header), "body" to MiniJson.Raw(body), "payments" to MiniJson.Raw("[]"))
    }

    /** 2_500_000 micros → «2.5»; 900 basis points → «9». */
    fun decimal(value: Long, scale: Int): String =
        java.math.BigDecimal.valueOf(value, scale).stripTrailingZeros().toPlainString()
}

/** Just enough JSON for the tax system's requests and answers (numbers with fractions included). */
object MiniJson {
    class Raw(val json: String)

    fun obj(vararg fields: Pair<String, Raw>): String = fields.joinToString(",", "{", "}") { (k, v) -> str(k) + ":" + v.json }

    fun str(s: String): String = SmsGateways.json(s)

    fun parse(text: String): Any? = P(text).run { val v = value(); ws(); require(i == t.length) { "json_trailing" }; v }

    private class P(val t: String) {
        var i = 0
        fun ws() { while (i < t.length && t[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = t[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (t[i] == '}') { i++; return m }
                    while (true) { ws(); val k = string(); ws(); require(t[i++] == ':'); m[k] = value(); ws(); when (t[i++]) { ',' -> {}; '}' -> return m; else -> error("json_object") } } }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (t[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); when (t[i++]) { ',' -> {}; ']' -> return l; else -> error("json_array") } } }
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) number() else error("json_unexpected")
            }
        }
        fun number(): Any {
            val start = i
            while (i < t.length && (t[i].isDigit() || t[i] in "+-.eE")) i++
            val s = t.substring(start, i)
            return s.toLongOrNull() ?: s.toBigDecimal()
        }
        fun string(): String {
            i++
            val b = StringBuilder()
            while (true) {
                val c = t[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = t[i++]) {
                        'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(t.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}
