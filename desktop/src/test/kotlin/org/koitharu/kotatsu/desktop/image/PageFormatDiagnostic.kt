package org.koitharu.kotatsu.desktop.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What image formats the sources in a real library actually serve.
 *
 * This decides whether decoding pages at display size can use ImageIO at all. ImageIO on
 * this JDK has no WebP reader, so if WebP is the majority then `setSourceSubsampling` is
 * a path most pages cannot take and the downsampling has to go through Skia instead.
 * Guessing this wrong means writing a format branch that helps almost nobody.
 *
 * Samples the sources the reader really uses rather than the head of the catalogue, which
 * is alphabetical and mostly dead.
 */
class PageFormatDiagnostic {

	@BeforeTest
	fun requireLive() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
	}

	@Test
	fun `sample page formats across the sources a library uses`() = runBlocking {
		val registry = SourceRegistry { UA }
		val sources = LIBRARY_SOURCES.mapNotNull { name ->
			MangaParserSource.entries.firstOrNull { it.name == name }
		}
		println("FORMAT sampling ${sources.size} sources")

		val formats = ConcurrentHashMap<String, Int>()
		val readable = ConcurrentHashMap<String, Int>()
		val permits = Semaphore(3)
		coroutineScope {
			sources.map { source ->
				async(Dispatchers.IO) {
					permits.withPermit {
						// One dead site must not end the survey. Half this catalogue is
						// broken on any given day, which is the reason the survey exists.
						try {
							withTimeoutOrNull(PER_SOURCE_MS) { sample(registry, source, formats, readable) }
								?: formats.merge("timeout", 1, Int::plus)
						} catch (e: Throwable) {
							formats.merge("failed:${e::class.simpleName}", 1, Int::plus)
							println("FORMAT ${source.name}: unreachable, ${e.message?.take(60)}")
						}
					}
				}
			}.awaitAll()
		}
		println("FORMAT magic bytes: ${formats.toSortedMap()}")
		println("FORMAT ImageIO can read: ${readable.toSortedMap()}")
	}

	private suspend fun sample(
		registry: SourceRegistry,
		source: MangaParserSource,
		formats: ConcurrentHashMap<String, Int>,
		readable: ConcurrentHashMap<String, Int>,
	) {
		val parser = registry.session(source).parser
		val client = registry.session(source).client
		val order = SortOrder.POPULARITY.takeIf { it in parser.availableSortOrders }
			?: parser.availableSortOrders.first()
		val manga = parser.getList(0, order, MangaListFilter()).firstOrNull() ?: return
		val chapter = parser.getDetails(manga).chapters?.firstOrNull() ?: return
		val page = parser.getPages(chapter).firstOrNull() ?: return
		val url = parser.getPageUrl(page)
		val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
			if (!response.isSuccessful) return else response.body?.bytes()
		} ?: return

		val format = magic(bytes)
		formats.merge(format, 1, Int::plus)
		// Not whether it decodes, but whether ImageIO has a reader at all, which is what
		// decides if subsampling is available for this format.
		val hasReader = ImageIO.createImageInputStream(bytes.inputStream()).use {
			ImageIO.getImageReaders(it).hasNext()
		}
		readable.merge("$format:${if (hasReader) "yes" else "NO"}", 1, Int::plus)
		println("FORMAT ${source.name}: $format  ${bytes.size / 1024}KB  imageIOReader=$hasReader")
	}

	private fun magic(b: ByteArray): String = when {
		b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpeg"
		b.size > 8 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png"
		b.size > 12 && String(b, 8, 4) == "WEBP" -> "webp"
		b.size > 12 && String(b, 4, 8) == "ftypavif" -> "avif"
		b.size > 2 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "gif"
		else -> "other:" + b.take(4).joinToString("") { "%02x".format(it) }
	}

	private companion object {

		/** The sources holding the most titles in the library on this machine. */
		val LIBRARY_SOURCES = listOf(
			"MANGAJINX", "MANGAFOREST", "ATSUMARU", "BATOTO", "MANGADEX",
			"MANGAPARK", "MANGAPUMA", "TRUEMANGA", "MANHUASCAN", "LIKEMANGAIN",
			"MANGAREADERTO", "NINEMANGA_EN", "MANGACUTE", "MANGAXYZ",
		)

		const val PER_SOURCE_MS = 60_000L
		const val UA =
			"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/124.0.0.0 Safari/537.36"
	}
}
