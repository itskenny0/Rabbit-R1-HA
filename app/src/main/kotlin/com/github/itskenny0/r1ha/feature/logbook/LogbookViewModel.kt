package com.github.itskenny0.r1ha.feature.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.LogbookEntry
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Logbook (Recent Activity) surface. Pulls
 * `/api/logbook/<since>` and surfaces the result as a reverse-
 * chronological list with a single PULL-TO-REFRESH affordance.
 *
 * The 12-hour default window catches "what did the automations do
 * overnight?" without slurping a multi-megabyte payload on big HA
 * installs; the user can extend it via the WINDOW chip (12 h / 24 h /
 * 3 d) at the top of the screen.
 */
class LogbookViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    enum class Window(val hours: Int, val label: String) {
        H12(12, "12 H"),
        H24(24, "24 H"),
        D3(72, "3 D"),
    }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val window: Window = Window.H12,
        /** Full set of entries from the last fetch. [visibleEntries] applies
         *  the search filter on top so we don't have to re-fetch from HA on
         *  every keystroke. */
        val all: List<LogbookEntry> = emptyList(),
        val query: String = "",
        val error: String? = null,
    ) {
        /** Filtered subset shown in the list. Substring-matches case-
         *  insensitively against the event name, message and entity_id. */
        val entries: List<LogbookEntry> get() {
            if (query.isBlank()) return all
            val q = query.trim().lowercase()
            return all.filter { e ->
                e.name.lowercase().contains(q) ||
                    e.message.lowercase().contains(q) ||
                    (e.entityId?.value?.lowercase()?.contains(q) ?: false)
            }
        }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val window = _ui.value.window
            haRepository.fetchLogbook(hours = window.hours).fold(
                onSuccess = { entries ->
                    R1Log.i("Logbook", "loaded ${entries.size} entries (${window.label})")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        all = entries,
                        error = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Logbook", "fetch failed: ${t.message}")
                    Toaster.error("Logbook load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        error = t.message ?: "Failed to load logbook",
                    )
                },
            )
        }
    }

    fun setWindow(window: Window) {
        if (_ui.value.window == window) return
        _ui.value = _ui.value.copy(window = window)
        refresh()
    }

    fun setQuery(query: String) {
        if (_ui.value.query == query) return
        _ui.value = _ui.value.copy(query = query)
    }

    /**
     * Surface the full event detail as a long-form toast — entity_id,
     * state and message, plus the absolute timestamp. The relative
     * timestamp on the row is fine for "how recent" but a user trying
     * to correlate with an HA automation needs the absolute time.
     *
     * Tap is the natural drilldown affordance even though the row
     * itself doesn't navigate anywhere — putting the toast on the
     * ToastHost's expand-on-tap path means the user can read a long
     * automation trigger message without it being clipped.
     */
    fun showDetail(entry: LogbookEntry) {
        val short = entry.entityId?.value ?: entry.name
        val full = buildString {
            append(entry.name).append('\n')
            if (entry.entityId != null) {
                append(entry.entityId.value).append('\n')
            }
            append(entry.message).append('\n')
            if (entry.state != null) append("→ ").append(entry.state).append('\n')
            // Absolute wall-clock so the user can scroll back to find what HA
            // triggered when at, e.g. "did the alarm fire at the right time?"
            append(entry.timestamp.toString())
        }
        Toaster.showExpandable(shortText = short, fullText = full)
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { LogbookViewModel(haRepository) }
        }
    }
}
