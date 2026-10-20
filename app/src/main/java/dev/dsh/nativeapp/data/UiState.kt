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
    val todos: List<TodoItem> = emptyList(),
    val permissionPreset: String? = null,
    val commands: List<SlashCommand> = emptyList(),
    val pendingApproval: PendingApproval? = null,
)

/** One todo row from the `todos` projection. */
data class TodoItem(
    val content: String,
    val status: TodoStatus,
) {
    val isDone: Boolean get() = status == TodoStatus.Completed
    val isActive: Boolean get() = status == TodoStatus.InProgress
}

enum class TodoStatus { Pending, InProgress, Completed }

/** One host slash command. */
data class SlashCommand(
    val name: String,
    val description: String,
    val hint: String? = null,
)

/**
 * A tool call waiting on the operator.
 *
 * DSH routes approval through the user-question channel; `callId` is the tool
 * call it belongs to, and the answer value decides the outcome.
 */
data class PendingApproval(
    val callId: String,
    val toolName: String,
    val reason: String,
    val detail: String? = null,
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
