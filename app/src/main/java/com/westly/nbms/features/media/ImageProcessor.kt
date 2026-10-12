package com.westly.nbms.features.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A picture ready to upload. */
class ProcessedImage(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String
)

/** A picture that cannot be used; [message] is already written for the person to read. */
class ImageProcessingException(message: String) : Exception(message)

/**
 * Turns a picked picture (a content Uri) into upload-ready bytes: upright, longer side at most 1600 px,
 * JPEG 85/75/65 (PNG when it has real transparency, GIF kept as is). Runs off the main thread and never
 * decodes a full-size bitmap.
 */
@Singleton
class ImageProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun process(uri: Uri): ProcessedImage = withContext(Dispatchers.Default) {
        try {
            processBlocking(uri)
        } catch (e: ImageProcessingException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw ImageProcessingException(MSG_MEDIA_IMAGE_TOO_LARGE)
        } catch (e: Exception) {
            throw ImageProcessingException(MSG_MEDIA_INVALID_IMAGE)
        }
    }

    private fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw ImageProcessingException(MSG_MEDIA_INVALID_IMAGE)

    private fun processBlocking(uri: Uri): ProcessedImage {
        // 1. Reject things that are clearly not pictures before decoding anything.
        val declared = context.contentResolver.getType(uri)
        if (declared != null && !declared.startsWith("image/")) throw ImageProcessingException(MSG_MEDIA_INVALID_IMAGE)

        // 2. Read only the size and type.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw ImageProcessingException(MSG_MEDIA_INVALID_IMAGE)

        // 3. GIFs are uploaded unchanged so they keep their animation.
        if (bounds.outMimeType == "image/gif") {
            val bytes = readLimited(uri, ImageSizing.GIF_MAX_BYTES)
                ?: throw ImageProcessingException(MSG_MEDIA_GIF_TOO_LARGE)
            return when (val decision = ImageSizing.chooseFormat(isGif = true, hasAlpha = false, bytesAfterEncode = bytes.size)) {
                is FormatDecision.Reject -> throw ImageProcessingException(decision.message)
                is FormatDecision.Use -> ProcessedImage(bytes, decision.format.contentType, decision.format.fileName)
            }
        }

        // 4. Decode small (inSampleSize), then scale so the longer side is at most 1600 px.
        val orientation = ImageSizing.orientationFor(readExifOrientation(uri))
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = ImageSizing.sampleSizeFor(width, height)
        }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: throw ImageProcessingException(MSG_MEDIA_INVALID_IMAGE)
        val target = ImageSizing.targetSize(decoded.width, decoded.height)
        val scaled = if (target.width != decoded.width || target.height != decoded.height) {
            Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        val upright = applyOrientation(scaled, orientation)

        try {
            // 5. Opaque pictures become JPEG; real transparency stays PNG.
            val hasAlpha = hasRealTransparency(upright)
            if (hasAlpha) {
                val png = encode(upright, Bitmap.CompressFormat.PNG, 100)
                return when (val decision = ImageSizing.chooseFormat(isGif = false, hasAlpha = true, bytesAfterEncode = png.size)) {
                    is FormatDecision.Reject -> throw ImageProcessingException(decision.message)
                    is FormatDecision.Use -> ProcessedImage(png, decision.format.contentType, decision.format.fileName)
                }
            }
            val jpeg = ImageSizing.encodeWithLadder { quality -> encode(upright, Bitmap.CompressFormat.JPEG, quality) }
            return ProcessedImage(jpeg, OutputFormat.Jpeg.contentType, OutputFormat.Jpeg.fileName)
        } finally {
            upright.recycle()
        }
    }

    /** Reads at most [limit] bytes; returns null when the file is bigger than that. */
    private fun readLimited(uri: Uri, limit: Int): ByteArray? {
        open(uri).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun readExifOrientation(uri: Uri): Int = try {
        open(uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: ImageSizing.Orientation): Bitmap {
        if (orientation.isIdentity) return bitmap
        val matrix = Matrix()
        if (orientation.degrees != 0f) matrix.postRotate(orientation.degrees)
        if (orientation.flipHorizontal) matrix.postScale(-1f, 1f)
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** True only when at least one pixel is not fully opaque (a PNG with an unused alpha channel becomes JPEG). */
    private fun hasRealTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val w = bitmap.width
        val row = IntArray(w)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (pixel in row) {
                if ((pixel ushr 24) != 0xFF) return true
            }
        }
        return false
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality, out)) throw ImageProcessingException(MSG_MEDIA_INVALID_IMAGE)
        return out.toByteArray()
    }
}
