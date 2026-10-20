package dev.dsh.nativeapp.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Everything the chat screen needs in one immutable snapshot. */
data class ChatUiState(
    val sessionId: String? = null,
    val title: String = "",
    val timeline: List<TimelineItem> = emptyList(),
    val running: Boolean = false,
    val connecting: Boolean = true,
    val error: String? = null,
    val tokenUsage: TokenUsage? = null,
    val contextWindow: Int? = null,
    val pendingPrompt: String? = null,
)

/** One row in the session drawer. */
data class SessionRow(
    val sessionId: String,
    val title: String,
    val subtitle: String,
    val running: Boolean,
    val updatedAt: Long,
)

/** Workspace group in the drawer. */
data class WorkspaceGroup(
    val workspaceId: String,
    val title: String,
    val sessions: List<SessionRow>,
)
