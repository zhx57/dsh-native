package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.SlashCommand
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Slash-command suggestions shown above the composer.
 *
 * Visible only while the draft starts with `/` and has no space yet, matching the
 * web composer's trigger. Selecting a command completes the draft rather than
 * firing immediately, so a command that takes an argument stays editable.
 */
@Composable
fun CommandSuggestions(
    commands: List<SlashCommand>,
    query: String,
    onPick: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (commands.isEmpty()) return
    val colors = LocalDshColors.current
    val filtered = commands.filter { it.name.startsWith(query, ignoreCase = true) }
    if (filtered.isEmpty()) return

    Column(
        modifier
            .fillMaxWidth()
            .border(0.5.dp, colors.borderL3, RoundedCornerShape(DshRadius.xl.dp))
            .background(colors.inputMajor, RoundedCornerShape(DshRadius.xl.dp)),
    ) {
        LazyColumn(
            Modifier.heightIn(max = 220.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
        ) {
            items(items = filtered, key = { it.name }) { command ->
                CommandRow(command) { onPick(command) }
            }
        }
    }
}

@Composable
private fun CommandRow(command: SlashCommand, onClick: () -> Unit) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DshSpacing.md.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "/${command.name}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = colors.labelPrimary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = command.description,
                fontSize = 11.5.sp,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (command.hint != null) {
                Text(
                    text = command.hint,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.labelCaption,
                    maxLines = 1,
                )
            }
        }
    }
}

/** True when a draft is asking for command completion. */
fun awaitingCommand(draft: String): String? {
    if (!draft.startsWith("/")) return null
    val body = draft.removePrefix("/")
    return if (body.contains(' ') || body.contains('\n')) null else body
}
