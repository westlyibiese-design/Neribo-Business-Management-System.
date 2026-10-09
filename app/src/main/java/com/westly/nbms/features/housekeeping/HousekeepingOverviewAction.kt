package com.westly.nbms.features.housekeeping

import androidx.compose.runtime.Composable
import com.westly.nbms.core.session.SessionState

/**
 * Seam for Phase 24 (section 2.3.2). Phase 24 binds one more action by adding a file; the management Overview (Part 23B)
 * shows every bound action to the left of its Room Assignments button.
 */
fun interface HousekeepingOverviewAction {
    @Composable
    fun Content(session: SessionState.SignedIn)
}
