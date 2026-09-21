package org.koitharu.kotatsu.desktop.feature.suggestions

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.SuggestionEntity

/** A suggestion on its way into the database. */
data class StoredSuggestion(val manga: Manga, val relevance: Float, val reason: String)

/** A suggestion on its way out, with enough to draw a card. */
data class SuggestionCard(
	val manga: Manga,
	val sourceName: String,
	val relevance: Float,
	val reason: String,
	val createdAt: Long,
)

/**
 * The stored suggestion set.
 *
 * Reading and writing only. Deciding what belongs in the set is [SuggestionEngine]'s
 * job and deriving the taste is [SuggestionProfiler]'s, which is what keeps all three
 * testable apart.
 *
 * Two things about `suggestions` shape this class and are worth stating plainly rather
 * than discovering later:
 *
 * - The table stores a manga id and nothing else about the title, and `SuggestionsDao`
 *   has no join, so the cover and the name are read back from `manga` one row at a time.
 * - `SuggestionsDao` exposes `clear` and `upsertAll` and no delete-by-id, so dismissing
 *   one card cannot remove its row. Dismissals therefore live in [SuggestionStore] and
 *   are applied as a filter on the way out; the row itself goes at the next refresh.
 *
 * Both are contract gaps, not preferences. They belong to whoever owns the shared schema.
 */
class SuggestionsRepository(
	private val db: LibraryDatabase,
	private val store: SuggestionStore,
	private val now: () -> Long = System::currentTimeMillis,
) {

	/**
	 * The stored set, best first, with dismissed titles filtered out.
	 *
	 * Combined with the dismissal flow rather than read once, so dismissing a card
	 * removes it from the grid immediately instead of at the next refresh.
	 */
	fun observe(limit: Int = MAX_SUGGESTIONS): Flow<List<SuggestionCard>> {
		// Asked for with headroom and trimmed after filtering. Filtering a LIMITed query
		// would let every dismissal permanently shrink the grid by one, which looks like
		// the feature quietly running out. The table never holds more than one stored
		// set, so the wider query costs nothing.
		val queryLimit = maxOf(limit, MAX_SUGGESTIONS)
		return combine(db.suggestionsDao().observeTop(queryLimit), store.data) { rows, prefs ->
			val dismissed = prefs.dismissed.toSet()
			rows.filterNot { it.mangaId in dismissed }.take(limit)
		}.map { rows -> rows.mapNotNull { it.toCard() } }
	}

	/**
	 * Replaces the whole set with [entries].
	 *
	 * One transaction: a half-applied replacement is a grid showing some of the last
	 * refresh and some of this one, ordered by two different profiles, which is worse
	 * than either alone.
	 *
	 * An existing `manga` row is left alone. Writing the suggestion's projection over it
	 * would blank `chapters_count` for a title that is also a favourite, and the library
	 * filters on that column.
	 */
	suspend fun replace(entries: List<StoredSuggestion>) = withContext(Dispatchers.IO) {
		val timestamp = now()
		db.useWriterConnection { transactor ->
			transactor.immediateTransaction {
				val mangaDao = db.mangaDao()
				for (entry in entries) {
					if (mangaDao.find(entry.manga.id) == null) {
						mangaDao.upsert(entry.manga.toEntity(entry.manga.chapters?.size ?: 0))
					}
				}
				db.suggestionsDao().clear()
				db.suggestionsDao().upsertAll(
					entries.map { entry ->
						SuggestionEntity(
							mangaId = entry.manga.id,
							relevance = entry.relevance,
							createdAt = timestamp,
							reason = entry.reason,
						)
					},
				)
			}
		}
	}

	/** Forgets everything, for a user who wants the feature quiet. */
	suspend fun clear() = withContext(Dispatchers.IO) { db.suggestionsDao().clear() }

	/**
	 * Hides one card for good.
	 *
	 * Recorded in the store, not deleted from the table, for the reason in the class
	 * comment. The engine reads the same set, so a dismissed title does not come back on
	 * the next refresh either.
	 */
	suspend fun dismiss(mangaId: Long) = store.dismiss(mangaId)

	suspend fun undismiss(mangaId: Long) = store.restore(mangaId)

	private suspend fun SuggestionEntity.toCard(): SuggestionCard? {
		// A suggestion whose manga row is gone is not an error: the row is deleted by
		// cascade when the title leaves the library, and skipping it is the whole of the
		// correct behaviour.
		val row = db.mangaDao().find(mangaId) ?: return null
		return SuggestionCard(
			manga = row.toSeedManga(),
			sourceName = row.source,
			relevance = relevance,
			reason = reason,
			createdAt = createdAt,
		)
	}
}
