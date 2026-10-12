package com.westly.nbms.features.checkout

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.westly.nbms.features.checkin.CheckInReceiptData
import com.westly.nbms.features.checkin.CheckInReceiptPdfBuilder
import com.westly.nbms.features.checkin.checkInReceiptFileName
import java.io.File
import java.io.FileOutputStream

/**
 * Writes the receipt PDF into the app's cache folder and opens the Android share sheet for it.
 * The file lives in `cache/exports/receipts/`, which the FileProvider paths of Phase 8 (`exports/`) already cover.
 */
internal object ReceiptSharer {

    /** Writes and shares the guest's stay receipt (the one handed over at check-in). */
    suspend fun shareCheckIn(context: Context, data: CheckInReceiptData) {
        val fileName = checkInReceiptFileName(data.receiptNumber)
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports/receipts").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { CheckInReceiptPdfBuilder.build(data, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Stay receipt ${data.receiptNumber}")
            clipData = ClipData.newRawUri("Receipt", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share receipt").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    suspend fun share(context: Context, data: ReceiptData) {
        val fileName = receiptFileName(data.receiptNumber)
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports/receipts").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { ReceiptPdfBuilder.build(data, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Guest receipt ${data.receiptNumber}")
            clipData = ClipData.newRawUri("Receipt", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share receipt").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
