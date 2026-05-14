package com.github.itskenny0.r1ha.core.ha

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface HaRepository {
    val connection: StateFlow<ConnectionState>
    /** Hot map of currently-known entity states for the subscribed set. */
    fun observe(entities: Set<EntityId>): Flow<Map<EntityId, EntityState>>
    /**
     * Fires once per service call the repository couldn't deliver — timeout, WS dropped,
     * HA returned an error, etc. The ViewModel watches this so it can roll back its
     * optimistic UI override; the repository already surfaces a user-visible toast.
     */
    val callFailures: SharedFlow<EntityId>

    /**
     * Wall-clock millis when the next reconnect attempt is scheduled to fire, or null
     * if no backoff is pending (we're either connected or actively connecting). UI
     * reads this to show "Reconnecting in Xs…" countdown text on the stalled-loading
     * empty state, which is much friendlier than an indefinite spinner during a long
     * backoff window.
     */
    val reconnectNextAttemptAtMillis: StateFlow<Long?>
    /** Fire a service call. Coalesces back-to-back calls per entity via internal debounce. */
    suspend fun call(call: ServiceCall): Result<Unit>
    /** One-shot REST GET /api/states equivalent, used by FavoritesPicker. */
    suspend fun listAllEntities(): Result<List<EntityState>>

    /**
     * Diagnostic — issue the same GET /api/states call as [listAllEntities] but
     * group the **raw** response by entity_id prefix without applying our supported-
     * domain filter or per-row decoder. Lets the user verify whether HA even sent
     * media_player.* (or any other domain) for their token. Used by About →
     * Entities → 'PROBE RAW' so 'where are my entities?' becomes self-service.
     */
    suspend fun listAllEntitiesRawPrefixCounts(): Result<Map<String, Int>>

    /**
     * History fetch — `GET /api/history/period/<since-iso>?filter_entity_id=<id>`. Returns
     * the timestamped state changes for [entityId] going back [hours] hours from now,
     * in chronological order. Used by SensorCard to render a line chart for numeric
     * sensors and a recent-changes list for text/categorical sensors.
     */
    suspend fun fetchHistory(entityId: EntityId, hours: Int = 24): Result<List<HistoryPoint>>

    /**
     * HA's conversation/process endpoint — sends a natural-language [text]
     * prompt and returns the plain-text response. Powers the Assist text
     * surface. [conversationId] threads multi-turn context; null starts a
     * fresh conversation.
     */
    suspend fun conversationProcess(
        text: String,
        language: String? = null,
        conversationId: String? = null,
    ): Result<ConversationResponse>
    suspend fun start()
    suspend fun stop()

    /**
     * Cancel any pending reconnect-backoff and attempt a connection immediately. No-op if the
     * connection is already Connecting / Authenticating / Connected — in those states the
     * existing attempt is the right one to ride out. Used by the stalled-loading affordance
     * so the user has a one-tap recovery path that doesn't require waiting out the backoff
     * (which can be 30+ seconds on the 20th consecutive failure).
     */
    fun reconnectNow()
}
