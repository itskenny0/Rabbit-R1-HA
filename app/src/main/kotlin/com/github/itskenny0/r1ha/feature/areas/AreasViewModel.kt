package com.github.itskenny0.r1ha.feature.areas

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives the HA Areas browser. HA's area_registry isn't reachable from
 * a public REST endpoint — it lives behind the WebSocket
 * `config/area_registry/list` command. Rather than extending
 * HaWebSocketClient to support arbitrary command/result calls (which
 * would touch correlation IDs + result futures), we fetch the area
 * data via a server-side Jinja template through the existing
 * `/api/template` endpoint:
 *
 * ```
 * {% set out = namespace(items=[]) %}
 * {% for area in areas() %}
 *   {% set _ = out.items.append({"name": area_name(area), "entities": area_entities(area)}) %}
 * {% endfor %}
 * {{ out.items | tojson }}
 * ```
 *
 * HA renders the template with full access to its area registry and
 * returns the JSON array directly. We parse, sort, and surface.
 */
class AreasViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Area(
        val name: String,
        val entityIds: List<String>,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val areas: List<Area> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // HA's templating gives us areas() + area_entities(area_id).
            // We pull both in one pass to avoid a per-area network round
            // trip (which would be O(N) requests on a big install).
            val tpl = """
                {%- set out = namespace(items=[]) -%}
                {%- for area in areas() -%}
                  {%- set _ = out.items.append({"id": area, "name": area_name(area), "entities": area_entities(area)}) -%}
                {%- endfor -%}
                {{ out.items | tojson }}
            """.trimIndent()
            haRepository.renderTemplate(tpl).fold(
                onSuccess = { rendered ->
                    runCatching {
                        val root = Json.parseToJsonElement(rendered)
                        val arr = root as? JsonArray
                            ?: error("Unexpected template response shape — not an array")
                        val list = arr.mapNotNull { el ->
                            val obj = el as? JsonObject ?: return@mapNotNull null
                            val name = (obj["name"] as? JsonPrimitive)?.content
                                ?: (obj["id"] as? JsonPrimitive)?.content
                                ?: return@mapNotNull null
                            val entitiesArr = obj["entities"] as? JsonArray
                            val entities = entitiesArr?.mapNotNull {
                                (it as? JsonPrimitive)?.content
                            }.orEmpty()
                            Area(name = name, entityIds = entities)
                        }.sortedBy { it.name.lowercase() }
                        R1Log.i("Areas", "loaded ${list.size}")
                        _ui.value = _ui.value.copy(loading = false, areas = list, error = null)
                    }.onFailure { t ->
                        R1Log.w("Areas", "parse failed: ${t.message}")
                        Toaster.error("Areas parse failed — try Templates to debug")
                        _ui.value = _ui.value.copy(loading = false, error = t.message)
                    }
                },
                onFailure = { t ->
                    R1Log.w("Areas", "fetch failed: ${t.message}")
                    Toaster.error("Areas load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { AreasViewModel(haRepository) }
        }
    }
}
