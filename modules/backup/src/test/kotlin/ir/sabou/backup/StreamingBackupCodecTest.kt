package ir.sabou.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingBackupCodecTest {
    private val password = "یک رمز قوی برای پشتیبان".toCharArray()
    private val seg = 4_096
    private val fastKdf = 100_000

    private fun encrypt(data: ByteArray): ByteArray = ByteArrayOutputStream().also {
        StreamingBackupCodec.encrypt(password, ByteArrayInputStream(data), it, seg, fastKdf)
    }.toByteArray()

    private fun decrypt(blob: ByteArray, pw: CharArray = password): ByteArray = ByteArrayOutputStream().also {
        StreamingBackupCodec.decrypt(pw, ByteArrayInputStream(blob), it)
    }.toByteArray()

    @Test fun roundTripAcrossSegmentBoundaries() {
        for (size in listOf(0, 1, seg - 1, seg, seg + 1, 3 * seg, 3 * seg + 17)) {
            val data = Random(size).nextBytes(size)
            assertContentEquals(data, decrypt(encrypt(data)), "size=$size")
        }
    }

    @Test fun wrongPasswordFails() {
        val blob = encrypt(Random(1).nextBytes(10_000))
        assertEquals("authentication_failed", assertFailsWith<BackupFormatException> { decrypt(blob, "رمز نادرست دیگری".toCharArray()) }.message)
    }

    @Test fun bitFlipAnywhereInThePayloadIsDetected() {
        val blob = encrypt(Random(2).nextBytes(3 * seg))
        for (position in listOf(60, blob.size / 2, blob.size - 1)) {
            val copy = blob.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertFailsWith<BackupFormatException> { decrypt(copy) }
        }
    }

    @Test fun truncationAndAppendedDataAreDetected() {
        val blob = encrypt(Random(3).nextBytes(3 * seg))
        val headerSize = 44
        val frame = 1 + 4 + seg + 16
        // Cut after the first full frame: the final segment is missing.
        assertEquals("truncated", assertFailsWith<BackupFormatException> { decrypt(blob.copyOf(headerSize + frame)) }.message)
        assertEquals("trailing_data", assertFailsWith<BackupFormatException> { decrypt(blob + byteArrayOf(0)) }.message)
    }

    @Test fun reorderedSegmentsAreDetected() {
        val blob = encrypt(Random(4).nextBytes(3 * seg))
        val headerSize = 44
        val frame = 1 + 4 + seg + 16
        val swapped = blob.copyOf()
        System.arraycopy(blob, headerSize + frame, swapped, headerSize, frame)
        System.arraycopy(blob, headerSize, swapped, headerSize + frame, frame)
        assertEquals("authentication_failed", assertFailsWith<BackupFormatException> { decrypt(swapped) }.message)
    }

    @Test fun shortPasswordIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            StreamingBackupCodec.encrypt("کوتاه".toCharArray(), ByteArrayInputStream(ByteArray(1)), ByteArrayOutputStream(), seg, fastKdf)
        }
    }
}
