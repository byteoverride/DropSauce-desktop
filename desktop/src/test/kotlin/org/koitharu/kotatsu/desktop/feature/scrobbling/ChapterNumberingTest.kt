package org.koitharu.kotatsu.desktop.feature.scrobbling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one piece of arithmetic in this area, and the one most likely to be wrong quietly.
 *
 * A wrong number here is not a crash, it is a tracker told the user has read something
 * they have not, which they then have to fix by hand on a website.
 */
class ChapterNumberingTest {

	@Test
	fun `an ordinary chapter reports its own number`() {
		val chapters = listOf(chapter(1L, 1f), chapter(2L, 2f), chapter(3L, 12f))

		assertEquals(12, trackerChapterNumber(chapters, 3L))
		assertEquals(1, trackerChapterNumber(chapters, 1L))
	}

	@Test
	fun `a decimal chapter truncates down`() {
		val chapters = listOf(chapter(1L, 10f), chapter(2L, 10.5f), chapter(3L, 11f))

		// Finishing 10.5 means ten whole chapters are behind the reader, not eleven.
		// Rounding up would mark chapter 11 read before it has been opened.
		assertEquals(10, trackerChapterNumber(chapters, 2L))
		assertEquals(11, trackerChapterNumber(chapters, 3L))
	}

	@Test
	fun `a chapter numbered zero falls back to its position`() {
		val chapters = listOf(chapter(1L, 0f), chapter(2L, 0f), chapter(3L, 0f))

		// A source that numbers nothing leaves position as the only signal. Truncating
		// zero would push "0 chapters read" and undo real progress.
		assertEquals(1, trackerChapterNumber(chapters, 1L))
		assertEquals(2, trackerChapterNumber(chapters, 2L))
		assertEquals(3, trackerChapterNumber(chapters, 3L))
	}

	@Test
	fun `a decimal below one also falls back to position`() {
		val chapters = listOf(chapter(1L, 0.5f), chapter(2L, 1f), chapter(3L, 2f))

		// floor(0.5) is 0, which a tracker reads as "nothing read".
		assertEquals(1, trackerChapterNumber(chapters, 1L))
		assertEquals(1, trackerChapterNumber(chapters, 2L))
	}

	@Test
	fun `the position fallback counts only the chapter's own branch`() {
		val chapters = listOf(
			chapter(1L, 0f, branch = "Group A"),
			chapter(2L, 0f, branch = "Group B"),
			chapter(3L, 0f, branch = "Group A"),
			chapter(4L, 0f, branch = "Group B"),
			chapter(5L, 0f, branch = "Group A"),
		)

		// Counting the raw list index would report 5 for a reader three chapters into
		// one of two interleaved scanlation branches.
		assertEquals(3, trackerChapterNumber(chapters, 5L))
		assertEquals(2, trackerChapterNumber(chapters, 4L))
	}

	@Test
	fun `a chapter that is not in the list reports nothing to push`() {
		val chapters = listOf(chapter(1L, 1f))

		assertNull(trackerChapterNumber(chapters, 99L))
		assertNull(trackerChapterNumber(emptyList(), 1L))
	}
}
