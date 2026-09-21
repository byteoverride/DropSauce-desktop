package org.koitharu.kotatsu.desktop.feature.readerx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation and re-pairing, without rendering anything.
 *
 * [DoublePageState] holds Compose snapshot state but has no composition of its own, so
 * all of this is reachable from a plain unit test. What is not reachable this way is the
 * layout itself: that two halves really do come out side by side, and that the image
 * loader reports a decoded size back here, are only observable in a running window.
 */
class DoublePageStateTest {

	private val portrait = PageSize(800, 1200)
	private val spread = PageSize(1600, 1200)

	@Test
	fun `a fresh state starts on the first screenful`() {
		val state = DoublePageState(pageCount = 6, coverFirst = false)
		assertEquals(0, state.index)
		assertEquals(Spread.Pair(0, 1), state.current)
	}

	@Test
	fun `next and previous walk the screenfuls and stop at the ends`() {
		val state = DoublePageState(pageCount = 4, coverFirst = false)
		assertFalse(state.previous())
		assertTrue(state.next())
		assertEquals(Spread.Pair(2, 3), state.current)
		assertFalse("there is no third screenful", state.next())
		assertTrue(state.previous())
		assertEquals(Spread.Pair(0, 1), state.current)
	}

	@Test
	fun `showPage moves to the screenful containing that page`() {
		val state = DoublePageState(pageCount = 6, coverFirst = true)
		state.showPage(4)
		assertEquals(Spread.Pair(3, 4), state.current)
		// Clamped rather than throwing: a resumed position can outlive the page count.
		state.showPage(99)
		assertEquals(Spread.Single(5), state.current)
	}

	@Test
	fun `learning that a page is a spread re-pairs without stranding the reader`() {
		val state = DoublePageState(pageCount = 6, coverFirst = false)
		state.showPage(2)
		assertEquals(Spread.Pair(2, 3), state.current)
		// Page 1 decodes and turns out to be drawn across the gutter. Everything after
		// it shifts by one. The reader is still looking at page 2, which is the point of
		// anchoring on a page number rather than on a screenful number.
		state.onDecoded(1, spread)
		assertEquals(2, state.anchorPage)
		assertEquals(Spread.Pair(2, 3), state.current)
		assertEquals(
			listOf(Spread.Single(0), Spread.Single(1), Spread.Pair(2, 3), Spread.Pair(4, 5)),
			state.spreads,
		)
	}

	@Test
	fun `a decoded size is remembered`() {
		val state = DoublePageState(pageCount = 3)
		assertEquals(null, state.sizeOf(1))
		state.onDecoded(1, portrait)
		assertEquals(portrait, state.sizeOf(1))
		// Out of range reports are ignored rather than growing the list.
		state.onDecoded(99, portrait)
		assertEquals(3, state.pageCount)
	}

	@Test
	fun `flipping the cover toggle re-pairs the whole book`() {
		val state = DoublePageState(pageCount = 5, coverFirst = false)
		assertEquals(Spread.Pair(0, 1), state.spreads.first())
		state.coverFirst = true
		assertEquals(Spread.Single(0), state.spreads.first())
		assertEquals(Spread.Pair(1, 2), state.spreads[1])
	}

	@Test
	fun `right to left mirrors the pairs but not the page order`() {
		val state = DoublePageState(pageCount = 4, coverFirst = false, isRtl = true)
		assertEquals(Spread.Pair(left = 1, right = 0), state.current)
		assertTrue(state.next())
		assertEquals(Spread.Pair(left = 3, right = 2), state.current)
		assertEquals(2, state.anchorPage)
	}

	@Test
	fun `an empty chapter has nothing to show and cannot be navigated`() {
		val state = DoublePageState(pageCount = 0)
		assertEquals(null, state.current)
		assertFalse(state.next())
		assertFalse(state.previous())
		state.showPage(3)
		assertEquals(0, state.anchorPage)
	}
}
