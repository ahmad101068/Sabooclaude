package ir.sabou.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.sabou.app.export.PdfReport
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.core.Fa
import ir.sabou.core.Messages
import ir.sabou.core.ReportTable
import ir.sabou.core.SabouCore
import ir.sabou.core.Xlsx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «خروجی اکسل» and «خروجی PDF» for any report: the user picks where to save (Downloads, Drive, …); the
 * tables are built fresh from the database at that moment, off the main thread.
 */
@Composable
fun ExportButtons(fileName: String, build: SabouCore.() -> List<ReportTable>) {
    val session = LocalSession.current
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    fun export(uri: android.net.Uri?, pdf: Boolean) {
        uri ?: return
        busy = true; message = null
        session.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tables = session.core.build()
                    require(tables.isNotEmpty()) { "EMPTY_REPORT" }
                    val out = context.contentResolver.openOutputStream(uri, "w") ?: error("EXPORT_TARGET_UNAVAILABLE")
                    out.use { if (pdf) PdfReport.write(context, tables, it) else it.write(Xlsx.write(tables)) }
                }
            }
            busy = false
            message = result.fold({ "فایل ذخیره شد." to true }, { Messages.of(it) to false })
        }
    }
    val excel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX)) { export(it, pdf = false) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { export(it, pdf = true) }
    val stamp = Fa.latinDigits(Fa.date(session.today)).replace('/', '-')
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(if (busy) "در حال ساخت…" else "خروجی اکسل", { excel.launch("$fileName-$stamp.xlsx") }, Modifier.weight(1f), enabled = !busy)
            SecondaryButton(if (busy) "در حال ساخت…" else "خروجی PDF", { pdf.launch("$fileName-$stamp.pdf") }, Modifier.weight(1f), enabled = !busy)
        }
        message?.let { (text, ok) -> Banner(text, if (ok) ChipKind.PRIMARY else ChipKind.DANGER) }
    }
}

private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
