package dev.dsh.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Folds DSH session traffic into a renderable timeline.
 *
 * The web client does not render "assistant message rows plus tool rows": it
 * keeps one **Assistant node per step** whose blocks keep their source order,
 * and one **Tool node per call id**. Ordering between them comes from a
 * per-node anchor sequence, and a turn's answer stays attached to its step.
 *
 * This fold reproduces that model instead of inventing a second one, because the
 * previous "split prose from tool calls into separate rows" approach lost the
 * interleaving: a step that emitted `text, tool-call, text` collapsed to one
 * prose bubble and one tool card in the wrong order, and a tool call whose
 * `assistant/message` block arrived after an earlier registration was dropped
 * entirely (`if (toolIndex.containsKey) return`).
 *
 * Three sources feed the fold:
 *  - history: `session/page` records (`{type:"event", event:{...}}`)
 *  - live:    `session/follow` items (`event` / `snapshot` / `assistant-stream`)
 *  - stream:  LLM deltas for the step currently being generated
 *
 * Every row carries an event `seq` that fixes its position. Where several rows
 * share one `seq` — one `assistant/message` emits a prose run plus one row per
 * tool call — their insertion order is the content order the host logged, which is
 * what [TimelineItem.keyOf] keys and what Compose renders.
 */
class TimelineBuilder {

    private val items = mutableListOf<TimelineItem>()
    private val toolIndex = mutableMapOf<String, Int>()
    private val commandIndex = mutableMapOf<String, Int>()

    /**
     * Identity of every row already in [items].
     *
     * A `session/follow` reconnect replays its snapshot from the top, and a history
     * page overlaps the live window, so the same event legitimately reaches the
     * fold twice. Without this set the second pass appended a duplicate row: that
     * is what showed one answer twice and put a tool card both above and below the
     * prose that had triggered it.
     */
    private val rowKeys = HashSet<String>()

    /**
     * Rows removed so they could be re-emitted in the right position.
     *
     * A committed `assistant/message` re-lays-out the calls of its own step; the
     * row it replaces keeps whatever the stream already learned (streamed
     * arguments, an earlier result), so detaching never loses payload.
     */
    private val detachedCalls = HashMap<String, TimelineItem>()

    /**
     * Rows the current stream froze because a tool call interrupted their prose run.
     *
     * They exist so the transcript reads prose → tool → prose while the step is
     * live. The committed `assistant/message` reprints the same text, so these are
     * removed when that message lands; keeping both printed every split run twice.
     *
     * The set only ever holds rows of the step being streamed, because it is cleared
     * whenever a new step opens. A row frozen by a step that never committed (the
     * operator stopped mid-step) therefore stays on screen as the turn's prefix.
     */
    private val frozenRows = HashSet<String>()

    /** Step being generated, keyed `turn:step`, mirroring the web assistant node. */
    private var streaming: StreamingStep? = null
    private var localSeq = 0L
    private var lastTime = 0L

    private data class StreamingStep(
        val partition: String,
        val turn: Int,
        val step: Int,
        val attemptIds: MutableSet<String> = mutableSetOf(),
        val blocks: MutableList<ContentBlock> = mutableListOf(),
        var indexInList: Int = -1,
        var lastSeq: Long = 0L,
        var lastTime: Long = 0L,
        /** Ordinal of the current prose run inside this step. */
        var part: Int = 0,
    )

    fun snapshot(): List<TimelineItem> = items.toList()

    /** Append a row unless an identical one is already present. */
    private fun append(item: TimelineItem) {
        appendReturningIndex(item)
    }

    /**
     * Append a row unless an identical one is already present.
     *
     * @returns the index the row occupies, which is the existing one when the row
     *   was a duplicate. Callers that record an index must use this rather than
     *   `items.size`, because a skipped duplicate leaves the size unchanged and the
     *   recorded index would then point past the end of the list.
     */
    private fun appendReturningIndex(item: TimelineItem): Int {
        val key = item.keyOf()
        if (!rowKeys.add(key)) {
            val existing = items.indexOfFirst { it.keyOf() == key }
            if (existing >= 0) return existing
            // The identity is claimed by a row that is no longer in the list (it was
            // detached for re-emission), so this row legitimately takes it over.
        }
        items.add(item)
        return items.lastIndex
    }

    /**
     * Fold an older page *in front of* what is already loaded.
     *
     * Pagination walks backwards, so a page is older than everything in memory,
     * but the page and the live window overlap: `session/page` returns the newest
     * `maxMessages` events at or before `beforeSeq`, and the follow stream already
     * replayed the tail. Re-folding an overlapping event duplicated rows — most
     * visibly a tool call whose `assistant/message` block, `tool/call`, and
     * `tool/result` were partly in each window. Rows are therefore deduplicated by
     * [TimelineItem.keyOf], and the earlier copy wins because it is prepended.
     */
    fun prependPage(records: List<JsonObject>) {
        if (records.isEmpty()) return
        val older = TimelineBuilder()
        records.forEach(older::applyRecord)
        val head = older.snapshot()
        if (head.isEmpty()) return

        val fresh = head.filter { it.keyOf() !in rowKeys }
        if (fresh.isEmpty()) return
        items.addAll(0, fresh)
        fresh.forEach { rowKeys.add(it.keyOf()) }
        // Every previously recorded index shifted by the size of the prefix.
        val shift = fresh.size
        toolIndex.replaceAll { _, value -> value + shift }
        commandIndex.replaceAll { _, value -> value + shift }
        streaming?.let { it.indexInList += shift }
    }

    /** Oldest event sequence folded so far; 0 when nothing is loaded. */
    fun oldestSeq(): Long = items.minOfOrNull { it.seq } ?: 0L

    fun clear() {
        items.clear()
        toolIndex.clear()
        commandIndex.clear()
        rowKeys.clear()
        detachedCalls.clear()
        frozenRows.clear()
        streaming = null
        localSeq = 0L
        lastTime = 0L
    }

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
            "assistant/attempt" -> applyAssistantAttempt(seq, time, data)
            "tool/call" -> applyToolCall(seq, time, data)
            "tool/result" -> applyToolResult(seq, time, data)
            "turn/start" -> {
                val turn = JsonText.integer(data["turn"]) ?: 0
                if (turn > 1) append(TimelineItem.TurnDivider(seq, time, turn))
            }
            "step/end" -> closeStreaming()
            "turn/end" -> {
                closeStreaming()
                applyTurnEnd(seq, time, data)
            }
            "compact/summary", "compaction/summary" -> {
                val summary = summaryText(data["summary"]) ?: return
                if (summary.isNotBlank()) {
                    append(TimelineItem.Notice(seq, time, "上下文已压缩"))
                }
            }
            "command/run" -> {
                val name = JsonText.string(data["name"]) ?: return
                val args = JsonText.string(data["args"]).orEmpty()
                val commandId = JsonText.string(data["commandId"]).orEmpty()
                commandIndex[commandId] = items.size
                append(
                    TimelineItem.CommandItem(
                        seq = seq,
                        time = time,
                        commandId = commandId,
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
                    append(TimelineItem.Notice(seq, time, text))
                }
            }
            "agent/error", "session/error" -> {
                val message = JsonText.string(data["message"]) ?: "运行出错"
                append(TimelineItem.Notice(seq, time, message))
            }
            else -> Unit
        }
    }

    private fun applyTurnEnd(seq: Long, time: Long, data: JsonObject) {
        val reason = data["reason"] as? JsonObject
        val kind = JsonText.string(reason?.get("kind"))
        when (kind) {
            "error" -> {
                val failure = reason?.get("error") as? JsonObject
                append(
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
            "aborted" -> append(
                TimelineItem.TurnOutcome(seq, time, TurnOutcomeKind.Aborted, "已停止"),
            )
            "max-tokens" -> append(
                TimelineItem.TurnOutcome(
                    seq, time, TurnOutcomeKind.MaxTokens, "达到输出长度上限",
                ),
            )
            "interrupted" -> append(
                TimelineItem.TurnOutcome(
                    seq, time, TurnOutcomeKind.Interrupted, "上一轮被中断",
                ),
            )
            "blocked" -> append(
                TimelineItem.TurnOutcome(seq, time, TurnOutcomeKind.Blocked, "被安全策略拦截"),
            )
            else -> Unit
        }
    }

    private fun applyUserMessage(seq: Long, time: Long, data: JsonObject) {
        // Only operator turns are conversation; injected reminders and runtime
        // context snapshots are transport detail. The web client draws them as
        // collapsed `context` rows; a watch-sized screen has no room for those, so
        // they stay out of the transcript entirely.
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
        append(TimelineItem.UserMessage(seq, time, blocks))
    }

    /**
     * Fold a committed assistant message.
     *
     * This is the *answer* half of a step. The web assistant node owns the whole
     * step and keeps its blocks in source order, so this emits in content order too:
     * consecutive non-tool blocks become one prose row, and each `tool-call` block
     * becomes its own row at the point it appeared. A step that answers, calls a
     * tool, and answers again therefore renders as prose → tool → prose, not as one
     * bubble with every tool card堆积 above or below it.
     *
     * An already-registered call is *moved*, never skipped. The stream registers a
     * call as soon as its name arrives, so the committed message usually arrives
     * second; skipping it there is exactly what used to drop tool rows.
     */
    private fun applyAssistantMessage(seq: Long, time: Long, data: JsonObject) {
        val message = data["message"] as? JsonObject ?: return
        val blocks = EventParser.blocks(message["content"])
        if (blocks.isEmpty()) return
        // The committed message *is* this step, so its provisional bubble goes even
        // when it already holds text: the message below reprints the same words.
        discardStreaming()

        // Rows the live stream created for this same message must move, not stay
        // put. The stream sees a call's name before any prose is committed, so its
        // row was appended first; leaving it there put the tool card *above* text
        // the model emitted before the call, which is the reported wrong order.
        // Detaching and re-emitting in content order fixes that without losing the
        // arguments or result the stream already collected.
        blocks.filterIsInstance<ContentBlock.ToolCall>().forEach { detachCall(it.id) }
        // Frozen live rows for this step are superseded by the rows emitted below.
        dropFrozenRows()

        var run = mutableListOf<ContentBlock>()
        var part = 0
        fun flushRun() {
            if (run.isEmpty()) return
            append(TimelineItem.AssistantMessage(seq, time, run.toList(), part = part))
            part += 1
            run = mutableListOf()
        }
        blocks.forEach { block ->
            when (block) {
                is ContentBlock.ToolCall -> {
                    flushRun()
                    registerCall(block.id, block.name, block.arguments, seq, time)
                }
                is ContentBlock.Unknown -> Unit
                else -> run.add(block)
            }
        }
        flushRun()
    }

    /**
     * Drop the rows the live stream froze at tool boundaries.
     *
     * Called when a committed `assistant/message` lands, because it reprints that
     * text in the right order on its own. Any frozen row whose step never committed
     * (aborted mid-step) is left in place by the caller not reaching here.
     */
    private fun dropFrozenRows() {
        if (frozenRows.isEmpty()) return
        val keys = frozenRows.toSet()
        frozenRows.clear()
        var index = items.size - 1
        while (index >= 0) {
            val row = items[index]
            if (row is TimelineItem.AssistantMessage && row.keyOf() in keys) {
                items.removeAt(index)
                rowKeys.remove(row.keyOf())
                shiftIndicesAfter(index)
            }
            index -= 1
        }
    }

    /**
     * Remove a call's row so it can be re-emitted at its correct position.
     *
     * The row's payload survives in [detachedCalls], so only the position changes;
     * streamed arguments and an already-received result are kept. A replayed message
     * finds the call already in the right place and this is a no-op.
     */
    private fun detachCall(callId: String) {
        val index = toolIndex[callId] ?: return
        val row = items.getOrNull(index) ?: return
        if (row !is TimelineItem.ToolCallItem && row !is TimelineItem.PendingQuestionItem) return
        toolIndex.remove(callId)
        items.removeAt(index)
        rowKeys.remove(row.keyOf())
        shiftIndicesAfter(index)
        // Re-registering must still know what the stream already learned.
        detachedCalls[callId] = row
    }

    /**
     * Fold a failed attempt that produced no `assistant/message`.
     *
     * The host logs `assistant/attempt` with the attempt's own stream when the
     * provider failed mid-step. Without it a failed step looked like a silently
     * empty step, because no message and no tool rows were ever emitted.
     */
    private fun applyAssistantAttempt(seq: Long, time: Long, data: JsonObject) {
        val stream = data["stream"] as? JsonArray ?: return
        var failure: String? = null
        for (entry in stream) {
            val frame = entry as? JsonObject ?: continue
            val chunk = frame["chunk"] as? JsonObject ?: continue
            if (JsonText.string(chunk["type"]) != "finish") continue
            val reason = chunk["reason"] as? JsonObject ?: continue
            if (JsonText.string(reason["kind"]) != "error") continue
            val detail = reason["failure"] as? JsonObject
            failure = JsonText.string(detail?.get("message"))
                ?: JsonText.string(detail?.get("code"))
                ?: "provider failure"
        }
        if (failure == null) return
        // The attempt's prefix is all that survived, so it stays on screen; only an
        // empty bubble is dropped.
        closeStreaming()
        append(
            TimelineItem.TurnOutcome(
                seq = seq,
                time = time,
                kind = TurnOutcomeKind.Error,
                message = failure,
            ),
        )
    }

    /**
     * Register one tool call row.
     *
     * Later knowledge of the same call only fills what is missing: the stream
     * knows the name first, `tool/call` adds the full arguments, `tool/result` adds
     * the output. Re-registering must never append a second row, and must never
     * discard arguments it already has.
     */
    private fun registerCall(
        callId: String,
        name: String,
        arguments: String,
        seq: Long,
        time: Long,
    ) {
        val existingIndex = toolIndex[callId]
        val existing = existingIndex?.let { items.getOrNull(it) }
        when (existing) {
            is TimelineItem.ToolCallItem -> {
                if (existingIndex == null) return
                val merged = existing.copy(
                    name = if (existing.name.isBlank()) name else existing.name,
                    arguments = if (existing.arguments.isBlank()) arguments else existing.arguments,
                )
                if (merged != existing) items[existingIndex] = merged
            }
            is TimelineItem.PendingQuestionItem -> {
                // Already an interactive card. The prompts normally came from the
                // same payload, but the stream only knows the call name, so fill
                // them once `tool/call` supplies the arguments.
                if (existingIndex == null) return
                if (existing.questions.isEmpty()) {
                    val prompts = parseQuestionPrompts(arguments)
                    if (prompts.isNotEmpty()) {
                        items[existingIndex] = existing.copy(questions = prompts)
                    }
                }
            }
            else -> {
                if (name == "ask_user_question") {
                    val prompts = parseQuestionPrompts(arguments).ifEmpty {
                        (detachedCalls[callId] as? TimelineItem.PendingQuestionItem)?.questions.orEmpty()
                    }
                    if (prompts.isNotEmpty()) {
                        detachedCalls.remove(callId)
                        toolIndex[callId] = appendReturningIndex(
                            TimelineItem.PendingQuestionItem(seq, time, callId, prompts),
                        )
                        return
                    }
                }
                // A detached row is re-emitted here, keeping what the stream learned.
                val previous = detachedCalls.remove(callId)
                val seed = (previous as? TimelineItem.ToolCallItem)?.copy(seq = seq, time = time)
                toolIndex[callId] = appendReturningIndex(
                    seed ?: TimelineItem.ToolCallItem(seq, time, callId, name, arguments),
                )
            }
        }
    }

    private fun applyToolCall(seq: Long, time: Long, data: JsonObject) {
        val callId = JsonText.string(data["callId"]) ?: return
        val name = JsonText.string(data["name"]) ?: "tool"
        val args = JsonText.string(data["arguments"]) ?: ""
        registerCall(callId, name, args, seq, time)
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
        // The web client reads `message.source.callId`; `toolCallId` is present too
        // but `source.callId` is the field the durable log anchors the pair on.
        val callId = JsonText.string((message["source"] as? JsonObject)?.get("callId"))
            ?: JsonText.string(message["toolCallId"])
            ?: return

        // A question that has been answered is replaced by its outcome row.
        val pendingIndex = toolIndex[callId]
        if (pendingIndex != null) {
            val pending = items.getOrNull(pendingIndex)
            if (pending is TimelineItem.PendingQuestionItem) {
                items.removeAt(pendingIndex)
                rowKeys.remove(pending.keyOf())
                toolIndex.remove(callId)
                shiftIndicesAfter(pendingIndex)
            }
        }

        val text = EventParser.textOf(message["content"])
        val isError = JsonText.bool(message["isError"]) ?: false
        val index = toolIndex[callId]
        val existing = index?.let { items.getOrNull(it) as? TimelineItem.ToolCallItem }
        if (existing != null && index != null) {
            items[index] = existing.copy(result = text, isError = isError, running = false)
            return
        }
        // The call head can fall outside the loaded window; keep the result anyway
        // rather than losing the output, and mark it settled.
        toolIndex[callId] = items.size
        append(
            TimelineItem.ToolCallItem(
                seq = seq,
                time = time,
                callId = callId,
                name = JsonText.string(data["name"]) ?: "tool",
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
        val turn = JsonText.integer(frame["turn"]) ?: 0
        val step = JsonText.integer(frame["step"]) ?: 0
        val partition = "$turn:$step"

        when (frameType) {
            "start" -> {
                if (streaming?.partition != partition) finalizeStreaming()
                val fresh = streaming == null
                val active = streaming ?: StreamingStep(partition, turn, step).also {
                    it.lastSeq = localSeq
                    it.lastTime = lastTime
                    streaming = it
                }
                // A new step owns its own frozen rows; the previous step's were
                // either superseded by its commit or belong to an aborted prefix.
                if (fresh) frozenRows.clear()
                active.attemptIds.add(attemptId)
            }

            "chunk" -> {
                val active = streaming?.takeIf { it.partition == partition } ?: return
                val time = JsonText.long(frame["time"]) ?: lastTime
                val chunk = frame["chunk"] as? JsonObject ?: return
                applyChunk(active, chunk, time)
                // Only materialize a row once the run actually has content, so a
                // step that starts with a tool call shows no empty bubble above it.
                if (active.blocks.isNotEmpty()) {
                    ensureStreamingRow(active, time)
                    refreshStreaming(active, time)
                }
            }

            "end" -> finalizeStreaming()
        }
    }

    private fun applyChunk(step: StreamingStep, chunk: JsonObject, time: Long) {
        when (JsonText.string(chunk["type"])) {
            "text-delta" -> {
                val delta = JsonText.string(chunk["text"]) ?: return
                appendText(step, delta)
            }
            "reasoning-delta" -> {
                val delta = JsonText.string(chunk["text"]) ?: return
                appendReasoning(step, delta)
            }
            "tool-call-delta" -> {
                val id = JsonText.string(chunk["id"]) ?: return
                val name = JsonText.string(chunk["name"])
                val args = JsonText.string(chunk["argumentsDelta"]).orEmpty()
                if (name != null) {
                    // A call interrupts the current prose run. Freezing the bubble
                    // first is what keeps the order right: text produced before the
                    // call stays above the tool row, and the text that continues
                    // after it opens a fresh bubble below. Without this split the
                    // whole step's prose sat in one row above every tool card, which
                    // is the "tool calls in the wrong order" symptom.
                    splitStreamingForCall(step, time)
                    registerCall(id, name, args, step.lastSeq, time)
                } else {
                    appendToolArguments(id, args)
                }
            }
            "block-start" -> {
                val blockType = JsonText.string(chunk["blockType"])
                val block = chunk["block"] as? JsonObject
                val id = JsonText.string(block?.get("id"))
                val name = JsonText.string(block?.get("name"))
                if (blockType == "tool-call" && block != null && id != null && name != null) {
                    registerCall(id, name, JsonText.string(block["arguments"]).orEmpty(), step.lastSeq, time)
                }
            }
            "block-end" -> {
                val block = chunk["block"] as? JsonObject
                if (block != null) {
                    val id = JsonText.string(block["id"])
                    val name = JsonText.string(block["name"])
                    if (id != null && name != null) {
                        registerCall(
                            id,
                            name,
                            JsonText.string(block["arguments"]).orEmpty(),
                            step.lastSeq,
                            time,
                        )
                    }
                }
            }
        }
    }

    /** Append a text delta, extending the trailing text block when there is one. */
    private fun appendText(step: StreamingStep, delta: String) {
        val last = step.blocks.lastOrNull()
        if (last is ContentBlock.Text) {
            step.blocks[step.blocks.lastIndex] = ContentBlock.Text(last.text + delta)
        } else {
            step.blocks.add(ContentBlock.Text(delta))
        }
    }

    private fun appendReasoning(step: StreamingStep, delta: String) {
        val last = step.blocks.lastOrNull()
        if (last is ContentBlock.Reasoning) {
            step.blocks[step.blocks.lastIndex] = ContentBlock.Reasoning(last.text + delta)
        } else {
            step.blocks.add(ContentBlock.Reasoning(delta))
        }
    }

    /** Extend the pending arguments of a call that is already registered. */
    private fun appendToolArguments(callId: String, delta: String) {
        if (delta.isEmpty()) return
        val index = toolIndex[callId] ?: return
        val existing = items.getOrNull(index) as? TimelineItem.ToolCallItem ?: return
        items[index] = existing.copy(arguments = existing.arguments + delta)
    }

    /**
     * Freeze the current prose run and detach it, so the next delta opens a new row.
     *
     * This is what fixes the ordering: text produced before a call stays above the
     * tool row, and text produced after it lands in a fresh row below. Without the
     * split, a step's whole prose lived in one row above every one of its tool cards.
     *
     * An empty run is removed rather than frozen, because a bubble with no blocks
     * renders as a blank gap. The replacement row is created lazily by
     * [ensureStreamingRow], so a step that is nothing but tool calls never shows an
     * empty bubble between them.
     */
    private fun splitStreamingForCall(step: StreamingStep, time: Long) {
        val index = step.indexInList
        if (index >= 0 && index < items.size) {
            val current = items.getOrNull(index) as? TimelineItem.AssistantMessage
            if (current != null) {
                rowKeys.remove(current.keyOf())
                if (current.blocks.isEmpty()) {
                    items.removeAt(index)
                    shiftIndicesAfter(index)
                } else {
                    // Freeze in place: the row keeps its position above the call, and
                    // stops being "live" so the committed message's own row for this
                    // run supersedes it instead of printing the same words twice.
                    val frozen = current.copy(streaming = false, live = false)
                    items[index] = frozen
                    rowKeys.remove(current.keyOf())
                    rowKeys.add(frozen.keyOf())
                    frozenRows.add(frozen.keyOf())
                }
            }
        }
        step.indexInList = -1
        step.part += 1
        step.blocks.clear()
        step.lastSeq = localSeq
        step.lastTime = time
    }

    /**
     * Create the row for the current prose run if it does not exist yet.
     *
     * @returns the row's index in [items].
     */
    private fun ensureStreamingRow(step: StreamingStep, time: Long): Int {
        val existing = step.indexInList
        if (existing >= 0 && existing < items.size &&
            items.getOrNull(existing) is TimelineItem.AssistantMessage
        ) {
            return existing
        }
        step.lastSeq = localSeq
        step.lastTime = time
        step.indexInList = appendReturningIndex(
            TimelineItem.AssistantMessage(
                seq = step.lastSeq,
                time = time,
                blocks = emptyList(),
                streaming = true,
                part = step.part,
                live = true,
            ),
        )
        return step.indexInList
    }

    private fun refreshStreaming(step: StreamingStep, time: Long) {        val index = step.indexInList
        if (index < 0 || index >= items.size) return
        step.lastTime = time
        val previous = items.getOrNull(index)
        val next = TimelineItem.AssistantMessage(
            seq = step.lastSeq,
            time = time,
            blocks = step.blocks.toList(),
            streaming = true,
            part = step.part,
            live = true,
        )
        items[index] = next
        // The preview's identity is stable across deltas, so a recompose reuses the
        // row instead of rebuilding the whole bubble on every token.
        if (previous != null && previous.keyOf() != next.keyOf()) {
            rowKeys.remove(previous.keyOf())
        }
        rowKeys.add(next.keyOf())
    }

    /**
     * Retire the provisional streaming bubble.
     *
     * Both cases remove it. The committed `assistant/message` replays whatever it
     * kept, so leaving a bubble behind duplicated the prose; and a step that
     * streamed nothing at all left an empty "正在输出" row above the conversation.
     * The stream is a preview of the message, never extra content.
     */
    private fun finalizeStreaming() = discardStreaming()

    /**
     * Close the open step at a step or turn boundary.
     *
     * A step can produce text and then never commit an `assistant/message`: the
     * operator hit stop, the provider failed, or the host dropped the step. The web
     * client keeps that prefix and marks it 已停止, so the words the model actually
     * produced are not thrown away. Only when a committed message for this step
     * already exists is the bubble discarded, because that message reprints it.
     */
    private fun closeStreaming() {
        val step = streaming ?: return
        streaming = null
        val index = step.indexInList
        if (index < 0 || index >= items.size) return
        val current = items.getOrNull(index) as? TimelineItem.AssistantMessage ?: return
        if (current.blocks.isEmpty()) {
            items.removeAt(index)
            rowKeys.remove(current.keyOf())
            shiftIndicesAfter(index)
            return
        }
        // Keep the prefix, but stop animating: nothing more is arriving.
        val frozen = current.copy(streaming = false)
        items[index] = frozen
        rowKeys.add(frozen.keyOf())
    }

    private fun discardStreaming() {
        val step = streaming ?: return
        streaming = null
        val index = step.indexInList
        if (index < 0 || index >= items.size) return
        if (items.getOrNull(index) !is TimelineItem.AssistantMessage) return
        items.removeAt(index)
        shiftIndicesAfter(index)
    }

    // ------------------------------------------------------------------ helpers

    /** Decrement every recorded index above [removed] after an in-list removal. */
    private fun shiftIndicesAfter(removed: Int) {
        toolIndex.replaceAll { _, value -> if (value > removed) value - 1 else value }
        commandIndex.replaceAll { _, value -> if (value > removed) value - 1 else value }
        streaming?.let { if (it.indexInList > removed) it.indexInList -= 1 }
    }

    /** `compaction/summary` carries content blocks, not a plain string. */
    private fun summaryText(element: kotlinx.serialization.json.JsonElement?): String? {
        JsonText.string(element)?.let { return it }
        return EventParser.textOf(element)
    }
}
