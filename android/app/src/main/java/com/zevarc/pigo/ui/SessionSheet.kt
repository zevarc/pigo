package com.zevarc.pigo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
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
import com.zevarc.pigo.model.ResumeEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the phone can switch to, in one list.
 *
 * Like pi's own `/resume`, this is built from the sessions pi has on disk,
 * grouped by working directory — the user thinks in projects, not in ids. A
 * session that the bridge is already serving is marked and goes straight to
 * it; anything else is resumed lazily on tap.
 */
@Composable
fun SessionSheet(
    state: PiUiState,
    onSelect: (String) -> Unit,
    onCreate: (String, String?) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onResume: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expandedNew by remember { mutableStateOf(false) }
    var cwd by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }

    val query = state.resumeQuery
    val entries = state.resumeEntries
    // Directory order: most recently used first, so the top of the sheet is
    // where the user was last working.
    val groups = entries
        .groupBy { it.cwd }
        .toList()
        .sortedByDescending { (_, rows) -> rows.maxOfOrNull { it.updatedAt } ?: 0L }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("会话", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${entries.size} 个历史会话 · ${groups.size} 个目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
            TextButton(onClick = { expandedNew = !expandedNew }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("新建")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onSearch,
            placeholder = { Text("搜索目录、内容或 id") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        if (expandedNew) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "工作区根：${state.workspaceRoot ?: "未知"}",
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
                    label = { Text("相对路径，留空为根") },
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
                TextButton(
                    onClick = {
                        onCreate(cwd, title.ifBlank { null })
                        cwd = ""
                        title = ""
                        expandedNew = false
                    },
                ) { Text("创建并切入") }
                HorizontalDivider()
            }
        }

        if (groups.isEmpty()) {
            Text(
                text = if (query.isBlank()) "还没有历史会话" else "没有匹配的会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        groups.forEach { (dir, rows) ->
            FolderHeader(dir = dir, count = rows.size)
            rows.sortedByDescending { it.updatedAt }.forEach { entry ->
                SessionRow(
                    entry = entry,
                    isActive = entry.id == state.activeSessionId,
                    onClick = { if (entry.active) onSelect(entry.id) else onResume(entry.id) },
                    onDelete = { onDelete(entry.id) },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        ListItem(
            headlineContent = { Text("设置") },
            supportingContent = {
                Text(
                    text = state.workspaceRoot?.let { "Bridge 根目录 $it" } ?: "地址与 Token",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
            modifier = Modifier.clickable { onOpenSettings() },
        )
    }
}

@Composable
private fun FolderHeader(dir: String, count: Int) {
    val name = dir.substringAfterLast('/').ifBlank { dir }
    val parent = dir.substringBeforeLast('/', "").removePrefix("/")
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (parent.isNotEmpty()) {
                Text(
                    text = "  ~/$parent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Row(modifier = Modifier.weight(1f)) {}
            }
            Text(
                text = "$count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionRow(
    entry: ResumeEntry,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        headlineContent = {
            Text(
                text = entry.name ?: entry.preview?.take(40) ?: entry.id.take(8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent = {
            Text(
                text = "${relativeTime(entry.updatedAt)} · ${fileSize(entry.sizeBytes)} · ${entry.id.take(8)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = if (isActive) {
            { Text("●", color = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.active) Badge { Text("运行中") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除会话")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun relativeTime(updatedAt: Long): String {
    if (updatedAt <= 0L) return "未知时间"
    val delta = System.currentTimeMillis() - updatedAt
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
        delta < 7 * 86_400_000L -> "${delta / 86_400_000L} 天前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))
    }
}

private fun fileSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format(Locale.US, "%.1fMB", bytes / 1048576.0)
}
