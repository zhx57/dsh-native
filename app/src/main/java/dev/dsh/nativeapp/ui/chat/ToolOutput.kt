package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * How a tool result should be presented.
 *
 * Raw text is what the model sees; it is a poor thing to show a person. Each
 * variant picks the structure that actually carries the information — a diff
 * wants line coloring, a file listing wants a column grid, a JSON blob wants
 * indentation.
 */
sealed interface ToolOutput {
    data class Diff(val lines: List<DiffLine>, val added: Int, val removed: Int) : ToolOutput
    data class Lines(val lines: List<OutputLine>) : ToolOutput
    data class Text(val body: String) : ToolOutput
    data object Empty : ToolOutput
}

/** One colored diff line. */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { Added, Removed, Context, Header }
}

/** One output line with an optional leading line number and severity. */
data class OutputLine(val text: String, val severity: Severity = Severity.Plain) {
    enum class Severity { Plain, Error, Warn, Success }
}

/** Detect the most useful rendering for a tool result. */
fun classifyToolOutput(raw: String?): ToolOutput {
    if (raw.isNullOrBlank()) return ToolOutput.Empty
    val text = raw.trimEnd()

    unifiedDiff(text)?.let { return it }

    val lines = text.split('\n')
    // Very large results would stall layout; keep a readable head and say so.
    val capped = if (lines.size > 400) lines.take(400) + listOf("… 还有 ${lines.size - 400} 行") else lines
    return ToolOutput.Lines(capped.map { line -> OutputLine(line, severityOf(line)) })
}

private fun unifiedDiff(text: String): ToolOutput.Diff? {
    val lines = text.split('\n')
    val hasHunk = lines.any { it.startsWith("@@") }
    val hasMarkers = lines.count { it.startsWith("+++") || it.startsWith("---") } >= 2
    if (!hasHunk && !hasMarkers) return null

    var added = 0
    var removed = 0
    val parsed = lines.map { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") ||
                line.startsWith("index ") || line.startsWith("@@") -> {
                DiffLine(DiffLine.Kind.Header, line)
            }
            line.startsWith("+") -> {
                added++
                DiffLine(DiffLine.Kind.Added, line.removePrefix("+"))
            }
            line.startsWith("-") -> {
                removed++
                DiffLine(DiffLine.Kind.Removed, line.removePrefix("-"))
            }
            else -> DiffLine(DiffLine.Kind.Context, line.removePrefix(" "))
        }
    }
    if (added + removed == 0 && !hasHunk) return null
    return ToolOutput.Diff(parsed, added, removed)
}

private val ERROR_HINT = Regex("(?i)\\b(error|fatal|exception|failed|failure|traceback)\\b")
private val WARN_HINT = Regex("(?i)\\b(warn|warning|deprecated)\\b")
private val SUCCESS_HINT = Regex("(?i)\\b(success|passed|ok|done|created|updated)\\b")

private fun severityOf(line: String): OutputLine.Severity = when {
    line.isBlank() -> OutputLine.Severity.Plain
    ERROR_HINT.containsMatchIn(line) -> OutputLine.Severity.Error
    WARN_HINT.containsMatchIn(line) -> OutputLine.Severity.Warn
    SUCCESS_HINT.containsMatchIn(line) -> OutputLine.Severity.Success
    else -> OutputLine.Severity.Plain
}

/** Render one classified tool result. */
@Composable
fun ToolOutputView(output: ToolOutput, modifier: Modifier = Modifier) {
    val colors = LocalDshColors.current

    when (output) {
        ToolOutput.Empty -> Unit

        is ToolOutput.Diff -> Column(modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "+${output.added}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.stateSuccess,
                )
                Text(
                    text = "−${output.removed}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.stateError,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.bgBase, RoundedCornerShape(DshRadius.sm.dp)),
            ) {
                output.lines.forEach { line ->
                    val background = when (line.kind) {
                        DiffLine.Kind.Added -> colors.diffAdded
                        DiffLine.Kind.Removed -> colors.diffDeleted
                        else -> androidx.compose.ui.graphics.Color.Transparent
                    }
                    val foreground = when (line.kind) {
                        DiffLine.Kind.Header -> colors.labelCaption
                        DiffLine.Kind.Added -> colors.labelPrimary
                        DiffLine.Kind.Removed -> colors.labelPrimary
                        DiffLine.Kind.Context -> colors.labelSecondary
                    }
                    Text(
                        text = line.text.ifEmpty { " " },
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = foreground,
                        softWrap = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(background)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }

        is ToolOutput.Lines -> Column(modifier.fillMaxWidth()) {
            output.lines.forEachIndexed { index, line ->
                val color = when (line.severity) {
                    OutputLine.Severity.Error -> colors.stateError
                    OutputLine.Severity.Warn -> colors.stateWarn
                    OutputLine.Severity.Success -> colors.labelSecondary
                    OutputLine.Severity.Plain -> colors.labelSecondary
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = (index + 1).toString(),
                        fontSize = 10.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.labelCaption.copy(alpha = 0.6f),
                        modifier = Modifier
                            .width(26.dp)
                            .padding(top = 1.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    Text(
                        text = line.text.ifEmpty { " " },
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        softWrap = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is ToolOutput.Text -> Text(
            text = output.body,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.labelSecondary,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** Header summary line for a diff-style tool result. */
@Composable
fun DiffStat(added: Int, removed: Int) {
    val colors = LocalDshColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "+$added", fontSize = 11.sp, color = colors.stateSuccess)
        Text(text = "−$removed", fontSize = 11.sp, color = colors.stateError)
    }
}

/** Small colored bar used where a one-glyph status is enough. */
@Composable
fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .height(6.dp)
            .width(6.dp)
            .background(color, RoundedCornerShape(DshRadius.full.dp)),
    )
}

/** Color a whole line for emphasis inside a plain text block. */
internal fun colorize(text: String, color: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = color), 0, text.length)
    }

/** Indent a JSON blob when it parses; otherwise return it unchanged. */
fun prettyJson(raw: String): String? = runCatching {
    val element = kotlinx.serialization.json.Json.parseToJsonElement(raw)
    val pretty = kotlinx.serialization.json.Json { prettyPrint = true }
    pretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
}.getOrNull()
