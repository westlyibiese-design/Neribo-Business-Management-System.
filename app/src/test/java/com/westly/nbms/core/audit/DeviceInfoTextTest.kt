package com.westly.nbms.core.audit

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTextTest {

    @Test
    fun makerModelAndAndroidVersionAreJoined() {
        assertEquals("Samsung SM-A135F · Android 14", deviceInfoText("Samsung", "SM-A135F", "14"))
    }

    @Test
    fun makerIsNotRepeatedWhenTheModelStartsWithIt() {
        assertEquals("Google Pixel 7 · Android 15", deviceInfoText("Google", "Google Pixel 7", "15"))
        assertEquals("samsung SM-A135F · Android 14", deviceInfoText("samsung", "samsung SM-A135F", "14"))
    }

    @Test
    fun missingPartsGetSafeFallbacks() {
        assertEquals("Unknown device · Android ?", deviceInfoText(null, null, null))
        assertEquals("Tecno · Android 13", deviceInfoText("Tecno", "", "13"))
    }
}
