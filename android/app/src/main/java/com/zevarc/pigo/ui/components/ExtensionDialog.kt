package com.zevarc.pigo.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zevarc.pigo.model.CommandInfo
import com.zevarc.pigo.model.UiRequest

/**
 * Renders whatever dialog an extension is currently blocking on.
 *
 * pi's own `timeout` is not honoured here on purpose: the dialog stays until the
 * user answers or dismisses it, and dismissing sends `cancelled: true` so the
 * extension unblocks immediately instead of waiting out its timer.
 */
@Composable
fun ExtensionDialog(
    request: UiRequest,
    onAnswer: (value: String?, confirmed: Boolean?, cancelled: Boolean) -> Unit,
) {
    var text by remember(request.requestId) { mutableStateOf(request.prefill) }

    AlertDialog(
        onDismissRequest = { onAnswer(null, null, true) },
        title = { Text(request.title ?: methodLabel(request.method)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                when (request.method) {
                    "select" -> Column(
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    ) {
                        request.options.forEach { option ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable { onAnswer(option, null, false) },
                            ) {
                                Text(
                                    text = option,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }

                    "input" -> OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(request.placeholder ?: "") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onAnswer(text, null, false) }),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    "editor" -> OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        minLines = 4,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (request.method) {
                "confirm" -> TextButton(onClick = { onAnswer(null, true, false) }) { Text("确定") }
                "input", "editor" -> TextButton(onClick = { onAnswer(text, null, false) }) { Text("提交") }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(null, null, true) }) {
                Text(if (request.method == "confirm") "取消" else "取消")
            }
        },
    )
}

private fun methodLabel(method: String): String = when (method) {
    "select" -> "选择一个选项"
    "confirm" -> "确认执行"
    "input" -> "输入"
    "editor" -> "编辑内容"
    else -> method
}

/**
 * `/` palette. pi resolves skill commands, prompt templates and extension
 * commands through the same `prompt` path, so tapping a row just prefills the
 * composer instead of trying to be clever.
 */
@Composable
fun CommandPalette(
    commands: List<CommandInfo>,
    query: String,
    onPick: (CommandInfo) -> Unit,
) {
    val needle = query.removePrefix("/").trim().lowercase()
    val matches = commands
        .filter { needle.isEmpty() || it.name.lowercase().contains(needle) || (it.description?.lowercase()?.contains(needle) == true) }
        .take(40)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        if (matches.isEmpty()) {
            Text(
                text = if (commands.isEmpty()) "没有加载到命令（Bridge 未返回）" else "没有匹配的命令",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(matches, key = { it.name }) { command ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(command) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "/${command.name}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            command.source?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        command.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
