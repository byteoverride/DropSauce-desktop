package org.koitharu.kotatsu.desktop.feature.curate

import androidx.room.PooledConnection
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity
import org.koitharu.kotatsu.shared.db.FavouriteEntity
import org.koitharu.kotatsu.shared.db.FavouriteWithManga
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.HistoryWithManga
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaPrefsEntity

/** Where a merge had got to, for progress reporting and for the rollback test. */
enum class MergeStage { CATEGORIES_READ, CATEGORIES_WRITTEN, CATEGORIES_CLEARED, HISTORY_WRITTEN }

/**
 * Batch operations over a library that has grown too big to manage one title at a time.
 *
 * Every method that touches more than one row runs inside
 * `useWriterConnection { it.immediateTransaction { } }`. That is not belt and braces: a
 * half-applied batch is worse than a failed one, because the user has no way to tell
 * which half went through. Nested suspend DAO calls inside that block are proven safe by
 * `shared/src/jvmTest/.../TransactionReentrancyTest.kt`.
 *
 * Reads the screen needs but no DAO exposes (a title's memberships as whole rows, the
 * set of incognito ids) go through raw SQL on the transaction's own connection, so they
 * see the same snapshot as the writes made against them.
 */
class CurateRepository(
	private val db: LibraryDatabase,
	private val now: () -> Long = System::currentTimeMillis,
) {

	/**
	 * Bumped after a write to a table no DAO `Flow` covers.
	 *
	 * `manga_prefs` is read for the incognito badge, and `MangaPrefsDao` only observes a
	 * single title. Without this the badge would not appear until some unrelated
	 * favourite or history change happened to re-fire the other flows.
	 */
	private val revision = MutableStateFlow(0L)

	fun observeCategories(): Flow<List<FavouriteCategoryEntity>> =
		db.favouriteCategoriesDao().observeAll()

	/**
	 * Every saved title with its memberships, reading position and incognito flag.
	 *
	 * Not filtered by category here. Filtering happens after sorting, in the screen,
	 * because the selection has to be reconciled against the whole library and not
	 * against whatever the current chip shows.
	 *
	 * The history flow is asked for `Int.MAX_VALUE` rows deliberately: the DAO's limit
	 * exists for the recents list, and a library sorted by "recently read" that silently
	 * stopped at 100 would put the 101st title at the bottom for ever.
	 */
	fun observeItems(): Flow<List<CurateItem>> = combine(
		db.favouritesDao().observeAll(),
		db.historyDao().observeRecent(Int.MAX_VALUE),
		revision,
	) { favourites, history, _ ->
		assemble(favourites, history, readIncognitoIds())
	}

	private fun assemble(
		favourites: List<FavouriteWithManga>,
		history: List<HistoryWithManga>,
		incognitoIds: Set<Long>,
	): List<CurateItem> {
		val historyByManga = history.associateBy { it.history.mangaId }
		return favourites.groupBy { it.manga.mangaId }.map { (mangaId, rows) ->
			val entity = rows.first().manga
			val entry = historyByManga[mangaId]
			CurateItem(
				manga = entity.toSeedManga(),
				sourceName = entity.source,
				chaptersCount = entity.chaptersCount,
				// The earliest membership. Filing a title into a second category is not
				// the same as saving it again, and "recently added" must not say it is.
				addedAt = rows.minOf { it.favourite.createdAt },
				categoryIds = rows.mapTo(mutableSetOf()) { it.favourite.categoryId },
				lastReadAt = entry?.history?.updatedAt ?: 0L,
				progress = entry?.history?.percent ?: 0f,
				hasHistory = entry != null,
				incognito = mangaId in incognitoIds,
			)
		}
	}

	// region batch operations

	/**
	 * Files [ids] into [categoryId], leaving anything already there untouched.
	 *
	 * Idempotent by reading the memberships first rather than by upserting over them: an
	 * upsert would rewrite `created_at`, and "recently added" would then jump every time
	 * the user re-ran an action that did nothing.
	 */
	suspend fun addToCategory(ids: Collection<Long>, categoryId: Long): BatchResult =
		writing { connection ->
			val (known, missing) = connection.partitionByStoredManga(ids)
			var added = 0
			var present = 0
			for (id in known) {
				val existing = connection.readFavourites(id)
				if (existing.any { it.categoryId == categoryId }) {
					present++
					continue
				}
				db.favouritesDao().upsert(
					FavouriteEntity(
						mangaId = id,
						categoryId = categoryId,
						sortKey = 0,
						createdAt = now(),
						deletedAt = 0L,
					),
				)
				added++
			}
			BatchResult(
				affected = added,
				skipped = present + missing,
				skippedReason = when {
					missing > 0 && present > 0 -> "already there, or no longer saved"
					missing > 0 -> "no longer saved"
					present > 0 -> "already in this category"
					else -> null
				},
			)
		}

	/** Takes [ids] out of one category. Memberships of every other category survive. */
	suspend fun removeFromCategory(ids: Collection<Long>, categoryId: Long): BatchResult =
		writing { connection ->
			var removed = 0
			for (id in ids.distinct()) {
				if (connection.readFavourites(id).none { it.categoryId == categoryId }) continue
				db.favouritesDao().remove(id, categoryId)
				removed++
			}
			BatchResult(affected = removed)
		}

	/**
	 * Moves [ids] from one category to another in a single transaction.
	 *
	 * Not "remove then add" from the caller: those are two transactions, and a failure
	 * between them takes titles out of the library with nowhere to put them back.
	 */
	suspend fun moveToCategory(ids: Collection<Long>, from: Long, to: Long): BatchResult {
		require(from != to) { "cannot move a category onto itself (id $from)" }
		return writing { connection ->
			val (known, missing) = connection.partitionByStoredManga(ids)
			var moved = 0
			for (id in known) {
				val existing = connection.readFavourites(id)
				val source = existing.firstOrNull { it.categoryId == from } ?: continue
				db.favouritesDao().remove(id, from)
				if (existing.none { it.categoryId == to }) {
					// Carries the original created_at across: the title was saved when it
					// was saved, and re-filing it is not a new acquisition.
					db.favouritesDao().upsert(source.copy(categoryId = to))
				}
				moved++
			}
			BatchResult(
				affected = moved,
				skipped = (known.size - moved) + missing,
				skippedReason = "not in that category".takeIf { known.size - moved + missing > 0 },
			)
		}
	}

	/**
	 * Drops [ids] from the library entirely.
	 *
	 * History, bookmarks and downloads are left alone, and the confirmation says so. The
	 * `manga` row stays too: `downloads` and `stats` point at it without a foreign key,
	 * so deleting it would cascade history and bookmarks away and orphan files already on
	 * disk. An entry is gone from the library when it has no memberships, which is what
	 * this does.
	 */
	suspend fun removeFromLibrary(ids: Collection<Long>): BatchResult = writing { connection ->
		var removed = 0
		for (id in ids.distinct()) {
			if (connection.readFavourites(id).isEmpty()) continue
			db.favouritesDao().removeFromAll(id)
			removed++
		}
		BatchResult(affected = removed)
	}

	/**
	 * Marks the reading position of [ids] finished or unstarted.
	 *
	 * Only rewrites history rows that already exist. Marking a never-opened title as read
	 * would mean inventing a chapter id to point at, and the reader would then resume
	 * into a chapter that may not be in the source's list any more. Titles without
	 * history are reported as skipped instead of silently ignored.
	 */
	suspend fun markHistory(ids: Collection<Long>, read: Boolean): BatchResult = writing {
		var changed = 0
		var withoutHistory = 0
		val timestamp = now()
		for (id in ids.distinct()) {
			val entry = db.historyDao().find(id)
			if (entry == null) {
				withoutHistory++
				continue
			}
			val target = if (read) 1f else 0f
			if (entry.percent == target && (read || entry.page == 0)) continue
			db.historyDao().upsert(
				entry.copy(
					updatedAt = timestamp,
					page = if (read) entry.page else 0,
					percent = target,
				),
			)
			changed++
		}
		BatchResult(
			affected = changed,
			skipped = withoutHistory,
			skippedReason = "never opened".takeIf { withoutHistory > 0 },
		)
	}

	/** Deletes the reading position of [ids]. The titles stay in the library. */
	suspend fun deleteHistory(ids: Collection<Long>): BatchResult = writing {
		var deleted = 0
		for (id in ids.distinct()) {
			if (db.historyDao().find(id) == null) continue
			db.historyDao().delete(id)
			deleted++
		}
		BatchResult(affected = deleted)
	}

	// endregion

	/**
	 * Folds duplicate entries into one.
	 *
	 * Everything happens in one transaction. A half-applied merge is the worst failure
	 * this area can produce: the categories could move while the losing rows stayed, so
	 * the library would show the duplicate twice in the same category, or the history
	 * could be deleted before it had been copied, losing a reading position permanently.
	 *
	 * What moves: category memberships the survivor does not already have, and the
	 * furthest-along reading position. What does not: bookmarks, whose page and chapter
	 * ids belong to the loser's source and cannot be remapped without both chapter lists
	 * (that is `MigrationRepository`'s job, which fetches them); and the losers' `manga`
	 * rows, for the same reason [removeFromLibrary] leaves them.
	 *
	 * [onStage] is called between the stages. Production uses it for progress on a large
	 * merge; the rollback test throws from it, which is the only way to land a failure
	 * mid-transaction with writes already made.
	 */
	suspend fun merge(
		survivorId: Long,
		otherIds: Collection<Long>,
		onStage: suspend (MergeStage) -> Unit = {},
	): MergeResult {
		val losers = otherIds.distinct().filterNot { it == survivorId }
		require(losers.isNotEmpty()) { "a merge needs at least one entry to fold in" }
		return writing { connection ->
			requireNotNull(db.mangaDao().find(survivorId)) {
				"cannot merge into $survivorId: it is not in the database"
			}
			val survivorCategories = connection.readFavourites(survivorId)
				.associateBy { it.categoryId }
				.toMutableMap()
			val loserFavourites = losers.associateWith { connection.readFavourites(it) }
			onStage(MergeStage.CATEGORIES_READ)

			var categoriesMoved = 0
			for ((_, favourites) in loserFavourites) {
				for (favourite in favourites) {
					if (survivorCategories.containsKey(favourite.categoryId)) continue
					val moved = favourite.copy(mangaId = survivorId)
					db.favouritesDao().upsert(moved)
					survivorCategories[favourite.categoryId] = moved
					categoriesMoved++
				}
			}
			onStage(MergeStage.CATEGORIES_WRITTEN)

			for (loser in losers) {
				db.favouritesDao().removeFromAll(loser)
			}
			onStage(MergeStage.CATEGORIES_CLEARED)

			val survivorHistory = db.historyDao().find(survivorId)
			val candidates = buildList {
				survivorHistory?.let { add(it) }
				for (loser in losers) {
					db.historyDao().find(loser)?.let { add(it) }
				}
			}
			// Furthest read wins, most recent breaks the tie. Progress is the one thing
			// here that cannot be recreated, so it is what the choice optimises for.
			val best = candidates.maxWithOrNull(
				compareBy<HistoryEntity>({ it.percent }, { it.updatedAt }),
			)
			var historyMoved = false
			if (best != null && best.mangaId != survivorId) {
				db.historyDao().upsert(
					best.copy(
						mangaId = survivorId,
						// The oldest start date, so "reading since" does not reset.
						createdAt = candidates.minOf { it.createdAt },
					),
				)
				historyMoved = true
			}
			onStage(MergeStage.HISTORY_WRITTEN)

			for (loser in losers) {
				db.historyDao().delete(loser)
			}
			MergeResult(
				survivorId = survivorId,
				removed = losers.size,
				categoriesMoved = categoriesMoved,
				historyMoved = historyMoved,
			)
		}
	}

	// region incognito

	/** The per-title flag, straight from `manga_prefs`. */
	suspend fun isIncognito(mangaId: Long): Boolean =
		db.mangaPrefsDao().find(mangaId)?.incognito == true

	/**
	 * Sets the per-title flag, keeping every other override on the row.
	 *
	 * Returns false when the title has no `manga` row: `manga_prefs` has a foreign key
	 * onto it, so there is nowhere to record the flag yet. That is not an error worth
	 * throwing over, because the reader treats an absent flag as "not incognito" and a
	 * title that has never been stored has never been read either.
	 */
	suspend fun setIncognito(mangaId: Long, incognito: Boolean): Boolean {
		val result = writing {
			if (db.mangaDao().find(mangaId) == null) return@writing false
			val existing = db.mangaPrefsDao().find(mangaId)
			db.mangaPrefsDao().upsert(
				existing?.copy(incognito = incognito) ?: MangaPrefsEntity(
					mangaId = mangaId,
					readingMode = null,
					titleOverride = null,
					coverOverride = null,
					branch = null,
					incognito = incognito,
				),
			)
			true
		}
		if (result) revision.value++
		return result
	}

	/** Sets the flag on a whole selection, in one transaction. */
	suspend fun setIncognito(ids: Collection<Long>, incognito: Boolean): BatchResult {
		val result = writing {
			var changed = 0
			var missing = 0
			for (id in ids.distinct()) {
				if (db.mangaDao().find(id) == null) {
					missing++
					continue
				}
				val existing = db.mangaPrefsDao().find(id)
				if (existing?.incognito == incognito) continue
				db.mangaPrefsDao().upsert(
					existing?.copy(incognito = incognito) ?: MangaPrefsEntity(
						mangaId = id,
						readingMode = null,
						titleOverride = null,
						coverOverride = null,
						branch = null,
						incognito = incognito,
					),
				)
				changed++
			}
			BatchResult(
				affected = changed,
				skipped = missing,
				skippedReason = "no longer saved".takeIf { missing > 0 },
			)
		}
		revision.value++
		return result
	}

	// endregion

	/**
	 * Runs [block] on the writer connection inside one transaction, off the caller's
	 * dispatcher.
	 *
	 * Explicitly on IO because every caller is a button handler running on the
	 * composition's scope, and the raw reads below are blocking.
	 */
	private suspend fun <T> writing(block: suspend (PooledConnection) -> T): T =
		withContext(Dispatchers.IO) {
			db.useWriterConnection { transactor ->
				transactor.immediateTransaction { block(this) }
			}
		}

	/**
	 * Whole favourite rows for one title.
	 *
	 * `FavouritesDao` exposes memberships only as a `Flow` of category ids, which loses
	 * `sort_key` and `created_at` and would read outside the transaction.
	 */
	private suspend fun PooledConnection.readFavourites(mangaId: Long): List<FavouriteEntity> =
		usePrepared(
			"SELECT category_id, sort_key, created_at, deleted_at FROM favourites " +
				"WHERE manga_id = ? AND deleted_at = 0",
		) { statement ->
			statement.bindLong(1, mangaId)
			buildList {
				while (statement.step()) {
					add(
						FavouriteEntity(
							mangaId = mangaId,
							categoryId = statement.getLong(0),
							sortKey = statement.getInt(1),
							createdAt = statement.getLong(2),
							deletedAt = statement.getLong(3),
						),
					)
				}
			}
		}

	/**
	 * Splits a selection into ids that still have a `manga` row and a count of those that
	 * do not.
	 *
	 * A selection outlives the list it was made from. Inserting a favourite for an id
	 * that has since been deleted would fail the foreign key and roll the whole batch
	 * back, so the batch reports it as skipped instead.
	 */
	private suspend fun PooledConnection.partitionByStoredManga(
		ids: Collection<Long>,
	): Pair<List<Long>, Int> {
		val distinct = ids.distinct()
		val known = distinct.filter { id ->
			usePrepared("SELECT 1 FROM manga WHERE manga_id = ?") { statement ->
				statement.bindLong(1, id)
				statement.step()
			}
		}
		return known to (distinct.size - known.size)
	}

	/** Ids with the per-title incognito flag set. `MangaPrefsDao` can only observe one title. */
	private suspend fun readIncognitoIds(): Set<Long> = db.useWriterConnection { connection ->
		connection.usePrepared("SELECT manga_id FROM manga_prefs WHERE incognito != 0") { statement ->
			buildSet {
				while (statement.step()) {
					add(statement.getLong(0))
				}
			}
		}
	}
}
