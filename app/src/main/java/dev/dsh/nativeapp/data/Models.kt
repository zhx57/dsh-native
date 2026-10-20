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
    /** Prompt tokens written into the cache; billed alongside the other two. */
    val cacheWrite: Long = 0,
) {
    val total: Long get() = input + output

    /**
     * Prompt-side tokens that were actually billed.
     *
     * The web client sums the three disjoint buckets (`billedInputTokens`), which is
     * the denominator for its cache-hit figure. Using `input` alone undercounted and
     * made the hit rate read far higher than the host's own number.
     */
    val billedInput: Long get() = input + cacheRead + cacheWrite

    /** Cache hit share of billed input, or null before anything is billed. */
    val cacheHitPercent: Int?
        get() = if (billedInput <= 0) null
        else ((cacheRead.toDouble() / billedInput) * 100).toInt().coerceIn(0, 100)

    companion object {
        fun from(element: JsonElement?): TokenUsage? {
            if (element == null) return null
            return TokenUsage(
                input = JsonText.long(JsonText.field(element, "uncachedInputTokens")) ?: 0L,
                output = JsonText.long(JsonText.field(element, "outputTokens")) ?: 0L,
                cacheRead = JsonText.long(JsonText.field(element, "cacheReadTokens")) ?: 0L,
                cacheWrite = JsonText.long(JsonText.field(element, "cacheWriteTokens")) ?: 0L,
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
) {
    /** Every model the host currently offers, as `(provider, model)` pairs. */
    val entries: List<Pair<String, ModelEntry>>
        get() = groups.flatMap { group -> group.models.map { group.id to it } }

    /**
     * Whether the host still offers this provider/model.
     *
     * The session's `modelSelection` projection records what was chosen, which keeps
     * naming a model after its provider or config entry is deleted. Without this
     * check the picker and the composer chip kept presenting a model that no longer
     * exists, and selecting it failed only after the round trip.
     */
    fun has(provider: String?, model: String?): Boolean {
        if (provider == null || model == null) return false
        return groups.any { group ->
            group.id == provider && group.models.any { it.id == model }
        }
    }

    /** True once at least one model is known; an empty catalog means "not loaded". */
    val loaded: Boolean get() = groups.any { it.models.isNotEmpty() }
}

@Serializable
data class ModelRef(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)
