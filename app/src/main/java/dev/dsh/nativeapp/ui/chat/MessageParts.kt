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
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.text.style.TextOverflow
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
    val output = remember(item.result) { classifyToolOutput(item.result) }
    val resultPreview = remember(output) { previewOf(output) }

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
                imageVector = iconFor(item.name),
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName(item.name),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.labelPrimary,
                    )
                    if (output is ToolOutput.Diff && (output.added + output.removed) > 0) {
                        Spacer(Modifier.width(6.dp))
                        DiffStat(output.added, output.removed)
                    }
                }
                val subtitle = summary.ifBlank { resultPreview }
                if (subtitle.isNotBlank() && !expanded) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = colors.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = if (summary.isBlank()) FontFamily.Default else FontFamily.Monospace,
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
                    ToolOutputView(output)
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

/** A short human-readable gist of a result, used when no argument digest exists. */
private fun previewOf(output: ToolOutput): String = when (output) {
    ToolOutput.Empty -> ""
    is ToolOutput.Diff -> "改动 ${output.lines.size} 行"
    is ToolOutput.Lines -> {
        val first = output.lines.firstOrNull { it.text.isNotBlank() }?.text.orEmpty()
        when {
            output.lines.size == 1 -> first.take(110)
            else -> "${output.lines.size} 行输出 · ${first.take(80)}"
        }
    }
    is ToolOutput.Text -> output.body.lineSequence().firstOrNull().orEmpty().take(110)
}

/** Friendlier label for a tool id. */
private fun displayName(name: String): String = when (name) {
    "pwsh", "bash", "shell" -> "终端命令"
    "read", "fs_read" -> "读取文件"
    "write", "fs_write" -> "写入文件"
    "edit", "str_replace_editor", "str-replace-editor" -> "编辑文件"
    "grep", "fs_search", "search" -> "搜索内容"
    "glob", "find" -> "查找文件"
    "todo", "todo_write" -> "更新待办"
    "web_search" -> "网页搜索"
    "web_fetch" -> "抓取网页"
    "present" -> "展示文件"
    "task", "subagent" -> "子任务"
    else -> name
}

private fun iconFor(name: String): androidx.compose.ui.graphics.vector.ImageVector = when (name) {
    "read", "fs_read", "write", "fs_write", "edit", "str_replace_editor", "str-replace-editor" ->
        Icons.Outlined.Description
    "web_search", "web_fetch" -> Icons.Outlined.Language
    "todo", "todo_write" -> Icons.Outlined.Checklist
    "grep", "fs_search", "search", "glob", "find" -> Icons.Outlined.Search
    else -> Icons.Outlined.Terminal
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
