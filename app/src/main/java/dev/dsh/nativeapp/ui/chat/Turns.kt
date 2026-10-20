package dev.dsh.nativeapp.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.ContentBlock
import dev.dsh.nativeapp.data.TimelineItem
import dev.dsh.nativeapp.ui.markdown.MarkdownText
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Operator turn: right-aligned bubble, matching the web transcript.
 *
 * Text is selectable so a phrase can be copied with the system handles; the
 * transcript deliberately carries no per-message button row, which on a small
 * screen cost more height than the content.
 */
@Composable
fun UserTurn(item: TimelineItem.UserMessage) {
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
                .padding(horizontal = DshSpacing.lg.dp, vertical = DshSpacing.md.dp),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    color = colors.labelPrimary,
                )
            }
        }
    }
}

/** Assistant turn: reasoning plus body, both selectable. */
@Composable
fun AssistantTurn(item: TimelineItem.AssistantMessage) {
    val textBlocks = item.blocks.filterIsInstance<ContentBlock.Text>()
    val reasoning = item.blocks.filterIsInstance<ContentBlock.Reasoning>().joinToString("\n\n") { it.text }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (reasoning.isNotBlank()) {
            ReasoningBlock(reasoning, item.streaming)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                textBlocks.forEach { block ->
                    if (block.text.isNotBlank()) {
                        MarkdownText(source = block.text, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (item.streaming) {
            TypingIndicator()
        }
    }
}

/**
 * Three-dot pulse shown while a step is generating.
 *
 * The dots animate rather than sitting still: a static "…" is indistinguishable
 * from a frozen screen when a tool call takes a while.
 */
@Composable
fun TypingIndicator() {
    val colors = LocalDshColors.current
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dot",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            // Each dot peaks a third of a cycle apart, so the row reads as a wave.
            val distance = (phase - index + 3f) % 3f
            val active = distance < 1f
            val scale = if (active) 1f - (distance * 0.45f) else 0.7f
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size((4 + 2 * scale).dp)
                    .background(
                        colors.labelTertiary.copy(alpha = 0.5f + 0.5f * scale),
                        RoundedCornerShape(DshRadius.full.dp),
                    ),
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(text = "正在生成", fontSize = 11.sp, color = colors.labelTertiary)
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
