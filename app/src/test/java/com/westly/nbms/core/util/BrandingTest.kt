package com.westly.nbms.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BrandingTest {

    @Test fun appName_hasNoSpaces() {
        assertEquals("NeriboBMS", Branding.APP_NAME)
    }

    @Test fun copyright_text() {
        assertEquals("\u00A9 Neribo Group", Branding.COPYRIGHT)
    }

    @Test fun csv_getsCopyrightAsLastRow() {
        assertEquals("a,b\n1,2\n\u00A9 Neribo Group\n", Branding.csvWithCopyright("a,b\n1,2"))
    }

    @Test fun csv_trailingNewlinesAreNotDoubled() {
        assertEquals("a,b\n\u00A9 Neribo Group\n", Branding.csvWithCopyright("a,b\r\n\n"))
    }
}
