package com.zevarc.pigo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zevarc.pigo.bridge.BridgeSocketClient
import com.zevarc.pigo.bridge.TranscriptReducer
import com.zevarc.pigo.bridge.Wire
import com.zevarc.pigo.bridge.boolean
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
        send(
            Wire.obj(
                "type" to JsonPrimitive("prompt"),
                "sessionId" to JsonPrimitive(sessionId),
                "message" to JsonPrimitive(text),
                "id" to JsonPrimitive(userTurn.id),
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

    /** Lists pi's own session store for the resume picker. */
    fun loadResumeEntries(query: String = "") {
        _state.update { it.copy(resumeQuery = query) }
        send(
            Wire.obj(
                "type" to JsonPrimitive("list_pi_sessions"),
                "query" to JsonPrimitive(query),
                "limit" to JsonPrimitive(50),
            ).toString(),
        )
    }

    /** Opens an existing pi session by id; the bridge spawns it in its own cwd. */
    fun resumeSession(sessionId: String) {
        reducer.resetCursor()
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

    fun dismissError() = _state.update { it.copy(errorBanner = null) }

    // ------------------------------------------------------------------- plumbing

    private fun observeConnection() {
        viewModelScope.launch {
            client.status.collect { status ->
                if (status == ConnectionStatus.CONNECTED) refreshSessions()
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
                if (after != null && after != before) {
                    requestMessages(after)
                }
                // The replay buffer cannot cover the gap: pi's message list is the
                // only source of truth, and the cursor must restart from it.
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
