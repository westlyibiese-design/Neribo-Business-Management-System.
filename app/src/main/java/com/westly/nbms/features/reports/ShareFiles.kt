package com.westly.nbms.features.reports

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Thin wrapper: write a file to the cache, then open the Android share sheet for it. Never throws. */
object ShareFiles {

    fun shareText(context: Context, fileName: String, content: String, mimeType: String = "text/csv"): Result<Unit> =
        shareBytes(context, fileName, content.toByteArray(Charsets.UTF_8), mimeType)

    fun shareBytes(context: Context, fileName: String, bytes: ByteArray, mimeType: String): Result<Unit> =
        runCatching {
            val safeName = File(fileName).name
            require(safeName.isNotBlank()) { "File name is empty" }

            val dir = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
            val file = File(dir, safeName)
            file.writeBytes(bytes)

            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(safeName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, safeName).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }

    // Inside "exports/" because that is the only cache folder the Phase 0/8 FileProvider (file_paths.xml) exposes.
    private const val SHARE_FOLDER = "exports/shared"
}
