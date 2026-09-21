package org.koitharu.kotatsu.desktop.feature.curate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selection rules, with no database and no composition in the way. */
class SelectionTest {

	private val visible = listOf(1L, 2L, 3L, 4L)

	@Test
	fun `select and deselect one id`() {
		val selection = Selection.EMPTY.select(3L)
		assertTrue(3L in selection)
		assertEquals(1, selection.size)
		assertFalse(3L in selection.deselect(3L))
		assertTrue(selection.deselect(3L).isEmpty)
	}

	@Test
	fun `selecting the same id twice changes nothing`() {
		val once = Selection.EMPTY.select(3L)
		val twice = once.select(3L)
		assertEquals(once, twice)
		// Identity, not just equality: a new instance would restart every remember() keyed
		// on the selection and rebuild the whole grid on a no-op click.
		assertSame(once, twice)
	}

	@Test
	fun `toggle flips one id`() {
		val selection = Selection.EMPTY.toggle(2L)
		assertTrue(2L in selection)
		assertFalse(2L in selection.toggle(2L))
	}

	@Test
	fun `select-all adds everything visible and keeps what was already there`() {
		val selection = Selection(setOf(99L)).selectAll(visible)
		assertEquals(setOf(99L, 1L, 2L, 3L, 4L), selection.ids)
	}

	@Test
	fun `select-all over an empty list is a no-op`() {
		val selection = Selection(setOf(7L))
		assertSame(selection, selection.selectAll(emptyList()))
	}

	@Test
	fun `invert flips the visible ids only`() {
		// 9 was selected under some other filter and is not on screen. Invert is a
		// statement about what the user can see, so it must survive.
		val selection = Selection(setOf(1L, 3L, 9L)).invert(visible)
		assertEquals(setOf(2L, 4L, 9L), selection.ids)
	}

	@Test
	fun `invert twice returns to the starting selection`() {
		val start = Selection(setOf(1L, 3L))
		assertEquals(start, start.invert(visible).invert(visible))
	}

	@Test
	fun `clear empties the selection`() {
		assertTrue(Selection(setOf(1L, 2L)).clear().isEmpty)
		val empty = Selection.EMPTY
		assertSame(empty, empty.clear())
	}

	@Test
	fun `a selection survives the underlying list changing under it`() {
		var selection = Selection.EMPTY.selectAll(visible)
		// Two titles were removed by another screen while this one held a selection, and
		// a title the user has never seen appeared.
		val afterChange = listOf(1L, 4L, 5L)
		selection = selection.retaining(afterChange)
		assertEquals(setOf(1L, 4L), selection.ids)
		// The new title is not swept into the selection by the reconciliation.
		assertFalse(5L in selection)
	}

	@Test
	fun `reconciling against an unchanged list leaves the selection alone`() {
		val selection = Selection(setOf(1L, 2L))
		assertSame(selection, selection.retaining(visible))
	}

	@Test
	fun `reconciling against an empty library clears everything`() {
		assertTrue(Selection(setOf(1L, 2L)).retaining(emptyList()).isEmpty)
	}

	@Test
	fun `ordering follows the list, not the set`() {
		val selection = Selection(setOf(4L, 1L, 3L))
		assertEquals(listOf(1L, 3L, 4L), selection.orderedBy(visible))
		assertEquals(listOf(4L, 3L, 1L), selection.orderedBy(visible.reversed()))
	}
}
