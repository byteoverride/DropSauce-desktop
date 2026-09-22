package org.koitharu.kotatsu.desktop.feature.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assume.assumeTrue
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real chapter, downloaded from a real source, onto a real disk.
 *
 * [DownloadManagerTest] covers the queue thoroughly against a fake page source, which is
 * the right way to test the state machine. It says nothing about `ParserPageSource`,
 * which is the half that talks to a site, and until a download could be started from
 * anywhere in the app that half had never run outside somebody's manual trial.
 *
 * Gated behind `-Dlive=true`, like the parser tests.
 */
class DownloadLiveTest {

	private val dbFile = Files.createTempFile("download-live", ".db").toFile().also { it.delete() }
	private val storageDir = Files.createTempDirectory("download-live")
	private val db: LibraryDatabase = openLibraryDatabase(dbFile.toOkioPath())
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	@BeforeTest
	fun requireLive() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
	}

	@AfterTest
	fun tearDown() {
		db.close()
		dbFile.delete()
		storageDir.toFile().deleteRecursively()
	}

	@Test
	fun `a chapter queued from a details screen lands on disk`() = runBlocking {
		val registry = SourceRegistry { UA }
		val storage = DownloadStorage(storageDir.toOkioPath())
		val pageSource = ParserPageSource(registry) { registry.session(it).client }
		val repository = DownloadRepository(
			db = db,
			storage = storage,
			pageSource = pageSource,
			manager = DownloadManager(db = db, storage = storage, pageSource = pageSource, scope = scope),
		)

		val source = MangaParserSource.MANGADEX
		val parser = registry.session(source).parser
		val listed = parser.getList(0, SortOrder.POPULARITY, MangaListFilter())
		assumeTrue("the source listed nothing, so there is nothing to download", listed.isNotEmpty())
		val details = parser.getDetails(listed.first())
		val chapters = details.chapters.orEmpty()
		assumeTrue("the title has no chapters", chapters.isNotEmpty())

		// Exactly what the Download button does: one selection, then queue it.
		val wanted = ChapterSelection.First(1, chapters.first().branch).select(chapters)
		assertEquals(1, wanted.size)
		val queued = repository.download(details, wanted)
		assertEquals(1, queued)

		val finished = withTimeout(TIMEOUT_MS) {
			repository.observeFor(details.id).first { items ->
				items.isNotEmpty() && items.all { DownloadState.isFinished(it.state) }
			}
		}
		val item = finished.single()
		assertEquals(DownloadState.DONE, item.state, "download failed: ${item.error}")
		assertTrue(item.pagesTotal > 0, "a chapter with no pages is not a download")
		assertEquals(item.pagesTotal, item.pagesDone)

		// The rows are only half the claim. The pages have to actually be on disk, and
		// be big enough to be images rather than error bodies saved with a .jpg name.
		val paths = repository.localPagePaths(details.id, item.chapterId)
		assertEquals(item.pagesTotal, paths.size, "fewer files than pages")
		paths.forEach { path ->
			val file = path.toFile()
			assertTrue(file.isFile, "missing page file: $path")
			assertTrue(file.length() > 1_000, "page is ${file.length()} bytes, which is not an image: $path")
		}
		println("LIVE downloaded ${paths.size} pages of '${details.title}' to $storageDir")

		// And the reader must be able to find them again, which is the whole point of
		// having downloaded them.
		val offline = repository.localPages(details.id, item.chapterId)
		assertEquals(paths.size, offline.size)
	}

	private companion object {

		const val TIMEOUT_MS = 180_000L
		const val UA =
			"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/124.0.0.0 Safari/537.36"
	}
}
