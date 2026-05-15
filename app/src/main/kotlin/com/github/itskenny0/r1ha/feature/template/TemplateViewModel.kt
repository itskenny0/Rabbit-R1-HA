package com.github.itskenny0.r1ha.feature.template

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the Templates surface — a Jinja2 evaluator backed by HA's
 * `/api/template`. Holds the editable template, the last result (or
 * error), and an in-flight flag so a slow render doesn't spawn racing
 * fetches if the user mashes RENDER.
 *
 * Why this lives in the app: HA ships a template editor in its
 * frontend, but reaching it from the R1 means context-switching to a
 * desktop. Iterating a template ("{{ states.sun.sun.state }}" → "what
 * about elevation?") in the same surface as the rest of HA control
 * keeps the feedback loop tight.
 */
class TemplateViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
) : ViewModel() {

    @Volatile
    private var historyDepth: Int = 5

    @androidx.compose.runtime.Stable
    data class UiState(
        val template: String = """{{ now().isoformat() }}""",
        val rendered: String = "",
        val error: String? = null,
        val inFlight: Boolean = false,
        /** Last 5 successfully-rendered templates, newest first. In-memory
         *  ViewModel state — clears on app restart by design (so a stale
         *  syntactically-incorrect template from yesterday doesn't haunt
         *  today's session). */
        val recent: List<String> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun setTemplate(value: String) {
        _ui.value = _ui.value.copy(template = value)
    }

    fun clearRecent() {
        _ui.value = _ui.value.copy(recent = emptyList())
    }

    fun render() {
        val template = _ui.value.template
        if (template.isBlank() || _ui.value.inFlight) return
        _ui.value = _ui.value.copy(inFlight = true, error = null)
        viewModelScope.launch {
            historyDepth = settings.settings.first().integrations.recentHistoryDepth
                .coerceIn(0, 100)
            haRepository.renderTemplate(template).fold(
                onSuccess = { rendered ->
                    R1Log.i("Template", "rendered len=${rendered.length}")
                    // Push to recent (dedupe + cap honouring the depth setting).
                    val newRecent = (listOf(template) + _ui.value.recent.filterNot { it == template })
                        .take(historyDepth)
                    _ui.value = _ui.value.copy(
                        rendered = rendered,
                        error = null,
                        inFlight = false,
                        recent = newRecent,
                    )
                },
                onFailure = { t ->
                    // HA's syntax-error path returns a 400 with the Jinja
                    // traceback in the body; surface it verbatim so the
                    // user can iterate without leaving the screen.
                    R1Log.w("Template", "render failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        error = t.message ?: "Render failed",
                        inFlight = false,
                    )
                },
            )
        }
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
        ) = viewModelFactory {
            initializer { TemplateViewModel(haRepository, settings) }
        }
    }
}
