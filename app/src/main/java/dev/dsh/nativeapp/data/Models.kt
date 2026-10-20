package dev.dsh.nativeapp.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** One DSH session summary, as returned by `session/list`. */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val updatedAt: Long = 0L,
    val running: Boolean = false,
    val blank: Boolean = false,
    val agentAvailable: Boolean = true,
    val parentSessionId: String? = null,
    val origin: String? = null,
    val cwd: String? = null,
    val projections: ProjectionsEnvelope? = null,
) {
    val title: String
        get() = projections?.values?.get("title")?.let { JsonText.string(it) }?.takeIf { it.isNotBlank() } ?: "新会话"

    val tokenUsage: TokenUsage?
        get() = projections?.values?.get("tokenUsage")?.let { TokenUsage.from(it) }

    val contextWindow: Int?
        get() = projections?.values?.get("contextPressure")
            ?.let { JsonText.integer(JsonText.field(it, "contextWindow")) }
}

/** Projection envelope: `{kind, asOfSeq, values}`. */
@Serializable
data class ProjectionsEnvelope(
    val kind: String? = null,
    val asOfSeq: Long = 0L,
    val values: Map<String, JsonElement> = emptyMap(),
)

/** Token accounting projection. */
data class TokenUsage(
    val input: Long,
    val output: Long,
    val cacheRead: Long,
) {
    val total: Long get() = input + output

    companion object {
        fun from(element: JsonElement?): TokenUsage? {
            if (element == null) return null
            return TokenUsage(
                input = JsonText.long(JsonText.field(element, "uncachedInputTokens")) ?: 0L,
                output = JsonText.long(JsonText.field(element, "outputTokens")) ?: 0L,
                cacheRead = JsonText.long(JsonText.field(element, "cacheReadTokens")) ?: 0L,
            )
        }
    }
}

/** One workspace, as returned by `workspace/follow`'s baseline item. */
@Serializable
data class WorkspaceSummary(
    val workspaceId: String,
    val path: String = "",
    val title: String = "",
    val sessionIds: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val displayName: String
        get() = title.ifBlank { path.substringAfterLast('\\').substringAfterLast('/').ifBlank { "工作区" } }
}

/** Baseline payload of the `workspace/follow` stream. */
@Serializable
data class WorkspaceBaseline(
    val items: List<WorkspaceSummary> = emptyList(),
    val archivedSessionIds: List<String> = emptyList(),
    val pinnedSessionIds: List<String> = emptyList(),
)

/** A model option from `session/modelCatalog`. */
@Serializable
data class ModelGroup(
    val id: String,
    val name: String = "",
    val models: List<ModelEntry> = emptyList(),
)

@Serializable
data class ModelEntry(
    val id: String,
    val name: String = "",
    val reasoning: ReasoningSpec? = null,
)

@Serializable
data class ReasoningSpec(
    val efforts: List<ReasoningEffort> = emptyList(),
    val defaultEffort: String? = null,
)

@Serializable
data class ReasoningEffort(
    val id: String,
    val name: String = "",
)

@Serializable
data class ModelCatalog(
    val default: ModelRef? = null,
    val groups: List<ModelGroup> = emptyList(),
    val routableProviders: List<String> = emptyList(),
)

@Serializable
data class ModelRef(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)
