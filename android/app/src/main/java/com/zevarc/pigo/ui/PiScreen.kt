package com.zevarc.pigo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.zevarc.pigo.model.ChatTurn
import com.zevarc.pigo.model.ConnectionStatus
import com.zevarc.pigo.model.PiUiState
import com.zevarc.pigo.ui.components.CommandPalette
import com.zevarc.pigo.ui.components.ExtensionDialog
import com.zevarc.pigo.ui.components.ToolCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiScreen(
    viewModel: PiViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The session list is a side rail, not a modal sheet: you switch projects
    // far more often than you change screens, and a sheet hides the transcript.
    val openSessions = { scope.launch { drawerState.open() }; Unit }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) viewModel.loadResumeEntries(state.resumeQuery)
    }
    val closeSessions: () -> Unit = {
        scope.launch { drawerState.close() }
        Unit
    }
    val listState = rememberLazyListState()

    // Follow the stream while text arrives.
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.content?.length) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 360.dp)) {
                SessionSheet(
                    state = state,
                    onSelect = {
                        viewModel.selectSession(it)
                        closeSessions()
                    },
                    onCreate = { cwd, title -> viewModel.createSession(cwd, title) },
                    onDelete = viewModel::deleteSession,
                    onRefresh = viewModel::refreshSessions,
                    onSearch = viewModel::loadResumeEntries,
                    onResume = {
                        viewModel.resumeSession(it)
                        closeSessions()
                    },
                    onOpenSettings = {
                        closeSessions()
                        onOpenSettings()
                    },
                )
            }
        },
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(onClick = openSessions) {
                        Icon(Icons.Filled.Menu, contentDescription = "会话列表")
                    }
                },
                title = {
                    val active = state.sessions.firstOrNull { it.id == state.activeSessionId }
                    Text(
                        text = active?.title ?: state.activeSessionId?.take(8) ?: "PiGo",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    // Connection is a permanent, glanceable indicator — not a
                    // dismissible message and not a duplicate of the title.
                    ConnectionIndicator(
                        status = state.connection,
                        onReconnect = viewModel::reconnect,
                    )
                },
            )
        },
        bottomBar = {
            if (state.inputText.startsWith("/") && state.commands.isNotEmpty()) {
                CommandPalette(
                    commands = state.commands,
                    query = state.inputText,
                    onPick = { viewModel.onInputChange("/${it.name} ") },
                )
            }
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
                    if (state.extensionStatus.isNotEmpty()) {
                        item(key = "ext-status") {
                            Text(
                                text = state.extensionStatus.values.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    state.widgets.forEach { (key, lines) ->
                        item(key = "widget-$key") {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = lines.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
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
                ErrorChip(
                    message = message,
                    onDismiss = viewModel::dismissError,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }
        }
    }

    state.uiRequest?.let { request ->
        ExtensionDialog(
            request = request,
            onAnswer = { value, confirmed, cancelled -> viewModel.answerUi(value, confirmed, cancelled) },
        )
    }

    }
}

/**
 * Markdown styled after pi's own renderer, not the library defaults.
 *
 * pi keeps one visual language across its TUI theme tokens (mdHeading,
 * mdCode, mdCodeBlock, mdLink, mdListBullet...) and its exported HTML CSS.
 * The rules worth copying:
 *
 *  - headings are body-sized and bold, distinguished by color, not by scale;
 *  - inline code keeps the body typeface with a tinted chip, code blocks are
 *    the only monospace surface;
 *  - blockquotes are italic with a 3dp left rule;
 *  - list markers are accented.
 */
@Composable
private fun PiMarkdown(content: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val base = MaterialTheme.typography.bodyMedium
    val heading = base.copy(fontWeight = FontWeight.Bold, color = scheme.tertiary)
    val code = base.copy(fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)

    Markdown(
        content = content,
        colors = markdownColor(
            text = scheme.onSurface,
            codeBackground = scheme.surfaceVariant,
            inlineCodeBackground = scheme.surfaceVariant,
            dividerColor = scheme.outlineVariant,
            tableBackground = scheme.surfaceVariant,
        ),
        typography = markdownTypography(
            text = base,
            paragraph = base,
            code = code,
            inlineCode = base.copy(color = scheme.secondary),
            h1 = heading,
            h2 = heading,
            h3 = heading,
            h4 = heading,
            h5 = heading,
            h6 = heading,
            quote = base.copy(fontStyle = FontStyle.Italic),
            ordered = base,
            bullet = base,
            list = base,
            textLink = TextLinkStyles(
                style = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline),
            ),
            table = base,
        ),
        modifier = modifier,
    )
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
                PiMarkdown(
                    content = turn.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
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
                    PiMarkdown(
                        content = turn.content.ifEmpty { "…" },
                        modifier = Modifier.fillMaxWidth(),
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
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "中止")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = state.inputText.isNotBlank() && state.activeSessionId != null,
                        modifier = Modifier.size(48.dp),
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
                if (state.activeSessionId != null) {
                    Text("正在载入历史…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "这个会话还没有消息，或历史仍在拉取",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("已连接，但未选择会话", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "点左上角菜单选择历史会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/**
 * Transient error indicator.
 *
 * A full-width banner on top of the transcript is both intrusive and, as
 * shipped, permanent — nothing ever cleared it. This is a small badge in the
 * corner that disappears on its own after a few seconds (or on tap), so the
 * state can never look stuck.
 */
@Composable
private fun ErrorChip(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember(message) { mutableStateOf(true) }

    LaunchedEffect(message) {
        delay(6_000)
        visible = false
        onDismiss()
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(20.dp),
            onClick = {
                visible = false
                onDismiss()
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = "错误",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            }
        }
    }
}

/**
 * Always-visible link indicator: green antenna when the tailnet link is up,
 * amber while connecting, red when it is down (tap to retry).
 */
@Composable
private fun ConnectionIndicator(status: ConnectionStatus, onReconnect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF3FB950)
        ConnectionStatus.CONNECTING -> scheme.tertiary
        ConnectionStatus.DISCONNECTED -> scheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = status == ConnectionStatus.DISCONNECTED, onClick = onReconnect)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        if (status == ConnectionStatus.CONNECTING) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = color,
            )
        }
    }
}
