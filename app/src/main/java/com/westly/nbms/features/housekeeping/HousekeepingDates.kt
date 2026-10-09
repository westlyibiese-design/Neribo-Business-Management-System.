package com.westly.nbms.features.housekeeping

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY_KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** The calendar day of [instant] in [zoneId] as "yyyy-MM-dd" (the `dayKey` of a housekeeping task). */
fun dateKeyInZone(instant: Instant, zoneId: ZoneId): String =
    DAY_KEY_FORMAT.format(instant.atZone(zoneId).toLocalDate())
