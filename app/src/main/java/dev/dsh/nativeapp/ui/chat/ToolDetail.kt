package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a tool call should show when expanded.
 *
 * The raw result string is what the model sees; it is rarely what a person wants.
 * Each tool gets the shape that carries the meaning: a shell call shows the command
 * and its output, a file edit shows a diff, a read shows the file body.
 */
sealed interface ToolDetail {
    /** Shell-like call: the command that ran and what came back. */
    data class Shell(val command: String, val output: String, val isError: Boolean) : ToolDetail

    /** File edit: unified diff when available, else the changed snippet. */
    data class Edit(val path: String?, val diff: ToolOutput, val isError: Boolean) : ToolDetail

    /** File read: the path and the body. */
    data class Read(val path: String?, val body: String) : ToolDetail

    /** Anything else: label plus body. */
    data class Plain(val input: String, val output: String) : ToolDetail
}

/** Build the presentation for one tool call. */
fun toolDetailOf(name: String, arguments: String, result: String?, isError: Boolean): ToolDetail {
    val args = runCatching {
        Json.parseToJsonElement(arguments) as? JsonObject
    }.getOrNull()

    fun arg(key: String): String? =
        (args?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

    val body = result.orEmpty()

    return when (name) {
        "pwsh", "bash", "shell", "terminal" -> ToolDetail.Shell(
            command = arg("command") ?: arg("cmd") ?: arguments,
            output = body,
            isError = isError,
        )

        "edit", "str_replace_editor", "str-replace-editor", "write", "fs_write", "multi_edit" -> {
            val diff = classifyToolOutput(body)
            val shown = if (diff is ToolOutput.Diff) {
                diff
            } else {
                // An edit result often echoes only a confirmation line; show the
                // changed text from the arguments instead of that.
                val oldText = arg("old_string") ?: arg("old_str") ?: arg("old")
                val newText = arg("new_string") ?: arg("new_str") ?: arg("new") ?: arg("content")
                if (oldText != null || newText != null) {
                    diffOf(oldText, newText)
                } else {
                    diff
                }
            }
            ToolDetail.Edit(path = arg("path") ?: arg("file_path"), diff = shown, isError = isError)
        }

        "read", "fs_read" -> ToolDetail.Read(
            path = arg("path") ?: arg("file_path"),
            body = body,
        )

        else -> ToolDetail.Plain(input = arguments, output = body)
    }
}

/** Render a matched-pair edit as a two-part diff. */
private fun diffOf(oldText: String?, newText: String?): ToolOutput {
    val lines = buildList {
        oldText?.lineSequence()?.forEach { add(DiffLine(DiffLine.Kind.Removed, it)) }
        newText?.lineSequence()?.forEach { add(DiffLine(DiffLine.Kind.Added, it)) }
    }
    if (lines.isEmpty()) return ToolOutput.Empty
    return ToolOutput.Diff(
        lines = lines,
        added = lines.count { it.kind == DiffLine.Kind.Added },
        removed = lines.count { it.kind == DiffLine.Kind.Removed },
    )
}

/** Added/removed counts for the collapsed subtitle of an edit card. */
fun diffCounts(detail: ToolDetail): Pair<Int, Int>? = when (detail) {
    is ToolDetail.Edit -> (detail.diff as? ToolOutput.Diff)?.let { it.added to it.removed }
    else -> null
}

/** Expanded body of a tool card. */
@Composable
fun ToolDetailView(detail: ToolDetail, modifier: Modifier = Modifier) {
    val colors = LocalDshColors.current

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (detail) {
            is ToolDetail.Shell -> {
                LabelledBlock(
                    label = "命令",
                    body = detail.command.trim(),
                    tint = colors.labelSecondary,
                )
                if (detail.output.isNotBlank()) {
                    LabelledBlock(
                        label = if (detail.isError) "错误" else "输出",
                        body = null,
                        tint = if (detail.isError) colors.stateError else colors.labelSecondary,
                    ) {
                        ToolOutputView(classifyToolOutput(detail.output))
                    }
                }
            }

            is ToolDetail.Edit -> {
                if (detail.path != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = detail.path,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.labelTertiary,
                        )
                        diffCounts(detail)?.let { (added, removed) ->
                            Text(
                                text = "  +$added",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.stateSuccess,
                            )
                            Text(
                                text = "  −$removed",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.stateError,
                            )
                        }
                    }
                }
                ToolOutputView(detail.diff)
            }

            is ToolDetail.Read -> {
                if (detail.path != null) {
                    Text(
                        text = detail.path,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.labelTertiary,
                    )
                }
                ToolOutputView(classifyToolOutput(detail.body))
            }

            is ToolDetail.Plain -> {
                if (detail.input.isNotBlank()) {
                    LabelledBlock(label = "输入", body = detail.input, tint = colors.labelSecondary)
                }
                if (detail.output.isNotBlank()) {
                    LabelledBlock(label = "输出", body = null, tint = colors.labelSecondary) {
                        ToolOutputView(classifyToolOutput(detail.output))
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelledBlock(
    label: String,
    body: String?,
    tint: Color,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = LocalDshColors.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = colors.labelCaption,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.bgBase, RoundedCornerShape(DshRadius.sm.dp))
                .padding(horizontal = 7.dp, vertical = 6.dp),
        ) {
            if (content != null) {
                content()
            } else if (body != null) {
                Text(
                    text = body,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = tint,
                )
            }
        }
    }
}
