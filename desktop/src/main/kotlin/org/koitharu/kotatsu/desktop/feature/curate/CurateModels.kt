package org.koitharu.kotatsu.desktop.feature.curate

import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * One saved title, with everything the organise screen sorts, groups and acts on.
 *
 * Deliberately richer than `LibraryRepository.LibraryItem`, which carries only what a
 * grid cell draws. Sorting by "recently added" needs `favourites.created_at` and sorting
 * by progress needs the history row, and re-deriving either per card would be a query
 * per cell.
 */
data class CurateItem(
	val manga: Manga,
	val sourceName: String,
	val chaptersCount: Int,
	/** `favourites.created_at` of the earliest membership, so re-filing does not look new. */
	val addedAt: Long,
	val categoryIds: Set<Long>,
	/** `history.updated_at`, or 0 when the title has never been opened. */
	val lastReadAt: Long,
	/** `history.percent`, 0 when there is no history row. */
	val progress: Float,
	val hasHistory: Boolean,
	/** The per-title `manga_prefs.incognito` flag. */
	val incognito: Boolean,
) {

	val id: Long get() = manga.id

	val title: String get() = manga.title
}

/**
 * How the library is ordered.
 *
 * Each key's comparator produces the order its label promises, so "recently added" means
 * newest first without the caller having to know to invert it. `descending` in
 * [CuratePrefs] then flips whatever that natural order is.
 */
@Serializable
enum class LibrarySortKey(val label: String) {

	TITLE("Title"),
	RECENTLY_ADDED("Recently added"),
	RECENTLY_READ("Recently read"),
	PROGRESS("Progress"),
	CHAPTER_COUNT("Chapter count"),
	;

	/**
	 * No tiebreaker beyond the key itself.
	 *
	 * Kotlin's sort is stable, so equal values keep the order they arrived in. Adding a
	 * secondary sort on id would look tidier and would destroy that: a list already
	 * ordered by the user's previous choice would be silently reshuffled among ties.
	 */
	val comparator: Comparator<CurateItem>
		get() = when (this) {
			TITLE -> Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title) }
			RECENTLY_ADDED -> compareByDescending { it.addedAt }
			RECENTLY_READ -> compareByDescending { it.lastReadAt }
			PROGRESS -> compareByDescending { it.progress }
			CHAPTER_COUNT -> compareByDescending { it.chaptersCount }
		}
}

@Serializable
enum class LibraryViewMode(val label: String) {

	GRID("Grid"),
	LIST("List"),
}

/**
 * Sorts [items] by [key].
 *
 * `sortedWith` on a reversed comparator rather than `sortedBy { }.reversed()`: reversing
 * the result would also reverse ties, which is exactly the stability the comparators are
 * written to preserve.
 */
fun List<CurateItem>.sortedByKey(key: LibrarySortKey, descending: Boolean = false): List<CurateItem> =
	sortedWith(if (descending) key.comparator.reversed() else key.comparator)

/** What a batch action did, so the screen can state the outcome instead of guessing. */
data class BatchResult(
	val affected: Int,
	/** Entries the action could not apply to, with the reason already resolved. */
	val skipped: Int = 0,
	val skippedReason: String? = null,
) {

	fun describe(verb: String): String = buildString {
		append("$affected ${plural(affected, "title", "titles")} $verb")
		if (skipped > 0 && skippedReason != null) {
			append(", $skipped skipped ($skippedReason)")
		}
	}
}

/** What a merge moved, for the confirmation that follows it. */
data class MergeResult(
	val survivorId: Long,
	val removed: Int,
	val categoriesMoved: Int,
	val historyMoved: Boolean,
) {

	fun describe(): String = buildString {
		append("Merged $removed ${plural(removed, "entry", "entries")}")
		val moved = buildList {
			if (categoriesMoved > 0) {
				add("$categoriesMoved ${plural(categoriesMoved, "category", "categories")}")
			}
			if (historyMoved) add("reading position")
		}
		if (moved.isEmpty()) append(", nothing to move") else append("; kept ${moved.joinToString(" and ")}")
	}
}

internal fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many
