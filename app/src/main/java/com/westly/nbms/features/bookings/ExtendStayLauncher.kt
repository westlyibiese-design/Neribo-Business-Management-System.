package com.westly.nbms.features.bookings

import androidx.compose.runtime.Composable

/** Phase 14 binds one (@IntoSet); the Bookings page shows "Extend Stay" only if the set is not empty. */
interface ExtendStayLauncher {
    @Composable
    fun Dialog(booking: Booking, onDismiss: () -> Unit)
}
