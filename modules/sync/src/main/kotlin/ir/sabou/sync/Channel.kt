package ir.sabou.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The wire between the two phones (ADR-0042), with no third-party code.
 *
 * Pairing (once): both phones make a fresh P-256 key pair and exchange public keys and nonces; the shared secret
 * becomes a 32-byte pairing key. Both screens show a six-digit code derived from it, and the owner confirms they
 * match — the same "numeric comparison" Bluetooth uses, which defeats a device in the middle (it would get two
 * different keys, hence two different codes).
 *
 * Every later connection: the second phone names itself and sends a nonce, the main phone answers with its own;
 * both derive one AES-256-GCM key per direction from the pairing key and both nonces. Every frame carries a
 * counter as its nonce, so frames cannot be replayed, reordered or altered; a peer without the pairing key fails
 * the very first frame.
 */
internal object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** HKDF-SHA256 (RFC 5869) with a 32-byte output. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: String): ByteArray {
        val prk = hmac(salt, secret)
        return hmac(prk, info.toByteArray(Charsets.UTF_8), byteArrayOf(1))
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        parts.forEach { d.update(it) }
        return d.digest()
    }

    class Ephemeral {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        val publicKey: ByteArray = pair.public.encoded

        fun agree(peer: ByteArray): ByteArray {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peer))
            return KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(key, true); generateSecret() }
        }
    }

    /** The pairing key and the six-digit comparison code both screens show. */
    fun pairing(shared: ByteArray, clientPub: ByteArray, serverPub: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): Pair<ByteArray, String> {
        val transcript = sha256(clientPub, serverPub, clientNonce, serverNonce)
        val key = hkdf(shared, transcript, "sabou-pair-v1")
        val c = hmac(key, "code".toByteArray())
        val n = ((c[0].toLong() and 0xff) shl 24 or ((c[1].toLong() and 0xff) shl 16) or ((c[2].toLong() and 0xff) shl 8) or (c[3].toLong() and 0xff)) % 1_000_000
        return key to n.toString().padStart(6, '0')
    }

    fun sessionKeys(pairingKey: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = sha256(clientNonce, serverNonce)
        return hkdf(pairingKey, salt, "sabou-c2s-v1") to hkdf(pairingKey, salt, "sabou-s2c-v1")
    }
}

/** An encrypted, authenticated, ordered stream of frames over one connection. */
internal class SecureChannel(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    sendKey: ByteArray,
    receiveKey: ByteArray,
) {
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val receiveKey = SecretKeySpec(receiveKey, "AES")
    private var sent = 0L
    private var received = 0L

    private fun iv(counter: Long) = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (counter ushr (56 - 8 * i)).toByte() }

    @Synchronized
    fun sendBytes(plain: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, iv(sent++)))
        val sealed = c.doFinal(plain)
        output.writeInt(sealed.size)
        output.write(sealed)
        output.flush()
    }

    fun receiveBytes(): ByteArray {
        val size = input.readInt()
        if (size !in 16..MAX_FRAME) throw IOException("bad frame size")
        val sealed = ByteArray(size).also { input.readFully(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(128, iv(received++)))
        return try {
            c.doFinal(sealed)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw IOException("frame failed authentication", e)
        }
    }

    fun send(message: Message) = sendBytes(Wire.encode(message))
    fun receive(): Message = Wire.decode(receiveBytes())

    companion object {
        /** Large enough for a command with a few photos attached; the full copy travels in chunks. */
        const val MAX_FRAME = 48 * 1024 * 1024
    }
}

/** Java serialization of [Message]s, reading back only the app's own classes and plain JDK/Kotlin data types. */
internal object Wire {
    fun encode(message: Message): ByteArray = ByteArrayOutputStream().also { b -> ObjectOutputStream(b).use { it.writeObject(message) } }.toByteArray()

    fun decode(bytes: ByteArray): Message = AllowListInput(ByteArrayInputStream(bytes)).use { it.readObject() as Message }

    private val ALLOWED_PREFIXES = listOf("ir.sabou.", "java.util.", "kotlin.collections.", "kotlin.Pair", "kotlin.Triple", "kotlin.Unit")
    private val ALLOWED_EXACT = setOf(
        "java.lang.String", "java.lang.Long", "java.lang.Integer", "java.lang.Short", "java.lang.Byte", "java.lang.Boolean",
        "java.lang.Character", "java.lang.Double", "java.lang.Float", "java.lang.Number", "java.lang.Enum", "java.lang.Object",
    )
    // Collections that run code while being read back are not data and are refused.
    private val REFUSED = setOf("java.util.PriorityQueue", "java.util.TreeMap", "java.util.TreeSet", "java.util.Comparator")

    fun allowed(name: String): Boolean {
        val base = name.trimStart('[').removePrefix("L").removeSuffix(";")
        if (base.length == 1) return true   // primitive arrays: [B, [C, [J …
        if (base in REFUSED || base.startsWith("java.util.concurrent.") || base.startsWith("java.util.logging.")) return false
        return base in ALLOWED_EXACT || ALLOWED_PREFIXES.any { base.startsWith(it) }
    }

    private class AllowListInput(input: InputStream) : ObjectInputStream(input) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            if (!allowed(desc.name)) throw java.io.InvalidClassException(desc.name, "not allowed on the sync wire")
            return super.resolveClass(desc)
        }
    }
}

/** Reads exactly [n] bytes or fails (a closed connection mid-handshake). */
internal fun DataInputStream.readBytes(n: Int): ByteArray {
    if (n !in 0..4096) throw IOException("bad handshake field")
    return ByteArray(n).also { readFully(it) }
}

internal fun DataInputStream.readShortBytes(): ByteArray = readBytes(readUnsignedShort())

internal fun DataOutputStream.writeShortBytes(b: ByteArray) {
    writeShort(b.size); write(b)
}

internal fun eof(): Nothing = throw EOFException()
