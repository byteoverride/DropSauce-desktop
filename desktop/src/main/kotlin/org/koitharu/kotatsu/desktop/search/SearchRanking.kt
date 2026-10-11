package org.koitharu.kotatsu.desktop.search

import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Re-ranks what a source returned for a text query, and says which rows answer it at all.
 *
 * A site's "search" is not a search. Measured against the catalogue this app ships:
 * MangaDex has a real `RELEVANCE` order, and most of the rest do not, so the request falls
 * back to `UPDATED` and the answer comes back ordered by *when it was last touched*.
 * Several sources also widen a query that would otherwise return little, so a search for
 * "Berserk" on ATSUMARU really does hand back "Peerless Battle Spirit" and "Black
 * Summoner" in the first eight rows. Showing that list unchanged is what makes the app
 * look like its search is broken when the source is the one being loose.
 *
 * The Android app has the same problem and fixes it client-side in `SearchV2Helper`,
 * sorting by Levenshtein distance to the query. That is not copied here, for the reason
 * already recorded for [org.koitharu.kotatsu.desktop.feature.migration.titleSimilarity]:
 * edit distance punishes a long correct title far harder than a short wrong one. Measured
 * on the live answer above, it lifts "SPRIGGAN" to second place for a query of "Berserk",
 * because a short unrelated word is a few edits from a short query. Whole words are what
 * differ between a hit and a miss, so the tiers below are built on words.
 */

/** The outcome of ranking one source's answer. [hidden] rows share no word with the query. */
class RankedResults(
	val shown: List<Manga>,
	val hidden: List<Manga>,
) {

	/** Everything, best first, with the rows that answer the query ahead of the rows that do not. */
	val all: List<Manga> get() = shown + hidden

	val hiddenCount: Int get() = hidden.size
}

/**
 * Orders [items] by how well they answer [query] and splits off the rows that do not.
 *
 * A blank or one-character query is a plain browse, where the source's own order is the
 * only meaningful one, so it is returned untouched. Ties keep the source's order: two rows
 * this scores the same are two rows the source knows more about than we do.
 */
fun rankForQuery(query: String, items: List<Manga>): RankedResults {
	val normalisedQuery = normaliseTitle(query)
	if (normalisedQuery.length < MIN_RANKABLE_QUERY) {
		// One character is not something to rank on: it is a prefix of half the
		// catalogue, so every tier below would fire on rows that are not hits. Hiding
		// those rows would leave a user who typed one letter staring at "nothing
		// matches" when the source did answer, so the source's order stands.
		return RankedResults(shown = items, hidden = emptyList())
	}
	val scored = items
		.map { it to relevance(normalisedQuery, it) }
		// sortedByDescending is stable, which is what keeps equal scores in source order.
		.sortedByDescending { it.second }
	return RankedResults(
		shown = scored.filter { it.second > 0f }.map { it.first },
		hidden = scored.filter { it.second <= 0f }.map { it.first },
	)
}

/**
 * How well [manga] answers [normalisedQuery], 1f for an exact title and 0f for no relation.
 *
 * Alternative titles count for as much as the main one: a source that lists a comic under
 * its romanisation and the query that finds it are often not the same string, and the row
 * is just as much a hit either way.
 */
fun relevance(normalisedQuery: String, manga: Manga): Float {
	var best = score(normalisedQuery, normaliseTitle(manga.title))
	for (alt in manga.altTitles) {
		if (best >= 1f) break
		best = maxOf(best, score(normalisedQuery, normaliseTitle(alt)))
	}
	return best
}

/**
 * One title against one query, both already normalised.
 *
 * The tiers are ordered by how much of the query the title actually accounts for. The raw
 * substring tier is below the word tiers but above them being ignored, and it carries two
 * cases that word matching cannot see: an inflection ("berserker" for "berserk"), and any
 * language that does not put spaces between words, where normalisation leaves one long
 * token and every word tier reads it as a single mismatched word.
 */
private fun score(query: String, title: String): Float = when {
	title.isEmpty() -> 0f
	title == query -> 1f
	title.startsWith("$query ") -> PREFIX
	" $title ".contains(" $query ") -> CONTAINS_WORDS
	" $query ".contains(" $title ") -> IS_PART_OF_QUERY
	title.contains(query) -> SUBSTRING
	else -> wordOverlap(query, title)
}

/**
 * How much of the query the title's words account for, when no run of them matched.
 *
 * Each of the query's words earns full credit for being there and part credit for only
 * sharing a stem ("berserkers" against "berserker", "leveling" against "levelers"). The
 * credit is blended rather than tiered, because a query of several words routinely has one
 * word matching outright and another only by its stem, and scoring that the same as one
 * bare word match loses the difference.
 *
 * Words of one character are skipped on both sides. They are almost always a volume
 * number or a particle left behind by normalisation, and counting them means "berserk 1"
 * scores against every title with a 1 in it.
 */
private fun wordOverlap(query: String, title: String): Float {
	val queryWords = query.split(' ').filter { it.length > 1 }
	if (queryWords.isEmpty()) return 0f
	val titleWords = title.split(' ').filter { it.length > 1 }
	if (titleWords.isEmpty()) return 0f
	var credit = 0f
	for (word in queryWords) {
		credit += when {
			word in titleWords -> 1f
			titleWords.any { sharesStem(it, word) } -> STEM_CREDIT
			else -> 0f
		}
	}
	return if (credit == 0f) 0f else WORD_OVERLAP * credit / queryWords.size
}

/**
 * Whether two words are forms of the same one.
 *
 * A shared opening rather than one word containing the other, because the endings are
 * where the forms differ and they differ in both directions at once: "leveling" and
 * "levelers" share neither as a prefix of the other. Half of the shorter word has to be
 * in the shared part as well as [STEM_PREFIX] characters, so "it" and "italy" are not
 * declared related on a two-letter coincidence.
 */
private fun sharesStem(a: String, b: String): Boolean {
	val shorter = minOf(a.length, b.length)
	var common = 0
	while (common < shorter && a[common] == b[common]) {
		common++
	}
	return common >= STEM_PREFIX && common * 2 >= shorter
}

/** Lowercase, punctuation dropped, whitespace collapsed. */
fun normaliseTitle(value: String): String = buildString(value.length) {
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

/** The query is the start of the title: "Berserk - The Prototype" for "berserk". */
private const val PREFIX = 0.9f

/** The whole query appears in the title as a run of words: "Berserk of Gluttony". */
private const val CONTAINS_WORDS = 0.8f

/** The title is a run of words inside a longer query: "Berserk" for "berserk deluxe". */
private const val IS_PART_OF_QUERY = 0.75f

/** The query appears inside a word, or inside a title with no word breaks at all. */
private const val SUBSTRING = 0.6f

/** Scaled by how much of the query the title's words account for. */
private const val WORD_OVERLAP = 0.5f

/** What one query word earns for sharing only a stem with a word of the title. */
private const val STEM_CREDIT = 0.6f

/** How many opening characters two words must share before they count as one word. */
private const val STEM_PREFIX = 4

/** Shorter than this there is nothing to rank on. */
private const val MIN_RANKABLE_QUERY = 2

/**
 * How many consecutive pages holding no match a browse screen accepts before it stops.
 *
 * Paging on towards a screenful of matches, on a source that does not have the title and
 * answers anyway, would walk the whole site one request at a time for a search that was
 * never going to succeed. Three is enough to get past a front page and some filler.
 */
const val MAX_FRUITLESS_PAGES = 3
