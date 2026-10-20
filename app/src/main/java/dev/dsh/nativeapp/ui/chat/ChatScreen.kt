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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    contentPadding: PaddingValues,
) {
    val colors = LocalDshColors.current
    val listState = rememberLazyListState()

    val itemCount = state.timeline.size
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= itemCount - 2
        }
    }

    // Follow the tail while streaming, but never fight a user scrolling back.
    LaunchedEffect(itemCount, state.running) {
        if (itemCount > 0 && (atBottom || state.running)) {
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

        Composer(
            enabled = state.sessionId != null,
            running = state.running,
            onSend = onSend,
            onStop = onStop,
            modelLabel = modelLabel,
            onModelClick = onModelClick,
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
