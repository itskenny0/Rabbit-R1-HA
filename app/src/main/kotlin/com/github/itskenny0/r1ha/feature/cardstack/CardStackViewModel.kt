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
import com.github.itskenny0.r1ha.core.prefs.WheelSettings
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
            if (override == null) {
                state
            } else if (state.supportsScalar) {
                // Scalar entity: optimistic just overrides the percent.
                state.copy(percent = override)
            } else {
                // Switch entity: encode desired ON in optimistic >= 1, OFF in 0. Flip isOn
                // immediately so the switch card snaps to the chosen position rather than
                // waiting for HA's state broadcast.
                state.copy(percent = override, isOn = override > 0)
            }
        }
}

class CardStackViewModel(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
    private val wheelInput: WheelInput,
) : ViewModel() {

    private val _state = MutableStateFlow(CardStackUiState())
    val state: StateFlow<CardStackUiState> = _state

    /** Snapshot of WheelSettings refreshed by the settings observer. We hold this in a
     *  separate field instead of calling settings.first() per wheel event — even though a
     *  hot Flow's first() is fast, doing it 50 times/sec inside the wheel collector lets
     *  events queue + drop in the SharedFlow buffer, manifesting as the readout jumping in
     *  irregular chunks rather than tracking the wheel. */
    @Volatile private var cachedWheel: WheelSettings = WheelSettings()

    /** Recent wheel-event timestamps; used to compute rate for acceleration. */
    private val wheelTimestamps = ArrayDeque<Long>()
    private val rateWindowMillis = 250L

    private val debounced = DebouncedCaller<EntityId, Int>(
        scope = viewModelScope,
        debounceMillis = 250L,
    ) { entityId, pct ->
        // Look up the entity's current state to pick the right service shape: scalar entities
        // get setPercent (turn_on with brightness_pct, set_percentage, set_cover_position,
        // volume_set), switch entities get the discrete setSwitch (turn_on/turn_off, open/
        // close_cover, media_play/media_pause). Reading the state at fire-time means the
        // wheel-up→ON and wheel-down→OFF intent stays accurate even if HA's state has shifted
        // during the 250 ms debounce.
        val entityState = _state.value.cards.firstOrNull { it.id == entityId }
        val call = if (entityState?.supportsScalar == false) {
            R1Log.i("CardStack.debounced", "sending setSwitch($entityId, on=${pct > 0})")
            ServiceCall.setSwitch(entityId, on = pct > 0)
        } else {
            R1Log.i("CardStack.debounced", "sending setPercent($entityId, $pct)")
            ServiceCall.setPercent(entityId, pct)
        }
        haRepository.call(call)
        // NOTE: do NOT clear the optimistic value here. It's cleared automatically when
        // observe emits an EntityState whose percent matches (see ordering logic below).
    }

    init {
        observeFavorites()
        // Keep a non-suspend snapshot of WheelSettings so onWheel() never has to hit the
        // settings Flow on the hot path.
        settings.settings
            .map { it.wheel }
            .distinctUntilChanged()
            .onEach { cachedWheel = it }
            .launchIn(viewModelScope)
        // Wheel events are NOT collected here. They're collected by CardStackScreen only
        // while it is in composition, so spinning the wheel from any other screen does not
        // silently change the active card's brightness.
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
                // Trim optimistic entries in two cases:
                //   1) The server caught up (cached value matches the optimistic override).
                //   2) The entity is no longer in the favourites set at all (user un-favourited
                //      it before HA echoed back). Without (2) the override map slowly grows
                //      every time someone toggles a favourite off.
                val favoriteSet = ordered.map { it.id }.toSet()
                val newOptimistic = cur.optimisticPercents.filter { (id, optPct) ->
                    if (id !in favoriteSet) return@filter false
                    val cached = entityMap[id]?.percent
                    cached == null || cached != optPct
                }
                _state.value = cur.copy(
                    cards = ordered,
                    currentIndex = clampedIndex,
                    optimisticPercents = newOptimistic,
                    favouritesCount = favouriteIds.size,
                )
            }
            .launchIn(viewModelScope)
    }

    /** Called from CardStackScreen when a wheel event arrives. Synchronous on the hot path —
     *  reads only cached state — so that 50 Hz wheel input doesn't back up in the SharedFlow
     *  buffer. The actual HA call is dispatched via the debouncer in its own coroutine. */
    fun onWheel(event: WheelEvent) {
        val wheel = cachedWheel
        val activeState = _state.value.activeState ?: return
        // Ignore wheel on unavailable entities: spinning would create a runaway optimistic
        // override that never reconciles because HA never responds with a state change.
        if (!activeState.isAvailable) return

        val sign = WheelInput.applyDirection(event.direction, wheel.invertDirection)

        // ── Switch (on/off only) entities: wheel sets absolute state ──────────────────
        if (!activeState.supportsScalar) {
            val newPct = if (sign > 0) 100 else 0
            _state.value = _state.value.copy(
                optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
            )
            viewModelScope.launch { debounced.submit(activeState.id, newPct) }
            return
        }

        // ── Scalar entities: wheel adjusts percent with optional acceleration ─────────
        // Sliding-window rate computation: count events in the last [rateWindowMillis] ms,
        // multiply by (1000 / window) to scale to events/sec.
        val now = event.timestampMillis
        wheelTimestamps.addLast(now)
        while (wheelTimestamps.isNotEmpty() && now - wheelTimestamps.first() > rateWindowMillis) {
            wheelTimestamps.removeFirst()
        }
        val ratePerSec = wheelTimestamps.size * (1000.0 / rateWindowMillis)

        val step = WheelInput.effectiveStep(wheel.stepPercent, ratePerSec, wheel.acceleration)
        val currentPct = _state.value.optimisticPercents[activeState.id] ?: activeState.percent ?: 0
        val newPct = (currentPct + sign * step).coerceIn(0, 100)
        R1Log.d("CardStack.onWheel", "dir=${event.direction} step=$step (rate=$ratePerSec) -> $currentPct→$newPct")

        // Apply optimistic update synchronously
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
        )

        // Submit debounced call (trails by debounceMillis). Suspend-free hot path: we launch
        // the submit so onWheel can return immediately and the next event isn't blocked.
        viewModelScope.launch { debounced.submit(activeState.id, newPct) }
    }

    /** Sync the VM's active-card index with the pager's settled page. The wheel/tap handlers
     *  read activeState off of this index, so it has to track whatever page the user has just
     *  paged to. */
    fun setCurrentIndex(index: Int) {
        val size = _state.value.cards.size
        if (size == 0) return
        _state.value = _state.value.copy(currentIndex = index.coerceIn(0, size - 1))
    }

    fun tapToggle() {
        val activeState = _state.value.activeState ?: return
        if (!activeState.isAvailable) return  // can't toggle an unreachable entity
        viewModelScope.launch {
            R1Log.i("CardStack.tap", "toggle ${activeState.id} isOn=${activeState.isOn}")
            haRepository.call(ServiceCall.tapAction(activeState.id, activeState.isOn))
        }
    }

    /**
     * Set the active switch-card entity to an explicit [on] state — wired to the ON/OFF
     * labels on [SwitchCard]. Re-uses the same optimistic + debouncer pipeline as the wheel
     * so a rapid tap-ON tap-OFF still resolves to the last intent.
     */
    fun setSwitchOn(on: Boolean) {
        val activeState = _state.value.activeState ?: return
        if (!activeState.isAvailable) return
        val newPct = if (on) 100 else 0
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
        )
        viewModelScope.launch { debounced.submit(activeState.id, newPct) }
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
