package org.koitharu.kotatsu.desktop.feature.suggestions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns sources into things [SuggestionEngine] can run, and keeps the full titles.
 *
 * The engine and the scorer work on [Candidate], which carries no cover and no url, so
 * that scoring stays a pure function over plain data. The grid and the database need the
 * real [Manga]. Rather than smuggling one through the other, the collector keeps them
 * side by side: the adapter records each title as it arrives and [manga] hands it back
 * after ranking.
 *
 * One collector per refresh. [reset] runs at the start of each one, so a title that has
 * since vanished from a source does not linger into the next set.
 */
class CandidateCollector {

	private val seen = ConcurrentHashMap<Long, Manga>()

	fun reset() = seen.clear()

	fun manga(mangaId: Long): Manga? = seen[mangaId]

	/**
	 * Remembers the full title behind a candidate.
	 *
	 * Internal rather than private so a test can drive a refresh with fake sources and
	 * still exercise the real persist path. Losing a recording is silent otherwise: the
	 * grid would simply store nothing and look like a source that answered with nothing.
	 */
	internal fun record(manga: Manga) {
		seen[manga.id] = manga
	}

	/**
	 * A fetcher per source, asking each for titles under the tags the profile cares about.
	 *
	 * Building the parser happens on IO because creating one is not free and, for some
	 * sources, touches the config file. No exception is caught here on purpose: the
	 * engine catches per source, which is what keeps one dead site from costing the
	 * rest, and catching twice would hide the failure from the progress list.
	 */
	fun sourcesFor(
		context: FeatureContext,
		sources: List<MangaParserSource>,
		perSource: Int = MAX_PER_SOURCE,
	): List<CandidateSource> = sources.map { source ->
		CandidateSource(key = source.name, title = source.title) { wantedTags ->
			withContext(Dispatchers.IO) {
				val parser = context.sources.session(source).parser
				val tag = matchingTag(parser, wantedTags)
				val list = parser.getList(
					0,
					preferredOrder(parser.availableSortOrders),
					MangaListFilter(tags = setOfNotNull(tag)),
				).take(perSource)
				list.forEach(::record)
				list.map { it.toCandidate(source) }
			}
		}
	}

	companion object {

		/**
		 * How many titles to keep from one source.
		 *
		 * A page is typically twenty to sixty. Keeping all of them from forty sources
		 * would mean scoring thousands of candidates to show sixty, and the tail of one
		 * source's popularity list is not better than the head of another's.
		 */
		const val MAX_PER_SOURCE = 24
	}
}

/**
 * The first of the profile's tags this source actually has, or null.
 *
 * Null is a perfectly good outcome: the source is then browsed by popularity and the
 * scorer sorts out what is relevant. Forcing a tag the source does not know would either
 * error or, worse, be silently ignored and look like a filtered result.
 *
 * `getFilterOptions` is a network call and some sources cannot serve it, so a failure
 * degrades to the untagged browse rather than failing the whole source.
 */
internal suspend fun matchingTag(parser: MangaParser, wantedTags: List<String>): MangaTag? {
	if (wantedTags.isEmpty()) return null
	val available = runCatching { parser.getFilterOptions().availableTags }
		.getOrNull()
		.orEmpty()
	if (available.isEmpty()) return null
	val byKey = available.associateBy { tagKey(it.title) }
	return wantedTags.firstNotNullOfOrNull { byKey[tagKey(it)] }
}

/**
 * How to order a source's listing.
 *
 * Popularity first, then rating, then recency. A recommendation drawn from an
 * alphabetical listing is a list of titles beginning with A, which is the failure mode
 * this ordering exists to avoid.
 */
internal fun preferredOrder(available: Set<SortOrder>): SortOrder =
	PREFERRED_ORDERS.firstOrNull { it in available }
		?: available.firstOrNull()
		?: SortOrder.POPULARITY

private val PREFERRED_ORDERS = listOf(
	SortOrder.POPULARITY,
	SortOrder.RATING,
	SortOrder.UPDATED,
	SortOrder.NEWEST,
)
