package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.ModelCatalog
import dev.dsh.nativeapp.data.ModelEntry
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Model picker, grouped by provider like the web selector.
 *
 * Picking a model that exposes reasoning efforts opens a second page for the
 * effort, because `session/selectModel` carries provider, model and effort in one
 * call — there is no endpoint that changes the effort alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    catalog: ModelCatalog?,
    currentProvider: String?,
    currentModel: String?,
    currentEffort: String?,
    onDismiss: () -> Unit,
    onSelect: (provider: String, model: String, effort: String?) -> Unit,
) {
    val colors = LocalDshColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var effortFor by remember { mutableStateOf<ModelEntry?>(null) }
    var effortProvider by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
        dragHandle = null,
    ) {
        Column(Modifier.padding(horizontal = DshSpacing.lg.dp)) {
            val entry = effortFor
            val provider = effortProvider
            if (entry != null && provider != null) {
                EffortPicker(
                    entry = entry,
                    current = if (provider == currentProvider && entry.id == currentModel) currentEffort else null,
                    onBack = { effortFor = null },
                    onPick = { effort -> onSelect(provider, entry.id, effort) },
                )
            } else {
                Text(
                    text = "选择模型",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                    modifier = Modifier.padding(vertical = DshSpacing.md.dp),
                )

                LazyColumn(
                    Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    catalog?.groups?.forEach { group ->
                        item(key = "g-${group.id}") {
                            Text(
                                text = group.name.ifBlank { group.id },
                                fontSize = 11.sp,
                                color = colors.labelCaption,
                                modifier = Modifier.padding(top = DshSpacing.md.dp, bottom = 4.dp),
                            )
                        }
                        items(items = group.models, key = { "${group.id}/${it.id}" }) { model ->
                            val isCurrent = group.id == currentProvider && model.id == currentModel
                            ModelRow(
                                entry = model,
                                selected = isCurrent,
                                effort = if (isCurrent) currentEffort else null,
                                onClick = {
                                    if (model.reasoning?.efforts.isNullOrEmpty()) {
                                        onSelect(group.id, model.id, null)
                                    } else {
                                        effortProvider = group.id
                                        effortFor = model
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(DshSpacing.lg.dp))
        }
    }
}

/** Reasoning-effort list for the chosen model. */
@Composable
private fun EffortPicker(
    entry: ModelEntry,
    current: String?,
    onBack: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val colors = LocalDshColors.current
    val efforts = entry.reasoning?.efforts.orEmpty()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = DshSpacing.md.dp, bottom = DshSpacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "‹ 返回",
            fontSize = 12.sp,
            color = colors.labelTertiary,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        )
        Text(
            text = "思考等级",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.labelPrimary,
        )
    }

    Text(
        text = entry.name.ifBlank { entry.id },
        fontSize = 11.sp,
        color = colors.labelCaption,
        modifier = Modifier.padding(bottom = 6.dp),
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        efforts.forEach { effort ->
            val selected = effort.id == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) colors.sidebarNavItemActive else Color.Transparent,
                        RoundedCornerShape(DshRadius.md.dp),
                    )
                    .clickable { onPick(effort.id) }
                    .padding(horizontal = DshSpacing.md.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = effortLabel(effort.id, effort.name),
                    fontSize = 14.sp,
                    color = if (selected) colors.labelPrimary else colors.labelSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text(text = "当前", fontSize = 11.sp, color = colors.labelTertiary)
                }
            }
        }
    }
}

/** Localized effort label, falling back to the host-provided name. */
fun effortLabel(id: String, fallback: String = ""): String = when (id) {
    "off" -> "关闭 · 不思考"
    "minimal" -> "极低"
    "low" -> "低 · 快"
    "medium" -> "中"
    "high" -> "高 · 均衡"
    "max", "xhigh" -> "最高 · 难题优先"
    else -> fallback.ifBlank { id }
}

@Composable
private fun ModelRow(
    entry: ModelEntry,
    selected: Boolean,
    effort: String?,
    onClick: () -> Unit,
) {
    val colors = LocalDshColors.current
    val hasEffort = !entry.reasoning?.efforts.isNullOrEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) colors.sidebarNavItemActive else Color.Transparent,
                RoundedCornerShape(DshRadius.md.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = DshSpacing.md.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.name.ifBlank { entry.id },
            fontSize = 14.sp,
            color = if (selected) colors.labelPrimary else colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        when {
            selected && hasEffort && effort != null -> Text(
                text = "当前 · ${effortLabel(effort)}",
                fontSize = 11.sp,
                color = colors.labelTertiary,
            )
            selected -> Text(text = "当前", fontSize = 11.sp, color = colors.labelTertiary)
            hasEffort -> Text(text = "思考等级 ›", fontSize = 11.sp, color = colors.labelCaption)
        }
    }
}
