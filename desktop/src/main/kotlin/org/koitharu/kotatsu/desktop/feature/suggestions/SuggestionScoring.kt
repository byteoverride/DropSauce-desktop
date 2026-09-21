package org.koitharu.kotatsu.desktop.feature.suggestions

/**
 * A title a source offered, flattened the same way [TasteSignal] is.
 *
 * Holds no `Manga`, so scoring can be exercised with four lines of test data and the
 * scorer cannot accidentally reach for a chapter list or a cover.
 */
data class Candidate(
	val mangaId: Long,
	val title: String,
	val sourceName: String,
	/** The source's display title, for a reason a person can read. */
	val sourceTitle: String,
	/** Tag titles as the source spells them, in the order it gave them. */
	val tags: List<String>,
	val contentType: String?,
	/** 0f..1f, or 0f when the source does not rate titles. */
	val rating: Float,
)

/** Why a candidate was kept out. Each one is a different thing to tell a person. */
enum class ExclusionCause(val explanation: String) {

	ALREADY_KNOWN("already in your library or history"),
	DISMISSED("dismissed earlier"),
	HIDDEN_SOURCE("from a source you have hidden"),
	NO_PROFILE("there is nothing in your library to compare it against"),
	NO_CONNECTION("nothing in common with what you read"),
}

/**
 * The scorer's answer about one candidate.
 *
 * Exclusion is a verdict rather than a very low score on purpose. "You already own this"
 * and "this is a weak match" have to be distinguishable: the first must never surface
 * however sparse the results are, and the second is exactly what fills a screen when the
 * library is small.
 */
sealed interface Verdict {

	data class Suggest(
		val relevance: Float,
		val reason: String,
		/** The shared tags, strongest first, in the user's own spelling. */
		val sharedTags: List<String>,
		/** How many of the user's own titles carry at least one shared tag. */
		val matchedTitles: Int,
	) : Verdict

	data class Exclude(val cause: ExclusionCause) : Verdict
}

/**
 * Scores one candidate against one profile.
 *
 * Pure by contract: no clock, no randomness, no I/O, no shared mutable state. That is
 * what makes the ranking reproducible and lets every rule below be tested directly
 * rather than inferred from what a refresh happened to return.
 *
 * [dismissed] and [visibleSources] are parameters rather than a filter applied by the
 * caller so that the reason a candidate vanished is recorded here, in one place, instead
 * of being spread over three `filter` calls whose order nobody can reconstruct later.
 * [visibleSources] of null means "no source filter", which is not the same as the empty
 * set.
 */
fun score(
	profile: TasteProfile,
	candidate: Candidate,
	dismissed: Set<Long> = emptySet(),
	visibleSources: Set<String>? = null,
): Verdict {
	if (profile.isEmpty) return Verdict.Exclude(ExclusionCause.NO_PROFILE)
	if (candidate.mangaId in profile.known) return Verdict.Exclude(ExclusionCause.ALREADY_KNOWN)
	if (candidate.mangaId in dismissed) return Verdict.Exclude(ExclusionCause.DISMISSED)
	if (visibleSources != null && candidate.sourceName !in visibleSources) {
		return Verdict.Exclude(ExclusionCause.HIDDEN_SOURCE)
	}

	val shared = candidate.tags
		.mapNotNull { profile.tags[tagKey(it)] }
		.distinctBy { it.key }
		.sortedByDescending { it.weight }
	val sourceScore = profile.sourceAffinity(candidate.sourceName)
	val typeScore = profile.contentTypeAffinity(candidate.contentType)

	// Nothing in common and no reason to trust the source either. Surfacing this would
	// be a random title with a reason that admits it is random, which is worse than a
	// shorter list.
	if (shared.isEmpty() && sourceScore <= 0f) return Verdict.Exclude(ExclusionCause.NO_CONNECTION)

	// Summed, then saturated. Summing is what makes three shared tags beat one; the
	// saturation stops a source that tags a title with forty genres from outscoring
	// everything by breadth alone.
	val tagSum = shared.sumOf { it.weight.toDouble() }.toFloat()
	val tagScore = (tagSum / TAG_SATURATION).coerceAtMost(1f)
	val ratingScore = candidate.rating.coerceIn(0f, 1f)

	val relevance = TAG_SHARE * tagScore +
		SOURCE_SHARE * sourceScore +
		TYPE_SHARE * typeScore +
		RATING_SHARE * ratingScore

	val matchedTitles = shared.flatMapTo(HashSet()) { it.titles }.size
	return Verdict.Suggest(
		relevance = relevance.coerceIn(0f, 1f),
		reason = explain(shared, matchedTitles, candidate, sourceScore),
		sharedTags = shared.map { it.label },
		matchedTitles = matchedTitles,
	)
}

/**
 * Scores a whole batch and returns only what survived, best first.
 *
 * Ties break on title so two runs over the same data produce the same order; without it
 * the grid reshuffles on every refresh for no visible reason.
 */
fun rank(
	profile: TasteProfile,
	candidates: Collection<Candidate>,
	dismissed: Set<Long> = emptySet(),
	visibleSources: Set<String>? = null,
	limit: Int = MAX_SUGGESTIONS,
): List<ScoredSuggestion> = candidates
	.distinctBy { it.mangaId }
	.mapNotNull { candidate ->
		val verdict = score(profile, candidate, dismissed, visibleSources)
		(verdict as? Verdict.Suggest)?.let { ScoredSuggestion(candidate, it) }
	}
	.sortedWith(compareByDescending<ScoredSuggestion> { it.verdict.relevance }.thenBy { it.candidate.title })
	.take(limit)

/** A candidate that survived scoring, paired with why. */
data class ScoredSuggestion(val candidate: Candidate, val verdict: Verdict.Suggest) {

	val relevance: Float get() = verdict.relevance

	val reason: String get() = verdict.reason
}

/**
 * The sentence stored in `suggestions.reason` and shown under the cover.
 *
 * It names the tags that actually matched and counts the titles those tags actually came
 * from, because a recommendation the user cannot check is a recommendation they cannot
 * disagree with. The column exists for exactly this, so nothing here is decoration.
 */
private fun explain(
	shared: List<TagAffinity>,
	matchedTitles: Int,
	candidate: Candidate,
	sourceScore: Float,
): String {
	if (shared.isEmpty()) {
		return "From ${candidate.sourceTitle}, a source you already read"
	}
	val named = shared.take(MAX_NAMED_TAGS).joinToString(", ") { it.label }
	val rest = shared.size - MAX_NAMED_TAGS
	val tagPart = if (rest > 0) "$named and $rest more" else named
	val titlePart = if (matchedTitles == 1) "1 title you read" else "$matchedTitles titles you read"
	val base = "Shares $tagPart with $titlePart"
	return if (sourceScore >= FAMILIAR_SOURCE) "$base, on ${candidate.sourceTitle}" else base
}

/**
 * How much shared-tag weight counts as a perfect tag match.
 *
 * Three of the profile's strongest tags. Below it the score rises with every extra
 * shared tag, which is the behaviour that matters; above it more tags stop helping.
 */
private const val TAG_SATURATION = 3f

/**
 * Tags dominate deliberately. The other three terms are tie-breakers between titles that
 * already match on subject, and none of them should ever pull a one-tag match above a
 * three-tag one.
 */
private const val TAG_SHARE = 0.70f
private const val SOURCE_SHARE = 0.12f
private const val TYPE_SHARE = 0.08f
private const val RATING_SHARE = 0.10f

/** Above this the source is worth naming in the reason rather than just scoring. */
private const val FAMILIAR_SOURCE = 0.5f

/** Enough tags to justify the recommendation, few enough to fit under a cover. */
private const val MAX_NAMED_TAGS = 3

/** A screenful and then some. More than this is a list nobody scrolls. */
const val MAX_SUGGESTIONS = 60
