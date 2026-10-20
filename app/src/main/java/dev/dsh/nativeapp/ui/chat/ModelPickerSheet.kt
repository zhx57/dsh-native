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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.ModelCatalog
import dev.dsh.nativeapp.data.ModelEntry
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Model picker sheet, grouped by provider like the web selector. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    catalog: ModelCatalog?,
    currentProvider: String?,
    currentModel: String?,
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
        Column(Modifier.padding(horizontal = DshSpacing.lg.dp)) {
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
                    items(items = group.models, key = { "${group.id}/${it.id}" }) { entry ->
                        ModelRow(
                            entry = entry,
                            selected = group.id == currentProvider && entry.id == currentModel,
                            onClick = { onSelect(group.id, entry.id, null) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(DshSpacing.lg.dp))
        }
    }
}

@Composable
private fun ModelRow(entry: ModelEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) colors.sidebarNavItemActive else androidx.compose.ui.graphics.Color.Transparent,
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
        if (selected) {
            Text(text = "当前", fontSize = 11.sp, color = colors.labelTertiary)
        }
    }
}
