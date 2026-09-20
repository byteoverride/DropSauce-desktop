package org.koitharu.kotatsu.desktop.feature.discover

/**
 * A small JSON reader and writer, used only by this feature's own state file.
 *
 * Hand-rolled because kotlinx.serialization is an `implementation` dependency of
 * `:shared` and so is not on the `:desktop` compile classpath, and pulling a new
 * dependency in for one small file is not this area's call to make. The only JSON this
 * ever sees is JSON the app itself wrote, so the grammar below (object, array, string,
 * number, boolean, null) is the whole of what it has to handle; anything else is treated
 * as a corrupt file rather than something to recover.
 */
internal sealed interface JsonValue {

	data class Str(val value: String) : JsonValue

	data class Num(val value: Double) : JsonValue

	data class Bool(val value: Boolean) : JsonValue

	data object Null : JsonValue

	data class Arr(val items: List<JsonValue>) : JsonValue

	data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

/** Thrown for any input this reader will not accept. Callers treat it as "no file". */
internal class JsonException(message: String) : Exception(message)

internal fun JsonValue.asObject(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.fields

internal fun JsonValue.asArray(): List<JsonValue>? = (this as? JsonValue.Arr)?.items

internal fun JsonValue.asString(): String? = (this as? JsonValue.Str)?.value

internal fun JsonValue.asInt(): Int? = (this as? JsonValue.Num)?.value?.toInt()

/** Every string in this array that is a string, skipping anything that is not. */
internal fun JsonValue?.asStringList(): List<String> =
	this?.asArray()?.mapNotNull { it.asString() }.orEmpty()

internal fun jsonObject(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
	JsonValue.Obj(linkedMapOf(*fields))

internal fun jsonStrings(values: Iterable<String>): JsonValue.Arr =
	JsonValue.Arr(values.map { JsonValue.Str(it) })

/**
 * Renders [value] as indented JSON.
 *
 * Indented rather than compact because this lands in the user's config directory, where
 * being able to open it and see what the app remembered is worth a few bytes.
 */
internal fun writeJson(value: JsonValue): String = StringBuilder().also { write(it, value, 0) }.toString()

private fun write(out: StringBuilder, value: JsonValue, depth: Int) {
	when (value) {
		is JsonValue.Null -> out.append("null")
		is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
		is JsonValue.Num -> {
			val d = value.value
			// Whole numbers are written without a fractional part; everything this
			// feature stores is a count or a timestamp and "3.0" would only confuse.
			if (d == d.toLong().toDouble()) out.append(d.toLong()) else out.append(d)
		}

		is JsonValue.Str -> writeString(out, value.value)
		is JsonValue.Arr -> if (value.items.isEmpty()) {
			out.append("[]")
		} else {
			out.append("[\n")
			value.items.forEachIndexed { index, item ->
				indent(out, depth + 1)
				write(out, item, depth + 1)
				if (index != value.items.lastIndex) out.append(',')
				out.append('\n')
			}
			indent(out, depth)
			out.append(']')
		}

		is JsonValue.Obj -> if (value.fields.isEmpty()) {
			out.append("{}")
		} else {
			out.append("{\n")
			val entries = value.fields.entries.toList()
			entries.forEachIndexed { index, (key, item) ->
				indent(out, depth + 1)
				writeString(out, key)
				out.append(": ")
				write(out, item, depth + 1)
				if (index != entries.lastIndex) out.append(',')
				out.append('\n')
			}
			indent(out, depth)
			out.append('}')
		}
	}
}

private fun indent(out: StringBuilder, depth: Int) {
	repeat(depth) { out.append("  ") }
}

private fun writeString(out: StringBuilder, value: String) {
	out.append('"')
	for (c in value) {
		when {
			c == '"' -> out.append("\\\"")
			c == '\\' -> out.append("\\\\")
			c == '\n' -> out.append("\\n")
			c == '\r' -> out.append("\\r")
			c == '\t' -> out.append("\\t")
			// Control characters are the only ones JSON requires to be escaped; the rest
			// go out as UTF-8, which matters because queries are frequently not Latin.
			c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
			else -> out.append(c)
		}
	}
	out.append('"')
}

/** Parses [text], or throws [JsonException]. */
internal fun parseJson(text: String): JsonValue {
	val reader = JsonReader(text)
	val value = reader.readValue()
	reader.skipWhitespace()
	if (!reader.isAtEnd) throw JsonException("trailing content at ${reader.position}")
	return value
}

private class JsonReader(private val text: String) {

	var position = 0
		private set

	val isAtEnd: Boolean get() = position >= text.length

	fun skipWhitespace() {
		while (position < text.length && text[position].isWhitespace()) position++
	}

	fun readValue(): JsonValue {
		skipWhitespace()
		if (isAtEnd) throw JsonException("unexpected end of input")
		return when (val c = text[position]) {
			'{' -> readObject()
			'[' -> readArray()
			'"' -> JsonValue.Str(readString())
			't' -> {
				readLiteral("true")
				JsonValue.Bool(true)
			}

			'f' -> {
				readLiteral("false")
				JsonValue.Bool(false)
			}

			'n' -> {
				readLiteral("null")
				JsonValue.Null
			}
			else -> if (c == '-' || c in '0'..'9') readNumber() else throw JsonException("unexpected '$c' at $position")
		}
	}

	private fun readLiteral(literal: String) {
		if (!text.startsWith(literal, position)) throw JsonException("expected $literal at $position")
		position += literal.length
	}

	private fun readNumber(): JsonValue.Num {
		val start = position
		if (position < text.length && text[position] == '-') position++
		while (position < text.length && (text[position] in '0'..'9' || text[position] in ".eE+-")) position++
		val slice = text.substring(start, position)
		val parsed = slice.toDoubleOrNull() ?: throw JsonException("bad number '$slice' at $start")
		return JsonValue.Num(parsed)
	}

	private fun readString(): String {
		expect('"')
		val out = StringBuilder()
		while (true) {
			if (isAtEnd) throw JsonException("unterminated string")
			when (val c = text[position++]) {
				'"' -> return out.toString()
				'\\' -> out.append(readEscape())
				else -> out.append(c)
			}
		}
	}

	private fun readEscape(): Char {
		if (isAtEnd) throw JsonException("unterminated escape")
		return when (val c = text[position++]) {
			'"', '\\', '/' -> c
			'b' -> '\b'
			'f' -> '\u000C'
			'n' -> '\n'
			'r' -> '\r'
			't' -> '\t'
			'u' -> {
				if (position + 4 > text.length) throw JsonException("truncated \\u escape at $position")
				val hex = text.substring(position, position + 4)
				position += 4
				hex.toIntOrNull(16)?.toChar() ?: throw JsonException("bad \\u escape '$hex'")
			}

			else -> throw JsonException("unknown escape '\\$c' at ${position - 1}")
		}
	}

	private fun readArray(): JsonValue.Arr {
		expect('[')
		val items = mutableListOf<JsonValue>()
		skipWhitespace()
		if (peek() == ']') {
			position++
			return JsonValue.Arr(items)
		}
		while (true) {
			items += readValue()
			skipWhitespace()
			when (val c = next()) {
				',' -> Unit
				']' -> return JsonValue.Arr(items)
				else -> throw JsonException("expected ',' or ']' but found '$c' at ${position - 1}")
			}
		}
	}

	private fun readObject(): JsonValue.Obj {
		expect('{')
		val fields = LinkedHashMap<String, JsonValue>()
		skipWhitespace()
		if (peek() == '}') {
			position++
			return JsonValue.Obj(fields)
		}
		while (true) {
			skipWhitespace()
			val key = readString()
			skipWhitespace()
			expect(':')
			fields[key] = readValue()
			skipWhitespace()
			when (val c = next()) {
				',' -> Unit
				'}' -> return JsonValue.Obj(fields)
				else -> throw JsonException("expected ',' or '}' but found '$c' at ${position - 1}")
			}
		}
	}

	private fun peek(): Char? = if (isAtEnd) null else text[position]

	private fun next(): Char {
		if (isAtEnd) throw JsonException("unexpected end of input")
		return text[position++]
	}

	private fun expect(c: Char) {
		if (peek() != c) throw JsonException("expected '$c' at $position")
		position++
	}
}
