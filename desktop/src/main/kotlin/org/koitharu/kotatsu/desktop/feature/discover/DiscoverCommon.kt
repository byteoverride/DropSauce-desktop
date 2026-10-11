package org.koitharu.kotatsu.desktop.feature.discover

/**
 * What is left of this area's private UI helpers.
 *
 * It held four things that four other areas had their own copy of, and all four now live
 * in `desktop/ui`: `CoverCard` moved to `ui/Covers.kt`, the hand-drawn two-box
 * `ProgressBar` became `LoadingStrip`, `STRIP_ITEM_WIDTH` became `ThumbSize.strip` and
 * `COVER_ASPECT` became the single declaration in `ui/Theme.kt`.
 *
 * The constant below stays because it is genuinely about this area's paging and nothing
 * shared wants it.
 */

/** How close to the end of a grid to get before asking for the next page. */
const val GRID_PREFETCH_DISTANCE = 8
