package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
 * Session statistics, following the web client's own panes.
 *
 * The numbers and their sources are taken from the web UI verbatim, because the
 * earlier version computed several of them differently and disagreed with a browser
 * showing the same session:
 *
 *  - **context used** is `projectedTokens ?? pressureTokens` over `contextWindow`
 *    (`contextOccupancy`). It is *not* the prompt breakdown; those are different
 *    quantities, and reading the breakdown as occupancy is what made the figure
 *    wrong.
 *  - **cache hit** divides `cacheReadTokens` by *billed* input, which is the sum of
 *    the three disjoint prompt buckets. Dividing by uncached input alone reported a
 *    far rosier hit rate than the host's.
 *  - **TPS** is `decodeTokens / (decodeMs / 1e3)`; the old code divided by TTFT,
 *    which is a latency, not a duration.
 *  - **TTFT average** divides by the steps that reported one (`ttftSteps`), not by
 *    all steps.
 *
 * Section titles and row labels are the web client's strings: 上下文已用, 系统提示词,
 * 工具定义, 对话消息, Token 用量, 未缓存输入, 缓存读取, 缓存写入, 输出, 会话统计,
 * 模型用时, 工具调用用时, 首 token 平均（TTFT）, 输出速度（TPS）.
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
                // A watch screen is shorter than this content, so the sheet body must
                // scroll; without the cap the sections below the fold were unreachable.
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DshSpacing.md.dp),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.sm.dp),
        ) {
            Text(
                text = "会话统计",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.labelPrimary,
                modifier = Modifier.padding(top = DshSpacing.sm.dp),
            )

            ContextCard(state)
            UsageCard(state)
            StatsCard(state)

            SectionCard {
                MetricTitle("当前配置")
                MetricRow("模型", modelLabel ?: "—")
                MetricRow("思考等级", state.reasoningEffort?.let { effortLabel(it) } ?: "—")
                MetricRow("权限", state.permissionPreset?.let { permissionShortLabel(it) } ?: "—")
                MetricRow("会话 ID", state.sessionId?.takeLast(12) ?: "—")
            }

            Spacer(Modifier.height(DshSpacing.xl.dp))
        }
    }
}

/** 上下文已用: the meter's own figure, plus what the prompt is made of. */
@Composable
private fun ContextCard(state: ChatUiState) {
    val colors = LocalDshColors.current
    val occupancy = state.contextOccupancy
    val breakdown = state.contextBreakdown

    SectionCard {
        MetricTitle("上下文已用")
        if (occupancy != null && occupancy.contextWindow > 0) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "${occupancy.percent}%",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${fmt(occupancy.usedTokens)} / ${fmt(occupancy.contextWindow)}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.labelSecondary,
                )
            }
            Spacer(Modifier.height(6.dp))
            CapacityBar(
                fraction = (occupancy.usedTokens.toFloat() / occupancy.contextWindow).coerceIn(0f, 1f),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "剩余 ${fmt(occupancy.remaining)}",
                fontSize = 11.5.sp,
                color = colors.labelTertiary,
            )
        } else {
            Text(text = "等待会话数据", fontSize = 12.sp, color = colors.labelTertiary)
        }

        if (breakdown != null && breakdown.total > 0) {
            Spacer(Modifier.height(8.dp))
            // The same three colours are used by the bar and by the rows below it, so
            // each segment is identifiable. Without the swatches the bar was three
            // unexplained colours.
            BreakdownBar(breakdown.systemTokens, breakdown.toolsTokens, breakdown.messageTokens)
            Spacer(Modifier.height(7.dp))
            MetricRow(
                label = "系统提示词",
                value = fmt(breakdown.systemTokens),
                hint = percent(breakdown.systemTokens, breakdown.total),
                swatch = LocalDshColors.current.buttonInfoFill,
            )
            MetricRow(
                label = "工具定义",
                value = fmt(breakdown.toolsTokens),
                hint = percent(breakdown.toolsTokens, breakdown.total),
                swatch = LocalDshColors.current.stateWarn,
            )
            MetricRow(
                label = "对话消息",
                value = fmt(breakdown.messageTokens),
                hint = percent(breakdown.messageTokens, breakdown.total),
                swatch = LocalDshColors.current.link,
            )
        }
    }
}

/** Token 用量: the four billed buckets, plus the cache-hit share the web shows. */
@Composable
private fun UsageCard(state: ChatUiState) {
    val usage = state.tokenUsage
    SectionCard {
        MetricTitle("Token 用量")
        if (usage == null) {
            Text(text = "尚无用量", fontSize = 12.sp, color = LocalDshColors.current.labelTertiary)
            return@SectionCard
        }
        MetricRow("未缓存输入", fmt(usage.input))
        MetricRow("缓存读取", fmt(usage.cacheRead))
        if (usage.cacheWrite > 0) MetricRow("缓存写入", fmt(usage.cacheWrite))
        MetricRow("输出", fmt(usage.output))
        Divider()
        MetricRow("计费输入", fmt(usage.billedInput), emphasis = true)
        usage.cacheHitPercent?.let { hit ->
            MetricRow("缓存命中", "$hit%")
        }
    }
}

/**
 * 会话统计: counters and timing, each guarded by the same "is it meaningful" tests the
 * web client uses before showing a row.
 */
@Composable
private fun StatsCard(state: ChatUiState) {
    val stats = state.sessionStats ?: return
    SectionCard {
        MetricTitle("会话统计")
        MetricRow("轮次 / 步数", "${stats.turns} 轮 ${stats.steps} 步")
        if (stats.llmMs > 0) MetricRow("模型用时", duration(stats.llmMs))
        if (stats.toolMs > 0) MetricRow("工具调用用时", duration(stats.toolMs))
        stats.averageTtftMs?.let { MetricRow("首 token 平均（TTFT）", duration(it)) }
        stats.tokensPerSecond?.let { MetricRow("输出速度（TPS）", "${formatTps(it)} tok/s") }
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
private fun MetricRow(
    label: String,
    value: String,
    hint: String? = null,
    emphasis: Boolean = false,
    /** Colour of the matching bar segment, so the bar's legend is inline. */
    swatch: Color? = null,
) {
    val colors = LocalDshColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(swatch, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(6.dp))
        }
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

/** Occupancy bar; turns amber then red as the window fills, like the web meter. */
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

/** Whole tokens from ten up, one decimal below — the web's `formatTokensPerSecond`. */
private fun formatTps(tps: Double): String =
    if (tps >= 10) tps.toInt().toString() else String.format("%.1f", tps)
