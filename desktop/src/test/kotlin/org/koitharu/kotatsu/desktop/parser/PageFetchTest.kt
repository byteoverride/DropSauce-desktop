package org.koitharu.kotatsu.desktop.parser

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.jetbrains.skia.Image

/**
 * Diagnostic for the page image path: resolve, fetch, decode.
 *
 * Reports what each stage actually returns instead of swallowing failures, which is how
 * a blank page in the reader gets diagnosed.
 */
class PageFetchTest {

	@Test
	fun `is the 404 caused by our client, or by MangaDex`() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
		val registry = SourceRegistry()
		val source = MangaParserSource.MANGADEX
		val session = registry.session(source)
		val bare = okhttp3.OkHttpClient()
		runBlocking {
			val list = session.parser.getList(0, SortOrder.POPULARITY, MangaListFilter())
			val manga = session.parser.getDetails(list.first())
			val chapter = manga.chapters!!.first()
			val pages = session.parser.getPages(chapter)
			println("pages=${pages.size}")
			var failures = 0
			for ((i, page) in pages.withIndex()) {
				val url = registry.pageUrl(source, page)
				val viaParser = code(session.client, url)
				if (viaParser == 200) continue
				failures++
				// Controls: a bare client on the same url, and a fresh getPages which
				// asks MangaDex for a new at-home assignment.
				val viaBare = code(bare, url)
				val freshUrl = registry.pageUrl(source, session.parser.getPages(chapter)[i])
				val viaFresh = code(session.client, freshUrl)
				println(
					"page $i: parserClient=$viaParser bareClient=$viaBare " +
						"freshAssignment=$viaFresh " +
						"sameAfterFresh=${freshUrl == url}",
				)
				if (failures >= 2) break
			}
			println("total pages failing on first try: $failures")
		}
	}

	private fun code(client: okhttp3.OkHttpClient, url: String): Int =
		runCatching {
			client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
		}.getOrDefault(-1)

	@Test
	fun `re-resolving a failed page yields a different node`() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
		val registry = SourceRegistry()
		val source = MangaParserSource.MANGADEX
		val session = registry.session(source)
		runBlocking {
			val list = session.parser.getList(0, SortOrder.POPULARITY, MangaListFilter())
			val manga = session.parser.getDetails(list.first())
			val pages = session.parser.getPages(manga.chapters!!.first())
			var checked = 0
			for (page in pages) {
				val first = registry.pageUrl(source, page)
				val ok1 = fetchOk(session.client, first)
				if (ok1) continue
				// This page failed. Does asking again give a different address?
				val second = registry.pageUrl(source, page)
				val ok2 = fetchOk(session.client, second)
				println("FAILED PAGE: sameUrl=${first == second} secondAttemptOk=$ok2")
				println("  1st host=${first.toHttpUrlOrNull()?.host}")
                println("  2nd host=${second.toHttpUrlOrNull()?.host}")
				checked++
				if (checked >= 2) break
			}
			if (checked == 0) println("no page failed on first attempt this run")
		}
	}

	private fun fetchOk(client: okhttp3.OkHttpClient, url: String): Boolean =
		runCatching {
			client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
		}.getOrDefault(false)

	@Test
	fun `resolve fetch and decode real pages`() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
		val registry = SourceRegistry()
		val source = MangaParserSource.MANGADEX
		val session = registry.session(source)
		runBlocking {
			val list = session.parser.getList(0, SortOrder.POPULARITY, MangaListFilter())
			val manga = session.parser.getDetails(list.first())
			val chapter = manga.chapters!!.first()
			val pages = session.parser.getPages(chapter)
			println("chapter='${chapter.title}' pages=${pages.size}")
			println("raw page.url samples:")
			for (page in pages.take(3)) println("   ${page.url}")
			for (page in pages.take(3)) {
				val url = runCatching { registry.pageUrl(source, page) }
				if (url.isFailure) {
					println("  resolve FAILED: ${url.exceptionOrNull()}")
					continue
				}
				val resolved = url.getOrThrow()
				session.client.newCall(Request.Builder().url(resolved).build()).execute().use { r ->
					val bytes = r.body?.bytes()
					if (r.code != 200) {
						// Retry once: distinguishes a dead url from a transient/CDN miss.
						val again = session.client
							.newCall(Request.Builder().url(resolved).build()).execute()
						println("    retry http=${again.code} bytes=${again.body?.bytes()?.size}")
						again.close()
					}
					val decoded = bytes?.let {
						runCatching { Image.makeFromEncoded(it) }.getOrNull()
					}
					println(
						"  url=${resolved.take(90)}\n" +
							"    http=${r.code} type=${r.header("content-type")} " +
							"bytes=${bytes?.size} decoded=${decoded?.let { "${it.width}x${it.height}" } ?: "NO"}",
					)
				}
			}
		}
	}
}
