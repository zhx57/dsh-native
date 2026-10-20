package dev.dsh.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Stream-backed facade for the two endpoints DSH only exposes over
 * `/api/remote.mux`: `workspace/follow` and `session/follow`.
 */
class DshStreams(private val client: DshClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Follow the workspace index; [onBaseline] fires once, [onChange] on every update. */
    fun followWorkspaces(
        onBaseline: (WorkspaceBaseline) -> Unit,
        onError: (Throwable) -> Unit = {},
    ): DshStream = client.stream(
        endpoint = "workspace/follow",
        args = buildJsonObject { },
        onItem = { value ->
            val obj = value.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "baseline" -> runCatching {
                    json.decodeFromJsonElement(
                        WorkspaceBaseline.serializer(),
                        obj["value"]?.jsonObject ?: JsonObject(emptyMap()),
                    )
                }.onSuccess(onBaseline)
                else -> Unit
            }
        },
        onError = onError,
    )

    /**
     * Follow one session: history page first, then live events and assistant frames.
     *
     * @param onItem receives each stream item value verbatim, so the caller owns
     *   folding and can reuse the same reducer as `session/page`.
     */
    fun followSession(
        sessionId: String,
        onItem: (JsonObject) -> Unit,
        onError: (Throwable) -> Unit = {},
        onClosed: () -> Unit = {},
    ): DshStream = client.stream(
        endpoint = "session/follow",
        args = buildJsonObject {
            put("request", buildJsonObject {
                put("address", buildJsonObject {
                    put("kind", "session")
                    put("sessionId", sessionId)
                })
                put("assistantStream", true)
                put("maxMessages", 80)
            })
        },
        onItem = { value -> onItem(value.jsonObject) },
        onError = onError,
        onClosed = onClosed,
    )
}
