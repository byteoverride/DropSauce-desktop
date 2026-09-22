package org.koitharu.kotatsu.shared.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Relation
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A bookmark joined to the manga it belongs to, for a list that shows covers. */
data class BookmarkWithManga(
	@Embedded val bookmark: BookmarkEntity,
	@Relation(parentColumn = "manga_id", entityColumn = "manga_id")
	val manga: MangaEntity,
)

/** A download joined to its manga, for the downloads screen. */
data class DownloadWithManga(
	@Embedded val download: DownloadEntity,
	@Relation(parentColumn = "manga_id", entityColumn = "manga_id")
	val manga: MangaEntity,
)

/** A tracked title joined to its manga, for the updates screen. */
data class TrackWithManga(
	@Embedded val track: TrackEntity,
	@Relation(parentColumn = "manga_id", entityColumn = "manga_id")
	val manga: MangaEntity,
)

@Dao
interface BookmarksDao {

	@Query("SELECT * FROM bookmarks ORDER BY created_at DESC")
	fun observeAll(): Flow<List<BookmarkWithManga>>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId ORDER BY created_at DESC")
	fun observeByManga(mangaId: Long): Flow<List<BookmarkEntity>>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId AND page_id = :pageId")
	suspend fun find(mangaId: Long, pageId: Long): BookmarkEntity?

	@Upsert
	suspend fun upsert(bookmark: BookmarkEntity)

	@Query("DELETE FROM bookmarks WHERE manga_id = :mangaId AND page_id = :pageId")
	suspend fun delete(mangaId: Long, pageId: Long)

	@Query("DELETE FROM bookmarks WHERE manga_id = :mangaId")
	suspend fun deleteAllFor(mangaId: Long)
}

@Dao
interface DownloadsDao {

	@Query("SELECT * FROM downloads ORDER BY created_at DESC")
	fun observeAll(): Flow<List<DownloadWithManga>>

	@Query("SELECT * FROM downloads WHERE manga_id = :mangaId")
	fun observeByManga(mangaId: Long): Flow<List<DownloadEntity>>

	@Query("SELECT * FROM downloads WHERE state = :state ORDER BY created_at ASC")
	suspend fun getByState(state: String): List<DownloadEntity>

	@Query("SELECT * FROM downloads WHERE manga_id = :mangaId AND chapter_id = :chapterId")
	suspend fun find(mangaId: Long, chapterId: Long): DownloadEntity?

	@Upsert
	suspend fun upsert(download: DownloadEntity)

	@Query("DELETE FROM downloads WHERE manga_id = :mangaId AND chapter_id = :chapterId")
	suspend fun delete(mangaId: Long, chapterId: Long)

	@Query("UPDATE downloads SET state = :state, updated_at = :now WHERE state = :from")
	suspend fun reviveState(from: String, state: String, now: Long)
}

@Dao
interface LocalLibraryDao {

	@Query("SELECT * FROM local_index ORDER BY title COLLATE NOCASE ASC")
	fun observeAll(): Flow<List<LocalMangaEntity>>

	@Query("SELECT * FROM local_index WHERE path = :path")
	suspend fun findByPath(path: String): LocalMangaEntity?

	@Query("SELECT * FROM local_index WHERE id = :id")
	suspend fun find(id: Long): LocalMangaEntity?

	@Upsert
	suspend fun upsert(entity: LocalMangaEntity): Long

	@Query("DELETE FROM local_index WHERE id = :id")
	suspend fun delete(id: Long)
}

@Dao
interface StatsDao {

	@Query("SELECT * FROM stats WHERE started_at >= :since ORDER BY started_at DESC")
	fun observeSince(since: Long): Flow<List<StatsEntity>>

	@Query("SELECT COALESCE(SUM(duration), 0) FROM stats WHERE started_at >= :since")
	fun observeTotalTime(since: Long): Flow<Long>

	@Query("SELECT COALESCE(SUM(pages), 0) FROM stats WHERE started_at >= :since")
	fun observeTotalPages(since: Long): Flow<Int>

	@Query(
		"SELECT manga_id, COALESCE(SUM(duration), 0) AS total FROM stats " +
			"WHERE started_at >= :since GROUP BY manga_id ORDER BY total DESC LIMIT :limit",
	)
	fun observeTopManga(since: Long, limit: Int): Flow<List<MangaTime>>

	@Upsert
	suspend fun upsert(entity: StatsEntity)

	@Query("DELETE FROM stats")
	suspend fun clear()
}

/** Total reading time for one title, for the statistics screen. */
data class MangaTime(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "total") val total: Long,
)

@Dao
interface TracksDao {

	/**
	 * Tracked titles that still have a manga row.
	 *
	 * The `manga_id IN` is not redundant. [TrackWithManga.manga] is non-null, so Room
	 * throws `Relationship item 'manga' was expected to be NON-NULL` for a track whose
	 * manga row has gone, and it throws inside the flow on the UI thread, which takes the
	 * window down and leaves the updates tab unopenable until the row is removed.
	 *
	 * There is a foreign key with ON DELETE CASCADE that should make that impossible, and
	 * a test proves it is enforced and does cascade. It happened anyway. Rather than
	 * guess which write got around it, the read is made incapable of producing the crash:
	 * an orphan is not worth showing and is certainly not worth losing the window for.
	 */
	@Query("SELECT * FROM tracks WHERE manga_id IN (SELECT manga_id FROM manga) ORDER BY last_check ASC")
	fun observeAll(): Flow<List<TrackWithManga>>

	@Query(
		"SELECT * FROM tracks WHERE chapters_new > 0 AND manga_id IN (SELECT manga_id FROM manga) " +
			"ORDER BY last_chapter_date DESC",
	)
	fun observeWithUpdates(): Flow<List<TrackWithManga>>

	/** How many tracked titles have chapters the reader has not seen. */
	@Query("SELECT COUNT(*) FROM tracks WHERE chapters_new > 0 AND manga_id IN (SELECT manga_id FROM manga)")
	fun observeUpdatedCount(): Flow<Int>

	/** Titles with something new, for marking them where the reader keeps them. */
	@Query("SELECT manga_id FROM tracks WHERE chapters_new > 0")
	fun observeUpdatedIds(): Flow<List<Long>>

	/**
	 * Starts watching every saved title whose category has tracking on.
	 *
	 * Scoped the way the Android app scopes it: favourites only, and only categories with
	 * the `track` flag, which is what that column has always been for and which desktop
	 * ignored. History alone does not qualify, matching Android's default, where watching
	 * read-but-unsaved titles is a setting the reader turns on rather than something the
	 * app decides for them.
	 *
	 * OR IGNORE rather than an upsert, so a title already watched keeps the chapter it was
	 * last seen at. An upsert would reset it and the next check would call the whole
	 * archive new.
	 *
	 * One statement rather than a query per title: over several hundred saved titles the
	 * loop it replaces was two round trips each.
	 */
	@Query(
		"INSERT OR IGNORE INTO tracks " +
			"(manga_id, last_chapter_id, last_chapter_date, chapters_new, last_check, last_error) " +
			"SELECT m.manga_id, 0, 0, 0, 0, NULL FROM manga m WHERE m.manga_id IN (" +
			"SELECT f.manga_id FROM favourites f " +
			"JOIN favourite_categories c ON c.category_id = f.category_id " +
			"WHERE f.deleted_at = 0 AND c.track = 1)",
	)
	suspend fun insertTracksForEverythingKept()

	/** How many qualify but are not watched yet, which is what an insert would add. */
	@Query(
		"SELECT COUNT(*) FROM manga m WHERE m.manga_id NOT IN (SELECT manga_id FROM tracks) " +
			"AND m.manga_id IN (" +
			"SELECT f.manga_id FROM favourites f " +
			"JOIN favourite_categories c ON c.category_id = f.category_id " +
			"WHERE f.deleted_at = 0 AND c.track = 1)",
	)
	suspend fun countUntrackedKept(): Int

	/**
	 * Stops watching the titles in [categoryId] that no tracked category still holds.
	 *
	 * Only ever called when the reader turns a category's tracking off, never as a sweep.
	 * A title can also be watched because somebody asked for it directly, and a blanket
	 * "remove anything not covered by a category" would throw those away without being
	 * asked. The second clause is what keeps a title filed in two categories, one of them
	 * still tracked, from being dropped.
	 */
	@Query(
		"DELETE FROM tracks WHERE manga_id IN (" +
			"SELECT manga_id FROM favourites WHERE deleted_at = 0 AND category_id = :categoryId" +
			") AND manga_id NOT IN (" +
			"SELECT f.manga_id FROM favourites f " +
			"JOIN favourite_categories c ON c.category_id = f.category_id " +
			"WHERE f.deleted_at = 0 AND c.track = 1)",
	)
	suspend fun untrackCategory(categoryId: Long)

	/**
	 * Starts watching everything kept, and says how many that added.
	 *
	 * Counted and inserted in one transaction because Room will only let an INSERT return
	 * void or a rowid, and a count taken outside the transaction could be reported after
	 * something else had already changed it.
	 */
	@Transaction
	suspend fun trackEverythingKept(): Int {
		val added = countUntrackedKept()
		insertTracksForEverythingKept()
		return added
	}

	/** Starts tracking one title, leaving an existing watch untouched. */
	@Query(
		"INSERT OR IGNORE INTO tracks " +
			"(manga_id, last_chapter_id, last_chapter_date, chapters_new, last_check, last_error) " +
			"VALUES (:mangaId, 0, 0, 0, 0, NULL)",
	)
	suspend fun trackIfNew(mangaId: Long)

	/** Removes track rows whose title is gone. Cheap, and keeps the table honest. */
	@Query("DELETE FROM tracks WHERE manga_id NOT IN (SELECT manga_id FROM manga)")
	suspend fun deleteOrphans(): Int

	@Query("SELECT * FROM tracks WHERE manga_id = :mangaId")
	suspend fun find(mangaId: Long): TrackEntity?

	@Query("SELECT * FROM tracks ORDER BY last_check ASC")
	suspend fun getAll(): List<TrackEntity>

	@Upsert
	suspend fun upsert(track: TrackEntity)

	@Query("DELETE FROM tracks WHERE manga_id = :mangaId")
	suspend fun delete(mangaId: Long)

	@Query("UPDATE tracks SET chapters_new = 0 WHERE manga_id = :mangaId")
	suspend fun clearNew(mangaId: Long)
}
