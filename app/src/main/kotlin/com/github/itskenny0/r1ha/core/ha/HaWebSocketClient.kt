package com.github.itskenny0.r1ha.core.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-level Home Assistant WebSocket client. Owns the WS lifecycle, performs auth handshake,
 * exposes inbound messages as a SharedFlow, and accepts outbound messages via [send].
 *
 * Reconnect/backoff/keepalive are layered on by HaRepository (Milestone 6); this class focuses on
 * "one connection, cleanly handled."
 */
class HaWebSocketClient internal constructor(
    private val http: OkHttpClient,
    internal val scope: CoroutineScope,
) {
    constructor() : this(http = OkHttpClient(), scope = CoroutineScope(SupervisorJob()))

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<HaInbound>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val inbound: SharedFlow<HaInbound> = _inbound.asSharedFlow()

    private val nextId = AtomicInteger(1)
    fun nextRequestId(): Int = nextId.getAndIncrement()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var receiverJob: Job? = null
    private val outgoing = Channel<HaOutbound>(capacity = Channel.UNLIMITED)

    fun connect(url: String, accessToken: String) {
        if (_state.value !is ConnectionState.Idle && _state.value !is ConnectionState.Disconnected) return
        _state.value = ConnectionState.Connecting
        val req = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                webSocket = ws
                _state.value = ConnectionState.Authenticating
                receiverJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    for (msg in outgoing) ws.send(HaJson.encodeToString(msg))
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                val msg = runCatching { HaJson.decodeFromString<HaInbound>(text) }
                    .getOrElse { HaInbound.Unknown }
                when (msg) {
                    is HaInbound.AuthRequired -> ws.send(HaJson.encodeToString<HaOutbound>(HaOutbound.Auth(accessToken)))
                    is HaInbound.AuthOk -> _state.value = ConnectionState.Connected(msg.haVersion)
                    is HaInbound.AuthInvalid -> {
                        _state.value = ConnectionState.AuthLost(msg.message)
                        ws.close(1000, "auth_invalid")
                    }
                    else -> Unit
                }
                _inbound.tryEmit(msg)
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { /* HA only sends text */ }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _state.value = ConnectionState.Disconnected(ConnectionState.Cause.ServerClosed, attempt = 0)
                receiverJob?.cancel(); webSocket = null
            }
            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                _state.value = ConnectionState.Disconnected(ConnectionState.Cause.Error(t), attempt = 0)
                receiverJob?.cancel(); webSocket = null
            }
        }
        http.newWebSocket(req, listener)
    }

    /** Enqueue an outbound message. Safe to call before [connect] has completed; the queue drains once connected. */
    fun send(msg: HaOutbound) {
        outgoing.trySend(msg)
    }

    fun disconnect(code: Int = 1000, reason: String = "client_disconnect") {
        webSocket?.close(code, reason)
        webSocket = null
        receiverJob?.cancel()
        _state.value = ConnectionState.Idle
    }
}
