package com.github.itskenny0.r1ha.feature.cardstack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.DebouncedCaller
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class CardStackUiState(
    /** Ordered by the user's favorites list (NOT by entity_id). */
    val cards: List<EntityState> = emptyList(),
    val currentIndex: Int = 0,
    /** Optimistic percent overrides per entity, applied while waiting for HA state_changed. */
    val optimisticPercents: Map<EntityId, Int> = emptyMap(),
    /**
     * Number of entity IDs in the user's favourites list, regardless of whether HA has
     * sent state for them yet. Distinguishes "no favourites set" (zero) from "favourites
     * set but waiting on HA" (non-zero with empty `cards`).
     */
    val favouritesCount: Int = 0,
) {
    val activeState: EntityState?
        get() = cards.getOrNull(currentIndex)?.let { state ->
            val override = optimisticPercents[state.id]
            if (override != null) state.copy(percent = override) else state
        }
}

class CardStackViewModel(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
    private val wheelInput: WheelInput,
) : ViewModel() {

    private val _state = MutableStateFlow(CardStackUiState())
    val state: StateFlow<CardStackUiState> = _state

    /** Recent wheel-event timestamps; used to compute rate for acceleration. */
    private val wheelTimestamps = ArrayDeque<Long>()
    private val rateWindowMillis = 250L

    private val debounced = DebouncedCaller<EntityId, Int>(
        scope = viewModelScope,
        debounceMillis = 250L,
    ) { entityId, pct ->
        R1Log.i("CardStack.debounced", "sending setPercent($entityId, $pct)")
        haRepository.call(ServiceCall.setPercent(entityId, pct))
        // NOTE: do NOT clear the optimistic value here. It's cleared automatically when
        // observe emits an EntityState whose percent matches (see ordering logic below).
    }

    init {
        observeFavorites()
        announceServerOnce()
        // Wheel events are NOT collected here. They're collected by CardStackScreen only
        // while it is in composition, so spinning the wheel from any other screen does not
        // silently change the active card's brightness.
    }

    /** Show a one-shot toast on first mount so the user can see the resolved server URL. */
    private fun announceServerOnce() {
        viewModelScope.launch {
            val s = settings.settings.first()
            val url = s.server?.url
            if (url != null) {
                Toaster.show("Connected to $url")
            } else {
                Toaster.show("No server configured — open Settings", long = true)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFavorites() {
        // The favourites list is the source of truth for both membership and order.
        val favoritesFlow = settings.settings
            .map { it.favorites }
            .distinctUntilChanged()

        favoritesFlow
            .flatMapLatest { favouriteIds ->
                val ids = favouriteIds.mapNotNull { runCatching { EntityId(it) }.getOrNull() }.toSet()
                if (ids.isEmpty()) flowOf(Pair(favouriteIds, emptyMap<EntityId, EntityState>()))
                else haRepository.observe(ids).map { favouriteIds to it }
            }
            .onEach { (favouriteIds, entityMap) ->
                // Preserve user-chosen order; drop entries that aren't yet known to HA.
                val ordered = favouriteIds.mapNotNull { id ->
                    runCatching { EntityId(id) }.getOrNull()?.let { entityMap[it] }
                }
                R1Log.d("CardStack.observe", "favoriteIds=${favouriteIds.size} ordered=${ordered.size}")
                val cur = _state.value
                val clampedIndex = if (ordered.isEmpty()) 0
                    else cur.currentIndex.coerceIn(0, ordered.size - 1)
                // Drop optimistic entries that the server has caught up on.
                val staleOptimistic = cur.optimisticPercents.filter { (id, optPct) ->
                    val cached = entityMap[id]?.percent
                    cached != null && cached == optPct
                }.keys
                val newOptimistic = if (staleOptimistic.isEmpty()) cur.optimisticPercents
                    else cur.optimisticPercents - staleOptimistic
                _state.value = cur.copy(
                    cards = ordered,
                    currentIndex = clampedIndex,
                    optimisticPercents = newOptimistic,
                    favouritesCount = favouriteIds.size,
                )
            }
            .launchIn(viewModelScope)
    }

    /** Called from CardStackScreen when a wheel event arrives. Public so the screen can scope
     *  collection to its own lifecycle. */
    suspend fun onWheel(event: WheelEvent) {
        val appSettings = settings.settings.first()
        val wheel = appSettings.wheel
        val activeState = _state.value.activeState ?: return

        // Sliding-window rate computation: count events in the last [rateWindowMillis] ms,
        // multiply by (1000 / window) to scale to events/sec.
        val now = event.timestampMillis
        wheelTimestamps.addLast(now)
        while (wheelTimestamps.isNotEmpty() && now - wheelTimestamps.first() > rateWindowMillis) {
            wheelTimestamps.removeFirst()
        }
        val ratePerSec = wheelTimestamps.size * (1000.0 / rateWindowMillis)

        val step = WheelInput.effectiveStep(wheel.stepPercent, ratePerSec, wheel.acceleration)
        val sign = WheelInput.applyDirection(event.direction, wheel.invertDirection)
        val currentPct = _state.value.optimisticPercents[activeState.id] ?: activeState.percent ?: 0
        val newPct = (currentPct + sign * step).coerceIn(0, 100)
        R1Log.d("CardStack.onWheel", "dir=${event.direction} step=$step (rate=$ratePerSec) -> $currentPct→$newPct")

        // Apply optimistic update synchronously
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
        )

        // Submit debounced call (trails by debounceMillis)
        debounced.submit(activeState.id, newPct)
    }

    fun next() {
        val size = _state.value.cards.size
        if (size == 0) return
        _state.value = _state.value.copy(
            currentIndex = (_state.value.currentIndex + 1).mod(size)
        )
    }

    fun previous() {
        val size = _state.value.cards.size
        if (size == 0) return
        _state.value = _state.value.copy(
            currentIndex = (_state.value.currentIndex - 1).mod(size)
        )
    }

    fun tapToggle() {
        val activeState = _state.value.activeState ?: return
        viewModelScope.launch {
            R1Log.i("CardStack.tap", "toggle ${activeState.id} isOn=${activeState.isOn}")
            haRepository.call(ServiceCall.tapAction(activeState.id, activeState.isOn))
        }
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: SettingsRepository,
            wheelInput: WheelInput,
        ) = viewModelFactory {
            initializer {
                CardStackViewModel(
                    haRepository = haRepository,
                    settings = settings,
                    wheelInput = wheelInput,
                )
            }
        }
    }
}
