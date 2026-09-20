package org.koitharu.kotatsu.desktop.feature.migration

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.shared.db.BookmarkEntity
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity
import org.koitharu.kotatsu.shared.db.FavouriteEntity
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.TrackEntity
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.nio.file.Files

/**
 * Migration against a real database file.
 *
 * A real file rather than in-memory, for the same reason the bookmarks tests use one: the
 * foreign keys onto `manga` and the composite primary keys are schema behaviour, and half
 * of what is being asserted here is that the schema enforces what the repository assumes.
 */
class MigrationRepositoryTest {

	private lateinit var db: LibraryDatabase
	private lateinit var file: java.io.File
	private lateinit var repository: MigrationRepository

	@Before
	fun setUp() {
		file = Files.createTempFile("migration", ".db").toFile()
		file.delete()
		db = openLibraryDatabase(file.toPath().toOkioPath(), now = { 1_000L })
		repository = MigrationRepository(db, now = { 5_000L })
	}

	@After
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `favourites move to the new title and the old one leaves the library`() = runBlocking {
		val reading = category("Reading")
		val favourites = category("Favourites")
		val old = manga(1L, "Solo Leveling", chapters = listOf(chapter(11L, 1f), chapter(12L, 2f)))
		val new = manga(2L, "Solo Leveling", NEW_SOURCE, listOf(chapter(21L, 1f, NEW_SOURCE)))
		store(old)
		favourite(old, reading, sortKey = 3, createdAt = 700L)
		favourite(old, favourites, sortKey = 4, createdAt = 800L)

		val result = repository.migrate(old, new)

		assertEquals(2, result.favouritesMoved)
		assertEquals(setOf(reading, favourites), categoriesOf(2L))
		assertEquals(emptySet<Long>(), categoriesOf(1L))
		// The row keeps its place in each category rather than jumping to the top.
		assertEquals(listOf(3 to 700L, 4 to 800L), favouriteKeysOf(2L).sortedBy { it.first })
	}

	@Test
	fun `history lands on the chapter with the same number, not the same position`() = runBlocking {
		// The new source has an extra chapter 0 at the front, so a positional copy would
		// land one chapter early. Only a number match gets this right.
		val old = manga(
			1L,
			"Berserk",
			chapters = listOf(chapter(11L, 1f), chapter(12L, 2f), chapter(13L, 3f)),
		)
		val new = manga(
			2L,
			"Berserk",
			NEW_SOURCE,
			listOf(
				chapter(20L, 0.5f, NEW_SOURCE),
				chapter(21L, 1f, NEW_SOURCE),
				chapter(22L, 2f, NEW_SOURCE),
				chapter(23L, 3f, NEW_SOURCE),
			),
		)
		store(old)
		db.historyDao().upsert(
			HistoryEntity(
				mangaId = 1L,
				createdAt = 100L,
				updatedAt = 200L,
				chapterId = 12L,
				page = 7,
				scroll = 0f,
				percent = 0.66f,
				chaptersCount = 3,
				deletedAt = 0L,
			),
		)

		val result = repository.migrate(old, new)

		assertTrue(result.historyMoved)
		assertTrue(result.historyMatchedByNumber)
		assertNull(db.historyDao().find(1L))
		val moved = checkNotNull(db.historyDao().find(2L))
		assertEquals(22L, moved.chapterId)
		// Position within the chapter, and when it was first read, are the user's and survive.
		assertEquals(7, moved.page)
		assertEquals(100L, moved.createdAt)
		assertEquals(0.66f, moved.percent, 0.0001f)
		assertEquals(4, moved.chaptersCount)
	}

	@Test
	fun `a bookmark whose chapter has no counterpart is dropped, not mis-pointed`() = runBlocking {
		// New source is missing chapter 2 entirely. An ordinal fallback would map old
		// chapter 2 onto new index 1, which is chapter 3: the exact silent mis-point this
		// is meant to prevent.
		val old = manga(
			1L,
			"Vagabond",
			chapters = listOf(chapter(11L, 1f), chapter(12L, 2f), chapter(13L, 3f)),
		)
		val new = manga(
			2L,
			"Vagabond",
			NEW_SOURCE,
			listOf(chapter(21L, 1f, NEW_SOURCE), chapter(23L, 3f, NEW_SOURCE)),
		)
		store(old)
		bookmark(1L, pageId = 101L, chapterId = 11L, page = 1)
		bookmark(1L, pageId = 102L, chapterId = 12L, page = 2)
		bookmark(1L, pageId = 103L, chapterId = 13L, page = 3)

		val result = repository.migrate(old, new)

		assertEquals(2, result.bookmarksMoved)
		assertEquals(1, result.bookmarksDropped)
		assertEquals(0, bookmarksOf(1L).size)
		val moved = bookmarksOf(2L).associateBy { it.pageId }
		assertEquals(setOf(101L, 103L), moved.keys)
		assertEquals(21L, moved.getValue(101L).chapterId)
		assertEquals(23L, moved.getValue(103L).chapterId)
	}

	@Test
	fun `migrateProgress false moves favourites and deletes history and bookmarks`() = runBlocking {
		val reading = category("Reading")
		val old = manga(1L, "Blame", chapters = listOf(chapter(11L, 1f)))
		val new = manga(2L, "Blame", NEW_SOURCE, listOf(chapter(21L, 1f, NEW_SOURCE)))
		store(old)
		favourite(old, reading)
		db.historyDao().upsert(
			HistoryEntity(1L, 100L, 200L, 11L, 3, 0f, 0.5f, 1, 0L),
		)
		bookmark(1L, pageId = 101L, chapterId = 11L, page = 1)
		db.tracksDao().upsert(TrackEntity(1L, 11L, 0L, 0, 0L, null))

		val result = repository.migrate(old, new, migrateProgress = false)

		assertEquals(1, result.favouritesMoved)
		assertFalse(result.historyMoved)
		assertEquals(0, result.bookmarksMoved)
		assertFalse(result.trackMoved)
		assertEquals(setOf(reading), categoriesOf(2L))
		// Gone from both ids: the old entry took its progress with it, and the new one
		// never had any.
		assertNull(db.historyDao().find(1L))
		assertNull(db.historyDao().find(2L))
		assertEquals(0, bookmarksOf(1L).size)
		assertEquals(0, bookmarksOf(2L).size)
		assertNull(db.tracksDao().find(1L))
		assertNull(db.tracksDao().find(2L))
	}

	@Test
	fun `tracking follows the title and adopts the new source's newest chapter`() = runBlocking {
		val old = manga(1L, "Dorohedoro", chapters = listOf(chapter(11L, 1f)))
		val new = manga(
			2L,
			"Dorohedoro",
			NEW_SOURCE,
			listOf(chapter(21L, 1f, NEW_SOURCE), chapter(22L, 2f, NEW_SOURCE)),
		)
		store(old)
		db.tracksDao().upsert(TrackEntity(1L, 11L, 0L, 0, 0L, "an old error"))

		val result = repository.migrate(old, new)

		assertTrue(result.trackMoved)
		assertNull(db.tracksDao().find(1L))
		val moved = checkNotNull(db.tracksDao().find(2L))
		// Not 11L. Carrying the old id across would make the next check report the new
		// source's entire back catalogue as unread.
		assertEquals(22L, moved.lastChapterId)
		assertEquals(0, moved.newChapters)
		assertNull(moved.lastError)
	}

	/**
	 * The most important test in this file.
	 *
	 * The failure is injected through the existing `now` parameter rather than a test-only
	 * hook in production code. `now()` is first called in the tracking step, which is the
	 * last of four, so by the time it throws the transaction has already inserted the new
	 * manga row, moved two favourite rows, moved a bookmark and moved the history row. If
	 * the transaction is not doing its job, every one of those is visible afterwards.
	 */
	@Test
	fun `a failure midway leaves the database exactly as it was`() = runBlocking {
		val reading = category("Reading")
		val favourites = category("Favourites")
		val old = manga(1L, "Berserk", chapters = listOf(chapter(11L, 1f), chapter(12L, 2f)))
		val new = manga(
			2L,
			"Berserk",
			NEW_SOURCE,
			listOf(chapter(21L, 1f, NEW_SOURCE), chapter(22L, 2f, NEW_SOURCE)),
		)
		store(old)
		favourite(old, reading)
		favourite(old, favourites)
		db.historyDao().upsert(HistoryEntity(1L, 100L, 200L, 12L, 4, 0f, 0.9f, 2, 0L))
		bookmark(1L, pageId = 101L, chapterId = 11L, page = 1)
		db.tracksDao().upsert(TrackEntity(1L, 11L, 0L, 0, 0L, null))

		val tables = listOf(
			"manga" to "manga_id",
			"favourites" to "manga_id, category_id",
			"history" to "manga_id",
			"bookmarks" to "manga_id, page_id",
			"tracks" to "manga_id",
		)
		val countsBefore = tables.associate { (table, _) -> table to db.countOf(table) }
		val dumpsBefore = tables.associate { (table, order) -> table to db.dumpOf(table, order) }

		val failing = MigrationRepository(db, now = { error("the clock exploded") })
		var thrown: Throwable? = null
		try {
			failing.migrate(old, new)
		} catch (e: IllegalStateException) {
			thrown = e
		}
		assertNotNull("the migration was supposed to fail", thrown)

		for ((table, _) in tables) {
			assertEquals("row count of $table", countsBefore[table], db.countOf(table))
		}
		for ((table, order) in tables) {
			assertEquals("contents of $table", dumpsBefore[table], db.dumpOf(table, order))
		}
		// And specifically: nothing leaked onto the new id.
		assertNull(db.mangaDao().find(2L))
		assertEquals(emptySet<Long>(), categoriesOf(2L))
		assertNull(db.historyDao().find(2L))
	}

	/**
	 * Positive control for the test above.
	 *
	 * Without it, "the row counts did not change" would be equally consistent with a
	 * transaction that works and with a migrate() that never wrote anything in the first
	 * place. This proves the same fixture, run without the injected failure, does move.
	 */
	@Test
	fun `the same migration without the injected failure does change the database`() = runBlocking {
		val reading = category("Reading")
		val old = manga(1L, "Berserk", chapters = listOf(chapter(11L, 1f), chapter(12L, 2f)))
		val new = manga(
			2L,
			"Berserk",
			NEW_SOURCE,
			listOf(chapter(21L, 1f, NEW_SOURCE), chapter(22L, 2f, NEW_SOURCE)),
		)
		store(old)
		favourite(old, reading)
		db.historyDao().upsert(HistoryEntity(1L, 100L, 200L, 12L, 4, 0f, 0.9f, 2, 0L))
		bookmark(1L, pageId = 101L, chapterId = 11L, page = 1)
		db.tracksDao().upsert(TrackEntity(1L, 11L, 0L, 0, 0L, null))

		repository.migrate(old, new)

		assertNotNull(db.mangaDao().find(2L))
		assertEquals(setOf(reading), categoriesOf(2L))
		assertNotNull(db.historyDao().find(2L))
		assertEquals(1, bookmarksOf(2L).size)
		assertNotNull(db.tracksDao().find(2L))
	}

	@Test
	fun `migrating a title onto itself is refused`() = runBlocking {
		val old = manga(1L, "Berserk", chapters = listOf(chapter(11L, 1f)))
		store(old)
		var thrown: Throwable? = null
		try {
			repository.migrate(old, old)
		} catch (e: IllegalArgumentException) {
			thrown = e
		}
		assertNotNull(thrown)
		Unit
	}

	private suspend fun store(manga: Manga) {
		db.mangaDao().upsert(manga.toEntity(manga.chapters?.size ?: 0))
	}

	private suspend fun category(title: String): Long = db.favouriteCategoriesDao().insert(
		FavouriteCategoryEntity(
			categoryId = 0L,
			createdAt = 1_000L,
			sortKey = 0,
			title = title,
			order = "NEWEST",
			track = true,
			isVisibleInLibrary = true,
		),
	)

	private suspend fun favourite(manga: Manga, categoryId: Long, sortKey: Int = 0, createdAt: Long = 500L) {
		db.favouritesDao().upsert(
			FavouriteEntity(
				mangaId = manga.id,
				categoryId = categoryId,
				sortKey = sortKey,
				createdAt = createdAt,
				deletedAt = 0L,
			),
		)
	}

	private suspend fun bookmark(mangaId: Long, pageId: Long, chapterId: Long, page: Int) {
		db.bookmarksDao().upsert(
			BookmarkEntity(
				mangaId = mangaId,
				pageId = pageId,
				chapterId = chapterId,
				page = page,
				scroll = 0,
				imageUrl = "https://example.test/p/$pageId.jpg",
				createdAt = 900L,
				percent = 0.1f,
			),
		)
	}

	private suspend fun categoriesOf(mangaId: Long): Set<Long> =
		db.favouritesDao().observeCategoriesOf(mangaId).first().toSet()

	private suspend fun favouriteKeysOf(mangaId: Long): List<Pair<Int, Long>> =
		db.dumpOf("favourites", "manga_id, category_id")
			.map { it.split('|') }
			.filter { it[0] == mangaId.toString() }
			.map { it[2].toInt() to it[3].toLong() }

	private suspend fun bookmarksOf(mangaId: Long) =
		db.bookmarksDao().observeByManga(mangaId).first()
}
