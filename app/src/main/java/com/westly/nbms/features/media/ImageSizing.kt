package com.westly.nbms.features.media

import kotlin.math.max
import kotlin.math.roundToInt

internal const val MSG_MEDIA_INVALID_IMAGE = "Please choose a JPEG, PNG, WEBP, GIF, or AVIF image."
internal const val MSG_MEDIA_GIF_TOO_LARGE = "That GIF is too large. Please choose a smaller file."
internal const val MSG_MEDIA_IMAGE_TOO_LARGE = "That image is too large. Please choose a smaller one."

enum class OutputFormat(val contentType: String, val fileName: String) {
    Jpeg("image/jpeg", "photo.jpg"),
    Png("image/png", "photo.png"),
    Gif("image/gif", "photo.gif")
}

sealed interface FormatDecision {
    data class Use(val format: OutputFormat) : FormatDecision
    data class Reject(val message: String) : FormatDecision
}

/** What a picture needs to be turned into before it is uploaded. Pure maths, no Android classes. */
object ImageSizing {
    const val MAX_SIDE = 1600
    const val JPEG_BUDGET_BYTES = 1_572_864        // 1.5 MiB
    const val GIF_MAX_BYTES = 4 * 1024 * 1024      // 4 MiB
    const val PNG_MAX_BYTES = 4 * 1024 * 1024      // 4 MiB

    val qualitySteps: List<Int> = listOf(85, 75, 65)

    data class Size(val width: Int, val height: Int)

    /** Scales so the longer side is at most [max]; never enlarges; keeps the ratio; each side is at least 1. */
    fun targetSize(w: Int, h: Int, max: Int = MAX_SIDE): Size {
        if (w <= 0 || h <= 0) return Size(max(w, 1), max(h, 1))
        val longer = max(w, h)
        if (longer <= max) return Size(w, h)
        val scale = max.toDouble() / longer
        return Size(
            (w * scale).roundToInt().coerceAtLeast(1),
            (h * scale).roundToInt().coerceAtLeast(1)
        )
    }

    /**
     * Power-of-two `inSampleSize` that keeps the decoded longer side at or above [max] but below 2 x [max]
     * (so a 12-megapixel photo is never fully decoded). 1 when the picture is already small.
     */
    fun sampleSizeFor(w: Int, h: Int, max: Int = MAX_SIDE): Int {
        val longer = max(w, h)
        var sample = 1
        while (longer / (sample * 2) >= max) sample *= 2
        return sample
    }

    /**
     * Decides the output type.
     * [bytesAfterEncode] is the size of what would be uploaded: the original file for a GIF, the encoded PNG for a
     * transparent picture. Opaque pictures always become JPEG (their size is handled by [encodeWithLadder]).
     */
    fun chooseFormat(isGif: Boolean, hasAlpha: Boolean, bytesAfterEncode: Int): FormatDecision = when {
        isGif ->
            if (bytesAfterEncode <= GIF_MAX_BYTES) FormatDecision.Use(OutputFormat.Gif)
            else FormatDecision.Reject(MSG_MEDIA_GIF_TOO_LARGE)
        hasAlpha ->
            if (bytesAfterEncode <= PNG_MAX_BYTES) FormatDecision.Use(OutputFormat.Png)
            else FormatDecision.Reject(MSG_MEDIA_IMAGE_TOO_LARGE)
        else -> FormatDecision.Use(OutputFormat.Jpeg)
    }

    /**
     * Encodes at 85, then 75, then 65, and stops at the first result within the 1.5 MiB budget.
     * If even 65 is above the budget, that last result is returned.
     */
    fun encodeWithLadder(encode: (quality: Int) -> ByteArray): ByteArray {
        var last = ByteArray(0)
        for (quality in qualitySteps) {
            last = encode(quality)
            if (last.size <= JPEG_BUDGET_BYTES) return last
        }
        return last
    }

    /** Rotation (degrees clockwise) and horizontal flip needed to make an EXIF-oriented picture upright. */
    data class Orientation(val degrees: Float, val flipHorizontal: Boolean) {
        val isIdentity: Boolean get() = degrees == 0f && !flipHorizontal
    }

    /** [exifOrientation] is the EXIF orientation tag value 1..8 (anything else means upright). Rotate first, then flip. */
    fun orientationFor(exifOrientation: Int): Orientation = when (exifOrientation) {
        2 -> Orientation(0f, true)
        3 -> Orientation(180f, false)
        4 -> Orientation(180f, true)
        5 -> Orientation(90f, true)
        6 -> Orientation(90f, false)
        7 -> Orientation(270f, true)
        8 -> Orientation(270f, false)
        else -> Orientation(0f, false)
    }
}
