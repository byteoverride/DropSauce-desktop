package org.koitharu.kotatsu.desktop.feature.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaChapter

/** The pure half of migration: which new chapter each old chapter becomes. */
class ChapterMatchingTest {

	@Test
	fun `chapters are matched by number, not by position`() {
		val old = listOf(chapter(11L, 1f), chapter(12L, 2f))
		val new = listOf(chapter(20L, 0.5f), chapter(21L, 1f), chapter(22L, 2f))

		assertEquals(mapOf(11L to 21L, 12L to 22L), mapChapterIds(old, new))
	}

	@Test
	fun `a numbered chapter with no numbered counterpart is dropped`() {
		val old = listOf(chapter(11L, 1f), chapter(12L, 2f), chapter(13L, 3f))
		val new = listOf(chapter(21L, 1f), chapter(23L, 3f))

		val mapped = mapChapterIds(old, new)

		assertEquals(mapOf(11L to 21L, 13L to 23L), mapped)
		// The point of the test: 12 must not become 23 just because 23 sits at index 1.
		assertNull(mapped[12L])
	}

	@Test
	fun `volume is part of the identity`() {
		val old = listOf(chapter(11L, 1f, volume = 1), chapter(12L, 1f, volume = 2))
		val new = listOf(chapter(21L, 1f, volume = 1), chapter(22L, 1f, volume = 2))

		assertEquals(mapOf(11L to 21L, 12L to 22L), mapChapterIds(old, new))
	}

	@Test
	fun `unnumbered chapters fall back to position`() {
		// Plenty of sources number nothing. Position is then the only signal there is.
		val old = listOf(chapter(11L, 0f), chapter(12L, 0f))
		val new = listOf(chapter(21L, 0f), chapter(22L, 0f))

		assertEquals(mapOf(11L to 21L, 12L to 22L), mapChapterIds(old, new))
	}

	@Test
	fun `an unnumbered chapter past the end of the new list is dropped`() {
		val old = listOf(chapter(11L, 0f), chapter(12L, 0f), chapter(13L, 0f))
		val new = listOf(chapter(21L, 0f), chapter(22L, 0f))

		assertEquals(mapOf(11L to 21L, 12L to 22L), mapChapterIds(old, new))
	}

	@Test
	fun `two old chapters never claim the same new chapter`() {
		// The unnumbered chapter at index 0 would otherwise land on the same new chapter
		// that the numbered one already matched.
		val old = listOf(chapter(11L, 0f), chapter(12L, 1f))
		val new = listOf(chapter(21L, 1f))

		val mapped = mapChapterIds(old, new)

		assertEquals(1, mapped.values.toSet().size)
		assertEquals(mapOf(11L to 21L), mapped)
	}

	@Test
	fun `an empty chapter list on either side maps nothing`() {
		assertEquals(emptyMap<Long, Long>(), mapChapterIds(emptyList(), listOf(chapter(1L, 1f))))
		assertEquals(emptyMap<Long, Long>(), mapChapterIds(listOf(chapter(1L, 1f)), emptyList()))
	}

	@Test
	fun `a reading position prefers an exact number match`() {
		val old = listOf(chapter(11L, 1f), chapter(12L, 2f))
		val new = listOf(chapter(20L, 0.5f), chapter(21L, 1f), chapter(22L, 2f))

		val match = checkNotNull(matchReadingPosition(old, new, oldChapterId = 12L, percent = 0.9f))

		assertEquals(22L, match.chapterId)
		assertTrue(match.byNumber)
	}

	@Test
	fun `a reading position with no number match is placed, not lost`() {
		// Losing the position entirely is the worst outcome of a migration, so unlike a
		// bookmark it falls back to the equivalent slot and says it did.
		val old = listOf(chapter(11L, 1f), chapter(12L, 2f), chapter(13L, 3f))
		val new = listOf(chapter(21L, 10f), chapter(22L, 11f), chapter(23L, 12f))

		val match = checkNotNull(matchReadingPosition(old, new, oldChapterId = 12L, percent = 0.5f))

		assertEquals(22L, match.chapterId)
		assertFalse(match.byNumber)
	}

	@Test
	fun `a reading position on an unknown chapter is placed by percent`() {
		val old = listOf(chapter(11L, 1f), chapter(12L, 2f), chapter(13L, 3f))
		val new = listOf(chapter(21L, 1f), chapter(22L, 2f), chapter(23L, 3f))

		// Chapter 99 is not in the old list at all: the old source reorganised.
		val match = checkNotNull(matchReadingPosition(old, new, oldChapterId = 99L, percent = 1f))

		assertEquals(23L, match.chapterId)
		assertTrue(match.byNumber)
	}

	@Test
	fun `with no old chapter list the position comes from percent alone`() {
		// The dead-source case: the old chapter list could not be loaded at all.
		val new = listOf(chapter(21L, 1f), chapter(22L, 2f), chapter(23L, 3f))

		assertEquals(21L, checkNotNull(matchReadingPosition(emptyList(), new, 5L, 0f)).chapterId)
		assertEquals(23L, checkNotNull(matchReadingPosition(emptyList(), new, 5L, 1f)).chapterId)
		assertFalse(checkNotNull(matchReadingPosition(emptyList(), new, 5L, 0.5f)).byNumber)
	}

	@Test
	fun `a new source with no chapters has nowhere to put the position`() {
		val old: List<MangaChapter> = listOf(chapter(11L, 1f))
		assertNull(matchReadingPosition(old, emptyList(), 11L, 0.5f))
	}
}
