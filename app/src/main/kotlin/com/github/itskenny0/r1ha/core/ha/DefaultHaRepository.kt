package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class DefaultHaRepository(
    private val ws: HaWebSocketClient,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : HaRepository {

    override val connection: StateFlow<ConnectionState> = ws.state

    private val cache = MutableStateFlow<Map<EntityId, EntityState>>(emptyMap())
    private val pendingCalls = ConcurrentHashMap<Int, CompletableDeferred<Result<Unit>>>()
    private var supervisorJob: Job? = null
    private var subscriptionId: Int? = null

    private val debouncer = DebouncedCaller<EntityId, ServiceCall>(scope, debounceMillis = 120) { _, call ->
        val id = ws.nextRequestId()
        val deferred = CompletableDeferred<Result<Unit>>()
        pendingCalls[id] = deferred
        ws.send(HaOutbound.CallService(id, call.haDomain, call.service, call.target.value, call.data))
        // best-effort: on error, the inbound listener completes the deferred; we don't await here
    }

    override suspend fun start() {
        if (supervisorJob != null) return
        supervisorJob = scope.launch {
            ws.inbound.onEach { msg ->
                when (msg) {
                    is HaInbound.Result -> pendingCalls.remove(msg.id)?.complete(
                        if (msg.success) Result.success(Unit)
                        else Result.failure(IllegalStateException(msg.error?.message ?: "ha_error"))
                    )
                    is HaInbound.Event -> applyEvent(msg)
                    else -> Unit
                }
            }.launchIn(this)

            ws.state.onEach { st ->
                when (st) {
                    is ConnectionState.Connected -> resubscribe()
                    is ConnectionState.Disconnected -> reconnectLater(st.attempt)
                    else -> Unit
                }
            }.launchIn(this)

            connectFromSettings()
        }
    }

    override suspend fun stop() {
        supervisorJob?.cancel(); supervisorJob = null
        ws.disconnect()
    }

    private suspend fun connectFromSettings() {
        val s = settings.settings.first()
        val server = s.server ?: return
        val t = tokens.load() ?: return
        val base = server.url.trimEnd('/')
        val wsUrl = when {
            base.startsWith("https://") -> base.replaceFirst("https://", "wss://")
            base.startsWith("http://")  -> base.replaceFirst("http://", "ws://")
            else -> base
        } + "/api/websocket"
        ws.connect(wsUrl, t.accessToken)
    }

    private fun reconnectLater(attempt: Int) {
        scope.launch {
            delay(backoff.delayForAttempt(attempt))
            connectFromSettings()
        }
    }

    private fun applyEvent(ev: HaInbound.Event) {
        val raw = ev.event.variables.trigger.toState
        val idStr = raw.entityId ?: ev.event.variables.trigger.entityId
        val prefix = idStr.substringBefore('.', missingDelimiterValue = "")
        if (!Domain.isSupportedPrefix(prefix)) return
        val id = EntityId(idStr)
        val isOn = raw.state.equals("on", ignoreCase = true) ||
            raw.state.equals("playing", ignoreCase = true) ||
            (raw.state.equals("open", ignoreCase = true))
        val available = !raw.state.equals("unavailable", ignoreCase = true)
        val pct = computePercent(id.domain, raw.attributes)
        val rawNum = computeRaw(id.domain, raw.attributes)
        val newState = EntityState(
            id = id,
            friendlyName = (raw.attributes["friendly_name"]?.toString()?.trim('"')) ?: id.objectId,
            area = raw.attributes["area_id"]?.toString()?.trim('"'),
            isOn = isOn,
            percent = if (available) pct else null,
            raw = rawNum,
            lastChanged = runCatching { Instant.parse(raw.lastChanged ?: "") }.getOrDefault(Instant.now()),
            isAvailable = available,
        )
        cache.value = cache.value + (id to newState)
    }

    private fun computePercent(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Int? {
        return when (domain) {
            Domain.LIGHT -> (attrs["brightness"]?.toString()?.toIntOrNull())?.let(EntityState::normaliseLightBrightness)
            Domain.FAN -> attrs["percentage"]?.toString()?.toIntOrNull()?.let(EntityState::normaliseFanPercentage)
            Domain.COVER -> attrs["current_position"]?.toString()?.toIntOrNull()?.let(EntityState::normaliseCoverPosition)
            Domain.MEDIA_PLAYER -> attrs["volume_level"]?.toString()?.toDoubleOrNull()?.let(EntityState::normaliseMediaVolume)
        }
    }

    private fun computeRaw(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Number? = when (domain) {
        Domain.LIGHT -> attrs["brightness"]?.toString()?.toIntOrNull()
        Domain.FAN -> attrs["percentage"]?.toString()?.toIntOrNull()
        Domain.COVER -> attrs["current_position"]?.toString()?.toIntOrNull()
        Domain.MEDIA_PLAYER -> attrs["volume_level"]?.toString()?.toDoubleOrNull()
    }

    override fun observe(entities: Set<EntityId>): Flow<Map<EntityId, EntityState>> =
        cache.map { it.filterKeys { id -> id in entities } }

    override suspend fun call(call: ServiceCall): Result<Unit> {
        // Optimistic update was already applied by the ViewModel — the repo just forwards.
        debouncer.submit(call.target, call)
        return Result.success(Unit)
    }

    override suspend fun listAllEntities(): Result<List<EntityState>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first(); val server = s.server ?: error("no server")
            val t = tokens.load() ?: error("no token")
            val req = Request.Builder()
                .url("${server.url.trimEnd('/')}/api/states")
                .header("Authorization", "Bearer ${t.accessToken}")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "states: HTTP ${resp.code}" }
                resp.body!!.string()
            }
            val states = Json { ignoreUnknownKeys = true }.decodeFromString<List<RawStateRow>>(body)
            states.mapNotNull { row ->
                val prefix = row.entity_id.substringBefore('.', missingDelimiterValue = "")
                if (!Domain.isSupportedPrefix(prefix)) return@mapNotNull null
                EntityState(
                    id = EntityId(row.entity_id),
                    friendlyName = row.attributes?.get("friendly_name")?.toString()?.trim('"') ?: row.entity_id.substringAfter('.'),
                    area = row.attributes?.get("area_id")?.toString()?.trim('"'),
                    isOn = row.state.equals("on", ignoreCase = true) ||
                        row.state.equals("playing", ignoreCase = true) ||
                        row.state.equals("open", ignoreCase = true),
                    percent = null, raw = null,
                    lastChanged = runCatching { Instant.parse(row.last_changed ?: "") }.getOrDefault(Instant.now()),
                    isAvailable = !row.state.equals("unavailable", ignoreCase = true),
                )
            }
        }
    }

    @kotlinx.serialization.Serializable
    private data class RawStateRow(
        val entity_id: String,
        val state: String,
        val attributes: kotlinx.serialization.json.JsonObject? = null,
        val last_changed: String? = null,
    )

    private fun resubscribe() {
        scope.launch {
            val favs = settings.settings.first().favorites
            if (favs.isEmpty()) return@launch
            val newId = ws.nextRequestId()
            ws.send(HaOutbound.SubscribeStateTrigger(id = newId, entityIds = favs))
            subscriptionId?.let { old ->
                val unsubId = ws.nextRequestId()
                ws.send(HaOutbound.UnsubscribeEvents(id = unsubId, subscription = old))
            }
            subscriptionId = newId
        }
    }
}
