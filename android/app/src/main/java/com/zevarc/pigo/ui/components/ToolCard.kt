package com.zevarc.pigo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zevarc.pigo.model.ToolCallItem
import com.zevarc.pigo.model.ToolStatus

/**
 * Collapsed-by-default tool card. One line tells the user what ran and whether
 * it failed; tapping reveals the full output (mobile screens have no room for
 * terminal scrollback until asked for).
 */
@Composable
fun ToolCard(item: ToolCallItem, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = when (item.status) {
        ToolStatus.ERROR -> scheme.error
        ToolStatus.OK -> scheme.secondary
        ToolStatus.RUNNING -> scheme.primary
    }
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.5f), shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.35f), shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.status == ToolStatus.ERROR) {
                Icon(Icons.Filled.ErrorOutline, null, tint = accent, modifier = Modifier.padding(end = 0.dp))
            } else if (item.status == ToolStatus.RUNNING) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 1.5.dp)
            } else {
                Icon(Icons.Filled.Terminal, null, tint = accent)
            }
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            item.durationMs?.let {
                Text("${it}ms", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Icon(
                if (item.isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (item.isExpanded) "收起" else "展开",
            )
        }
        AnimatedVisibility(visible = item.isExpanded) {
            Column {
                Text(
                    text = item.output?.takeIf { it.isNotBlank() } ?: "（暂无输出）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == ToolStatus.ERROR) scheme.error else Color.Unspecified,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
