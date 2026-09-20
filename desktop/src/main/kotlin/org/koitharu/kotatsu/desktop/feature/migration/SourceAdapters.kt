package org.koitharu.kotatsu.desktop.feature.migration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * The real search, against a real parser.
 *
 * The only place in this feature that knows a candidate source is an HTTP call. Everything
 * else takes an [AlternativeSearcher] or an [AlternativeDetailsLoader], which is what lets
 * the whole area be tested without a network.
 */
class SourceRegistrySearcher(private val context: FeatureContext) : AlternativeSearcher {

	override suspend fun search(source: MangaParserSource, query: String): List<Manga> =
		withContext(Dispatchers.IO) {
			val parser = context.sources.session(source).parser
			if (!parser.filterCapabilities.isSearchSupported) {
				// Reported as a failure rather than an empty answer. "Cannot be searched"
				// and "has nothing" are different facts, and only the first one means the
				// source might still have the title under a filter we did not use.
				throw UnsupportedOperationException("no text search on this source")
			}
			parser.getList(0, searchOrderFor(parser.availableSortOrders), MangaListFilter(query = query))
		}
}

/** The real details fetch. Refuses rather than guessing when the source has left the catalogue. */
class SourceRegistryDetailsLoader(private val context: FeatureContext) : AlternativeDetailsLoader {

	override suspend fun load(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val source = parserSourceOrNull(manga.source.name)
			?: throw IllegalStateException("${manga.source.name} is not in the catalogue")
		context.sources.session(source).parser.getDetails(manga)
	}
}

/**
 * The sort order to search with.
 *
 * RELEVANCE where the source has it, because migration searches by exact title and the
 * wanted result is the top one. A duplicate of the discover area's `defaultSort` rather
 * than a call into it: the two feature areas are built independently and must not compile
 * against each other.
 */
internal fun searchOrderFor(available: Set<SortOrder>): SortOrder = when {
	SortOrder.RELEVANCE in available -> SortOrder.RELEVANCE
	SortOrder.POPULARITY in available -> SortOrder.POPULARITY
	SortOrder.UPDATED in available -> SortOrder.UPDATED
	else -> available.firstOrNull() ?: SortOrder.ALPHABETICAL
}
