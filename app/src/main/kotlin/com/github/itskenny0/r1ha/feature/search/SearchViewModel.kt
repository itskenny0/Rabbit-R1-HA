package com.github.itskenny0.r1ha.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Drives the Universal Search surface. Pulls every entity HA exposes
 * via the supported Domain enum (the same listAllEntities call the
 * favourites picker uses), filters by substring on friendlyName +
 * entity_id, and returns the matches grouped by domain.
 *
 * Tap action depends on the entity's domain:
 *  - Scenes / scripts / buttons → fire via the appropriate service
 *  - On/off entities (light / switch / fan / cover / lock) → toggle
 *  - Everything else → surface a detail toast with state + area
 *
 * Acts as a complement to the Favourites Picker (which is for
 * managing the card stack) and the Scenes screen (which is scene/
 * script-only). This surface is for "I know I have an entity called
 * 'Bedroom Light' — find it and fire it" without configuring it as
 * a favourite first.
 */
class SearchViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    /** Coarse-grained domain bucket for the filter chips. Maps the
     *  Domain enum into the four user-facing groupings the chips
     *  expose; "ALL" disables the kind filter entirely. */
    enum class Bucket { ALL, CONTROLS, SENSORS, ACTIONS, OTHER }

    private fun bucketOf(d: Domain): Bucket = when (d) {
        Domain.LIGHT, Domain.SWITCH, Domain.FAN, Domain.COVER, Domain.MEDIA_PLAYER,
        Domain.LOCK, Domain.INPUT_BOOLEAN, Domain.HUMIDIFIER, Domain.CLIMATE,
        Domain.WATER_HEATER, Domain.VACUUM, Domain.VALVE, Domain.NUMBER,
        Domain.INPUT_NUMBER, Domain.SELECT, Domain.INPUT_SELECT -> Bucket.CONTROLS
        Domain.SENSOR, Domain.BINARY_SENSOR -> Bucket.SENSORS
        Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON,
        Domain.AUTOMATION -> Bucket.ACTIONS
    }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        /** All entities loaded once on entry. Search filters this in
         *  memory so keystrokes don't hit the network. */
        val all: List<EntityState> = emptyList(),
        val query: String = "",
        val bucket: Bucket = Bucket.ALL,
        val error: String? = null,
    )

    /** Filtered subset matching [query] AND the active [bucket]. Empty
     *  query with ALL bucket returns empty list (avoid rendering the
     *  entire entity registry on entry); query OR bucket non-empty
     *  produces matches.*/
    val results: List<EntityState>
        get() {
            val s = _ui.value
            val q = s.query.trim().lowercase()
            if (q.isBlank() && s.bucket == Bucket.ALL) return emptyList()
            return s.all.filter { e ->
                val matchesQuery = if (q.isBlank()) true else (
                    e.friendlyName.lowercase().contains(q) ||
                        e.id.value.lowercase().contains(q) ||
                        (e.area?.lowercase()?.contains(q) ?: false)
                    )
                val matchesBucket = s.bucket == Bucket.ALL || bucketOf(e.id.domain) == s.bucket
                matchesQuery && matchesBucket
            }
                .sortedWith(
                    compareByDescending<EntityState> {
                        q.isNotBlank() && (
                            it.friendlyName.lowercase().startsWith(q) ||
                                it.id.value.lowercase().substringAfter('.').startsWith(q)
                            )
                    }.thenBy { it.friendlyName.lowercase() },
                )
                .take(80)
        }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listAllEntities().fold(
                onSuccess = { entities ->
                    R1Log.i("Search", "loaded ${entities.size} entities")
                    _ui.value = _ui.value.copy(loading = false, all = entities, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Search", "list failed: ${t.message}")
                    Toaster.error("Search load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    fun setQuery(q: String) {
        if (_ui.value.query == q) return
        _ui.value = _ui.value.copy(query = q)
    }

    fun setBucket(b: Bucket) {
        if (_ui.value.bucket == b) return
        _ui.value = _ui.value.copy(bucket = b)
    }

    /**
     * Tap action — dispatches the appropriate service for the entity's
     * domain. Action-only entities (scene / script / button) fire;
     * controllable entities (light / switch / fan / etc.) toggle;
     * everything else surfaces a detail toast.
     */
    fun activate(entity: EntityState) {
        viewModelScope.launch {
            val target = entity.id
            when {
                target.domain == Domain.SCENE -> {
                    haRepository.call(ServiceCall(target, "turn_on", JsonObject(emptyMap())))
                    Toaster.show("Fired scene '${entity.friendlyName}'")
                }
                target.domain == Domain.SCRIPT -> {
                    haRepository.call(ServiceCall(target, "turn_on", JsonObject(emptyMap())))
                    Toaster.show("Fired script '${entity.friendlyName}'")
                }
                target.domain == Domain.BUTTON || target.domain == Domain.INPUT_BUTTON -> {
                    haRepository.call(ServiceCall(target, "press", JsonObject(emptyMap())))
                    Toaster.show("Pressed '${entity.friendlyName}'")
                }
                // For toggleable entities (lights / switches / fans /
                // covers / locks / media_players) use ServiceCall.tapAction
                // which encodes the right on→off, off→on semantics per
                // domain.
                target.domain == Domain.LIGHT || target.domain == Domain.SWITCH ||
                    target.domain == Domain.FAN || target.domain == Domain.COVER ||
                    target.domain == Domain.LOCK || target.domain == Domain.MEDIA_PLAYER ||
                    target.domain == Domain.INPUT_BOOLEAN || target.domain == Domain.AUTOMATION ||
                    target.domain == Domain.HUMIDIFIER || target.domain == Domain.CLIMATE ||
                    target.domain == Domain.WATER_HEATER || target.domain == Domain.VACUUM ||
                    target.domain == Domain.VALVE -> {
                    haRepository.call(ServiceCall.tapAction(target, entity.isOn))
                    Toaster.show("${if (entity.isOn) "Off" else "On"}: ${entity.friendlyName}")
                }
                else -> {
                    // Sensors / numbers / selects — read-only or not
                    // tap-toggle-friendly. Surface a detail toast instead.
                    val parts = buildString {
                        append(entity.friendlyName).append('\n')
                        append(entity.id.value).append('\n')
                        append("state: ").append(entity.rawState ?: if (entity.isOn) "on" else "off")
                        if (entity.area != null) append("\narea: ").append(entity.area)
                    }
                    Toaster.showExpandable(shortText = entity.friendlyName, fullText = parts)
                }
            }
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { SearchViewModel(haRepository) }
        }
    }
}
