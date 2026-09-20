package org.koitharu.kotatsu.desktop.feature.download

import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * A 2x2 control for DECISIONS.md D20, the open bug where a page image 404s through the
 * parser's client while a bare client gets 200 on the identical url.
 *
 * D20's existing evidence compares two clients that differ in four ways at once, so it
 * can only conclude "our client". This narrows it. Static reading of the parsers jar
 * (`kotatsu-parsers-21d4b79b5f`) says exactly what our client adds for MANGADEX:
 *
 *  - `MangaParserFactoryKt.newParser` returns `MangaParserWrapper(parser)` for every
 *    source, and `MangaParserWrapper.intercept` merges `delegate.requestHeaders` into
 *    the outgoing request. That is the only header merge in the library.
 *  - `MangaDexParser` does not override `intercept` or `requestHeaders`. Its chain
 *    (`MangaDexParser` -> `FlexibleMangaParser`) has a pass-through `intercept`, and
 *    `FlexibleMangaParser.getRequestHeaders` builds a `Headers` with a single entry:
 *    `User-Agent`.
 *
 * So for MangaDex, the whole effect of "the parser is installed as an interceptor" is
 * one `User-Agent` header. The other differences from a bare client are the shared
 * cookie jar and the timeouts. This test varies the interceptor and the cookie jar
 * independently on the same url, which is what tells those apart. Run it with
 * `-Dlive=true`; it needs the real MangaDex and is off by default.
 *
 * Nothing in the download feature assumes an outcome here. [ParserPageSource] keeps
 * using the parser's client, exactly as the reader does, so this bug and its fix stay
 * one decision in one place.
 */
class PageClientControlTest {

	@Test
	fun `which part of our client turns a 200 into a 404`() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
		val registry = SourceRegistry()
		val source = MangaParserSource.MANGADEX
		val session = registry.session(source)

		// Everything, one factor removed at a time.
		val full = session.client
		val withoutInterceptors = session.client.newBuilder()
			.apply { interceptors().clear() }
			.build()
		val withoutCookies = session.client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
		val bare = OkHttpClient()
		// Same client as `full`, plus a network interceptor that prints what actually
		// went on the wire. Reading the parser's `requestHeaders` directly is not
		// available to us here, and the wire is better evidence than the accessor anyway.
		val spy = session.client.newBuilder()
			.addNetworkInterceptor { chain ->
				println("  wire headers: " + chain.request().headers.toString().replace("\n", " | "))
				chain.proceed(chain.request())
			}
			.build()

		runBlocking {
			val list = session.parser.getList(0, SortOrder.POPULARITY, MangaListFilter())
			val manga = session.parser.getDetails(list.first())
			val chapter = requireNotNull(manga.chapters).first()
			val pages = session.parser.getPages(chapter)
			println("pages=${pages.size}")

			var failures = 0
			for ((index, page) in pages.withIndex()) {
				val url = registry.pageUrl(source, page)
				val first = code(full, url)
				if (first == 200) continue
				failures++
				println(
					"page $index\n" +
						"  full client            = $first\n" +
						"  no parser interceptor  = ${code(withoutInterceptors, url)}\n" +
						"  no cookie jar          = ${code(withoutCookies, url)}\n" +
						"  bare client            = ${code(bare, url)}\n" +
						"  full client, with dump = ${code(spy, url)}\n" +
						// Repeated last so a url that simply went stale between the
						// first and second request shows up as "full client recovered"
						// instead of being read as evidence about the other cells.
						"  full client, again     = ${code(full, url)}",
				)
				if (failures >= 2) break
			}
			println("pages failing on the full client: $failures")
		}
	}

	private fun code(client: OkHttpClient, url: String): Int = runCatching {
		client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
	}.getOrDefault(-1)
}
