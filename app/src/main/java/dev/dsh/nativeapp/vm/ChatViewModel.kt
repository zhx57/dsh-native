package dev.dsh.nativeapp.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.nativeapp.DshApplication
import dev.dsh.nativeapp.data.ChatUiState
import dev.dsh.nativeapp.data.DshStream
import dev.dsh.nativeapp.data.ModelCatalog
import dev.dsh.nativeapp.data.SessionRow
import dev.dsh.nativeapp.data.TimelineBuilder
import dev.dsh.nativeapp.data.WorkspaceGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
                        error = error.message ?: "无法读取会话列表",
                    )
                }
        }
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
        val groups = workspaceIndex.values.map { workspace ->
            WorkspaceGroup(
                workspaceId = workspace.workspaceId,
                title = workspace.displayName,
                sessions = workspace.sessionIds.mapNotNull { rows[it] },
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

    fun openSession(sessionId: String) {
        if (_chat.value.sessionId == sessionId && activeStream != null) return
        detach()

        activeBuilder = TimelineBuilder()
        _chat.value = ChatUiState(sessionId = sessionId, connecting = true)
        val title = _sessions.value.firstOrNull { it.sessionId == sessionId }?.title.orEmpty()

        activeStream = streams.followSession(
            sessionId = sessionId,
            onItem = { value ->
                activeBuilder.applyFollowValue(value)
                val running = detectRunning(value)
                val usage = detectUsage(value)
                viewModelScope.launch {
                    _chat.value = _chat.value.copy(
                        timeline = activeBuilder.snapshot(),
                        title = title,
                        connecting = false,
                        running = running ?: _chat.value.running,
                        tokenUsage = usage ?: _chat.value.tokenUsage,
                    )
                }
            },
            onError = { error ->
                viewModelScope.launch {
                    _chat.value = _chat.value.copy(
                        connecting = false,
                        error = error.message ?: "连接中断",
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
                    _chat.value = _chat.value.copy(error = error.message ?: "无法创建会话")
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
                    _chat.value = _chat.value.copy(error = error.message ?: "发送失败")
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
                    _chat.value = _chat.value.copy(error = error.message ?: "取消失败")
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
                    _chat.value = _chat.value.copy(error = error.message ?: "切换模型失败")
                }
        }
    }

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
