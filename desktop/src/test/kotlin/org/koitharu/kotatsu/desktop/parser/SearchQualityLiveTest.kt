package org.koitharu.kotatsu.desktop.parser

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koitharu.kotatsu.desktop.search.rankForQuery
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * What a real source hands back for a title search, before and after ranking.
 *
 * This is the measurement the ranking exists for, and it is the only test that can show
 * the premise still holds: that sources answer a query loosely and in an order that has
 * nothing to do with it. Gated behind `-Dlive=true` like the other live tests, because a
 * third-party site being down must not fail an ordinary build.
 *
 * Run it with `./gradlew :desktop:test --tests '*SearchQualityLiveTest' --rerun-tasks
 * -Dlive=true` and read the printed pairs.
 */
class SearchQualityLiveTest {

	private val userAgent =
		"Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"

	private val query = "Berserk"

	/** One source with a real `RELEVANCE` order and one without, which is the common case. */
	private val sources = listOf(MangaParserSource.MANGADEX, MangaParserSource.ATSUMARU)

	private fun parser(source: MangaParserSource) = DesktopParsers.create(
		source = source,
		httpClient = OkHttpClient(),
		cookieJar = InMemoryCookieJar(),
		userAgent = userAgent,
		configProvider = { DefaultSourceConfig() },
	)

	@Test
	fun `ranking keeps every row and puts the ones answering the query first`() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
		var reached = 0
		runBlocking {
			for (source in sources) {
				val parser = parser(source)
				val hasRelevance = SortOrder.RELEVANCE in parser.availableSortOrders
				val order = when {
					hasRelevance -> SortOrder.RELEVANCE
					SortOrder.UPDATED in parser.availableSortOrders -> SortOrder.UPDATED
					else -> parser.availableSortOrders.first()
				}
				val answer = runCatching {
					parser.getList(0, order, MangaListFilter(query = query))
				}.getOrElse {
					println("${source.name} did not answer: ${it::class.simpleName} ${it.message}")
					continue
				}
				if (answer.isEmpty()) {
					println("${source.name} answered with nothing")
					continue
				}
				reached++
				val ranked = rankForQuery(query, answer)
				println("\n=== ${source.name} order=$order rows=${answer.size} held back=${ranked.hiddenCount}")
				println("  as the source sent it: " + answer.take(6).joinToString(" | ") { it.title })
				println("  ranked:               " + ranked.shown.take(6).joinToString(" | ") { it.title })
				if (ranked.hidden.isNotEmpty()) {
					println("  held back:            " + ranked.hidden.take(6).joinToString(" | ") { it.title })
				}

				assertEquals("ranking must not lose or duplicate a row", answer.size, ranked.all.size)
				assertTrue(
					"the row whose title is the query must be shown",
					ranked.shown.any { it.title.equals(query, ignoreCase = true) } ||
						answer.none { it.title.equals(query, ignoreCase = true) },
				)
			}
		}
		assumeTrue("no source answered, nothing was measured", reached > 0)
	}
}
