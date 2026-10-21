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
import androidx.compose.runtime.LaunchedEffect
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

/**
 * Reasoning disclosure, transcribed from the web client's `ReasoningRow`.
 *
 * The web behaviour, and why each part exists:
 *
 *  - it stays **collapsed** unless the reader opens it, so a finished transcript
 *    stays compact;
 *  - the collapsed row is **not** a bare label: it carries a summary that, while
 *    running, is the first line of the **latest completed paragraph** and, once
 *    settled, the first line of the block. `**` markers are stripped. That summary
 *    advancing is what makes the thinking legible while it streams; without it the
 *    panel said only 思考 and the text appeared in one lump on commit;
 *  - `expandOnRowClick` — the whole row toggles, not just a chevron.
 *
 * The title reads 正在思考 while the block is the streaming tail and 思考 once it is
 * settled, matching the ask from the device: collapsed while working, but visibly
 * working.
 */
@Composable
fun ReasoningBlock(text: String, streaming: Boolean) {
    val colors = LocalDshColors.current
    var expanded by remember { mutableStateOf(false) }

    // While running the summary is the latest *completed* paragraph's first line, so
    // it advances paragraph by paragraph instead of flickering on every token.
    val summary = remember(text, streaming) {
        val source = if (streaming) latestCompletedParagraphFirstLine(text) else firstLine(text)
        source.replace("**", "").trim()
    }

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
                tint = if (streaming) colors.deepDiving else colors.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (streaming) "正在思考" else "思考",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (streaming) colors.deepDiving else colors.labelTertiary,
            )
            if (!expanded && summary.isNotBlank()) {
                // The collapsed summary is the whole point: it is the live preview.
                Text(
                    text = " · ",
                    fontSize = 13.sp,
                    color = colors.labelCaption,
                )
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = colors.labelCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = colors.labelTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            // Tapping anywhere in the open body collapses it, which is what the web
            // client's expand-on-row-click region covers.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = false }
                    .padding(
                        start = DshSpacing.md.dp,
                        end = DshSpacing.md.dp,
                        bottom = DshSpacing.md.dp,
                    ),
            ) {
                MarkdownText(
                    source = text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * First line of the last paragraph that already has a newline after it.
 *
 * Mirrors the web client's `latestCompletedParagraphFirstLine`: a paragraph counts
 * only once its first line is terminated, so the preview does not grow mid-line.
 */
internal fun latestCompletedParagraphFirstLine(text: String): String {
    var summary = ""
    var paragraphStart = 0
    val separator = Regex("\\r?\\n(?:[\\t ]*\\r?\\n)+")
    while (true) {
        val next = separator.find(text, paragraphStart)
        val paragraphEnd = next?.range?.first ?: text.length
        val newline = text.indexOf('\n', paragraphStart)
        if (newline != -1 && newline <= paragraphEnd) {
            val candidate = text.substring(paragraphStart, newline).trim()
            if (candidate.isNotEmpty()) summary = candidate
        }
        if (next == null) return summary
        paragraphStart = next.range.last + 1
    }
}

/** First non-empty line of a settled block. */
internal fun firstLine(text: String): String =
    text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** One tool invocation with its collapsed result. */
@Composable
fun ToolCallCard(item: TimelineItem.ToolCallItem) {
    val colors = LocalDshColors.current
    var expanded by remember { mutableStateOf(false) }
    val digest = remember(item.arguments) { digestArguments(item.name, item.arguments) }
    val output = remember(item.result) { classifyToolOutput(item.result) }
    val resultPreview = remember(output) { previewOf(output) }
    val detail = remember(item.name, item.arguments, item.result, item.isError) {
        toolDetailOf(item.name, item.arguments, item.result, item.isError)
    }
    // Diff counters belong on the collapsed row: an edit is most usefully
    // summarised as "how much moved", not as the path alone.
    val counts = remember(detail) { diffCounts(detail) }

    // Title preference: the model's own explanation, then the concrete target,
    // then the tool's friendly name. This is what the web transcript shows.
    val title = digest.headline.ifBlank { displayName(item.name) }
    val subtitle = when {
        digest.headline.isNotBlank() && digest.detail.isNotBlank() -> digest.detail
        digest.headline.isBlank() && digest.detail.isNotBlank() -> digest.detail
        else -> resultPreview
    }
    val subtitleIsCommand = digest.detail.isNotBlank() && subtitle == digest.detail

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
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (counts != null && (counts.first + counts.second) > 0) {
                        Spacer(Modifier.width(6.dp))
                        DiffStat(counts.first, counts.second)
                    } else if (output is ToolOutput.Diff && (output.added + output.removed) > 0) {
                        Spacer(Modifier.width(6.dp))
                        DiffStat(output.added, output.removed)
                    }
                }
                if (subtitle.isNotBlank() && !expanded) {
                    Text(
                        text = subtitle,
                        fontSize = 11.5.sp,
                        color = if (subtitleIsCommand) colors.labelTertiary else colors.labelCaption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = if (subtitleIsCommand) FontFamily.Monospace else FontFamily.Default,
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
            ) {
                ToolDetailView(detail)
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

/**
 * Split a tool call's arguments into the human explanation and the concrete
 * target.
 *
 * `pwsh` calls carry both: `description` is the model's own sentence about what
 * the command does, while `command` is the thing being run. Showing only the
 * command loses the intent; showing them on one line conflates two different
 * kinds of information, so the card renders the explanation as its title and the
 * command as a monospace subtitle.
 */
internal data class ArgumentDigest(val headline: String, val detail: String)

internal fun digestArguments(name: String, arguments: String): ArgumentDigest {
    if (arguments.isBlank()) return ArgumentDigest("", "")

    val parsed = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
        ?: return ArgumentDigest("", arguments.replace('\n', ' ').take(160))

    fun value(key: String): String? =
        (parsed[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

    val explanation = value("description") ?: value("explanation")
    val target = value("command")
        ?: value("file_path")
        ?: value("path")
        ?: value("pattern")
        ?: value("query")
        ?: value("url")
        ?: value("prompt")

    return when {
        explanation != null && target != null -> ArgumentDigest(explanation, target)
        explanation != null -> ArgumentDigest(explanation, "")
        target != null -> ArgumentDigest("", target)
        else -> {
            // No recognized field: fall back to the first short scalar so the row
            // still says something specific instead of nothing.
            val firstScalar = parsed.entries
                .mapNotNull { (_, element) -> (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }
                .firstOrNull { it.isNotBlank() && it.length <= 120 }
            ArgumentDigest("", firstScalar ?: "")
        }
    }
}
