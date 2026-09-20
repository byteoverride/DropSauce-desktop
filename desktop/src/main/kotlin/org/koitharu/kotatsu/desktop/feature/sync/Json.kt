package org.koitharu.kotatsu.desktop.feature.sync

/**
 * A small JSON reader and writer, owned by this feature.
 *
 * Written by hand because `:desktop` has neither `kotlinx-serialization-json` nor the
 * serialization compiler plugin: the only serialization artifact that reaches its compile
 * classpath is `kotlinx-serialization-core`, transitively, which carries no JSON format and
 * no way to generate a serializer without the plugin. Adding either is a build-file change,
 * and this feature is not allowed to make one. See the contract gaps in the feature report.
 *
 * Numbers keep their source text instead of being parsed into `Double` on the way in,
 * because manga ids are 64-bit hashes and a trip through `Double` silently rounds anything
 * above 2^53. That would corrupt exactly the field every foreign key is built on.
 */
sealed interface JsonValue

data object JsonNull : JsonValue

data class JsonBool(val value: Boolean) : JsonValue

/** A number kept as written. Use [JsonValue.asLong] and friends to narrow it. */
data class JsonNumber(val text: String) : JsonValue {

	constructor(value: Long) : this(value.toString())

	constructor(value: Int) : this(value.toString())

	constructor(value: Float) : this(if (value.isFinite()) value.toString() else "0")
}

data class JsonString(val value: String) : JsonValue

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue

/** Thrown for anything a backup file can get wrong: bad JSON, missing fields, wrong types. */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

// region reading

fun JsonValue.asObject(what: String): JsonObject =
	this as? JsonObject ?: throw BackupFormatException("$what should be a JSON object, found ${kindOf()}")

fun JsonValue.asArray(what: String): JsonArray =
	this as? JsonArray ?: throw BackupFormatException("$what should be a JSON array, found ${kindOf()}")

private fun JsonValue.kindOf(): String = when (this) {
	is JsonNull -> "null"
	is JsonBool -> "a boolean"
	is JsonNumber -> "a number"
	is JsonString -> "a string"
	is JsonArray -> "an array"
	is JsonObject -> "an object"
}

/** The value at [name], or null when absent or explicitly null. */
fun JsonObject.opt(name: String): JsonValue? = fields[name]?.takeIf { it !is JsonNull }

fun JsonObject.requireString(name: String): String = when (val v = opt(name)) {
	is JsonString -> v.value
	null -> throw BackupFormatException("missing required field \"$name\"")
	else -> throw BackupFormatException("field \"$name\" should be a string, found ${v.kindOf()}")
}

fun JsonObject.string(name: String, default: String? = null): String? = when (val v = opt(name)) {
	is JsonString -> v.value
	null -> default
	// Tolerated on purpose: an older writer may have emitted a bare number for a text field,
	// and refusing a whole restore over that helps nobody.
	is JsonNumber -> v.text
	else -> throw BackupFormatException("field \"$name\" should be a string, found ${v.kindOf()}")
}

fun JsonObject.requireLong(name: String): Long = when (val v = opt(name)) {
	is JsonNumber -> v.text.toLongOrNull()
		?: v.text.toDoubleOrNull()?.toLong()
		?: throw BackupFormatException("field \"$name\" is not a whole number: ${v.text}")

	null -> throw BackupFormatException("missing required field \"$name\"")
	else -> throw BackupFormatException("field \"$name\" should be a number, found ${v.kindOf()}")
}

fun JsonObject.long(name: String, default: Long = 0L): Long =
	if (opt(name) == null) default else requireLong(name)

fun JsonObject.requireInt(name: String): Int = requireLong(name).toInt()

fun JsonObject.int(name: String, default: Int = 0): Int =
	if (opt(name) == null) default else requireInt(name)

fun JsonObject.float(name: String, default: Float = 0f): Float = when (val v = opt(name)) {
	is JsonNumber -> v.text.toFloatOrNull() ?: default
	null -> default
	else -> throw BackupFormatException("field \"$name\" should be a number, found ${v.kindOf()}")
}

fun JsonObject.bool(name: String, default: Boolean): Boolean = when (val v = opt(name)) {
	is JsonBool -> v.value
	// Some writers emit 0/1 for booleans; SQLite does, so a hand-made backup might too.
	is JsonNumber -> v.text != "0"
	null -> default
	else -> throw BackupFormatException("field \"$name\" should be a boolean, found ${v.kindOf()}")
}

fun JsonObject.obj(name: String): JsonObject = (
	opt(name) ?: throw BackupFormatException("missing required field \"$name\"")
	).asObject("field \"$name\"")

fun JsonObject.array(name: String): List<JsonValue> =
	opt(name)?.asArray("field \"$name\"")?.items ?: emptyList()

fun JsonObject.stringList(name: String): List<String> =
	array(name).map { (it as? JsonString)?.value ?: throw BackupFormatException("\"$name\" should hold strings") }

// endregion

// region writing

/** Appends [value] to [out] as compact JSON. */
fun writeJson(value: JsonValue, out: Appendable) {
	when (value) {
		is JsonNull -> out.append("null")
		is JsonBool -> out.append(if (value.value) "true" else "false")
		is JsonNumber -> out.append(value.text)
		is JsonString -> writeJsonString(value.value, out)
		is JsonArray -> {
			out.append('[')
			value.items.forEachIndexed { index, item ->
				if (index > 0) out.append(',')
				writeJson(item, out)
			}
			out.append(']')
		}

		is JsonObject -> {
			out.append('{')
			var first = true
			for ((key, item) in value.fields) {
				if (!first) out.append(',')
				first = false
				writeJsonString(key, out)
				out.append(':')
				writeJson(item, out)
			}
			out.append('}')
		}
	}
}

fun JsonValue.toJsonString(): String = StringBuilder().also { writeJson(this, it) }.toString()

private fun writeJsonString(value: String, out: Appendable) {
	out.append('"')
	for (ch in value) {
		when {
			ch == '"' -> out.append("\\\"")
			ch == '\\' -> out.append("\\\\")
			ch == '\n' -> out.append("\\n")
			ch == '\r' -> out.append("\\r")
			ch == '\t' -> out.append("\\t")
			ch == '\b' -> out.append("\\b")
			ch == '\u000C' -> out.append("\\f")
			// Control characters are not legal raw inside a JSON string. Manga titles carry
			// all sorts of stray bytes, so this is not theoretical.
			ch < ' ' -> out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
			else -> out.append(ch)
		}
	}
	out.append('"')
}

/** Builds an object, dropping nothing: a null [JsonValue] entry is written as JSON null. */
fun jsonObject(vararg pairs: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*pairs))

fun jsonOf(value: String?): JsonValue = if (value == null) JsonNull else JsonString(value)

fun jsonOf(value: Long): JsonValue = JsonNumber(value)

fun jsonOf(value: Int): JsonValue = JsonNumber(value)

fun jsonOf(value: Float): JsonValue = JsonNumber(value)

fun jsonOf(value: Boolean): JsonValue = JsonBool(value)

fun jsonArrayOf(values: List<JsonValue>): JsonArray = JsonArray(values)

// endregion

// region parsing

/** Parses [text] as a single JSON value. Throws [BackupFormatException] on anything else. */
fun parseJson(text: String): JsonValue {
	val parser = JsonParser(text)
	val value = parser.parseValue()
	parser.skipWhitespace()
	if (!parser.atEnd()) {
		throw BackupFormatException("trailing content after the JSON value at offset ${parser.offset}")
	}
	return value
}

private class JsonParser(private val text: String) {

	var offset: Int = 0
		private set

	fun atEnd(): Boolean = offset >= text.length

	fun skipWhitespace() {
		while (offset < text.length && text[offset].isJsonWhitespace()) offset++
	}

	fun parseValue(): JsonValue {
		skipWhitespace()
		if (atEnd()) fail("unexpected end of input")
		return when (val ch = text[offset]) {
			'{' -> parseObject()
			'[' -> parseArray()
			'"' -> JsonString(parseString())
			't' -> literal("true", JsonBool(true))
			'f' -> literal("false", JsonBool(false))
			'n' -> literal("null", JsonNull)
			else -> if (ch == '-' || ch in '0'..'9') parseNumber() else fail("unexpected character '$ch'")
		}
	}

	private fun parseObject(): JsonObject {
		expect('{')
		// Insertion order is kept so a re-encode of a parsed file is byte-comparable, which
		// is what makes the round-trip test meaningful rather than order-dependent.
		val fields = LinkedHashMap<String, JsonValue>()
		skipWhitespace()
		if (peek() == '}') {
			offset++
			return JsonObject(fields)
		}
		while (true) {
			skipWhitespace()
			if (peek() != '"') fail("expected an object key")
			val key = parseString()
			skipWhitespace()
			expect(':')
			fields[key] = parseValue()
			skipWhitespace()
			when (peek()) {
				',' -> offset++
				'}' -> {
					offset++
					return JsonObject(fields)
				}

				else -> fail("expected ',' or '}' in object")
			}
		}
	}

	private fun parseArray(): JsonArray {
		expect('[')
		val items = ArrayList<JsonValue>()
		skipWhitespace()
		if (peek() == ']') {
			offset++
			return JsonArray(items)
		}
		while (true) {
			items += parseValue()
			skipWhitespace()
			when (peek()) {
				',' -> offset++
				']' -> {
					offset++
					return JsonArray(items)
				}

				else -> fail("expected ',' or ']' in array")
			}
		}
	}

	private fun parseString(): String {
		expect('"')
		val sb = StringBuilder()
		while (true) {
			if (atEnd()) fail("unterminated string")
			when (val ch = text[offset++]) {
				'"' -> return sb.toString()
				'\\' -> {
					if (atEnd()) fail("unterminated escape")
					when (val esc = text[offset++]) {
						'"' -> sb.append('"')
						'\\' -> sb.append('\\')
						'/' -> sb.append('/')
						'b' -> sb.append('\b')
						'f' -> sb.append('\u000C')
						'n' -> sb.append('\n')
						'r' -> sb.append('\r')
						't' -> sb.append('\t')
						'u' -> {
							if (offset + 4 > text.length) fail("truncated \\u escape")
							val hex = text.substring(offset, offset + 4)
							val code = hex.toIntOrNull(16) ?: fail("bad \\u escape \"$hex\"")
							offset += 4
							sb.append(code.toChar())
						}

						else -> fail("unknown escape '\\$esc'")
					}
				}

				else -> sb.append(ch)
			}
		}
	}

	private fun parseNumber(): JsonNumber {
		val start = offset
		if (peek() == '-') offset++
		while (!atEnd() && text[offset] in '0'..'9') offset++
		if (!atEnd() && text[offset] == '.') {
			offset++
			while (!atEnd() && text[offset] in '0'..'9') offset++
		}
		if (!atEnd() && (text[offset] == 'e' || text[offset] == 'E')) {
			offset++
			if (!atEnd() && (text[offset] == '+' || text[offset] == '-')) offset++
			while (!atEnd() && text[offset] in '0'..'9') offset++
		}
		val raw = text.substring(start, offset)
		if (raw.isEmpty() || raw == "-") fail("malformed number")
		return JsonNumber(raw)
	}

	private fun literal(word: String, value: JsonValue): JsonValue {
		if (!text.startsWith(word, offset)) fail("expected \"$word\"")
		offset += word.length
		return value
	}

	private fun peek(): Char = if (atEnd()) fail("unexpected end of input") else text[offset]

	private fun expect(ch: Char) {
		if (atEnd() || text[offset] != ch) fail("expected '$ch'")
		offset++
	}

	private fun fail(message: String): Nothing =
		throw BackupFormatException("$message at offset $offset")
}

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

// endregion
