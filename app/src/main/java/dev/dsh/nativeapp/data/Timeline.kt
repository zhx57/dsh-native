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
}

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
