package dev.dsh.nativeapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.DshRepository
import dev.dsh.nativeapp.data.PairedHost
import dev.dsh.nativeapp.ui.theme.AppearanceMode
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors
import kotlinx.serialization.json.JsonPrimitive

/**
 * Settings, built from the host's own settings document.
 *
 * The web UI's settings panes are generated from `settings/describe`, so the app
 * reads the same namespaces and writes the same `set` ops. That keeps the two in
 * sync: a value changed in a browser shows up here, and vice versa.
 *
 * Only the namespaces that mean something on a phone are surfaced. The rest
 * (`pwsh-sandbox` timeouts, `subagent` depths, provider internals) are host-side
 * tuning that a watch-sized screen has no room to explain honestly.
 */
@Composable
fun SettingsScreen(
    host: PairedHost?,
    appearance: AppearanceMode,
    settings: DshRepository.SettingsSnapshot?,
    modelLabel: String?,
    onAppearance: (AppearanceMode) -> Unit,
    onLoadSettings: () -> Unit,
    onSetting: (String, List<String>, kotlinx.serialization.json.JsonElement) -> Unit,
    onModelClick: () -> Unit,
    onBack: () -> Unit,
    onUnpair: () -> Unit,
) {
    val colors = LocalDshColors.current

    // Read the live document on open; it is the single source for every value below.
    LaunchedEffect(Unit) { onLoadSettings() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bgBase)
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DshSpacing.sm.dp, vertical = DshSpacing.sm.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "设置",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.labelPrimary,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DshSpacing.lg.dp),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.md.dp),
        ) {
            // ui-theme.preference is the host's own theme choice. It is kept next to
            // the local appearance switch so both agree once `dsh web` is themed too.
            Section("外观") {
                Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm.dp)) {
                    AppearanceMode.entries.forEach { mode ->
                        ChoiceChip(
                            label = when (mode) {
                                AppearanceMode.Light -> "浅色"
                                AppearanceMode.Dark -> "深色"
                                AppearanceMode.System -> "跟随系统"
                            },
                            selected = mode == appearance,
                            onClick = { onAppearance(mode) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val fontSize = (settings?.find("ui-theme")?.get("fontSize") as? JsonPrimitive)
                    ?.content?.toIntOrNull()
                StepperRow(
                    label = "字号",
                    value = fontSize?.let { "$it" } ?: "默认",
                    onMinus = {
                        val next = ((fontSize ?: 14) - 1).coerceIn(10, 22)
                        onSetting("ui-theme", listOf("fontSize"), JsonPrimitive(next))
                    },
                    onPlus = {
                        val next = ((fontSize ?: 14) + 1).coerceIn(10, 22)
                        onSetting("ui-theme", listOf("fontSize"), JsonPrimitive(next))
                    },
                )
            }

            Section("模型") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onModelClick),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "默认模型", fontSize = 13.sp, color = colors.labelTertiary)
                    Text(
                        text = modelLabel ?: "未选择",
                        fontSize = 13.sp,
                        color = colors.labelSecondary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                // agent-default-model is what a new session starts with; the picker
                // above covers the current session.
                val defaultModel = settings?.find("agent-default-model")
                val provider = (defaultModel?.get("provider") as? JsonPrimitive)?.content
                val model = (defaultModel?.get("model") as? JsonPrimitive)?.content
                if (provider != null && model != null) {
                    Spacer(Modifier.height(6.dp))
                    InfoRow("新会话默认", "$provider / $model")
                }
            }

            // ui-chat.performanceUsage drives how much timing detail the web transcript
            // shows; the same two modes are honoured here.
            Section("用量显示") {
                val usage = (settings?.find("ui-chat")?.get("performanceUsage") as? JsonPrimitive)?.content
                    ?: "detailed"
                Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm.dp)) {
                    ChoiceChip("详细", usage == "detailed") {
                        onSetting("ui-chat", listOf("performanceUsage"), JsonPrimitive("detailed"))
                    }
                    ChoiceChip("紧凑", usage == "compact") {
                        onSetting("ui-chat", listOf("performanceUsage"), JsonPrimitive("compact"))
                    }
                }
            }

            // ui-conversation.busyEnter: what Enter does while a turn runs. The app has
            // no physical Enter, but the value documents the queue semantics it follows.
            Section("忙碌时发送") {
                val busy = (settings?.find("ui-conversation")?.get("busyEnter") as? JsonPrimitive)?.content
                    ?: "queue"
                Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm.dp)) {
                    ChoiceChip("排队", busy == "queue") {
                        onSetting("ui-conversation", listOf("busyEnter"), JsonPrimitive("queue"))
                    }
                    ChoiceChip("插话打断", busy == "steer") {
                        onSetting("ui-conversation", listOf("busyEnter"), JsonPrimitive("steer"))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "排队：消息等当前轮结束后再发；插话：立刻打断当前轮。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.labelCaption,
                )
            }

            Section("连接") {
                if (host != null) {
                    InfoRow("地址", host.baseUrl)
                    InfoRow("主机", host.authority)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "断开连接",
                        fontSize = 14.sp,
                        color = colors.stateError,
                        modifier = Modifier
                            .clickable(onClick = onUnpair)
                            .padding(vertical = 6.dp),
                    )
                } else {
                    Text(text = "尚未连接", fontSize = 13.sp, color = colors.labelTertiary)
                }
            }

            Section("关于") {
                InfoRow("客户端", "DSH Native 1.0.0")
                InfoRow("协议", "DSH Remote RPC + remote.mux")
                if (settings != null) {
                    InfoRow("设置可写", if (settings.writable) "是" else "否")
                    InfoRow("命名空间", "${settings.namespaces.size} 个")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = LocalDshColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.labelCaption,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.sidebarFill, RoundedCornerShape(DshRadius.lg.dp))
                .padding(DshSpacing.md.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 13.sp, color = colors.labelTertiary)
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.labelSecondary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** A value with − / + controls, sized for a narrow screen. */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val colors = LocalDshColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 13.sp, color = colors.labelTertiary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("−", onMinus)
            Text(
                text = value,
                fontSize = 13.sp,
                color = colors.labelSecondary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            StepperButton("+", onPlus)
        }
    }
}

@Composable
private fun StepperButton(symbol: String, onClick: () -> Unit) {
    val colors = LocalDshColors.current
    Text(
        text = symbol,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = colors.labelPrimary,
        modifier = Modifier
            .background(colors.bgBase, RoundedCornerShape(DshRadius.sm.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalDshColors.current
    Text(
        text = label,
        fontSize = 13.sp,
        color = if (selected) colors.labelPrimary else colors.labelSecondary,
        modifier = Modifier
            .background(
                if (selected) colors.sidebarNavItemActive else colors.bgBase,
                RoundedCornerShape(DshRadius.full.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
