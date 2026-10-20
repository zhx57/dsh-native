package dev.dsh.nativeapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Typed facade over [DshClient].
 *
 * Argument objects match the descriptor field names exactly, because the DSH
 * gateway rejects missing or extra fields.
 */
class DshRepository(private val client: DshClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------- sessions

    suspend fun listSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        val value = client.call("session/list", buildJsonObject { put("_request", buildJsonObject { }) })
        val items = value.jsonObject["items"] as? JsonArray ?: return@withContext emptyList()
        items.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(SessionSummary.serializer(), element) }.getOrNull()
        }
    }

    suspend fun modelCatalog(): ModelCatalog = withContext(Dispatchers.IO) {
        val value = client.call("session/modelCatalog", buildJsonObject { })
        json.decodeFromJsonElement(ModelCatalog.serializer(), value)
    }

    suspend fun createSession(workspaceId: String? = null, cwd: String? = null): String =
        withContext(Dispatchers.IO) {
            val request = buildJsonObject {
                if (workspaceId != null) put("workspaceId", workspaceId)
                if (cwd != null) put("cwd", cwd)
            }
            val value = client.call("session/create", buildJsonObject { put("request", request) })
            JsonText.string(value.jsonObject["sessionId"]) ?: error("session/create returned no id")
        }

    suspend fun renameSession(sessionId: String, title: String) = withContext(Dispatchers.IO) {
        client.call(
            "session/rename",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("sessionId", sessionId)
                    put("title", title)
                })
            },
        )
        Unit
    }

    suspend fun cancelSession(sessionId: String) = withContext(Dispatchers.IO) {
        client.call(
            "session/cancel",
            buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) },
        )
        Unit
    }

    suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?,
    ) = withContext(Dispatchers.IO) {
        client.call(
            "session/selectModel",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("sessionId", sessionId)
                    put("provider", provider)
                    put("model", model)
                    if (reasoningEffort != null) put("reasoningEffort", reasoningEffort)
                })
            },
        )
        Unit
    }

    /** Send a user prompt. [mode] is `queue` (default) or `steer`. */
    suspend fun sendPrompt(sessionId: String, text: String, mode: String = "queue") =
        withContext(Dispatchers.IO) {
            val content = buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            client.call(
                "session/prompt",
                buildJsonObject {
                    put("request", buildJsonObject {
                        put("requestId", UUID.randomUUID().toString())
                        put("sessionId", sessionId)
                        put("mode", mode)
                        put("content", content)
                        put("clientTimeZone", java.util.TimeZone.getDefault().id)
                    })
                },
            )
            Unit
        }

    // ------------------------------------------------------------------ page

    /** Load one page of a session's event log. */
    suspend fun loadPage(sessionId: String, throughSeq: Long, maxMessages: Int = 60): PageResult =
        withContext(Dispatchers.IO) {
            val value = client.call(
                "session/page",
                buildJsonObject {
                    put("request", buildJsonObject {
                        put("address", buildJsonObject {
                            put("kind", "session")
                            put("sessionId", sessionId)
                        })
                        put("throughSeq", throughSeq)
                        put("maxMessages", maxMessages)
                    })
                },
            )
            val obj = value.jsonObject
            PageResult(
                records = (obj["records"] as? JsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { it as? JsonObject },
                cursor = JsonText.long(obj["cursor"]) ?: 0L,
                hasMore = JsonText.bool(obj["hasMore"]) ?: false,
            )
        }

    data class PageResult(
        val records: List<JsonObject>,
        val cursor: Long,
        val hasMore: Boolean,
    )
}
