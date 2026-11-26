package com.zevarc.pigo.bridge

import android.util.Log
import com.zevarc.pigo.model.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Long-lived link to the home bridge.
 *
 * OkHttp's listener runs on its own threads, so every socket callback is
 * forwarded into flows and all state stays observable from the UI scope.
 *
 * Reconnection uses capped exponential backoff (1s, 2s, 4s, 8s, 16s, 30s…).
 * After every successful open the caller's [onConnected] runs, which is where
 * the sync cursor is replayed.
 */
class BridgeSocketClient(
    private val scope: CoroutineScope,
    private val urlProvider: () -> String?,
    private val onConnected: () -> Unit,
) {
    private companion object {
        const val TAG = "BridgeSocket"
        const val MAX_BACKOFF_MS = 30_000L
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    private var manuallyClosed = false
    private var lastError: String? = null

    fun connect() {
        manuallyClosed = false
        attempt = 0
        reconnectJob?.cancel()
        open()
    }

    /** Sends one text frame. Returns false when the socket is not open. */
    fun send(raw: String): Boolean = socket?.send(raw) == true

    fun disconnect() {
        manuallyClosed = true
        reconnectJob?.cancel()
        socket?.close(1000, "client closed")
        socket = null
        _status.value = ConnectionStatus.DISCONNECTED
    }

    private fun open() {
        val url = urlProvider()
        if (url.isNullOrBlank()) {
            lastError = "未配置 Bridge 地址"
            _status.value = ConnectionStatus.DISCONNECTED
            scheduleReconnect()
            return
        }

        _status.value = ConnectionStatus.CONNECTING
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        if (manuallyClosed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = min(MAX_BACKOFF_MS, 1_000L shl attempt.coerceAtMost(5))
            attempt++
            Log.i(TAG, "reconnecting in ${delayMs}ms (attempt $attempt)${lastError?.let { " after $it" } ?: ""}")
            delay(delayMs)
            if (!manuallyClosed) open()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            lastError = null
            _status.value = ConnectionStatus.CONNECTED
            onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!_incoming.tryEmit(text)) {
                Log.w(TAG, "dropped bridge event: consumer is too slow")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            _status.value = ConnectionStatus.DISCONNECTED
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            lastError = t.message ?: t::class.simpleName
            _status.value = ConnectionStatus.DISCONNECTED
            scheduleReconnect()
        }
    }
}
