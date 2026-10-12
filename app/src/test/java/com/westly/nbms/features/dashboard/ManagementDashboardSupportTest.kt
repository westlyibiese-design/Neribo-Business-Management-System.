package com.westly.nbms.features.dashboard

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagementDashboardSupportTest {

    @Test fun longDateIsInTheBusinessZone() {
        assertEquals("Monday, October 12, 2026", longDateText(DASH_NOW, DASH_LAGOS))
    }

    @Test fun greetingAndSubLines() {
        assertEquals("Good day, Ada 👋", superAdminTitle("Ada"))
        assertEquals("5/10 rooms", occupancySub(5, 10))
        assertEquals("↑3 in · ↓2 out", activitySub(3, 2))
    }

    @Test fun statusBadgeTextAndColours() {
        assertEquals("checked in", statusText("checked_in"))
        assertEquals("", statusText(null))
        assertEquals("confirmed", bookingBadgeKey("confirmed"))
        assertEquals("checked_in", bookingBadgeKey("checked_in"))
        assertEquals("pending", bookingBadgeKey("pending"))
        assertEquals("out_of_service", bookingBadgeKey("cancelled"))
        assertEquals("out_of_service", bookingBadgeKey(null))
    }

    @Test fun tabletsGetFourColumnsAndPhonesTwo() {
        assertEquals(2, columnsFor(411.dp, narrow = 2, wide = 4))
        assertEquals(4, columnsFor(600.dp, narrow = 2, wide = 4))
    }

    @Test fun datesShowInTheBusinessZone() {
        // 23:30 UTC on the 11th is the 12th in Lagos
        assertEquals("12 Oct 2026", dashDate(java.time.Instant.parse("2026-10-11T23:30:00Z"), DASH_LAGOS))
        assertEquals("—", dashDate(null, DASH_LAGOS))
    }
}
