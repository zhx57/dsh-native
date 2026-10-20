package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Localized permission preset label. */
fun permissionLabel(preset: String): String = when (preset) {
    "read-only" -> "只读"
    "workspace-write" -> "工作区写入"
    "danger-full-access" -> "完整访问"
    else -> preset
}

private fun permissionIcon(preset: String) = when (preset) {
    "read-only" -> Icons.Filled.Lock
    "workspace-write" -> Icons.Filled.Shield
    else -> Icons.Filled.LockOpen
}

/**
 * Permission chip shown in the composer.
 *
 * The web UI switches the sandbox preset from the composer, which is where the
 * decision actually matters. Tapping opens the preset list; the value reflects the
 * session projection, not a local guess.
 */
@Composable
fun PermissionChip(
    preset: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshColors.current
    val value = preset ?: "danger-full-access"
    val accent = when (value) {
        "read-only" -> colors.labelSecondary
        "workspace-write" -> colors.stateWarn
        else -> colors.stateError
    }
    Row(
        modifier
            .background(colors.selector, RoundedCornerShape(DshRadius.full.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = permissionIcon(value),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = permissionLabel(value),
            fontSize = 11.5.sp,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Preset list presented as a sheet. */
@Composable
fun PermissionPicker(
    options: List<String>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDshColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.lg.dp, vertical = DshSpacing.md.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "权限模式",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "关闭",
                fontSize = 12.sp,
                color = colors.labelTertiary,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(4.dp),
            )
        }
        Text(
            text = "决定 DSH 能对这台电脑做什么。放宽权限会立即生效。",
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = colors.labelTertiary,
            modifier = Modifier.padding(bottom = DshSpacing.sm.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { option ->
                val selected = option == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) colors.sidebarNavItemActive else Color.Transparent,
                            RoundedCornerShape(DshRadius.md.dp),
                        )
                        .clickable { onPick(option) }
                        .padding(horizontal = DshSpacing.md.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = permissionIcon(option),
                        contentDescription = null,
                        tint = if (selected) colors.labelPrimary else colors.labelTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = permissionLabel(option),
                            fontSize = 14.sp,
                            color = if (selected) colors.labelPrimary else colors.labelSecondary,
                        )
                        Text(
                            text = permissionHint(option),
                            fontSize = 11.sp,
                            color = colors.labelCaption,
                        )
                    }
                    if (selected) {
                        Text(text = "当前", fontSize = 11.sp, color = colors.labelTertiary)
                    }
                }
            }
        }
        Spacer(Modifier.height(DshSpacing.md.dp))
    }
}

private fun permissionHint(preset: String): String = when (preset) {
    "read-only" -> "只能读取文件，不能修改"
    "workspace-write" -> "可修改工作区内的文件"
    "danger-full-access" -> "不受沙箱限制，可执行任意命令"
    else -> ""
}

/** Actions offered when a message is long-pressed. */
@Composable
fun MessageActionSheet(
    canBranch: Boolean,
    onBranch: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDshColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.lg.dp, vertical = DshSpacing.md.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (canBranch) {
            ActionRow(
                icon = Icons.Outlined.AccountTree,
                title = "从这里分支",
                subtitle = "保留这条之前的对话，开启新会话",
                onClick = onBranch,
            )
        }
        ActionRow(
            icon = Icons.Outlined.ContentCopy,
            title = "复制文本",
            subtitle = null,
            onClick = onCopy,
        )
        Spacer(Modifier.height(DshSpacing.sm.dp))
        Text(
            text = "取消",
            fontSize = 13.sp,
            color = colors.labelTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgBase, RoundedCornerShape(DshRadius.md.dp))
                .clickable(onClick = onDismiss)
                .padding(vertical = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.sidebarFill, RoundedCornerShape(DshRadius.md.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = DshSpacing.md.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = title, fontSize = 14.sp, color = colors.labelPrimary)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 11.sp, color = colors.labelCaption)
            }
        }
    }
}
