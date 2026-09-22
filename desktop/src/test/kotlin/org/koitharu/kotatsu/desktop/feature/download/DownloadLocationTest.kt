package org.koitharu.kotatsu.desktop.feature.download

import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.koitharu.kotatsu.shared.db.DownloadEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What happens to downloads when the folder they go to changes.
 *
 * The trap: reads used to recompute a chapter's directory from the current root, which
 * was correct while the root could never move and becomes silent data loss the moment it
 * is a setting. Every chapter downloaded before the change would read as empty while its
 * files sat on disk and its row still said DONE.
 */
class DownloadLocationTest {

	private val dbFile = Files.createTempFile("download-location", ".db").toFile().also { it.delete() }
	private val oldRoot = Files.createTempDirectory("old-downloads")
	private val newRoot = Files.createTempDirectory("new-downloads")
	private val db: LibraryDatabase = openLibraryDatabase(dbFile.toOkioPath())

	/** Points wherever the test last set it, the way the setting does. */
	private var root = oldRoot.toOkioPath()
	private val storage = DownloadStorage({ root })
	private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

	/** Nothing is downloaded here: the pages are written by hand, as an earlier run left them. */
	private val pageSource = FakePageSource(chapters = emptyList(), pageCount = 0)

	private fun repository() = DownloadRepository(
		db = db,
		storage = storage,
		pageSource = pageSource,
		manager = DownloadManager(db = db, storage = storage, pageSource = pageSource, scope = scope),
	)

	@AfterTest
	fun tearDown() {
		db.close()
		dbFile.delete()
		oldRoot.toFile().deleteRecursively()
		newRoot.toFile().deleteRecursively()
	}

	@Test
	fun `the root is read fresh, so a change applies without a restart`() {
		val before = storage.mangaDir("MANGADEX", 1L)
		root = newRoot.toOkioPath()
		val after = storage.mangaDir("MANGADEX", 1L)
		assertTrue(before != after, "the root was captured at construction")
		assertTrue(after.toString().startsWith(newRoot.toString()))
	}

	@Test
	fun `a chapter downloaded before the move still reads afterwards`() = runBlocking {
		val repository = repository()
		db.mangaDao().upsert(mangaRow())
		val chapterDir = storage.chapterDir("MANGADEX", MANGA_ID, CHAPTER_ID)
		storage.prepare(chapterDir)
		repeat(3) { index -> storage.writePage(chapterDir, index, bytesFor(index)) }
		db.downloadsDao().upsert(doneRow(path = chapterDir.toString()))

		assertEquals(3, repository.localPagePaths(MANGA_ID, CHAPTER_ID).size)

		// The reader moves their downloads folder. Nothing is copied, by design.
		root = newRoot.toOkioPath()

		val after = repository.localPagePaths(MANGA_ID, CHAPTER_ID)
		assertEquals(3, after.size, "the old chapter was orphaned by changing the folder")
		assertTrue(after.all { it.toString().startsWith(oldRoot.toString()) })
	}

	@Test
	fun `a row with no recorded path still resolves under the current root`() = runBlocking {
		val repository = repository()
		db.mangaDao().upsert(mangaRow())
		val chapterDir = storage.chapterDir("MANGADEX", MANGA_ID, CHAPTER_ID)
		storage.prepare(chapterDir)
		storage.writePage(chapterDir, 0, bytesFor(0))
		// Rows written before the path column carried anything.
		db.downloadsDao().upsert(doneRow(path = ""))

		assertEquals(1, repository.localPagePaths(MANGA_ID, CHAPTER_ID).size)
	}

	private fun mangaRow() = org.koitharu.kotatsu.shared.db.MangaEntity(
		mangaId = MANGA_ID,
		title = "Test",
		altTitle = null,
		url = "/manga/1",
		publicUrl = "https://example.test/manga/1",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		state = null,
		author = null,
		source = MangaParserSource.MANGADEX.name,
		chaptersCount = 1,
	)

	private fun doneRow(path: String) = DownloadEntity(
		mangaId = MANGA_ID,
		chapterId = CHAPTER_ID,
		chapterTitle = "Chapter 1",
		chapterNumber = 1f,
		state = DownloadState.DONE,
		pagesTotal = 3,
		pagesDone = 3,
		path = path,
		error = null,
		createdAt = 1L,
		updatedAt = 1L,
	)

	private companion object {

		const val MANGA_ID = 1L
		const val CHAPTER_ID = 100L
	}
}
