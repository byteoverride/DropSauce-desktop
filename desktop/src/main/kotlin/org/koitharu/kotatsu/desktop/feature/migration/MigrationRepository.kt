package org.koitharu.kotatsu.desktop.feature.migration

import androidx.room.PooledConnection
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.shared.db.BookmarkEntity
import org.koitharu.kotatsu.shared.db.FavouriteEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.TrackEntity

/** What a migration actually moved. Shown to the user, and asserted on in tests. */
data class MigrationResult(
	val favouritesMoved: Int,
	val historyMoved: Boolean,
	/**
	 * True when the reading position landed on a chapter with the same number, false when
	 * it had to be placed by position. See [matchReadingPosition].
	 */
	val historyMatchedByNumber: Boolean,
	val bookmarksMoved: Int,
	/** Bookmarks whose chapter has no counterpart on the new source. */
	val bookmarksDropped: Int,
	val trackMoved: Boolean,
) {

	fun describe(): String = buildList {
		add("$favouritesMoved ${plural(favouritesMoved, "category", "categories")}")
		if (historyMoved) {
			add(if (historyMatchedByNumber) "reading position" else "reading position (approximate)")
		}
		if (bookmarksMoved > 0) add("$bookmarksMoved ${plural(bookmarksMoved, "bookmark", "bookmarks")}")
		if (bookmarksDropped > 0) {
			add("$bookmarksDropped ${plural(bookmarksDropped, "bookmark", "bookmarks")} dropped")
		}
		if (trackMoved) add("tracking")
	}.joinToString(", ").ifEmpty { "nothing to move" }
}

/**
 * Moves a library entry from one source to another.
 *
 * Everything happens in one writer transaction. A migration that half-applied would leave
 * a title favourited on the new source with its reading position still on the old one, or
 * worse, neither: the user would have lost their place with nothing to point at. Room
 * KMP's `withTransaction` equivalent is `useWriterConnection { it.immediateTransaction }`,
 * and nested suspend DAO calls inside it are proven safe by
 * `shared/src/jvmTest/.../TransactionReentrancyTest.kt`.
 *
 * Reads that no DAO exposes go through `usePrepared` on the transaction's own connection
 * rather than through a `Flow`-returning DAO method, so every read in the transaction
 * sees the same snapshot the writes are made against.
 *
 * Takes fully loaded [Manga] objects. Fetching details is the caller's job
 * ([MigrationService]), which keeps this class testable with no network at all.
 *
 * The old row in `manga` is deliberately left behind, exactly as Android leaves it. That
 * table is a cache keyed by id, and `downloads` and `stats` reference it without a
 * foreign key: deleting it would cascade `history`, `bookmarks` and `tracks` away and
 * would silently orphan already-downloaded chapters and reading statistics belonging to
 * another feature area. The entry disappears from the library because its favourite rows
 * moved, which is what "gone" means here.
 */
class MigrationRepository(
	private val db: LibraryDatabase,
	private val now: () -> Long = System::currentTimeMillis,
) {

	/**
	 * @param migrateProgress mirrors Android's flag exactly. When true, history, bookmarks
	 * and tracking follow the title onto the new source. When false the new title simply
	 * takes the old one's place in the library: favourites still move, and everything the
	 * old entry had read, bookmarked or tracked is deleted along with it.
	 */
	suspend fun migrate(
		oldManga: Manga,
		newManga: Manga,
		migrateProgress: Boolean = true,
	): MigrationResult {
		require(oldManga.id != newManga.id) {
			"cannot migrate ${oldManga.title} onto itself (id ${oldManga.id})"
		}
		val newChapters = newManga.chapters.orEmpty()
		val oldChapters = oldManga.chapters.orEmpty()
		// On IO explicitly: the raw `usePrepared` reads run on the caller's dispatcher,
		// and the caller is a button handler on the composition's scope.
		return withContext(Dispatchers.IO) {
			db.useWriterConnection { transactor ->
				transactor.immediateTransaction {
					// The new row has to exist before anything can point a foreign key at it.
					db.mangaDao().upsert(newManga.toEntity(newChapters.size))

					val favourites = readFavourites(oldManga.id)
					if (favourites.isNotEmpty()) {
						db.favouritesDao().removeFromAll(oldManga.id)
						for (favourite in favourites) {
							db.favouritesDao().upsert(favourite.copy(mangaId = newManga.id))
						}
					}

					if (!migrateProgress) {
						// Plain replacement. Deleting rather than leaving them behind, because a
						// stray history row on the old id would keep the dead entry in the
						// history list for ever with no way to open it.
						db.bookmarksDao().deleteAllFor(oldManga.id)
						db.historyDao().delete(oldManga.id)
						db.tracksDao().delete(oldManga.id)
						return@immediateTransaction MigrationResult(
							favouritesMoved = favourites.size,
							historyMoved = false,
							historyMatchedByNumber = false,
							bookmarksMoved = 0,
							bookmarksDropped = 0,
							trackMoved = false,
						)
					}

					val chapterIds = mapChapterIds(oldChapters, newChapters)
					val bookmarks = readBookmarks(oldManga.id)
					// The page id and the thumbnail url belong to the old source and cannot be
					// recomputed. The reader finds a bookmark by manga, chapter and page, so
					// keeping them costs nothing and the bookmark still opens.
					val movedBookmarks = bookmarks.mapNotNull { bookmark ->
						val chapterId = chapterIds[bookmark.chapterId] ?: return@mapNotNull null
						bookmark.copy(mangaId = newManga.id, chapterId = chapterId)
					}
					if (bookmarks.isNotEmpty()) {
						db.bookmarksDao().deleteAllFor(oldManga.id)
						for (bookmark in movedBookmarks) {
							db.bookmarksDao().upsert(bookmark)
						}
					}

					val history = db.historyDao().find(oldManga.id)
					var historyMoved = false
					var historyByNumber = false
					if (history != null) {
						val match = matchReadingPosition(
							oldChapters = oldChapters,
							newChapters = newChapters,
							oldChapterId = history.chapterId,
							percent = history.percent,
						)
						db.historyDao().delete(oldManga.id)
						if (match != null) {
							db.historyDao().upsert(
								history.copy(
									mangaId = newManga.id,
									chapterId = match.chapterId,
									chaptersCount = newChapters.size,
									// The progress the reader earned is theirs. The equivalent
									// chapter was just located, so recomputing percent here would
									// wipe the progress bar for no reason.
									deletedAt = 0L,
								),
							)
							historyMoved = true
							historyByNumber = match.byNumber
						}
						// A new source with no chapters at all leaves nowhere to point. The old
						// row is still removed: it belongs to an entry that no longer exists.
					}

					val track = db.tracksDao().find(oldManga.id)
					var trackMoved = false
					if (track != null) {
						db.tracksDao().delete(oldManga.id)
						val newest = newChapters.lastOrNull()
						// Adopts the new source's newest chapter rather than carrying the old
						// remembered id across. The old id means nothing here, and leaving it
						// would make the next check report the entire new back catalogue as new.
						db.tracksDao().upsert(
							TrackEntity(
								mangaId = newManga.id,
								lastChapterId = newest?.id ?: 0L,
								lastChapterDate = newest?.uploadDate?.takeIf { it > 0L } ?: now(),
								newChapters = 0,
								lastCheck = now(),
								lastError = null,
							),
						)
						trackMoved = true
					}

					MigrationResult(
						favouritesMoved = favourites.size,
						historyMoved = historyMoved,
						historyMatchedByNumber = historyByNumber,
						bookmarksMoved = movedBookmarks.size,
						bookmarksDropped = bookmarks.size - movedBookmarks.size,
						trackMoved = trackMoved,
					)
				}
			}
		}
	}

	/**
	 * Every favourite row for a title, category memberships included.
	 *
	 * `FavouritesDao` exposes only a `Flow` of category ids, which loses `sort_key` and
	 * `created_at` and would read outside this transaction. Raw SQL on the transaction's
	 * own connection keeps the whole row and the snapshot.
	 */
	private suspend fun PooledConnection.readFavourites(mangaId: Long): List<FavouriteEntity> =
		usePrepared(
			"SELECT category_id, sort_key, created_at, deleted_at FROM favourites WHERE manga_id = ?",
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

	/** `BookmarksDao` only observes; migration needs a one-shot read inside the transaction. */
	private suspend fun PooledConnection.readBookmarks(mangaId: Long): List<BookmarkEntity> =
		usePrepared(
			"SELECT page_id, chapter_id, page, scroll, image_url, created_at, percent " +
				"FROM bookmarks WHERE manga_id = ?",
		) { statement ->
			statement.bindLong(1, mangaId)
			buildList {
				while (statement.step()) {
					add(
						BookmarkEntity(
							mangaId = mangaId,
							pageId = statement.getLong(0),
							chapterId = statement.getLong(1),
							page = statement.getInt(2),
							scroll = statement.getInt(3),
							imageUrl = statement.getText(4),
							createdAt = statement.getLong(5),
							percent = statement.getFloat(6),
						),
					)
				}
			}
		}
}

private fun plural(count: Int, one: String, many: String) = if (count == 1) one else many
