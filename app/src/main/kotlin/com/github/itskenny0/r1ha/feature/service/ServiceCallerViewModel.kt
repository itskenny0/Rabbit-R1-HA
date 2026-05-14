package com.github.itskenny0.r1ha.feature.service

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Service Caller power-user surface. Lets the user type
 * any `domain.service` pair (e.g. `automation.reload`,
 * `homeassistant.restart`, `notify.mobile_app_pixel`) plus an
 * optional JSON `data` body and dispatches it via HA's REST
 * `/api/services` path.
 *
 * Why REST rather than the WebSocket call_service path the rest of
 * the app uses: WS call_service requires an EntityId target; many of
 * the most useful diagnostic services (restart, reload, set persistent
 * notification, …) don't have one. The REST endpoint accepts naked
 * service calls with optional `entity_id` in the body, which is the
 * shape the user actually wants for power-tool dispatch.
 */
class ServiceCallerViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class RecentCall(
        val domain: String,
        val service: String,
        val data: String,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val domain: String = "homeassistant",
        val service: String = "check_config",
        val data: String = "",
        val inFlight: Boolean = false,
        val result: String = "",
        val error: String? = null,
        /** Last 5 successfully-fired calls, newest first. Lives in
         *  ViewModel state only — not persisted across app restarts.
         *  That's intentional: this is "what did I just try?", not
         *  "what did I do last week" — the latter would want a real
         *  history surface. */
        val recent: List<RecentCall> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun setDomain(value: String) { _ui.value = _ui.value.copy(domain = value) }
    fun setService(value: String) { _ui.value = _ui.value.copy(service = value) }
    fun setData(value: String) { _ui.value = _ui.value.copy(data = value) }
    fun clearRecent() { _ui.value = _ui.value.copy(recent = emptyList()) }

    fun fire() {
        val s = _ui.value
        if (s.inFlight) return
        if (s.domain.isBlank() || s.service.isBlank()) {
            _ui.value = s.copy(error = "Domain + service required")
            return
        }
        // Parse the data field as a JsonObject if non-blank; empty = {}.
        val payload = if (s.data.isBlank()) {
            kotlinx.serialization.json.JsonObject(emptyMap())
        } else {
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(s.data)
                    as? kotlinx.serialization.json.JsonObject
                    ?: error("Data must be a JSON object")
            }.getOrElse { t ->
                _ui.value = s.copy(error = "Bad JSON data: ${t.message}")
                return
            }
        }
        _ui.value = s.copy(inFlight = true, error = null, result = "")
        viewModelScope.launch {
            haRepository.callRawService(s.domain.trim(), s.service.trim(), payload).fold(
                onSuccess = { result ->
                    R1Log.i("ServiceCaller", "${s.domain}.${s.service} OK len=${result.length}")
                    // Push to recent history (dedupe + cap at 5). Newest first.
                    val justFired = RecentCall(s.domain.trim(), s.service.trim(), s.data)
                    val newRecent = (listOf(justFired) + _ui.value.recent.filterNot { it == justFired })
                        .take(5)
                    _ui.value = _ui.value.copy(
                        result = if (result.isBlank()) "[] (no state changes)" else result,
                        error = null,
                        inFlight = false,
                        recent = newRecent,
                    )
                },
                onFailure = { t ->
                    R1Log.w("ServiceCaller", "${s.domain}.${s.service} failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        error = t.message ?: "Service call failed",
                        inFlight = false,
                    )
                },
            )
        }
    }

    /** Seed the editor with a [domain]/[service]/[data] preset — used by
     *  the example chips on the screen so the user can try common calls
     *  with one tap. */
    fun load(domain: String, service: String, data: String) {
        _ui.value = _ui.value.copy(
            domain = domain,
            service = service,
            data = data,
            error = null,
            result = "",
        )
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { ServiceCallerViewModel(haRepository) }
        }
    }
}
