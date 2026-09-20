package org.koitharu.kotatsu.desktop.parser

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Proves the desktop parser host actually works, rather than merely compiling.
 *
 * The offline tests are the regression net and always run. The live test needs the
 * network and a third-party site to be up, so it runs only with -Dlive=true; a flaky
 * external dependency must not be able to fail the build.
 */
class DesktopParsersTest {

	private val userAgent =
		"Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"

	private fun parser(source: MangaParserSource) = DesktopParsers.create(
		source = source,
		httpClient = OkHttpClient(),
		cookieJar = InMemoryCookieJar(),
		userAgent = userAgent,
		configProvider = { DefaultSourceConfig() },
	)

	@Test
	fun `every source in the catalogue can be instantiated`() {
		val failures = mutableListOf<String>()
		for (source in MangaParserSource.entries) {
			try {
				parser(source)
			} catch (e: Throwable) {
				failures += "${source.name}: ${e::class.simpleName} ${e.message}"
			}
		}
		assertEquals("sources that failed to instantiate: $failures", 0, failures.size)
	}

	@Test
	fun `the catalogue is the expected size and reports broken sources`() {
		val all = MangaParserSource.entries
		assertEquals(1270, all.size)
		// DECISIONS.md D18: a large minority are known-broken and the UI must filter them.
		val broken = all.count { it.isBroken }
		assertTrue("expected a meaningful number of broken sources, got $broken", broken > 300)
		assertTrue("broken should not be the majority, got $broken of ${all.size}", broken < all.size / 2)
	}

	@Test
	fun `a parser is an OkHttp interceptor, which is why each gets its own client`() {
		// The trap from DECISIONS.md D1: MangaParser extends okhttp3.Interceptor, and
		// MangaParserWrapper.intercept is the only place a parser's requestHeaders (UA,
		// Referer) are merged into an outgoing request. Nothing in the library installs it.
		// A host sharing one client across parsers sends no per-source headers, still
		// compiles, and still works against undemanding sources. This asserts the premise
		// that forces DesktopParsers.create to pair each parser with its own client.
		val p = parser(MangaParserSource.MANGADEX)
		assertTrue("MangaParser is expected to be an okhttp3.Interceptor", p is okhttp3.Interceptor)
	}

	@Test
	fun `live fetch against a real source`() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
		val p = parser(MangaParserSource.MANGADEX)
		runBlocking {
			val list = p.getList(0, SortOrder.POPULARITY, MangaListFilter())
			assertTrue("expected a non-empty manga list", list.isNotEmpty())
			val details = p.getDetails(list.first())
			assertTrue("expected chapters", !details.chapters.isNullOrEmpty())
			val pages = p.getPages(details.chapters!!.first())
			assertTrue("expected pages", pages.isNotEmpty())
			println("LIVE OK: ${list.size} manga, ${details.chapters!!.size} chapters, ${pages.size} pages")
		}
	}
}
