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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.PairedHost
import dev.dsh.nativeapp.ui.theme.AppearanceMode
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Settings sheet: appearance, connection info, and unpair. */
@Composable
fun SettingsScreen(
    host: PairedHost?,
    appearance: AppearanceMode,
    onAppearance: (AppearanceMode) -> Unit,
    onBack: () -> Unit,
    onUnpair: () -> Unit,
) {
    val colors = LocalDshColors.current

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
            verticalArrangement = Arrangement.spacedBy(DshSpacing.lg.dp),
        ) {
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontSize = 13.sp, color = colors.labelTertiary)
        Text(
            text = value,
            fontSize = 13.sp,
            color = colors.labelSecondary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
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
