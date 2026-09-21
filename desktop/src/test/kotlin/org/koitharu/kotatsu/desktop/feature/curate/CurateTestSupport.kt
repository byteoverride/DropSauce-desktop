package org.koitharu.kotatsu.desktop.feature.curate

import androidx.room.useWriterConnection
import okio.Path.Companion.toOkioPath
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity
import org.koitharu.kotatsu.shared.db.FavouriteEntity
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.io.File
import java.nio.file.Files

/**
 * Fixtures for the organise area.
 *
 * Its own copy rather than the sync area's equivalent: two feature areas that share a
 * test helper cannot be worked on independently, which is the whole point of the
 * directory split.
 */

/**
 * A real database in a temp file.
 *
 * A file rather than in-memory, because the foreign keys and the `ON DELETE CASCADE` on
 * `favourites` are exactly what the batch operations rely on, and an in-memory database
 * configured by hand would be testing a different schema from the one that ships.
 */
fun openTempDatabase(prefix: String): Pair<LibraryDatabase, File> {
	val file = Files.createTempFile(prefix, ".db").toFile()
	file.delete()
	return openLibraryDatabase(file.toOkioPath(), now = { 1_000L }) to file
}

fun testManga(
	id: Long,
	title: String,
	source: MangaParserSource = MangaParserSource.MANGADEX,
): Manga = Manga(
	id = id,
	title = title,
	altTitles = emptySet(),
	url = "/manga/$id",
	publicUrl = "https://example.test/manga/$id",
	rating = 0.8f,
	contentRating = null,
	coverUrl = "https://example.test/cover/$id.jpg",
	largeCoverUrl = null,
	tags = emptySet(),
	state = null,
	authors = setOf("Author $id"),
	description = null,
	chapters = null,
	source = source,
)

/** A [CurateItem] for the pure tests, which never touch a database. */
fun testItem(
	id: Long,
	title: String,
	source: MangaParserSource = MangaParserSource.MANGADEX,
	chaptersCount: Int = 0,
	addedAt: Long = 0L,
	categoryIds: Set<Long> = emptySet(),
	lastReadAt: Long = 0L,
	progress: Float = 0f,
	hasHistory: Boolean = lastReadAt > 0L,
	incognito: Boolean = false,
): CurateItem = CurateItem(
	manga = testManga(id, title, source),
	sourceName = source.name,
	chaptersCount = chaptersCount,
	addedAt = addedAt,
	categoryIds = categoryIds,
	lastReadAt = lastReadAt,
	progress = progress,
	hasHistory = hasHistory,
	incognito = incognito,
)

suspend fun LibraryDatabase.putManga(
	id: Long,
	title: String,
	source: String = MangaParserSource.MANGADEX.name,
	chaptersCount: Int = 0,
) = mangaDao().upsert(
	MangaEntity(
		mangaId = id,
		title = title,
		altTitle = null,
		url = "/manga/$id",
		publicUrl = "https://example.test/manga/$id",
		rating = 0.8f,
		contentRating = null,
		coverUrl = "https://example.test/cover/$id.jpg",
		largeCoverUrl = null,
		state = null,
		author = "Author $id",
		source = source,
		chaptersCount = chaptersCount,
	),
)

suspend fun LibraryDatabase.putCategory(title: String, sortKey: Int = 0): Long =
	favouriteCategoriesDao().insert(
		FavouriteCategoryEntity(
			categoryId = 0,
			createdAt = 1_000L,
			sortKey = sortKey,
			title = title,
			order = "NEWEST",
			track = true,
			isVisibleInLibrary = true,
		),
	)

suspend fun LibraryDatabase.putFavourite(mangaId: Long, categoryId: Long, createdAt: Long = 2_000L) =
	favouritesDao().upsert(
		FavouriteEntity(
			mangaId = mangaId,
			categoryId = categoryId,
			sortKey = 0,
			createdAt = createdAt,
			deletedAt = 0L,
		),
	)

suspend fun LibraryDatabase.putHistory(
	mangaId: Long,
	chapterId: Long = 50L,
	page: Int = 3,
	percent: Float = 0.5f,
	updatedAt: Long = 5_000L,
	createdAt: Long = 4_000L,
) = historyDao().upsert(
	HistoryEntity(
		mangaId = mangaId,
		createdAt = createdAt,
		updatedAt = updatedAt,
		chapterId = chapterId,
		page = page,
		scroll = 0f,
		percent = percent,
		chaptersCount = 10,
		deletedAt = 0L,
	),
)

/** Row count straight from SQLite, so a test never measures through the code it is testing. */
suspend fun LibraryDatabase.rowCount(table: String): Int = useWriterConnection { connection ->
	connection.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
		if (statement.step()) statement.getLong(0).toInt() else 0
	}
}

/**
 * Counts plus the full contents of every table a merge can touch.
 *
 * Counts alone would pass a rollback that had swapped two rows over, which is precisely
 * the shape a half-applied merge has.
 */
suspend fun LibraryDatabase.mergeSnapshot(): Map<String, Any> = mapOf(
	"manga" to rowCount("manga"),
	"favourite_categories" to rowCount("favourite_categories"),
	"favourites" to rowCount("favourites"),
	"history" to rowCount("history"),
	"favourite_rows" to dumpRows("SELECT manga_id, category_id, created_at FROM favourites"),
	"history_rows" to dumpRows("SELECT manga_id, chapter_id, page, percent, updated_at FROM history"),
)

private suspend fun LibraryDatabase.dumpRows(sql: String): List<String> =
	useWriterConnection { connection ->
		connection.usePrepared(sql) { statement ->
			buildList {
				while (statement.step()) {
					add((0 until statement.getColumnCount()).joinToString("|") { statement.getText(it) })
				}
			}
		}
	}.sorted()
