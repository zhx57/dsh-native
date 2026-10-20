package dev.dsh.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.ChatUiState
import dev.dsh.nativeapp.data.WorkspaceGroup
import dev.dsh.nativeapp.ui.chat.ChatScreen
import dev.dsh.nativeapp.ui.drawer.SessionDrawer
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Phone shell: a top bar with drawer toggle, the transcript, and an overlay
 * drawer. The web app uses a persistent sidebar on desktop; on a phone the same
 * content becomes an edge-anchored panel so the transcript keeps full width.
 */
@Composable
fun ChatScaffold(
    state: ChatUiState,
    groups: List<WorkspaceGroup>,
    modelLabel: String?,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onModelClick: () -> Unit,
    onDismissError: () -> Unit,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    onRunCommand: (String) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onInfo: () -> Unit,
    onBranch: (Long) -> Unit,
    onPermissionClick: () -> Unit,
    onAnswerQuestions: (String, Map<String, List<String>>) -> Unit,
    onPlusClick: () -> Unit,
    attachmentNames: List<String>,
    onRemoveAttachment: (Int) -> Unit,
) {
    val colors = LocalDshColors.current
    var drawerOpen by remember { mutableStateOf(false) }

    // Back closes the drawer first; the Activity only exits when nothing is open.
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    Box(Modifier.fillMaxSize().background(colors.bgBase)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = state.title.ifBlank { "DSH" },
                running = state.running,
                onMenu = { drawerOpen = true },
                onNewSession = onNewSession,
                onInfo = onInfo,
            )
            ChatScreen(
                state = state,
                modelLabel = modelLabel,
                onSend = onSend,
                onStop = onStop,
                onModelClick = onModelClick,
                onDismissError = onDismissError,
                onAllow = onAllow,
                onReject = onReject,
                onRunCommand = onRunCommand,
                draft = draft,
                onDraftChange = onDraftChange,
                onBranch = onBranch,
                onPermissionClick = onPermissionClick,
                onAnswerQuestions = onAnswerQuestions,
                onPlusClick = onPlusClick,
                attachmentNames = attachmentNames,
                onRemoveAttachment = onRemoveAttachment,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            )
        }

        // Scrim
        AnimatedVisibility(
            visible = drawerOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.bgMask)
                    .clickable { drawerOpen = false },
            )
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            SessionDrawer(
                groups = groups,
                activeSessionId = state.sessionId,
                onSelect = {
                    drawerOpen = false
                    onSelectSession(it)
                },
                onNewSession = {
                    drawerOpen = false
                    onNewSession()
                },
                onSettings = {
                    drawerOpen = false
                    onOpenSettings()
                },
            )
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    running: Boolean,
    onMenu: () -> Unit,
    onNewSession: () -> Unit,
    onInfo: () -> Unit,
) {
    val colors = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bgBase)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "会话列表",
                tint = colors.labelSecondary,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(colors.stateSuccess, RoundedCornerShape(DshRadius.full.dp)),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(text = "运行中", fontSize = 11.sp, color = colors.labelTertiary)
                }
            }
        }
        IconButton(onClick = onInfo, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "会话信息",
                tint = colors.labelSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onNewSession, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "新建会话",
                tint = colors.labelSecondary,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}
