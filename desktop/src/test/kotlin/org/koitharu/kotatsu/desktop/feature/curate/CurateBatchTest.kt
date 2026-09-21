package org.koitharu.kotatsu.desktop.feature.curate

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import java.io.File

/** The batch operations, against a real database file. */
class CurateBatchTest {

	private lateinit var db: LibraryDatabase
	private lateinit var file: File
	private lateinit var repository: CurateRepository

	private var clock = 7_000L

	private var reading = 0L
	private var finished = 0L

	@Before
	fun setUp() = runBlocking {
		val opened = openTempDatabase("curate-batch")
		db = opened.first
		file = opened.second
		repository = CurateRepository(db) { clock }
		reading = db.putCategory("Reading", sortKey = 1)
		finished = db.putCategory("Finished", sortKey = 2)
		for (id in 1L..4L) {
			db.putManga(id, "Title $id", chaptersCount = id.toInt() * 10)
			db.putFavourite(id, reading, createdAt = 1_000L + id)
		}
	}

	@After
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `adding to a category is idempotent`() = runBlocking {
		val first = repository.addToCategory(listOf(1L, 2L), finished)
		assertEquals(2, first.affected)
		assertEquals(0, first.skipped)
		assertEquals(6, db.rowCount("favourites"))

		val second = repository.addToCategory(listOf(1L, 2L), finished)
		assertEquals(0, second.affected)
		assertEquals(2, second.skipped)
		assertEquals("already in this category", second.skippedReason)
		// No new rows, and nothing rewritten: a second click must not move the title to
		// the top of "recently added".
		assertEquals(6, db.rowCount("favourites"))
		val addedAt = repository.observeItems().first().first { it.id == 1L }.addedAt
		assertEquals(1_001L, addedAt)
	}

	@Test
	fun `adding a title that is no longer stored is reported, not thrown`() = runBlocking {
		val result = repository.addToCategory(listOf(1L, 999L), finished)
		assertEquals(1, result.affected)
		assertEquals(1, result.skipped)
		assertEquals("no longer saved", result.skippedReason)
		// The batch still applied for the id that survived: one bad id in a selection of
		// two hundred must not roll the other hundred and ninety nine back.
		assertEquals(5, db.rowCount("favourites"))
	}

	@Test
	fun `removing from one category leaves the others intact`() = runBlocking {
		repository.addToCategory(listOf(1L, 2L, 3L), finished)
		val result = repository.removeFromCategory(listOf(1L, 2L), finished)
		assertEquals(2, result.affected)

		val items = repository.observeItems().first().associateBy { it.id }
		assertEquals(setOf(reading), items.getValue(1L).categoryIds)
		assertEquals(setOf(reading), items.getValue(2L).categoryIds)
		assertEquals(setOf(reading, finished), items.getValue(3L).categoryIds)
		assertEquals(4, items.size)
	}

	@Test
	fun `moving takes the title out of one category and puts it in the other`() = runBlocking {
		val result = repository.moveToCategory(listOf(1L, 2L), from = reading, to = finished)
		assertEquals(2, result.affected)
		val items = repository.observeItems().first().associateBy { it.id }
		assertEquals(setOf(finished), items.getValue(1L).categoryIds)
		assertEquals(setOf(reading), items.getValue(3L).categoryIds)
		// The original created_at follows the title: re-filing is not re-acquiring.
		assertEquals(1_001L, items.getValue(1L).addedAt)
		assertEquals(4, db.rowCount("favourites"))
	}

	@Test
	fun `moving a title that is already in the target does not duplicate it`() = runBlocking {
		repository.addToCategory(listOf(1L), finished)
		assertEquals(5, db.rowCount("favourites"))
		repository.moveToCategory(listOf(1L), from = reading, to = finished)
		assertEquals(setOf(finished), repository.observeItems().first().first { it.id == 1L }.categoryIds)
		assertEquals(4, db.rowCount("favourites"))
	}

	@Test
	fun `removing from the library drops every membership and keeps history`() = runBlocking {
		repository.addToCategory(listOf(1L), finished)
		db.putHistory(1L, percent = 0.6f)
		val result = repository.removeFromLibrary(listOf(1L))
		assertEquals(1, result.affected)
		assertTrue(repository.observeItems().first().none { it.id == 1L })
		// Stated in the confirmation, so it has to be true.
		assertNotNull(db.historyDao().find(1L))
		assertNotNull(db.mangaDao().find(1L))
	}

	@Test
	fun `marking read sets the position to the end and marking unread resets it`() = runBlocking {
		db.putHistory(1L, page = 4, percent = 0.25f, updatedAt = 2_000L)
		db.putHistory(2L, page = 9, percent = 0.9f, updatedAt = 2_000L)

		val read = repository.markHistory(listOf(1L, 2L), read = true)
		assertEquals(2, read.affected)
		assertEquals(1f, db.historyDao().find(1L)!!.percent, 0.0001f)
		assertEquals(clock, db.historyDao().find(1L)!!.updatedAt)

		val unread = repository.markHistory(listOf(1L, 2L), read = false)
		assertEquals(2, unread.affected)
		assertEquals(0f, db.historyDao().find(2L)!!.percent, 0.0001f)
		assertEquals(0, db.historyDao().find(2L)!!.page)
	}

	@Test
	fun `marking a never-opened title is reported as skipped`() = runBlocking {
		db.putHistory(1L, percent = 0.5f)
		val result = repository.markHistory(listOf(1L, 2L, 3L), read = true)
		assertEquals(1, result.affected)
		assertEquals(2, result.skipped)
		assertEquals("never opened", result.skippedReason)
		// Skipped means skipped: no invented history row for a title never opened.
		assertNull(db.historyDao().find(2L))
		assertEquals(1, db.rowCount("history"))
	}

	@Test
	fun `deleting history keeps the titles in the library`() = runBlocking {
		db.putHistory(1L)
		db.putHistory(2L)
		val result = repository.deleteHistory(listOf(1L, 2L, 3L))
		assertEquals(2, result.affected)
		assertEquals(0, db.rowCount("history"))
		assertEquals(4, repository.observeItems().first().size)
	}

	@Test
	fun `a title in two categories appears once, dated from its first membership`() = runBlocking {
		repository.addToCategory(listOf(1L), finished)
		val items = repository.observeItems().first().filter { it.id == 1L }
		assertEquals(1, items.size)
		assertEquals(setOf(reading, finished), items.single().categoryIds)
		assertEquals(1_001L, items.single().addedAt)
	}

	@Test
	fun `items carry the reading position the sort keys need`() = runBlocking {
		db.putHistory(2L, percent = 0.42f, updatedAt = 6_000L)
		val items = repository.observeItems().first().associateBy { it.id }
		val read = items.getValue(2L)
		assertTrue(read.hasHistory)
		assertEquals(0.42f, read.progress, 0.0001f)
		assertEquals(6_000L, read.lastReadAt)
		val unread = items.getValue(3L)
		assertFalse(unread.hasHistory)
		assertEquals(0L, unread.lastReadAt)
		assertEquals(30, unread.chaptersCount)
	}

	@Test
	fun `moving a category onto itself is refused before anything is written`() = runBlocking {
		val error = runCatching { repository.moveToCategory(listOf(1L), reading, reading) }
		assertTrue(error.exceptionOrNull() is IllegalArgumentException)
		assertEquals(4, db.rowCount("favourites"))
	}
}
