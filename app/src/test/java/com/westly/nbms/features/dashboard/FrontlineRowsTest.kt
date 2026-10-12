package com.westly.nbms.features.dashboard

import com.google.firebase.Timestamp
import com.westly.nbms.core.data.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FrontlineRowsTest {

    @Test fun rowsHaveDefaultsOnEveryField() {
        assertEquals(AmountRow(0.0, null, null), AmountRow())
        assertEquals(OrderRow(null, 0.0, null), OrderRow())
        assertEquals(FrontlineLaundryRow(null, null), FrontlineLaundryRow())
        assertEquals(HkTaskRow(null, null, null), HkTaskRow())
    }

    @Test fun amountRowMapsTimestampAndKeepsApprovalStatus() {
        val row = RawAmountRow(total = 1500.5, createdAt = Timestamp(1_700_000_000L, 0), approvalStatus = "pending").toRowOrNull()
        assertEquals(AmountRow(1500.5, Instant.ofEpochSecond(1_700_000_000L), "pending"), row)
    }

    @Test fun deletedRecordsMapToNull() {
        assertNull(RawAmountRow(total = 1.0, isDeleted = true).toRowOrNull())
        assertNull(RawOrderRow(status = "pending", isDeleted = true).toRowOrNull())
        assertNull(RawFrontlineLaundryRow(status = "ready", isDeleted = true).toRowOrNull())
        assertNull(RawHkTaskRow(status = "pending", isDeleted = true).toRowOrNull())
    }

    @Test fun orderRowMapsStatusTotalAndDate() {
        val row = RawOrderRow(status = "served", total = 900.0, createdAt = Timestamp(1_700_000_100L, 0)).toRowOrNull()
        assertEquals(OrderRow("served", 900.0, Instant.ofEpochSecond(1_700_000_100L)), row)
    }

    @Test fun laundryRowMapsDeliveredAt() {
        val row = RawFrontlineLaundryRow(status = "delivered", deliveredAt = Timestamp(1_700_000_200L, 0)).toRowOrNull()
        assertEquals(FrontlineLaundryRow("delivered", Instant.ofEpochSecond(1_700_000_200L)), row)
        assertEquals(FrontlineLaundryRow("received", null), RawFrontlineLaundryRow(status = "received").toRowOrNull())
    }

    @Test fun housekeepingRowMapsFields() {
        val row = RawHkTaskRow(roomNumber = "204", priority = "urgent", status = "in_progress").toRowOrNull()
        assertEquals(HkTaskRow("204", "urgent", "in_progress"), row)
    }

    @Test fun onlyActiveUndeletedAssignmentsAreCounted() {
        val rows = listOf(
            RawRoomAssignmentRow(status = "active"), RawRoomAssignmentRow(status = "active"),
            RawRoomAssignmentRow(status = "ended"), RawRoomAssignmentRow(status = null),
            RawRoomAssignmentRow(status = "active", isDeleted = true)
        )
        assertEquals(2, countActiveAssignments(rows))
        assertEquals(0, countActiveAssignments(emptyList()))
    }

    @Test fun housekeepingQueueKeepsPendingAndInProgressOnly() {
        assertTrue(isQueued(HkTaskRow(status = "pending")))
        assertTrue(isQueued(HkTaskRow(status = "in_progress")))
        assertEquals(false, isQueued(HkTaskRow(status = "completed")))
        assertEquals(false, isQueued(HkTaskRow(status = "skipped")))
        assertEquals(false, isQueued(HkTaskRow(status = null)))
    }

    @Test fun mapRowsNotNullDropsNullsAndPassesLoadingAndErrorThrough() {
        val ok: Resource<List<RawOrderRow>> = Resource.Success(
            listOf(RawOrderRow(status = "pending"), RawOrderRow(status = "x", isDeleted = true))
        )
        val mapped = ok.mapRowsNotNull { it.toRowOrNull() }
        assertEquals(Resource.Success(listOf(OrderRow("pending", 0.0, null))), mapped)
        val loading: Resource<List<RawOrderRow>> = Resource.Loading
        assertEquals(Resource.Loading, loading.mapRowsNotNull { it.toRowOrNull() })
        val err: Resource<List<RawOrderRow>> = Resource.Error("boom")
        assertEquals(err, err.mapRowsNotNull { it.toRowOrNull() })
    }
}
