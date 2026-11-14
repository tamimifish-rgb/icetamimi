package com.tamimi.naqiice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// Rows travel as JSON objects end to end (server → cache → screen, screen → outbox →
// server), exactly like the web app, so a payload queued by either client stays valid.

val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

typealias Row = JsonObject

fun parseObject(text: String): JsonObject = JSON.parseToJsonElement(text) as JsonObject

fun JsonElement?.orNull(): JsonElement? = if (this == null || this is JsonNull) null else this

fun JsonObject.str(key: String): String? = (this[key].orNull() as? JsonPrimitive)?.contentOrNull
fun JsonObject.num(key: String): Double = numOrNull(key) ?: 0.0
fun JsonObject.numOrNull(key: String): Double? {
    val p = this[key].orNull() as? JsonPrimitive ?: return null
    return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
}
fun JsonObject.bool(key: String): Boolean? = (this[key].orNull() as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key].orNull() as? JsonObject
fun JsonObject.rows(key: String): List<JsonObject> = (this[key].orNull() as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
fun JsonObject.strings(key: String): List<String> =
    (this[key].orNull() as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

fun JsonElement?.asRows(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** Whole amounts are written as integers so numeric columns and comparisons stay exact. */
fun jnum(value: Double): JsonPrimitive =
    if (value.isFinite() && value == Math.rint(value) && kotlin.math.abs(value) < 9e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

fun jstr(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
fun jnumOrNull(value: Double?): JsonElement = if (value == null) JsonNull else jnum(value)

fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(this + pairs)
fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(this - keys.toSet())

/** Keeps the newest copy of each record id — retries never create duplicates. */
fun dedupeById(items: List<JsonObject>): List<JsonObject> {
    val map = LinkedHashMap<String, JsonObject>()
    for (it in items) map[it.str("id") ?: continue] = it
    return map.values.toList()
}
