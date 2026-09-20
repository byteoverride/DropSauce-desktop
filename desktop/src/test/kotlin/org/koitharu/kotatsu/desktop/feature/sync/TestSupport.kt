package org.koitharu.kotatsu.desktop.feature.sync

import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.Path
import okio.Path.Companion.toOkioPath
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.BookmarkEntity
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity
import org.koitharu.kotatsu.shared.db.FavouriteEntity
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import org.koitharu.kotatsu.shared.settings.SettingsData
import org.koitharu.kotatsu.shared.settings.SettingsStore
import java.io.File

/** A settings store held in memory, so the backup tests need no config file. */
class FakeSettingsStore(initial: SettingsData = SettingsData()) : SettingsStore {

	private val state = MutableStateFlow(initial)

	override val data: StateFlow<SettingsData> get() = state

	override suspend fun update(transform: (SettingsData) -> SettingsData) {
		state.value = transform(state.value)
	}
}

/**
 * Opens a real database in [dir].
 *
 * A file rather than in-memory on purpose: `openLibraryDatabase` is the production entry
 * point, it installs `PrePopulateCallback`, and the seeded "Read later" category is the
 * exact thing the duplicate-category test is about.
 */
fun openTestDatabase(dir: File, name: String): LibraryDatabase =
	openLibraryDatabase(File(dir, name).toOkioPath())

fun File.okio(): Path = toOkioPath()

/** Row count straight from SQLite, so a test never measures through the code it is testing. */
suspend fun LibraryDatabase.rowCount(table: String): Int = useWriterConnection { connection ->
	connection.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
		if (statement.step()) statement.getLong(0).toInt() else 0
	}
}

/** Every count that a restore could plausibly disturb, in one snapshot. */
suspend fun LibraryDatabase.countSnapshot(): Map<String, Int> = listOf(
	"manga",
	"favourite_categories",
	"favourites",
	"history",
	"bookmarks",
	"tracks",
).associateWith { rowCount(it) }

suspend fun LibraryDatabase.putManga(
	id: Long,
	title: String,
	source: String = MangaParserSource.MANGADEX.name,
	chaptersCount: Int = 0,
) {
	mangaDao().upsert(
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
}

suspend fun LibraryDatabase.putCategory(title: String, sortKey: Int = 0, createdAt: Long = 1_000L): Long =
	favouriteCategoriesDao().insert(
		FavouriteCategoryEntity(
			categoryId = 0,
			createdAt = createdAt,
			sortKey = sortKey,
			title = title,
			order = "NEWEST",
			track = true,
			isVisibleInLibrary = true,
		),
	)

suspend fun LibraryDatabase.putFavourite(mangaId: Long, categoryId: Long, createdAt: Long = 2_000L) {
	favouritesDao().upsert(
		FavouriteEntity(
			mangaId = mangaId,
			categoryId = categoryId,
			sortKey = 0,
			createdAt = createdAt,
			deletedAt = 0L,
		),
	)
}

suspend fun LibraryDatabase.putHistory(
	mangaId: Long,
	chapterId: Long,
	page: Int,
	percent: Float,
	updatedAt: Long,
) {
	historyDao().upsert(
		HistoryEntity(
			mangaId = mangaId,
			createdAt = updatedAt - 500L,
			updatedAt = updatedAt,
			chapterId = chapterId,
			page = page,
			scroll = 0f,
			percent = percent,
			chaptersCount = 10,
			deletedAt = 0L,
		),
	)
}

suspend fun LibraryDatabase.putBookmark(mangaId: Long, pageId: Long, page: Int) {
	bookmarksDao().upsert(
		BookmarkEntity(
			mangaId = mangaId,
			pageId = pageId,
			chapterId = 77L,
			page = page,
			scroll = 0,
			imageUrl = "https://example.test/page/$pageId.jpg",
			createdAt = 3_000L,
			percent = 0.5f,
		),
	)
}

/** A [Manga] with the fields the tracker actually reads, built the way LibraryRepository does. */
fun testManga(
	id: Long,
	title: String,
	source: MangaParserSource = MangaParserSource.MANGADEX,
	chapters: List<MangaChapter>? = null,
) = Manga(
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
	chapters = chapters,
	source = source,
)

fun testChapters(
	count: Int,
	source: MangaParserSource = MangaParserSource.MANGADEX,
	firstId: Long = 100L,
): List<MangaChapter> = (0 until count).map { index ->
	MangaChapter(
		id = firstId + index,
		title = "Chapter ${index + 1}",
		number = (index + 1).toFloat(),
		volume = 0,
		url = "/chapter/${firstId + index}",
		scanlator = null,
		uploadDate = 10_000L + index * 1_000L,
		branch = null,
		source = source,
	)
}
