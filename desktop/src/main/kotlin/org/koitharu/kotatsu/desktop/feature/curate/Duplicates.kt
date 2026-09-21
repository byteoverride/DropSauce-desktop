package org.koitharu.kotatsu.desktop.feature.curate

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Titles the user has saved more than once.
 *
 * [key] is the normalised title the group formed around, shown so the grouping is never
 * opaque. [suggestedSurvivorId] is the entry a merge would keep by default; the user can
 * override it, which is why it is a suggestion and not a decision.
 */
data class DuplicateGroup(
	val key: String,
	val items: List<CurateItem>,
	val suggestedSurvivorId: Long,
) {

	val size: Int get() = items.size

	/** True when the copies come from different sources, which is the common case. */
	val crossSource: Boolean get() = items.map { it.sourceName }.distinct().size > 1
}

/**
 * Finds titles saved twice.
 *
 * Two passes. The first groups on a normalised title, which catches the same work saved
 * from two sources and any difference of case, spacing or punctuation. The second merges
 * groups whose normalised titles are near-identical, for the typos and the stray
 * subtitle.
 *
 * Near-identical deliberately means *edit distance*, not shared words. Word overlap would
 * group "Dragon Ball" with "Dragon Quest" and "Attack on Titan" with "Attack on Avatar",
 * and a false positive here is not cosmetic: the action offered on a group deletes rows.
 * [SIMILARITY] is set high enough that two strings of ordinary title length must differ
 * by about one character to pass.
 */
object Duplicates {

	/**
	 * Minimum 1 - distance/length for two normalised titles to be called the same work.
	 *
	 * At 0.9, a 20-character title tolerates two edits and a 10-character title one. The
	 * false-positive tests pin the other side of this number.
	 */
	const val SIMILARITY = 0.9

	fun find(items: List<CurateItem>): List<DuplicateGroup> {
		if (items.size < 2) return emptyList()
		val byKey = LinkedHashMap<String, MutableList<CurateItem>>()
		for (item in items) {
			val key = normalize(item.title)
			// A title that normalises to nothing (punctuation only) has no evidence behind
			// it, and lumping all of those together would be the worst kind of grouping.
			if (key.isEmpty()) continue
			byKey.getOrPut(key) { mutableListOf() }.add(item)
		}
		val keys = byKey.keys.toList()
		val union = UnionFind(keys.size)
		for (i in keys.indices) {
			for (j in i + 1 until keys.size) {
				if (isNearIdentical(keys[i], keys[j])) union.join(i, j)
			}
		}
		val merged = LinkedHashMap<Int, MutableList<CurateItem>>()
		for (i in keys.indices) {
			merged.getOrPut(union.root(i)) { mutableListOf() }.addAll(byKey.getValue(keys[i]))
		}
		return merged.entries
			.filter { it.value.size > 1 }
			.map { (root, group) ->
				DuplicateGroup(
					key = keys[root],
					// Ordered so the screen never reshuffles a group between refreshes.
					items = group.sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.id })),
					suggestedSurvivorId = suggestSurvivor(group),
				)
			}
			.sortedBy { it.key }
	}

	/**
	 * The entry a merge keeps unless the user says otherwise.
	 *
	 * Most-read first, because reading position is the one thing in a library that cannot
	 * be recreated. Chapter count breaks the tie (the entry whose source carries the most
	 * of the work), then the oldest entry, then the id so the answer is never arbitrary.
	 */
	fun suggestSurvivor(items: List<CurateItem>): Long = items
		.sortedWith(
			compareByDescending<CurateItem> { it.progress }
				.thenByDescending { it.chaptersCount }
				.thenBy { it.addedAt }
				.thenBy { it.id },
		)
		.first().id

	/**
	 * Lowercases, strips punctuation and collapses runs of space.
	 *
	 * Punctuation goes because "Re:Zero" and "Re Zero" are one work saved twice, and the
	 * sources disagree about it constantly. Digits and letters stay, so "Gantz" and
	 * "Gantz 2" remain two different works.
	 */
	fun normalize(title: String): String {
		val builder = StringBuilder(title.length)
		var pendingSpace = false
		for (character in title.lowercase(Locale.ROOT)) {
			if (character.isLetterOrDigit()) {
				if (pendingSpace && builder.isNotEmpty()) builder.append(' ')
				pendingSpace = false
				builder.append(character)
			} else {
				pendingSpace = true
			}
		}
		return builder.toString()
	}

	/** Cheap length gate first: two strings that differ in length by more than the budget cannot pass. */
	fun isNearIdentical(a: String, b: String): Boolean {
		if (a == b) return true
		val longest = max(a.length, b.length)
		if (longest == 0) return false
		val budget = ((1.0 - SIMILARITY) * longest).toInt()
		if (budget == 0) return false
		if (abs(a.length - b.length) > budget) return false
		return distance(a, b, budget) <= budget
	}

	/**
	 * Levenshtein distance, giving up once it passes [budget].
	 *
	 * Two rows rather than a full matrix, and an early exit, because this runs over every
	 * pair of distinct titles in the library and the answer for almost every pair is "not
	 * even close".
	 */
	fun distance(a: String, b: String, budget: Int = Int.MAX_VALUE): Int {
		if (a == b) return 0
		if (a.isEmpty()) return b.length
		if (b.isEmpty()) return a.length
		var previous = IntArray(b.length + 1) { it }
		var current = IntArray(b.length + 1)
		for (i in 1..a.length) {
			current[0] = i
			var rowBest = current[0]
			for (j in 1..b.length) {
				val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
				current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
				rowBest = min(rowBest, current[j])
			}
			// Every remaining cell is at least rowBest, so once the whole row is over
			// budget the final answer is too.
			if (rowBest > budget) return budget + 1
			val swap = previous
			previous = current
			current = swap
		}
		return previous[b.length]
	}
}

/** Plain union-find over group indices; near-identity is transitive once two keys join. */
private class UnionFind(size: Int) {

	private val parent = IntArray(size) { it }

	fun root(index: Int): Int {
		var node = index
		while (parent[node] != node) {
			parent[node] = parent[parent[node]]
			node = parent[node]
		}
		return node
	}

	fun join(a: Int, b: Int) {
		val rootA = root(a)
		val rootB = root(b)
		if (rootA == rootB) return
		// Lower index wins, so the surviving root is the first key seen and group order
		// stays insertion-ordered.
		if (rootA < rootB) parent[rootB] = rootA else parent[rootA] = rootB
	}
}
