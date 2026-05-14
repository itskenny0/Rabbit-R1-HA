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
    data class UiState(
        val loading: Boolean = true,
        val weather: WeatherSummary? = null,
        val persons: PersonsSummary? = null,
        val nextEvent: CalendarSummary? = null,
        val sun: SunSummary? = null,
        val cameraCount: Int = 0,
        val notifications: List<PersistentNotification> = emptyList(),
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
                awaitAll(weatherJob, personJob, calendarJob, cameraJob, notifJob, sunJob)
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
                    error = null,
                )
            } catch (t: Throwable) {
                R1Log.w("Dashboard", "refresh failed: ${t.message}")
                _ui.value = _ui.value.copy(loading = false, error = t.message)
            }
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { DashboardViewModel(haRepository) }
        }
    }
}
