package dev.dsh.nativeapp.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Everything the chat screen needs in one immutable snapshot. */
data class ChatUiState(
    val sessionId: String? = null,
    val title: String = "",
    val timeline: List<TimelineItem> = emptyList(),
    val running: Boolean = false,
    /**
     * Epoch millis when the current turn started, or null when unknown.
     *
     * Drives the 深度求索中 clock. Null means the app attached mid-turn and no
     * `turn/start` was seen, in which case the status shows no elapsed time rather
     * than a wrong one.
     */
    val runningSince: Long? = null,
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
    /** Window fullness, from `contextPressure`; the figure the meter shows. */
    val contextOccupancy: ContextOccupancy? = null,
    val sessionStats: SessionStats? = null,
    /** `ask_user_question` batches awaiting an answer. */
    val questions: List<PendingQuestion> = emptyList(),
    /** Live question pushed on the `$events` channel. */
    val liveQuestion: LiveQuestion? = null,
    /** Messages accepted while a turn runs, waiting to start the next turn. */
    val queued: List<QueuedMessage> = emptyList(),
    /** True while a send is in flight but not yet admitted to the queue. */
    val sending: Boolean = false,
)

/** One queued message from the session's `inbox.next-turn` projection. */
data class QueuedMessage(
    val id: String,
    val text: String,
    val imageCount: Int = 0,
    val fileNames: List<String> = emptyList(),
) {
    /** Queued messages carrying attachments cannot be edited as plain text. */
    val editable: Boolean get() = imageCount == 0 && fileNames.isEmpty()
}

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
    /** Total decode time; the web client divides tokens by this for tok/s. */
    val decodeMs: Long = 0,
    /** Steps that reported a TTFT; the average divides by this, not by steps. */
    val ttftSteps: Int = 0,
) {
    /** Output speed, matching the web client's `decodeTokens / (decodeMs / 1e3)`. */
    val tokensPerSecond: Double? get() = if (decodeMs > 0) decodeTokens / (decodeMs / 1e3) else null

    /** Mean time to first token across the steps that reported one. */
    val averageTtftMs: Long? get() = if (ttftSteps > 0) ttftMs / ttftSteps else null
}

/**
 * Context occupancy, from `contextPressure`.
 *
 * The web client's `contextOccupancy` reads `projectedTokens ?? pressureTokens`
 * against `contextWindow`. These are NOT the same as [ContextBreakdown]: the
 * breakdown describes what the prompt is made of, while these two describe how full
 * the window is. Conflating them is what made the app's occupancy figure wrong.
 */
data class ContextOccupancy(
    val usedTokens: Long = 0,
    val contextWindow: Long = 0,
) {
    val percent: Int
        get() = if (contextWindow <= 0) 0
        else ((usedTokens.toDouble() / contextWindow) * 100).toInt().coerceIn(0, 100)

    val remaining: Long get() = (contextWindow - usedTokens).coerceAtLeast(0)
}

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
    /** A session with no turn yet; cold start prefers a real conversation. */
    val blank: Boolean = false,
    /** Pinned sessions sort first in the drawer. */
    val pinned: Boolean = false,
)

/** Workspace group in the drawer. */
data class WorkspaceGroup(
    val workspaceId: String,
    val title: String,
    val sessions: List<SessionRow>,
)
