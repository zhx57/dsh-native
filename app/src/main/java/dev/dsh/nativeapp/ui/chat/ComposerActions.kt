package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Actions offered by the composer's plus button. */
enum class ComposerAction(val title: String, val subtitle: String) {
    PickImage("图片", "从相册选一张发给 DSH"),
    PickFile("文件", "上传任意文件给 DSH 读取"),
    SlashCommand("命令", "插入一条 host 斜杠命令"),
    ;

    val icon: ImageVector
        get() = when (this) {
            PickImage -> Icons.Outlined.Image
            PickFile -> Icons.Outlined.AttachFile
            SlashCommand -> Icons.Outlined.Terminal
        }
}

/**
 * Plus-menu mirroring the web composer's attachment entry.
 *
 * Rendered as a bottom sheet with a single column of rows: on a watch-width screen
 * an icon grid would produce tiles narrower than the touch target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerActionSheet(
    onPick: (ComposerAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDshColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgBase,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DshSpacing.md.dp, vertical = DshSpacing.sm.dp),
        ) {
            Text(
                text = "添加",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.labelPrimary,
                modifier = Modifier.padding(vertical = DshSpacing.sm.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ComposerAction.entries.forEach { action ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.sidebarFill, RoundedCornerShape(DshRadius.md.dp))
                            .clickable { onPick(action) }
                            .padding(horizontal = DshSpacing.md.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = colors.labelSecondary,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = action.title,
                                fontSize = 13.5.sp,
                                color = colors.labelPrimary,
                            )
                            Text(
                                text = action.subtitle,
                                fontSize = 11.sp,
                                color = colors.labelCaption,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.padding(vertical = DshSpacing.sm.dp))
        }
    }
}

/** Pending attachment shown above the composer. */
@Composable
fun AttachmentChips(
    names: List<String>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (names.isEmpty()) return
    val colors = LocalDshColors.current
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        names.forEachIndexed { index, name ->
            Row(
                Modifier
                    .background(colors.selector, RoundedCornerShape(DshRadius.sm.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = name,
                    fontSize = 11.sp,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "×",
                    fontSize = 13.sp,
                    color = colors.labelTertiary,
                    modifier = Modifier
                        .clickable { onRemove(index) }
                        .padding(horizontal = 2.dp),
                )
            }
        }
    }
}
