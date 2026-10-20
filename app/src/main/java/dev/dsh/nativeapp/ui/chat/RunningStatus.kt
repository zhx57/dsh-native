package dev.dsh.nativeapp.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors
import kotlinx.coroutines.delay

/**
 * Live running indicator: `深度求索中，用时 12秒 ···`.
 *
 * Transcribed from the web client's `RunningStatus`, including its copy and its
 * duration format:
 *
 *  - no known start time → `深度求索中`
 *  - otherwise → `深度求索中，用时 {duration} ···`
 *  - duration uses 小时/分/秒 with the hour part only past an hour and the minute
 *    part only past a minute, so a short turn reads `12秒` rather than `0分12秒`
 *
 * The clock ticks once a second. It is deliberately its own composable so a tick
 * recomposes only this row and not the transcript above it — the web client isolates
 * the same way.
 */
@Composable
fun RunningStatus(
    runningSince: Long?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshColors.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(runningSince) {
        if (runningSince == null) return@LaunchedEffect
        now = System.currentTimeMillis()
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val label = if (runningSince == null) {
        "深度求索中"
    } else {
        // Floor at one second: the web client does the same, so the first paint
        // reads `1秒` instead of `0秒`.
        val elapsed = (now - runningSince).coerceAtLeast(1_000)
        "深度求索中，用时 ${formatDuration(elapsed)} ···"
    }

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm.dp),
    ) {
        WhalePulse()
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = colors.deepDiving,
        )
    }
}

/** Hours only past an hour, minutes only past a minute — the web's own rule. */
internal fun formatDuration(millis: Long): String {
    val total = (millis / 1_000).coerceAtLeast(0)
    val hours = total / 3_600
    val minutes = (total / 60) % 60
    val seconds = total % 60
    return buildString {
        if (hours > 0) append("${hours}小时")
        if (total >= 60) append("${minutes}分")
        append("${seconds}秒")
    }
}

/** Two-tone pulse standing in for the web client's animated whale tail. */
@Composable
private fun WhalePulse() {
    val colors = LocalDshColors.current
    val transition = rememberInfiniteTransition(label = "deep-diving")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(2) { index ->
            val distance = (phase - index.toFloat() + 2f) % 2f
            val scale = if (distance < 1f) 1f - (distance * 0.5f) else 0.5f
            Box(
                Modifier
                    .padding(end = 3.dp)
                    .size((3.5f + 2.5f * scale).dp)
                    .background(
                        colors.deepDiving.copy(alpha = 0.45f + 0.55f * scale),
                        RoundedCornerShape(DshRadius.full.dp),
                    ),
            )
        }
        Spacer(Modifier.width(2.dp))
    }
}
