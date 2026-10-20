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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Composer docked above the navigation bar.
 *
 * Layout rules that came out of the small-screen review:
 *  - the send/stop control is a fixed square pinned to the bottom-right corner, so
 *    it never shares width with the model name and can never be squashed;
 *  - the input grows upward and the chip row sits under it, both scrolling away
 *    from the keyboard rather than shrinking;
 *  - every chip truncates instead of pushing its neighbours.
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
    onPlusClick: () -> Unit = {},
    attachmentNames: List<String> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
) {
    val colors = LocalDshColors.current
    var text by remember { mutableStateOf(draft) }
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
        if (attachmentNames.isNotEmpty()) {
            AttachmentChips(
                names = attachmentNames,
                onRemove = onRemoveAttachment,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .border(0.5.dp, colors.borderL3, RoundedCornerShape(DshRadius.xxl.dp))
                .background(colors.inputMajor, RoundedCornerShape(DshRadius.xxl.dp))
                .padding(start = DshSpacing.md.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onDraftChange(it)
                    },
                    enabled = enabled,
                    textStyle = TextStyle(
                        fontSize = 14.5.sp,
                        color = colors.labelPrimary,
                        lineHeight = 21.sp,
                    ),
                    cursorBrush = SolidColor(colors.labelPrimary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 22.dp, max = 120.dp),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = "给 DSH 发消息…",
                                    fontSize = 14.5.sp,
                                    color = colors.labelCaption,
                                )
                            }
                            inner()
                        }
                    },
                )

                Spacer(Modifier.size(5.dp))

                // Chip row: fixed height and clipped content. The keyboard reduces
                // the window, and a wrapping row here used to squeeze the chips and
                // the send button instead of scrolling.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "添加",
                        tint = colors.labelSecondary,
                        modifier = Modifier
                            .size(17.dp)
                            .clickable(onClick = onPlusClick),
                    )
                    Spacer(Modifier.width(5.dp))
                    if (modelLabel != null) {
                        Text(
                            text = modelLabel,
                            fontSize = 10.5.sp,
                            color = colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .background(colors.selector, RoundedCornerShape(DshRadius.full.dp))
                                .clickable(onClick = onModelClick)
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    PermissionChip(preset = permissionPreset, onClick = onPermissionClick)
                }
            }

            Spacer(Modifier.width(6.dp))

            // Fixed square, always bottom-right. Nothing shares its width.
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        when {
                            running -> colors.selector
                            canSend -> colors.buttonPrimaryFill
                            else -> colors.bgSkeleton
                        },
                        RoundedCornerShape(DshRadius.full.dp),
                    )
                    .clickable(enabled = running || canSend) {
                        if (running) {
                            onStop()
                        } else if (canSend) {
                            if (text.trimStart().startsWith("/")) {
                                onSubmitCommand(text.trim())
                            } else {
                                onSend(text)
                            }
                            text = ""
                            onDraftChange("")
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    running -> Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止",
                        tint = colors.labelPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                    else -> Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (canSend) colors.buttonPrimaryForeground else colors.labelCaption,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}
