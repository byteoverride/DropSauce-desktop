package org.koitharu.kotatsu.shared.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A favourite row joined to the manga it points at. */
data class FavouriteWithManga(
	@Embedded val favourite: FavouriteEntity,
	@Relation(parentColumn = "manga_id", entityColumn = "manga_id")
	val manga: MangaEntity,
)

/** A history row joined to the manga it points at. */
data class HistoryWithManga(
	@Embedded val history: HistoryEntity,
	@Relation(parentColumn = "manga_id", entityColumn = "manga_id")
	val manga: MangaEntity,
)

@Dao
interface MangaDao {

	@Upsert
	suspend fun upsert(manga: MangaEntity)

	@Query("SELECT * FROM manga WHERE manga_id = :id")
	suspend fun find(id: Long): MangaEntity?

	/**
	 * Copies a chapter count the app already knows onto the title itself.
	 *
	 * `history.chapters` has carried the real count since before `manga.chapters_count`
	 * existed, and nothing ever moved it across: a library restored from an Android
	 * backup (which does not carry the field at all) or written before the column was
	 * added ends up with a read title whose stored count is still zero, so the library's
	 * length filter puts it in "Not loaded" forever. Idempotent, so it is safe to run on
	 * every start and again before a refresh.
	 */
	@Query(
		"UPDATE manga SET chapters_count = " +
			"(SELECT h.chapters FROM history h WHERE h.manga_id = manga.manga_id) " +
			"WHERE chapters_count = 0 AND manga_id IN " +
			"(SELECT h.manga_id FROM history h WHERE h.chapters > 0)",
	)
	suspend fun backfillChaptersCountFromHistory(): Int

	/**
	 * Saved titles whose chapter count is not known locally, in display order.
	 *
	 * Scoped by category because a library is filled in a category at a time: the point
	 * of the length filter is to sort out one shelf, and making that cost a live request
	 * for every other shelf too is the difference between a usable action and one nobody
	 * presses. A null [categoryId] means the whole library.
	 */
	@Query(
		"SELECT * FROM manga WHERE chapters_count = 0 AND manga_id IN " +
			"(SELECT f.manga_id FROM favourites f WHERE f.deleted_at = 0 " +
			"AND (:categoryId IS NULL OR f.category_id = :categoryId)) ORDER BY title",
	)
	suspend fun favouritesWithoutChaptersCount(categoryId: Long?): List<MangaEntity>

	@Query(
		"SELECT COUNT(*) FROM manga WHERE chapters_count = 0 AND manga_id IN " +
			"(SELECT f.manga_id FROM favourites f WHERE f.deleted_at = 0 " +
			"AND (:categoryId IS NULL OR f.category_id = :categoryId))",
	)
	fun observeFavouritesWithoutChaptersCount(categoryId: Long?): Flow<Int>

	/**
	 * Records a freshly fetched count without touching the rest of the row.
	 *
	 * An upsert would replace the whole row from whatever projection the caller happens
	 * to hold, which is how a title's stored cover or author gets quietly downgraded.
	 */
	@Query("UPDATE manga SET chapters_count = :count WHERE manga_id = :id AND :count > 0")
	suspend fun setChaptersCount(id: Long, count: Int)
}

@Dao
interface FavouriteCategoriesDao {

	@Query("SELECT * FROM favourite_categories ORDER BY sort_key ASC, created_at ASC")
	fun observeAll(): Flow<List<FavouriteCategoryEntity>>

	@Query("SELECT * FROM favourite_categories ORDER BY sort_key ASC, created_at ASC")
	suspend fun getAll(): List<FavouriteCategoryEntity>

	@Query("SELECT COUNT(*) FROM favourite_categories")
	suspend fun count(): Int

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(category: FavouriteCategoryEntity): Long

	@Update
	suspend fun update(category: FavouriteCategoryEntity)

	@Query("UPDATE favourite_categories SET title = :title WHERE category_id = :id")
	suspend fun rename(id: Long, title: String)

	/**
	 * Whether new chapters are looked for in this category.
	 *
	 * The column has been in the schema since the Android app's and nothing ever set it.
	 * A shelf of finished titles has no reason to be checked, and checking it is a live
	 * request per title.
	 */
	@Query("UPDATE favourite_categories SET track = :track WHERE category_id = :id")
	suspend fun setTracked(id: Long, track: Boolean)

	@Query("DELETE FROM favourite_categories WHERE category_id = :id")
	suspend fun delete(id: Long)

	@Query("SELECT COALESCE(MAX(sort_key), -1) + 1 FROM favourite_categories")
	suspend fun nextSortKey(): Int
}

@Dao
interface FavouritesDao {

	@Query(
		"SELECT * FROM favourites WHERE category_id = :categoryId AND deleted_at = 0 " +
			"ORDER BY created_at DESC",
	)
	fun observeByCategory(categoryId: Long): Flow<List<FavouriteWithManga>>

	/**
	 * Every favourite row, including a title that sits in several categories.
	 *
	 * Previously `GROUP BY manga_id`, which silently dropped the second and later rows
	 * for such a title. That is wrong for backup, which must round-trip every
	 * membership. Callers wanting one card per title de-duplicate in Kotlin, where the
	 * intent is visible.
	 */
	@Query("SELECT * FROM favourites WHERE deleted_at = 0 ORDER BY created_at DESC")
	fun observeAll(): Flow<List<FavouriteWithManga>>

	@Query("SELECT category_id FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0")
	fun observeCategoriesOf(mangaId: Long): Flow<List<Long>>

	@Query("SELECT COUNT(*) FROM favourites WHERE category_id = :categoryId AND deleted_at = 0")
	fun observeCount(categoryId: Long): Flow<Int>

	@Upsert
	suspend fun upsert(favourite: FavouriteEntity)

	@Query("DELETE FROM favourites WHERE manga_id = :mangaId AND category_id = :categoryId")
	suspend fun remove(mangaId: Long, categoryId: Long)

	@Query("DELETE FROM favourites WHERE manga_id = :mangaId")
	suspend fun removeFromAll(mangaId: Long)
}

@Dao
interface HistoryDao {

	@Query("SELECT * FROM history WHERE deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit")
	fun observeRecent(limit: Int): Flow<List<HistoryWithManga>>

	@Query("SELECT * FROM history WHERE manga_id = :mangaId AND deleted_at = 0")
	suspend fun find(mangaId: Long): HistoryEntity?

	@Upsert
	suspend fun upsert(history: HistoryEntity)

	@Query("DELETE FROM history WHERE manga_id = :mangaId")
	suspend fun delete(mangaId: Long)

	@Query("DELETE FROM history")
	suspend fun clear()
}
