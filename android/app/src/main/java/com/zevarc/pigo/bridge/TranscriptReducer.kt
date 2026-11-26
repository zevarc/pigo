package com.zevarc.pigo.bridge

import com.zevarc.pigo.model.ChatTurn
import com.zevarc.pigo.model.CommandInfo
import com.zevarc.pigo.model.UiRequest
import com.zevarc.pigo.model.PiUiState
import com.zevarc.pigo.model.ResumeEntry
import com.zevarc.pigo.model.SessionSummary
import com.zevarc.pigo.model.ToolCallItem
import com.zevarc.pigo.model.ToolStatus
import com.zevarc.pigo.model.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Pure, stateless-per-event translation of bridge events into [PiUiState].
 *
 * Kept free of Android dependencies so the whole event contract is unit
 * testable on the JVM. Rules encoded here:
 *
 *  - `seq` is a per-session monotonic counter: anything at or below the cursor
 *    is a duplicate from a replay burst and is dropped.
 *  - `message_delta` appends; `assistant_message_end` authoritatively replaces
 *    the reconstructed text (deltas may be lossy after a reconnect).
 *  - `agent_end` is *not* the end of a run: retries, compaction recovery and
 *    queued follow-ups keep it running. Only `agent_settled` clears the flag.
 */
class TranscriptReducer {
    /** Highest seq applied so far; reset whenever the active session changes. */
    var lastSeq: Long = 0L
        private set

    fun resetCursor(seq: Long = 0L) {
        lastSeq = seq
    }

    fun cursor(): Long = lastSeq

    fun reduce(state: PiUiState, event: JsonObject): PiUiState {
        val type = event.string("type") ?: return state
        val sessionId = event.string("sessionId")
        if (sessionId != null && state.activeSessionId != null && sessionId != state.activeSessionId) {
            // Events for background sessions only refresh the badge.
            return refreshSession(state, sessionId, event)
        }

        val seq = event.long("seq")
        if (seq != null) {
            if (seq <= lastSeq) return state
            lastSeq = seq
        }

        return when (type) {
            "sessions_snapshot", "sessions_updated" -> applySessions(state, event)
            "session_created" -> applySessionCreated(state, event)
            "session_deleted" -> applySessionDeleted(state, event)
            "workspaces_snapshot" -> applyWorkspaces(state, event)
            "messages_snapshot" -> applyMessagesSnapshot(state, event)
            "sync_response" -> applySync(state, event)
            "agent_start" -> state.copy(isAgentRunning = true, errorBanner = null)
            "agent_end" -> state // still not idle: retries / queued work may follow
            "agent_settled" -> state.copy(
                isAgentRunning = false,
                turns = state.turns.map { if (it.isStreaming) it.copy(isStreaming = false) else it },
            )
            "assistant_message_start" -> applyAssistantStart(state, event)
            "message_delta" -> applyTextDelta(state, event)
            "thinking_delta" -> applyThinkingDelta(state, event)
            "assistant_message_end" -> applyAssistantEnd(state, event)
            "tool_card", "tool_start" -> applyToolStart(state, event)
            "tool_progress" -> applyToolProgress(state, event)
            "tool_end" -> applyToolEnd(state, event)
            "ui_request" -> applyUiRequest(state, event)
            "commands_snapshot" -> applyCommands(state, event)
            "pi_sessions_snapshot" -> applyResumeEntries(state, event)
            "status" -> applyStatus(state, event)
            "widget" -> applyWidget(state, event)
            "editor_text" -> state.copy(inputText = event.string("text").orEmpty())
            "session_title" -> state
            "notice" -> applyNotice(state, event)
            "error" -> state.copy(errorBanner = event.string("message") ?: "Bridge error")
            "session_process_exit" -> state.copy(
                isAgentRunning = false,
                turns = state.turns.map { if (it.isStreaming) it.copy(isStreaming = false) else it },
                notices = state.notices + processExitNotice(event),
            )
            else -> state
        }
    }

    // ------------------------------------------------------------------ sessions

    private fun applySessions(state: PiUiState, event: JsonObject): PiUiState {
        val sessions = event.array("sessions").orEmpty().mapNotNull(::parseSession)
        val current = state.activeSessionId
        // Keep the current session even when this snapshot predates it (a
        // `resume_session` reply can land after the snapshot). Falling back to
        // "first in the list" would silently switch the user to another session.
        val active = when {
            current != null -> current
            else -> sessions.firstOrNull()?.id
        }
        val running = sessions.firstOrNull { it.id == active }?.isAgentRunning ?: state.isAgentRunning
        return state.copy(
            sessions = sessions,
            activeSessionId = active,
            isAgentRunning = running,
            turns = state.turns,
        )
    }

    private fun applySessionCreated(state: PiUiState, event: JsonObject): PiUiState {
        val session = event.obj("session")?.let(::parseSession) ?: return state
        val sessions = state.sessions.filterNot { it.id == session.id } + session
        return state.copy(sessions = sessions, activeSessionId = session.id, turns = emptyList(), notices = emptyList())
    }

    private fun applySessionDeleted(state: PiUiState, event: JsonObject): PiUiState {
        val deleted = event.string("sessionId")
        val sessions = event.array("sessions")?.mapNotNull(::parseSession)
            ?: state.sessions.filterNot { it.id == deleted }
        val active = if (state.activeSessionId == deleted) sessions.firstOrNull()?.id else state.activeSessionId
        return state.copy(
            sessions = sessions,
            activeSessionId = active,
            turns = if (state.activeSessionId == deleted) emptyList() else state.turns,
        )
    }

    private fun applyWorkspaces(state: PiUiState, event: JsonObject): PiUiState {
        val workspaces = event.array("workspaces").orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val path = obj.string("path") ?: return@mapNotNull null
            Workspace(path = path, name = obj.string("name") ?: path)
        }
        return state.copy(workspaces = workspaces, workspaceRoot = event.string("root"))
    }

    /** Rebuilds the transcript from pi's authoritative message list (after reconnect or eviction). */
    private fun applyMessagesSnapshot(state: PiUiState, event: JsonObject): PiUiState {
        val raw = event.array("messages").orEmpty()
        val turns = mutableListOf<ChatTurn>()
        raw.forEachIndexed { index, element ->
            val message = element as? JsonObject ?: return@forEachIndexed
            when (message.string("role")) {
                "user" -> {
                    val text = messageText(message)
                    if (text.isNotBlank()) turns += ChatTurn("snap-user-$index", ChatTurn.Role.USER, text)
                }
                "assistant" -> turns += ChatTurn(
                    message.string("id") ?: "snap-assistant-$index",
                    ChatTurn.Role.ASSISTANT,
                    messageText(message),
                )
                else -> Unit
            }
        }
        return state.copy(turns = turns)
    }

    private fun messageText(message: JsonObject): String {
        message["content"]?.let { content ->
            if (content is JsonPrimitive) return content.contentOrNull.orEmpty()
            val blocks = runCatching { content.jsonArray }.getOrNull().orEmpty()
            return blocks.mapNotNull { block ->
                (block as? JsonObject)?.let { if (it.string("type") == "text") it.string("text") else null }
            }.joinToString("")
        }
        return ""
    }

    private fun applySync(state: PiUiState, event: JsonObject): PiUiState {
        var next = state
        event.array("events").orEmpty().forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            next = reduce(next, obj)
        }
        val latest = event.long("latestSeq")
        if (latest != null && latest > lastSeq) lastSeq = latest
        val running = event.boolean("isAgentRunning")
        return if (event.boolean("needsHistoryReload") == true) {
            next.copy(notices = next.notices + "事件缓冲已滑出窗口，请重新拉取历史")
        } else if (running != null) {
            next.copy(isAgentRunning = running)
        } else {
            next
        }
    }

    /** Background-session events: only lift the running flag into the badge list. */
    private fun refreshSession(state: PiUiState, sessionId: String, event: JsonObject): PiUiState {
        val type = event.string("type")
        val running = when (type) {
            "agent_start" -> true
            "agent_settled", "session_process_exit" -> false
            else -> return state
        }
        return state.copy(
            sessions = state.sessions.map {
                if (it.id == sessionId) it.copy(isAgentRunning = running) else it
            },
        )
    }

    // ------------------------------------------------------------------ messages

    private fun applyAssistantStart(state: PiUiState, event: JsonObject): PiUiState {
        val id = event.string("messageId") ?: "assistant-${lastSeq}"
        if (state.turns.any { it.id == id }) return state
        return state.copy(turns = state.turns + ChatTurn(id, ChatTurn.Role.ASSISTANT, "", isStreaming = true))
    }

    private fun applyTextDelta(state: PiUiState, event: JsonObject): PiUiState {
        val delta = event.string("delta").orEmpty()
        val authoritative = event.string("content")
        val index = state.turns.indexOfLast { it.role == ChatTurn.Role.ASSISTANT && it.isStreaming }
        if (index < 0) {
            val turn = ChatTurn("assistant-${lastSeq}", ChatTurn.Role.ASSISTANT, authoritative ?: delta, isStreaming = true)
            return state.copy(turns = state.turns + turn)
        }
        val turn = state.turns[index]
        val content = when {
            authoritative != null -> authoritative
            else -> turn.content + delta
        }
        return state.copy(turns = state.turns.replacedAt(index, turn.copy(content = content)))
    }

    private fun applyThinkingDelta(state: PiUiState, event: JsonObject): PiUiState {
        val delta = event.string("delta").orEmpty()
        if (delta.isEmpty()) return state
        val index = state.turns.indexOfLast { it.role == ChatTurn.Role.ASSISTANT }
        if (index < 0) return state
        val turn = state.turns[index]
        return state.copy(turns = state.turns.replacedAt(index, turn.copy(thinking = turn.thinking + delta)))
    }

    private fun applyAssistantEnd(state: PiUiState, event: JsonObject): PiUiState {
        val text = event.string("text")
        val index = state.turns.indexOfLast { it.role == ChatTurn.Role.ASSISTANT && it.isStreaming }
        if (index < 0) {
            if (text.isNullOrEmpty()) return state
            return state.copy(
                turns = state.turns + ChatTurn(
                    event.string("messageId") ?: "assistant-${lastSeq}",
                    ChatTurn.Role.ASSISTANT,
                    text,
                ),
            )
        }
        val turn = state.turns[index]
        return state.copy(turns = state.turns.replacedAt(index, turn.copy(content = text ?: turn.content, isStreaming = false)))
    }

    private fun applyToolStart(state: PiUiState, event: JsonObject): PiUiState {
        val id = event.string("toolCallId") ?: return state
        val item = ToolCallItem(
            id = id,
            name = event.string("name").orEmpty(),
            summary = event.string("summary").orEmpty(),
            status = ToolStatus.RUNNING,
        )
        val index = state.turns.indexOfLast { it.role == ChatTurn.Role.ASSISTANT }
        if (index < 0) {
            val turn = ChatTurn("tools-${lastSeq}", ChatTurn.Role.ASSISTANT, "", toolCalls = listOf(item))
            return state.copy(turns = state.turns + turn)
        }
        val turn = state.turns[index]
        val tools = if (turn.toolCalls.any { it.id == id }) {
            turn.toolCalls.map { if (it.id == id) item.copy(isExpanded = it.isExpanded) else it }
        } else {
            turn.toolCalls + item
        }
        return state.copy(turns = state.turns.replacedAt(index, turn.copy(toolCalls = tools)))
    }

    private fun applyToolProgress(state: PiUiState, event: JsonObject): PiUiState {
        val id = event.string("toolCallId") ?: return state
        val output = event.string("output").orEmpty()
        return state.updateTool(id) { it.copy(output = output) }
    }

    private fun applyToolEnd(state: PiUiState, event: JsonObject): PiUiState {
        val id = event.string("toolCallId") ?: return state
        val output = event.string("output")
        val failed = event.boolean("isError") == true
        val duration = event.long("durationMs")
        return state.updateTool(id) {
            it.copy(
                status = if (failed) ToolStatus.ERROR else ToolStatus.OK,
                output = output ?: it.output,
                durationMs = duration ?: it.durationMs,
            )
        }
    }

    private fun applyUiRequest(state: PiUiState, event: JsonObject): PiUiState {
        val requestId = event.string("requestId") ?: return state
        return state.copy(
            uiRequest = UiRequest(
                requestId = requestId,
                method = event.string("method").orEmpty(),
                title = event.string("title"),
                message = event.string("message"),
                options = event.array("options").orEmpty().mapNotNull { it.asString() },
                placeholder = event.string("placeholder"),
                prefill = event.string("prefill").orEmpty(),
            ),
        )
    }

    private fun applyCommands(state: PiUiState, event: JsonObject): PiUiState {
        val commands = event.array("commands").orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            CommandInfo(name, obj.string("description"), obj.string("source"))
        }
        return state.copy(commands = commands)
    }

    private fun applyResumeEntries(state: PiUiState, event: JsonObject): PiUiState {
        val entries = event.array("sessions").orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            ResumeEntry(
                id = id,
                cwd = obj.string("cwd").orEmpty(),
                name = obj.string("name"),
                preview = obj.string("preview"),
                updatedAt = obj.long("updatedAt") ?: 0L,
                sizeBytes = obj.long("sizeBytes") ?: 0L,
                active = obj.boolean("active") == true,
            )
        }
        return state.copy(resumeEntries = entries)
    }

    private fun applyStatus(state: PiUiState, event: JsonObject): PiUiState {
        val key = event.string("statusKey") ?: return state
        val text = event.string("statusText")
        val next = state.extensionStatus.toMutableMap()
        if (text.isNullOrEmpty()) next.remove(key) else next[key] = text
        return state.copy(extensionStatus = next)
    }

    private fun applyWidget(state: PiUiState, event: JsonObject): PiUiState {
        val key = event.string("widgetKey") ?: return state
        val lines = event.array("widgetLines").orEmpty().mapNotNull { it.asString() }
        val next = state.widgets.toMutableMap()
        if (lines.isEmpty()) next.remove(key) else next[key] = lines
        return state.copy(widgets = next)
    }

    private fun applyNotice(state: PiUiState, event: JsonObject): PiUiState {
        val kind = event.string("kind").orEmpty()
        val text = when (kind) {
            "compaction_start" -> "正在压缩上下文…"
            "compaction_end" -> if (event.boolean("aborted") == true) "上下文压缩已取消" else "上下文已压缩"
            "auto_retry_start" -> "请求失败，第 ${event.long("attempt") ?: 1} 次重试…"
            "auto_retry_end" -> if (event.boolean("success") == true) "重试成功" else "重试失败"
            "extension_error" -> "扩展错误：${event.string("error") ?: ""}"
            "extension_notify" -> event.string("message")
            "prompt_accepted" -> when (event.string("disposition")) {
                "queued" -> "已排队，等当前回合结束后执行"
                "handled" -> "已由扩展处理"
                else -> null
            }
            "queue_cleared" -> "已清空排队消息"
            else -> null
        }
        return if (text == null) state else state.copy(notices = (state.notices + text).takeLast(20))
    }

    // ------------------------------------------------------------------ helpers

    private fun parseSession(element: kotlinx.serialization.json.JsonElement): SessionSummary? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id") ?: return null
        return SessionSummary(
            id = id,
            title = obj.string("title") ?: id,
            cwd = obj.string("cwd").orEmpty(),
            createdAt = obj.long("createdAt") ?: 0L,
            lastActiveAt = obj.long("lastActiveAt") ?: 0L,
            isAgentRunning = obj.boolean("isAgentRunning") == true,
            processAlive = obj.boolean("processAlive") == true,
            latestSeq = obj.long("latestSeq") ?: 0L,
        )
    }

    private fun processExitNotice(event: JsonObject): String {
        val code = event.long("code")
        val signal = event.string("signal")
        return when {
            signal != null -> "pi 进程被信号 $signal 终止"
            code != null -> "pi 进程已退出（code=$code），下条消息会自动拉起"
            else -> "pi 进程已退出"
        }
    }
}

private fun <T> List<T>.replacedAt(index: Int, value: T): List<T> =
    mapIndexed { i, existing -> if (i == index) value else existing }

private fun PiUiState.updateTool(id: String, transform: (ToolCallItem) -> ToolCallItem): PiUiState {
    var changed = false
    val turns = turns.map { turn ->
        if (turn.toolCalls.none { it.id == id }) return@map turn
        changed = true
        turn.copy(toolCalls = turn.toolCalls.map { if (it.id == id) transform(it) else it })
    }
    return if (changed) copy(turns = turns) else this
}
