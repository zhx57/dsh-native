package dev.dsh.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One content block inside a message. */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Reasoning(val text: String) : ContentBlock
    data class Image(val mediaType: String, val data: String, val name: String?) : ContentBlock
    data class ToolCall(val id: String, val name: String, val arguments: String) : ContentBlock
    data class Unknown(val type: String, val raw: JsonElement) : ContentBlock
}

/** Who produced a rendered message. */
enum class MessageRole { User, Assistant, Tool, System }

/** One rendered item in the conversation timeline. */
sealed interface TimelineItem {
    val seq: Long
    val time: Long

    data class UserMessage(
        override val seq: Long,
        override val time: Long,
        val blocks: List<ContentBlock>,
    ) : TimelineItem

    data class AssistantMessage(
        override val seq: Long,
        override val time: Long,
        val blocks: List<ContentBlock>,
        val streaming: Boolean = false,
        val error: String? = null,
    ) : TimelineItem

    data class ToolCallItem(
        override val seq: Long,
        override val time: Long,
        val callId: String,
        val name: String,
        val arguments: String,
        val result: String? = null,
        val isError: Boolean = false,
        val running: Boolean = true,
    ) : TimelineItem

    data class Notice(
        override val seq: Long,
        override val time: Long,
        val text: String,
    ) : TimelineItem

    data class TurnDivider(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
    ) : TimelineItem

    /**
     * An `ask_user_question` call blocked waiting for an answer.
     *
     * The host only publishes an `active` batch in the `userQuestions` projection
     * when the tool declares a timed `timeout` parameter; this deployment's schema
     * has none, so that projection stays empty even while the call is pending. The
     * `tool/call` event carries the same payload, so the question is recovered from
     * it and cleared once its `tool/result` arrives.
     */
    data class PendingQuestionItem(
        override val seq: Long,
        override val time: Long,
        val callId: String,
        val questions: List<QuestionPrompt>,
    ) : TimelineItem

    /**
     * A host slash command the operator ran.
     *
     * `/compact` and friends never produce a `user/message`; the host logs
     * `command/run` and `command/done` instead, so without this the command looked
     * like it had been swallowed.
     */
    /**
     * Why a turn stopped.
     *
     * `turn/end` carries a merge-extensible reason; `error` carries the provider
     * failure verbatim (message, machine code, HTTP status). Without this the app
     * looked identical whether a turn completed or the provider rejected the call.
     */
    data class TurnOutcome(
        override val seq: Long,
        override val time: Long,
        val kind: TurnOutcomeKind,
        val message: String? = null,
        val code: String? = null,
        val status: Int? = null,
    ) : TimelineItem

    data class CommandItem(
        override val seq: Long,
        override val time: Long,
        val line: String,
        val result: String? = null,
        val failed: Boolean = false,
    ) : TimelineItem
}

/** Normalized `turn/end` reason. */
enum class TurnOutcomeKind {
    /** The provider or transport failed; [TurnOutcome.message] explains it. */
    Error,

    /** The operator cancelled. */
    Aborted,

    /** Hit the output-token ceiling. */
    MaxTokens,

    /** The host closed an orphaned turn after a crash. */
    Interrupted,

    /** A guard rejected the step. */
    Blocked,
}

/** One question inside a pending `ask_user_question` call. */
data class QuestionPrompt(
    val id: String,
    val question: String,
    val header: String? = null,
    val options: List<QuestionChoice> = emptyList(),
    val multiSelect: Boolean = false,
)

data class QuestionChoice(val label: String, val description: String? = null)

/** Parses raw DSH session events into timeline items. */
object EventParser {

    fun blocks(content: JsonElement?): List<ContentBlock> {
        val array = content as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            when (val type = JsonText.string(obj["type"])) {
                "text" -> JsonText.string(obj["text"])?.let { ContentBlock.Text(it) }
                "reasoning" -> JsonText.string(obj["text"])?.let { ContentBlock.Reasoning(it) }
                "image" -> {
                    val media = JsonText.string(obj["mediaType"]) ?: "image/png"
                    val data = JsonText.string(obj["data"])
                    if (data == null) null else ContentBlock.Image(media, data, JsonText.string(obj["name"]))
                }
                "tool-call" -> {
                    val id = JsonText.string(obj["id"]) ?: ""
                    val name = JsonText.string(obj["name"]) ?: "tool"
                    ContentBlock.ToolCall(id, name, JsonText.string(obj["arguments"]) ?: "")
                }
                null -> null
                else -> ContentBlock.Unknown(type, element)
            }
        }
    }

    /** Extract the plain text of a message payload. */
    fun textOf(content: JsonElement?): String =
        blocks(content).filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
}
