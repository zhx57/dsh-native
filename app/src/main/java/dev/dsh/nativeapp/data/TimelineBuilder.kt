package dev.dsh.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Folds DSH session traffic into a renderable timeline.
 *
 * Two sources feed this fold:
 *  - history: `session/page` records (`{type:"event", event:{...}}`)
 *  - live:    `session/follow` items (`event` / `snapshot` / `assistant-stream`)
 *
 * Live assistant text arrives as `assistant-stream` frames whose `chunk` payload
 * is an LLM delta (`text-delta`, `reasoning-delta`, `tool-call-delta`, ...). The
 * committed `assistant/message` event that follows replaces the provisional bubble.
 */
class TimelineBuilder {

    private val items = mutableListOf<TimelineItem>()
    private val toolIndex = mutableMapOf<String, Int>()
    private val commandIndex = mutableMapOf<String, Int>()
    private var localSeq = 0L
    private var lastTime = 0L

    private var streaming: StreamingAttempt? = null

    private data class StreamingAttempt(
        val attemptId: String,
        val turn: Int,
        val step: Int,
        val text: StringBuilder = StringBuilder(),
        val reasoning: StringBuilder = StringBuilder(),
        var indexInList: Int = -1,
        var lastSeq: Long = 0L,
    )

    fun snapshot(): List<TimelineItem> = items.toList()

    fun clear() {
        items.clear()
        toolIndex.clear()
        commandIndex.clear()
        streaming = null
        localSeq = 0L
        lastTime = 0L
    }

    /** Highest sequence number folded so far. */
    fun cursor(): Long = localSeq

    // ------------------------------------------------------------ entry points

    /** Fold one `session/page` record. */
    fun applyRecord(record: JsonObject) {
        when (JsonText.string(record["type"])) {
            "event" -> (record["event"] as? JsonObject)?.let(::applyEvent)
            else -> Unit
        }
    }

    /** Fold one `session/follow` item value. */
    fun applyFollowValue(value: JsonObject) {
        when (JsonText.string(value["type"])) {
            "event" -> (value["event"] as? JsonObject)?.let(::applyEvent)
            "snapshot" -> {
                (value["records"] as? JsonArray)?.forEach { record ->
                    (record as? JsonObject)?.let(::applyRecord)
                }
                (value["assistantStream"] as? JsonArray)?.forEach { frame ->
                    (frame as? JsonObject)?.let(::applyStreamFrame)
                }
            }
            "assistant-stream" -> (value["frame"] as? JsonObject)?.let(::applyStreamFrame)
            else -> Unit
        }
    }

    // ----------------------------------------------------------------- events

    fun applyEvent(event: JsonObject) {
        val type = JsonText.string(event["type"]) ?: return
        val seq = JsonText.long(event["seq"]) ?: localSeq++
        val time = JsonText.long(event["time"]) ?: lastTime
        lastTime = time
        if (seq >= localSeq) localSeq = seq + 1
        val data = event["data"] as? JsonObject ?: JsonObject(emptyMap())

        when (type) {
            "user/message" -> applyUserMessage(seq, time, data)
            "assistant/message" -> applyAssistantMessage(seq, time, data)
            "tool/call" -> applyToolCall(seq, time, data)
            "tool/result" -> applyToolResult(seq, time, data)
            "turn/start" -> {
                finalizeStreaming()
                val turn = JsonText.integer(data["turn"]) ?: 0
                if (turn > 1) items.add(TimelineItem.TurnDivider(seq, time, turn))
            }
            "turn/end" -> {
                finalizeStreaming()
                val reason = data["reason"] as? JsonObject
                val kind = JsonText.string(reason?.get("kind"))
                when (kind) {
                    "error" -> {
                        val failure = reason?.get("error") as? JsonObject
                        items.add(
                            TimelineItem.TurnOutcome(
                                seq = seq,
                                time = time,
                                kind = TurnOutcomeKind.Error,
                                message = JsonText.string(failure?.get("message")) ?: "请求失败",
                                code = JsonText.string(failure?.get("code")),
                                status = JsonText.integer(failure?.get("status")),
                            ),
                        )
                    }
                    "aborted" -> items.add(
                        TimelineItem.TurnOutcome(seq, time, TurnOutcomeKind.Aborted, "已停止"),
                    )
                    "max-tokens" -> items.add(
                        TimelineItem.TurnOutcome(
                            seq, time, TurnOutcomeKind.MaxTokens, "达到输出长度上限",
                        ),
                    )
                    "interrupted" -> items.add(
                        TimelineItem.TurnOutcome(
                            seq, time, TurnOutcomeKind.Interrupted, "上一轮被中断",
                        ),
                    )
                    "blocked" -> items.add(
                        TimelineItem.TurnOutcome(seq, time, TurnOutcomeKind.Blocked, "被安全策略拦截"),
                    )
                    else -> Unit
                }
            }
            "step/end" -> finalizeStreaming()
            "compact/summary", "compaction/summary" -> {
                val summary = JsonText.string(data["summary"]) ?: return
                if (summary.isNotBlank()) {
                    items.add(TimelineItem.Notice(seq, time, "上下文已压缩"))
                }
            }
            "command/run" -> {
                val name = JsonText.string(data["name"]) ?: return
                val args = JsonText.string(data["args"]).orEmpty()
                val commandId = JsonText.string(data["commandId"]).orEmpty()
                commandIndex[commandId] = items.size
                items.add(
                    TimelineItem.CommandItem(
                        seq = seq,
                        time = time,
                        line = if (args.isBlank()) "/$name" else "/$name $args",
                    ),
                )
            }
            "command/done" -> {
                val commandId = JsonText.string(data["commandId"]).orEmpty()
                val text = JsonText.string(data["text"]).orEmpty()
                val kind = JsonText.string(data["kind"]).orEmpty()
                val index = commandIndex[commandId]
                val existing = index?.let { items.getOrNull(it) as? TimelineItem.CommandItem }
                if (existing != null && index != null) {
                    items[index] = existing.copy(result = text, failed = kind != "success")
                } else if (text.isNotBlank()) {
                    items.add(TimelineItem.Notice(seq, time, text))
                }
            }
            "agent/error", "session/error" -> {
                val message = JsonText.string(data["message"]) ?: "运行出错"
                items.add(TimelineItem.Notice(seq, time, message))
            }
            else -> Unit
        }
    }

    private fun applyUserMessage(seq: Long, time: Long, data: JsonObject) {
        // Only operator turns are conversation; injected reminders and runtime
        // context snapshots are transport detail.
        val source = data["source"] as? JsonObject
        val kind = JsonText.string(source?.get("kind"))
        if (kind != null && kind != "user") return
        val blocks = EventParser.blocks(data["content"])
        if (blocks.isEmpty()) return
        val text = blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        if (text.startsWith("<system-reminder>") ||
            text.startsWith("Current runtime context") ||
            text.startsWith("<available_skills>")
        ) {
            return
        }
        finalizeStreaming()
        items.add(TimelineItem.UserMessage(seq, time, blocks))
    }

    private fun applyAssistantMessage(seq: Long, time: Long, data: JsonObject) {
        val message = data["message"] as? JsonObject ?: return
        val blocks = EventParser.blocks(message["content"])
        if (blocks.isEmpty()) return
        finalizeStreaming()

        // A committed assistant message mixes prose and tool calls. Emit the prose
        // once and let each tool-call block become its own row, otherwise the same
        // sentences appear both as a bubble and again beneath the tool card.
        val prose = blocks.filter { it !is ContentBlock.ToolCall }
        if (prose.isNotEmpty()) {
            items.add(TimelineItem.AssistantMessage(seq, time, prose))
        }
        blocks.filterIsInstance<ContentBlock.ToolCall>().forEach { call ->
            if (toolIndex.containsKey(call.id)) return@forEach
            toolIndex[call.id] = items.size
            items.add(
                TimelineItem.ToolCallItem(
                    seq = seq,
                    time = time,
                    callId = call.id,
                    name = call.name,
                    arguments = call.arguments,
                ),
            )
        }
    }

    private fun applyToolCall(seq: Long, time: Long, data: JsonObject) {
        val callId = JsonText.string(data["callId"]) ?: return
        val name = JsonText.string(data["name"]) ?: "tool"
        val args = JsonText.string(data["arguments"]) ?: ""

        // `ask_user_question` blocks until answered and the host's projection for it
        // is empty on this deployment, so the pending question is recovered here.
        if (name == "ask_user_question") {
            val prompts = parseQuestionPrompts(args)
            if (prompts.isNotEmpty()) {
                if (!toolIndex.containsKey(callId)) {
                    toolIndex[callId] = items.size
                    items.add(TimelineItem.PendingQuestionItem(seq, time, callId, prompts))
                }
                return
            }
        }

        // The same call arrives twice: once inside the assistant message's content
        // and once as a standalone tool/call event. Keep whichever lands first and
        // only backfill arguments the other one lacked.
        val existing = toolIndex[callId]?.let { items.getOrNull(it) as? TimelineItem.ToolCallItem }
        if (existing != null) {
            if (existing.arguments.isBlank() && args.isNotBlank()) {
                val index = toolIndex.getValue(callId)
                items[index] = existing.copy(arguments = args)
            }
            return
        }
        toolIndex[callId] = items.size
        items.add(TimelineItem.ToolCallItem(seq, time, callId, name, args))
    }

    /** Decode the `questions` array from an `ask_user_question` argument string. */
    private fun parseQuestionPrompts(arguments: String): List<QuestionPrompt> {
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(arguments) as? JsonObject
        }.getOrNull() ?: return emptyList()
        val array = root["questions"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = JsonText.string(obj["id"]) ?: return@mapNotNull null
            val text = JsonText.string(obj["question"]) ?: return@mapNotNull null
            QuestionPrompt(
                id = id,
                question = text,
                header = JsonText.string(obj["header"]),
                options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { raw ->
                    val opt = raw as? JsonObject ?: return@mapNotNull null
                    val label = JsonText.string(opt["label"]) ?: return@mapNotNull null
                    QuestionChoice(label, JsonText.string(opt["description"]))
                },
                multiSelect = JsonText.bool(obj["multiSelect"])
                    ?: JsonText.bool(obj["multi_select"])
                    ?: false,
            )
        }
    }

    private fun applyToolResult(seq: Long, time: Long, data: JsonObject) {
        val message = data["message"] as? JsonObject ?: return
        val callId = JsonText.string(message["toolCallId"]) ?: return

        // A question that has been answered is replaced by its outcome row.
        val pendingIndex = toolIndex[callId]
        if (pendingIndex != null && items.getOrNull(pendingIndex) is TimelineItem.PendingQuestionItem) {
            items.removeAt(pendingIndex)
            toolIndex.remove(callId)
            toolIndex.replaceAll { _, value -> if (value > pendingIndex) value - 1 else value }
        }

        val text = EventParser.textOf(message["content"])
        val isError = JsonText.bool(message["isError"]) ?: false
        val index = toolIndex[callId]
        val existing = index?.let { items.getOrNull(it) as? TimelineItem.ToolCallItem }
        if (existing != null && index != null) {
            items[index] = existing.copy(result = text, isError = isError, running = false)
            return
        }
        toolIndex[callId] = items.size
        items.add(
            TimelineItem.ToolCallItem(
                seq = seq,
                time = time,
                callId = callId,
                name = "tool",
                arguments = "",
                result = text,
                isError = isError,
                running = false,
            ),
        )
    }

    // --------------------------------------------------------- live streaming

    /** Fold one `assistant-stream` frame (`{type:"start"|"chunk"|"end", ...}`). */
    fun applyStreamFrame(frame: JsonObject) {
        val frameType = JsonText.string(frame["type"]) ?: return
        val attemptId = JsonText.string(frame["attemptId"]) ?: "attempt"

        when (frameType) {
            "start" -> {
                finalizeStreaming()
                val attempt = StreamingAttempt(
                    attemptId = attemptId,
                    turn = JsonText.integer(frame["turn"]) ?: 0,
                    step = JsonText.integer(frame["step"]) ?: 0,
                    lastSeq = localSeq,
                )
                attempt.indexInList = items.size
                items.add(TimelineItem.AssistantMessage(localSeq, lastTime, emptyList(), streaming = true))
                streaming = attempt
            }

            "chunk" -> {
                val attempt = streaming?.takeIf { it.attemptId == attemptId } ?: return
                val time = JsonText.long(frame["time"]) ?: lastTime
                val chunk = frame["chunk"] as? JsonObject ?: return
                when (JsonText.string(chunk["type"])) {
                    "text-delta" -> JsonText.string(chunk["text"])?.let { attempt.text.append(it) }
                    "reasoning-delta" -> JsonText.string(chunk["text"])?.let { attempt.reasoning.append(it) }
                    "tool-call-delta" -> {
                        val name = JsonText.string(chunk["name"])
                        val id = JsonText.string(chunk["id"])
                        if (name != null && id != null) {
                            // Surface the call as soon as its identity is known so the
                            // user sees activity before arguments finish streaming.
                            if (!toolIndex.containsKey(id)) {
                                toolIndex[id] = items.size
                                items.add(
                                    TimelineItem.ToolCallItem(
                                        seq = localSeq,
                                        time = time,
                                        callId = id,
                                        name = name,
                                        arguments = "",
                                        running = true,
                                    ),
                                )
                            }
                        }
                    }
                    "block-end" -> {
                        val block = chunk["block"] as? JsonObject
                        if (block != null) {
                            val id = JsonText.string(block["id"])
                            val name = JsonText.string(block["name"])
                            if (id != null && name != null && !toolIndex.containsKey(id)) {
                                toolIndex[id] = items.size
                                items.add(
                                    TimelineItem.ToolCallItem(
                                        seq = localSeq,
                                        time = time,
                                        callId = id,
                                        name = name,
                                        arguments = JsonText.string(block["arguments"]) ?: "",
                                        running = true,
                                    ),
                                )
                            }
                        }
                    }
                }
                attempt.lastSeq = time
                refreshStreaming(attempt, time)
            }

            "end" -> finalizeStreaming()
        }
    }

    private fun refreshStreaming(attempt: StreamingAttempt, time: Long) {
        val index = attempt.indexInList
        if (index < 0 || index >= items.size) return
        val blocks = buildList {
            if (attempt.reasoning.isNotEmpty()) add(ContentBlock.Reasoning(attempt.reasoning.toString()))
            if (attempt.text.isNotEmpty()) add(ContentBlock.Text(attempt.text.toString()))
        }
        items[index] = TimelineItem.AssistantMessage(
            seq = attempt.lastSeq,
            time = time,
            blocks = blocks,
            streaming = true,
        )
    }

    /**
     * Retire the provisional streaming bubble.
     *
     * The committed `assistant/message` always supersedes the attempt that
     * produced it, so the placeholder is removed whether or not it collected
     * text — leaving it behind is what showed a stale "正在输出" row above the
     * conversation and duplicated already-printed prose.
     */
    private fun finalizeStreaming() {
        val attempt = streaming ?: return
        streaming = null
        val index = attempt.indexInList
        if (index < 0 || index >= items.size) return
        val current = items.getOrNull(index) as? TimelineItem.AssistantMessage ?: return
        items.removeAt(index)
        // Tool rows created during the attempt keep their order; only rows after
        // the removed placeholder shift down one slot.
        toolIndex.replaceAll { _, value -> if (value > index) value - 1 else value }
    }
}
