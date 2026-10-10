package com.westly.nbms.features.users

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import com.westly.nbms.features.users.models.AuditLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.westly.nbms.core.util.Branding

internal const val CSV_HEADER = "\"Timestamp\",\"User\",\"Role\",\"Action\",\"Collection\",\"Document ID\""

/** Wraps a value in double quotes and doubles any quote inside it. */
internal fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

/** One CSV line for an entry: Timestamp, User, Role (role key), Action, Collection, Document ID. */
internal fun csvRow(entry: AuditLogEntry, time: String): String =
    listOf(time, entry.userName, entry.userRole, entry.action, entry.collection, entry.documentId)
        .joinToString(",") { csvCell(it) }

/** The whole file: the header, then one row per entry (callers pass the FILTERED list). */
internal fun buildAuditCsv(
    entries: List<AuditLogEntry>,
    timeText: (AuditLogEntry) -> String = { Format.dateTime(it.timestamp.toInstant()) }
): String = (listOf(CSV_HEADER) + entries.map { csvRow(it, timeText(it)) }).joinToString("\n")

/** "audit-log-2026-10-08.csv" for a date written as YYYY-MM-DD. */
internal fun auditCsvFileName(isoDate: String): String = "audit-log-$isoDate.csv"

/** Writes the CSV into the app's cache folder and opens the Android share sheet for it. */
internal object AuditCsvExporter {

    suspend fun share(context: Context, csv: String, fileName: String) {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(Branding.csvWithCopyright(csv), Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Export audit log").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
