package com.westly.nbms.core.util

import com.google.firebase.Timestamp
import kotlinx.datetime.Instant

/** Converts a Firestore Timestamp to a kotlinx-datetime Instant (null stays null). */
fun Timestamp?.toInstant(): Instant? =
    this?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds.toLong()) }
