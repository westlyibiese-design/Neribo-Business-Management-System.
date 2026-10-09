package com.westly.nbms.features.housekeeping

import org.junit.Assert.assertEquals
import org.junit.Test

class HousekeepingBalanceTest {

    @Test fun typeWeightsMatchWestly() {
        assertEquals(
            mapOf(
                "checkout_cleaning" to 2.0, "occupied_service" to 1.0, "cleaning" to 1.0,
                "manual" to 1.0, "maintenance_followup" to 1.5
            ),
            HousekeepingBalance.TASK_TYPE_WEIGHT
        )
    }

    @Test fun priorityBonusesMatchWestly() {
        assertEquals(
            mapOf("urgent" to 1.0, "high" to 0.5, "medium" to 0.0, "low" to 0.0),
            HousekeepingBalance.PRIORITY_WEIGHT_BONUS
        )
    }

    @Test fun thresholdDefault() {
        assertEquals(0.25, HousekeepingBalance.DEFAULT_REBALANCE_THRESHOLD, 0.0)
    }

    @Test fun computeTaskWeightForEveryTypeAndPriority() {
        val types = mapOf("checkout_cleaning" to 2.0, "occupied_service" to 1.0, "cleaning" to 1.0, "manual" to 1.0, "maintenance_followup" to 1.5)
        val bonus = mapOf("urgent" to 1.0, "high" to 0.5, "medium" to 0.0, "low" to 0.0)
        for ((t, tw) in types) for ((p, pb) in bonus) {
            assertEquals("$t/$p", tw + pb, HousekeepingBalance.computeTaskWeight(t, p), 0.0)
        }
    }

    @Test fun unknownTypeCountsOneAndUnknownPriorityZero() {
        assertEquals(1.0, HousekeepingBalance.computeTaskWeight("mystery", "mystery"), 0.0)
        assertEquals(3.0, HousekeepingBalance.computeTaskWeight("checkout_cleaning", "urgent"), 0.0)
        assertEquals(2.0, HousekeepingBalance.computeTaskWeight("checkout_cleaning", "mystery"), 0.0)
        assertEquals(2.0, HousekeepingBalance.computeTaskWeight("mystery", "urgent"), 0.0)
    }
}
