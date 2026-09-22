package org.koitharu.kotatsu.desktop.feature.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.desktop.library.LibraryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tracker, with the network replaced by a [MangaDetailsFetcher] the test controls.
 *
 * Every case here is about the *bookkeeping*, which is what breaks: whether a grown list
 * is counted from the right place, whether an unchanged list leaves the row alone, and
 * whether one dead source can take the whole run down with it.
 */
class TrackerRepositoryTest {

	private lateinit var dir: File
	private lateinit var db: LibraryDatabase

	@Before
	fun setUp() {
		dir = Files.createTempDirectory("tracker-test").toFile()
		db = openTestDatabase(dir, "tracker.db")
	}

	@After
	fun tearDown() {
		db.close()
		dir.deleteRecursively()
	}

	private fun repository(fetcher: MangaDetailsFetcher, clock: Long = 1_000L) =
		TrackerRepository(db, fetcher, now = { clock })

	/** A fetcher that answers each manga id with a fixed chapter count. */
	private fun fetcherReturning(counts: Map<Long, Int>) = MangaDetailsFetcher { _, manga ->
		manga.copy(chapters = testChapters(counts.getValue(manga.id)))
	}

	@Test
	fun `a title whose chapter list grew reports the right new count`() = runBlocking {
		val manga = testManga(1L, "Grower", chapters = testChapters(3))
		repository(fetcherReturning(mapOf(1L to 3))).track(manga)
		// Three chapters at the time of tracking, five now, so two of them are new.
		val result = repository(fetcherReturning(mapOf(1L to 5)), clock = 2_000L).check(1L)

		assertEquals(2, result.newChapters)
		assertNull(result.error)
		val row = requireNotNull(db.tracksDao().find(1L))
		assertEquals(2, row.newChapters)
		assertEquals(104L, row.lastChapterId)
		assertEquals(2_000L, row.lastCheck)
		assertNull(row.lastError)
		// The stored count follows, so the library length filter stays honest.
		assertEquals(5, requireNotNull(db.mangaDao().find(1L)).chaptersCount)
	}

	@Test
	fun `a title whose chapter list did not change reports nothing new`() = runBlocking {
		val manga = testManga(2L, "Stable", chapters = testChapters(4))
		repository(fetcherReturning(mapOf(2L to 4))).track(manga)
		val before = requireNotNull(db.tracksDao().find(2L))

		val result = repository(fetcherReturning(mapOf(2L to 4)), clock = 2_000L).check(2L)

		assertEquals(0, result.newChapters)
		assertNull(result.error)
		val after = requireNotNull(db.tracksDao().find(2L))
		assertEquals(0, after.newChapters)
		assertEquals(before.lastChapterId, after.lastChapterId)
	}

	@Test
	fun `a newly tracked title does not claim its whole archive is new`() = runBlocking {
		// Tracked with no chapter list at all, so lastChapterId is 0 and the first check has
		// nothing to measure from. Reporting 12 new chapters there would be noise, not news.
		db.putManga(3L, "Fresh")
		db.tracksDao().upsert(
			org.koitharu.kotatsu.shared.db.TrackEntity(
				mangaId = 3L,
				lastChapterId = 0L,
				lastChapterDate = 0L,
				newChapters = 0,
				lastCheck = 0L,
				lastError = null,
			),
		)

		val result = repository(fetcherReturning(mapOf(3L to 12))).check(3L)

		assertEquals(0, result.newChapters)
		assertEquals(111L, requireNotNull(db.tracksDao().find(3L)).lastChapterId)
	}

	@Test
	fun `a source that throws records an error on that title without aborting the run`() = runBlocking {
		val good = testManga(10L, "Good", chapters = testChapters(2))
		val bad = testManga(11L, "Bad", chapters = testChapters(2))
		val alsoGood = testManga(12L, "Also good", chapters = testChapters(2))
		val seeding = fetcherReturning(mapOf(10L to 2, 11L to 2, 12L to 2))
		repository(seeding).track(good)
		repository(seeding).track(bad)
		repository(seeding).track(alsoGood)

		val attempts = AtomicInteger(0)
		val fetcher = MangaDetailsFetcher { _, manga ->
			attempts.incrementAndGet()
			if (manga.id == 11L) {
				throw IOException("the source is down")
			}
			manga.copy(chapters = testChapters(5))
		}

		val summary = repository(fetcher, clock = 3_000L).checkAll(concurrency = 2)

		assertEquals("every title must be attempted", 3, attempts.get())
		assertEquals(3, summary.checked)
		assertEquals(1, summary.failed)
		assertEquals(2, summary.titlesWithUpdates)
		assertEquals(6, summary.newChapters)

		val failed = requireNotNull(db.tracksDao().find(11L))
		assertTrue("the error text must reach the row", failed.lastError?.contains("down") == true)
		assertEquals("a failed check must not invent new chapters", 0, failed.newChapters)
		// Crucially the remembered chapter is untouched, so the next successful check still
		// measures from the right place rather than reporting the whole list as new.
		assertEquals(101L, failed.lastChapterId)
		assertEquals(3_000L, failed.lastCheck)

		assertEquals(3, requireNotNull(db.tracksDao().find(10L)).newChapters)
		assertNull(requireNotNull(db.tracksDao().find(12L)).lastError)
	}

	@Test
	fun `a successful check clears an error left by an earlier one`() = runBlocking {
		val manga = testManga(20L, "Flaky", chapters = testChapters(2))
		repository(fetcherReturning(mapOf(20L to 2))).track(manga)
		repository(MangaDetailsFetcher { _, _ -> throw IOException("down") }).check(20L)
		assertTrue(requireNotNull(db.tracksDao().find(20L)).lastError != null)

		repository(fetcherReturning(mapOf(20L to 3))).check(20L)

		val row = requireNotNull(db.tracksDao().find(20L))
		assertNull(row.lastError)
		assertEquals(1, row.newChapters)
	}

	@Test
	fun `new chapters accumulate across runs until they are marked seen`() = runBlocking {
		val manga = testManga(30L, "Serial", chapters = testChapters(2))
		repository(fetcherReturning(mapOf(30L to 2))).track(manga)

		repository(fetcherReturning(mapOf(30L to 4))).check(30L)
		assertEquals(2, requireNotNull(db.tracksDao().find(30L)).newChapters)
		repository(fetcherReturning(mapOf(30L to 5))).check(30L)
		// Two from the first run plus one from the second: checking twice between two reading
		// sessions must not lose the earlier batch from the badge.
		assertEquals(3, requireNotNull(db.tracksDao().find(30L)).newChapters)

		repository(fetcherReturning(mapOf(30L to 5))).clearNew(30L)
		assertEquals(0, requireNotNull(db.tracksDao().find(30L)).newChapters)
	}

	@Test
	fun `a title whose source this build does not have records an error rather than throwing`() =
		runBlocking {
			db.putManga(40L, "Mihon title", source = "MIHON_SOMETHING")
			db.tracksDao().upsert(
				org.koitharu.kotatsu.shared.db.TrackEntity(
					mangaId = 40L,
					lastChapterId = 1L,
					lastChapterDate = 0L,
					newChapters = 0,
					lastCheck = 0L,
					lastError = null,
				),
			)

			val result = repository(
				MangaDetailsFetcher { _, _ -> throw AssertionError("must not be fetched") },
			).check(40L)

			assertTrue(result.error?.contains("MIHON_SOMETHING") == true)
			assertTrue(requireNotNull(db.tracksDao().find(40L)).lastError != null)
		}

	@Test
	fun `check all never runs more than the permitted number of sources at once`() = runBlocking {
		val ids = (50L..57L).toList()
		val seeding = fetcherReturning(ids.associateWith { 1 })
		for (id in ids) {
			repository(seeding).track(testManga(id, "Title $id", chapters = testChapters(1)))
		}

		val live = AtomicInteger(0)
		val peak = AtomicInteger(0)
		val fetcher = MangaDetailsFetcher { _, manga ->
			val current = live.incrementAndGet()
			peak.updateAndGet { maxOf(it, current) }
			// Long enough that a semaphore-free implementation would overlap all eight.
			delay(50)
			live.decrementAndGet()
			manga.copy(chapters = testChapters(1))
		}

		val summary = repository(fetcher).checkAll(concurrency = 3)

		assertEquals(8, summary.checked)
		assertTrue("peak concurrency was ${peak.get()}, expected at most 3", peak.get() <= 3)
		// Positive control for the detector: if the checks ran one at a time the peak would be
		// 1 and the bound above would pass for the wrong reason.
		assertTrue("expected the checks to actually overlap, peak was ${peak.get()}", peak.get() > 1)
	}

	// Scoped as the Android app scopes it: saved titles only, and only in categories with
	// tracking on. History alone does not qualify, which is Android's default too.
	@Test
	fun `tracking covers saved titles in tracked categories, and is idempotent`() =
		runBlocking {
			db.putManga(70L, "Favourite one")
			db.putManga(71L, "Favourite two")
			db.putManga(72L, "Only read, never saved")
			val category = db.putCategory("Reading")
			db.putFavourite(70L, category)
			db.putFavourite(71L, category)
			db.putHistory(72L, chapterId = 1L, page = 0, percent = 0f, updatedAt = 1L)
			val repository = repository(fetcherReturning(emptyMap()))

			assertEquals(2, repository.trackEverythingKept())
			assertEquals(2, db.rowCount("tracks"))
			// Running it again must not duplicate, and must not reset a title that already
			// has new chapters recorded against it.
			assertEquals(0, repository.trackEverythingKept())
			assertEquals(2, db.rowCount("tracks"))
			assertNull(
				"reading something without saving it is not asking to be told about it",
				db.tracksDao().find(72L),
			)
		}

	// The button is no longer the only way in. Saving a title is the moment you begin
	// caring whether it updates, and waiting for someone to remember a button on another
	// screen is how a library ends up with four tracked titles out of three hundred.
	@Test
	fun `a category with tracking off is left out`() = runBlocking {
		db.putManga(90L, "On a watched shelf")
		db.putManga(91L, "On a finished shelf")
		val watched = db.putCategory("Reading")
		val finished = db.putCategory("Done")
		db.putFavourite(90L, watched)
		db.putFavourite(91L, finished)
		db.favouriteCategoriesDao().setTracked(finished, false)
		val repository = repository(fetcherReturning(emptyMap()))

		assertEquals(1, repository.trackEverythingKept())
		assertNotNull(db.tracksDao().find(90L))
		assertNull("a shelf you have finished with is not checked", db.tracksDao().find(91L))
	}

	// A title on two shelves, one still watched, must survive the other being turned off.
	@Test
	fun `turning a category off keeps titles another tracked category still holds`() = runBlocking {
		db.putManga(92L, "On both shelves")
		db.putManga(93L, "Only on the one being turned off")
		val watched = db.putCategory("Reading")
		val finished = db.putCategory("Done")
		db.putFavourite(92L, watched)
		db.putFavourite(92L, finished)
		db.putFavourite(93L, finished)
		val library = LibraryRepository(db)
		library.setCategoryTracked(finished, true)
		assertEquals(2, db.rowCount("tracks"))

		library.setCategoryTracked(finished, false)

		assertNotNull("still on a tracked shelf", db.tracksDao().find(92L))
		assertNull("only on the shelf that was turned off", db.tracksDao().find(93L))
	}

	@Test
	fun `saving a title to the library starts watching it`() = runBlocking {
		val category = db.putCategory("Reading")
		val library = LibraryRepository(db)
		library.setFavourite(testManga(80L, "Newly saved", MangaParserSource.MANGADEX, testChapters(2)), category, true)

		assertNotNull(db.tracksDao().find(80L))
	}

	@Test
	fun `saving into a category with tracking off does not start watching`() = runBlocking {
		val category = db.putCategory("Done")
		db.favouriteCategoriesDao().setTracked(category, false)
		val library = LibraryRepository(db)
		library.setFavourite(testManga(82L, "Filed away", MangaParserSource.MANGADEX, testChapters(2)), category, true)

		assertNull(db.tracksDao().find(82L))
	}

	@Test
	fun `saving a title that is already watched does not reset what it has seen`() = runBlocking {
		val category = db.putCategory("Reading")
		db.putManga(81L, "Already watched")
		db.tracksDao().upsert(
			org.koitharu.kotatsu.shared.db.TrackEntity(
				mangaId = 81L,
				lastChapterId = 555L,
				lastChapterDate = 9L,
				newChapters = 4,
				lastCheck = 9L,
				lastError = null,
			),
		)
		val library = LibraryRepository(db)
		library.setFavourite(testManga(81L, "Already watched", MangaParserSource.MANGADEX, testChapters(2)), category, true)

		val track = db.tracksDao().find(81L)
		assertNotNull(track)
		// Resetting these would make the very next check report the whole archive as new.
		assertEquals(555L, track!!.lastChapterId)
		assertEquals(4, track.newChapters)
	}

	@Test
	fun `tracking is idempotent and untracking removes the row`() = runBlocking {
		val manga: Manga = testManga(60L, "Once", MangaParserSource.MANGADEX, testChapters(3))
		val repository = repository(fetcherReturning(mapOf(60L to 3)))
		repository.track(manga)
		repository.track(manga)

		assertEquals(1, db.rowCount("tracks"))
		assertTrue(repository.isTracked(60L))

		repository.untrack(60L)
		assertEquals(0, db.rowCount("tracks"))
	}
}
