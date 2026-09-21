package org.koitharu.kotatsu.desktop.feature.curate

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import java.io.File

/**
 * Merging duplicates, and what happens when a merge fails half way.
 *
 * The rollback test is the important one in this area. Merge is the only operation here
 * that both writes and deletes across three tables, so a merge that half-applied would
 * take a reading position out of one row before it had landed in the other, and nothing
 * afterwards could tell that it had happened.
 */
class MergeTest {

	private lateinit var db: LibraryDatabase
	private lateinit var file: File
	private lateinit var repository: CurateRepository

	private var reading = 0L
	private var finished = 0L
	private var later = 0L

	/**
	 * Two entries for the same work.
	 *
	 * 1 is the survivor: in "Reading", barely started. 2 is the duplicate from another
	 * source: in "Finished" and "Read later", and much further along, so the merge has
	 * something to move in every table it touches.
	 */
	@Before
	fun setUp() = runBlocking {
		val opened = openTempDatabase("curate-merge")
		db = opened.first
		file = opened.second
		repository = CurateRepository(db) { 9_000L }
		reading = db.putCategory("Reading", sortKey = 1)
		finished = db.putCategory("Finished", sortKey = 2)
		later = db.putCategory("Backlog", sortKey = 3)

		db.putManga(1L, "Vinland Saga", source = MangaParserSource.MANGADEX.name, chaptersCount = 190)
		db.putManga(2L, "vinland saga", source = MangaParserSource.MANGAKAKALOT.name, chaptersCount = 60)
		db.putManga(3L, "Berserk", chaptersCount = 370)

		db.putFavourite(1L, reading, createdAt = 1_100L)
		db.putFavourite(2L, finished, createdAt = 1_200L)
		db.putFavourite(2L, later, createdAt = 1_300L)
		db.putFavourite(3L, reading, createdAt = 1_400L)

		db.putHistory(1L, chapterId = 11L, page = 2, percent = 0.10f, updatedAt = 5_000L, createdAt = 4_000L)
		db.putHistory(2L, chapterId = 22L, page = 8, percent = 0.75f, updatedAt = 6_000L, createdAt = 4_500L)
		db.putHistory(3L, chapterId = 33L, page = 1, percent = 0.30f, updatedAt = 7_000L)
	}

	@After
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `merge moves categories and history to the survivor and removes the others`() = runBlocking {
		val result = repository.merge(survivorId = 1L, otherIds = listOf(2L))
		assertEquals(1, result.removed)
		assertEquals(2, result.categoriesMoved)
		assertTrue(result.historyMoved)

		val items = repository.observeItems().first().associateBy { it.id }
		// The loser is gone from the library.
		assertNull(items[2L])
		assertEquals(2, items.size)

		// Its categories are now the survivor's, alongside the one it already had.
		assertEquals(setOf(reading, finished, later), items.getValue(1L).categoryIds)

		// The furthest reading position won, and it is on the survivor's row.
		val history = db.historyDao().find(1L)
		assertNotNull(history)
		assertEquals(22L, history!!.chapterId)
		assertEquals(8, history.page)
		assertEquals(0.75f, history.percent, 0.0001f)
		// The older start date is kept, so "reading since" does not reset on a merge.
		assertEquals(4_000L, history.createdAt)
		assertNull(db.historyDao().find(2L))

		// Nothing happened to the unrelated title.
		assertEquals(setOf(reading), items.getValue(3L).categoryIds)
		assertEquals(0.30f, db.historyDao().find(3L)!!.percent, 0.0001f)
	}

	@Test
	fun `the survivor keeps its own position when it is the furthest along`() = runBlocking {
		db.putHistory(1L, chapterId = 11L, page = 40, percent = 0.99f, updatedAt = 5_000L)
		val result = repository.merge(survivorId = 1L, otherIds = listOf(2L))
		assertFalse(result.historyMoved)
		assertEquals(11L, db.historyDao().find(1L)!!.chapterId)
		assertNull(db.historyDao().find(2L))
	}

	@Test
	fun `merging more than two entries folds them all in`() = runBlocking {
		db.putManga(4L, "Vinland  Saga!", chaptersCount = 12)
		db.putFavourite(4L, reading, createdAt = 1_500L)
		val result = repository.merge(survivorId = 1L, otherIds = listOf(2L, 4L))
		assertEquals(2, result.removed)
		val items = repository.observeItems().first().associateBy { it.id }
		assertNull(items[2L])
		assertNull(items[4L])
		assertEquals(setOf(reading, finished, later), items.getValue(1L).categoryIds)
	}

	@Test
	fun `the losing manga rows are left in place on purpose`() = runBlocking {
		repository.merge(survivorId = 1L, otherIds = listOf(2L))
		// `downloads` and `stats` point at `manga` with no foreign key. Deleting the row
		// would cascade bookmarks away and orphan files already on disk, so an entry is
		// "removed" when it has no memberships left, not when its cache row is gone.
		assertNotNull(db.mangaDao().find(2L))
	}

	@Test
	fun `a merge with no other entries is refused`() = runBlocking {
		val failure = runCatching { repository.merge(survivorId = 1L, otherIds = listOf(1L)) }
		assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
	}

	@Test
	fun `a mid-merge failure leaves the database exactly as it was`() = runBlocking {
		val before = db.mergeSnapshot()

		// POSITIVE CONTROL. The snapshot is the detector, so first prove it can see a
		// merge at all. Without this, an assertion that nothing changed would also pass
		// if the snapshot were blind, or if the merge had never run.
		repository.merge(survivorId = 1L, otherIds = listOf(2L))
		val afterSuccess = db.mergeSnapshot()
		assertNotEquals(before, afterSuccess)
		// The count is unchanged: three rows moved from the loser to the survivor. Which
		// is exactly why the snapshot carries the rows themselves and not just counts.
		assertEquals(4, afterSuccess["favourites"])
		assertEquals(2, afterSuccess["history"])

		// Same fixture again, this time failing after the categories have been written
		// and the losing rows cleared, which is the worst possible moment: the duplicate
		// has lost its memberships and its history has not moved yet.
		db.close()
		file.delete()
		setUp()
		val beforeFailure = db.mergeSnapshot()
		assertEquals(before, beforeFailure)

		val boom = runCatching {
			repository.merge(survivorId = 1L, otherIds = listOf(2L)) { stage ->
				if (stage == MergeStage.CATEGORIES_CLEARED) error("injected failure")
			}
		}
		assertTrue(boom.isFailure)
		assertEquals("injected failure", boom.exceptionOrNull()?.message)

		val afterFailure = db.mergeSnapshot()
		assertEquals(beforeFailure, afterFailure)
		assertEquals(4, afterFailure["favourites"])
		assertEquals(3, afterFailure["history"])
		// Read back through the repository too, so the check is not only against raw SQL.
		val items = repository.observeItems().first().associateBy { it.id }
		assertEquals(3, items.size)
		assertEquals(setOf(finished, later), items.getValue(2L).categoryIds)
		assertEquals(0.75f, db.historyDao().find(2L)!!.percent, 0.0001f)
		assertEquals(0.10f, db.historyDao().find(1L)!!.percent, 0.0001f)
	}

	@Test
	fun `a failure at every stage rolls the whole merge back`() = runBlocking {
		val before = db.mergeSnapshot()
		for (stage in MergeStage.entries) {
			val failure = runCatching {
				repository.merge(survivorId = 1L, otherIds = listOf(2L)) { reached ->
					if (reached == stage) error("injected at $stage")
				}
			}
			assertTrue("no failure at $stage", failure.isFailure)
			assertEquals("rolled back at $stage", before, db.mergeSnapshot())
		}
	}

	@Test
	fun `merging into a title that is not in the database changes nothing`() = runBlocking {
		val before = db.mergeSnapshot()
		val failure = runCatching { repository.merge(survivorId = 404L, otherIds = listOf(2L)) }
		assertTrue(failure.isFailure)
		assertEquals(before, db.mergeSnapshot())
	}
}
