package org.koitharu.kotatsu.shared.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Per-title overrides and external service links.
 *
 * Added for the second feature batch. As with the other shared tables, this is defined
 * in one place rather than by whichever area happens to need it first.
 */

/**
 * Settings that belong to one title rather than the whole app.
 *
 * Mirrors the Android app's `manga_prefs`, which is what migration moves alongside
 * favourites. Every column is nullable so "not overridden" is distinguishable from
 * "overridden to the default", which matters: a title explicitly set to paged must not
 * silently follow a later change to the global default.
 */
@Entity(
	tableName = "manga_prefs",
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class MangaPrefsEntity(
	@PrimaryKey @ColumnInfo(name = "manga_id") val mangaId: Long,
	/** PagedLtr, PagedRtl, Webtoon, or null to follow the global default. */
	@ColumnInfo(name = "reading_mode") val readingMode: String?,
	@ColumnInfo(name = "title_override") val titleOverride: String?,
	@ColumnInfo(name = "cover_override") val coverOverride: String?,
	/** Preferred scanlator or branch, or null for no preference. */
	@ColumnInfo(name = "branch") val branch: String?,
	@ColumnInfo(name = "incognito") val incognito: Boolean,
)

/** A link between a local title and an entry on an external tracking service. */
@Entity(
	tableName = "scrobbling",
	primaryKeys = ["manga_id", "service"],
	indices = [Index("manga_id")],
)
data class ScrobblingEntity(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	/** ANILIST, MAL, SHIKIMORI, KITSU. */
	@ColumnInfo(name = "service") val service: String,
	@ColumnInfo(name = "remote_id") val remoteId: Long,
	@ColumnInfo(name = "target_id") val targetId: Long,
	@ColumnInfo(name = "status") val status: String?,
	@ColumnInfo(name = "chapter") val chapter: Int,
	@ColumnInfo(name = "rating") val rating: Float,
	@ColumnInfo(name = "comment") val comment: String?,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** A recommendation produced from what is already in the library. */
@Entity(tableName = "suggestions", indices = [Index("relevance")])
data class SuggestionEntity(
	@PrimaryKey @ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "relevance") val relevance: Float,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	/** Why it was suggested, shown to the user so a recommendation is never opaque. */
	@ColumnInfo(name = "reason") val reason: String,
)

@Dao
interface MangaPrefsDao {

	@Query("SELECT * FROM manga_prefs WHERE manga_id = :mangaId")
	suspend fun find(mangaId: Long): MangaPrefsEntity?

	@Query("SELECT * FROM manga_prefs WHERE manga_id = :mangaId")
	fun observe(mangaId: Long): Flow<MangaPrefsEntity?>

	@Upsert
	suspend fun upsert(prefs: MangaPrefsEntity)

	@Query("DELETE FROM manga_prefs WHERE manga_id = :mangaId")
	suspend fun delete(mangaId: Long)
}

@Dao
interface ScrobblingDao {

	@Query("SELECT * FROM scrobbling WHERE manga_id = :mangaId")
	fun observeFor(mangaId: Long): Flow<List<ScrobblingEntity>>

	@Query("SELECT * FROM scrobbling WHERE service = :service")
	suspend fun getByService(service: String): List<ScrobblingEntity>

	@Query("SELECT * FROM scrobbling WHERE manga_id = :mangaId AND service = :service")
	suspend fun find(mangaId: Long, service: String): ScrobblingEntity?

	@Upsert
	suspend fun upsert(entity: ScrobblingEntity)

	@Query("DELETE FROM scrobbling WHERE manga_id = :mangaId AND service = :service")
	suspend fun delete(mangaId: Long, service: String)

	@Query("DELETE FROM scrobbling WHERE service = :service")
	suspend fun deleteService(service: String)
}

@Dao
interface SuggestionsDao {

	@Query("SELECT * FROM suggestions ORDER BY relevance DESC LIMIT :limit")
	fun observeTop(limit: Int): Flow<List<SuggestionEntity>>

	@Query("DELETE FROM suggestions")
	suspend fun clear()

	@Upsert
	suspend fun upsertAll(items: List<SuggestionEntity>)
}
