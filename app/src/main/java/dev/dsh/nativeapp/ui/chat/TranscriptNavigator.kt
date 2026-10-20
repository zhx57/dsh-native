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
 * Floating transcript navigator, matching the reference app.
 *
 * Up jumps to the previous message, down to the next one, and the double-arrow
 * goes straight to the newest message. It is the only way back to the tail once
 * auto-follow has been released by scrolling up, and it gives a reliable way to
 * step through a long conversation without swipe precision.
 */
@Composable
fun TranscriptNavigator(
    canScrollUp: Boolean,
    canScrollDown: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJumpToLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshColors.current

    Column(
        modifier
            .background(colors.bgLayer1.copy(alpha = 0.92f), RoundedCornerShape(DshRadius.full.dp))
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        NavArrow(
            up = true,
            enabled = canScrollUp,
            onClick = onPrevious,
        )
        // The double chevron appears only when the operator is away from the tail,
        // so it does not compete with the single-arrow affordance.
        if (canScrollDown) {
            NavArrow(
                up = false,
                enabled = true,
                double = true,
                onClick = onJumpToLatest,
            )
        }
        NavArrow(
            up = false,
            enabled = canScrollDown,
            onClick = onNext,
        )
    }
}

@Composable
private fun NavArrow(
    up: Boolean,
    enabled: Boolean,
    double: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalDshColors.current
    val tint = if (enabled) colors.labelSecondary else colors.labelCaption.copy(alpha = 0.4f)
    Box(
        Modifier
            .size(28.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (double) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "跳到最新",
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    imageVector = if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (up) "上一条" else "下一条",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
