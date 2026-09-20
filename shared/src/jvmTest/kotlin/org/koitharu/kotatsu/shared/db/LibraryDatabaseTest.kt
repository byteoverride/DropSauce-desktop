package org.koitharu.kotatsu.shared.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryDatabaseTest {

	private lateinit var db: LibraryDatabase
	private lateinit var file: java.io.File

	private fun manga(id: Long, title: String) = MangaEntity(
		mangaId = id, title = title, altTitle = null, url = "/m/$id",
		publicUrl = "https://example.test/m/$id", rating = 0.8f, contentRating = "SAFE",
		coverUrl = null, largeCoverUrl = null, state = "ONGOING", author = "Someone",
		source = "MANGADEX",
	)

	@BeforeTest
	fun setUp() {
		file = Files.createTempFile("library", ".db").toFile()
		file.delete()
		db = openLibraryDatabase(file.toPath().toOkioPath(), now = { 1_000L })
	}

	@AfterTest
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `a fresh database is seeded with the default category`() = runBlocking {
		// DECISIONS.md D14: this row is NOT in the schema's CREATE statements. Without
		// the prepopulate callback a new library opens with nowhere to put a favourite.
		val categories = db.favouriteCategoriesDao().getAll()
		assertEquals(1, categories.size)
		assertEquals("Read later", categories.single().title)
	}

	@Test
	fun `categories can be created, renamed and deleted`() = runBlocking {
		val dao = db.favouriteCategoriesDao()
		val id = dao.insert(
			FavouriteCategoryEntity(
				categoryId = 0, createdAt = 1L, sortKey = dao.nextSortKey(),
				title = "Reading", order = "NEWEST", track = true, isVisibleInLibrary = true,
			),
		)
		assertTrue(id > 0)
		assertEquals(2, dao.count())

		dao.rename(id, "Currently reading")
		assertEquals("Currently reading", dao.getAll().single { it.categoryId == id }.title)

		dao.delete(id)
		assertEquals(1, dao.count())
	}

	@Test
	fun `a manga can be favourited into a category and observed`() = runBlocking {
		val categoryId = db.favouriteCategoriesDao().getAll().first().categoryId
		db.mangaDao().upsert(manga(7, "Berserk"))
		db.favouritesDao().upsert(
			FavouriteEntity(mangaId = 7, categoryId = categoryId, sortKey = 0, createdAt = 5L, deletedAt = 0L),
		)
		val rows = db.favouritesDao().observeByCategory(categoryId).first()
		assertEquals(1, rows.size)
		assertEquals("Berserk", rows.single().manga.title)
		assertEquals(listOf(categoryId), db.favouritesDao().observeCategoriesOf(7).first())
	}

	@Test
	fun `deleting a category removes its favourites but keeps the manga`() = runBlocking {
		val dao = db.favouriteCategoriesDao()
		val id = dao.insert(
			FavouriteCategoryEntity(0, 1L, dao.nextSortKey(), "Temp", "NEWEST", false, true),
		)
		db.mangaDao().upsert(manga(9, "Vinland Saga"))
		db.favouritesDao().upsert(FavouriteEntity(9, id, 0, 5L, 0L))
		assertEquals(1, db.favouritesDao().observeByCategory(id).first().size)

		dao.delete(id)

		// ON DELETE CASCADE on category_id, but the manga row itself must survive
		// because history may still point at it.
		assertEquals(0, db.favouritesDao().observeByCategory(id).first().size)
		assertNotNull(db.mangaDao().find(9))
		Unit
	}

	@Test
	fun `history round-trips and survives reopening the database`() = runBlocking {
		db.mangaDao().upsert(manga(11, "Solo Leveling"))
		db.historyDao().upsert(
			HistoryEntity(
				mangaId = 11, createdAt = 1L, updatedAt = 2L, chapterId = 33L,
				page = 4, scroll = 0f, percent = 0.25f, chaptersCount = 10, deletedAt = 0L,
			),
		)
		db.close()

		// Reopen the same file: this is the "survives restart" requirement.
		db = openLibraryDatabase(file.toPath().toOkioPath(), now = { 1_000L })
		val recent = db.historyDao().observeRecent(10).first()
		assertEquals(1, recent.size)
		assertEquals("Solo Leveling", recent.single().manga.title)
		assertEquals(4, recent.single().history.page)
		// The callback must not run again on an existing database.
		assertEquals(1, db.favouriteCategoriesDao().count())
	}

	@Test
	fun `removing a manga from all categories leaves history intact`() = runBlocking {
		val categoryId = db.favouriteCategoriesDao().getAll().first().categoryId
		db.mangaDao().upsert(manga(13, "Chainsaw Man"))
		db.favouritesDao().upsert(FavouriteEntity(13, categoryId, 0, 5L, 0L))
		db.historyDao().upsert(HistoryEntity(13, 1L, 2L, 1L, 0, 0f, 0f, 5, 0L))

		db.favouritesDao().removeFromAll(13)

		assertEquals(0, db.favouritesDao().observeCategoriesOf(13).first().size)
		assertNotNull(db.historyDao().find(13))
		assertNull(db.favouritesDao().observeByCategory(categoryId).first().firstOrNull())
	}
}
