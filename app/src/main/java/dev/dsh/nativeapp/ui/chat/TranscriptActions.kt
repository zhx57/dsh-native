package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.ContentBlock
import dev.dsh.nativeapp.data.TimelineItem
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Single action row beneath the transcript.
 *
 * Copying the whole conversation is what people actually want from a phone
 * transcript; per-message copy buttons were removed because each one cost a line
 * of height. Branching lives here too, so a stray long-press can no longer fork a
 * session by accident — it needs an explicit tap.
 */
@Composable
fun TranscriptActions(
    onCopyAll: () -> Unit,
    onBranch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshColors.current
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs.dp),
    ) {
        ActionPill(
            icon = Icons.Outlined.ContentCopy,
            label = "复制全部",
            onClick = onCopyAll,
        )
        ActionPill(
            icon = Icons.Outlined.AccountTree,
            label = "回退上一轮",
            onClick = onBranch,
        )
        Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Flatten the transcript to plain text for the clipboard. */
fun plainTextOf(timeline: List<TimelineItem>): String = buildString {
    timeline.forEach { item ->
        when (item) {
            is TimelineItem.UserMessage -> {
                append("## 我\n")
                item.blocks.filterIsInstance<ContentBlock.Text>().forEach { append(it.text) }
                append("\n\n")
            }
            is TimelineItem.AssistantMessage -> {
                item.blocks.filterIsInstance<ContentBlock.Text>().forEach {
                    append(it.text)
                    append("\n\n")
                }
            }
            else -> Unit
        }
    }
}.trim()

/** Sequence of the last assistant message, for branching at that point. */
fun branchSeqOf(timeline: List<TimelineItem>): Long? =
    timeline.lastOrNull { it is TimelineItem.AssistantMessage }?.seq
