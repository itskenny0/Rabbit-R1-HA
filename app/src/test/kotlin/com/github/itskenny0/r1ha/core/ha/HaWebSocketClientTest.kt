package com.github.itskenny0.r1ha.core.ha

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Simple WebSocket server-side recorder backed by blocking queues.
 * Replaces WebSocketRecorder (not shipped in okhttp3 mockwebserver 4.12).
 */
private class ServerRecorder : WebSocketListener() {
    private val opens = LinkedBlockingQueue<WebSocket>()
    private val messages = LinkedBlockingQueue<String>()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opens.put(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        messages.put(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { /* unused */ }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    fun awaitOpen(timeoutMs: Long = 3_000): WebSocket =
        opens.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: error("Timed out waiting for WebSocket open")

    fun awaitTextMessage(timeoutMs: Long = 3_000): String =
        messages.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: error("Timed out waiting for text message")
}

@OptIn(ExperimentalCoroutinesApi::class)
class HaWebSocketClientTest {
    private lateinit var server: MockWebServer
    private lateinit var recorder: ServerRecorder
    private lateinit var httpClient: OkHttpClient

    @BeforeEach fun setUp() {
        server = MockWebServer()
        recorder = ServerRecorder()
        httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
    @AfterEach fun tearDown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        server.shutdown()
    }

    private fun http(): OkHttpClient = httpClient

    @Test fun `connect performs auth and emits Connected`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()
        val url = server.url("/api/websocket").toString().replace("http", "ws")

        val client = HaWebSocketClient(http = http(), scope = TestScope(StandardTestDispatcher(testScheduler)))
        client.state.test {
            assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
            client.connect(url, accessToken = "TOK")
            assertThat(awaitItem()).isEqualTo(ConnectionState.Connecting)
            assertThat(awaitItem()).isEqualTo(ConnectionState.Authenticating)

            // Server side: receive auth, then respond auth_ok
            val opened = recorder.awaitOpen()
            opened.send("""{"type":"auth_required","ha_version":"2026.5.0"}""")
            val authFrame = recorder.awaitTextMessage()
            assertThat(authFrame).contains("\"type\":\"auth\"")
            assertThat(authFrame).contains("\"access_token\":\"TOK\"")
            opened.send("""{"type":"auth_ok","ha_version":"2026.5.0"}""")
            advanceUntilIdle()
            val connected = awaitItem()
            assertThat(connected).isInstanceOf(ConnectionState.Connected::class.java)
            assertThat((connected as ConnectionState.Connected).haVersion).isEqualTo("2026.5.0")
            cancelAndConsumeRemainingEvents()
        }
        client.scope.cancel()
    }

    @Test fun `after auth_ok client drains queued sends`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()
        val url = server.url("/api/websocket").toString().replace("http", "ws")
        val client = HaWebSocketClient(http = http(), scope = TestScope(StandardTestDispatcher(testScheduler)))

        client.connect(url, accessToken = "TOK")
        val opened = recorder.awaitOpen()
        opened.send("""{"type":"auth_required"}""")
        recorder.awaitTextMessage()                              // auth frame
        opened.send("""{"type":"auth_ok","ha_version":"x"}""")

        // Now queue subscribe + call_service and verify they hit the wire
        val subId = client.nextRequestId()
        client.send(HaOutbound.SubscribeStateTrigger(id = subId, entityIds = listOf("light.kitchen")))
        val callId = client.nextRequestId()
        client.send(HaOutbound.CallService(callId, "light", "turn_on", "light.kitchen", null))

        advanceUntilIdle()
        val frame1 = recorder.awaitTextMessage()
        val frame2 = recorder.awaitTextMessage()
        assertThat(frame1).contains("\"type\":\"subscribe_trigger\"")
        assertThat(frame2).contains("\"type\":\"call_service\"")
        client.disconnect(); client.scope.cancel()
    }
}
