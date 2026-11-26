package com.zevarc.pigo.model

/**
 * Everything the UI needs to paint one screen. Immutable so a Compose render
 * never observes a half-applied event.
 */
data class PiUiState(
    val connection: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val workspaces: List<Workspace> = emptyList(),
    val workspaceRoot: String? = null,
    val isAgentRunning: Boolean = false,
    val turns: List<ChatTurn> = emptyList(),
    val inputText: String = "",
    val errorBanner: String? = null,
    val notices: List<String> = emptyList(),
)

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class SessionSummary(
    val id: String,
    val title: String,
    val cwd: String,
    val createdAt: Long = 0L,
    val lastActiveAt: Long = 0L,
    val isAgentRunning: Boolean = false,
    val processAlive: Boolean = false,
    val latestSeq: Long = 0L,
)

/** A directory under the bridge's allowed root that can host a new session. */
data class Workspace(val path: String, val name: String)

data class ChatTurn(
    val id: String,
    val role: Role,
    val content: String,
    val thinking: String = "",
    val toolCalls: List<ToolCallItem> = emptyList(),
    val isStreaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, NOTICE }
}

data class ToolCallItem(
    val id: String,
    val name: String,
    val summary: String,
    val status: ToolStatus = ToolStatus.RUNNING,
    val output: String? = null,
    val durationMs: Long? = null,
    val isExpanded: Boolean = false,
)

enum class ToolStatus { RUNNING, OK, ERROR }
