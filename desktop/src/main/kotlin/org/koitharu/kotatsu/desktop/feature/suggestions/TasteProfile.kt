package org.koitharu.kotatsu.desktop.feature.suggestions

import kotlin.math.max

/**
 * One title the user has a relationship with, flattened to the facts that matter here.
 *
 * Deliberately not a `Manga` and not a `MangaEntity`. The profile is a pure function of
 * these records, so the whole of [buildProfile] and everything downstream of it is
 * testable without a database, a parser or a network. Whoever collects the records
 * decides where tags came from; this layer never asks.
 */
data class TasteSignal(
	val mangaId: Long,
	val title: String,
	/** [org.koitharu.kotatsu.parsers.model.MangaParserSource.name], not the display title. */
	val sourceName: String,
	/** Tag titles as the source spells them. Normalised into keys by [tagKey]. */
	val tags: Set<String>,
	/** [org.koitharu.kotatsu.parsers.model.ContentType.name], or null when unknown. */
	val contentType: String?,
	val isFavourite: Boolean,
	/**
	 * How much of the title has been read, 0f..1f, or null when it was never opened.
	 *
	 * Null and 0f are different facts: a favourite that was never opened is a weaker
	 * signal than one opened and abandoned on page one, and both are far weaker than one
	 * read to the end.
	 */
	val readFraction: Float?,
)

/** What one tag is worth, and which of the user's titles it came from. */
data class TagAffinity(
	/** Normalised form, used for matching. */
	val key: String,
	/** The spelling to show a person, taken from the first title that carried it. */
	val label: String,
	/** 0f..1f, scaled so the strongest tag in the profile is exactly 1f. */
	val weight: Float,
	/** The library or history titles carrying this tag. Its size is the count in a reason. */
	val titles: Set<Long>,
)

/**
 * What the user appears to like, derived from the library and the reading history.
 *
 * Immutable and self-contained: scoring reads nothing else. [known] is the exclusion set
 * rather than a filter applied earlier, because "you already have this" has to be
 * reportable as a distinct verdict and not silently dropped.
 */
data class TasteProfile(
	val tags: Map<String, TagAffinity>,
	/** Source name to affinity, 0f..1f. */
	val sources: Map<String, Float>,
	/** Content type name to affinity, 0f..1f. */
	val contentTypes: Map<String, Float>,
	/** Every title already in the library or the history. */
	val known: Set<Long>,
	/** How many titles the profile was built from. */
	val sampleSize: Int,
) {

	val isEmpty: Boolean get() = sampleSize == 0

	/** Tags strongest first, for showing the user what the recommendations are based on. */
	val rankedTags: List<TagAffinity> get() = tags.values.sortedByDescending { it.weight }

	fun affinityFor(tag: String): Float = tags[tagKey(tag)]?.weight ?: 0f

	fun sourceAffinity(sourceName: String): Float = sources[sourceName] ?: 0f

	fun contentTypeAffinity(contentType: String?): Float =
		contentType?.let { contentTypes[it] } ?: 0f

	companion object {

		val EMPTY = TasteProfile(emptyMap(), emptyMap(), emptyMap(), emptySet(), 0)
	}
}

/**
 * How much one title's opinion counts.
 *
 * A read title outweighs a saved one, and reading more of it outweighs reading less.
 * The favourite floor is below the weight of a title merely opened, because saving
 * something for later says you expect to like it while reading it says you did; but it
 * is well above zero, because a shelf full of unread fantasy is still a statement about
 * fantasy. A title that is both gets a small bonus rather than the sum, which would let
 * one title dominate a small library.
 */
internal fun signalWeight(signal: TasteSignal): Float {
	val read = signal.readFraction
	val fromHistory = if (read == null) 0f else HISTORY_BASE + HISTORY_READ * read.coerceIn(0f, 1f)
	val fromLibrary = if (signal.isFavourite) FAVOURITE_WEIGHT else 0f
	val bonus = if (signal.isFavourite && read != null) BOTH_BONUS else 0f
	return max(fromHistory, fromLibrary) + bonus
}

/**
 * Folds the signals into a profile. Pure: same input, same output, no clock, no I/O.
 *
 * Tag weights are rescaled so the strongest is 1f. Without that, a profile built from
 * three titles and one built from three hundred would produce scores on completely
 * different ranges, and the relevance stored in the database would mean nothing across
 * refreshes.
 */
fun buildProfile(signals: Collection<TasteSignal>): TasteProfile {
	if (signals.isEmpty()) return TasteProfile.EMPTY
	val rawTags = LinkedHashMap<String, MutableTag>()
	val rawSources = LinkedHashMap<String, Float>()
	val rawTypes = LinkedHashMap<String, Float>()
	val known = LinkedHashSet<Long>()
	for (signal in signals) {
		val weight = signalWeight(signal)
		known += signal.mangaId
		if (weight <= 0f) continue
		rawSources[signal.sourceName] = (rawSources[signal.sourceName] ?: 0f) + weight
		signal.contentType?.let { rawTypes[it] = (rawTypes[it] ?: 0f) + weight }
		for (tag in signal.tags) {
			val key = tagKey(tag)
			if (key.isEmpty()) continue
			val entry = rawTags.getOrPut(key) { MutableTag(key, tag.trim()) }
			entry.weight += weight
			entry.titles += signal.mangaId
		}
	}
	// Hoisted: recomputing this inside the map would be quadratic, and a library with no
	// tags at all would make maxOf throw rather than return an empty tag map.
	val topTagWeight = rawTags.values.maxOfOrNull { it.weight } ?: 0f
	return TasteProfile(
		tags = rawTags.values
			.map { it.toAffinity(scale = topTagWeight) }
			.sortedByDescending { it.weight }
			.associateBy { it.key },
		sources = rawSources.normalised(),
		contentTypes = rawTypes.normalised(),
		known = known,
		sampleSize = signals.size,
	)
}

/**
 * The matching form of a tag title.
 *
 * Sources disagree about case, about hyphens and about padding ("Slice of Life",
 * "slice-of-life", " Slice Of Life"). Without folding those together a profile counts
 * the same genre three times and the reason shown to the user reads as nonsense.
 */
fun tagKey(raw: String): String = buildString(raw.length) {
	var lastWasSpace = true
	for (ch in raw) {
		val c = if (ch == '-' || ch == '_' || ch.isWhitespace()) ' ' else ch.lowercaseChar()
		if (c == ' ') {
			if (!lastWasSpace) append(c)
			lastWasSpace = true
		} else {
			append(c)
			lastWasSpace = false
		}
	}
}.trim()

private class MutableTag(val key: String, val label: String) {

	var weight: Float = 0f
	val titles: MutableSet<Long> = LinkedHashSet()

	fun toAffinity(scale: Float) = TagAffinity(
		key = key,
		label = label,
		weight = if (scale <= 0f) 0f else weight / scale,
		titles = titles.toSet(),
	)
}

private fun Map<String, Float>.normalised(): Map<String, Float> {
	val top = values.maxOrNull() ?: return emptyMap()
	if (top <= 0f) return emptyMap()
	return mapValues { it.value / top }
}

/** A saved but unopened title still counts. See [signalWeight] for why it counts less. */
private const val FAVOURITE_WEIGHT = 0.35f

/** Opening a title at all is already a stronger statement than shelving it. */
private const val HISTORY_BASE = 0.45f

/** The part of a read title's weight that depends on how far it was actually read. */
private const val HISTORY_READ = 0.55f

/** Saved and read is more than either, but not the sum of both. */
private const val BOTH_BONUS = 0.15f
