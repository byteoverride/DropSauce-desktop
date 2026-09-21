package org.koitharu.kotatsu.desktop.feature.readerx

/**
 * The decoded pixel size of one page.
 *
 * Only the ratio matters here, but the raw dimensions are kept because the reader learns
 * them from a decoded [androidx.compose.ui.graphics.ImageBitmap] and rounding to a float
 * ratio at the boundary would lose the ability to say "this is 1:1" exactly.
 */
data class PageSize(val width: Int, val height: Int) {

	/**
	 * A page that is wider than it is tall, meaning the artist drew across the gutter.
	 *
	 * Square counts as portrait. A 1:1 page is rare and squeezing it into half the width
	 * is still readable, whereas a genuine 2:1 spread is not.
	 */
	val isSpread: Boolean get() = width > height && width > 0 && height > 0
}

/**
 * One screenful of the double-page reader.
 *
 * A sealed type rather than a nullable pair, because "one page centred" and "two pages
 * side by side" want different layouts, not the same layout with a hole in it.
 */
sealed interface Spread {

	/** Every page index this screenful shows, in reading order. */
	val pages: List<Int>

	/** A page that fills the whole width: a cover, a drawn spread, or an odd last page. */
	data class Single(val page: Int) : Spread {
		override val pages: List<Int> get() = listOf(page)
	}

	/**
	 * Two pages side by side.
	 *
	 * [left] and [right] are screen positions, already mirrored for reading direction, so
	 * a renderer never has to know which way the book runs.
	 */
	data class Pair(val left: Int, val right: Int) : Spread {
		override val pages: List<Int> get() = if (left <= right) listOf(left, right) else listOf(right, left)
	}
}

/**
 * Groups page indices into the screenfuls a double-page reader shows.
 *
 * Pure on purpose. Pairing is the part of double-page mode that is actually hard, and it
 * is untestable once it is tangled up with measurement and image loading.
 *
 * [sizes] is indexed by page. A null entry is a page that has not been decoded yet and is
 * assumed to be portrait, because that is the overwhelmingly common case and assuming
 * otherwise would leave every not-yet-loaded page alone on screen. The consequence is
 * real and worth knowing about: when a page turns out to be a spread after it decodes,
 * re-running this function shifts the pairing from that point on.
 *
 * [coverFirst] leaves page 0 on its own. That is what the "which side does page one sit
 * on" toggle actually controls: a book whose first page is a cover pairs 1 with 2, 3 with
 * 4 and so on, while a book without one pairs 0 with 1. Getting it wrong is not a
 * cosmetic error, it puts every single facing pair in the whole book on the wrong side.
 *
 * [isRtl] mirrors each pair. It does not reverse the list: the reader still advances
 * through spreads in page order, it is only the two halves of one screen that swap.
 */
fun pairPages(
	sizes: List<PageSize?>,
	coverFirst: Boolean = false,
	isRtl: Boolean = false,
): List<Spread> {
	if (sizes.isEmpty()) return emptyList()
	val result = ArrayList<Spread>(sizes.size / 2 + 1)
	var i = 0
	while (i < sizes.size) {
		val isFirst = i == 0
		val current = sizes[i]
		val hasPartner = i + 1 <= sizes.lastIndex
		// getOrNull would conflate "no such page" with "size not decoded yet", and those
		// two want opposite answers: one cannot be paired, the other must be.
		val nextIsSpread = hasPartner && sizes[i + 1]?.isSpread == true
		when {
			// The cover stands alone, which is what offsets every later pair by one.
			coverFirst && isFirst -> {
				result += Spread.Single(i)
				i += 1
			}
			// A page drawn across the gutter gets the whole width. Halving it is not a
			// degraded reading experience, it is an unreadable one.
			current?.isSpread == true -> {
				result += Spread.Single(i)
				i += 1
			}
			// Do not pair a portrait page with a spread: the spread has to stand alone,
			// so this page is left on its own and the spread starts the next screenful.
			!hasPartner || nextIsSpread -> {
				result += Spread.Single(i)
				i += 1
			}

			else -> {
				result += if (isRtl) Spread.Pair(left = i + 1, right = i) else Spread.Pair(left = i, right = i + 1)
				i += 2
			}
		}
	}
	return result
}

/**
 * The index in [spreads] that shows [page], or -1 when nothing does.
 *
 * Needed because every position the rest of the app deals in is a page number: history,
 * bookmarks and the page counter in the bar all speak pages, and turning one back into a
 * screenful is otherwise a linear scan open-coded at each call site.
 */
fun spreadIndexOfPage(spreads: List<Spread>, page: Int): Int =
	spreads.indexOfFirst { page in it.pages }
