package com.westly.nbms.features.housekeeping

/** Pure workload maths (port of Westly `lib/housekeepingBalance.ts`). */
object HousekeepingBalance {
    /** Cleaning effort of one task, by task type key. */
    val TASK_TYPE_WEIGHT: Map<String, Double> = mapOf(
        "checkout_cleaning" to 2.0,
        "occupied_service" to 1.0,
        "cleaning" to 1.0,
        "manual" to 1.0,
        "maintenance_followup" to 1.5
    )

    /** Extra effort added by a priority key. */
    val PRIORITY_WEIGHT_BONUS: Map<String, Double> = mapOf(
        "urgent" to 1.0,
        "high" to 0.5,
        "medium" to 0.0,
        "low" to 0.0
    )

    const val DEFAULT_REBALANCE_THRESHOLD = 0.25

    /** Type weight plus priority bonus; an unknown type counts 1 and an unknown priority 0. */
    fun computeTaskWeight(type: String, priority: String): Double =
        (TASK_TYPE_WEIGHT[type] ?: 1.0) + (PRIORITY_WEIGHT_BONUS[priority] ?: 0.0)
}
