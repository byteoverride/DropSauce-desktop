package org.koitharu.kotatsu.desktop.feature.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparator on its own, with a positive control.
 *
 * The control matters: the whole point of this comparator is that it disagrees with
 * `String.compareTo`, so every case here first proves plain sorting gets it wrong.
 */
class NaturalOrderTest {

	@Test
	fun `digit runs compare as numbers`() {
		val input = listOf("page10.png", "page1.png", "page2.png", "page21.png", "page3.png")

		assertEquals(
			"plain sorting must get this wrong, or the test proves nothing",
			listOf("page1.png", "page10.png", "page2.png", "page21.png", "page3.png"),
			input.sorted(),
		)
		assertEquals(
			listOf("page1.png", "page2.png", "page3.png", "page10.png", "page21.png"),
			input.sortedWith(NaturalOrder),
		)
	}

	@Test
	fun `leading zeros do not change a number's value`() {
		assertEquals(0, NaturalOrder.compare("p007", "p007"))
		assertTrue(NaturalOrder.compare("p007", "p8") < 0)
		assertTrue(NaturalOrder.compare("p0010", "p9") > 0)
	}

	@Test
	fun `equal values still order deterministically`() {
		// "1" and "01" are the same number; they must not compare equal, or a sort would
		// be free to interleave them differently on each run.
		val cmp = NaturalOrder.compare("p1.png", "p01.png")
		assertTrue("distinct names must not collapse to equal", cmp != 0)
		assertEquals(-cmp.coerceIn(-1, 1), NaturalOrder.compare("p01.png", "p1.png").coerceIn(-1, 1))
	}

	@Test
	fun `case does not decide the order, only the final tiebreak`() {
		val input = listOf("chapter 9.png", "Chapter 10.png")

		assertEquals(
			"plain sorting must get this wrong, or the test proves nothing",
			listOf("Chapter 10.png", "chapter 9.png"),
			input.sorted(),
		)
		assertEquals(
			listOf("chapter 9.png", "Chapter 10.png"),
			input.sortedWith(NaturalOrder),
		)
		assertTrue(NaturalOrder.compare("a9", "B2") < 0)
		assertEquals(0, NaturalOrder.compare("Chapter 4", "Chapter 4"))
		// Names differing only in case are ordered, not merged: returning 0 here would
		// let two real entries take an arbitrary order relative to each other.
		assertTrue(NaturalOrder.compare("Chapter 4", "chapter 4") != 0)
	}

	@Test
	fun `numbers sort after the prefix that contains them`() {
		val input = listOf("ch2/page1.png", "ch10/page1.png", "ch2/page10.png", "ch2/page2.png")

		assertEquals(
			listOf("ch2/page1.png", "ch2/page2.png", "ch2/page10.png", "ch10/page1.png"),
			input.sortedWith(NaturalOrder),
		)
	}

	@Test
	fun `a longer name sorts after the prefix it extends`() {
		assertTrue(NaturalOrder.compare("page", "page1") < 0)
		assertTrue(NaturalOrder.compare("page1", "page") > 0)
	}
}
