package com.zevarc.pigo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zevarc.pigo.bridge.BridgeSocketClient
import com.zevarc.pigo.bridge.TranscriptReducer
import com.zevarc.pigo.bridge.Wire
import com.zevarc.pigo.bridge.boolean
import com.zevarc.pigo.bridge.obj
import com.zevarc.pigo.bridge.long
import com.zevarc.pigo.bridge.string
import com.zevarc.pigo.data.BridgeSettings
import com.zevarc.pigo.model.ChatTurn
import com.zevarc.pigo.model.ConnectionStatus
import com.zevarc.pigo.model.PiUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * Owns the connection, the transcript reducer and the user's intents.
 *
 * All wire state lives in [PiUiState]; the reducer is the only thing allowed to
 * mutate it from the network side.
 */
class PiViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = BridgeSettings(application)
    private val reducer = TranscriptReducer()
    private val _state = MutableStateFlow(PiUiState())
    val state: StateFlow<PiUiState> = _state.asStateFlow()

    private val client = BridgeSocketClient(
        scope = viewModelScope,
        urlProvider = { settings.socketUrl() },
        onConnected = { syncActiveSession() },
    )

    init {
        observeConnection()
        observeIncoming()
        viewModelScope.launch {
            client.status.collect { status ->
                _state.update { it.copy(connection = status) }
            }
        }
        if (settings.socketUrl() != null) client.connect()
    }

    // ------------------------------------------------------------------ settings

    fun configure(endpoint: String, token: String) {
        settings.endpoint = endpoint
        settings.token = token
        client.disconnect()
        reducer.resetCursor()
        _state.value = PiUiState(connection = ConnectionStatus.CONNECTING)
        client.connect()
    }

    fun reconnect() {
        if (settings.socketUrl() == null) {
            _state.update { it.copy(errorBanner = "请先填写 Bridge 地址与 Token") }
            return
        }
        client.disconnect()
        client.connect()
    }

    fun endpoint(): String = settings.endpoint

    fun token(): String = settings.token

    // ------------------------------------------------------------------- intents

    fun onInputChange(text: String) = _state.update { it.copy(inputText = text) }

    fun sendPrompt() {
        val current = _state.value
        val sessionId = current.activeSessionId
        val text = current.inputText.trim()
        if (sessionId == null || text.isEmpty()) return

        val userTurn = ChatTurn("user-${UUID.randomUUID()}", ChatTurn.Role.USER, text)
        _state.update {
            it.copy(turns = it.turns + userTurn, inputText = "", errorBanner = null)
        }
        val delivered = client.send(
            Wire.obj(
                "type" to JsonPrimitive("prompt"),
                "sessionId" to JsonPrimitive(sessionId),
                "message" to JsonPrimitive(text),
                "id" to JsonPrimitive(userTurn.id),
            ).toString(),
        )

        // The optimistic turn is only honest if the frame actually left the
        // socket. Leaving it in place on failure shows the message as sent while
        // pi never saw it, so undo it and keep the text for a retry.
        if (!delivered) {
            _state.update {
                it.copy(
                    turns = it.turns.filterNot { turn -> turn.id == userTurn.id },
                    inputText = text,
                    errorBanner = "未连接，消息未发送",
                )
            }
        }
    }

    /** Drops everything still queued for the running session. */
    fun clearQueue() {
        val sessionId = _state.value.activeSessionId ?: return
        _state.update { it.copy(queuedSteering = emptyList(), queuedFollowUp = emptyList()) }
        send(
            Wire.obj(
                "type" to JsonPrimitive("abort"),
                "sessionId" to JsonPrimitive(sessionId),
                "clearQueue" to JsonPrimitive(true),
            ).toString(),
        )
    }

    fun abort() {
        val sessionId = _state.value.activeSessionId ?: return
        send(Wire.obj("type" to JsonPrimitive("abort"), "sessionId" to JsonPrimitive(sessionId)).toString())
    }

    fun selectSession(sessionId: String) {
        if (_state.value.activeSessionId == sessionId) return
        reducer.resetCursor()
        _state.update { it.copy(activeSessionId = sessionId, turns = emptyList(), notices = emptyList()) }
        requestMessages(sessionId)
        send(Wire.obj("type" to JsonPrimitive("sync"), "sessionId" to JsonPrimitive(sessionId), "lastSeq" to JsonPrimitive(0)).toString())
    }

    fun createSession(cwd: String, title: String?) {
        send(
            Wire.obj(
                "type" to JsonPrimitive("create_session"),
                "cwd" to JsonPrimitive(cwd),
                "title" to JsonPrimitive(title ?: cwd.substringAfterLast('/')),
            ).toString(),
        )
    }

    fun deleteSession(sessionId: String) {
        send(Wire.obj("type" to JsonPrimitive("delete_session"), "sessionId" to JsonPrimitive(sessionId)).toString())
    }

    fun refreshSessions() {
        send(Wire.obj("type" to JsonPrimitive("list_sessions")).toString())
        send(Wire.obj("type" to JsonPrimitive("list_workspaces")).toString())
    }

    /** Answers the pending extension dialog; pi is blocked until this arrives. */
    fun answerUi(value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false) {
        val current = _state.value
        val request = current.uiRequest ?: return
        val sessionId = current.activeSessionId ?: return
        _state.update { it.copy(uiRequest = null) }

        val payload = buildJsonObject {
            put("type", JsonPrimitive("ui_response"))
            put("sessionId", JsonPrimitive(sessionId))
            put("requestId", JsonPrimitive(request.requestId))
            if (cancelled) put("cancelled", JsonPrimitive(true))
            if (value != null) put("value", JsonPrimitive(value))
            if (confirmed != null) put("confirmed", JsonPrimitive(confirmed))
        }
        send(payload.toString())
    }

    /** Lists pi's own session store; the sheet shows every directory at once. */
    fun loadResumeEntries(query: String = "") {
        _state.update { it.copy(resumeQuery = query) }
        send(
            Wire.obj(
                "type" to JsonPrimitive("list_pi_sessions"),
                "query" to JsonPrimitive(query),
                "limit" to JsonPrimitive(400),
            ).toString(),
        )
    }

    /**
     * Opens an existing pi session by id.
     *
     * The cursor starts at 0 on purpose: the transcript is rebuilt from pi's own
     * message list, and any events emitted while resuming are replayed after it.
     */
    fun resumeSession(sessionId: String) {
        reducer.resetCursor()
        // Do NOT clear activeSessionId here: a `sessions_snapshot` can arrive
        // between the request and `session_created`, and with a null active id
        // the reducer falls back to "first session in the list" — switching the
        // user somewhere else and emptying the transcript.
        _state.update {
            it.copy(turns = emptyList(), notices = emptyList(), errorBanner = null)
        }
        send(
            Wire.obj(
                "type" to JsonPrimitive("resume_session"),
                "sessionId" to JsonPrimitive(sessionId),
            ).toString(),
        )
    }

    fun loadCommands() {
        val sessionId = _state.value.activeSessionId ?: return
        send(
            Wire.obj(
                "type" to JsonPrimitive("get_commands"),
                "sessionId" to JsonPrimitive(sessionId),
            ).toString(),
        )
    }

    fun toggleTool(toolCallId: String) {
        _state.update { current ->
            current.copy(
                turns = current.turns.map { turn ->
                    turn.copy(
                        toolCalls = turn.toolCalls.map {
                            if (it.id == toolCallId) it.copy(isExpanded = !it.isExpanded) else it
                        },
                    )
                },
            )
        }
    }

    /**
     * Loads one image's payload on demand.
     *
     * The transcript ships image placeholders only, so a long session opens fast
     * regardless of how many screenshots it contains. A tap is the one moment the
     * user has told us the bytes are worth sending.
     */
    fun loadImage(imageId: String) {
        val sessionId = _state.value.activeSessionId ?: return
        val block = _state.value.turns
            .flatMap { it.images }
            .firstOrNull { it.imageId == imageId }

        // Already loaded, or already asking: tapping again must not re-request.
        if (block == null || block.payload != null || block.isLoading) return

        _state.update { reducer.applyImageState(it, imageId, loading = true, failed = false) }
        send(
            Wire.obj(
                "type" to JsonPrimitive("get_image"),
                "sessionId" to JsonPrimitive(sessionId),
                "imageId" to JsonPrimitive(imageId),
            ).toString(),
        )
    }

    fun dismissError() = _state.update { it.copy(errorBanner = null) }

    // ------------------------------------------------------------------- plumbing

    /**
     * Connection bookkeeping.
     *
     * The refresh itself happens in [syncActiveSession] via the client's
     * `onConnected` callback. Doing it here as well meant every reconnect sent
     * `list_sessions`, `list_workspaces` and `list_pi_sessions` twice — and this
     * path dropped the resume query, so a search could silently reset.
     */
    private fun observeConnection() {
        viewModelScope.launch {
            client.status.collect { status ->
                // Reconnecting must not leave stale flags on screen: a run that
                // was streaming when the socket dropped will never settle now.
                if (status != ConnectionStatus.CONNECTED) {
                    _state.update { current ->
                        current.copy(
                            isAgentRunning = false,
                            turns = current.turns.map {
                                if (it.isStreaming) it.copy(isStreaming = false) else it
                            },
                        )
                    }
                }
            }
        }
    }

    private fun observeIncoming() {
        viewModelScope.launch {
            client.incoming.collect { raw ->
                val event = runCatching { Wire.parse(raw) }.getOrNull() ?: return@collect
                val before = _state.value.activeSessionId
                _state.update { reducer.reduce(it, event) }
                val after = _state.value.activeSessionId

                // Image payloads arrive outside the seq stream (they are not
                // transcript events), so they bypass the reducer's dedupe and are
                // merged into the matching placeholder here.
                when (event.string("type")) {
                    "image_data" -> {
                        _state.update { reducer.applyImageData(it, event) }
                        return@collect
                    }
                    "error" -> {
                        // A failed get_image must clear its spinner, or the card
                        // sits on "loading" forever.
                        val message = event.string("message").orEmpty()
                        if (message.contains("image", ignoreCase = true)) {
                            val pending = _state.value.turns
                                .flatMap { it.images }
                                .firstOrNull { it.isLoading }?.imageId
                            if (pending != null) {
                                _state.update {
                                    reducer.applyImageState(it, pending, loading = false, failed = true)
                                }
                            }
                            _state.update { it.copy(errorBanner = message) }
                            return@collect
                        }
                    }
                }

                // A session became active: load its transcript. `session_created`
                // carries the id explicitly, which is the only reliable signal —
                // inferring it from "activeSessionId changed" races with the
                // sessions snapshot that follows every (re)connect.
                val created = event.obj("session")?.let { it.string("id") }
                if (created != null && created != before) {
                    requestMessages(created)
                } else if (after != null && after != before) {
                    requestMessages(after)
                }

                // The replay buffer no longer covers the gap: reload pi's list.
                if (event.boolean("needsHistoryReload") == true && after != null) {
                    reducer.resetCursor(event.long("latestSeq") ?: 0L)
                    requestMessages(after)
                }
            }
        }
    }

    /** Called when the app returns to the foreground: recover whatever we missed. */
    fun onForeground() {
        if (client.status.value != ConnectionStatus.CONNECTED) return
        syncActiveSession()
    }

    private fun syncActiveSession() {
        refreshSessions()
        loadResumeEntries(_state.value.resumeQuery)
        val sessionId = _state.value.activeSessionId
        if (sessionId == null) return
        send(
            Wire.obj(
                "type" to JsonPrimitive("sync"),
                "sessionId" to JsonPrimitive(sessionId),
                "lastSeq" to JsonPrimitive(reducer.cursor()),
            ).toString(),
        )
    }

    private fun requestMessages(sessionId: String) {
        send(Wire.obj("type" to JsonPrimitive("get_messages"), "sessionId" to JsonPrimitive(sessionId)).toString())
        // Choose commands are per-session (skills/templates differ by workspace).
        loadCommands()
    }

    private fun send(raw: String) {
        if (!client.send(raw)) {
            _state.update { it.copy(errorBanner = "连接已断开，消息未发送") }
        }
    }

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }
}
