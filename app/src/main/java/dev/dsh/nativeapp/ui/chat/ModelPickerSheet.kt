package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.ModelCatalog
import dev.dsh.nativeapp.data.ModelEntry
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Model picker sized for a watch-class screen.
 *
 * Layout follows the reference: a close button and centred title, one row per
 * model with a check on the active one, then an "效果" row that expands in place
 * to the effort list. Nothing slides in a second page, so the sheet never grows
 * past the screen height and the title can never crowd the buttons.
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DshSpacing.md.dp),
        ) {
            SheetHeader(title = "选择模型", onClose = onDismiss)

            // Flatten provider groups: on a small screen a provider heading per
            // group costs a whole row and the model name already encodes it.
            val models = catalog?.groups
                ?.flatMap { group -> group.models.map { group.id to it } }
                .orEmpty()

            models.forEach { (provider, entry) ->
                val selected = provider == currentProvider && entry.id == currentModel
                ModelRow(
                    entry = entry,
                    selected = selected,
                    onClick = {
                        if (entry.reasoning?.efforts.isNullOrEmpty()) {
                            onSelect(provider, entry.id, null)
                        } else {
                            onSelect(provider, entry.id, currentEffort ?: defaultEffortOf(entry))
                        }
                    },
                )
            }

            if (models.isEmpty()) {
                Text(
                    text = "没有可用模型",
                    fontSize = 12.sp,
                    color = colors.labelTertiary,
                    modifier = Modifier.padding(vertical = DshSpacing.md.dp),
                )
            }

            val activeEntry = models.firstOrNull { (provider, entry) ->
                provider == currentProvider && entry.id == currentModel
            }?.second

            if (activeEntry != null && !activeEntry.reasoning?.efforts.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.borderL2))
                EffortRow(
                    entry = activeEntry,
                    current = currentEffort,
                    onPick = { effort ->
                        onSelect(currentProvider ?: "", currentModel ?: "", effort)
                    },
                )
            }

            Spacer(Modifier.height(DshSpacing.lg.dp))
        }
    }
}

/** Close button, centred title, matching the reference sheet. */
@Composable
private fun SheetHeader(title: String, onClose: () -> Unit) {
    val colors = LocalDshColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "关闭",
            tint = colors.labelSecondary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(28.dp)
                .clickable(onClick = onClose)
                .padding(4.dp),
        )
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colors.labelPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun ModelRow(entry: ModelEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.name.ifBlank { entry.id },
            fontSize = 14.sp,
            color = if (selected) colors.labelPrimary else colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "当前",
                tint = colors.labelPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Collapsible effort selector.
 *
 * Collapsed it reads like the reference's "效果  Default ›" row; tapping expands
 * the levels inline so the sheet height stays predictable on a small display.
 */
@Composable
private fun EffortRow(entry: ModelEntry, current: String?, onPick: (String) -> Unit) {
    val colors = LocalDshColors.current
    var expanded by remember { mutableStateOf(false) }
    val efforts = entry.reasoning?.efforts.orEmpty()
    val active = current ?: defaultEffortOf(entry)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "效果",
                fontSize = 14.sp,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = effortShort(active),
                fontSize = 13.sp,
                color = colors.labelTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(17.dp),
            )
        }

        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                efforts.forEach { effort ->
                    val selected = effort.id == active
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) colors.sidebarNavItemActive else Color.Transparent,
                                RoundedCornerShape(DshRadius.sm.dp),
                            )
                            .clickable { onPick(effort.id) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = effortLabel(effort.id, effort.name),
                            fontSize = 13.sp,
                            color = if (selected) colors.labelPrimary else colors.labelSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = colors.labelPrimary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun defaultEffortOf(entry: ModelEntry): String? =
    entry.reasoning?.defaultEffort
        ?: entry.reasoning?.efforts?.firstOrNull()?.id

/** Compact effort label for the collapsed row. */
fun effortShort(id: String?): String = when (id) {
    "off" -> "关闭"
    "minimal" -> "极低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "max", "xhigh" -> "最高"
    null -> "Default"
    else -> id
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
