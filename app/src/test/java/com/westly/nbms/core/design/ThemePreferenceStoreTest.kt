package com.westly.nbms.core.design

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceStoreTest {
    @Test fun savedKeysMapToModes() {
        assertEquals(ThemeMode.Light, themeModeFromKey("light"))
        assertEquals(ThemeMode.Dark, themeModeFromKey("dark"))
        assertEquals(ThemeMode.System, themeModeFromKey("system"))
    }

    @Test fun missingOrUnknownMeansSystem() {
        assertEquals(ThemeMode.System, themeModeFromKey(null))
        assertEquals(ThemeMode.System, themeModeFromKey("sepia"))
    }
}
