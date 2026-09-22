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
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What a new reader actually hits: a cover from the catalogue, and the first page of a
 * chapter, across a spread of sources.
 *
 * A diagnostic, not a test. Two reports came in as "sometimes the cover is missing" and
 * "this page could not be loaded on some manhwa", and neither is reproducible by reading
 * the code. This walks real sources and prints why each one failed, so the fix addresses
 * the actual cause rather than the most plausible-sounding one.
 */
class SourceSweepDiagnostic {

	@BeforeTest
	fun requireLive() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
	}

	@Test
	fun `sweep sources for cover and page failures`() = runBlocking {
		val registry = SourceRegistry { DEFAULT_UA }
		val sources = SourceRegistry.usableSources
			.filter { with(SourceRegistry) { !it.isAdult() } }
			.take(SAMPLE)

		val coverOutcomes = ConcurrentHashMap<String, Int>()
		val pageOutcomes = ConcurrentHashMap<String, Int>()
		val detail = ConcurrentHashMap<String, String>()
		val permits = Semaphore(4)

		coroutineScope {
			sources.map { source ->
				async(Dispatchers.IO) {
					permits.withPermit {
						withTimeoutOrNull(PER_SOURCE_MS) { sweep(registry, source, coverOutcomes, pageOutcomes, detail) }
							?: run {
								coverOutcomes.merge("timeout", 1, Int::plus)
								pageOutcomes.merge("timeout", 1, Int::plus)
							}
					}
				}
			}.awaitAll()
		}

		println("SWEEP sources=${sources.size}")
		println("SWEEP covers=${coverOutcomes.toSortedMap()}")
		println("SWEEP pages=${pageOutcomes.toSortedMap()}")
		detail.entries.sortedBy { it.key }.forEach { println("SWEEP   ${it.key}: ${it.value}") }
	}

	private suspend fun sweep(
		registry: SourceRegistry,
		source: MangaParserSource,
		covers: ConcurrentHashMap<String, Int>,
		pages: ConcurrentHashMap<String, Int>,
		detail: ConcurrentHashMap<String, String>,
	) {
		val parser = registry.session(source).parser
		val client = registry.session(source).client
		val list = try {
			parser.getList(0, SortOrder.POPULARITY.takeIf { it in parser.availableSortOrders }
				?: parser.availableSortOrders.first(), MangaListFilter())
		} catch (e: Throwable) {
			covers.merge("list-failed", 1, Int::plus)
			pages.merge("list-failed", 1, Int::plus)
			detail[source.name] = "list: ${e::class.simpleName} ${e.message?.take(60)}"
			return
		}
		val manga = list.firstOrNull() ?: run {
			covers.merge("empty-list", 1, Int::plus)
			pages.merge("empty-list", 1, Int::plus)
			return
		}

		covers.merge(fetch(client, manga.coverUrl), 1, Int::plus)
			.also { if (manga.coverUrl.isNullOrEmpty()) detail[source.name + " cover"] = "no cover url" }

		val outcome = try {
			val details = parser.getDetails(manga)
			val chapter = details.chapters?.firstOrNull()
			if (chapter == null) {
				"no-chapters"
			} else {
				val page = parser.getPages(chapter).firstOrNull()
				if (page == null) "no-pages" else fetch(client, parser.getPageUrl(page))
			}
		} catch (e: Throwable) {
			detail[source.name + " page"] = "${e::class.simpleName} ${e.message?.take(60)}"
			"threw-${e::class.simpleName}"
		}
		pages.merge(outcome, 1, Int::plus)
		if (outcome != "ok") detail[source.name + " pageOutcome"] = outcome
	}

	private fun fetch(client: okhttp3.OkHttpClient, url: String?): String {
		if (url.isNullOrEmpty()) return "no-url"
		return try {
			client.newCall(Request.Builder().url(url).build()).execute().use { response ->
				val type = response.header("Content-Type").orEmpty()
				val bytes = if (response.isSuccessful) response.body?.bytes() else null
				when {
					!response.isSuccessful -> "http-${response.code}"
					bytes == null || bytes.isEmpty() -> "empty-body"
					decodes(bytes) -> "ok"
					else -> "undecodable[$type][${magic(bytes)}]"
				}
			}
		} catch (e: Throwable) {
			"threw-${e::class.simpleName}"
		}
	}

	/** First bytes, so an undecodable payload can be identified rather than guessed at. */
	private fun magic(bytes: ByteArray): String {
		val head = bytes.take(12).joinToString("") { "%02x".format(it) }
		return when {
			head.startsWith("ffd8ff") -> "jpeg"
			head.startsWith("89504e47") -> "png"
			bytes.size > 12 && String(bytes, 8, 4) == "WEBP" -> "webp"
			bytes.size > 12 && String(bytes, 4, 8) == "ftypavif" -> "AVIF"
			bytes.size > 8 && String(bytes, 4, 4) == "ftyp" -> "heif-family:" + String(bytes, 8, 4)
			head.startsWith("3c21444f") || head.startsWith("3c68746d") -> "html"
			else -> head.take(16)
		}
	}

	private fun decodes(bytes: ByteArray): Boolean = try {
		org.jetbrains.skia.Image.makeFromEncoded(bytes)
		true
	} catch (e: Throwable) {
		false
	}

	private companion object {

		const val SAMPLE = 24
		const val PER_SOURCE_MS = 45_000L
		const val DEFAULT_UA =
			"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/124.0.0.0 Safari/537.36"
	}
}
