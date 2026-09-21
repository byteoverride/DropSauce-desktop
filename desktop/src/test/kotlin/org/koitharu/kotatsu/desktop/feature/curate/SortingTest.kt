package org.koitharu.kotatsu.desktop.feature.curate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sorting, including what happens to ties.
 *
 * Every key is tested for stability with a deliberately equal value, because the bug
 * this guards against is invisible in a normal library: a comparator with a hidden
 * secondary sort reads correctly and quietly reshuffles rows the user had arranged.
 */
class SortingTest {

	private fun ids(items: List<CurateItem>) = items.map { it.id }

	@Test
	fun `by title, ignoring case`() {
		val items = listOf(
			testItem(1L, "zebra"),
			testItem(2L, "Apple"),
			testItem(3L, "banana"),
		)
		assertEquals(listOf(2L, 3L, 1L), ids(items.sortedByKey(LibrarySortKey.TITLE)))
		assertEquals(listOf(1L, 3L, 2L), ids(items.sortedByKey(LibrarySortKey.TITLE, descending = true)))
	}

	@Test
	fun `by recently added, newest first`() {
		val items = listOf(
			testItem(1L, "Old", addedAt = 100L),
			testItem(2L, "New", addedAt = 900L),
			testItem(3L, "Middle", addedAt = 500L),
		)
		assertEquals(listOf(2L, 3L, 1L), ids(items.sortedByKey(LibrarySortKey.RECENTLY_ADDED)))
	}

	@Test
	fun `by recently read, with never-read titles last`() {
		val items = listOf(
			testItem(1L, "Never"),
			testItem(2L, "Yesterday", lastReadAt = 200L),
			testItem(3L, "Today", lastReadAt = 900L),
		)
		assertEquals(listOf(3L, 2L, 1L), ids(items.sortedByKey(LibrarySortKey.RECENTLY_READ)))
	}

	@Test
	fun `by progress, furthest first`() {
		val items = listOf(
			testItem(1L, "Started", progress = 0.1f),
			testItem(2L, "Finished", progress = 1f),
			testItem(3L, "Untouched"),
		)
		assertEquals(listOf(2L, 1L, 3L), ids(items.sortedByKey(LibrarySortKey.PROGRESS)))
	}

	@Test
	fun `by chapter count, longest first`() {
		val items = listOf(
			testItem(1L, "Short", chaptersCount = 8),
			testItem(2L, "Epic", chaptersCount = 1_100),
			testItem(3L, "Unknown", chaptersCount = 0),
		)
		assertEquals(listOf(2L, 1L, 3L), ids(items.sortedByKey(LibrarySortKey.CHAPTER_COUNT)))
	}

	@Test
	fun `every key is stable when values tie`() {
		val tied = listOf(
			testItem(5L, "Same", chaptersCount = 3, addedAt = 10L, lastReadAt = 10L, progress = 0.5f),
			testItem(3L, "same", chaptersCount = 3, addedAt = 10L, lastReadAt = 10L, progress = 0.5f),
			testItem(9L, "SAME", chaptersCount = 3, addedAt = 10L, lastReadAt = 10L, progress = 0.5f),
		)
		for (key in LibrarySortKey.entries) {
			assertEquals("$key reordered ties", listOf(5L, 3L, 9L), ids(tied.sortedByKey(key)))
		}
	}

	@Test
	fun `reversing keeps ties in their original order`() {
		// The reason sorting reverses the comparator instead of the result: a reversed
		// list would put tied rows in the opposite order from the one the user just saw.
		val items = listOf(
			testItem(1L, "A", addedAt = 10L),
			testItem(2L, "B", addedAt = 10L),
			testItem(3L, "C", addedAt = 99L),
		)
		assertEquals(
			listOf(1L, 2L, 3L),
			ids(items.sortedByKey(LibrarySortKey.RECENTLY_ADDED, descending = true)),
		)
	}

	@Test
	fun `sorting an empty or single-item list is safe`() {
		assertEquals(emptyList<Long>(), ids(emptyList<CurateItem>().sortedByKey(LibrarySortKey.TITLE)))
		val one = listOf(testItem(1L, "Only"))
		assertEquals(listOf(1L), ids(one.sortedByKey(LibrarySortKey.PROGRESS)))
	}
}
