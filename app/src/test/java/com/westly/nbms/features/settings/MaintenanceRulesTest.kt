package com.westly.nbms.features.settings

import com.westly.nbms.features.settings.models.DEFAULT_MAINTENANCE_TEXT
import com.westly.nbms.features.settings.models.DEFAULT_MAINTENANCE_TITLE
import com.westly.nbms.features.settings.models.MaintenanceSettings
import com.westly.nbms.features.settings.models.MaintenanceTarget
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceRulesTest {

    private val lagos = TimeZone.of("Africa/Lagos")

    @Test
    fun onlyAdminAndBothTurnTheStaffAppOff() {
        assertFalse(maintenanceModeFor(MaintenanceTarget.NONE))
        assertFalse(maintenanceModeFor(MaintenanceTarget.PUBLIC))
        assertTrue(maintenanceModeFor(MaintenanceTarget.ADMIN))
        assertTrue(maintenanceModeFor(MaintenanceTarget.BOTH))
    }

    @Test
    fun labelsMatchTheSpec() {
        assertEquals("Disabled", targetLabel(MaintenanceTarget.NONE))
        assertEquals("Public Website Only", targetLabel(MaintenanceTarget.PUBLIC))
        assertEquals("Admin System Only", targetLabel(MaintenanceTarget.ADMIN))
        assertEquals("Both", targetLabel(MaintenanceTarget.BOTH))
        assertEquals("Disabled", targetLabel("something-else"))
    }

    @Test
    fun estimatedReturnRoundTripsInTheBusinessTimeZone() {
        val date = LocalDate(2026, 10, 9)
        val time = LocalTime(14, 30)
        val iso = estimatedReturnIso(date, time, lagos)
        assertEquals("2026-10-09T13:30:00Z", iso) // Lagos is UTC+1
        val back = splitEstimatedReturn(iso, lagos)
        assertEquals(date to time, back)
    }

    @Test
    fun noReturnTimeGivesNull() {
        assertNull(estimatedReturnIso(null, null, lagos))
        assertNull(estimatedReturnIso(LocalDate(2026, 10, 9), null, lagos))
        assertNull(splitEstimatedReturn(null, lagos))
        assertNull(splitEstimatedReturn("not a date", lagos))
    }

    @Test
    fun halfAReturnTimeIsRejected() {
        assertEquals(MSG_RETURN_BOTH, validateReturn(MaintenanceForm(returnDate = LocalDate(2026, 10, 9))))
        assertEquals(MSG_RETURN_BOTH, validateReturn(MaintenanceForm(returnTime = LocalTime(9, 0))))
        assertNull(validateReturn(MaintenanceForm()))
        assertNull(validateReturn(MaintenanceForm(returnDate = LocalDate(2026, 10, 9), returnTime = LocalTime(9, 0))))
    }

    @Test
    fun blankTitleAndMessageFallBackToTheDefaults() {
        val f = MaintenanceForm(title = "  ", message = "")
        assertEquals(DEFAULT_MAINTENANCE_TITLE, cleanTitle(f))
        assertEquals(DEFAULT_MAINTENANCE_TEXT, cleanMessage(f))
        val fields = businessMaintenanceFields(f.copy(target = MaintenanceTarget.BOTH))
        assertEquals(true, fields["maintenanceMode"])
        assertEquals(DEFAULT_MAINTENANCE_TEXT, fields["maintenanceMessage"])
        assertEquals(setOf("maintenanceMode", "maintenanceMessage"), fields.keys)
    }

    @Test
    fun savedDocumentHasTheAgreedFields() {
        val m = maintenanceDocMap(
            MaintenanceForm(target = MaintenanceTarget.ADMIN, title = "Back soon", message = "Hi"),
            lagos, "SERVER_TIME", "u1", "Ada"
        )
        assertEquals(
            setOf("target", "title", "message", "estimatedReturn", "imageUrl", "updatedAt", "updatedBy", "updatedByName"),
            m.keys
        )
        assertEquals("admin", m["target"])
        assertEquals("Back soon", m["title"])
        assertNull(m["estimatedReturn"])
        assertNull(m["imageUrl"])
        assertEquals("Ada", m["updatedByName"])
    }

    @Test
    fun saveIsOnlyOfferedWhenSomethingChanged() {
        val saved = formFromSaved(MaintenanceSettings(), lagos)
        assertFalse(isDirty(saved, saved))
        assertTrue(isDirty(saved.copy(target = MaintenanceTarget.BOTH), saved))
        assertTrue(isDirty(saved.copy(title = "Other"), saved))
        // A blank title counts as the default, so clearing the default text is not a change.
        assertFalse(isDirty(saved.copy(title = ""), saved))
        assertTrue(isDirty(saved.copy(returnDate = LocalDate(2026, 10, 9), returnTime = LocalTime(9, 0)), saved))
    }

    @Test
    fun unknownSavedTargetCountsAsDisabled() {
        assertEquals(MaintenanceTarget.NONE, formFromSaved(MaintenanceSettings(target = "weird"), lagos).target)
    }
}
