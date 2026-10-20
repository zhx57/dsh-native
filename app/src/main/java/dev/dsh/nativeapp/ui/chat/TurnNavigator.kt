package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Turn ladder: jump between conversation turns, not between rows or messages.
 *
 * The transcript of a real session is dominated by tool calls — one turn can span
 * dozens of rows — so stepping by row or by message made a tap look like it did
 * nothing. The web client's rail navigates *turns*, and that is what a reader
 * actually wants: "/35" jumps to that turn, the arrows step one turn at a time.
 */
@Composable
fun TurnNavigator(
    currentTurn: Int?,
    totalTurns: Int,
    canGoUp: Boolean,
    canGoDown: Boolean,
    showJumpToLatest: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJumpToLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshColors.current

    Column(
        modifier
            .background(colors.bgLayer1.copy(alpha = 0.94f), RoundedCornerShape(DshRadius.full.dp))
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        StepButton(
            icon = Icons.Filled.ArrowUpward,
            label = "上一轮",
            enabled = canGoUp,
            onClick = onPrevious,
        )
        // The counter doubles as the jump-to-latest affordance: on a 200dp-wide
        // screen a separate button cost more height than the transcript could spare.
        TurnCounter(
            currentTurn = currentTurn,
            totalTurns = totalTurns,
            highlight = showJumpToLatest,
            onClick = onJumpToLatest,
        )
        StepButton(
            icon = Icons.Filled.ArrowDownward,
            label = "下一轮",
            enabled = canGoDown,
            onClick = onNext,
        )
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDshColors.current
    val tint = if (enabled) colors.labelSecondary else colors.labelCaption.copy(alpha = 0.3f)
    Box(
        Modifier
            .size(24.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * `当前/总数` pill.
 *
 * When the viewport is not on the newest turn the pill is tinted, which is the
 * only cue the operator needs to know a tap returns them to the tail.
 */
@Composable
private fun TurnCounter(
    currentTurn: Int?,
    totalTurns: Int,
    highlight: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDshColors.current
    if (totalTurns <= 0) {
        Spacer(Modifier.height(2.dp))
        return
    }
    val tint = if (highlight) colors.brandPrimary else colors.labelTertiary
    Box(
        Modifier
            .width(24.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = currentTurn?.toString() ?: "–",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
            Box(
                Modifier
                    .padding(vertical = 0.5.dp)
                    .width(9.dp)
                    .height(0.6.dp)
                    .background(tint.copy(alpha = 0.5f)),
            )
            Text(
                text = totalTurns.toString(),
                fontSize = 8.5.sp,
                color = colors.labelCaption,
                maxLines = 1,
            )
        }
    }
}

/** Bar showing where the viewport sits inside the turn list. */
@Composable
fun TurnProgress(
    currentTurn: Int?,
    totalTurns: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshColors.current
    if (totalTurns <= 1) return
    val fraction = ((currentTurn ?: 1).toFloat() / totalTurns.toFloat()).coerceIn(0f, 1f)
    Row(
        modifier
            .width(2.dp)
            .fillMaxHeight()
            .background(colors.borderL1),
    ) {
        Spacer(Modifier.height(0.dp))
        Box(
            Modifier
                .fillMaxHeight(fraction.coerceAtLeast(0.04f))
                .width(2.dp)
                .background(colors.labelTertiary.copy(alpha = 0.5f)),
        )
    }
}
