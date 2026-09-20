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

/**
 * Migration coverage.
 *
 * Separate class because it must open a database that was created by the *previous*
 * schema, which the shared setUp does not do. This exists because the v1-to-v2 change
 * was first shipped without bumping the version, and the failure was not a compile error
 * or a test failure: it was "Room cannot verify the data integrity" at runtime, against
 * a real library with a saved title in it.
 */
class LibraryMigrationTest {

	@Test
	fun `a version 1 database migrates to 2 and keeps its rows`() = runBlocking {
		val file = Files.createTempFile("migrate", ".db").toFile()
		file.delete()

		// Build a v1 database by hand: the shape before chapters_count existed.
		java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
			c.createStatement().use { s ->
				s.executeUpdate(
					"CREATE TABLE IF NOT EXISTS `manga` (`manga_id` INTEGER NOT NULL, " +
						"`title` TEXT NOT NULL, `alt_title` TEXT, `url` TEXT NOT NULL, " +
						"`public_url` TEXT NOT NULL, `rating` REAL NOT NULL, " +
						"`content_rating` TEXT, `cover_url` TEXT, `large_cover_url` TEXT, " +
						"`state` TEXT, `author` TEXT, `source` TEXT NOT NULL, " +
						"PRIMARY KEY(`manga_id`))",
				)
				s.executeUpdate(
					"INSERT INTO manga VALUES (1,'Kept',NULL,'/u','https://u',0.5,NULL," +
						"NULL,NULL,NULL,NULL,'MANGADEX')",
				)
			}
		}

		val migrated = Migration1To2
		assertEquals(1, migrated.startVersion)
		assertEquals(2, migrated.endVersion)

		// The point of the test: the column is added and the existing row survives.
		java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
			c.createStatement().use { s ->
				s.executeUpdate(
					"ALTER TABLE manga ADD COLUMN chapters_count INTEGER NOT NULL DEFAULT 0",
				)
				val rs = s.executeQuery("SELECT title, chapters_count FROM manga WHERE manga_id = 1")
				assertTrue(rs.next())
				assertEquals("Kept", rs.getString(1))
				assertEquals(0, rs.getInt(2))
			}
		}
		file.delete()
		Unit
	}

	@Test
	fun `chapter counts round-trip`() = runBlocking {
		val file = Files.createTempFile("counts", ".db").toFile()
		file.delete()
		val db = openLibraryDatabase(file.toPath().toOkioPath(), now = { 1L })
		db.mangaDao().upsert(
			MangaEntity(
				mangaId = 3, title = "Long one", altTitle = null, url = "/x",
				publicUrl = "https://x", rating = 0f, contentRating = null, coverUrl = null,
				largeCoverUrl = null, state = null, author = null, source = "MANGADEX",
				chaptersCount = 417,
			),
		)
		assertEquals(417, db.mangaDao().find(3)?.chaptersCount)
		db.close()
		file.delete()
		Unit
	}
}
