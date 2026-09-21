package org.koitharu.kotatsu.desktop.feature.suggestions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity

/**
 * Finds the tags for a title the library did not store.
 *
 * A function interface so tests hand it a map and need no network. In the app it is a
 * details fetch through the feature contract, which is the only place tags exist: the
 * `manga` table is a projection and does not persist them.
 */
fun interface TagResolver {

	/** Tags for [seed], or an empty list when the source has none for it. */
	suspend fun tagsFor(source: MangaParserSource, seed: Manga): List<String>
}

/**
 * Builds a [TasteProfile] out of the library, the history and whatever tags can be found.
 *
 * The awkward part of this feature lives here. Tag frequency is the whole basis of a
 * recommendation and the schema does not store tags, so they have to come from
 * somewhere: first the cache on disk, then a details fetch for whatever is missing. That
 * fetch is bounded and every failure is stepped over, because a profile built from
 * nineteen of twenty titles is fine and a refresh that dies on one dead source is not.
 *
 * Seeds are capped. A library of two thousand titles would otherwise mean two thousand
 * details fetches on the first run, and the tail of that list contributes almost nothing
 * to the ranking anyway.
 */
class SuggestionProfiler(
	private val db: LibraryDatabase,
	private val tags: TagCache,
	private val resolver: TagResolver,
	private val maxSeeds: Int = MAX_SEEDS,
	private val concurrency: Int = DETAILS_CONCURRENCY,
) {

	/**
	 * Reads the library and the history and turns them into a profile.
	 *
	 * [onProgress] reports resolved-so-far out of to-resolve, for the refresh bar. It is
	 * only called for titles that need a fetch; cached ones are instant and reporting
	 * them would make the bar jump to nearly full and then stall.
	 */
	suspend fun profile(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): TasteProfile {
		val seeds = collectSeeds()
		if (seeds.isEmpty()) return TasteProfile.EMPTY
		val resolved = resolveTags(seeds, onProgress)
		return buildProfile(
			seeds.map { seed ->
				TasteSignal(
					mangaId = seed.row.mangaId,
					title = seed.row.title,
					sourceName = seed.row.source,
					tags = resolved[seed.row.mangaId].orEmpty().toSet(),
					contentType = seed.source?.contentType?.name,
					isFavourite = seed.isFavourite,
					readFraction = seed.readFraction,
				)
			},
		)
	}

	/**
	 * Every title the user has touched, newest first, capped.
	 *
	 * History and favourites are merged on manga id rather than concatenated: a title
	 * that is both is one opinion held twice, not two opinions, and counting it twice
	 * would let a single title set the whole profile.
	 */
	private suspend fun collectSeeds(): List<Seed> = withContext(Dispatchers.IO) {
		val history = db.historyDao().observeRecent(maxSeeds).first()
		val favourites = db.favouritesDao().observeAll().first()
		val merged = LinkedHashMap<Long, Seed>()
		for (entry in history) {
			merged[entry.manga.mangaId] = Seed(
				row = entry.manga,
				isFavourite = false,
				readFraction = entry.history.percent.coerceIn(0f, 1f),
			)
		}
		for (entry in favourites) {
			val existing = merged[entry.manga.mangaId]
			merged[entry.manga.mangaId] = existing?.copy(isFavourite = true)
				?: Seed(row = entry.manga, isFavourite = true, readFraction = null)
		}
		merged.values.take(maxSeeds).map { it.copy(source = parserSourceOrNull(it.row.source)) }
	}

	/**
	 * Tags for every seed: cached where possible, fetched where not.
	 *
	 * A seed whose source has left the catalogue is skipped rather than substituted. A
	 * details call against the wrong source returns a different comic's tags, which
	 * would poison the profile silently, and a silently wrong profile is worse than a
	 * slightly smaller one.
	 */
	private suspend fun resolveTags(
		seeds: List<Seed>,
		onProgress: (Int, Int) -> Unit,
	): Map<Long, List<String>> {
		val known = LinkedHashMap<Long, List<String>>()
		val missing = ArrayList<Seed>()
		for (seed in seeds) {
			val cached = tags[seed.row.mangaId]
			if (cached != null) known[seed.row.mangaId] = cached else missing += seed
		}
		val fetchable = missing.filter { it.source != null }
		if (fetchable.isEmpty()) {
			onProgress(0, 0)
			return known
		}
		val permits = Semaphore(concurrency)
		var done = 0
		val lock = Any()
		val fetched = coroutineScope {
			fetchable.map { seed ->
				async(Dispatchers.IO) {
					permits.withPermit {
						val source = requireNotNull(seed.source)
						val result = try {
							seed.row.mangaId to resolver.tagsFor(source, seed.row.toSeedManga())
						} catch (e: CancellationException) {
							throw e
						} catch (e: Throwable) {
							// One unreachable title must not cost the profile. Recorded
							// as "no tags known" rather than cached, so the next refresh
							// tries again instead of remembering the failure as a fact.
							System.err.println(
								"suggestions: no tags for ${seed.row.title} (${e.describe()})",
							)
							null
						}
						synchronized(lock) {
							done++
							onProgress(done, fetchable.size)
						}
						result
					}
				}
			}.awaitAll()
		}.filterNotNull()
		if (fetched.isNotEmpty()) tags.putAll(fetched.toMap())
		known.putAll(fetched)
		return known
	}

	private data class Seed(
		val row: MangaEntity,
		val isFavourite: Boolean,
		val readFraction: Float?,
		val source: MangaParserSource? = null,
	)

	companion object {

		/**
		 * Enough titles to characterise a reader, few enough that a first run finishes.
		 *
		 * Past about this many the tag ranking stops moving, so the extra fetches buy a
		 * slower refresh and nothing else.
		 */
		const val MAX_SEEDS = 40

		/** Same ceiling as the candidate fetch, and against the same sites. */
		const val DETAILS_CONCURRENCY = 4
	}
}
