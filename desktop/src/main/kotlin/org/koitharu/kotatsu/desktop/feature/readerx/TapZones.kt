package org.koitharu.kotatsu.desktop.feature.readerx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One of the nine regions the reader surface is divided into. */
enum class TapZone(val row: Int, val column: Int, val label: String) {
	TOP_LEFT(0, 0, "Top left"),
	TOP_CENTER(0, 1, "Top centre"),
	TOP_RIGHT(0, 2, "Top right"),
	CENTER_LEFT(1, 0, "Left"),
	CENTER(1, 1, "Centre"),
	CENTER_RIGHT(1, 2, "Right"),
	BOTTOM_LEFT(2, 0, "Bottom left"),
	BOTTOM_CENTER(2, 1, "Bottom centre"),
	BOTTOM_RIGHT(2, 2, "Bottom right"),
	;

	companion object {

		fun at(row: Int, column: Int): TapZone =
			entries.first { it.row == row && it.column == column }
	}
}

/**
 * What a tap does.
 *
 * Deliberately shorter than the Android app's list, which also carries chapter skips and
 * a context menu. Those need reader state this feature does not own; adding enum entries
 * nothing can dispatch would be a stub with extra steps.
 */
enum class TapAction(val label: String) {
	NONE("Nothing"),
	NEXT_PAGE("Next page"),
	PREVIOUS_PAGE("Previous page"),
	TOGGLE_UI("Show or hide the bar"),
}

/**
 * Which action each of the nine regions triggers.
 *
 * A map rather than nine fields so that the editor can iterate it, and so that a config
 * file written before a zone existed still loads: [actionFor] falls back to [TapAction.NONE]
 * for anything missing rather than failing to parse.
 */
@Serializable
data class TapZoneGrid(
	@SerialName("actions") val actions: Map<TapZone, TapAction> = DEFAULT_ACTIONS,
) {

	fun actionFor(zone: TapZone): TapAction = actions[zone] ?: TapAction.NONE

	fun with(zone: TapZone, action: TapAction): TapZoneGrid =
		copy(actions = actions + (zone to action))

	companion object {

		/**
		 * Outer columns turn pages, the middle column shows and hides the bar.
		 *
		 * The middle column rather than the middle cell alone, because a reader who wants
		 * the bar back should not have to aim: on a maximised window the centre cell is a
		 * small target in the middle of the artwork.
		 */
		val DEFAULT_ACTIONS: Map<TapZone, TapAction> = TapZone.entries.associateWith { zone ->
			when (zone.column) {
				0 -> TapAction.PREVIOUS_PAGE
				2 -> TapAction.NEXT_PAGE
				else -> TapAction.TOGGLE_UI
			}
		}

		val DEFAULT = TapZoneGrid()
	}
}

/**
 * The region containing the point ([x], [y]) inside a surface of [width] by [height].
 *
 * Returns null for a degenerate surface. That happens for real: a pointer event can
 * arrive in the frame where the layout has been composed but not yet measured, and
 * dividing by zero there would put every such tap in the top-left cell.
 */
fun tapZoneAt(x: Float, y: Float, width: Float, height: Float): TapZone? {
	if (width <= 0f || height <= 0f) return null
	// Clamped after the truncation, not before: a tap exactly on the right or bottom edge
	// gives a fraction of 1.0 and would otherwise index a fourth column or row.
	val column = ((x / width) * GRID).toInt().coerceIn(0, GRID - 1)
	val row = ((y / height) * GRID).toInt().coerceIn(0, GRID - 1)
	return TapZone.at(row, column)
}

/**
 * What a tap at ([x], [y]) should do.
 *
 * [isRtl] swaps next and previous rather than mirroring the grid. The difference shows up
 * on an asymmetric layout: the user configured "this corner goes forward", and forward is
 * what they should keep getting when they switch a title to right-to-left. Mirroring the
 * grid instead would turn their forward corner into a back corner, which is the opposite
 * of what they asked for.
 */
fun TapZoneGrid.actionAt(
	x: Float,
	y: Float,
	width: Float,
	height: Float,
	isRtl: Boolean = false,
): TapAction {
	val zone = tapZoneAt(x, y, width, height) ?: return TapAction.NONE
	val action = actionFor(zone)
	return if (isRtl) action.mirrored() else action
}

/** Next and previous swap under a right-to-left reading direction; nothing else moves. */
fun TapAction.mirrored(): TapAction = when (this) {
	TapAction.NEXT_PAGE -> TapAction.PREVIOUS_PAGE
	TapAction.PREVIOUS_PAGE -> TapAction.NEXT_PAGE
	TapAction.NONE, TapAction.TOGGLE_UI -> this
}

/** Three columns and three rows. Named because it appears in both the hit test and the editor. */
internal const val GRID = 3
