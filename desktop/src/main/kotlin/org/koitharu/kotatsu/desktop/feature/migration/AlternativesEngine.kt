package org.koitharu.kotatsu.desktop.feature.migration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Searches one source by title.
 *
 * A function rather than a parser so the engine can be driven without a network: every
 * test in this area substitutes one of these. [SourceRegistrySearcher] is the production
 * implementation.
 */
fun interface AlternativeSearcher {

	/** May throw. The engine records the failure and carries on with the other sources. */
	suspend fun search(source: MangaParserSource, query: String): List<Manga>
}

/** Fills in a candidate's chapter list, which is what decides whether it is usable. */
fun interface AlternativeDetailsLoader {

	suspend fun load(manga: Manga): Manga
}

/** One source's answer, already reduced to its single best title. */
data class Alternative(
	val manga: Manga,
	/** Chapters the source actually returned. Zero means the title is there but unreadable. */
	val chaptersCount: Int,
	/** 0f to 1f against the title being migrated. See [titleSimilarity]. */
	val similarity: Float,
) {

	val source: MangaParserSource? get() = manga.source as? MangaParserSource
}

/** A source that could not be searched. Shown rather than swallowed: 30% of the catalogue is broken. */
data class AlternativeFailure(val source: MangaParserSource, val message: String)

/**
 * Finds the same title on other sources.
 *
 * Mirrors the Android `AlternativesUseCase`: bounded parallelism, the current source
 * skipped, and per source only the single best result is offered rather than every
 * same-name hit.
 */
class AlternativesEngine(
	private val searcher: AlternativeSearcher,
	private val details: AlternativeDetailsLoader,
	private val concurrency: Int = DEFAULT_CONCURRENCY,
) {

	init {
		require(concurrency > 0) { "concurrency must be positive, was $concurrency" }
	}

	/**
	 * Sources worth searching for an alternative to a title on [currentSourceName].
	 *
	 * Three filters, in order of how much they matter:
	 *
	 * 1. **Same kind only.** Android splits the catalogue on `isNovelSource`, because a
	 *    manga migrated onto a novel source lands in the wrong reader with content it
	 *    cannot load. Desktop's equivalent signal is [MangaParserSource.contentType], and
	 *    the rule chosen here is the same single boundary: [ContentType.NOVEL] on one
	 *    side, everything else on the other. Not exact contentType equality, because the
	 *    image kinds (MANGA, MANHWA, MANHUA, COMICS, ONE_SHOT, DOUJINSHI, HENTAI) all
	 *    render in the same reader and the same title is routinely catalogued as MANGA on
	 *    one site and MANHWA on the next; demanding equality would throw away most of the
	 *    real alternatives to buy nothing.
	 * 2. **Never the source being migrated away from.**
	 * 3. **Never a source the catalogue flags broken.** Migrating onto one of the 380
	 *    broken sources (DECISIONS.md D18) would be a move from one dead entry to
	 *    another, and auto-fix would then pick the same title up again next run.
	 */
	fun candidateSources(
		currentSourceName: String,
		isNovel: Boolean,
		catalogue: List<MangaParserSource> = MangaParserSource.entries,
	): List<MangaParserSource> = catalogue.filter { source ->
		source.name != currentSourceName &&
			!source.isBroken &&
			(source.contentType == ContentType.NOVEL) == isNovel
	}

	/**
	 * The same thing for a title whose source is still in the catalogue.
	 *
	 * Split from the explicit overload because an entry whose source has been *removed*
	 * from the catalogue is exactly what auto-fix repairs, and for those the kind cannot be
	 * read off the source at all. Those go through the overload above with `isNovel = false`,
	 * the image reader, which is what 1260 of the 1270 catalogue entries are.
	 */
	fun candidateSources(
		seed: Manga,
		catalogue: List<MangaParserSource> = MangaParserSource.entries,
	): List<MangaParserSource> = candidateSources(
		currentSourceName = seed.source.name,
		isNovel = (seed.source as? MangaParserSource)?.contentType == ContentType.NOVEL,
		catalogue = catalogue,
	)

	/**
	 * Streams one [Alternative] per source that answered, in the order they answer.
	 *
	 * Not sorted: the caller collects into a list and sorts with [alternativeOrder], so a
	 * partially filled screen is already ranked and the ranking is testable on its own.
	 */
	fun alternatives(
		seed: Manga,
		sources: List<MangaParserSource>,
		onFailure: (AlternativeFailure) -> Unit = {},
	): Flow<Alternative> = channelFlow {
		if (sources.isEmpty()) return@channelFlow
		val permits = Semaphore(concurrency)
		coroutineScope {
			for (source in sources) {
				launch {
					val best = try {
						// The permit covers the details fetches too, not just the search.
						// Android only guards the search, which lets a source with eight
						// same-name hits open eight more sockets on top of the four it is
						// already allowed. Holding the permit keeps the real ceiling at
						// `concurrency` sources talking to the network at once.
						permits.withPermit { bestFrom(source, seed) }
					} catch (e: CancellationException) {
						// The caller stopped us. Not this source's failure, and swallowing
						// it would break cancellation of the whole search.
						throw e
					} catch (e: Throwable) {
						onFailure(AlternativeFailure(source, e.describeFailure()))
						null
					}
					if (best != null) {
						send(best)
					}
				}
			}
		}
	}

	/**
	 * The single best title this source has for [seed], or null if it has none.
	 *
	 * A source often returns several rows with the same name (different uploaders, or a
	 * colour edition next to the original). Details are loaded for all of them and the
	 * one with the most chapters wins, because that is the one a reader can actually
	 * continue on.
	 */
	private suspend fun bestFrom(source: MangaParserSource, seed: Manga): Alternative? {
		val hits = searcher.search(source, seed.title).filter { it.id != seed.id }
		if (hits.isEmpty()) {
			return null
		}
		val loaded = coroutineScope {
			hits.map { hit ->
				async {
					// A candidate whose details fail is still offered, with whatever the
					// search row carried. It sorts below anything with chapters, so it can
					// only ever be picked when there is nothing better.
					try {
						details.load(hit)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Throwable) {
						hit
					}
				}
			}.awaitAll()
		}
		return loaded
			.map { Alternative(it, it.chapters?.size ?: 0, titleSimilarity(seed.title, it.title)) }
			// alternativeOrder is best-first, so the best candidate is the minimum.
			.minWithOrNull(alternativeOrder)
	}

	companion object {

		/** Four at a time, as Android does. */
		const val DEFAULT_CONCURRENCY = 4
	}
}

/**
 * Best first.
 *
 * The order is deliberately not a single blended score, because the three signals are not
 * interchangeable:
 *
 * 1. **Has chapters at all.** A source that lists the title but returns no chapters is
 *    useless to migrate onto, whatever its name match. This dominates everything.
 * 2. **Title similarity.** Among readable candidates the risk is migrating onto the wrong
 *    comic, which is worse than migrating onto a shorter copy of the right one. An exact
 *    match therefore beats a longer partial match.
 * 3. **Chapter count.** Only as a tie-break between equally good name matches, where more
 *    chapters means more of the story is reachable.
 * 4. **Source name.** Not a quality signal, only there so the order is stable across runs
 *    and a test can assert it.
 */
val alternativeOrder: Comparator<Alternative> = compareByDescending<Alternative> { it.chaptersCount > 0 }
	.thenByDescending { it.similarity }
	.thenByDescending { it.chaptersCount }
	.thenBy { it.manga.source.name }

/**
 * How close two titles are, 0f to 1f.
 *
 * Word overlap rather than edit distance: the differences that matter between two
 * listings of one comic are whole words ("Vol. 1", "(Official)", a romanisation suffix),
 * not transposed letters, and edit distance punishes a long correct title far harder than
 * a short wrong one.
 */
fun titleSimilarity(a: String, b: String): Float {
	val left = normaliseTitle(a)
	val right = normaliseTitle(b)
	if (left.isEmpty() || right.isEmpty()) return 0f
	if (left == right) return 1f
	val leftWords = left.split(' ').toSet()
	val rightWords = right.split(' ').toSet()
	val shared = leftWords.count { it in rightWords }
	if (shared == 0) return 0f
	val jaccard = shared.toFloat() / (leftWords.size + rightWords.size - shared)
	// One title containing all of the other's words is the common "Naruto" vs
	// "Naruto Shippuden" case, which is a strong signal that plain Jaccard scores low.
	// Capped below 1f so an exact match always outranks it.
	val contained = leftWords.containsAll(rightWords) || rightWords.containsAll(leftWords)
	return if (contained) maxOf(jaccard, CONTAINMENT_SCORE) else jaccard
}

/** Lowercase, punctuation dropped, whitespace collapsed. */
private fun normaliseTitle(value: String): String = buildString(value.length) {
	var lastWasSpace = true
	for (ch in value.lowercase()) {
		if (ch.isLetterOrDigit()) {
			append(ch)
			lastWasSpace = false
		} else if (!lastWasSpace) {
			append(' ')
			lastWasSpace = true
		}
	}
}.trim()

/** A one-line reason for a failures list. Many parser exceptions carry no message at all. */
internal fun Throwable.describeFailure(): String =
	message?.takeIf { it.isNotBlank() }?.lineSequence()?.first()?.take(MAX_REASON_LENGTH)
		?: this::class.simpleName
		?: "failed"

private const val CONTAINMENT_SCORE = 0.9f

private const val MAX_REASON_LENGTH = 160
