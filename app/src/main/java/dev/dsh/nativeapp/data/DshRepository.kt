package dev.dsh.nativeapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * Load one page of a session's event log.
     *
     * `throughSeq` must not exceed the session's own cursor, so callers either
     * pass the `asOfSeq` reported by `session/list` or [loadTail], which reads
     * the cursor first.
     */
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
            parsePage(value)
        }

    /** Load the newest page by resolving the session cursor first. */
    suspend fun loadTail(sessionId: String, maxMessages: Int = 60): PageResult =
        withContext(Dispatchers.IO) {
            val cursor = listSessions()
                .firstOrNull { it.sessionId == sessionId }
                ?.projections?.asOfSeq
                ?: 0L
            loadPage(sessionId, cursor, maxMessages)
        }

    // ------------------------------------------------------------- commands

    /** List the slash commands this session's host offers. */
    suspend fun listCommands(agentId: String): List<SlashCommand> = withContext(Dispatchers.IO) {
        val value = client.call("commands/list", buildJsonObject { put("agentId", agentId) })
        (value as? JsonArray ?: JsonArray(emptyList())).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = JsonText.string(obj["name"]) ?: return@mapNotNull null
            SlashCommand(
                name = name,
                description = JsonText.string(obj["description"]).orEmpty(),
                hint = JsonText.string(JsonText.field(obj["input"], "hint")),
            )
        }
    }

    /** Execute one slash command line such as `/compact`. */
    suspend fun executeCommand(agentId: String, line: String) = withContext(Dispatchers.IO) {
        client.call(
            "commands/execute",
            buildJsonObject {
                put("agentId", agentId)
                put("line", line)
                put("submittedAttachments", buildJsonArray { })
            },
        )
        Unit
    }

    /**
     * Branch the session from one message.
     *
     * `session/fork` copies the log up to `atSeq` into a new session, which is the
     * web UI's per-message "branch" action — a new conversation that keeps the
     * history above that point and drops everything after it.
     */
    suspend fun forkSession(sessionId: String, atSeq: Long): String = withContext(Dispatchers.IO) {
        val value = client.call(
            "session/fork",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("sessionId", sessionId)
                    put("atSeq", atSeq)
                })
            },
        )
        JsonText.string(value.jsonObject["sessionId"]) ?: error("session/fork returned no id")
    }

    /** Switch the permission preset (sandbox mode plus approval policy). */
    suspend fun setPermissionPreset(agentId: String, preset: String) = withContext(Dispatchers.IO) {
        client.call(
            "commands/execute",
            buildJsonObject {
                put("agentId", agentId)
                put("line", "/permission $preset")
                put("submittedAttachments", buildJsonArray { })
            },
        )
        Unit
    }

    /** Available permission presets with their defaults. */
    suspend fun permissionCatalog(): PermissionCatalog = withContext(Dispatchers.IO) {
        val value = client.call("permissionPresets/catalog", buildJsonObject { })
        val obj = value.jsonObject
        fun parse(key: String): List<String> =
            (obj[key] as? JsonArray)?.mapNotNull { element ->
                JsonText.string(JsonText.field(element, "value"))
            } ?: emptyList()
        PermissionCatalog(
            options = parse("options"),
            defaultPreset = JsonText.string(obj["defaultPreset"]),
        )
    }

    data class PermissionCatalog(
        val options: List<String>,
        val defaultPreset: String?,
    )


    /**
     * Answer one `ask_user_question` batch.
     *
     * @param answers question id to the selected option labels.
     */
    suspend fun answerQuestions(
        agentId: String,
        callId: String,
        answers: Map<String, List<String>>,
    ) = withContext(Dispatchers.IO) {
        client.call(
            "userQuestions/answer",
            buildJsonObject {
                put("agentId", agentId)
                put("callId", callId)
                put(
                    "answer",
                    buildJsonObject {
                        put(
                            "answers",
                            buildJsonArray {
                                answers.forEach { (id, selected) ->
                                    add(
                                        buildJsonObject {
                                            put("id", id)
                                            put("selected", buildJsonArray { selected.forEach { add(JsonPrimitive(it)) } })
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
        Unit
    }

    /**
     * Answer a pending approval.
     *
     * `allowed-once` / `rejected` are the outcomes the web approval panel sends;
     * the request rides the user-question channel keyed by the tool call id.
     */
    suspend fun answerApproval(agentId: String, callId: String, allowed: Boolean) =
        withContext(Dispatchers.IO) {
            client.call(
                "userQuestions/answer",
                buildJsonObject {
                    put("agentId", agentId)
                    put("callId", callId)
                    put(
                        "answer",
                        buildJsonObject {
                            put(
                                "answers",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("id", callId)
                                            put(
                                                "selected",
                                                buildJsonArray {
                                                    add(
                                                        JsonPrimitive(
                                                            if (allowed) "allowed-once" else "rejected",
                                                        ),
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            Unit
        }


    private fun parsePage(value: JsonElement): PageResult {
        val obj = value.jsonObject
        return PageResult(
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
