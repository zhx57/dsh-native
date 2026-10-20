package dev.dsh.nativeapp.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.nativeapp.DshApplication
import dev.dsh.nativeapp.data.ChatUiState
import dev.dsh.nativeapp.data.ContextBreakdown
import dev.dsh.nativeapp.data.SessionStats
import dev.dsh.nativeapp.data.DshStream
import dev.dsh.nativeapp.data.ModelCatalog
import dev.dsh.nativeapp.data.SessionRow
import dev.dsh.nativeapp.data.TimelineBuilder
import dev.dsh.nativeapp.data.WorkspaceGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private var activeStream: DshStream? = null
    private var workspaceStream: DshStream? = null
    private var workspaceIndex: Map<String, WorkspaceSummary> = emptyMap()

    init {
        refreshIndex()
        refreshCatalog()
        followWorkspaces()
        loadPermissionOptions()
    }

    // -------------------------------------------------------------- index

    fun refreshIndex() {
        viewModelScope.launch {
            runCatching { repo.listSessions() }
                .onSuccess { summaries ->
                    _sessions.value = summaries
                        .filter { it.parentSessionId == null }
                        .map { summary ->
                            SessionRow(
                                sessionId = summary.sessionId,
                                title = summary.title,
                                subtitle = summary.cwd?.substringAfterLast('\\')?.substringAfterLast('/') ?: "",
                                running = summary.running,
                                updatedAt = summary.updatedAt,
                            )
                        }
                        .sortedByDescending { it.updatedAt }
                    regroup()
                    openMostRecentIfIdle()
                }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(
                        connecting = false,
                        error = describe(error, "无法读取会话列表"),
                    )
                }
        }
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
     * Land on the most recent session after a cold start, matching the web app
     * restoring the last conversation instead of an empty pane.
     */
    private fun openMostRecentIfIdle() {
        if (_chat.value.sessionId != null) return
        val first = _sessions.value.firstOrNull() ?: return
        openSession(first.sessionId)
    }

    private fun followWorkspaces() {
        workspaceStream?.cancel()
        workspaceStream = streams.followWorkspaces(
            onBaseline = { baseline ->
                workspaceIndex = baseline.items.associateBy { it.workspaceId }
                viewModelScope.launch { regroup() }
            },
        )
    }

    private suspend fun regroup() {
        val rows = _sessions.value.associateBy { it.sessionId }
        // Recently used sessions first, both inside a workspace and across
        // workspaces, so the list opens on what was actually last touched.
        val groups = workspaceIndex.values.map { workspace ->
            WorkspaceGroup(
                workspaceId = workspace.workspaceId,
                title = workspace.displayName,
                sessions = workspace.sessionIds
                    .mapNotNull { rows[it] }
                    .sortedByDescending { it.updatedAt },
            )
        }.filter { it.sessions.isNotEmpty() }
            .sortedByDescending { group -> group.sessions.maxOfOrNull { it.updatedAt } ?: 0L }
        _workspaces.value = groups
    }

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

        // Re-entering the session that is already loaded keeps its timeline until
        // the fresh snapshot arrives, so the screen never flashes empty.
        val keepTimeline = loadedSessionId == sessionId

        detach()

        activeBuilder = TimelineBuilder()
        _chat.value = ChatUiState(
            sessionId = sessionId,
            connecting = true,
            title = if (keepTimeline) _chat.value.title else "",
            timeline = if (keepTimeline) _chat.value.timeline else emptyList(),
        )
        loadCommands()
        val title = _sessions.value.firstOrNull { it.sessionId == sessionId }?.title.orEmpty()

        activeStream = streams.followSession(
            sessionId = sessionId,
            onItem = { value ->
                activeBuilder.applyFollowValue(value)
                loadedSessionId = sessionId
                val running = detectRunning(value)
                val usage = detectUsage(value)
                val projections = detectProjections(value)
                val approval = detectApproval(value)
                val selection = detectModelSelection(value)
                val extras = detectExtras(value)
                viewModelScope.launch {
                    _chat.value = _chat.value.copy(
                        timeline = activeBuilder.snapshot(),
                        title = title,
                        connecting = false,
                        running = running ?: _chat.value.running,
                        tokenUsage = usage ?: _chat.value.tokenUsage,
                        todos = projections?.first ?: _chat.value.todos,
                        permissionPreset = projections?.second ?: _chat.value.permissionPreset,
                        pendingApproval = approval ?: _chat.value.pendingApproval,
                        modelProvider = selection?.first ?: _chat.value.modelProvider,
                        modelName = selection?.second ?: _chat.value.modelName,
                        reasoningEffort = selection?.third ?: _chat.value.reasoningEffort,
                        contextBreakdown = extras?.breakdown ?: _chat.value.contextBreakdown,
                        sessionStats = extras?.stats ?: _chat.value.sessionStats,
                        contextWindow = extras?.window ?: _chat.value.contextWindow,
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

    /** Read running state from the events the stream carries. */
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

    /** Context split and session counters shown in the info sheet. */
    private data class Extras(
        val breakdown: ContextBreakdown?,
        val stats: SessionStats?,
        val pressure: Long?,
        val window: Int?,
        val questions: List<PendingQuestion>,
    )

    private fun detectExtras(value: JsonObject): Extras? {
        if (dev.dsh.nativeapp.data.JsonText.string(value["type"]) != "snapshot") return null
        val projections = value["projections"] as? JsonObject ?: return null
        val values = projections["values"] as? JsonObject ?: return null

        fun num(container: JsonObject?, key: String): Long? =
            dev.dsh.nativeapp.data.JsonText.long(container?.get(key))

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
            )
        }

        val pressureObj = values["contextPressure"] as? JsonObject
        return Extras(
            breakdown = breakdown,
            stats = stats,
            pressure = num(pressureObj, "pressureTokens") ?: num(pressureObj, "projectedTokens"),
            window = num(pressureObj, "contextWindow")?.toInt(),
            questions = parseQuestions(values["userQuestions"] as? JsonObject),
        )
    }

    /**
     * Read the `userQuestions` projection.
     *
     * `ask_user_question` batches never arrive as stream events; the host keeps
     * them in this projection, which is why the tool previously looked like a
     * no-op in the app.
     */
    private fun parseQuestions(container: JsonObject?): List<PendingQuestion> {
        val active = container?.get("active") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return active.mapNotNull { element ->
            val batch = element as? JsonObject ?: return@mapNotNull null
            val callId = dev.dsh.nativeapp.data.JsonText.string(batch["callId"]) ?: return@mapNotNull null
            val questions = (batch["questions"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { raw ->
                    val q = raw as? JsonObject ?: return@mapNotNull null
                    val id = dev.dsh.nativeapp.data.JsonText.string(q["id"]) ?: return@mapNotNull null
                    QuestionItem(
                        id = id,
                        question = dev.dsh.nativeapp.data.JsonText.string(q["question"]) ?: return@mapNotNull null,
                        detail = dev.dsh.nativeapp.data.JsonText.string(q["detail"]),
                        header = dev.dsh.nativeapp.data.JsonText.string(q["header"]),
                        options = (q["options"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                            .mapNotNull { opt ->
                                val o = opt as? JsonObject ?: return@mapNotNull null
                                val label = dev.dsh.nativeapp.data.JsonText.string(o["label"]) ?: return@mapNotNull null
                                QuestionOption(label, dev.dsh.nativeapp.data.JsonText.string(o["description"]))
                            },
                        multiSelect = dev.dsh.nativeapp.data.JsonText.bool(q["multiSelect"]) ?: false,
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

    fun send(text: String) {
        val sessionId = _chat.value.sessionId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val mode = if (_chat.value.running) "steer" else "queue"
        viewModelScope.launch {
            runCatching { repo.sendPrompt(sessionId, trimmed, mode) }
                .onSuccess {
                    // The host titles a session from its first prompt, so pull the
                    // index again shortly after to surface the generated title.
                    refreshIndexSoon()
                }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = describe(error, "发送失败"))
                }
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
    fun selectModel(provider: String, model: String, reasoningEffort: String?) {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { repo.selectModel(sessionId, provider, model, reasoningEffort) }
                .onSuccess { refreshCatalog() }
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
