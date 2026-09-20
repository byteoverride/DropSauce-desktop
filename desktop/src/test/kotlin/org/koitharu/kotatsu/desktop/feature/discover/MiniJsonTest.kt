package org.koitharu.kotatsu.desktop.feature.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader only ever sees files this writer produced, so the contract worth proving is
 * that the pair round-trips and that a damaged file fails loudly enough for the store to
 * catch it, rather than quietly returning something wrong.
 */
class MiniJsonTest {

	@Test
	fun `what the writer writes the reader reads`() {
		val value = jsonObject(
			"version" to JsonValue.Num(1.0),
			"queries" to jsonStrings(listOf("a", "b")),
			"nested" to JsonValue.Obj(mapOf("on" to JsonValue.Bool(true), "off" to JsonValue.Null)),
			"empty" to JsonValue.Arr(emptyList()),
		)
		assertEquals(value, parseJson(writeJson(value)))
	}

	@Test
	fun `strings that need escaping survive`() {
		val awkward = "quote \" backslash \\ newline \n tab \t slash / control \u0001"
		val parsed = parseJson(writeJson(jsonStrings(listOf(awkward))))
		assertEquals(listOf(awkward), parsed.asArray()?.map { it.asString() })
	}

	@Test
	fun `non latin text is not escaped away`() {
		val text = "鋼の錬金術師 ✦ Привет"
		assertEquals(text, parseJson(writeJson(JsonValue.Str(text))).asString())
	}

	@Test
	fun `whole numbers are written without a decimal point`() {
		assertEquals("1", writeJson(JsonValue.Num(1.0)))
		assertEquals("1.5", writeJson(JsonValue.Num(1.5)))
	}

	@Test
	fun `escapes written by something else are still understood`() {
		val parsed = parseJson("""{"a": "A\/\b\f"}""")
		assertEquals("A/\b\u000C", parsed.asObject()?.get("a")?.asString())
	}

	@Test
	fun `whitespace between tokens is allowed`() {
		val parsed = parseJson("  {\n\t\"a\" : [ 1 , 2 ]\n}  ")
		assertEquals(listOf(1, 2), parsed.asObject()?.get("a")?.asArray()?.map { it.asInt() })
	}

	@Test
	fun `empty containers round trip`() {
		assertEquals("{}", writeJson(JsonValue.Obj(emptyMap())))
		assertEquals("[]", writeJson(JsonValue.Arr(emptyList())))
		assertTrue(parseJson("{}").asObject()?.isEmpty() == true)
		assertTrue(parseJson("[]").asArray()?.isEmpty() == true)
	}

	@Test
	fun `a truncated document is rejected rather than half read`() {
		assertThrows(JsonException::class.java) { parseJson("""{"a": ["b",""") }
		assertThrows(JsonException::class.java) { parseJson("""{"a": "unterminated""") }
		assertThrows(JsonException::class.java) { parseJson("") }
	}

	@Test
	fun `trailing content is rejected`() {
		assertThrows(JsonException::class.java) { parseJson("""{"a": 1} {"b": 2}""") }
	}

	@Test
	fun `nonsense is rejected`() {
		assertThrows(JsonException::class.java) { parseJson("not json") }
		assertThrows(JsonException::class.java) { parseJson("""{"a" 1}""") }
		assertThrows(JsonException::class.java) { parseJson("""{"a": tru}""") }
	}

	@Test
	fun `accessors return null for the wrong shape instead of throwing`() {
		val value = parseJson("""{"a": 1}""")
		assertNull(value.asArray())
		assertNull(value.asString())
		assertEquals(emptyList<String>(), value.asObject()?.get("a").asStringList())
	}
}
