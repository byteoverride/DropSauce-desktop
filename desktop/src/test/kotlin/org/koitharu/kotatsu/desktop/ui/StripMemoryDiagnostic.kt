package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What opening a chapter costs in resident memory.
 *
 * [StripLoadPressureTest] proves how many pages start at once; this one prices it. Real
 * decoded bitmaps, real `WebtoonStrip`, resident set size rather than heap, because Skia
 * allocates the pixels natively and every heap reading will say the app is fine while the
 * kernel is deciding whether to kill it.
 *
 * A diagnostic, not a test: it prints a number and asserts nothing, because the number
 * depends on the machine. Run it, note the figure, change something, run it again.
 */
class StripMemoryDiagnostic {

	private var scene: ImageComposeScene? = null

	@BeforeTest
	fun requireLive() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
		assumeTrue("needs /proc to read RSS", File("/proc/self/status").exists())
	}

	@AfterTest
	fun tearDown() {
		scene?.close()
	}

	@Test
	fun `price opening a chapter`() = runBlocking {
		// One realistic page, decoded once and handed out repeatedly. Sharing it would
		// understate the cost, so each request gets its own copy of the pixels.
		val encoded = encode(PAGE_WIDTH, PAGE_HEIGHT)
		val perPage = PAGE_WIDTH.toLong() * PAGE_HEIGHT * 4 / 1_000_000
		println("STRIPMEM page ${PAGE_WIDTH}x$PAGE_HEIGHT, ${perPage}MB decoded each, $PAGES in the chapter")

		val source = RealBitmapSource(encoded)
		val base = rss()
		println("STRIPMEM baseline ${base}MB")

		scene = ImageComposeScene(
			width = VIEWPORT_WIDTH,
			height = VIEWPORT_HEIGHT,
			density = Density(DENSITY),
			coroutineContext = Dispatchers.Unconfined,
		).also { built ->
			built.setContent {
				WebtoonStrip(
					pageSource = source,
					pages = strip(),
					zoom = androidx.compose.runtime.remember { ZoomState() },
					widthPercent = 100,
					listState = LazyListState(),
					footer = {},
				)
			}
			built.render()
		}

		// Let whatever the strip decided to start actually finish.
		withTimeoutOrNull(60_000) {
			while (source.completed.get() < source.started.get() || source.started.get() == 0) delay(50)
			delay(1_000)
		}
		scene?.render()

		val grown = rss() - base
		println("STRIPMEM pages decoded: ${source.started.get()} of $PAGES")
		println("STRIPMEM peak concurrent: ${source.peak.get()}")
		println("STRIPMEM growth: ${grown}MB")
		println("STRIPMEM if every page in the chapter were resident: ${perPage * PAGES}MB")
	}

	private fun rss(): Long {
		Runtime.getRuntime().gc()
		Thread.sleep(400)
		return File("/proc/self/status").readLines()
			.first { it.startsWith("VmRSS") }
			.filter(Char::isDigit).toLong() / 1024
	}

	private fun strip(): List<StripPage> = List(PAGES) { index ->
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
			chapterPageCount = PAGES,
		)
	}

	private val chapter = MangaChapter(
		id = 1L, title = "Chapter 1", number = 1f, volume = 0, url = "/c/1",
		scanlator = null, uploadDate = 0L, branch = null, source = MangaParserSource.MANGADEX,
	)

	/** Returns genuinely decoded pages, so the memory measured is the memory the app uses. */
	private class RealBitmapSource(private val encoded: ByteArray) : ReaderPageSource {

		val started = AtomicInteger(0)
		val completed = AtomicInteger(0)
		val inFlight = AtomicInteger(0)
		val peak = AtomicInteger(0)

		override suspend fun pages(chapter: MangaChapter): List<MangaPage> = emptyList()

		override suspend fun image(page: MangaPage, attempt: Int): ImageBitmap {
			started.incrementAndGet()
			val now = inFlight.incrementAndGet()
			peak.updateAndGet { maxOf(it, now) }
			try {
				// The app's own path: encoded bytes to a ComposeImageBitmap, which
				// allocates the full ARGB buffer and rasterises into it here.
				return org.jetbrains.skia.Image.makeFromEncoded(encoded).toComposeImageBitmap()
			} finally {
				inFlight.decrementAndGet()
				completed.incrementAndGet()
			}
		}
	}

	private fun encode(width: Int, height: Int): ByteArray {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val random = java.util.Random(3)
		for (y in 0 until height step 2) {
			for (x in 0 until width step 2) image.setRGB(x, y, random.nextInt())
		}
		return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
	}

	private companion object {

		const val PAGE_WIDTH = 900
		const val PAGE_HEIGHT = 12000
		const val PAGES = 20
		const val VIEWPORT_WIDTH = 700
		const val VIEWPORT_HEIGHT = 900
		const val DENSITY = 1.5f
	}
}
