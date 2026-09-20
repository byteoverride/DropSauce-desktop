package org.koitharu.kotatsu.shared.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The desktop library schema.
 *
 * Column names and types deliberately mirror the Android app's v37 schema so a backup
 * written by one can be read by the other (DECISIONS.md D15). This is a subset: only the
 * tables the desktop v1 scope needs. Every column is a primitive, matching Agent A's
 * finding that the Android schema uses no `@TypeConverter` anywhere.
 */
@Entity(tableName = "manga")
data class MangaEntity(
	@PrimaryKey @ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "alt_title") val altTitle: String?,
	@ColumnInfo(name = "url") val url: String,
	@ColumnInfo(name = "public_url") val publicUrl: String,
	@ColumnInfo(name = "rating") val rating: Float,
	@ColumnInfo(name = "content_rating") val contentRating: String?,
	@ColumnInfo(name = "cover_url") val coverUrl: String?,
	@ColumnInfo(name = "large_cover_url") val largeCoverUrl: String?,
	@ColumnInfo(name = "state") val state: String?,
	@ColumnInfo(name = "author") val author: String?,
	@ColumnInfo(name = "source") val source: String,
)

@Entity(tableName = "favourite_categories")
data class FavouriteCategoryEntity(
	@PrimaryKey(autoGenerate = true) @ColumnInfo(name = "category_id") val categoryId: Long,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "sort_key") val sortKey: Int,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "order") val order: String,
	@ColumnInfo(name = "track") val track: Boolean,
	@ColumnInfo(name = "show_in_lib") val isVisibleInLibrary: Boolean,
)

@Entity(
	tableName = "favourites",
	primaryKeys = ["manga_id", "category_id"],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = FavouriteCategoryEntity::class,
			parentColumns = ["category_id"],
			childColumns = ["category_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("category_id")],
)
data class FavouriteEntity(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "category_id") val categoryId: Long,
	@ColumnInfo(name = "sort_key") val sortKey: Int,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

@Entity(
	tableName = "history",
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class HistoryEntity(
	@PrimaryKey @ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "page") val page: Int,
	@ColumnInfo(name = "scroll") val scroll: Float,
	@ColumnInfo(name = "percent") val percent: Float,
	@ColumnInfo(name = "chapters") val chaptersCount: Int,
	@ColumnInfo(name = "deleted_at") val deletedAt: Long,
)
