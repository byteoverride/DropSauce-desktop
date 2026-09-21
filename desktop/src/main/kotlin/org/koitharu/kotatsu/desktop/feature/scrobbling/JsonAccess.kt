package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Tolerant readers over a parsed response.
 *
 * Both services omit fields rather than sending nulls, and MyAnimeList in particular drops
 * every empty field from `my_list_status`, so a data class with required members would
 * throw on a perfectly ordinary reply. These accessors return null instead, and [require*]
 * is used only where a missing field genuinely means the call did not do what was asked.
 */
internal val trackingJson = Json { ignoreUnknownKeys = true }

internal fun parseJsonObject(body: String, what: String): JsonObject {
	val element = try {
		trackingJson.parseToJsonElement(body)
	} catch (e: Exception) {
		throw TrackingApiException("$what: the reply was not JSON (${e.message})")
	}
	return element as? JsonObject
		?: throw TrackingApiException("$what: expected a JSON object, got ${element::class.simpleName}")
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonObject.child(name: String): JsonObject? = this[name]?.takeUnless { it is JsonNull }.asObject()

internal fun JsonObject.requireChild(name: String, what: String): JsonObject =
	child(name) ?: throw TrackingApiException("$what: the reply had no \"$name\" object")

internal fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.string(name: String): String? =
	(this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A number or a string, as text. Services are inconsistent about ids in particular. */
internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.requireLong(name: String, what: String): Long =
	long(name) ?: throw TrackingApiException("$what: the reply had no numeric \"$name\"")

internal fun JsonObject.int(name: String, fallback: Int = 0): Int = (this[name] as? JsonPrimitive)?.intOrNull ?: fallback

internal fun JsonObject.float(name: String, fallback: Float = 0f): Float =
	(this[name] as? JsonPrimitive)?.floatOrNull ?: fallback

internal fun JsonObject.boolean(name: String, fallback: Boolean = false): Boolean =
	(this[name] as? JsonPrimitive)?.booleanOrNull ?: fallback

/** A GraphQL string literal, quoted and escaped. */
internal fun graphQlString(value: String): String = JsonPrimitive(value).toString()
