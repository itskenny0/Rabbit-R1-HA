package com.github.itskenny0.r1ha.core.ha.testing

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Simple WebSocket server-side recorder backed by blocking queues.
 * Replaces WebSocketRecorder (not shipped in okhttp3 mockwebserver 4.12).
 */
class ServerRecorder : WebSocketListener() {
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
