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
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.junit.Assume.assumeTrue
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * How wide the pages and covers a real library actually serves are.
 *
 * This is the measurement Phase 3 rests on and it was very nearly skipped. Decoding at
 * display size only saves anything when the source is *wider* than the space it is drawn
 * in. The plan assumed a large saving from a 900x12000 page, but 900x12000 was a size
 * invented for a synthetic memory harness, not something observed: the strip is 60% of
 * the window by default, about 1150px on a 1080p display, and a page narrower than that
 * is already being upscaled. Downscaling it would cost quality and save nothing.
 *
 * Covers are the other half of the question and are expected to be the opposite case:
 * drawn a couple of hundred pixels wide whatever the source sends.
 *
 * Reads dimensions from the header through `Codec`, which allocates no pixels, so this
 * measures sizes without paying for the decodes.
 */
class PageSizeDiagnostic {

	private data class Sample(
		val source: String,
		val pageWidth: Int,
		val pageHeight: Int,
		val coverWidth: Int,
		val coverHeight: Int,
	)

	@BeforeTest
	fun requireLive() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
	}

	@Test
	fun `measure page and cover dimensions across a real library`() = runBlocking {
		val registry = SourceRegistry { UA }
		val sources = LIBRARY_SOURCES.mapNotNull { name ->
			MangaParserSource.entries.firstOrNull { it.name == name }
		}
		val samples = ConcurrentLinkedQueue<Sample>()
		val permits = Semaphore(3)
		coroutineScope {
			sources.map { source ->
				async(Dispatchers.IO) {
					permits.withPermit {
						try {
							withTimeoutOrNull(PER_SOURCE_MS) { sample(registry, source, samples) }
						} catch (e: Throwable) {
							println("SIZE ${source.name}: unreachable, ${e.message?.take(60)}")
						}
					}
				}
			}.awaitAll()
		}

		val all = samples.toList()
		if (all.isEmpty()) {
			println("SIZE nothing answered, no conclusion available")
			return@runBlocking
		}

		println("SIZE ---- pages ----")
		for (strip in STRIP_WIDTHS) {
			val shrinkable = all.count { it.pageWidth > strip }
			val saved = all.sumOf { s ->
				val full = s.pageWidth.toLong() * s.pageHeight * 4
				val capped = if (s.pageWidth > strip) {
					val h = s.pageHeight.toLong() * strip / s.pageWidth
					strip.toLong() * h * 4
				} else {
					full
				}
				full - capped
			}
			val total = all.sumOf { it.pageWidth.toLong() * it.pageHeight * 4 }
			println(
				"SIZE strip ${strip}px: ${shrinkable}/${all.size} pages wider than the strip, " +
					"total decoded ${total / 1_000_000}MB, saving ${saved / 1_000_000}MB " +
					"(${if (total > 0) saved * 100 / total else 0}%)",
			)
		}

		println("SIZE ---- covers at ${COVER_TARGET}px ----")
		val covers = all.distinctBy { it.source }
		val coverFull = covers.sumOf { it.coverWidth.toLong() * it.coverHeight * 4 }
		val coverCapped = covers.sumOf { s ->
			if (s.coverWidth > COVER_TARGET) {
				val h = s.coverHeight.toLong() * COVER_TARGET / s.coverWidth
				COVER_TARGET.toLong() * h * 4
			} else {
				s.coverWidth.toLong() * s.coverHeight * 4
			}
		}
		println(
			"SIZE covers: ${covers.count { it.coverWidth > COVER_TARGET }}/${covers.size} wider than " +
				"${COVER_TARGET}px, total ${coverFull / 1_000_000}MB would become " +
				"${coverCapped / 1_000_000}MB " +
				"(${if (coverFull > 0) (coverFull - coverCapped) * 100 / coverFull else 0}% saved)",
		)
	}

	private suspend fun sample(
		registry: SourceRegistry,
		source: MangaParserSource,
		samples: ConcurrentLinkedQueue<Sample>,
	) {
		val session = registry.session(source)
		val parser = session.parser
		val client = session.client
		val order = SortOrder.POPULARITY.takeIf { it in parser.availableSortOrders }
			?: parser.availableSortOrders.first()
		val manga = parser.getList(0, order, MangaListFilter()).firstOrNull() ?: return
		val details = parser.getDetails(manga)
		val chapter = details.chapters?.firstOrNull() ?: return
		val pages = parser.getPages(chapter)
		if (pages.isEmpty()) return
		// Several pages spread through the chapter, not just the first. The first page is
		// very often a scanlation group's banner, which is short and wide and nothing like
		// the pages that follow it; a survey of first pages would have measured the
		// banners and called it the content.
		val indices = listOf(0, pages.size / 4, pages.size / 2, pages.size * 3 / 4, pages.lastIndex)
			.distinct()
			.filter { it in pages.indices }
		val coverSize = dimensions(client, details.coverUrl.orEmpty()) ?: (0 to 0)
		for (index in indices) {
			val size = dimensions(client, parser.getPageUrl(pages[index])) ?: continue
			samples += Sample(source.name, size.first, size.second, coverSize.first, coverSize.second)
			println(
				"SIZE ${source.name} page ${index + 1}/${pages.size}: ${size.first}x${size.second} " +
					"(${size.first.toLong() * size.second * 4 / 1_000_000}MB decoded), " +
					"cover ${coverSize.first}x${coverSize.second}",
			)
		}
	}

	/** Width and height from the header alone; no pixels are allocated. */
	private fun dimensions(client: okhttp3.OkHttpClient, url: String): Pair<Int, Int>? {
		if (url.isEmpty()) return null
		val bytes = try {
			client.newCall(Request.Builder().url(url).build()).execute().use { response ->
				if (!response.isSuccessful) return null else response.body?.bytes()
			}
		} catch (e: Exception) {
			return null
		} ?: return null
		return try {
			Data.makeFromBytes(bytes).use { data ->
				Codec.makeFromData(data).use { codec -> codec.width to codec.height }
			}
		} catch (e: Throwable) {
			null
		}
	}

	private companion object {

		/** The sources holding the most titles in the library on this machine. */
		val LIBRARY_SOURCES = listOf(
			"MANGAJINX", "MANGAFOREST", "ATSUMARU", "BATOTO", "MANGADEX",
			"MANGAPARK", "MANGAPUMA", "TRUEMANGA", "MANHUASCAN", "LIKEMANGAIN",
			"MANGAREADERTO", "NINEMANGA_EN", "MANGACUTE", "MANGAXYZ",
		)

		/**
		 * The strip is 60% of the window by default, so these are roughly a 1366, a 1080p
		 * and a 1440p window, plus a small one.
		 */
		val STRIP_WIDTHS = listOf(600, 820, 1150, 1540)

		/** What the library grid actually draws a cover at. */
		const val COVER_TARGET = 360

		const val PER_SOURCE_MS = 60_000L
		const val UA =
			"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/124.0.0.0 Safari/537.36"
	}
}
