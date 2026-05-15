package com.github.itskenny0.r1ha.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.PersistentNotification
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Drives the Today dashboard — a single at-a-glance home screen
 * composed of: outdoor weather, persons home/away, the next calendar
 * event, camera count, and HA notification count.
 *
 * All five sections come from `/api/states` (filtered by domain prefix)
 * via [HaRepository.listRawEntitiesByDomain] in parallel. The user
 * sees partial data as soon as the first section lands — sections
 * that fail their fetch surface as "—" rather than blowing up the
 * whole dashboard.
 */
class DashboardViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class WeatherSummary(
        val name: String,
        val condition: String,
        val temperature: Double?,
        val temperatureUnit: String?,
    )

    @androidx.compose.runtime.Stable
    data class PersonsSummary(
        val homeCount: Int,
        val awayCount: Int,
        /** Each entry is "name → state". Limited to 6 for layout. */
        val rows: List<Pair<String, String>>,
    )

    @androidx.compose.runtime.Stable
    data class CalendarSummary(
        val calendarName: String,
        val eventTitle: String,
        val eventStart: Instant?,
        val happeningNow: Boolean,
    )

    @androidx.compose.runtime.Stable
    data class SunSummary(
        val state: String,
        val elevation: Double?,
        val nextRising: Instant?,
        val nextSetting: Instant?,
    )

    @androidx.compose.runtime.Stable
    data class MediaSummary(
        val entityId: String,
        val name: String,
        val state: String,
        val title: String?,
        val artist: String?,
    )

    @androidx.compose.runtime.Stable
    data class TimerSummary(
        val entityId: String,
        val name: String,
        val state: String, // active / paused / idle
        val finishesAt: Instant?,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val weather: WeatherSummary? = null,
        val persons: PersonsSummary? = null,
        val nextEvent: CalendarSummary? = null,
        val sun: SunSummary? = null,
        val cameraCount: Int = 0,
        val notifications: List<PersistentNotification> = emptyList(),
        /** Currently-playing or paused media players. Limited to 3 on
         *  the dashboard to keep the surface scannable. */
        val media: List<MediaSummary> = emptyList(),
        /** Currently-active (or paused) HA timer.* entities. Empty means
         *  no timers running. */
        val timers: List<TimerSummary> = emptyList(),
        /** Count of light.* entities currently in state='on'. -1 sentinel
         *  for "not loaded yet" so the UI can render '—' rather than 0. */
        val lightsOnCount: Int = -1,
        /** Total real-time power consumption from every sensor.* with
         *  device_class='power', in Watts. -1 sentinel for "not loaded
         *  yet" / "no power sensors". */
        val totalPowerW: Int = -1,
        /** List of "<entity_id>=<pct>" for every battery sensor under
         *  20%. Empty list means all batteries healthy. */
        val lowBatteries: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val weatherJob = async { haRepository.listRawEntitiesByDomain("weather") }
                val personJob = async { haRepository.listRawEntitiesByDomain("person") }
                val calendarJob = async { haRepository.listRawEntitiesByDomain("calendar") }
                val cameraJob = async { haRepository.listRawEntitiesByDomain("camera") }
                val notifJob = async { haRepository.listPersistentNotifications() }
                val sunJob = async { haRepository.listRawEntitiesByDomain("sun") }
                val mediaJob = async { haRepository.listRawEntitiesByDomain("media_player") }
                val timerJob = async { haRepository.listRawEntitiesByDomain("timer") }
                // Lightweight server-side count rather than transporting
                // every light entity's full row. The integer comes back
                // as plain text body from /api/template.
                val lightsJob = async {
                    haRepository.renderTemplate(
                        "{{ states.light | selectattr('state','eq','on') | list | count }}",
                    )
                }
                // Sum power-class sensor states. The rejectattr guards
                // against 'unavailable' / 'unknown' rows which would fail
                // the float() coercion. round() to whole watts because
                // the dashboard tile shows an integer.
                val powerJob = async {
                    haRepository.renderTemplate(
                        "{{ states.sensor " +
                            "| selectattr('attributes.device_class','eq','power') " +
                            "| rejectattr('state','in',['unavailable','unknown']) " +
                            "| map(attribute='state') | map('float',0) | sum | round(0) | int }}",
                    )
                }
                // Low-battery list: every battery-class sensor under 20%.
                // Builds a JSON array of "<entity_id>=<pct>" strings so
                // we can parse it back client-side.
                val batteryJob = async {
                    haRepository.renderTemplate(
                        "{%- set out = namespace(items=[]) -%}" +
                            "{%- for s in states.sensor " +
                            "| selectattr('attributes.device_class','eq','battery') " +
                            "| rejectattr('state','in',['unavailable','unknown']) -%}" +
                            "{%- set pct = s.state | float(101) -%}" +
                            "{%- if pct < 20 -%}" +
                            "{%- set _ = out.items.append(s.entity_id ~ '=' ~ (pct | int)) -%}" +
                            "{%- endif -%}" +
                            "{%- endfor -%}" +
                            "{{ out.items | tojson }}",
                    )
                }
                awaitAll(weatherJob, personJob, calendarJob, cameraJob, notifJob, sunJob, mediaJob, timerJob, lightsJob, powerJob, batteryJob)
                val weather = weatherJob.await().getOrNull()?.firstOrNull()?.let { row ->
                    WeatherSummary(
                        name = row.friendlyName,
                        condition = row.state,
                        temperature = (row.attributes["temperature"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                        temperatureUnit = (row.attributes["temperature_unit"] as? JsonPrimitive)?.content,
                    )
                }
                val persons = personJob.await().getOrNull()?.let { rows ->
                    val homeCount = rows.count { it.state == "home" }
                    val awayCount = rows.count { it.state == "not_home" || it.state == "away" }
                    PersonsSummary(
                        homeCount = homeCount,
                        awayCount = awayCount,
                        rows = rows.sortedBy { it.friendlyName.lowercase() }
                            .take(6)
                            .map { it.friendlyName to it.state },
                    )
                }
                // Next event: pick the earliest start_time across all
                // calendar entities, or the first happening-now (state=on)
                // if one exists.
                val nextEvent = calendarJob.await().getOrNull()?.let { rows ->
                    val parsed = rows.mapNotNull { row ->
                        val title = (row.attributes["message"] as? JsonPrimitive)?.content
                            ?: return@mapNotNull null
                        val startRaw = (row.attributes["start_time"] as? JsonPrimitive)?.content
                        val start = startRaw?.let { runCatching { Instant.parse(it.replace(' ', 'T') + "Z") }.getOrNull() }
                        CalendarSummary(
                            calendarName = row.friendlyName,
                            eventTitle = title,
                            eventStart = start,
                            happeningNow = row.state == "on",
                        )
                    }
                    parsed.firstOrNull { it.happeningNow }
                        ?: parsed.minByOrNull { it.eventStart ?: Instant.MAX }
                }
                val cameras = cameraJob.await().getOrNull().orEmpty()
                val notifs = notifJob.await().getOrNull().orEmpty()
                val media = mediaJob.await().getOrNull()
                    ?.filter { it.state in setOf("playing", "paused", "buffering") }
                    ?.map { row ->
                        MediaSummary(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            state = row.state,
                            title = (row.attributes["media_title"] as? JsonPrimitive)?.content,
                            artist = (row.attributes["media_artist"] as? JsonPrimitive)?.content,
                        )
                    }
                    ?.sortedByDescending { it.state == "playing" }
                    ?.take(3)
                    .orEmpty()
                val lightsOn = lightsJob.await().getOrNull()?.trim()?.toIntOrNull() ?: -1
                val totalPower = powerJob.await().getOrNull()?.trim()?.toIntOrNull() ?: -1
                val lowBatteries = batteryJob.await().getOrNull()?.let { raw ->
                    runCatching {
                        val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                            as? kotlinx.serialization.json.JsonArray
                        arr?.mapNotNull { (it as? JsonPrimitive)?.content }
                    }.getOrNull()
                }.orEmpty()
                val timers = timerJob.await().getOrNull()
                    ?.filter { it.state == "active" || it.state == "paused" }
                    ?.map { row ->
                        TimerSummary(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            state = row.state,
                            finishesAt = (row.attributes["finishes_at"] as? JsonPrimitive)?.content
                                ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        )
                    }
                    ?.sortedBy { it.finishesAt ?: Instant.MAX }
                    .orEmpty()
                val sun = sunJob.await().getOrNull()?.firstOrNull()?.let { row ->
                    SunSummary(
                        state = row.state,
                        elevation = (row.attributes["elevation"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                        nextRising = (row.attributes["next_rising"] as? JsonPrimitive)?.content
                            ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        nextSetting = (row.attributes["next_setting"] as? JsonPrimitive)?.content
                            ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    )
                }
                R1Log.i(
                    "Dashboard",
                    "weather=${weather != null} persons=${persons?.rows?.size ?: 0} " +
                        "nextEvent=${nextEvent != null} cameras=${cameras.size} notifs=${notifs.size}",
                )
                _ui.value = _ui.value.copy(
                    loading = false,
                    weather = weather,
                    persons = persons,
                    nextEvent = nextEvent,
                    sun = sun,
                    cameraCount = cameras.size,
                    notifications = notifs,
                    media = media,
                    timers = timers,
                    lightsOnCount = lightsOn,
                    totalPowerW = totalPower,
                    lowBatteries = lowBatteries,
                    error = null,
                )
            } catch (t: Throwable) {
                R1Log.w("Dashboard", "refresh failed: ${t.message}")
                _ui.value = _ui.value.copy(loading = false, error = t.message)
            }
        }
    }

    /** Transport dispatch for the on-dashboard media card. Uses the
     *  existing ServiceCall.mediaTransport helper + haRepository.call
     *  WS path so the dispatch is debounced + coalesced like every
     *  other service call in the app. Triggers an immediate dashboard
     *  refresh after a short settle delay so the play/pause state
     *  reflects the new reality without waiting for the next 60 s
     *  auto-refresh tick — makes the buttons feel responsive. */
    fun mediaTransport(
        entityId: String,
        action: com.github.itskenny0.r1ha.core.ha.MediaTransport,
    ) {
        viewModelScope.launch {
            val target = runCatching {
                com.github.itskenny0.r1ha.core.ha.EntityId(entityId)
            }.getOrNull() ?: return@launch
            haRepository.call(
                com.github.itskenny0.r1ha.core.ha.ServiceCall.mediaTransport(target, action),
            )
            // 800 ms settle delay — HA needs a moment for the media
            // entity to report its new state after a play/pause. Faster
            // and we'd refresh on the pre-action state and have to
            // refresh again on the next tick.
            kotlinx.coroutines.delay(800L)
            refresh()
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { DashboardViewModel(haRepository) }
        }
    }
}
