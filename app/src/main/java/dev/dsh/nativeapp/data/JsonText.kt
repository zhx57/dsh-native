package dev.dsh.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tolerant accessors over `JsonElement`.
 *
 * DSH's wire format carries loosely-typed projections and event payloads, so the
 * client reads them defensively instead of mirroring every union in Kotlin.
 */
object JsonText {

    fun field(element: JsonElement?, name: String): JsonElement? {
        val obj = element as? JsonObject ?: return null
        return obj[name]?.takeIf { it !is JsonNull }
    }

    fun string(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNullSafe()
        else -> null
    }

    fun integer(element: JsonElement?): Int? = long(element)?.toInt()

    fun long(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.contentOrNullSafe()?.toLongOrNull()
    }

    fun bool(element: JsonElement?): Boolean? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.contentOrNullSafe()?.toBooleanStrictOrNull()
    }

    fun array(element: JsonElement?): JsonArray? = element as? JsonArray

    fun objects(element: JsonElement?): List<JsonObject> =
        array(element)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is JsonNull) null else content
}
