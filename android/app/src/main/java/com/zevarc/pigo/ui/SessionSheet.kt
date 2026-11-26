package com.zevarc.pigo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zevarc.pigo.model.PiUiState

/**
 * Bottom sheet with two sections: existing sessions (tap to switch, running
 * ones carry a badge) and a "new session" form fed by the bridge's workspace
 * whitelist, so the client never types a path blind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    state: PiUiState,
    onSelect: (String) -> Unit,
    onCreate: (String, String?) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var cwd by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var expandedNew by remember { mutableStateOf(false) }

    // Plain Column + scroll: a bottom sheet measures its content with an
    // unbounded height, so a LazyColumn would collapse to zero.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
            IconButton(onClick = { expandedNew = !expandedNew }) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话")
            }
        }

        if (expandedNew) {
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "工作区（root: ${state.workspaceRoot ?: "未知"}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.workspaces.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.workspaces.take(3).forEach { workspace ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { cwd = workspace.path },
                            ) {
                                Text(
                                    text = workspace.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text("相对工作区路径，留空为根") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("会话名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(
                        onClick = {
                            onCreate(cwd, title.ifBlank { null })
                            cwd = ""
                            title = ""
                            expandedNew = false
                        },
                    ) { Text("创建并切入") }
                }
                HorizontalDivider()
            }
        }

        if (state.sessions.isEmpty()) {
            Text(
                text = "还没有会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        state.sessions.forEach { session ->
            ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = if (session.id == state.activeSessionId) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                    headlineContent = {
                        Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            (if (session.processAlive) "● 运行中" else "○ 已回收") + "  " + session.cwd,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (session.isAgentRunning) {
                                Badge { Text("任务运行中") }
                            }
                            IconButton(onClick = { onDelete(session.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除会话")
                            }
                        }
                    },
                modifier = Modifier.clickable { onSelect(session.id) },
            )
        }
    }
}
