package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.ChatUiState
import dev.dsh.nativeapp.data.TimelineItem
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Native transcript for one session, with the composer docked underneath. */
@Composable
fun ChatScreen(
    state: ChatUiState,
    modelLabel: String?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onModelClick: () -> Unit,
    onDismissError: () -> Unit,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    onRunCommand: (String) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onBranch: (Long) -> Unit,
    onPermissionClick: () -> Unit,
    onAnswerQuestions: (String, Map<String, List<String>>) -> Unit,
    onPlusClick: () -> Unit,
    attachmentNames: List<String>,
    onRemoveAttachment: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    val colors = LocalDshColors.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val itemCount = state.timeline.size

    // "Following" means the tail was last in view. It is tracked as explicit state
    // rather than recomputed from layout, because a streaming append changes the
    // item count and would otherwise be read as "still at the bottom" even while
    // the operator has scrolled up to select text.
    var followTail by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@collect
                val total = info.totalItemsCount
                if (total > 0) followTail = lastVisible >= total - 2
            }
    }

    // Only follow when the operator has not scrolled away, so watching the tail
    // never yanks the viewport out from under a text selection.
    LaunchedEffect(itemCount) {
        if (itemCount > 0 && followTail) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bgBase)
            .padding(top = contentPadding.calculateTopPadding())
            .imePadding(),
    ) {
        Box(Modifier.weight(1f)) {
            if (state.timeline.isEmpty() && !state.connecting) {
                if (state.sessionId == null) NoSessionState() else EmptyTranscript(state.title)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = DshSpacing.lg.dp,
                    end = DshSpacing.lg.dp,
                    top = DshSpacing.md.dp,
                    bottom = DshSpacing.md.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items = state.timeline, key = { keyOf(it) }) { item ->
                    when (item) {
                        is TimelineItem.UserMessage -> UserTurn(item)
                        is TimelineItem.AssistantMessage -> AssistantTurn(item)
                        is TimelineItem.ToolCallItem -> ToolCallCard(item)
                        is TimelineItem.TurnDivider -> TurnDivider(item.turn)
                        is TimelineItem.Notice -> Text(
                            text = item.text,
                            fontSize = 12.sp,
                            color = colors.labelTertiary,
                        )
                    }
                }
            }

            TranscriptNavigator(
                canScrollUp = state.timeline.isNotEmpty(),
                canScrollDown = !followTail,
                onPrevious = {
                    scope.launch {
                        val target = previousMessageIndex(listState, state.timeline)
                        if (target >= 0) listState.animateScrollToItem(target)
                    }
                },
                onNext = {
                    scope.launch {
                        val target = nextMessageIndex(listState, state.timeline)
                        if (target >= 0) listState.animateScrollToItem(target)
                    }
                },
                onJumpToLatest = {
                    scope.launch {
                        followTail = true
                        if (state.timeline.isNotEmpty()) {
                            listState.animateScrollToItem(state.timeline.lastIndex)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp),
            )

            if (state.connecting) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "正在加载会话…", fontSize = 13.sp, color = colors.labelTertiary)
                }
            }
        }

        if (state.error != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.lg.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.error,
                    fontSize = 12.sp,
                    color = colors.stateError,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "关闭",
                    fontSize = 12.sp,
                    color = colors.labelTertiary,
                    modifier = Modifier
                        .clickable(onClick = onDismissError)
                        .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }

        if (state.questions.isNotEmpty()) {
            state.questions.forEach { batch ->
                QuestionCard(
                    batch = batch,
                    onSubmit = { answers -> onAnswerQuestions(batch.callId, answers) },
                    modifier = Modifier.padding(
                        horizontal = DshSpacing.md.dp,
                        vertical = DshSpacing.xs.dp,
                    ),
                )
            }
        }

        if (state.pendingApproval != null) {
            ApprovalCard(
                toolName = state.pendingApproval.toolName,
                reason = state.pendingApproval.reason,
                detail = state.pendingApproval.detail,
                onAllow = onAllow,
                onReject = onReject,
                modifier = Modifier.padding(
                    horizontal = DshSpacing.md.dp,
                    vertical = DshSpacing.xs.dp,
                ),
            )
        }

        if (state.todos.isNotEmpty()) {
            TodoPanel(
                todos = state.todos,
                modifier = Modifier.padding(
                    horizontal = DshSpacing.md.dp,
                    vertical = DshSpacing.xs.dp,
                ),
            )
        }

        // One action row for the whole transcript. Per-message buttons cost a
        // full line each on a watch-sized screen and pushed the composer down.
        if (state.timeline.any { it is TimelineItem.AssistantMessage }) {
            val clipboard = LocalClipboardManager.current
            TranscriptActions(
                onCopyAll = { clipboard.setText(AnnotatedString(plainTextOf(state.timeline))) },
                onBranch = {
                    branchSeqOf(state.timeline)?.let(onBranch)
                },
                modifier = Modifier.padding(horizontal = DshSpacing.md.dp),
            )
        }

        Composer(
            enabled = state.sessionId != null,
            running = state.running,
            onSend = onSend,
            onStop = onStop,
            modelLabel = modelLabel,
            onModelClick = onModelClick,
            draft = draft,
            onDraftChange = onDraftChange,
            commands = state.commands,
            onSubmitCommand = onRunCommand,
            permissionPreset = state.permissionPreset,
            onPermissionClick = onPermissionClick,
            onPlusClick = onPlusClick,
            attachmentNames = attachmentNames,
            onRemoveAttachment = onRemoveAttachment,
            modifier = Modifier.padding(
                start = DshSpacing.md.dp,
                end = DshSpacing.md.dp,
                bottom = DshSpacing.sm.dp,
            ),
        )
        // The docked composer already sits inside the IME inset; adding the
        // navigation bar inset here would reserve that space twice.
        Spacer(Modifier.height(DshSpacing.xs.dp))
    }
}

@Composable
private fun EmptyTranscript(title: String) {
    val colors = LocalDshColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "DeepSeek Harness",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.labelPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "说点什么开始这一轮",
                fontSize = 13.sp,
                color = colors.labelTertiary,
            )
        }
    }
}

/** Shown when the host answered but the workspace has no session yet. */
@Composable
private fun NoSessionState() {
    val colors = LocalDshColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "还没有会话",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = colors.labelPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "点右上角 + 新建一个",
                fontSize = 13.sp,
                color = colors.labelTertiary,
            )
        }
    }
}

/** Stable identity so streaming updates reuse rows instead of rebuilding them. */
private fun keyOf(item: TimelineItem): String = when (item) {
    is TimelineItem.UserMessage -> "u-${item.seq}"
    is TimelineItem.AssistantMessage -> "a-${item.seq}"
    is TimelineItem.ToolCallItem -> "t-${item.callId}"
    is TimelineItem.TurnDivider -> "d-${item.turn}"
    is TimelineItem.Notice -> "n-${item.seq}"
}

/** Index of the message above the current viewport, or -1 at the top. */
private fun previousMessageIndex(
    listState: LazyListState,
    timeline: List<TimelineItem>,
): Int {
    val first = listState.firstVisibleItemIndex
    return (first - 1).coerceAtLeast(0).takeIf { first > 0 && timeline.isNotEmpty() } ?: -1
}

/** Index of the message below the viewport, or -1 when already at the tail. */
private fun nextMessageIndex(
    listState: LazyListState,
    timeline: List<TimelineItem>,
): Int {
    if (timeline.isEmpty()) return -1
    val visible = listState.layoutInfo.visibleItemsInfo
    val last = visible.lastOrNull()?.index ?: return -1
    return (last + 1).takeIf { it <= timeline.lastIndex } ?: -1
}
