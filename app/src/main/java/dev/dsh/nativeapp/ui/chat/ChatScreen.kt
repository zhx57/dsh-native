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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
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
import dev.dsh.nativeapp.data.TurnOutcomeKind
import dev.dsh.nativeapp.data.keyOf
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Rows the list prepends before the timeline itself. */
private const val HeadRows = 1

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
    onLoadOlder: () -> Unit,
    onSteerQueued: (String) -> Unit,
    onEditQueued: (String, String) -> Unit,
    onRemoveQueued: (String) -> Unit,
    loadingOlder: Boolean,
    hasMoreHistory: Boolean,
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

    // "Following" means the newest row is actually rendered in the viewport, not
    // merely that the index is near the end: a short last row or a tall item can
    // make `lastVisible >= total - 2` true while the operator has scrolled up.
    //
    // This only ever turns following back ON — it is how the operator resumes
    // following by scrolling to the bottom themselves. Turning it off on drag is
    // handled separately below, because a selection drag and a deliberate scroll-up
    // both flip `isScrollInProgress`.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val visible = info.visibleItemsInfo
                if (visible.isEmpty() || info.totalItemsCount == 0) return@collect
                val last = visible.last()
                val viewportEnd = info.viewportEndOffset - info.afterContentPadding
                val atBottom = last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= viewportEnd + 1
                if (atBottom) followTail = true
            }
    }

    // A deliberate scroll upward hands the viewport to the operator, so incoming
    // messages cannot yank them back down mid-read. Scrolling back to the bottom
    // re-arms following (the effect above), which is what makes manual scrolling
    // feel normal again instead of permanently losing auto-follow.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) return@collect
                val info = listState.layoutInfo
                val visible = info.visibleItemsInfo
                if (visible.isEmpty()) return@collect
                val last = visible.last()
                val viewportEnd = info.viewportEndOffset - info.afterContentPadding
                val atBottom = last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= viewportEnd + 1
                if (!atBottom) followTail = false
            }
    }

    // Land on the tail when the session changes. Overlays are handled separately:
    // this effect must not be keyed on `itemCount`, because every new message would
    // then force a jump to the bottom and yank the viewport away from a text
    // selection the operator is making.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.sessionId) {
        followTail = true
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    // Returning from an overlay or the background re-pins the tail, matching the
    // expectation that re-entering shows the newest state. It is deferred so the
    // scroll runs after layout settles.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                followTail = true
                scope.launch {
                    if (listState.layoutInfo.totalItemsCount > 0) {
                        listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Load earlier history when the operator reaches the top. The follow stream only
    // replays the newest window, so without this the transcript simply ended.
    //
    // The trigger requires the head indicator itself to be on screen AND the list to
    // actually be scrolled to its top. Firing on `first <= 1` alone meant the request
    // repeated while a page was in flight and, worse, fired again the instant a
    // prepend finished — which kept the spinner cycling and made the "已经是最早"
    // notice appear while older messages still existed.
    LaunchedEffect(listState, hasMoreHistory, loadingOlder) {
        if (!hasMoreHistory || loadingOlder) return@LaunchedEffect
        snapshotFlow {
            // Read from the state object: the offset lives there, not on layoutInfo.
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .collect { (first, offset) ->
                if (first == 0 && offset < 48) onLoadOlder()
            }
    }

    // Follow the tail while the operator stays there; a plain scroll keeps the
    // viewport pinned without the drift that animateScrollToItem shows on a long
    // list, and it never fights a text selection because followTail is false then.
    LaunchedEffect(itemCount) {
        if (itemCount > 0 && followTail) {
            listState.scrollToItem(itemCount - 1)
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
                // Chrome-style leading indicator: a spinner while the previous page
                // is loading, and a terminal notice once the log start is reached.
                item(key = "history-head") {
                    HistoryHead(
                        loading = loadingOlder,
                        hasMore = hasMoreHistory,
                        isEmpty = state.timeline.isEmpty(),
                    )
                }
                items(items = state.timeline, key = { it.keyOf() }) { item ->
                    when (item) {
                        is TimelineItem.UserMessage -> UserTurn(item)
                        is TimelineItem.AssistantMessage -> AssistantTurn(item)
                        is TimelineItem.ToolCallItem -> ToolCallCard(item)
                        is TimelineItem.PendingQuestionItem -> QuestionCard(
                            batch = item,
                            onSubmit = { answers -> onAnswerQuestions(item.callId, answers) },
                        )
                        is TimelineItem.CommandItem -> CommandRow(item)
                        is TimelineItem.TurnOutcome -> TurnOutcomeRow(item)
                        is TimelineItem.TurnDivider -> TurnDivider(item.turn)
                        is TimelineItem.Notice -> Text(
                            text = item.text,
                            fontSize = 12.sp,
                            color = colors.labelTertiary,
                        )
                    }
                }
                // The web client ends the transcript with its running status, not
                // inside the last message bubble. Keeping it as its own row also
                // means the once-a-second clock does not recompose the transcript.
                if (state.running) {
                    item(key = "running-status") {
                        RunningStatus(runningSince = state.runningSince)
                    }
                }
            }

            val anchors = remember(state.timeline) { turnAnchors(state.timeline) }
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
            // Index 0 is the history-head row, so the timeline is offset by one.
            val timelineFirst = (firstVisible - HeadRows).coerceAtLeast(0)
            val timelineLast = (lastVisible - HeadRows).coerceAtLeast(0)
            val here = currentTurn(anchors, timelineLast)

            TurnNavigator(
                currentTurn = here,
                totalTurns = anchors.size,
                canGoUp = previousTurn(anchors, timelineFirst) != null,
                canGoDown = nextTurn(anchors, timelineLast) != null,
                showJumpToLatest = !followTail && anchors.isNotEmpty(),
                onPrevious = {
                    previousTurn(anchors, (listState.firstVisibleItemIndex - HeadRows).coerceAtLeast(0))
                        ?.let { target ->
                            followTail = false
                            scope.launch { listState.animateScrollToItem(target.rowIndex + HeadRows) }
                        }
                },
                onNext = {
                    val bottom = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        ?: listState.firstVisibleItemIndex) - HeadRows
                    nextTurn(anchors, bottom.coerceAtLeast(0))?.let { target ->
                        followTail = false
                        scope.launch { listState.animateScrollToItem(target.rowIndex + HeadRows) }
                    }
                },
                onJumpToLatest = {
                    latestTurn(anchors)?.let { target ->
                        scope.launch {
                            followTail = true
                            listState.animateScrollToItem(target.rowIndex + HeadRows)
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

        // Pending questions are answered on their own full-screen surface, not here:
        // the composer column does not scroll, so an inline card was unusable.

        // Messages accepted while a turn runs wait here rather than disappearing.
        if (state.sending) {
            Text(
                text = "发送中…",
                fontSize = 11.sp,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DshSpacing.md.dp, vertical = 2.dp),
            )
        }
        QueueDock(
            queued = state.queued,
            running = state.running,
            onSteer = onSteerQueued,
            onEdit = onEditQueued,
            onRemove = onRemoveQueued,
            modifier = Modifier.padding(
                horizontal = DshSpacing.md.dp,
                vertical = DshSpacing.xs.dp,
            ),
        )

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

/**
 * How a turn ended, when it did not simply complete.
 *
 * A provider failure is the case that matters: the web UI shows a red notice with
 * the message and machine code, and without it a rejected API call looked like a
 * silent no-op in the app.
 */
@Composable
private fun TurnOutcomeRow(item: TimelineItem.TurnOutcome) {
    val colors = LocalDshColors.current
    val isError = item.kind == TurnOutcomeKind.Error
    val tint = if (isError) colors.stateError else colors.labelTertiary

    Column(
        Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.08f), RoundedCornerShape(DshRadius.sm.dp))
            .padding(horizontal = DshSpacing.md.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Info,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = when (item.kind) {
                    TurnOutcomeKind.Error -> "请求失败"
                    TurnOutcomeKind.Aborted -> "已停止"
                    TurnOutcomeKind.MaxTokens -> "达到输出上限"
                    TurnOutcomeKind.Interrupted -> "上一轮中断"
                    TurnOutcomeKind.Blocked -> "被拦截"
                },
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
            )
        }
        if (!item.message.isNullOrBlank()) {
            Text(
                text = item.message,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = if (isError) colors.labelSecondary else colors.labelTertiary,
            )
        }
        // Machine code and HTTP status are what make a provider failure diagnosable
        // (bad key, rate limit, model unavailable), so both are surfaced.
        val meta = listOfNotNull(
            item.code?.takeIf { it.isNotBlank() },
            item.status?.let { "HTTP $it" },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.labelCaption,
            )
        }
    }
}

/** A slash command the operator ran, with its outcome. */
@Composable
private fun CommandRow(item: TimelineItem.CommandItem) {
    val colors = LocalDshColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.selector.copy(alpha = 0.5f), RoundedCornerShape(DshRadius.md.dp))
            .padding(horizontal = DshSpacing.md.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = item.line,
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.labelPrimary,
        )
        if (!item.result.isNullOrBlank()) {
            Text(
                text = item.result,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = if (item.failed) colors.stateError else colors.labelTertiary,
            )
        }
    }
}

/** Index of the message above the current viewport, or -1 at the top. */

/**
 * Leading row of the transcript.
 *
 * Shows a small spinner while the previous page loads — the same affordance a
 * browser gives when reaching the top of a long page — and a quiet notice once the
 * host reports no earlier events, so the operator knows the list is genuinely at
 * its start rather than merely stuck.
 */
@Composable
private fun HistoryHead(loading: Boolean, hasMore: Boolean, isEmpty: Boolean) {
    val colors = LocalDshColors.current
    if (isEmpty) {
        Spacer(Modifier.height(1.dp))
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 1.5.dp,
                color = colors.labelTertiary,
            )
            !hasMore -> Text(
                text = "已经是最早的消息",
                fontSize = 11.sp,
                color = colors.labelCaption,
            )
        }
    }
}