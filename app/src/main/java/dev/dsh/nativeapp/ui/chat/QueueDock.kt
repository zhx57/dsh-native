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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.QueuedMessage
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Queue dock for messages sent while a turn is running.
 *
 * Mirrors the web UI's dock: a collapsed header showing the count, expanding to one
 * row per message. A single message shows its row directly. Each row offers
 * 插话发送 (send now), 编辑 and 删除 — the same three actions the web dock exposes
 * through `session/updateQueue`.
 */
@Composable
fun QueueDock(
    queued: List<QueuedMessage>,
    running: Boolean,
    onSteer: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queued.isEmpty()) return
    val colors = LocalDshColors.current
    var collapsed by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<String?>(null) }

    // A single queued message needs no disclosure control.
    val expanded = !collapsed || queued.size == 1

    Column(
        modifier
            .fillMaxWidth()
            .border(0.5.dp, colors.borderL2, RoundedCornerShape(DshRadius.lg.dp))
            .background(colors.sidebarFill, RoundedCornerShape(DshRadius.lg.dp)),
    ) {
        if (queued.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { collapsed = !collapsed }
                    .padding(horizontal = DshSpacing.md.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${queued.size} 条排队消息",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.labelSecondary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(
                    start = DshSpacing.sm.dp,
                    end = DshSpacing.sm.dp,
                    bottom = DshSpacing.sm.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                queued.forEach { item ->
                    if (editing == item.id) {
                        QueueEditor(
                            initial = item.text,
                            onSave = { text ->
                                onEdit(item.id, text)
                                editing = null
                            },
                            onCancel = { editing = null },
                        )
                    } else {
                        QueueRow(
                            item = item,
                            running = running,
                            onSteer = { onSteer(item.id) },
                            onEdit = { editing = item.id },
                            onRemove = { onRemove(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueuedMessage,
    running: Boolean,
    onSteer: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalDshColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgBase, RoundedCornerShape(DshRadius.md.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = item.text.ifBlank { "（仅附件）" },
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = colors.labelPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        // Attachments are listed because they cannot be edited as text.
        if (item.imageCount > 0 || item.fileNames.isNotEmpty()) {
            Text(
                text = buildString {
                    if (item.imageCount > 0) append("${item.imageCount} 张图片")
                    if (item.fileNames.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(item.fileNames.joinToString("、"))
                    }
                },
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Default,
                color = colors.labelCaption,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm.dp)) {
            if (running) {
                QueueAction("插话发送") { onSteer() }
            }
            if (item.editable) {
                QueueAction("编辑") { onEdit() }
            }
            QueueAction("删除", danger = true) { onRemove() }
        }
    }
}

@Composable
private fun QueueAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalDshColors.current
    Text(
        text = label,
        fontSize = 11.sp,
        color = if (danger) colors.stateError else colors.buttonInfoFill,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 2.dp),
    )
}

/** Inline editor for one queued message. */
@Composable
private fun QueueEditor(
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalDshColors.current
    var text by remember { mutableStateOf(initial) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgBase, RoundedCornerShape(DshRadius.md.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontSize = 12.5.sp, color = colors.labelPrimary, lineHeight = 18.sp),
            cursorBrush = SolidColor(colors.labelPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.sidebarFill, RoundedCornerShape(DshRadius.sm.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm.dp)) {
            QueueAction("保存") { if (text.isNotBlank()) onSave(text) }
            QueueAction("取消") { onCancel() }
        }
        Spacer(Modifier.width(1.dp))
    }
}
