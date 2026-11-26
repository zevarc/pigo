package com.zevarc.pigo.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Thin helpers over kotlinx.serialization's JsonElement tree.
 *
 * The bridge protocol is deliberately schema-less: events gain fields over time
 * and the client must ignore what it does not understand instead of failing to
 * deserialize an otherwise usable event.
 */
object Wire {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    fun parse(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    fun obj(vararg fields: Pair<String, JsonElement?>): JsonObject =
        buildJsonObject {
            for ((key, value) in fields) {
                if (value != null) put(key, value)
            }
        }
}

fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * Reads an integer field; tolerates a fractional value because some fields
 * (file timestamps) arrive as doubles.
 */
fun JsonObject.long(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    primitive.longOrNull?.let { return it }
    return primitive.doubleOrNull?.toLong()
}

fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)

fun JsonObject.array(key: String): List<JsonElement>? = runCatching { this[key]?.jsonArray }.getOrNull()

fun JsonElement.asString(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
