package com.westly.nbms.features.bookings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.nbms

/** Status pill: pending yellow, confirmed green, checked in blue, checked out gray, cancelled/rejected red, no show orange. */
@Composable
fun BookingStatusBadge(status: String, modifier: Modifier = Modifier) {
    NbmsPill(
        text = statusTitle(status),
        colors = MaterialTheme.nbms.statusPill(status),
        modifier = modifier
    )
}
