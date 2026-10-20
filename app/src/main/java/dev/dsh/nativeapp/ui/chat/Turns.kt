package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.ContentBlock
import dev.dsh.nativeapp.data.TimelineItem
import dev.dsh.nativeapp.ui.markdown.MarkdownText
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Operator turn: right-aligned bubble, matching the web transcript. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun UserTurn(
    item: TimelineItem.UserMessage,
    onLongPress: (TimelineItem.UserMessage) -> Unit = {},
) {
    val colors = LocalDshColors.current
    val text = item.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
    val bubbleWidth = LocalConfiguration.current.screenWidthDp.dp * 0.82f

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            Modifier
                .widthIn(max = bubbleWidth)
                .background(colors.bubble, RoundedCornerShape(DshRadius.xl.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onLongPress(item) },
                )
                .padding(horizontal = DshSpacing.lg.dp, vertical = DshSpacing.md.dp),
        ) {
            Text(
                text = text,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = colors.labelPrimary,
            )
        }
    }
}

/** Assistant turn: full-width content with reasoning, text and an action row. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AssistantTurn(
    item: TimelineItem.AssistantMessage,
    onLongPress: (TimelineItem.AssistantMessage) -> Unit = {},
) {
    val colors = LocalDshColors.current
    val clipboard = LocalClipboardManager.current
    val textBlocks = item.blocks.filterIsInstance<ContentBlock.Text>()
    val reasoning = item.blocks.filterIsInstance<ContentBlock.Reasoning>().joinToString("\n\n") { it.text }

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongPress(item) },
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (reasoning.isNotBlank()) {
            ReasoningBlock(reasoning, item.streaming)
        }
        textBlocks.forEach { block ->
            if (block.text.isNotBlank()) {
                MarkdownText(source = block.text, modifier = Modifier.fillMaxWidth())
            }
        }
        if (item.streaming && textBlocks.isEmpty()) {
            TypingIndicator()
        }
        if (!item.streaming && textBlocks.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(textBlocks.joinToString("\n\n") { it.text }))
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制",
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val colors = LocalDshColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size((4 + index).dp)
                    .background(colors.labelTertiary, RoundedCornerShape(DshRadius.full.dp)),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(text = "正在生成", fontSize = 12.sp, color = colors.labelTertiary)
    }
}

@Composable
fun TurnDivider(turn: Int) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(colors.borderL2),
        )
        Text(
            text = "第 $turn 轮",
            fontSize = 11.sp,
            color = colors.labelCaption,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(colors.borderL2),
        )
    }
}
