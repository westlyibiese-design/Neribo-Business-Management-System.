package com.westly.nbms.features.checkout

import androidx.compose.runtime.Composable
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.ExtendStayLauncher
import javax.inject.Inject
import javax.inject.Singleton

/** The Extend Stay dialog the Bookings page (Phase 12) looks for; it is bound into the launcher set in [CheckOutModule]. */
@Singleton
class ExtendStayLauncherImpl @Inject constructor() : ExtendStayLauncher {

    @Composable
    override fun Dialog(booking: Booking, onDismiss: () -> Unit) {
        ExtendStayDialog(booking = booking, onDismiss = onDismiss)
    }
}
