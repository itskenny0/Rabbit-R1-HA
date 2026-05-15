package com.github.itskenny0.r1ha.feature.calendars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Drives the Calendars surface. For each `calendar.*` entity HA
 * exposes, surfaces the entity state ("on" = an event is happening
 * right now, "off" = next event is in the future) plus the upcoming
 * event's title, location, start time and end time from the
 * attributes object.
 *
 * Full event-list browsing (HA's `/api/calendars/<id>?start=...`
 * endpoint) is a richer follow-up; this is the
 * "what's-on-the-agenda-next" surface that maps onto an at-a-glance
 * R1 view.
 */
class CalendarsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Calendar(
        val entityId: String,
        val name: String,
        /** "on" if there's an event happening right now, "off" otherwise. */
        val state: String,
        val eventMessage: String?,
        val eventLocation: String?,
        val eventStart: Instant?,
        val eventEnd: Instant?,
        val eventDescription: String?,
        /** All-day event detected via start_time being a bare YYYY-MM-DD
         *  (length ≤ 10, no time component). The UI tags these with an
         *  ALL-DAY pill instead of a "in 2 h" countdown that would be
         *  misleading for events without a specific start time. */
        val allDay: Boolean,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val calendars: List<Calendar> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listRawEntitiesByDomain("calendar").fold(
                onSuccess = { rows ->
                    val list = rows.map { row ->
                        val attrs = row.attributes
                        val startRaw = (attrs["start_time"] as? JsonPrimitive)?.content
                        Calendar(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            state = row.state,
                            eventMessage = (attrs["message"] as? JsonPrimitive)?.content,
                            eventLocation = (attrs["location"] as? JsonPrimitive)?.content,
                            eventStart = startRaw?.let { parseLooseTime(it) },
                            eventEnd = (attrs["end_time"] as? JsonPrimitive)?.content
                                ?.let { parseLooseTime(it) },
                            eventDescription = (attrs["description"] as? JsonPrimitive)?.content,
                            allDay = startRaw != null && startRaw.length <= 10,
                        )
                    }
                    // Currently-happening calendars first (state=on), then
                    // by next start time, then alphabetical.
                    val sorted = list.sortedWith(
                        compareByDescending<Calendar> { it.state == "on" }
                            .thenBy { it.eventStart ?: Instant.MAX }
                            .thenBy { it.name.lowercase() },
                    )
                    R1Log.i("Calendars", "loaded ${sorted.size}")
                    _ui.value = _ui.value.copy(loading = false, calendars = sorted, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Calendars", "list failed: ${t.message}")
                    Toaster.error("Calendars load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    /** HA's calendar attributes use a mix of "YYYY-MM-DD HH:MM:SS" (local)
     *  and ISO-8601 with offset depending on the integration. Try both
     *  rather than picking one; null on parse failure. */
    private fun parseLooseTime(raw: String): Instant? {
        // Try strict ISO-8601 first.
        runCatching { return Instant.parse(raw) }
        // "2026-05-15T08:30:00" (local, no offset) — assume UTC for sort
        // purposes; the UI displays a relative timestamp anyway, so the
        // offset doesn't bite us.
        runCatching {
            val normalised = if (raw.contains('T')) raw else raw.replace(' ', 'T')
            return Instant.parse(normalised + "Z")
        }
        return null
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { CalendarsViewModel(haRepository) }
        }
    }
}
