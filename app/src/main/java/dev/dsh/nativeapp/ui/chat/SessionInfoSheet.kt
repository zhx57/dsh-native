package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.ChatUiState
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Session metrics sheet.
 *
 * The web app prints three numbers under the transcript: context occupancy, the
 * token totals, and the prompt split. On a phone there is no room for a footer, so
 * the same numbers live behind the info button in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoSheet(
    state: ChatUiState,
    modelLabel: String?,
    onDismiss: () -> Unit,
) {
    val colors = LocalDshColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // A watch screen is shorter than this content, so the sheet body
                // must scroll: without a height cap the sections below the fold
                // were unreachable.
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DshSpacing.md.dp),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.sm.dp),
        ) {
            Text(
                text = "会话信息",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.labelPrimary,
                modifier = Modifier.padding(top = DshSpacing.sm.dp),
            )

            // ---- 1. Context occupancy -------------------------------------
            val used = state.tokenUsage
            val window = state.contextWindow
            val pressure = state.contextBreakdown?.total ?: 0L
            SectionCard {
                MetricTitle("上下文占用")
                if (window != null && window > 0) {
                    val tokens = state.contextWindow.let { w ->
                        // Prefer the pressure figure when present; it is what the
                        // host uses for compaction decisions.
                        if (pressure > 0) pressure else used?.let { it.input + it.output } ?: 0L
                    }
                    Text(
                        text = "${fmt(tokens)} / ${fmt(window.toLong())}",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.labelPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    CapacityBar(fraction = (tokens.toFloat() / window).coerceIn(0f, 1f))
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "剩余 ${fmt((window - tokens).coerceAtLeast(0))} · ${percent(tokens, window.toLong())}",
                        fontSize = 11.5.sp,
                        color = colors.labelTertiary,
                    )
                } else {
                    Text(text = "等待会话数据", fontSize = 12.sp, color = colors.labelTertiary)
                }
            }

            // ---- 2. Token totals ------------------------------------------
            SectionCard {
                MetricTitle("Token 用量")
                if (used != null) {
                    MetricRow("输入", fmt(used.input), hint = "未命中缓存")
                    MetricRow("输出", fmt(used.output))
                    MetricRow("缓存读取", fmt(used.cacheRead))
                    Divider()
                    MetricRow("总计", fmt(used.input + used.output), emphasis = true)
                } else {
                    Text(text = "尚无用量", fontSize = 12.sp, color = colors.labelTertiary)
                }
            }

            // ---- 3. Prompt split ------------------------------------------
            SectionCard {
                MetricTitle("上下文构成")
                val breakdown = state.contextBreakdown
                if (breakdown != null && breakdown.total > 0) {
                    BreakdownBar(breakdown.systemTokens, breakdown.toolsTokens, breakdown.messageTokens)
                    Spacer(Modifier.height(8.dp))
                    MetricRow("系统提示", fmt(breakdown.systemTokens), hint = percent(breakdown.systemTokens, breakdown.total))
                    MetricRow("工具定义", fmt(breakdown.toolsTokens), hint = percent(breakdown.toolsTokens, breakdown.total))
                    MetricRow("对话消息", fmt(breakdown.messageTokens), hint = percent(breakdown.messageTokens, breakdown.total))
                } else {
                    Text(text = "尚无数据", fontSize = 12.sp, color = colors.labelTertiary)
                }
            }

            val stats = state.sessionStats
            if (stats != null) {
                SectionCard {
                    MetricTitle("运行统计")
                    MetricRow("轮次", stats.turns.toString())
                    MetricRow("步数", stats.steps.toString())
                    MetricRow("模型耗时", duration(stats.llmMs))
                    MetricRow("工具耗时", duration(stats.toolMs))
                    if (stats.decodeTokens > 0) {
                        MetricRow("生成速度", "${(stats.decodeTokens * 1000.0 / stats.ttftMs.coerceAtLeast(1)).toInt()} tok/s")
                    }
                }
            }

            SectionCard {
                MetricTitle("当前配置")
                MetricRow("模型", modelLabel ?: "—")
                MetricRow("思考等级", state.reasoningEffort?.let { effortLabel(it) } ?: "—")
                MetricRow("权限", state.permissionPreset ?: "—")
                MetricRow("会话 ID", state.sessionId?.takeLast(12) ?: "—")
            }

            Spacer(Modifier.height(DshSpacing.xl.dp))
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    val colors = LocalDshColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.sidebarFill, RoundedCornerShape(DshRadius.lg.dp))
            .padding(DshSpacing.md.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun MetricTitle(text: String) {
    val colors = LocalDshColors.current
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = colors.labelCaption,
    )
}

@Composable
private fun MetricRow(label: String, value: String, hint: String? = null, emphasis: Boolean = false) {
    val colors = LocalDshColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (emphasis) colors.labelSecondary else colors.labelTertiary,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                text = hint,
                fontSize = 11.sp,
                color = colors.labelCaption,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            text = value,
            fontSize = if (emphasis) 13.sp else 12.5.sp,
            fontWeight = if (emphasis) FontWeight.Medium else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            color = colors.labelPrimary,
        )
    }
}

@Composable
private fun Divider() {
    val colors = LocalDshColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(colors.borderL2),
    )
}

/** Occupancy bar; turns amber then red as the window fills. */
@Composable
private fun CapacityBar(fraction: Float) {
    val colors = LocalDshColors.current
    val tint = when {
        fraction >= 0.9f -> colors.stateError
        fraction >= 0.7f -> colors.stateWarn
        else -> colors.buttonInfoFill
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(colors.borderL2, RoundedCornerShape(DshRadius.full.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .background(tint, RoundedCornerShape(DshRadius.full.dp)),
        )
    }
}

/** Stacked bar showing the prompt's system / tools / messages split. */
@Composable
private fun BreakdownBar(system: Long, tools: Long, messages: Long) {
    val colors = LocalDshColors.current
    val total = (system + tools + messages).coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(colors.borderL2, RoundedCornerShape(DshRadius.full.dp)),
    ) {
        Segment(system.toFloat() / total, colors.buttonInfoFill)
        Segment(tools.toFloat() / total, colors.stateWarn)
        Segment(messages.toFloat() / total, colors.link)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(weight: Float, color: Color) {
    if (weight <= 0f) return
    Box(
        Modifier
            .weight(weight)
            .height(8.dp)
            .background(color),
    )
}

private fun fmt(value: Long): String = when {
    value >= 1_000_000 -> String.format("%.2fM", value / 1_000_000.0)
    value >= 10_000 -> String.format("%.1fK", value / 1_000.0)
    value >= 1_000 -> String.format("%.2fK", value / 1_000.0)
    else -> value.toString()
}

private fun percent(part: Long, total: Long): String =
    if (total <= 0) "—" else String.format("%.1f%%", part * 100.0 / total)

private fun duration(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    ms >= 1_000 -> String.format("%.1fs", ms / 1000.0)
    else -> "${ms}ms"
}
