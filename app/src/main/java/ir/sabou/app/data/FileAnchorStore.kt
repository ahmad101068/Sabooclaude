package ir.sabou.app.data

import android.util.Base64
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.platform.AnchorKind
import ir.sabou.platform.AnchorStore
import ir.sabou.platform.IntegrityAnchor
import java.io.File
import java.security.MessageDigest

/**
 * Integrity anchors kept OUTSIDE the database and signed with a Keystore HMAC key, so swapping the
 * database for an older copy is detected at startup (ADR-0004). Only the last [KEEP] anchors are kept;
 * the file is replaced atomically.
 */
class FileAnchorStore(private val file: File, private val keys: DeviceKeys) : AnchorStore {

    @Synchronized
    override fun latest(): IntegrityAnchor? {
        val last = lines().lastOrNull() ?: return null
        return decode(last) ?: throw DomainException(DomainError.IntegrityViolation("ANCHOR_FILE_TAMPERED"))
    }

    @Synchronized
    override fun record(anchor: IntegrityAnchor) {
        val kept = (lines() + encode(anchor)).takeLast(KEEP)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(kept.joinToString("\n", postfix = "\n"))
        check(tmp.renameTo(file)) { "anchor_write_failed" }
    }

    private fun lines(): List<String> = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun encode(a: IntegrityAnchor): String {
        val body = listOf(a.kind.name, a.epoch, a.sequence.toString(), a.hash, a.reason, a.recordedAtEpochMillis.toString())
            .joinToString(SEP) { it.replace(SEP, " ") }.toByteArray(Charsets.UTF_8)
        return b64(body) + "." + b64(keys.hmac(body))
    }

    private fun decode(line: String): IntegrityAnchor? = runCatching {
        val (bodyText, macText) = line.split('.').also { require(it.size == 2) }
        val body = Base64.decode(bodyText, Base64.NO_WRAP)
        if (!MessageDigest.isEqual(keys.hmac(body), Base64.decode(macText, Base64.NO_WRAP))) return null
        val f = String(body, Charsets.UTF_8).split(SEP)
        IntegrityAnchor(AnchorKind.valueOf(f[0]), f[1], f[2].toLong(), f[3], f[4], f[5].toLong())
    }.getOrNull()

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val SEP = "\u001F"
        const val KEEP = 50
    }
}
