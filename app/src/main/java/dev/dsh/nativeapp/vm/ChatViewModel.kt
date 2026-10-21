package dev.dsh.nativeapp.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.nativeapp.DshApplication
import dev.dsh.nativeapp.data.ChatUiState
import dev.dsh.nativeapp.data.ContextBreakdown
import dev.dsh.nativeapp.data.ContextOccupancy
import dev.dsh.nativeapp.data.SessionStats
import dev.dsh.nativeapp.data.DshStream
import dev.dsh.nativeapp.data.ModelCatalog
import dev.dsh.nativeapp.data.SessionRow
import dev.dsh.nativeapp.data.SessionSummary
import dev.dsh.nativeapp.data.TimelineBuilder
import dev.dsh.nativeapp.data.WorkspaceGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.dsh.nativeapp.data.LiveQuestion
import dev.dsh.nativeapp.data.PendingRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.dsh.nativeapp.data.QueuedMessage
import dev.dsh.nativeapp.data.PendingApproval
import dev.dsh.nativeapp.data.PendingQuestion
import dev.dsh.nativeapp.data.QuestionItem
import dev.dsh.nativeapp.data.QuestionOption
import dev.dsh.nativeapp.data.SlashCommand
import dev.dsh.nativeapp.data.TodoItem
import dev.dsh.nativeapp.data.TodoStatus
import dev.dsh.nativeapp.data.WorkspaceSummary
import kotlinx.serialization.json.JsonObject

/** Owns the session index, the active session stream, and the composer state. */
class ChatViewModel(private val app: DshApplication) : ViewModel() {

    private val repo = app.repository()
    private val streams = app.streams()

    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceGroup>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceGroup>> = _workspaces.asStateFlow()

    private val _catalog = MutableStateFlow<ModelCatalog?>(null)
    val catalog: StateFlow<ModelCatalog?> = _catalog.asStateFlow()

    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()

    private var activeBuilder = TimelineBuilder()

    /** Sessions the operator pinned in the drawer; the host owns this list. */
    private val _pinnedSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedSessionIds: StateFlow<Set<String>> = _pinnedSessionIds.asStateFlow()

    /** Archived sessions, hidden from the drawer unless the filter is turned off. */
    private val _archivedSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val archivedSessionIds: StateFlow<Set<String>> = _archivedSessionIds.asStateFlow()
    private var activeStream: DshStream? = null
    private var workspaceStream: DshStream? = null
    private var workspaceIndex: Map<String, WorkspaceSummary> = emptyMap()

    init {
        refreshIndex()
        refreshCatalog()
        followWorkspaces()
        loadPermissionOptions()
        startIndexPolling()
        listenForRequests()
    }

    /**
     * Listen on `$events` for interactive requests.
     *
     * `ask_user_question` and tool approvals are Host waterfalls delivered on this
     * channel, not as answerable rows in the session log. The `userQuestions`
     * projection only mirrors them when the tool declares a timed `timeout`, so this
     * channel is the reliable source: the Host pushes the frame either way.
     */
    private fun listenForRequests() {
        val channel = app.events()
        // Reconnecting channel: pass a scope so it can reopen itself after a drop.
        channel.start(viewModelScope)
        viewModelScope.launch {
            // The Host's own running flag drives the send/stop button. Deriving it
            // from turn/start and turn/end drifted whenever an event was missed, and
            // a stop with a stale flag either did nothing or cancelled the wrong turn.
            channel.status.collect { (sessionId, running) ->
                if (sessionId == _chat.value.sessionId) {
                    // Stamp the turn's start only when the log cannot supply one, so a
                    // pushed status never restarts a clock that is already running.
                    val since = when {
                        !running -> null
                        activeBuilder.openTurnStartedAt() != null -> activeBuilder.openTurnStartedAt()
                        _chat.value.running -> _chat.value.runningSince
                        else -> System.currentTimeMillis()
                    }
                    _chat.value = _chat.value.copy(running = running, runningSince = since)
                }
                // Keep the drawer's spinner honest too.
                _sessions.value = _sessions.value.map { row ->
                    if (row.sessionId == sessionId) row.copy(running = running) else row
                }
                regroup()
            }
        }
        viewModelScope.launch {
            channel.requests.collect { request ->
                when (request) {
                    is PendingRequest.Question -> {
                        val live = LiveQuestion(
                            eventId = request.eventId,
                            agentId = request.agentId,
                            questions = request.questions,
                        )
                        // Attribute the question to its session and keep it there.
                        // It used to be shown whenever the app had no session open yet
                        // (a null id passed the old filter), so a question belonging to
                        // another conversation flashed over the screen at startup and
                        // then vanished as `openSession` replaced the state.
                        val owner = request.agentId
                        if (owner != null) {
                            pendingQuestions[owner] = live
                            if (owner != _chat.value.sessionId) return@collect
                        }
                        _chat.value = _chat.value.copy(liveQuestion = live)
                    }
                    is PendingRequest.Approval -> {
                        _chat.value = _chat.value.copy(
                            pendingApproval = PendingApproval(
                                callId = request.eventId,
                                toolName = request.toolName,
                                reason = request.reason,
                                detail = request.detail,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Questions the Host is blocking on, keyed by their session.
     *
     * `$events` replays every still-open waterfall to each new subscriber, so these
     * pile up across sessions. Holding them here lets `openSession` surface the one
     * that belongs to the conversation being opened, instead of flashing a foreign
     * question at startup.
     */
    private val pendingQuestions = mutableMapOf<String, LiveQuestion>()

    /** Hide the question panel without answering; the Host stays blocked. */
    fun dismissLiveQuestion() {
        _chat.value = _chat.value.copy(liveQuestion = null)
    }

    /** Remove a queued message that has not started its turn yet. */
    fun removeQueued(itemId: String) {
        val sessionId = _chat.value.sessionId ?: return
        _chat.value = _chat.value.copy(
            queued = _chat.value.queued.filterNot { it.id == itemId },
        )
        viewModelScope.launch {
            runCatching { repo.updateQueue(sessionId, itemId, "remove") }
                .onSuccess { refreshProjections() }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "删除排队消息失败"))
                    refreshIndex()
                }
        }
    }

    /** Replace the text of a queued message. */
    fun editQueued(itemId: String, text: String) {
        val sessionId = _chat.value.sessionId ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.updateQueue(sessionId, itemId, "edit", text) }
                .onSuccess { refreshProjections() }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "修改排队消息失败"))
                }
        }
    }

    /**
     * Send a queued message now, interrupting the running turn.
     *
     * This is the web UI's 插话发送: only meaningful while a turn runs, which the
     * Host enforces.
     */
    fun steerQueued(itemId: String) {
        val sessionId = _chat.value.sessionId ?: return
        if (!_chat.value.running) return
        viewModelScope.launch {
            runCatching { repo.updateQueue(sessionId, itemId, "steer") }
                .onSuccess { refreshProjections() }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "插话发送失败"))
                }
        }
    }

    /** Submit answers for the live question and clear the card. */
    fun answerLiveQuestion(answers: Map<String, List<String>>) {
        val live = _chat.value.liveQuestion ?: return
        val request = PendingRequest.Question(live.eventId, live.agentId, live.questions)
        // The card stays up until the Host accepts the answer. Optimistically
        // clearing it made a failed submit look like a silent success: the question
        // vanished, the turn stayed blocked, and there was no way to retry.
        _chat.value = _chat.value.copy(sending = true, error = null)
        viewModelScope.launch {
            val channel = app.events()
            // Wait briefly for a `ready` frame if the channel is reconnecting; the
            // answer is addressable only after the Host has named this client.
            if (channel.clientId.value == null) {
                withTimeoutOrNull(3_000) {
                    channel.ready.first { it }
                }
            }
            val ok = channel.answer(request, channel.answerValue(answers))
            if (ok) {
                live.agentId?.let { pendingQuestions.remove(it) }
                _chat.value = _chat.value.copy(liveQuestion = null, sending = false)
            } else {
                _chat.value = _chat.value.copy(
                    sending = false,
                    error = channel.lastError.value
                        ?: if (channel.clientId.value == null) "事件通道正在重连，请重试" else "提交回答失败",
                )
            }
        }
    }

    /** Approve or reject the live approval request. */
    fun answerLiveApproval(allowed: Boolean) {
        val pending = _chat.value.pendingApproval ?: return
        val request = PendingRequest.Approval(
            eventId = pending.callId,
            agentId = _chat.value.sessionId,
            callId = pending.callId,
            toolName = pending.toolName,
            reason = pending.reason,
            detail = pending.detail,
        )
        viewModelScope.launch {
            val value = buildJsonObject {
                put("outcome", if (allowed) "allowed-once" else "rejected")
            }
            val channel = app.events()
            val ok = channel.answer(request, value)
            _chat.value = if (ok) {
                _chat.value.copy(pendingApproval = null)
            } else {
                _chat.value.copy(
                    error = channel.lastError.value ?: "提交确认失败",
                )
            }
        }
    }

    /**
     * Keep the drawer current.
     *
     * The session index is a unary RPC with no push channel, so titles, running
     * flags and ordering only change when it is re-read. A slow poll keeps the list
     * honest while the app is open, and the drawer refreshes on open as well.
     */
    private fun startIndexPolling() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                // No session-id guard: the drawer must keep working (and reflect new
                // sessions) even before the first conversation is opened, which is
                // exactly when a fresh session is created.
                val summaries = runCatching { repo.listSessions() }.getOrNull() ?: continue
                applySessions(summaries)
            }
        }
    }

    /** Re-read the index; called when the drawer opens so the list is never stale. */
    fun refreshIndexNow() = refreshIndex()

    /** Pin or unpin a session; the host answers with the authoritative id list. */
    fun setPinned(sessionId: String, pinned: Boolean) {
        viewModelScope.launch {
            val ids = repo.setPinned(sessionId, pinned)
            if (ids == null) {
                _chat.value = _chat.value.copy(error = if (pinned) "置顶失败" else "取消置顶失败")
                return@launch
            }
            _pinnedSessionIds.value = ids.toSet()
            regroup()
        }
    }

    /** Archive or restore a session, hiding it from the drawer. */
    fun setArchived(sessionId: String, archived: Boolean) {
        viewModelScope.launch {
            val ids = repo.setArchived(sessionId, archived)
            if (ids == null) {
                _chat.value = _chat.value.copy(error = if (archived) "归档失败" else "恢复失败")
                return@launch
            }
            _archivedSessionIds.value = ids.toSet()
            regroup()
        }
    }

    // ------------------------------------------------------------ history

    /** Guards against stacking page fetches while one is in flight. */
    private var loadingHistory = false

    private val _hasMoreHistory = MutableStateFlow(false)

    /** True when the host reported more history above what is loaded. */
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)

    /** True while a previous page is being fetched, for the top-of-list spinner. */
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    /**
     * Fetch the previous page of history and prepend it.
     *
     * The follow stream only replays the newest window (`maxMessages`), so scrolling
     * past it showed nothing even though the session continues. `session/page`
     * accepts `beforeSeq` to walk backwards, and answers `hasMore` for the next step.
     *
     * `throughSeq` must be the session's real cursor: the host rejects anything past
     * it with `session page through seq ... is past cursor ...`, which silently made
     * every earlier attempt at paging fail.
     */
    fun loadOlderHistory() {
        val sessionId = _chat.value.sessionId ?: return
        if (loadingHistory || !_hasMoreHistory.value) return
        val cursor = activeBuilder.oldestSeq()
        if (cursor <= 0) {
            // Nothing has been folded yet, so the window's start is unknown — not
            // reached. Concluding "no more history" here is what made the app claim
            // 已经是最早的消息 permanently: the list's scroll indicator emits (0, 0)
            // as soon as it composes, which fires this before the follow snapshot
            // arrives.
            return
        }
        loadingHistory = true
        _loadingOlder.value = true
        viewModelScope.launch {
            try {
                val head = repo.listSessions().firstOrNull { it.sessionId == sessionId }
                    ?: run {
                        _hasMoreHistory.value = false
                        return@launch
                    }
                val page = repo.loadPage(
                    sessionId = sessionId,
                    throughSeq = head.projections?.asOfSeq ?: 0L,
                    maxMessages = 24,
                    beforeSeq = cursor,
                )
                val before = activeBuilder.oldestSeq()
                activeBuilder.prependPage(page.records)
                val after = activeBuilder.oldestSeq()
                // A page that produced no new rows cannot advance the window, so
                // asking again would loop forever on the same answer. That is the
                // only case where "no more history" is the honest report.
                val advanced = after < before && after > 0
                _hasMoreHistory.value = page.hasMore && advanced
                if (advanced) {
                    _chat.value = _chat.value.copy(timeline = activeBuilder.snapshot())
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _chat.value = _chat.value.copy(error = describe(error, "加载更早的消息失败"))
            } finally {
                loadingHistory = false
                _loadingOlder.value = false
            }
        }
    }

    // -------------------------------------------------------------- index

    fun refreshIndex() {
        viewModelScope.launch {
            try {
                applySessions(repo.listSessions())
                openMostRecentIfIdle()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _chat.value = _chat.value.copy(
                    connecting = false,
                    error = describe(error, "无法读取会话列表"),
                )
            }
        }
    }

    /** Fold one `session/list` answer into the drawer rows and regroup. */
    private suspend fun applySessions(summaries: List<SessionSummary>) {
        _sessions.value = summaries
            .filter { it.parentSessionId == null }
            .map { summary ->
                SessionRow(
                    sessionId = summary.sessionId,
                    title = summary.title,
                    subtitle = summary.cwd?.substringAfterLast('\\')?.substringAfterLast('/') ?: "",
                    running = summary.running,
                    updatedAt = summary.updatedAt,
                    blank = summary.blank,
                    cwd = summary.cwd.orEmpty(),
                )
            }
            .sortedByDescending { it.updatedAt }
        regroup()
    }

    /** Turn transport and gateway failures into something actionable. */
    private fun describe(error: Throwable, fallback: String): String = when {
        error is dev.dsh.nativeapp.data.DshRemoteException && error.code.startsWith("transport/http-401") ->
            "配对已过期，返回设置重新连接"
        error is dev.dsh.nativeapp.data.DshRemoteException && error.code.startsWith("transport/http-403") ->
            "主机拒绝了该请求（Host 校验失败）"
        error is java.net.UnknownHostException || error is java.net.ConnectException ->
            "连不上主机，检查地址和网络"
        error is java.net.SocketTimeoutException -> "主机响应超时"
        else -> error.message ?: fallback
    }

    /**
     * Land on the session the operator was last reading.
     *
     * The web app restores the last conversation rather than the freshest one. Those
     * differ: a background job, a subagent or a scheduled turn can bump another
     * session's `updatedAt` while the operator is reading an older one, so ordering
     * by recency jumped them somewhere they were not. The remembered id wins when it
     * still exists; otherwise fall back to the most recent non-blank session.
     */
    private fun openMostRecentIfIdle() {
        if (_chat.value.sessionId != null) return
        val rows = _sessions.value
        if (rows.isEmpty()) return
        val remembered = app.hostStore.lastSessionId()?.takeIf { id -> rows.any { it.sessionId == id } }
        val target = remembered
            ?: rows.firstOrNull { !it.blank }?.sessionId
            ?: rows.first().sessionId
        openSession(target)
    }

    private fun followWorkspaces() {
        workspaceStream?.cancel()
        workspaceStream = streams.followWorkspaces(
            onBaseline = { baseline ->
                workspaceIndex = baseline.items.associateBy { it.workspaceId }
                _pinnedSessionIds.value = baseline.pinnedSessionIds.toSet()
                _archivedSessionIds.value = baseline.archivedSessionIds.toSet()
                viewModelScope.launch { regroup() }
            },
            onPinned = { ids ->
                _pinnedSessionIds.value = ids.toSet()
                viewModelScope.launch { regroup() }
            },
            onArchived = { ids ->
                _archivedSessionIds.value = ids.toSet()
                viewModelScope.launch { regroup() }
            },
        )
    }

    private suspend fun regroup() {
        val pinned = _pinnedSessionIds.value
        val archived = _archivedSessionIds.value

        // Group from the session index, not from a workspace's cached `sessionIds`.
        // That list arrives once in the `workspace/follow` baseline and is only
        // updated for pin/archive frames, so a session created afterwards never
        // appeared in the drawer however often the index was re-read. The workspace
        // record is still used, but only to give the group its name.
        val titleByPath = workspaceIndex.values.associate { normalisePath(it.path) to it.displayName }

        val visible = _sessions.value.filter { it.sessionId !in archived }
        val groups = visible
            .groupBy { normalisePath(it.cwd) }
            .map { (path, sessions) ->
                WorkspaceGroup(
                    workspaceId = path.ifBlank { "unassigned" },
                    title = titleByPath[path]
                        ?: sessions.firstOrNull()?.subtitle?.takeIf { it.isNotBlank() }
                        ?: "其他",
                    // Pinned conversations lead their group, then most recent first.
                    sessions = sessions
                        .sortedWith(
                            compareByDescending<SessionRow> { it.sessionId in pinned }
                                .thenByDescending { it.updatedAt },
                        )
                        .map { it.copy(pinned = it.sessionId in pinned) },
                )
            }
            .sortedByDescending { group -> group.sessions.maxOfOrNull { it.updatedAt } ?: 0L }
        _workspaces.value = groups
    }

    /** Compare directories the way the host would, across separators and case. */
    private fun normalisePath(path: String): String =
        path.replace('/', '\\').trimEnd('\\').lowercase()

    fun refreshCatalog() {
        viewModelScope.launch {
            runCatching { repo.modelCatalog() }
                .onSuccess { _catalog.value = it }
        }
    }

    // -------------------------------------------------------------- session

    /** Session whose transcript is already in memory, to skip a redundant replay. */
    private var loadedSessionId: String? = null

    fun openSession(sessionId: String) {
        if (_chat.value.sessionId == sessionId && activeStream != null) return

        // Remember what the operator is actually reading, so relaunch resumes here
        // instead of jumping to whichever session a background job touched last.
        app.hostStore.saveLastSessionId(sessionId)

        // Re-entering the session that is already loaded keeps its timeline until
        // the fresh snapshot arrives, so the screen never flashes empty.
        val keepTimeline = loadedSessionId == sessionId

        detach()

        activeBuilder = TimelineBuilder()
        // Any session may have more history than the follow window replays, so
        // paging starts enabled and switches off once the host says so.
        _hasMoreHistory.value = true
        // A pending question must survive session switching: the startup path opens
        // the newest session right after `$events` delivers a waterfall, and wiping
        // the state here made the card flash and vanish.
        val carriedQuestion = _chat.value.liveQuestion
        val carriedApproval = _chat.value.pendingApproval
        // A question the Host is still blocking on belongs to its own session, so it
        // is restored from the per-session map rather than carried blindly from the
        // previous screen. An unattributed request (no agent id) has nowhere to live
        // in that map, so it is carried as-is.
        val restored = pendingQuestions[sessionId]
            ?: carriedQuestion?.takeIf { it.agentId == null }
        val seeded = _sessions.value.firstOrNull { it.sessionId == sessionId }
        _chat.value = ChatUiState(
            sessionId = sessionId,
            connecting = true,
            title = if (keepTimeline) _chat.value.title else "",
            timeline = if (keepTimeline) _chat.value.timeline else emptyList(),
            liveQuestion = restored,
            pendingApproval = carriedApproval,
            running = seeded?.running ?: false,
            runningSince = null,
        )
        loadCommands()
        val title = _sessions.value.firstOrNull { it.sessionId == sessionId }?.title.orEmpty()

        activeStream = streams.followSession(
            sessionId = sessionId,
            onItem = { value ->
                activeBuilder.applyFollowValue(value)
                loadedSessionId = sessionId
                // Compaction rewrites the model surface without changing what the
                // follow snapshot reported, so the info sheet only updated after a
                // manual re-read. Refresh as soon as the host says it compacted.
                if (isCompactionDone(value)) refreshProjections()
                val running = detectRunning(value)
                val usage = detectUsage(value)
                val projections = detectProjections(value)
                val approval = detectApproval(value)
                val selection = detectModelSelection(value)
                val extras = detectExtras(value)
                viewModelScope.launch {
                    // The folded log is authoritative for a client that attached
                    // mid-turn: an unclosed `turn/start` means the session is running
                    // and its timestamp is when the clock should have started. The
                    // pushed status still wins when it arrives, but it no longer
                    // resets the elapsed time to zero.
                    val openTurn = activeBuilder.openTurnStartedAt()
                    val nextRunning = running ?: _chat.value.running || openTurn != null
                    val since = when {
                        !nextRunning -> null
                        // Prefer the durable timestamp over a wall-clock stamp taken
                        // when a frame happened to arrive.
                        openTurn != null -> openTurn
                        _chat.value.runningSince != null -> _chat.value.runningSince
                        else -> System.currentTimeMillis()
                    }
                    _chat.value = _chat.value.copy(
                        timeline = activeBuilder.snapshot(),
                        title = title,
                        connecting = false,
                        running = nextRunning,
                        runningSince = since,
                        tokenUsage = usage ?: _chat.value.tokenUsage,
                        todos = projections?.first ?: _chat.value.todos,
                        permissionPreset = projections?.second ?: _chat.value.permissionPreset,
                        pendingApproval = approval ?: _chat.value.pendingApproval,
                        modelProvider = selection?.first ?: _chat.value.modelProvider,
                        modelName = selection?.second ?: _chat.value.modelName,
                        reasoningEffort = selection?.third ?: _chat.value.reasoningEffort,
                        contextBreakdown = extras?.breakdown ?: _chat.value.contextBreakdown,
                        contextOccupancy = extras?.occupancy ?: _chat.value.contextOccupancy,
                        sessionStats = extras?.stats ?: _chat.value.sessionStats,
                        contextWindow = extras?.window ?: _chat.value.contextWindow,
                        queued = extras?.queued ?: _chat.value.queued,
                    )
                }
            },
            onError = { error ->
                viewModelScope.launch {
                    _chat.value = _chat.value.copy(
                        connecting = false,
                        error = describe(error, "连接中断"),
                    )
                }
            },
            onClosed = {
                viewModelScope.launch {
                    _chat.value = _chat.value.copy(connecting = false, running = false)
                }
            },
        )
    }

    /**
     * True for the events a compaction lands.
     *
     * `compaction/end` is the marker that means the model surface actually shrank,
     * which is exactly when `contextPressure` and `contextBreakdown` change without a
     * new follow snapshot. `command/done` is deliberately NOT used: it carries only a
     * `commandId`, never the command name, so it cannot identify `/compact`.
     */
    private fun isCompactionDone(value: JsonObject): Boolean {
        if (dev.dsh.nativeapp.data.JsonText.string(value["type"]) != "event") return false
        val event = value["event"] as? JsonObject ?: return false
        return dev.dsh.nativeapp.data.JsonText.string(event["type"]) == "compaction/end"
    }

    /**
     * Fallback only: `api-session/status` on `$events` is authoritative, but it is
     * missed if the app attached after a turn started. Reading `turn/start` and
     * `turn/end` from the session log recovers that case; it never overrides a
     * pushed flag because the push is delivered later in the same tick.
     */
    private fun detectRunning(value: JsonObject): Boolean? {
        if (dev.dsh.nativeapp.data.JsonText.string(value["type"]) != "event") return null
        val event = value["event"] as? JsonObject ?: return null
        return when (dev.dsh.nativeapp.data.JsonText.string(event["type"])) {
            "turn/start", "step/start" -> true
            "turn/end" -> false
            else -> null
        }
    }

    /** Pull the token accounting projection out of a snapshot when present. */
    private fun detectUsage(value: JsonObject): dev.dsh.nativeapp.data.TokenUsage? {
        if (dev.dsh.nativeapp.data.JsonText.string(value["type"]) != "snapshot") return null
        val projections = value["projections"] as? JsonObject ?: return null
        val values = projections["values"] as? JsonObject ?: return null
        return dev.dsh.nativeapp.data.TokenUsage.from(values["tokenUsage"])
    }

    /**
     * Read the projections the chrome depends on: todos and the active
     * permission preset. Both travel in the follow snapshot, so no extra RPC.
     */
    private fun detectProjections(value: JsonObject): Pair<List<TodoItem>, String?>? {
        if (dev.dsh.nativeapp.data.JsonText.string(value["type"]) != "snapshot") return null
        val projections = value["projections"] as? JsonObject ?: return null
        val values = projections["values"] as? JsonObject ?: return null

        val todos = (values["todos"] as? JsonObject)?.let { todoContainer ->
            val items = todoContainer["items"] as? kotlinx.serialization.json.JsonArray
                ?: todoContainer["todos"] as? kotlinx.serialization.json.JsonArray
                ?: return@let null
            items.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val content = dev.dsh.nativeapp.data.JsonText.string(obj["content"]) ?: return@mapNotNull null
                val status = when (dev.dsh.nativeapp.data.JsonText.string(obj["status"])) {
                    "in_progress" -> TodoStatus.InProgress
                    "completed", "done" -> TodoStatus.Completed
                    else -> TodoStatus.Pending
                }
                TodoItemOf(content, status)
            }
        }.orEmpty()

        val preset = (values["permissions"] as? JsonObject)
            ?.let { dev.dsh.nativeapp.data.JsonText.string(it["currentValue"]) }
        return todos to preset
    }

    /**
     * Re-read the session's live projections.
     *
     * The follow stream pushes projections only in its opening snapshot, so anything
     * that changes afterwards — the context size after `/compact` being the case that
     * matters — stayed at its attach-time value and the info sheet looked frozen.
     * `session/projections` is the authoritative read for the same numbers.
     */
    fun refreshProjections() {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            val snapshot = repo.projections(sessionId) ?: return@launch
            // Build the same envelope `detectExtras` understands so both paths share
            // one parser instead of two that can drift.
            val envelope = buildJsonObject {
                put("type", "snapshot")
                put(
                    "projections",
                    buildJsonObject {
                        put("asOfSeq", snapshot.asOfSeq)
                        put("values", snapshot.values)
                    },
                )
            }
            val extras = detectExtras(envelope) ?: return@launch
            val usage = detectUsage(envelope)
            val selection = detectModelSelection(envelope)
            _chat.value = _chat.value.copy(
                contextBreakdown = extras.breakdown ?: _chat.value.contextBreakdown,
                contextOccupancy = extras.occupancy ?: _chat.value.contextOccupancy,
                sessionStats = extras.stats ?: _chat.value.sessionStats,
                contextWindow = extras.window ?: _chat.value.contextWindow,
                queued = extras.queued,
                tokenUsage = usage ?: _chat.value.tokenUsage,
                modelProvider = selection?.first ?: _chat.value.modelProvider,
                modelName = selection?.second ?: _chat.value.modelName,
                reasoningEffort = selection?.third ?: _chat.value.reasoningEffort,
            )
        }
    }

    /**
     * Read the session's effective model selection.
     *
     * `modelSelection.next` is what the next turn will use, which is what the
     * picker must highlight; `lastUsed` lags behind a switch.
     */
    private fun detectModelSelection(value: JsonObject): Triple<String, String, String?>? {
        if (dev.dsh.nativeapp.data.JsonText.string(value["type"]) != "snapshot") return null
        val projections = value["projections"] as? JsonObject ?: return null
        val values = projections["values"] as? JsonObject ?: return null
        val selection = values["modelSelection"] as? JsonObject ?: return null
        val chosen = (selection["next"] as? JsonObject) ?: (selection["lastUsed"] as? JsonObject) ?: return null
        val provider = dev.dsh.nativeapp.data.JsonText.string(chosen["provider"]) ?: return null
        val model = dev.dsh.nativeapp.data.JsonText.string(chosen["model"]) ?: return null
        val effort = dev.dsh.nativeapp.data.JsonText.string(chosen["reasoningEffort"])
        return Triple(provider, model, effort)
    }

    /** Context split, occupancy, and session counters shown in the info sheet. */
    private data class Extras(
        val breakdown: ContextBreakdown?,
        val occupancy: ContextOccupancy?,
        val stats: SessionStats?,
        val pressure: Long?,
        val window: Int?,
        val questions: List<PendingQuestion>,
        val queued: List<QueuedMessage>,
    )

    private fun detectExtras(value: JsonObject): Extras? {
        if (dev.dsh.nativeapp.data.JsonText.string(value["type"]) != "snapshot") return null
        val projections = value["projections"] as? JsonObject ?: return null
        val values = projections["values"] as? JsonObject ?: return null

        fun num(container: JsonObject?, key: String): Long? =
            dev.dsh.nativeapp.data.JsonText.long(container?.get(key))

        // The prompt's composition. This is NOT the window occupancy: it says what
        // the prompt is made of, while `contextPressure` says how full the window is.
        val breakdownObj = values["contextBreakdown"] as? JsonObject
        val breakdown = breakdownObj?.let {
            ContextBreakdown(
                systemTokens = num(it, "systemTokens") ?: 0,
                toolsTokens = num(it, "toolsTokens") ?: 0,
                messageTokens = num(it, "messageTokens") ?: 0,
            )
        }

        val statsObj = values["sessionStats"] as? JsonObject
        val stats = statsObj?.let {
            SessionStats(
                turns = num(it, "turns")?.toInt() ?: 0,
                steps = num(it, "steps")?.toInt() ?: 0,
                llmMs = num(it, "llmMs") ?: 0,
                toolMs = num(it, "toolMs") ?: 0,
                decodeTokens = num(it, "decodeTokens") ?: 0,
                ttftMs = num(it, "ttftMs") ?: 0,
                decodeMs = num(it, "decodeMs") ?: 0,
                ttftSteps = num(it, "ttftSteps")?.toInt() ?: 0,
            )
        }

        // Occupancy follows the web client's `contextOccupancy` exactly:
        // `projectedTokens ?? pressureTokens` against `contextWindow`. The previous
        // code read `pressureTokens` first, which is the *last measured* figure and
        // lags the projection, so the meter read low right after a compaction.
        val pressureObj = values["contextPressure"] as? JsonObject
        val used = num(pressureObj, "projectedTokens") ?: num(pressureObj, "pressureTokens")
        val window = num(pressureObj, "contextWindow")
        val occupancy = if (used != null && window != null && window > 0) {
            ContextOccupancy(usedTokens = used, contextWindow = window)
        } else {
            null
        }

        return Extras(
            breakdown = breakdown,
            occupancy = occupancy,
            stats = stats,
            pressure = used,
            window = window?.toInt(),
            questions = parseQuestions(values["userQuestions"] as? JsonObject),
            queued = parseQueue(values["inbox"] as? JsonObject),
        )
    }

    /**
     * Read `inbox.next-turn`: messages admitted while a turn ran.
     *
     * This is the same source the web UI's queue dock uses. `next-step` is separate
     * — those messages are folded into the running turn rather than queued behind it.
     */
    private fun parseQueue(inbox: JsonObject?): List<QueuedMessage> {
        val rows = inbox?.get("next-turn") as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        return rows.mapNotNull { element ->
            val msg = element as? JsonObject ?: return@mapNotNull null
            val id = dev.dsh.nativeapp.data.JsonText.string(msg["id"]) ?: return@mapNotNull null
            val content = msg["content"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            val text = content.mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                if (dev.dsh.nativeapp.data.JsonText.string(obj["type"]) != "text") return@mapNotNull null
                dev.dsh.nativeapp.data.JsonText.string(obj["text"])
            }.joinToString("\n")
            val images = content.count {
                dev.dsh.nativeapp.data.JsonText.string(dev.dsh.nativeapp.data.JsonText.field(it, "type")) == "image"
            }
            val files = content.mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                if (dev.dsh.nativeapp.data.JsonText.string(obj["type"]) != "file") return@mapNotNull null
                dev.dsh.nativeapp.data.JsonText.string(obj["name"]) ?: "附件"
            }
            QueuedMessage(id = id, text = text, imageCount = images, fileNames = files)
        }
    }

    /**
     * The host's `userQuestions` projection, kept as a secondary signal.
     *
     * It only contains a batch when the tool schema declares a timed `timeout`
     * parameter; this deployment's `ask_user_question` has none, so the pending
     * question is recovered from the `tool/call` event instead (see
     * `TimelineBuilder`). Reading the projection stays here so an upgraded host
     * starts working without a client change.
     */
    private fun parseQuestions(container: JsonObject?): List<PendingQuestion> {
        val active = container?.get("active") as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        return active.mapNotNull { element ->
            val batch = element as? JsonObject ?: return@mapNotNull null
            val callId = dev.dsh.nativeapp.data.JsonText.string(batch["callId"]) ?: return@mapNotNull null
            val questions = (batch["questions"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { raw ->
                    val q = raw as? JsonObject ?: return@mapNotNull null
                    val id = dev.dsh.nativeapp.data.JsonText.string(q["id"]) ?: return@mapNotNull null
                    val text = dev.dsh.nativeapp.data.JsonText.string(q["question"]) ?: return@mapNotNull null
                    QuestionItem(
                        id = id,
                        question = text,
                        detail = dev.dsh.nativeapp.data.JsonText.string(q["detail"]),
                        header = dev.dsh.nativeapp.data.JsonText.string(q["header"]),
                        options = (q["options"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                            .mapNotNull { opt ->
                                val o = opt as? JsonObject ?: return@mapNotNull null
                                val label = dev.dsh.nativeapp.data.JsonText.string(o["label"])
                                    ?: return@mapNotNull null
                                QuestionOption(label, dev.dsh.nativeapp.data.JsonText.string(o["description"]))
                            },
                        // The projection uses camelCase; the tool's own arguments use
                        // `multi_select`. Accept either so a shape change cannot hide
                        // the question.
                        multiSelect = dev.dsh.nativeapp.data.JsonText.bool(q["multiSelect"])
                            ?: dev.dsh.nativeapp.data.JsonText.bool(q["multi_select"])
                            ?: false,
                    )
                }
            if (questions.isEmpty()) null else PendingQuestion(callId, questions)
        }
    }

    /** Submit answers for one `ask_user_question` batch. */
    fun answerQuestions(callId: String, answers: Map<String, List<String>>) {
        val sessionId = _chat.value.sessionId ?: return
        _chat.value = _chat.value.copy(questions = _chat.value.questions.filterNot { it.callId == callId })
        viewModelScope.launch {
            runCatching { repo.answerQuestions(sessionId, callId, answers) }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "提交回答失败"))
                }
        }
    }

    private fun TodoItemOf(content: String, status: TodoStatus): TodoItem = TodoItem(content, status)

    /** Detect an approval request carried by a live event. */
    private fun detectApproval(value: JsonObject): PendingApproval? {
        if (dev.dsh.nativeapp.data.JsonText.string(value["type"]) != "event") return null
        val event = value["event"] as? JsonObject ?: return null
        val eventType = dev.dsh.nativeapp.data.JsonText.string(event["type"]) ?: return null
        if (eventType != "user-questions/request" && eventType != "approval/asked") return null
        val data = event["data"] as? JsonObject ?: return null
        val request = data["request"] as? JsonObject ?: data
        val callId = dev.dsh.nativeapp.data.JsonText.string(request["callId"])
            ?: dev.dsh.nativeapp.data.JsonText.string(request["id"])
            ?: return null
        val toolName = dev.dsh.nativeapp.data.JsonText.string(request["toolName"])
            ?: dev.dsh.nativeapp.data.JsonText.string(request["tool"])
            ?: "工具调用"
        val reason = dev.dsh.nativeapp.data.JsonText.string(request["reason"])
            ?: dev.dsh.nativeapp.data.JsonText.string(request["title"])
            ?: "这个操作需要你确认"
        val detail = dev.dsh.nativeapp.data.JsonText.string(request["detail"])
            ?: dev.dsh.nativeapp.data.JsonText.string(request["command"])
        return PendingApproval(callId, toolName, reason, detail)
    }

    /**
     * Create a session. Without an explicit workspace the host places it under
     * the active workspace's cwd, so the newest session inherits the same root
     * the web app would use.
     */
    fun newSession(workspaceId: String? = null) {
        viewModelScope.launch {
            val target = workspaceId ?: workspaceIndex.values.firstOrNull()?.workspaceId
            runCatching { repo.createSession(target) }
                .onSuccess { id ->
                    refreshIndex()
                    openSession(id)
                }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "无法创建会话"))
                }
        }
    }

    /** Uploaded attachments waiting to be sent with the next prompt. */
    private val _attachments = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val attachments: StateFlow<List<Pair<String, String>>> = _attachments.asStateFlow()

    /** Upload one picked file and remember its receipt for the next send. */
    fun addAttachment(name: String, bytes: ByteArray) {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { repo.uploadAttachment(sessionId, name, bytes) }
                .onSuccess { receipt ->
                    _attachments.value = _attachments.value + (name to receipt)
                }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "上传失败"))
                }
        }
    }

    fun removeAttachment(index: Int) {
        _attachments.value = _attachments.value.filterIndexed { i, _ -> i != index }
    }

    /**
     * Send the draft. Attachments ride along as file references; the plain-prompt
     * path is used when none are pending.
     */
    fun send(text: String) {
        val sessionId = _chat.value.sessionId ?: return
        val trimmed = text.trim()
        val files = _attachments.value.map { it.second }
        if (trimmed.isEmpty() && files.isEmpty()) return
        // `queue` always: the message waits for the next turn and appears in the
        // queue dock. `steer` means "interrupt the running turn and take over",
        // which is what the dock's 插话发送 button is for. Sending with `steer`
        // here made every message while a turn ran cancel that turn, and the queue
        // dock could never fill — the queue mechanism looked unimplemented.
        //
        // The web UI's own `ui-conversation.busyEnter` setting is `"queue"`, i.e.
        // pressing enter while busy queues rather than steers.
        val mode = "queue"
        // Surface the in-flight window: the host admits the prompt and only then
        // lists it in the inbox, so without this the message briefly vanishes.
        _chat.value = _chat.value.copy(sending = true)
        viewModelScope.launch {
            runCatching {
                if (files.isEmpty()) {
                    repo.sendPrompt(sessionId, trimmed, mode)
                } else {
                    repo.sendPromptWithFiles(sessionId, trimmed, files, mode)
                    _attachments.value = emptyList()
                }
            }
                .onSuccess {
                    refreshIndexSoon()
                    // The queue dock reads `inbox.next-turn`, which the follow stream
                    // only reports in a snapshot. Re-read projections so a message sent
                    // into the queue appears in the dock immediately instead of after
                    // the next unrelated update.
                    refreshProjections()
                }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "发送失败"))
                }
            _chat.value = _chat.value.copy(sending = false)
        }
    }

    private fun refreshIndexSoon() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_500)
            refreshIndex()
        }
    }

    fun stop() {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { repo.cancelSession(sessionId) }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "取消失败"))
                }
        }
    }

    fun dismissError() {
        _chat.value = _chat.value.copy(error = null)
    }

    /** Switch the active session's model, then reload the catalog projection. */
    /**
     * Switch the model or reasoning effort.
     *
     * `session/selectModel` answers with the applied selection, so the UI is updated
     * from that reply instead of waiting for the next `session/follow` snapshot —
     * that wait is why the composer chip only changed after leaving and re-entering
     * the screen.
     */
    fun selectModel(provider: String, model: String, reasoningEffort: String?) {
        val sessionId = _chat.value.sessionId ?: return
        // Optimistic: the picker closes immediately, so show the intent at once.
        _chat.value = _chat.value.copy(
            modelProvider = provider,
            modelName = model,
            reasoningEffort = reasoningEffort ?: _chat.value.reasoningEffort,
        )
        viewModelScope.launch {
            runCatching { repo.selectModel(sessionId, provider, model, reasoningEffort) }
                .onSuccess { applied ->
                    _chat.value = _chat.value.copy(
                        modelProvider = applied.provider,
                        modelName = applied.model,
                        reasoningEffort = applied.reasoningEffort,
                    )
                    refreshCatalog()
                }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "切换模型失败"))
                }
        }
    }

    /** Load the slash commands offered for the active session. */
    fun loadCommands() {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { repo.listCommands(sessionId) }
                .onSuccess { _chat.value = _chat.value.copy(commands = it) }
        }
    }

    /** Run a slash command line. */
    fun runCommand(line: String) {
        val sessionId = _chat.value.sessionId ?: return
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { repo.executeCommand(sessionId, trimmed) }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "命令执行失败"))
                }
        }
    }

    /** Approve or reject the pending tool call. */
    fun answerApproval(allowed: Boolean) {
        val sessionId = _chat.value.sessionId ?: return
        val pending = _chat.value.pendingApproval ?: return
        // Clear optimistically so the card cannot be double-submitted.
        _chat.value = _chat.value.copy(pendingApproval = null)
        viewModelScope.launch {
            runCatching { repo.answerApproval(sessionId, pending.callId, allowed) }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(
                        pendingApproval = pending,
                        error = describe(error, "提交确认失败"),
                    )
                }
        }
    }

    /**
     * Branch from one message: keep the history up to `atSeq` in a new session and
     * drop everything after it, then open the new session.
     */
    fun branchFrom(seq: Long) {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { repo.forkSession(sessionId, seq) }
                .onSuccess { childId ->
                    refreshIndex()
                    openSession(childId)
                }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "分支失败"))
                }
        }
    }

    /** Switch the permission preset for this session. */
    fun setPermission(preset: String) {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { repo.setPermissionPreset(sessionId, preset) }
                .onSuccess {
                    _chat.value = _chat.value.copy(permissionPreset = preset)
                    refreshIndex()
                }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "切换权限失败"))
                }
        }
    }

    /** Load the permission presets the host offers. */
    fun loadPermissionOptions() {
        viewModelScope.launch {
            runCatching { repo.permissionCatalog() }
                .onSuccess { _permissions.value = it.options }
        }
    }

    // -------------------------------------------------------------- settings

    private val _settings = MutableStateFlow<dev.dsh.nativeapp.data.DshRepository.SettingsSnapshot?>(null)

    /** The host's live settings document, as the web UI's settings panes read it. */
    val settings: StateFlow<dev.dsh.nativeapp.data.DshRepository.SettingsSnapshot?> =
        _settings.asStateFlow()

    /** Re-read the settings document. */
    fun loadSettings() {
        viewModelScope.launch {
            runCatching { repo.settings() }.onSuccess {
                _settings.value = it
                // LLM provider configuration lives in settings, so the model
                // catalog is derived from it. Re-read both together or the picker
                // keeps offering providers that were just removed.
                refreshCatalog()
            }
        }
    }

    /**
     * Set one leaf of a settings namespace.
     *
     * Optimistically applies the value so a chip does not flick back while the
     * round-trip completes, then re-reads the document because the host normalizes
     * what it stores.
     */
    fun updateSetting(ns: String, path: List<String>, value: kotlinx.serialization.json.JsonElement) {
        viewModelScope.launch {
            val ok = repo.updateSetting(ns, path, value)
            if (!ok) {
                _chat.value = _chat.value.copy(error = "保存设置失败")
                return@launch
            }
            loadSettings()
            // A model or provider edit changes the catalog, so the picker must see
            // the new list rather than the one read at startup.
            refreshCatalog()
        }
    }

    /** Local view of a settings leaf, used until the host answers. */
    fun settingValue(ns: String, path: List<String>): kotlinx.serialization.json.JsonElement? {
        var current: kotlinx.serialization.json.JsonElement? = _settings.value?.find(ns) ?: return null
        for (segment in path) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    private val _permissions = MutableStateFlow<List<String>>(emptyList())
    val permissions: StateFlow<List<String>> = _permissions.asStateFlow()

    private fun detach() {
        activeStream?.cancel()
        activeStream = null
    }

    override fun onCleared() {
        super.onCleared()
        detach()
        workspaceStream?.cancel()
    }

    companion object {
        /** Factory bound to the application's paired-host singletons. */
        fun factory(app: DshApplication) = ViewModelFactories.chat(app)
    }
}
