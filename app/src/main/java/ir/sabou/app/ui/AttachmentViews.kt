package ir.sabou.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.Messages
import ir.sabou.platform.Attachment
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.Attachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Reading a picked photo or PDF: photos are shrunk (long side ≤ 1600 px, JPEG) to stay well under the size limit. */
object AttachmentFiles {
    private const val MAX_SIDE = 1600

    fun read(context: Context, uri: Uri): AttachmentInput {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: error("ATTACHMENT_TYPE_UNKNOWN")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "پیوست"
        return when {
            mime == "application/pdf" -> {
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > Attachments.MAX_BYTES) throw ir.sabou.kernel.DomainException(
                            ir.sabou.kernel.DomainError.InvalidInput("attachment", "حجم PDF بیشتر از ۱٫۵ مگابایت است."),
                        )
                    }
                    out.toByteArray()
                } ?: error("ATTACHMENT_UNREADABLE")
                AttachmentInput(name, mime, bytes)
            }
            mime.startsWith("image/") -> {
                val bitmap = decode(context, uri)
                var quality = 85
                var bytes: ByteArray
                do {
                    bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                    quality -= 15
                } while (bytes.size > Attachments.MAX_BYTES && quality > 20)
                bitmap.recycle()
                AttachmentInput(name.substringBeforeLast('.') + ".jpg", "image/jpeg", bytes)
            }
            else -> throw ir.sabou.kernel.DomainException(ir.sabou.kernel.DomainError.InvalidInput("attachment", "فقط عکس یا PDF پیوست می‌شود."))
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also applies the photo's rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width; val h = info.size.height
                val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(w, h))
                decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("ATTACHMENT_UNREADABLE")
        }
}

/** A button that picks a photo or PDF and hands it over, already shrunk. */
@Composable
fun AttachmentPicker(label: String, onPicked: (AttachmentInput) -> Unit) {
    val session = LocalSession.current
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true; error = null
        session.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AttachmentFiles.read(context, uri) } }
            busy = false
            result.onSuccess(onPicked).onFailure { error = Messages.of(it) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SecondaryButton(if (busy) "در حال آماده‌سازی…" else label, { launcher.launch(arrayOf("image/*", "application/pdf")) }, enabled = !busy)
        error?.let { Banner(it) }
    }
}

/** Stored attachments of a document: a photo opens on screen, a PDF can be saved anywhere. */
@Composable
fun AttachmentList(attachments: List<Attachment>) {
    val session = LocalSession.current
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Pair<Attachment, Bitmap>?>(null) }
    var saving by remember { mutableStateOf<Attachment?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val target = saving ?: return@rememberLauncherForActivityResult
        saving = null
        uri ?: return@rememberLauncherForActivityResult
        session.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (_, bytes) = session.core.buying.attachment(target.id)
                    (context.contentResolver.openOutputStream(uri, "w") ?: error("EXPORT_TARGET_UNAVAILABLE")).use { it.write(bytes) }
                }
            }
            error = result.exceptionOrNull()?.let(Messages::of)
        }
    }
    fun open(a: Attachment) {
        error = null
        if (a.mime == "application/pdf") { saving = a; save.launch(a.fileName); return }
        session.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { session.core.buying.attachment(a.id).second.let { BitmapFactory.decodeByteArray(it, 0, it.size) ?: error("ATTACHMENT_UNREADABLE") } }
            }
            result.onSuccess { shown = a to it }.onFailure { error = Messages.of(it) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { open(a) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.fileName, style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                Text((if (a.mime == "application/pdf") "PDF · " else "عکس · ") + Fa.number((a.size / 1024).toLong()) + " KB",
                    style = SabouType.caption, color = Sabou.colors.muted)
            }
        }
        error?.let { Banner(it, ChipKind.DANGER) }
    }
    shown?.let { (a, bitmap) ->
        AlertDialog(
            onDismissRequest = { shown = null },
            confirmButton = { TextButton({ shown = null }) { Text("بستن") } },
            title = { Text(a.fileName, style = SabouType.section) },
            text = { Image(bitmap.asImageBitmap(), contentDescription = a.fileName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) },
            containerColor = Sabou.colors.surface,
        )
    }
}
