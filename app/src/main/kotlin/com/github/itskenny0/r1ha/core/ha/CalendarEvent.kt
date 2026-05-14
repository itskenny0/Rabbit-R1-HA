package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable
import java.time.Instant

/**
 * One event from HA's `/api/calendars/<entity_id>` window query. Used
 * by the calendar drill-down screen.
 *
 * HA's event JSON has these fields:
 *  - summary    — event title (required)
 *  - start      — { dateTime: "..." } or { date: "YYYY-MM-DD" } for all-day
 *  - end        — same shape as start
 *  - description — long-form notes (optional)
 *  - location   — venue / address (optional)
 *
 * We coerce both date and dateTime shapes into an [Instant] (assuming
 * UTC midnight for all-day events) so the sort + display logic doesn't
 * have to branch.
 */
@Stable
data class CalendarEvent(
    val summary: String,
    val start: Instant?,
    val end: Instant?,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
)
