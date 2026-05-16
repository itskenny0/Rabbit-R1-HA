package com.github.itskenny0.r1ha.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the History drill-in surface — a full-screen view of one
 * entity's recent state-change history, fetched from HA's
 * `/api/history/period/...` REST endpoint.
 *
 * The card stack's per-entity sparkline gives a 72-dp glimpse at 24 h
 * of history; this VM backs the much larger view a user gets when
 * they explicitly drill into an entity to investigate it — bigger
 * chart, configurable time window (1 h / 6 h / 24 h / 7 d), and
 * numerical readouts of min/max/avg/current.
 */
class HistoryViewModel(
    private val haRepository: HaRepository,
    private val entityId: EntityId,
) : ViewModel() {

    /** Time-window selector — the chips at the top of the screen flip
     *  between these. Each value is the number of hours to pull from
     *  HA's history endpoint. */
    enum class Window(val hours: Int, val label: String) {
        H1(1, "1H"),
        H6(6, "6H"),
        H24(24, "24H"),
        D7(7 * 24, "7D"),
    }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val window: Window = Window.H24,
        val points: List<HistoryPoint> = emptyList(),
        /** Friendly name for the title bar — pulled out of the first
         *  point's metadata or the entity_id if the history is empty. */
        val displayName: String = "",
        /** Numeric summary across the loaded window. Null when the
         *  entity isn't numeric (text sensors, enum sensors, etc.). */
        val min: Double? = null,
        val max: Double? = null,
        val avg: Double? = null,
        val current: String? = null,
        val unit: String? = null,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState(displayName = entityId.value))
    val ui: StateFlow<UiState> = _ui

    fun setWindow(w: Window) {
        if (_ui.value.window == w) return
        _ui.value = _ui.value.copy(window = w)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val w = _ui.value.window
            haRepository.fetchHistory(entityId, hours = w.hours).fold(
                onSuccess = { points ->
                    val numeric = points.mapNotNull { it.numeric }
                    val min = numeric.minOrNull()
                    val max = numeric.maxOrNull()
                    val avg = if (numeric.isNotEmpty()) numeric.sum() / numeric.size else null
                    val current = points.lastOrNull()?.state
                    // Fetch friendly name + unit from /api/states once.
                    // History API doesn't include attributes, so we
                    // grab them from the live state for the title bar
                    // and the y-axis unit label.
                    val live = haRepository.listAllEntities().getOrNull()
                        ?.firstOrNull { it.id == entityId }
                    val name = live?.friendlyName ?: entityId.value
                    val unit = live?.unit
                    R1Log.i(
                        "History",
                        "${entityId.value} window=${w.label} points=${points.size} " +
                            "numeric=${numeric.size}",
                    )
                    _ui.value = _ui.value.copy(
                        loading = false,
                        points = points,
                        displayName = name,
                        min = min,
                        max = max,
                        avg = avg,
                        current = current,
                        unit = unit,
                        error = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("History", "${entityId.value} fetch failed: ${t.message}")
                    Toaster.error("History load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository, entityId: EntityId) = viewModelFactory {
            initializer { HistoryViewModel(haRepository, entityId) }
        }
    }
}
