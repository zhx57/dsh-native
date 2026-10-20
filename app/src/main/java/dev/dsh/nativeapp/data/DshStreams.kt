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

    /**
     * Follow the workspace index.
     *
     * The host answers a `baseline` once and then pushes targeted frames as the
     * index changes. `pinned` carries only the pinned ids and `archived` only the
     * archived ones, so a pin action lands here without a reconnect.
     *
     * @param onBaseline full index on attach.
     * @param onPinned pinned ids after a pin/unpin.
     * @param onArchived archived ids after an archive/unarchive.
     */
    fun followWorkspaces(
        onBaseline: (WorkspaceBaseline) -> Unit,
        onPinned: (List<String>) -> Unit = {},
        onArchived: (List<String>) -> Unit = {},
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
                "pinned" -> readIds(obj["pinnedSessionIds"]).let(onPinned)
                "archived" -> readIds(obj["archivedSessionIds"]).let(onArchived)
                else -> Unit
            }
        },
        onError = onError,
    )

    /** Read a `["session-…"]` array from a frame, tolerating either nesting. */
    private fun readIds(element: kotlinx.serialization.json.JsonElement?): List<String> {
        val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    /**
     * Follow one session: history page first, then live events and assistant frames.
     *
     * @param maxMessages how much history the host replays. A phone screen shows a
     *   handful of turns, so requesting the whole log only delays first paint; the
     *   tail is enough and older context is not needed to resume.
     * @param onItem receives each stream item value verbatim, so the caller owns
     *   folding and can reuse the same reducer as `session/page`.
     */
    fun followSession(
        sessionId: String,
        onItem: (JsonObject) -> Unit,
        onError: (Throwable) -> Unit = {},
        onClosed: () -> Unit = {},
        maxMessages: Int = 24,
    ): DshStream = client.stream(
        endpoint = "session/follow",
        args = buildJsonObject {
            put("request", buildJsonObject {
                put("address", buildJsonObject {
                    put("kind", "session")
                    put("sessionId", sessionId)
                })
                put("assistantStream", true)
                put("maxMessages", maxMessages)
            })
        },
        onItem = { value -> onItem(value.jsonObject) },
        onError = onError,
        onClosed = onClosed,
    )
}
