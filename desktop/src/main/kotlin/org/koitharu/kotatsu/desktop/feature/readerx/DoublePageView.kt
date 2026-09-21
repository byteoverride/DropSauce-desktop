package org.koitharu.kotatsu.desktop.feature.readerx

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.desktop.ui.ReaderPageSource
import org.koitharu.kotatsu.parsers.model.MangaPage

/**
 * Position and layout for the double-page reader.
 *
 * Holds the decoded size of every page it has seen, because pairing depends on aspect
 * ratio and nothing knows a page's shape until it has been decoded. Sizes accumulate:
 * once a page is known it stays known for as long as the chapter is open, so paging back
 * and forth through a chapter settles into a stable layout instead of re-guessing.
 *
 * The current position is stored as a page number, not a spread number. That is the whole
 * reason this class exists rather than a plain `Int` in the caller: when a page decodes
 * and turns out to be a drawn spread, every screenful after it re-pairs, and a stored
 * spread index would silently jump the reader somewhere else. A page number survives it.
 */
@Stable
class DoublePageState(
	pageCount: Int,
	coverFirst: Boolean = true,
	isRtl: Boolean = false,
) {

	private val sizes = mutableStateListOf<PageSize?>().apply {
		repeat(pageCount) { add(null) }
	}

	var coverFirst by mutableStateOf(coverFirst)

	var isRtl by mutableStateOf(isRtl)

	/** The page that anchors the current screenful: the lowest page number on it. */
	var anchorPage by mutableStateOf(0)
		private set

	val pageCount: Int get() = sizes.size

	/**
	 * The screenfuls this chapter lays out into.
	 *
	 * A plain getter, not `derivedStateOf`. The cached version returned a stale list
	 * after [coverFirst] changed when read outside a composition, which is both a broken
	 * test and a broken class: correctness should not depend on who is reading. Computing
	 * it on each read costs one pass over the page list and, because the read happens at
	 * read time, a composable that touches this still records `sizes`, [coverFirst] and
	 * [isRtl] as recomposition dependencies.
	 */
	val spreads: List<Spread> get() = pairPages(sizes, coverFirst, isRtl)

	/** Which screenful is showing. Derived, so re-pairing cannot strand the reader. */
	val index: Int get() = spreadIndexOfPage(spreads, anchorPage).coerceAtLeast(0)

	val current: Spread? get() = spreads.getOrNull(index)

	/** Records the decoded shape of [page], which may re-pair everything after it. */
	fun onDecoded(page: Int, size: PageSize) {
		if (page !in sizes.indices) return
		if (sizes[page] == size) return
		sizes[page] = size
	}

	/** The size recorded for [page], or null if it has not been decoded yet. */
	fun sizeOf(page: Int): PageSize? = sizes.getOrNull(page)

	/** Moves to the screenful showing [page]. Out-of-range values are clamped. */
	fun showPage(page: Int) {
		if (pageCount == 0) return
		anchorPage = page.coerceIn(0, pageCount - 1)
	}

	/** Advances one screenful. Returns false at the end, so the caller can change chapter. */
	fun next(): Boolean {
		val target = spreads.getOrNull(index + 1) ?: return false
		anchorPage = target.pages.first()
		return true
	}

	fun previous(): Boolean {
		val target = spreads.getOrNull(index - 1) ?: return false
		anchorPage = target.pages.first()
		return true
	}
}

/**
 * Two pages side by side, or one when the layout calls for it.
 *
 * Takes its own [ReaderPageSource] rather than pre-decoded images so that it can report
 * each page's shape back into [state] as it loads. Decoding is where aspect ratio becomes
 * known, so the component that decodes is the only one that can drive the pairing.
 */
@Composable
fun DoublePageView(
	state: DoublePageState,
	pages: List<MangaPage>,
	pageSource: ReaderPageSource,
	modifier: Modifier = Modifier,
	colorFilter: ColorFilter? = null,
) {
	val spread = state.current
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		when (spread) {
			null -> Text(
				text = "This chapter has no pages.",
				style = MaterialTheme.typography.bodyMedium,
				color = Color.White.copy(alpha = 0.7f),
			)

			is Spread.Single -> DoublePageImage(
				index = spread.page,
				page = pages.getOrNull(spread.page),
				pageSource = pageSource,
				state = state,
				colorFilter = colorFilter,
				modifier = Modifier.fillMaxSize(),
			)

			is Spread.Pair -> Row(
				modifier = Modifier.fillMaxSize(),
				horizontalArrangement = Arrangement.Center,
			) {
				// No gutter between the halves: a facing pair is one drawing interrupted
				// by a fold, and inserting space reintroduces the seam the mode removes.
				for (half in listOf(spread.left, spread.right)) {
					DoublePageImage(
						index = half,
						page = pages.getOrNull(half),
						pageSource = pageSource,
						state = state,
						colorFilter = colorFilter,
						modifier = Modifier.weight(1f).fillMaxSize(),
					)
				}
			}
		}
	}
}

@Composable
private fun DoublePageImage(
	index: Int,
	page: MangaPage?,
	pageSource: ReaderPageSource,
	state: DoublePageState,
	colorFilter: ColorFilter?,
	modifier: Modifier = Modifier,
) {
	var bitmap: ImageBitmap? by remember(page?.id) { mutableStateOf(null) }
	var failed by remember(page?.id) { mutableStateOf(false) }

	LaunchedEffect(page?.id) {
		val target = page
		if (target == null) {
			failed = true
			return@LaunchedEffect
		}
		failed = false
		bitmap = null
		for (attempt in 1..PAGE_ATTEMPTS) {
			if (attempt > 1) delay(PAGE_RETRY_DELAY_MS * (attempt - 1))
			val loaded = pageSource.image(target, attempt)
			if (loaded != null) {
				bitmap = loaded
				// The one moment this page's shape is knowable. Reporting it here is what
				// lets a drawn spread stop being squeezed into half the window.
				state.onDecoded(index, PageSize(loaded.width, loaded.height))
				return@LaunchedEffect
			}
		}
		failed = true
	}

	val bmp = bitmap
	when {
		bmp != null -> Image(
			bitmap = bmp,
			contentDescription = null,
			contentScale = ContentScale.Fit,
			colorFilter = colorFilter,
			modifier = modifier,
		)

		failed -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
			Text(
				text = "Page ${index + 1} could not be loaded.",
				style = MaterialTheme.typography.bodyMedium,
				color = Color.White.copy(alpha = 0.7f),
			)
		}

		else -> Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
			Text(
				text = "Loading page ${index + 1}",
				style = MaterialTheme.typography.labelSmall,
				color = Color.White.copy(alpha = 0.5f),
			)
		}
	}
}

/** Matches the single-page reader, which retries a page before calling it lost. */
private const val PAGE_ATTEMPTS = 4

private const val PAGE_RETRY_DELAY_MS = 400L
