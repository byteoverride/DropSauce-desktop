package org.koitharu.kotatsu.shared.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
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

	@Query("SELECT * FROM tracks ORDER BY last_check ASC")
	fun observeAll(): Flow<List<TrackWithManga>>

	@Query("SELECT * FROM tracks WHERE chapters_new > 0 ORDER BY last_chapter_date DESC")
	fun observeWithUpdates(): Flow<List<TrackWithManga>>

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
