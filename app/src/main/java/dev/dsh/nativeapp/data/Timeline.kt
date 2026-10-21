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

    /**
     * A file the operator attached.
     *
     * The log stores an attachment reference, not the bytes:
     * `{type:"file", attachment:{attachmentId, name, bytes}}`. It used to fall through
     * to [Unknown] and was dropped, so a sent file left no trace in the transcript.
     */
    data class File(val name: String, val attachmentId: String, val bytes: Long) : ContentBlock
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
        /**
         * Ordinal of this prose run within its source message.
         *
         * One `assistant/message` can emit text, call a tool, then emit more text.
         * Each run is its own row but every one shares the message's `seq`, so the
         * ordinal is what keeps their identities distinct.
         */
        val part: Int = 0,
        /**
         * True for a row the live stream invented.
         *
         * The stream numbers a provisional row with the client's local counter, which
         * can coincide with the `seq` of the message that eventually commits the same
         * step. Without this flag the preview and the committed row would share an
         * identity, and the committed row would be rejected as a duplicate.
         */
        val live: Boolean = false,
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
        val commandId: String = "",
        val line: String,
        val result: String? = null,
        val failed: Boolean = false,
    ) : TimelineItem
}

/**
 * Stable identity of a row.
 *
 * Two rows can share an event `seq` — one `assistant/message` yields a prose run
 * and one row per tool call, all anchored on the same event — so the `seq` alone
 * cannot key them. This identity is what deduplicates an overlapping history page
 * and what Compose keys a row by, so a re-fold of the same event updates a row
 * instead of inserting a duplicate next to it.
 */
fun TimelineItem.keyOf(): String = when (this) {
    is TimelineItem.UserMessage -> "user:$seq"
    // A live preview carries a client counter, not a host seq, so it stays distinct
    // from the committed row that will replace it.
    is TimelineItem.AssistantMessage -> if (live) "assistant:live:$seq:$part" else "assistant:$seq:$part"
    is TimelineItem.ToolCallItem -> "tool:$callId"
    is TimelineItem.PendingQuestionItem -> "question:$callId"
    is TimelineItem.TurnDivider -> "divider:$turn"
    is TimelineItem.Notice -> "notice:$seq:$text"
    is TimelineItem.CommandItem -> "command:${commandId.ifBlank { seq.toString() }}"
    is TimelineItem.TurnOutcome -> "outcome:$seq:$kind"
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
                    // Two shapes exist: an inline `data` payload, or an attachment
                    // reference. Both are surfaced so a sent picture is visible.
                    val attachment = obj["attachment"] as? JsonObject
                    val media = JsonText.string(obj["mediaType"])
                        ?: JsonText.string(attachment?.get("mediaType"))
                        ?: "image/png"
                    val name = JsonText.string(obj["name"])
                        ?: JsonText.string(attachment?.get("name"))
                    val data = JsonText.string(obj["data"])
                    if (data == null && attachment == null) null
                    else ContentBlock.Image(media, data.orEmpty(), name)
                }
                "file" -> {
                    val attachment = obj["attachment"] as? JsonObject
                    val name = JsonText.string(attachment?.get("name"))
                        ?: JsonText.string(obj["name"])
                        ?: "附件"
                    val id = JsonText.string(attachment?.get("attachmentId"))
                        ?: JsonText.string(obj["attachmentId"])
                        ?: JsonText.string(obj["receiptId"])
                        ?: ""
                    ContentBlock.File(name, id, JsonText.long(attachment?.get("bytes")) ?: 0L)
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
