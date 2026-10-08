package ir.sabou.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupFormatException(message: String) : Exception(message)

/**
 * Password-protected, streaming backup container (format 4).
 *
 * The payload is split into fixed-size segments; each segment is sealed with AES-256-GCM under a
 * per-segment nonce (prefix ‖ counter) and authenticated together with the header, its index and
 * a "final" flag. Memory use is one segment regardless of backup size (fixes AUD-005, where GCM
 * over the whole file needed several times the backup size in RAM). Reordering, truncation,
 * appended data and bit flips are all detected.
 *
 * A segment is written to [OutputStream] only after it authenticates, but a damaged file can fail
 * after earlier segments were written: callers must decrypt into a temporary file and discard it
 * on any exception.
 */
object StreamingBackupCodec {
    private val MAGIC = "SABOUBK4".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 4
    const val DEFAULT_SEGMENT = 1 shl 20
    private const val ITERATIONS = 310_000
    private const val MIN_PASSWORD = 10
    private const val TAG_BYTES = 16

    fun encrypt(password: CharArray, input: InputStream, output: OutputStream, segmentSize: Int = DEFAULT_SEGMENT, iterations: Int = ITERATIONS): Long {
        require(password.size >= MIN_PASSWORD) { "backup_password_too_short" }
        require(segmentSize in 4_096..(16 shl 20)) { "backup_segment_size" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = header(salt, prefix, segmentSize, iterations)
        val key = deriveKey(password, salt, iterations)
        output.write(header)
        val out = DataOutputStream(output)
        val buffer = ByteArray(segmentSize)
        var next = readFully(input, buffer)
        var index = 0L
        var total = 0L
        while (true) {
            val current = next
            // Look ahead one segment so the last one can carry the final flag.
            val lookahead = if (current == segmentSize) ByteArray(segmentSize) else null
            val nextLen = if (lookahead != null) readFully(input, lookahead) else 0
            val final = lookahead == null || nextLen == 0
            val sealed = seal(key, prefix, header, index, final, buffer, current)
            out.writeByte(if (final) 1 else 0)
            out.writeInt(sealed.size)
            out.write(sealed)
            total += current
            if (final) break
            System.arraycopy(lookahead!!, 0, buffer, 0, nextLen)
            next = nextLen
            index++
        }
        out.flush()
        return total
    }

    fun decrypt(password: CharArray, input: InputStream, output: OutputStream, maxBytes: Long = Long.MAX_VALUE): Long {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also { readExact(data, it) }
        if (!magic.contentEquals(MAGIC)) throw BackupFormatException("not_a_sabou_backup")
        val version = data.readInt()
        if (version != VERSION) throw BackupFormatException("unsupported_version:$version")
        val iterations = data.readInt()
        if (iterations !in 100_000..5_000_000) throw BackupFormatException("bad_kdf_parameters")
        val salt = ByteArray(16).also { readExact(data, it) }
        val prefix = ByteArray(8).also { readExact(data, it) }
        val segmentSize = data.readInt()
        if (segmentSize !in 4_096..(16 shl 20)) throw BackupFormatException("bad_segment_size")
        val header = header(salt, prefix, segmentSize, iterations)
        val key = deriveKey(password, salt, iterations)
        var index = 0L
        var total = 0L
        while (true) {
            val flag = try { data.readUnsignedByte() } catch (e: EOFException) { throw BackupFormatException("truncated") }
            if (flag > 1) throw BackupFormatException("bad_frame")
            val length = data.readInt()
            if (length !in TAG_BYTES..(segmentSize + TAG_BYTES)) throw BackupFormatException("bad_frame_length")
            val sealed = ByteArray(length).also { readExact(data, it) }
            val plain = try { open(key, prefix, header, index, flag == 1, sealed) } catch (e: javax.crypto.AEADBadTagException) {
                throw BackupFormatException("authentication_failed")
            }
            if (flag == 0 && plain.size != segmentSize) throw BackupFormatException("short_non_final_segment")
            total += plain.size
            if (total > maxBytes) throw BackupFormatException("too_large")
            output.write(plain)
            if (flag == 1) break
            index++
            if (index >= 0xFFFF_FFFFL) throw BackupFormatException("too_many_segments")
        }
        if (data.read() != -1) throw BackupFormatException("trailing_data")
        output.flush()
        return total
    }

    private fun seal(key: SecretKeySpec, prefix: ByteArray, header: ByteArray, index: Long, final: Boolean, buf: ByteArray, len: Int): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index)))
            updateAAD(aad(header, index, final))
            doFinal(buf, 0, len)
        }

    private fun open(key: SecretKeySpec, prefix: ByteArray, header: ByteArray, index: Long, final: Boolean, sealed: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index)))
            updateAAD(aad(header, index, final))
            doFinal(sealed)
        }

    private fun nonce(prefix: ByteArray, index: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index.toInt()).array()

    private fun aad(header: ByteArray, index: Long, final: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 9).put(header).putLong(index).put(if (final) 1 else 0).array()

    private fun header(salt: ByteArray, prefix: ByteArray, segmentSize: Int, iterations: Int): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 4 + 4 + 16 + 8 + 4).put(MAGIC).putInt(VERSION).putInt(iterations).put(salt).put(prefix).putInt(segmentSize).array()

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun readExact(input: DataInputStream, buffer: ByteArray) {
        try { input.readFully(buffer) } catch (e: EOFException) { throw BackupFormatException("truncated") }
    }
}
