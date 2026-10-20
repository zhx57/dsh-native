package dev.dsh.nativeapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.dsh.nativeapp.data.TimelineItem
import dev.dsh.nativeapp.ui.markdown.MarkdownText
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Reasoning summary block, collapsed by default like the web transcript. */
@Composable
fun ReasoningBlock(text: String, streaming: Boolean) {
    val colors = LocalDshColors.current
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, colors.borderL2, RoundedCornerShape(DshRadius.lg.dp))
            .background(colors.codeBlock, RoundedCornerShape(DshRadius.lg.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = DshSpacing.md.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (streaming) "思考中…" else "已思考",
                fontSize = 13.sp,
                color = colors.labelTertiary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = colors.labelSecondary,
                modifier = Modifier.padding(
                    start = DshSpacing.md.dp,
                    end = DshSpacing.md.dp,
                    bottom = DshSpacing.md.dp,
                ),
            )
        }
    }
}

/** One tool invocation with its collapsed result. */
@Composable
fun ToolCallCard(item: TimelineItem.ToolCallItem) {
    val colors = LocalDshColors.current
    var expanded by remember { mutableStateOf(false) }
    val summary = remember(item.arguments) { summarizeArguments(item.name, item.arguments) }

    Column(
        Modifier
            .fillMaxWidth()
            .border(
                width = 0.5.dp,
                color = if (item.isError) colors.stateError.copy(alpha = 0.4f) else colors.borderL2,
                shape = RoundedCornerShape(DshRadius.lg.dp),
            )
            .background(colors.codeBlock, RoundedCornerShape(DshRadius.lg.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = DshSpacing.md.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.labelPrimary,
                )
                if (summary.isNotBlank() && !expanded) {
                    Text(
                        text = summary,
                        fontSize = 12.sp,
                        color = colors.labelTertiary,
                        maxLines = 1,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                item.running -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.6.dp,
                    color = colors.labelTertiary,
                )
                item.isError -> Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = "失败",
                    tint = colors.stateError,
                    modifier = Modifier.size(16.dp),
                )
                else -> Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "完成",
                    tint = colors.stateSuccess,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(
                    start = DshSpacing.md.dp,
                    end = DshSpacing.md.dp,
                    bottom = DshSpacing.md.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.arguments.isNotBlank()) {
                    SectionLabel("参数")
                    Text(
                        text = item.arguments,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.labelSecondary,
                    )
                }
                if (!item.result.isNullOrBlank()) {
                    SectionLabel(if (item.isError) "错误" else "输出")
                    Text(
                        text = item.result,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (item.isError) colors.stateError else colors.labelSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalDshColors.current
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = colors.labelTertiary,
    )
}

/** One-line argument digest shown while the card is collapsed. */
private fun summarizeArguments(name: String, arguments: String): String {
    if (arguments.isBlank()) return ""
    val parsed = runCatching {
        Json.parseToJsonElement(arguments) as? JsonObject
    }.getOrNull()
    val preferred = listOf("description", "command", "path", "file_path", "pattern", "query", "url")
    if (parsed != null) {
        for (key in preferred) {
            val text = (parsed[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            if (!text.isNullOrBlank()) return text.replace('\n', ' ').take(120)
        }
    }
    return arguments.replace('\n', ' ').take(120)
}
