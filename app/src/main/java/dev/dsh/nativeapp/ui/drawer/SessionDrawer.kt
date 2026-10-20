package dev.dsh.nativeapp.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.SessionRow
import dev.dsh.nativeapp.data.WorkspaceGroup
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Session drawer: workspace groups, session rows, and the primary actions. */
@Composable
fun SessionDrawer(
    groups: List<WorkspaceGroup>,
    activeSessionId: String?,
    onSelect: (String) -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = LocalDshColors.current

    Column(
        Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(colors.sidebarFill)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = DshSpacing.lg.dp, end = DshSpacing.sm.dp, top = DshSpacing.md.dp, bottom = DshSpacing.sm.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DeepSeek Harness",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewSession, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "新建会话",
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = DshSpacing.sm.dp,
                end = DshSpacing.sm.dp,
                bottom = DshSpacing.md.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            groups.forEach { group ->
                item(key = "ws-${group.workspaceId}") {
                    WorkspaceHeader(group.title)
                }
                items(items = group.sessions, key = { it.sessionId }) { session ->
                    SessionRowItem(
                        row = session,
                        active = session.sessionId == activeSessionId,
                        onClick = { onSelect(session.sessionId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(title: String) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = DshSpacing.md.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = colors.labelCaption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionRowItem(
    row: SessionRow,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    active -> colors.sidebarNavItemActive
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
                RoundedCornerShape(DshRadius.md.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                fontSize = 13.5.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (active) colors.labelPrimary else colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.subtitle.isNotBlank()) {
                Text(
                    text = row.subtitle,
                    fontSize = 11.sp,
                    color = colors.labelCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.running) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(colors.stateSuccess, RoundedCornerShape(DshRadius.full.dp)),
            )
        }
    }
}
