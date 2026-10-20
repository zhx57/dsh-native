package dev.dsh.nativeapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.TodoItem
import dev.dsh.nativeapp.data.TodoStatus
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Task progress for the running turn.
 *
 * The web app renders the same list inline above the composer; on a phone it
 * collapses to a single summary row so it never eats the transcript.
 */
@Composable
fun TodoPanel(todos: List<TodoItem>, modifier: Modifier = Modifier) {
    if (todos.isEmpty()) return
    val colors = LocalDshColors.current
    var expanded by remember { mutableStateOf(false) }

    val done = todos.count { it.isDone }
    val active = todos.firstOrNull { it.isActive }

    Column(
        modifier
            .fillMaxWidth()
            .border(0.5.dp, colors.borderL2, RoundedCornerShape(DshRadius.lg.dp))
            .background(colors.sidebarFill, RoundedCornerShape(DshRadius.lg.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = DshSpacing.md.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Checklist,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = active?.content ?: "任务",
                fontSize = 12.5.sp,
                color = colors.labelSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = "$done/${todos.size}",
                fontSize = 11.5.sp,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
        }

        // A slim progress bar reads faster than the fraction alone.
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.borderL2),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(if (todos.isEmpty()) 0f else done.toFloat() / todos.size)
                    .height(2.dp)
                    .background(colors.stateSuccess),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(horizontal = DshSpacing.md.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                todos.forEach { todo ->
                    Row(verticalAlignment = Alignment.Top) {
                        TodoMark(todo.status)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = todo.content,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = when (todo.status) {
                                TodoStatus.Completed -> colors.labelCaption
                                TodoStatus.InProgress -> colors.labelPrimary
                                TodoStatus.Pending -> colors.labelSecondary
                            },
                            textDecoration = if (todo.isDone) TextDecoration.LineThrough else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoMark(status: TodoStatus) {
    val colors = LocalDshColors.current
    when (status) {
        TodoStatus.Completed -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = colors.stateSuccess,
            modifier = Modifier.size(13.dp),
        )
        TodoStatus.InProgress -> Box(
            Modifier
                .padding(top = 4.dp)
                .size(7.dp)
                .background(colors.buttonInfoFill, RoundedCornerShape(DshRadius.full.dp)),
        )
        TodoStatus.Pending -> Box(
            Modifier
                .padding(top = 4.dp)
                .size(7.dp)
                .border(1.dp, colors.borderL4, RoundedCornerShape(DshRadius.full.dp)),
        )
    }
}

/**
 * One pending approval.
 *
 * Mirrors the web panel: a warning strip, the headline, the offending command,
 * then an explicit allow/reject pair. Nothing is pre-selected.
 */
@Composable
fun ApprovalCard(
    toolName: String,
    reason: String,
    detail: String?,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshColors.current

    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, colors.stateWarn.copy(alpha = 0.5f), RoundedCornerShape(DshRadius.lg.dp))
            .background(colors.inputMajor, RoundedCornerShape(DshRadius.lg.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.stateWarn.copy(alpha = 0.12f))
                .padding(horizontal = DshSpacing.md.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "需要你确认",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.stateWarn,
            )
        }

        Column(
            Modifier.padding(horizontal = DshSpacing.md.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = reason,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = colors.labelPrimary,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = colors.labelTertiary,
                )
            }
            Text(
                text = toolName,
                fontSize = 11.sp,
                color = colors.labelCaption,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DshSpacing.md.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "拒绝",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.labelSecondary,
                modifier = Modifier
                    .clickable(onClick = onReject)
                    .border(0.5.dp, colors.borderL3, RoundedCornerShape(DshRadius.full.dp))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "允许一次",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.buttonPrimaryForeground,
                modifier = Modifier
                    .clickable(onClick = onAllow)
                    .background(colors.buttonPrimaryFill, RoundedCornerShape(DshRadius.full.dp))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}
