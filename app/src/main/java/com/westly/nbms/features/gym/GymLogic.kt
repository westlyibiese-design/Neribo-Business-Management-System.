package com.westly.nbms.features.gym

import com.google.firebase.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Firestore Timestamp as a `java.time.Instant` (null stays null). */
internal fun Timestamp?.toGymInstant(): Instant? = this?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }

/** A `java.time.Instant` as a Firestore Timestamp. */
internal fun Instant.toGymTimestamp(): Timestamp = Timestamp(epochSecond, nano)

/**
 * The pure membership rules of Westly's `lib/gym.ts`. Public: the next phase (Gym Reports) uses them too.
 * Nothing here touches the database or the clock unless a `now` default is used.
 */
object GymLogic {

    private const val DAY_MILLIS = 86_400_000.0
    private const val DEFAULT_ZONE = "Africa/Lagos"
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

    /**
     * What the person should see: `suspended` and `cancelled` always win; otherwise an end date in the past is `expired`
     * (even if nobody edited the record); otherwise a stored `expired` becomes `active` (a renewed membership), and anything
     * else stays as stored. An unknown stored value counts as `active`.
     */
    fun effectiveStatus(member: GymMember, now: Instant = Instant.now()): MembershipStatus =
        effectiveStatus(member.status, member.endDate.toGymInstant(), now)

    fun effectiveStatus(storedStatus: String?, endDate: Instant?, now: Instant = Instant.now()): MembershipStatus {
        val stored = MembershipStatus.fromKey(storedStatus) ?: MembershipStatus.ACTIVE
        if (stored == MembershipStatus.SUSPENDED || stored == MembershipStatus.CANCELLED) return stored
        if (endDate != null && endDate.isBefore(now)) return MembershipStatus.EXPIRED
        return if (stored == MembershipStatus.EXPIRED) MembershipStatus.ACTIVE else stored
    }

    /** `ceil((end − now) / 1 day)`; null when there is no end date; negative once the end date is more than a day past. */
    fun daysUntilExpiry(endDate: Instant?, now: Instant = Instant.now()): Int? {
        if (endDate == null) return null
        val diffMillis = endDate.toEpochMilli() - now.toEpochMilli()
        return Math.ceil(diffMillis / DAY_MILLIS).toInt()
    }

    /**
     * Days in a package, from its free-text label (case ignored): "year" or "annual" is 365, "quarter" 90, "week" 7,
     * "day" 1, and anything else (including "Monthly" and blank) is 30.
     */
    fun durationToDays(label: String?): Int {
        val text = label.orEmpty().lowercase()
        return when {
            text.contains("year") || text.contains("annual") -> 365
            text.contains("quarter") -> 90
            text.contains("week") -> 7
            text.contains("day") -> 1
            else -> 30
        }
    }

    /** The new end date of a renewal: the current end date when it is still in the future (early renewals keep the remaining days), otherwise [now]; plus [durationDays] × 24 h. */
    fun renewalEnd(currentEnd: Instant?, now: Instant, durationDays: Int): Instant {
        val base = if (currentEnd != null && currentEnd.isAfter(now)) currentEnd else now
        return base.plusSeconds(durationDays.toLong() * 24L * 3_600L)
    }

    /** "—" when either time is missing; "{m} min" under an hour; otherwise "{h}h {m}m". */
    fun visitDurationLabel(checkIn: Instant?, checkOut: Instant?): String {
        if (checkIn == null || checkOut == null) return "—"
        val minutes = ((checkOut.epochSecond - checkIn.epochSecond) / 60L).coerceAtLeast(0L)
        return if (minutes < 60L) "$minutes min" else "${minutes / 60L}h ${minutes % 60L}m"
    }

    /** "yyyy-MM-dd" of [instant] in the business [timezone] (never UTC). An unknown zone falls back to Africa/Lagos. */
    fun dateKey(instant: Instant, timezone: String): String = instant.atZone(zoneOf(timezone)).toLocalDate().toString()

    /** "HH:mm" of [instant] in the business [timezone]; "—" when there is no time. */
    fun timeLabel(instant: Instant?, timezone: String): String =
        if (instant == null) "—" else TIME_FORMAT.format(instant.atZone(zoneOf(timezone)))

    /** "12 Mar 2025" of [instant] in the business [timezone]; "—" when there is no date. */
    fun dateLabel(instant: Instant?, timezone: String): String =
        if (instant == null) "—" else DATE_FORMAT.format(instant.atZone(zoneOf(timezone)))

    internal fun zoneOf(timezone: String): ZoneId = try {
        ZoneId.of(timezone)
    } catch (e: Exception) {
        ZoneId.of(DEFAULT_ZONE)
    }
}
