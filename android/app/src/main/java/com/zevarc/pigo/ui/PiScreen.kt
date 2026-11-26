package com.zevarc.pigo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zevarc.pigo.model.ChatTurn
import com.zevarc.pigo.model.ConnectionStatus
import com.zevarc.pigo.model.PiUiState
import com.zevarc.pigo.ui.components.ToolCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiScreen(
    viewModel: PiViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    // Follow the stream while text arrives.
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.content?.length) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column {
                        Text(
                            text = state.sessions.firstOrNull { it.id == state.activeSessionId }?.title ?: "PiGo",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = connectionLabel(state.connection),
                            style = MaterialTheme.typography.bodySmall,
                            color = when (state.connection) {
                                ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
                                ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
                                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                },
                actions = {
                    if (state.connection != ConnectionStatus.CONNECTED) {
                        IconButton(onClick = viewModel::reconnect) {
                            Icon(Icons.Filled.Dns, contentDescription = "重连")
                        }
                    }
                    IconButton(onClick = { showSessions = true }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "切换会话")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                state = state,
                onInput = viewModel::onInputChange,
                onSend = viewModel::sendPrompt,
                onAbort = viewModel::abort,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.turns.isEmpty()) {
                EmptyState(state)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.turns, key = { it.id }) { turn ->
                        TurnRow(turn, viewModel::toggleTool)
                    }
                    if (state.notices.isNotEmpty()) {
                        item(key = "notices") {
                            Text(
                                text = state.notices.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            state.errorBanner?.let { message ->
                ErrorBanner(message, viewModel::dismissError, Modifier.align(Alignment.TopCenter))
            }
        }
    }

    if (showSessions) {
        ModalBottomSheet(
            onDismissRequest = { showSessions = false },
            sheetState = sheetState,
        ) {
            SessionSheet(
                state = state,
                onSelect = {
                    viewModel.selectSession(it)
                    showSessions = false
                },
                onCreate = { cwd, title -> viewModel.createSession(cwd, title) },
                onDelete = viewModel::deleteSession,
                onRefresh = viewModel::refreshSessions,
            )
        }
    }
}

@Composable
private fun TurnRow(turn: ChatTurn, onToggleTool: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        when (turn.role) {
            ChatTurn.Role.USER -> Surface(
                color = scheme.primary.copy(alpha = 0.14f),
                shape = shape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = turn.content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(10.dp),
                )
            }

            ChatTurn.Role.NOTICE -> Text(
                text = turn.content,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            ChatTurn.Role.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (turn.thinking.isNotBlank()) {
                    Text(
                        text = turn.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (turn.content.isNotEmpty() || turn.isStreaming) {
                    Text(
                        text = turn.content.ifEmpty { "…" },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                turn.toolCalls.forEach { item ->
                    ToolCard(item, onToggle = { onToggleTool(item.id) })
                }
            }
        }
    }
}

@Composable
private fun Composer(
    state: PiUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.navigationBarsPadding().imePadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (state.isAgentRunning) "追加指令（将排队）" else "给家里的 pi 下达任务") },
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp),
                )
                if (state.isAgentRunning) {
                    FilledIconButton(
                        onClick = onAbort,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "中止", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = state.inputText.isNotBlank() && state.activeSessionId != null,
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
            if (state.isAgentRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(state: PiUiState) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when (state.connection) {
            ConnectionStatus.CONNECTED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("已连接，但没有会话", style = MaterialTheme.typography.titleMedium)
                Text(
                    "点右上角切换会话，或先在 Bridge 调测台创建",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ConnectionStatus.CONNECTING -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在连接 Bridge…", modifier = Modifier.padding(top = 12.dp))
            }
            ConnectionStatus.DISCONNECTED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("未连接", style = MaterialTheme.typography.titleMedium)
                Text(
                    "确认 Tailscale 在线、Bridge 已启动、地址与 Token 正确",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.padding(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
        }
    }
}

private fun connectionLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.CONNECTED -> "Tailscale 已连通"
    ConnectionStatus.CONNECTING -> "连接中…"
    ConnectionStatus.DISCONNECTED -> "已断开"
}
