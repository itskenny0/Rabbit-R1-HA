package com.github.itskenny0.r1ha.core.ha

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface HaRepository {
    val connection: StateFlow<ConnectionState>
    /** Hot map of currently-known entity states for the subscribed set. */
    fun observe(entities: Set<EntityId>): Flow<Map<EntityId, EntityState>>
    /** Fire a service call. Coalesces back-to-back calls per entity via internal debounce. */
    suspend fun call(call: ServiceCall): Result<Unit>
    /** One-shot REST GET /api/states equivalent, used by FavoritesPicker. */
    suspend fun listAllEntities(): Result<List<EntityState>>
    suspend fun start()
    suspend fun stop()
}
