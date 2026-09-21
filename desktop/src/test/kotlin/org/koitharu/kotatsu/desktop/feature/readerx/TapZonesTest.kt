package org.koitharu.kotatsu.desktop.feature.readerx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapZonesTest {

	private val width = 900f
	private val height = 600f

	/** The centre of the cell at [row], [column] in a 900 by 600 surface. */
	private fun centreOf(row: Int, column: Int): Pair<Float, Float> =
		(width / 3f) * (column + 0.5f) to (height / 3f) * (row + 0.5f)

	@Test
	fun `every one of the nine regions is hit by a point inside it`() {
		for (zone in TapZone.entries) {
			val (x, y) = centreOf(zone.row, zone.column)
			assertEquals(zone.name, zone, tapZoneAt(x, y, width, height))
		}
	}

	@Test
	fun `the corners of the surface land in the corner regions`() {
		assertEquals(TapZone.TOP_LEFT, tapZoneAt(0f, 0f, width, height))
		// The far edge is inclusive. A fraction of exactly 1.0 must not index a fourth
		// column, which is what an unclamped truncation would do.
		assertEquals(TapZone.TOP_RIGHT, tapZoneAt(width, 0f, width, height))
		assertEquals(TapZone.BOTTOM_LEFT, tapZoneAt(0f, height, width, height))
		assertEquals(TapZone.BOTTOM_RIGHT, tapZoneAt(width, height, width, height))
	}

	@Test
	fun `a point outside the surface is clamped rather than dropped`() {
		assertEquals(TapZone.TOP_LEFT, tapZoneAt(-50f, -50f, width, height))
		assertEquals(TapZone.BOTTOM_RIGHT, tapZoneAt(width + 50f, height + 50f, width, height))
	}

	@Test
	fun `a surface with no size yields no region`() {
		assertNull(tapZoneAt(10f, 10f, 0f, height))
		assertNull(tapZoneAt(10f, 10f, width, 0f))
	}

	@Test
	fun `the default grid turns pages on the outer columns and toggles in the middle`() {
		val grid = TapZoneGrid.DEFAULT
		for (row in 0 until 3) {
			assertEquals(TapAction.PREVIOUS_PAGE, grid.actionFor(TapZone.at(row, 0)))
			assertEquals(TapAction.TOGGLE_UI, grid.actionFor(TapZone.at(row, 1)))
			assertEquals(TapAction.NEXT_PAGE, grid.actionFor(TapZone.at(row, 2)))
		}
	}

	@Test
	fun `all nine regions dispatch the action configured for them`() {
		val grid = TapZoneGrid(
			actions = TapZone.entries.associateWith { zone ->
				TapAction.entries[(zone.row * 3 + zone.column) % TapAction.entries.size]
			},
		)
		for (zone in TapZone.entries) {
			val (x, y) = centreOf(zone.row, zone.column)
			assertEquals(zone.name, grid.actionFor(zone), grid.actionAt(x, y, width, height))
		}
	}

	@Test
	fun `right to left swaps next and previous`() {
		val grid = TapZoneGrid.DEFAULT
		val (leftX, leftY) = centreOf(1, 0)
		val (rightX, rightY) = centreOf(1, 2)
		assertEquals(TapAction.PREVIOUS_PAGE, grid.actionAt(leftX, leftY, width, height, isRtl = false))
		assertEquals(TapAction.NEXT_PAGE, grid.actionAt(leftX, leftY, width, height, isRtl = true))
		assertEquals(TapAction.NEXT_PAGE, grid.actionAt(rightX, rightY, width, height, isRtl = false))
		assertEquals(TapAction.PREVIOUS_PAGE, grid.actionAt(rightX, rightY, width, height, isRtl = true))
	}

	@Test
	fun `right to left leaves toggle and nothing alone`() {
		val grid = TapZoneGrid.DEFAULT.with(TapZone.TOP_CENTER, TapAction.NONE)
		val (toggleX, toggleY) = centreOf(1, 1)
		val (noneX, noneY) = centreOf(0, 1)
		assertEquals(TapAction.TOGGLE_UI, grid.actionAt(toggleX, toggleY, width, height, isRtl = true))
		assertEquals(TapAction.NONE, grid.actionAt(noneX, noneY, width, height, isRtl = true))
	}

	@Test
	fun `mirroring twice is the identity, which is what lets the editor show mirrored`() {
		for (action in TapAction.entries) {
			assertEquals(action, action.mirrored().mirrored())
		}
	}

	@Test
	fun `a zone set to none does nothing`() {
		val grid = TapZoneGrid.DEFAULT.with(TapZone.CENTER_RIGHT, TapAction.NONE)
		val (x, y) = centreOf(1, 2)
		assertEquals(TapAction.NONE, grid.actionAt(x, y, width, height))
		assertEquals(TapAction.NONE, grid.actionAt(x, y, width, height, isRtl = true))
		// Only that one cell changed: the corner above it still turns the page.
		val (cornerX, cornerY) = centreOf(0, 2)
		assertEquals(TapAction.NEXT_PAGE, grid.actionAt(cornerX, cornerY, width, height))
	}

	@Test
	fun `a zone missing from the map reads as none rather than failing`() {
		// An older config file will not contain a zone added later.
		val grid = TapZoneGrid(actions = mapOf(TapZone.CENTER to TapAction.TOGGLE_UI))
		assertEquals(TapAction.NONE, grid.actionFor(TapZone.TOP_LEFT))
		val (x, y) = centreOf(1, 1)
		assertEquals(TapAction.TOGGLE_UI, grid.actionAt(x, y, width, height))
	}

	@Test
	fun `a tap on a surface with no size does nothing`() {
		assertEquals(TapAction.NONE, TapZoneGrid.DEFAULT.actionAt(5f, 5f, 0f, 0f))
	}
}
