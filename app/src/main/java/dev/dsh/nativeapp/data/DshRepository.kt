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

    /**
     * Switch the session's model and reasoning effort.
     *
     * @returns the selection the host actually applied, which may differ from the
     *   request when a provider is unavailable.
     */
    suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?,
    ): ModelRef = withContext(Dispatchers.IO) {
        val value = client.call(
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
        val selected = JsonText.field(value, "selected")
        ModelRef(
            provider = JsonText.string(JsonText.field(selected, "provider")) ?: provider,
            model = JsonText.string(JsonText.field(selected, "model")) ?: model,
            reasoningEffort = JsonText.string(JsonText.field(selected, "reasoningEffort"))
                ?: reasoningEffort,
        )
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
    suspend fun loadPage(
        sessionId: String,
        throughSeq: Long,
        maxMessages: Int = 60,
        beforeSeq: Long? = null,
    ): PageResult =
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
                        // Backwards pagination: only records older than this seq.
                        if (beforeSeq != null) put("beforeSeq", beforeSeq)
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

    /**
     * Read a session's live projections.
     *
     * The follow stream only pushes projections on its opening snapshot, so values
     * that change later — token usage, context pressure, stats — stayed frozen at
     * whatever they were when the app attached. After `/compact` that meant the
     * session-info sheet kept showing the pre-compaction context size. This is the
     * authoritative read the web client uses for the same panel.
     */
    suspend fun projections(sessionId: String): ProjectionSnapshot? =
        withContext(Dispatchers.IO) {
            val value = runCatching {
                client.call(
                    "session/projections",
                    buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) },
                )
            }.getOrNull() ?: return@withContext null
            val obj = value as? JsonObject ?: return@withContext null
            ProjectionSnapshot(
                asOfSeq = JsonText.long(obj["asOfSeq"]) ?: 0L,
                values = obj["values"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }

    /** One `session/projections` answer: revision plus the value bag. */
    data class ProjectionSnapshot(val asOfSeq: Long, val values: JsonObject)

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
     * Upload one attachment and return the receipt id a prompt references.
     *
     * `fileUploads/upload` takes base64 and answers with a receipt; the prompt then
     * carries `{type:"file", receiptId}` instead of inline bytes.
     */
    suspend fun uploadAttachment(agentId: String, name: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val value = client.call(
                "fileUploads/upload",
                buildJsonObject {
                    put("agentId", agentId)
                    put("request", buildJsonObject {
                        put("data", encoded)
                        put("name", name)
                    })
                },
            )
            JsonText.string(JsonText.field(value, "receiptId"))
                ?: JsonText.string(value.jsonObject["receiptId"])
                ?: JsonText.string(value.jsonObject["id"])
                ?: error("upload returned no receipt")
        }

    /** Send a prompt that references previously uploaded files. */
    suspend fun sendPromptWithFiles(
        sessionId: String,
        text: String,
        receiptIds: List<String>,
        mode: String = "queue",
    ) = withContext(Dispatchers.IO) {
        val content = buildJsonArray {
            if (text.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            receiptIds.forEach { receipt ->
                add(
                    buildJsonObject {
                        put("type", "file")
                        put("receiptId", receipt)
                    },
                )
            }
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

    /**
     * Mutate one queued message.
     *
     * The queue is the session's `inbox.next-turn` projection: messages accepted
     * while a turn runs and waiting to wake the next one. `kind` is `edit`, `remove`
     * or `steer` (send now, interrupting the running turn).
     */
    suspend fun updateQueue(
        sessionId: String,
        itemId: String,
        kind: String,
        text: String? = null,
    ) = withContext(Dispatchers.IO) {
        client.call(
            "session/updateQueue",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("sessionId", sessionId)
                    put("itemId", itemId)
                    put(
                        "action",
                        buildJsonObject {
                            put("kind", kind)
                            if (kind == "edit" && text != null) {
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", text)
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                })
            },
        )
        Unit
    }

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
