package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Floating transcript navigator.
 *
 * Up and down step between *messages* (a user turn or an assistant turn), not
 * between list rows: one turn spans dozens of tool-call rows, so row-stepping made
 * a single tap look like it did nothing but scroll a few pixels. The double arrow
 * returns to the newest message.
 */
@Composable
fun TranscriptNavigator(
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
            .background(colors.bgLayer1.copy(alpha = 0.92f), RoundedCornerShape(DshRadius.full.dp))
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        NavArrow(up = true, enabled = canGoUp, onClick = onPrevious)
        if (showJumpToLatest) {
            JumpArrow(enabled = true, onClick = onJumpToLatest)
        }
        NavArrow(up = false, enabled = canGoDown, onClick = onNext)
    }
}

@Composable
private fun NavArrow(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalDshColors.current
    val tint = if (enabled) colors.labelSecondary else colors.labelCaption.copy(alpha = 0.35f)
    Box(
        Modifier
            .size(26.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (up) "上一条消息" else "下一条消息",
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** Double chevron: jump straight to the newest message. */
@Composable
private fun JumpArrow(enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalDshColors.current
    val tint = if (enabled) colors.labelSecondary else colors.labelCaption.copy(alpha = 0.35f)
    Box(
        Modifier
            .size(26.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(13.dp),
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "跳到最新消息",
                tint = tint,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}
