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
    /** Effective model for this session, from the `modelSelection` projection. */
    val modelProvider: String? = null,
    val modelName: String? = null,
    val reasoningEffort: String? = null,
    val contextBreakdown: ContextBreakdown? = null,
    val sessionStats: SessionStats? = null,
    /** `ask_user_question` batches awaiting an answer. */
    val questions: List<PendingQuestion> = emptyList(),
    /** Live question pushed on the `$events` channel. */
    val liveQuestion: LiveQuestion? = null,
)

/** A question delivered as a Host waterfall on `$events`. */
data class LiveQuestion(
    val eventId: String,
    /** Session that asked it, so it is not shown over a different conversation. */
    val agentId: String? = null,
    val questions: List<QuestionPrompt>,
)

/** One question inside an `ask_user_question` batch. */
data class QuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

data class QuestionOption(val label: String, val description: String? = null)

/** One `ask_user_question` call awaiting answers. */
data class PendingQuestion(
    val callId: String,
    val questions: List<QuestionItem>,
)

/** Token split by prompt section, from `contextBreakdown`. */
data class ContextBreakdown(
    val systemTokens: Long = 0,
    val toolsTokens: Long = 0,
    val messageTokens: Long = 0,
) {
    val total: Long get() = systemTokens + toolsTokens + messageTokens
}

/** Turn/step counters and timing, from `sessionStats`. */
data class SessionStats(
    val turns: Int = 0,
    val steps: Int = 0,
    val llmMs: Long = 0,
    val toolMs: Long = 0,
    val decodeTokens: Long = 0,
    val ttftMs: Long = 0,
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
