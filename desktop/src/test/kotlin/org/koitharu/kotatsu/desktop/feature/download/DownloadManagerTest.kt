package org.koitharu.kotatsu.desktop.feature.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.shared.db.DownloadEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.nio.file.Files

/**
 * End to end tests of the queue against a real Room database and a real filesystem.
 *
 * Only the network is faked. Substituting the database or the filesystem would leave
 * the two things most likely to be wrong, the state transitions and the temp-then-move
 * write, untested.
 */
class DownloadManagerTest {

	private lateinit var root: java.io.File
	private lateinit var dbFile: java.io.File
	private lateinit var db: LibraryDatabase
	private lateinit var storage: DownloadStorage
	private lateinit var scope: CoroutineScope

	private val chapter = testChapter(id = 7L)
	private val manga = testManga(chapters = listOf(chapter))

	@Before
	fun setUp() {
		root = Files.createTempDirectory("downloads-test").toFile()
		dbFile = java.io.File(root, "library.db")
		db = openLibraryDatabase(dbFile.toPath().toOkioPath())
		storage = DownloadStorage(root.toPath().toOkioPath())
		scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	}

	@After
	fun tearDown() {
		scope.cancel()
		db.close()
		root.deleteRecursively()
	}

	private fun manager(pageSource: PageSource, maxConcurrent: Int = 2, attempts: Int = 1) =
		DownloadManager(
			db = db,
			storage = storage,
			pageSource = pageSource,
			scope = scope,
			maxConcurrent = maxConcurrent,
			// One by default, so the tests that assert a failure do not sit through a
			// retry ladder for a page that is never going to answer.
			attempts = { attempts },
		)

	private fun chapterDir(): Path = storage.chapterDir(TEST_SOURCE.name, manga.id, chapter.id)

	@Test
	fun `a chapter downloads every page and finishes DONE`() = runBlocking {
		val source = FakePageSource(listOf(chapter), pageCount = 5)
		val manager = manager(source)

		assertEquals(1, manager.enqueue(manga, listOf(chapter)))
		manager.awaitIdle()

		val row = assertNotNull(db.downloadsDao().find(manga.id, chapter.id))
		assertEquals(DownloadState.DONE, row.state)
		assertEquals(5, row.pagesTotal)
		assertEquals(row.pagesTotal, row.pagesDone)
		assertNull(row.error)

		val files = storage.pages(chapterDir())
		assertEquals(5, files.size)
		// Natural order is reading order, and the bytes prove page N really is page N.
		assertEquals(listOf("0001.jpg", "0002.jpg", "0003.jpg", "0004.jpg", "0005.jpg"), files.map { it.name })
		files.forEachIndexed { index, path ->
			assertEquals("page $index", FileSystem.SYSTEM.read(path) { readUtf8() })
		}
		assertEquals(listOf(0, 1, 2, 3, 4), source.fetched.toList())
	}

	// The reported bug. One 404 fifty pages in used to fail the chapter and delete
	// everything already fetched, for a condition DECISIONS.md D20 records as normal:
	// a page 404s on first touch and succeeds once the node has it.
	@Test
	fun `a page that fails once is asked for again and the chapter completes`() = runBlocking {
		var refusals = 0
		val source = FakePageSource(
			chapters = listOf(chapter),
			pageCount = 4,
			onFetch = { index ->
				// The third page refuses twice then works, which is the shape D20
				// describes rather than a page that is genuinely gone.
				if (index == 2 && refusals < 2) {
					refusals++
					throw java.io.IOException("HTTP 404 for page $index")
				}
				bytesFor(index)
			},
		)
		val manager = manager(source, attempts = 3)

		manager.enqueue(manga, listOf(chapter))
		manager.awaitIdle()

		val row = db.downloadsDao().find(manga.id, chapter.id)
		assertEquals("gave up on a page that answers on retry: ${row?.error}", DownloadState.DONE, row?.state)
		assertEquals(4, row?.pagesDone)
		assertEquals(2, refusals)
	}

	@Test
	fun `a page that never answers still fails the chapter, after trying`() = runBlocking {
		val tries = java.util.concurrent.atomic.AtomicInteger(0)
		val source = FakePageSource(
			chapters = listOf(chapter),
			pageCount = 3,
			onFetch = { index ->
				if (index == 1) {
					tries.incrementAndGet()
					throw java.io.IOException("HTTP 404 for page $index")
				}
				bytesFor(index)
			},
		)
		val manager = manager(source, attempts = 3)

		manager.enqueue(manga, listOf(chapter))
		manager.awaitIdle()

		assertEquals(DownloadState.FAILED, db.downloadsDao().find(manga.id, chapter.id)?.state)
		// Tried the configured number of times, rather than once and rather than forever.
		assertEquals(3, tries.get())
	}

	@Test
	fun `a page that fails marks the chapter FAILED and never claims DONE`() = runBlocking {
		val source = FakePageSource(listOf(chapter), pageCount = 5) { index ->
			if (index == 2) throw IOException("HTTP 404 for page $index") else bytesFor(index)
		}
		val manager = manager(source)

		manager.enqueue(manga, listOf(chapter))
		manager.awaitIdle()

		val row = assertNotNull(db.downloadsDao().find(manga.id, chapter.id))
		assertEquals(DownloadState.FAILED, row.state)
		assertTrue("error should carry the cause: ${row.error}", row.error.orEmpty().contains("404"))
		assertTrue("pages_done must not claim the whole chapter", row.pagesDone < row.pagesTotal)
		// The two pages that did arrive are not kept: a partial chapter that looks like
		// a chapter is worse than no chapter.
		assertFalse(storage.exists(chapterDir()))
	}

	@Test
	fun `cancelling stops the download and leaves nothing that looks complete`() = runBlocking {
		val reachedSecondPage = CompletableDeferred<Unit>()
		val source = FakePageSource(listOf(chapter), pageCount = 5) { index ->
			if (index == 1) {
				reachedSecondPage.complete(Unit)
				// Hangs exactly like a stalled socket would, so the cancel lands in the
				// middle of the chapter rather than between two chapters.
				awaitCancellation()
			}
			bytesFor(index)
		}
		val manager = manager(source)

		manager.enqueue(manga, listOf(chapter))
		reachedSecondPage.await()
		manager.cancel(manga.id, chapter.id)

		val row = assertNotNull(db.downloadsDao().find(manga.id, chapter.id))
		assertEquals(DownloadState.CANCELLED, row.state)
		assertFalse("the chapter directory must be gone", storage.exists(chapterDir()))
		// Belt and braces on the temp-then-move rule: nothing half written survives
		// anywhere under the downloads tree either.
		assertTrue(allFiles(storage.downloadsRoot).none { it.name.endsWith(".part") })
	}

	@Test
	fun `a RUNNING row from a previous session is revived to QUEUED`() = runBlocking {
		db.mangaDao().upsert(manga.toEntity(1))
		db.downloadsDao().upsert(
			DownloadEntity(
				mangaId = manga.id,
				chapterId = chapter.id,
				chapterTitle = "Chapter 1",
				chapterNumber = 1f,
				// What a process that was killed mid-download leaves behind.
				state = DownloadState.RUNNING,
				pagesTotal = 5,
				pagesDone = 2,
				path = chapterDir().toString(),
				error = null,
				createdAt = 1L,
				updatedAt = 1L,
			),
		)
		val source = FakePageSource(listOf(chapter), pageCount = 5)
		val manager = manager(source)

		// Paused first so the revived row can be observed before a worker picks it up;
		// without this the assertion would be racing the queue it just restarted.
		manager.pause()
		manager.start()

		val revived = assertNotNull(db.downloadsDao().find(manga.id, chapter.id))
		assertEquals(DownloadState.QUEUED, revived.state)

		// And it is a real queue entry, not just a relabelled row: resuming finishes it.
		manager.resume()
		manager.awaitIdle()
		assertEquals(DownloadState.DONE, assertNotNull(db.downloadsDao().find(manga.id, chapter.id)).state)
	}

	@Test
	fun `deleting a download removes its row and its directory`() = runBlocking {
		val source = FakePageSource(listOf(chapter), pageCount = 3)
		val manager = manager(source)
		manager.enqueue(manga, listOf(chapter))
		manager.awaitIdle()
		assertTrue(storage.exists(chapterDir()))

		manager.delete(manga.id, chapter.id)

		assertNull(db.downloadsDao().find(manga.id, chapter.id))
		assertFalse(storage.exists(chapterDir()))
		// The title's directory goes too once it holds nothing.
		assertFalse(storage.exists(storage.mangaDir(TEST_SOURCE.name, manga.id)))
	}

	@Test
	fun `a whole title queues every chapter and respects the concurrency limit`() = runBlocking {
		val chapters = (1..6).map { testChapter(id = it.toLong(), number = it.toFloat()) }
		val title = testManga(chapters = chapters)
		val inFlight = java.util.concurrent.atomic.AtomicInteger()
		val peak = java.util.concurrent.atomic.AtomicInteger()
		val source = FakePageSource(chapters, pageCount = 2) { index ->
			val current = inFlight.incrementAndGet()
			peak.getAndUpdate { previous -> maxOf(previous, current) }
			try {
				// Long enough that overlapping downloads actually overlap; without it
				// each chapter could finish before the next one starts and the peak
				// would read 1 whatever the limit is.
				kotlinx.coroutines.delay(20)
				bytesFor(index)
			} finally {
				inFlight.decrementAndGet()
			}
		}
		val manager = manager(source, maxConcurrent = 2)

		assertEquals(6, manager.enqueue(title, chapters))
		manager.awaitIdle()

		for (item in chapters) {
			assertEquals(DownloadState.DONE, assertNotNull(db.downloadsDao().find(title.id, item.id)).state)
		}
		assertTrue("peak concurrency was ${peak.get()}", peak.get() in 1..2)
	}

	@Test
	fun `queueing a chapter twice does not create a second download`() = runBlocking {
		val source = FakePageSource(listOf(chapter), pageCount = 2)
		val manager = manager(source)
		manager.enqueue(manga, listOf(chapter))
		manager.awaitIdle()

		assertEquals(0, manager.enqueue(manga, listOf(chapter)))
		manager.awaitIdle()
		assertEquals(listOf(0, 1), source.fetched.toList())
	}

	private fun allFiles(dir: Path): List<Path> {
		if (!FileSystem.SYSTEM.exists(dir)) return emptyList()
		return FileSystem.SYSTEM.listRecursively(dir).toList()
	}
}

/** JUnit 4 has no non-null assertion that returns its argument, and every call needs one. */
private fun <T : Any> assertNotNull(value: T?): T {
	org.junit.Assert.assertNotNull(value)
	return value!!
}
