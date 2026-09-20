package org.koitharu.kotatsu.desktop.source

import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.parser.DefaultSourceConfig
import org.koitharu.kotatsu.desktop.parser.DesktopParsers
import org.koitharu.kotatsu.desktop.parser.InMemoryCookieJar
import org.koitharu.kotatsu.desktop.parser.ParserSession
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Owns every live parser and the HTTP stack underneath them.
 *
 * Sessions are created on demand and kept, because building a parser is not free and the
 * browse screen goes back and forth between sources.
 */
class SourceRegistry {

	private val cookieJar = InMemoryCookieJar()

	private val baseClient: OkHttpClient = OkHttpClient.Builder()
		.cookieJar(cookieJar)
		.connectTimeout(20, TimeUnit.SECONDS)
		.readTimeout(30, TimeUnit.SECONDS)
		.callTimeout(90, TimeUnit.SECONDS)
		.followRedirects(true)
		.build()

	private val sessions = ConcurrentHashMap<MangaParserSource, ParserSession>()

	internal fun session(source: MangaParserSource): ParserSession = sessions.getOrPut(source) {
		DesktopParsers.open(
			source = source,
			httpClient = baseClient,
			cookieJar = cookieJar,
			userAgent = USER_AGENT,
			configProvider = { DefaultSourceConfig() },
		)
	}

	/**
	 * The directly fetchable url for [page].
	 *
	 * Many sources return a page whose `url` still needs a second request to resolve into
	 * an image address, which is what `getPageUrl` performs, so this is not the same as
	 * reading `page.url`.
	 */
	suspend fun pageUrl(source: MangaParserSource, page: MangaPage): String =
		session(source).parser.getPageUrl(page)

	companion object {

		/**
		 * A current desktop Firefox string. Sources fingerprint the UA, and the parsers
		 * library's own defaults lean mobile, which some sources answer with a different
		 * layout than the parser expects.
		 */
		const val USER_AGENT =
			"Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"

		/**
		 * The catalogue, minus the sources the library itself marks broken.
		 *
		 * DECISIONS.md D18: roughly 380 of 1270 are flagged, and presenting them would
		 * mean most of what a user clicks simply fails.
		 */
		val usableSources: List<MangaParserSource> by lazy {
			MangaParserSource.entries
				.filterNot { it.isBroken }
				.sortedBy { it.title.lowercase() }
		}
	}
}
