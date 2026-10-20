package dev.dsh.nativeapp.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Client for `$events`: the Host's forwarded-event channel.
 *
 * This is the channel that carries interactive requests. `ask_user_question` and
 * tool approvals are **waterfalls**: the Host pushes a frame on `$events`, waits for
 * the client to answer, and the answer is delivered back through the
 * `$events/result` RPC. They never appear in the session log as an answerable
 * request, which is why watching `session/follow` alone showed nothing.
 */
class DshEvents(private val client: DshClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Host client id from the `ready` frame, required to address a result. */
    private val _clientId = MutableStateFlow<String?>(null)
    val clientId: StateFlow<String?> = _clientId

    private val _requests = MutableSharedFlow<PendingRequest>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Interactive requests waiting for an answer. */
    val requests: SharedFlow<PendingRequest> = _requests

    private val _status = MutableSharedFlow<Pair<String, Boolean>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Session id to running flag, from the Host's `api-session/status` event. */
    val status: SharedFlow<Pair<String, Boolean>> = _status

    private var stream: DshStream? = null

    /** Open the channel. Idempotent; call [close] to detach. */
    fun start(
        onError: (Throwable) -> Unit = {},
        onReady: () -> Unit = {},
    ) {
        if (stream != null) return
        stream = client.stream(
            endpoint = "\$events",
            args = buildJsonObject { },
            onItem = { value ->
                val frame = value as? JsonObject ?: return@stream
                when (JsonText.string(frame["type"])) {
                    "ready" -> {
                        _clientId.value = JsonText.string(frame["clientId"])
                        onReady()
                    }
                    "waterfall" -> parseWaterfall(frame)?.let { _requests.tryEmit(it) }
                    // Authoritative running flag: the Host emits this from
                    // `agent/status`, so it is the same signal the web UI trusts for
                    // its send/stop button. Inferring from turn/start and turn/end
                    // drifts whenever an event is missed or a turn is resumed.
                    "emit" -> {
                        if (JsonText.string(frame["event"]) == "api-session/status") {
                            val args = frame["args"] as? JsonArray ?: frame["payload"] as? JsonArray
                            val sessionId = args?.getOrNull(0)?.let(JsonText::string)
                            val running = args?.getOrNull(1)?.let(JsonText::bool)
                            if (sessionId != null && running != null) {
                                _status.tryEmit(sessionId to running)
                            }
                        }
                    }
                    else -> Unit
                }
            },
            onError = { error ->
                stream = null
                onError(error)
            },
            onClosed = { stream = null },
        )
    }

    fun close() {
        stream?.cancel()
        stream = null
    }

    /**
     * Deliver one answer.
     *
     * `outcome.kind = "result"` settles the Host waterfall with a value; omitting
     * the value (or sending `next`) declines it so another answerer can take over.
     */
    fun answer(request: PendingRequest, value: JsonObject): Boolean {
        val clientId = _clientId.value ?: return false
        return runCatching {
            client.call(
                "\$events/result",
                buildJsonObject {
                    put("clientId", clientId)
                    put("eventId", request.eventId)
                    put(
                        "outcome",
                        buildJsonObject {
                            put("kind", "result")
                            put("value", value)
                        },
                    )
                },
            )
            true
        }.getOrDefault(false)
    }

    /** Decline a request so the Host can continue past it. */
    fun decline(request: PendingRequest): Boolean {
        val clientId = _clientId.value ?: return false
        return runCatching {
            client.call(
                "\$events/result",
                buildJsonObject {
                    put("clientId", clientId)
                    put("eventId", request.eventId)
                    put("outcome", buildJsonObject { put("kind", "next") })
                },
            )
            true
        }.getOrDefault(false)
    }

    private fun parseWaterfall(frame: JsonObject): PendingRequest? {
        val event = JsonText.string(frame["event"]) ?: return null
        val eventId = JsonText.string(frame["eventId"]) ?: return null
        val agentId = JsonText.string(frame["agentId"])
        val request = frame["request"] as? JsonObject ?: return null

        return when (event) {
            "user-questions/request" -> PendingRequest.Question(
                eventId = eventId,
                agentId = agentId,
                questions = parseQuestions(request),
            )
            "approval/request" -> PendingRequest.Approval(
                eventId = eventId,
                agentId = agentId,
                callId = JsonText.string(request["callId"]),
                toolName = JsonText.string(request["toolName"])
                    ?: JsonText.string(request["tool"])
                    ?: "工具调用",
                reason = JsonText.string(request["reason"])
                    ?: JsonText.string(request["title"])
                    ?: "这个操作需要你确认",
                detail = JsonText.string(request["detail"])
                    ?: JsonText.string(request["command"]),
            )
            else -> null
        }
    }

    private fun parseQuestions(request: JsonObject): List<QuestionPrompt> {
        val array = request["questions"] as? JsonArray ?: return emptyList()
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

    /** Build the answer value the Host expects: `{answers:[{id,selected}]}`. */
    fun answerValue(answers: Map<String, List<String>>): JsonObject = buildJsonObject {
        put(
            "answers",
            buildJsonArray {
                answers.forEach { (id, selected) ->
                    add(
                        buildJsonObject {
                            put("id", id)
                            put(
                                "selected",
                                buildJsonArray {
                                    selected.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                                },
                            )
                        },
                    )
                }
            },
        )
    }
}

/** One interactive request pushed by the Host and waiting for an answer. */
sealed interface PendingRequest {
    val eventId: String
    val agentId: String?

    data class Question(
        override val eventId: String,
        override val agentId: String?,
        val questions: List<QuestionPrompt>,
    ) : PendingRequest

    data class Approval(
        override val eventId: String,
        override val agentId: String?,
        val callId: String?,
        val toolName: String,
        val reason: String,
        val detail: String?,
    ) : PendingRequest
}
