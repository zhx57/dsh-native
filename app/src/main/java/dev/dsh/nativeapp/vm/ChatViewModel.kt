package dev.dsh.nativeapp.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.nativeapp.DshApplication
import dev.dsh.nativeapp.data.ChatUiState
import dev.dsh.nativeapp.data.DshStream
import dev.dsh.nativeapp.data.ModelCatalog
import dev.dsh.nativeapp.data.ModelGroup
import dev.dsh.nativeapp.data.SessionRow
import dev.dsh.nativeapp.data.SessionSummary
import dev.dsh.nativeapp.data.TimelineBuilder
import dev.dsh.nativeapp.data.WorkspaceGroup
import dev.dsh.nativeapp.data.WorkspaceSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            runCatching { withContext(Dispatchers.IO) { repo.listSessions() } }
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
                }
        }
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
            runCatching { withContext(Dispatchers.IO) { repo.modelCatalog() } }
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
                viewModelScope.launch {
                    _chat.value = _chat.value.copy(
                        timeline = activeBuilder.snapshot(),
                        title = title,
                        connecting = false,
                        running = running ?: _chat.value.running,
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
        val type = dev.dsh.nativeapp.data.JsonText.string(value["type"])
        if (type != "event") return null
        val event = value["event"] as? JsonObject ?: return null
        return when (dev.dsh.nativeapp.data.JsonText.string(event["type"])) {
            "turn/start", "step/start" -> true
            "turn/end" -> false
            else -> null
        }
    }

    fun newSession(workspaceId: String? = null) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.createSession(workspaceId) } }
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
            runCatching { withContext(Dispatchers.IO) { repo.sendPrompt(sessionId, trimmed, mode) } }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = error.message ?: "发送失败")
                }
        }
    }

    fun stop() {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.cancelSession(sessionId) } }
                .onFailure { error ->
                    _chat.value = _chat.value.copy(error = error.message ?: "取消失败")
                }
        }
    }

    fun dismissError() {
        _chat.value = _chat.value.copy(error = null)
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
