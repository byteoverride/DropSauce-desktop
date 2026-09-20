package org.koitharu.kotatsu.shared.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Schema for the features added after the initial library: bookmarks, downloaded
 * chapters, imported local files, reading statistics and chapter tracking.
 *
 * Defined here, in one place, on purpose. These tables are written by separate feature
 * areas that are built in parallel, and a shared schema is exactly the kind of thing that
 * must have a single owner rather than five concurrent editors.
 *
 * Column names mirror the Android v37 schema where an equivalent table exists, so backup
 * interop (D15) stays possible.
 */

/** A saved position inside a chapter. */
@Entity(
	tableName = "bookmarks",
	primaryKeys = ["manga_id", "page_id"],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("manga_id")],
)
data class BookmarkEntity(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "page_id") val pageId: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "page") val page: Int,
	@ColumnInfo(name = "scroll") val scroll: Int,
	@ColumnInfo(name = "image_url") val imageUrl: String,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "percent") val percent: Float,
)

/**
 * A chapter whose pages have been written to disk.
 *
 * [state] is a plain string rather than an enum column because Room KMP would need a
 * converter for an enum and this schema has none anywhere (Agent A's finding), so the
 * mapping stays in Kotlin where it is visible.
 */
@Entity(
	tableName = "downloads",
	primaryKeys = ["manga_id", "chapter_id"],
	indices = [Index("manga_id")],
)
data class DownloadEntity(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "chapter_title") val chapterTitle: String,
	@ColumnInfo(name = "chapter_number") val chapterNumber: Float,
	/** QUEUED, RUNNING, DONE, FAILED, CANCELLED. */
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "pages_total") val pagesTotal: Int,
	@ColumnInfo(name = "pages_done") val pagesDone: Int,
	/** Directory holding the downloaded pages, empty until the download starts. */
	@ColumnInfo(name = "path") val path: String,
	@ColumnInfo(name = "error") val error: String?,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * A comic imported from disk, rather than fetched from a source.
 *
 * Keyed by path because that is what makes an import idempotent: re-importing the same
 * file must update the row rather than create a second one.
 */
@Entity(tableName = "local_index", indices = [Index(value = ["path"], unique = true)])
data class LocalMangaEntity(
	@PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long,
	@ColumnInfo(name = "path") val path: String,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "cover_entry") val coverEntry: String?,
	/** CBZ, ZIP, DIRECTORY, EPUB. */
	@ColumnInfo(name = "format") val format: String,
	@ColumnInfo(name = "chapters_count") val chaptersCount: Int,
	@ColumnInfo(name = "size_bytes") val sizeBytes: Long,
	@ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Time spent reading, one row per session, for the statistics screen. */
@Entity(tableName = "stats", indices = [Index("manga_id")])
data class StatsEntity(
	@PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long,
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "started_at") val startedAt: Long,
	@ColumnInfo(name = "duration") val duration: Long,
	@ColumnInfo(name = "pages") val pages: Int,
)

/**
 * A title being watched for new chapters.
 *
 * [lastChapterId] and [lastCheck] are what make "new" meaningful: without a remembered
 * chapter, every check would report everything as new.
 */
@Entity(
	tableName = "tracks",
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class TrackEntity(
	@PrimaryKey @ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "last_chapter_id") val lastChapterId: Long,
	@ColumnInfo(name = "last_chapter_date") val lastChapterDate: Long,
	@ColumnInfo(name = "chapters_new") val newChapters: Int,
	@ColumnInfo(name = "last_check") val lastCheck: Long,
	@ColumnInfo(name = "last_error") val lastError: String?,
)
