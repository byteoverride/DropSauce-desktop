package org.koitharu.kotatsu.desktop.feature.readerx

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pairing is the part of double-page mode that is genuinely hard, and it is entirely
 * decidable from a list of page shapes, so every case is checked here rather than by
 * looking at a rendered window.
 */
class PagePairingTest {

	private val portrait = PageSize(800, 1200)
	private val spread = PageSize(1600, 1200)
	private val square = PageSize(1000, 1000)

	private fun portraits(count: Int) = List<PageSize?>(count) { portrait }

	@Test
	fun `an even run of portrait pages pairs up completely`() {
		val result = pairPages(portraits(6))
		assertEquals(
			listOf(Spread.Pair(0, 1), Spread.Pair(2, 3), Spread.Pair(4, 5)),
			result,
		)
	}

	@Test
	fun `an odd run leaves the last page on its own`() {
		val result = pairPages(portraits(5))
		assertEquals(
			listOf(Spread.Pair(0, 1), Spread.Pair(2, 3), Spread.Single(4)),
			result,
		)
	}

	@Test
	fun `cover first puts page one alone and shifts every pair after it`() {
		// The whole reason the toggle exists: with it on, 1 faces 2 rather than 0 facing 1.
		val result = pairPages(portraits(5), coverFirst = true)
		assertEquals(
			listOf(Spread.Single(0), Spread.Pair(1, 2), Spread.Pair(3, 4)),
			result,
		)
	}

	@Test
	fun `cover first off pairs from page zero`() {
		assertEquals(
			listOf(Spread.Pair(0, 1), Spread.Pair(2, 3)),
			pairPages(portraits(4), coverFirst = false),
		)
	}

	@Test
	fun `cover first on an even book leaves the last page alone`() {
		assertEquals(
			listOf(Spread.Single(0), Spread.Pair(1, 2), Spread.Pair(3, 4), Spread.Single(5)),
			pairPages(portraits(6), coverFirst = true),
		)
	}

	@Test
	fun `a wide page takes the whole width`() {
		val sizes = listOf<PageSize?>(portrait, portrait, spread, portrait, portrait)
		assertEquals(
			listOf(Spread.Pair(0, 1), Spread.Single(2), Spread.Pair(3, 4)),
			pairPages(sizes),
		)
	}

	@Test
	fun `a page facing a spread is left alone rather than paired with it`() {
		// Page 0 has no valid partner: page 1 must stand alone, so 0 does too.
		val sizes = listOf<PageSize?>(portrait, spread, portrait, portrait)
		assertEquals(
			listOf(Spread.Single(0), Spread.Single(1), Spread.Pair(2, 3)),
			pairPages(sizes),
		)
	}

	@Test
	fun `a spread re-pairs everything after it, which is why sizes drive the layout`() {
		val before = pairPages(portraits(4))
		val after = pairPages(listOf<PageSize?>(portrait, spread, portrait, portrait))
		assertEquals(listOf(Spread.Pair(0, 1), Spread.Pair(2, 3)), before)
		assertEquals(listOf(Spread.Single(0), Spread.Single(1), Spread.Pair(2, 3)), after)
	}

	@Test
	fun `a square page is not treated as a spread`() {
		// Squeezing a 1 to 1 page into half the width is tight but readable. A 4 to 3
		// landscape page is not, and that is where the line has to sit.
		assertEquals(
			listOf(Spread.Pair(0, 1)),
			pairPages(listOf<PageSize?>(square, square)),
		)
	}

	@Test
	fun `right to left swaps the halves of each pair without reversing the order`() {
		val result = pairPages(portraits(4), isRtl = true)
		assertEquals(
			listOf(Spread.Pair(left = 1, right = 0), Spread.Pair(left = 3, right = 2)),
			result,
		)
		// Still read front to back: the first screenful is still the first two pages.
		assertEquals(listOf(0, 1), result.first().pages)
	}

	@Test
	fun `right to left with a cover keeps the cover alone and mirrors the rest`() {
		assertEquals(
			listOf(Spread.Single(0), Spread.Pair(left = 2, right = 1), Spread.Pair(left = 4, right = 3)),
			pairPages(portraits(5), coverFirst = true, isRtl = true),
		)
	}

	@Test
	fun `a page whose size is unknown is assumed portrait and still pairs`() {
		// Sizes only arrive on decode. Leaving undecoded pages unpaired would make every
		// fresh chapter open as a run of single pages that then reflow.
		assertEquals(
			listOf(Spread.Pair(0, 1), Spread.Pair(2, 3)),
			pairPages(listOf(null, null, null, null)),
		)
	}

	@Test
	fun `an empty chapter produces no spreads`() {
		assertEquals(emptyList<Spread>(), pairPages(emptyList()))
	}

	@Test
	fun `a single page chapter is one single`() {
		assertEquals(listOf(Spread.Single(0)), pairPages(portraits(1)))
	}

	@Test
	fun `every page appears exactly once, in order`() {
		val sizes = listOf<PageSize?>(portrait, portrait, spread, null, portrait, portrait, portrait)
		for (cover in listOf(false, true)) {
			for (rtl in listOf(false, true)) {
				val flattened = pairPages(sizes, cover, rtl).flatMap { it.pages }
				assertEquals("cover=$cover rtl=$rtl", sizes.indices.toList(), flattened)
			}
		}
	}

	@Test
	fun `spreadIndexOfPage finds the screenful showing a page`() {
		val spreads = pairPages(portraits(5), coverFirst = true)
		assertEquals(0, spreadIndexOfPage(spreads, 0))
		assertEquals(1, spreadIndexOfPage(spreads, 1))
		assertEquals(1, spreadIndexOfPage(spreads, 2))
		assertEquals(2, spreadIndexOfPage(spreads, 4))
		assertEquals(-1, spreadIndexOfPage(spreads, 9))
	}

	@Test
	fun `spreadIndexOfPage works for a mirrored pair`() {
		val spreads = pairPages(portraits(4), isRtl = true)
		assertEquals(0, spreadIndexOfPage(spreads, 1))
		assertEquals(1, spreadIndexOfPage(spreads, 2))
	}
}
