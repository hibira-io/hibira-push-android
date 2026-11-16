package io.hibira.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Plain JSON values: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean or null. */
internal object JsonValues {
    fun parse(text: String): Any? = try {
        fromElement(Json.parseToJsonElement(text))
    } catch (_: Exception) {
        null
    }

    /** Pusher sends most payloads as JSON inside a string: decode it when it is JSON. */
    fun parseData(data: Any?): Any? = if (data is String) parse(data) ?: data else data

    fun fromElement(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { fromElement(it.value) }
        is JsonArray -> e.map { fromElement(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> e.doubleOrNull
        }
    }

    fun toElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toElement(x) })
        is Iterable<*> -> JsonArray(v.map { toElement(it) })
        is Array<*> -> JsonArray(v.map { toElement(it) })
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    fun stringify(v: Any?): String = toElement(v).toString()
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()

internal fun Any?.asList(): List<Any?> = (this as? List<Any?>) ?: emptyList()

internal fun Map<String, Any?>.str(key: String): String? = this[key]?.let { if (it is String) it else it.toString() }

internal fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}
