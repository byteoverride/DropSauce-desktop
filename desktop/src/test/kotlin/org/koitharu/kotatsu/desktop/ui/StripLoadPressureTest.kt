package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How many pages the strip tries to load at the same moment.
 *
 * This is the crash, measured. Decoding is not the lazy thing it appears to be: the image
 * cache turns encoded bytes into a ComposeImageBitmap, which allocates the full ARGB
 * buffer and rasterises into it immediately, 43 MB and 131 ms for a long page. An
 * unloaded page used to reserve only the height of its spinner, so a screen held fifteen
 * of them and all fifteen started at once. Fifteen simultaneous 43 MB native allocations
 * is not survivable on a machine with a gigabyte, and a failed native allocation leaves
 * no Java stack trace behind to explain itself.
 *
 * Driven through the real [WebtoonStrip] rather than a copy of it, because a copy would
 * only prove that the copy is correct. `ImageComposeScene` renders on the CPU, which also
 * makes this the closest thing available here to the software renderer the affected
 * machines are using.
 */
class StripLoadPressureTest {

	private val scene = ImageComposeScene(
		width = VIEWPORT_WIDTH,
		height = VIEWPORT_HEIGHT,
		density = Density(DENSITY),
		coroutineContext = Dispatchers.Unconfined,
	)

	@AfterTest
	fun tearDown() = scene.close()

	@Test
	fun `no more than a couple of pages load at once`() = runBlocking {
		val source = CountingPageSource(holdMillis = 400)
		scene.setContent {
			WebtoonStrip(
				pageSource = source,
				pages = strip(PAGES),
				zoom = remembered(),
				widthPercent = 100,
				listState = LazyListState(),
				footer = {},
			)
		}
		scene.render()
		// Let the effects start and overlap. The hold is long enough that anything
		// started together is still running together.
		withTimeout(10_000) {
			while (source.started.get() == 0) delay(20)
			delay(600)
		}

		assertTrue(
			source.peakConcurrent.get() <= 2,
			"${source.peakConcurrent.get()} pages decoded at once; " +
				"each is up to 43 MB allocated eagerly",
		)
	}

	// The other half of the same fix. If unloaded pages are short, the list composes a
	// screenful of them however tight the concurrency limit is, and the limit only queues
	// the damage rather than preventing it.
	@Test
	fun `an unloaded page stands as tall as the window`() = runBlocking {
		val source = CountingPageSource(holdMillis = 5_000)
		scene.setContent {
			WebtoonStrip(
				pageSource = source,
				pages = strip(PAGES),
				zoom = remembered(),
				widthPercent = 100,
				listState = LazyListState(),
				footer = {},
			)
		}
		scene.render()
		withTimeout(10_000) {
			while (source.started.get() == 0) delay(20)
			delay(300)
		}

		// A viewport of 900px at density 1.5 is 600dp. A spinner-sized placeholder is
		// 36dp, so the old build composed on the order of fifteen of these.
		assertTrue(
			source.everStarted.size <= 3,
			"${source.everStarted.size} pages were composed into one screen, " +
				"so each is standing in at roughly ${VIEWPORT_HEIGHT / DENSITY / source.everStarted.size}dp",
		)
	}

	@androidx.compose.runtime.Composable
	private fun remembered(): ZoomState = androidx.compose.runtime.remember { ZoomState() }

	private fun strip(count: Int): List<StripPage> = List(count) { index ->
		StripPage(
			chapterIndex = 0,
			chapter = chapter,
			page = MangaPage(
				id = index.toLong(),
				url = "https://example.test/page/$index",
				preview = null,
				source = MangaParserSource.MANGADEX,
			),
			pageInChapter = index,
			chapterPageCount = count,
		)
	}

	private val chapter = MangaChapter(
		id = 1L,
		title = "Chapter 1",
		number = 1f,
		volume = 0,
		url = "/chapter/1",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MangaParserSource.MANGADEX,
	)

	/** Records how many decodes overlap, which is the number this test exists for. */
	private class CountingPageSource(private val holdMillis: Long) : ReaderPageSource {

		val started = AtomicInteger(0)
		val inFlight = AtomicInteger(0)
		val peakConcurrent = AtomicInteger(0)
		val everStarted: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

		override suspend fun pages(chapter: MangaChapter): List<MangaPage> = emptyList()

		override suspend fun image(page: MangaPage, attempt: Int): ImageBitmap? {
			started.incrementAndGet()
			everStarted += page.id
			val now = inFlight.incrementAndGet()
			peakConcurrent.updateAndGet { maxOf(it, now) }
			try {
				delay(holdMillis)
			} finally {
				inFlight.decrementAndGet()
			}
			// Null rather than a bitmap: allocating real pages here would reintroduce the
			// very memory pressure being measured, and the placeholder path is the one
			// under test.
			return null
		}
	}

	private companion object {

		const val VIEWPORT_WIDTH = 700
		const val VIEWPORT_HEIGHT = 900
		const val DENSITY = 1.5f
		const val PAGES = 20
	}
}
