package com.westly.nbms.features.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSizingTest {

    // ---- targetSize ----

    @Test fun smallPictureIsNeverEnlarged() {
        assertEquals(ImageSizing.Size(800, 600), ImageSizing.targetSize(800, 600))
        assertEquals(ImageSizing.Size(1600, 900), ImageSizing.targetSize(1600, 900))
    }

    @Test fun landscapeShrinksToLongerSide1600() {
        assertEquals(ImageSizing.Size(1600, 1200), ImageSizing.targetSize(4000, 3000))
    }

    @Test fun portraitShrinksToLongerSide1600() {
        assertEquals(ImageSizing.Size(1200, 1600), ImageSizing.targetSize(3000, 4000))
    }

    @Test fun extremeRatioKeepsAtLeastOnePixel() {
        val size = ImageSizing.targetSize(100_000, 10)
        assertEquals(1600, size.width)
        assertEquals(1, size.height)
    }

    @Test fun customMaxIsHonoured() {
        assertEquals(ImageSizing.Size(500, 250), ImageSizing.targetSize(2000, 1000, max = 500))
    }

    // ---- sampleSizeFor ----

    @Test fun smallPictureIsDecodedAtFullSize() {
        assertEquals(1, ImageSizing.sampleSizeFor(1000, 800))
        assertEquals(1, ImageSizing.sampleSizeFor(1600, 1200))
        assertEquals(1, ImageSizing.sampleSizeFor(3199, 2000))
    }

    @Test fun sampleSizeIsAPowerOfTwoThatKeepsAtLeastTheTarget() {
        assertEquals(2, ImageSizing.sampleSizeFor(3200, 2400))
        assertEquals(2, ImageSizing.sampleSizeFor(4000, 3000))    // 12 MP: decoded at 2000 x 1500
        assertEquals(4, ImageSizing.sampleSizeFor(6400, 4800))
        assertEquals(8, ImageSizing.sampleSizeFor(12800, 9600))
    }

    @Test fun decodedLongerSideStaysBetweenOneAndTwoTimesTheTarget() {
        for (longer in listOf(1601, 2500, 3199, 3200, 5000, 8000, 12000)) {
            val sample = ImageSizing.sampleSizeFor(longer, longer / 2)
            val decoded = longer / sample
            assertTrue("longer=$longer decoded=$decoded", decoded >= ImageSizing.MAX_SIDE)
            assertTrue("longer=$longer decoded=$decoded", decoded < 2 * ImageSizing.MAX_SIDE)
        }
    }

    // ---- chooseFormat ----

    @Test fun gifUnderLimitIsKeptAsGif() {
        val d = ImageSizing.chooseFormat(isGif = true, hasAlpha = false, bytesAfterEncode = 4 * 1024 * 1024)
        assertEquals(FormatDecision.Use(OutputFormat.Gif), d)
    }

    @Test fun gifOverFourMiBIsRejected() {
        val d = ImageSizing.chooseFormat(isGif = true, hasAlpha = false, bytesAfterEncode = 4 * 1024 * 1024 + 1)
        assertEquals(FormatDecision.Reject("That GIF is too large. Please choose a smaller file."), d)
    }

    @Test fun transparentPictureStaysPng() {
        val d = ImageSizing.chooseFormat(isGif = false, hasAlpha = true, bytesAfterEncode = 900_000)
        assertEquals(FormatDecision.Use(OutputFormat.Png), d)
    }

    @Test fun transparentPictureOverFourMiBIsRejected() {
        val d = ImageSizing.chooseFormat(isGif = false, hasAlpha = true, bytesAfterEncode = 4 * 1024 * 1024 + 1)
        assertEquals(FormatDecision.Reject("That image is too large. Please choose a smaller one."), d)
    }

    @Test fun opaquePictureBecomesJpeg() {
        val d = ImageSizing.chooseFormat(isGif = false, hasAlpha = false, bytesAfterEncode = 0)
        assertEquals(FormatDecision.Use(OutputFormat.Jpeg), d)
    }

    @Test fun outputNamesAndTypesMatch() {
        assertEquals("photo.jpg", OutputFormat.Jpeg.fileName)
        assertEquals("image/jpeg", OutputFormat.Jpeg.contentType)
        assertEquals("photo.png", OutputFormat.Png.fileName)
        assertEquals("image/png", OutputFormat.Png.contentType)
        assertEquals("photo.gif", OutputFormat.Gif.fileName)
        assertEquals("image/gif", OutputFormat.Gif.contentType)
    }

    // ---- quality ladder ----

    @Test fun qualityStepsAre85Then75Then65() {
        assertEquals(listOf(85, 75, 65), ImageSizing.qualitySteps)
    }

    @Test fun ladderStopsAtTheFirstPassUnderBudget() {
        val tried = mutableListOf<Int>()
        val result = ImageSizing.encodeWithLadder { q ->
            tried += q
            ByteArray(100)
        }
        assertEquals(listOf(85), tried)
        assertEquals(100, result.size)
    }

    @Test fun ladderGoesDownUntilItFits() {
        val tried = mutableListOf<Int>()
        val result = ImageSizing.encodeWithLadder { q ->
            tried += q
            when (q) {
                85 -> ByteArray(ImageSizing.JPEG_BUDGET_BYTES + 1)
                75 -> ByteArray(ImageSizing.JPEG_BUDGET_BYTES)   // exactly on budget counts as fitting
                else -> ByteArray(10)
            }
        }
        assertEquals(listOf(85, 75), tried)
        assertEquals(ImageSizing.JPEG_BUDGET_BYTES, result.size)
    }

    @Test fun ladderReturnsTheLastTryWhenNothingFits() {
        val tried = mutableListOf<Int>()
        val result = ImageSizing.encodeWithLadder { q ->
            tried += q
            ByteArray(ImageSizing.JPEG_BUDGET_BYTES + q)
        }
        assertEquals(listOf(85, 75, 65), tried)
        assertEquals(ImageSizing.JPEG_BUDGET_BYTES + 65, result.size)
    }

    // ---- EXIF orientation ----

    @Test fun uprightOrUnknownOrientationDoesNothing() {
        assertTrue(ImageSizing.orientationFor(1).isIdentity)
        assertTrue(ImageSizing.orientationFor(0).isIdentity)
        assertTrue(ImageSizing.orientationFor(99).isIdentity)
    }

    @Test fun orientationTableMatchesExif() {
        assertEquals(ImageSizing.Orientation(0f, true), ImageSizing.orientationFor(2))
        assertEquals(ImageSizing.Orientation(180f, false), ImageSizing.orientationFor(3))
        assertEquals(ImageSizing.Orientation(180f, true), ImageSizing.orientationFor(4))
        assertEquals(ImageSizing.Orientation(90f, true), ImageSizing.orientationFor(5))
        assertEquals(ImageSizing.Orientation(90f, false), ImageSizing.orientationFor(6))
        assertEquals(ImageSizing.Orientation(270f, true), ImageSizing.orientationFor(7))
        assertEquals(ImageSizing.Orientation(270f, false), ImageSizing.orientationFor(8))
        assertFalse(ImageSizing.orientationFor(6).isIdentity)
    }

    @Test fun budgetsMatchTheSpec() {
        assertEquals(1600, ImageSizing.MAX_SIDE)
        assertEquals((1.5 * 1024 * 1024).toInt(), ImageSizing.JPEG_BUDGET_BYTES)
        assertEquals(4 * 1024 * 1024, ImageSizing.GIF_MAX_BYTES)
        assertEquals(4 * 1024 * 1024, ImageSizing.PNG_MAX_BYTES)
        assertArrayEquals(intArrayOf(85, 75, 65), ImageSizing.qualitySteps.toIntArray())
    }
}
