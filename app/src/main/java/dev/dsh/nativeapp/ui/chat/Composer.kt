package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Composer docked at the bottom of the transcript.
 *
 * Mirrors the web composer's shape: a rounded surface holding the input plus a
 * trailing action that switches between send and stop while a turn runs.
 */
@Composable
fun Composer(
    enabled: Boolean,
    running: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    modelLabel: String? = null,
    onModelClick: () -> Unit = {},
    draft: String = "",
    onDraftChange: (String) -> Unit = {},
    commands: List<dev.dsh.nativeapp.data.SlashCommand> = emptyList(),
    onSubmitCommand: (String) -> Unit = {},
    permissionPreset: String? = null,
    onPermissionClick: () -> Unit = {},
) {
    val colors = LocalDshColors.current
    var text by remember { mutableStateOf(draft) }
    // Keep the field in sync when the caller rewrites the draft (command completion).
    LaunchedEffect(draft) {
        if (draft != text) text = draft
    }
    val canSend = enabled && text.isNotBlank()
    val commandQuery = awaitingCommand(text)

    Column(modifier = modifier.fillMaxWidth()) {
        if (commandQuery != null && commands.isNotEmpty()) {
            CommandSuggestions(
                commands = commands,
                query = commandQuery,
                onPick = { command ->
                    val completed = "/${command.name} "
                    text = completed
                    onDraftChange(completed)
                },
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .border(0.5.dp, colors.borderL3, RoundedCornerShape(DshRadius.xxl.dp))
                .background(colors.inputMajor, RoundedCornerShape(DshRadius.xxl.dp))
                .padding(start = DshSpacing.lg.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onDraftChange(it)
                },
                enabled = enabled,
                textStyle = TextStyle(fontSize = 15.sp, color = colors.labelPrimary, lineHeight = 22.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.labelPrimary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp, max = 160.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "给 DSH 发消息…",
                                fontSize = 15.sp,
                                color = colors.labelCaption,
                            )
                        }
                        inner()
                    }
                },
            )

            Spacer(Modifier.size(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The model chip takes whatever is left after the fixed-size send
                // button and truncates, so a long model id can never push the
                // actions off a narrow screen.
                if (modelLabel != null) {
                    Row(
                        Modifier
                            .weight(1f, fill = false)
                            .background(colors.selector, RoundedCornerShape(DshRadius.full.dp))
                            .clickable(onClick = onModelClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = modelLabel,
                            fontSize = 11.sp,
                            color = colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                PermissionChip(preset = permissionPreset, onClick = onPermissionClick)
                Spacer(Modifier.width(4.dp))

                if (running) {
                    IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "停止",
                            tint = colors.labelPrimary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(
                                if (canSend) colors.buttonPrimaryFill else colors.bgSkeleton,
                                RoundedCornerShape(DshRadius.full.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    if (text.trimStart().startsWith("/")) {
                                        onSubmitCommand(text.trim())
                                    } else {
                                        onSend(text)
                                    }
                                    text = ""
                                    onDraftChange("")
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (canSend) colors.buttonPrimaryForeground else colors.labelCaption,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
