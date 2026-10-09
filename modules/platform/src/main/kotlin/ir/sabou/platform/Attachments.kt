package ir.sabou.platform

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.ensure
import java.security.MessageDigest

/** A file the user attaches to a document (a photo or PDF of a supplier invoice, a cheque image). */
class AttachmentInput(val fileName: String, val mime: String, val bytes: ByteArray) {
    val sha256: String by lazy { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }

    /** For command fingerprints: the content hash, never the bytes. */
    fun fingerprint() = "$mime:${bytes.size}:$sha256"
}

/** Stored metadata; the bytes are read separately so lists stay light. */
data class Attachment(
    val id: GlobalId,
    val ownerType: String,
    val ownerId: GlobalId,
    val fileName: String,
    val mime: String,
    val sha256: String,
    val size: Int,
    val recordedAtEpochMillis: Long,
)

/** Attachments are immutable: a wrong file is not edited or deleted, the right one is added. */
interface AttachmentStore {
    fun save(attachment: Attachment, bytes: ByteArray)
    fun of(ownerType: String, ownerId: GlobalId): List<Attachment>
    fun meta(id: GlobalId): Attachment?
    fun content(id: GlobalId): ByteArray?
}

object Attachments {
    /** Kept under Android's 2 MB cursor-window limit; the app shrinks photos before attaching. */
    const val MAX_BYTES = 1_500_000
    const val MAX_PER_DOCUMENT = 10
    val MIMES = setOf("image/jpeg", "image/png", "image/webp", "application/pdf")

    /** Validates and stores [inputs] for one document inside the caller's command. */
    fun attach(ctx: CommandContext, store: AttachmentStore, ownerType: String, ownerId: GlobalId, inputs: List<AttachmentInput>) {
        val existing = store.of(ownerType, ownerId)
        ensure(existing.size + inputs.size <= MAX_PER_DOCUMENT) { DomainError.InvalidInput("attachment", "حداکثر $MAX_PER_DOCUMENT پیوست برای هر سند.") }
        inputs.distinctBy { it.sha256 }.forEach { input ->
            ensure(input.mime in MIMES) { DomainError.InvalidInput("attachment", "فقط عکس یا PDF پیوست می‌شود.") }
            ensure(input.bytes.isNotEmpty() && input.bytes.size <= MAX_BYTES) { DomainError.InvalidInput("attachment", "حجم پیوست حداکثر ۱٫۵ مگابایت است.") }
            if (existing.any { it.sha256 == input.sha256 }) return@forEach   // the same file twice adds nothing
            val name = input.fileName.trim().take(120).ifEmpty { "پیوست" }
            val a = Attachment(GlobalId.new(), ownerType, ownerId, name, input.mime, input.sha256, input.bytes.size, ctx.nowEpochMillis)
            store.save(a, input.bytes)
            ctx.audit(AuditDraft("ATTACHMENT_ADD", ownerType, ownerId.value, "attachment=${a.id};sha256=${a.sha256};size=${a.size}"))
        }
    }
}
