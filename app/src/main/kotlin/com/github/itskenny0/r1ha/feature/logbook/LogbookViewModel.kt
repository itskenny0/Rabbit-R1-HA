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
        val entries: List<LogbookEntry> = emptyList(),
        val error: String? = null,
    )

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
                        entries = entries,
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

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { LogbookViewModel(haRepository) }
        }
    }
}
