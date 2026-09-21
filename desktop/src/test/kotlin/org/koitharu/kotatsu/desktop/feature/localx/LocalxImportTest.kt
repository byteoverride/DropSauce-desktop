package org.koitharu.kotatsu.desktop.feature.localx

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.desktop.feature.local.LocalArchives
import org.koitharu.kotatsu.desktop.feature.local.LocalPageRef
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.LocalMangaEntity
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * The importer against real files and a real Room database.
 *
 * Every page this suite asserts on is read back through `LocalArchives`, the same call
 * the reader makes. That is the assertion that matters: a chapter list that counts right
 * and cannot be opened is worse than no chapter list.
 */
class LocalxImportTest {

	private lateinit var temp: File
	private lateinit var db: LibraryDatabase
	private lateinit var importer: LocalxImporter

	@Before
	fun setUp() {
		temp = Files.createTempDirectory("localx-import-test").toFile()
		db = openLibraryDatabase(File(temp, "library.db").toOkioPath())
		importer = LocalxImporter(db.localLibraryDao()) { FIXED_NOW }
	}

	@After
	fun tearDown() {
		db.close()
		temp.deleteRecursively()
	}

	@Test
	fun `a multi-chapter cbz imports with the right chapters and page counts`() = runBlocking {
		val file = File(temp, "Series.cbz")
		Fixtures.zip(
			file,
			listOf(
				"Chapter 10/001.png",
				"Chapter 2/001.png",
				"Chapter 2/002.png",
				"Chapter 2/003.png",
				"Chapter 1/001.png",
				"Chapter 1/002.png",
			),
		)

		val preview = ready(importer.inspect(listOf(file)).single())
		assertEquals(2, preview.options.size)
		assertEquals("chapters", preview.options.first().key)

		val report = importer.commit(preview.options.first().candidates)
		assertEquals(emptyList<Any>(), report.failures)

		val row = rows().single()
		assertEquals(LocalxFormat.CBZ_CHAPTERS.dbName, row.format)
		assertEquals(3, row.chaptersCount)

		val chapters = readChapters(row)
		assertEquals(
			"Chapter 2 must come before Chapter 10",
			listOf("Chapter 1", "Chapter 2", "Chapter 10"),
			chapters.map { it.title },
		)
		assertEquals(listOf(2, 3, 1), chapters.map { it.pageCount })
		assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
		// Every page must actually come back as an image, through the same call the
		// reader makes.
		for (chapter in chapters) {
			for (url in chapter.pageUrls()) {
				val ref = requireNotNull(LocalPageRef.decode(url))
				assertNotNull("page $url must decode", ImageIO.read(ByteArrayInputStream(LocalArchives.readPage(ref))))
			}
		}
	}

	@Test
	fun `the same archive can be imported as one chapter instead`() = runBlocking {
		val file = File(temp, "Series.cbz")
		Fixtures.zip(file, listOf("Ch 1/001.png", "Ch 2/001.png"))

		val preview = ready(importer.inspect(listOf(file)).single())
		val single = preview.options.first { it.key == "single" }
		importer.commit(single.candidates)

		val row = rows().single()
		assertEquals(LocalxFormat.CBZ.dbName, row.format)
		assertEquals(1, row.chaptersCount)
		assertEquals(2, readChapters(row).single().pageCount)
	}

	@Test
	fun `a chapter inside an archive cannot be read by the shell's reader`() = runBlocking {
		val file = File(temp, "Inside.cbz")
		Fixtures.zip(file, listOf("Ch 1/001.png", "Ch 2/001.png"))
		importer.commit(ready(importer.inspect(listOf(file)).single()).options.first().candidates)

		// The shell resolves a local chapter's pages by listing its whole container, so a
		// chapter that is a subset of one has to be read here instead. This flag is what
		// the screen branches on; if it ever reads true the reader shows the wrong pages.
		assertEquals(listOf(false, false), readChapters(rows().single()).map { it.isShellReadable })
	}

	@Test
	fun `a folder of chapter folders imports as one title with readable pages`() = runBlocking {
		val root = File(temp, "Long Series")
		Fixtures.folder(
			root,
			listOf(
				"Chapter 1/001.png",
				"Chapter 1/002.png",
				"Chapter 2/001.png",
				"Chapter 10/001.png",
				"Chapter 10/002.png",
				"Chapter 10/003.png",
			),
		)

		val preview = ready(importer.inspect(listOf(root)).single())
		importer.commit(preview.options.first().candidates)

		val row = rows().single()
		assertEquals("Long Series", row.title)
		assertEquals(LocalxFormat.DIRECTORY_CHAPTERS.dbName, row.format)
		assertEquals(3, row.chaptersCount)

		val chapters = readChapters(row)
		assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), chapters.map { it.title })
		assertEquals(listOf(2, 1, 3), chapters.map { it.pageCount })
		// The container is the sub-folder, which is the only addressing LocalArchives
		// accepts for a directory: it refuses an entry below the folder it was handed.
		assertEquals(File(root, "Chapter 1").absolutePath, chapters.first().container)
		assertEquals(listOf("001.png", "002.png"), chapters.first().entries)
		for (url in chapters.last().pageUrls()) {
			val ref = requireNotNull(LocalPageRef.decode(url))
			assertNotNull(ImageIO.read(ByteArrayInputStream(LocalArchives.readPage(ref))))
		}
		// These chapters are whole containers, so the shell's reader can open them.
		assertTrue(chapters.all { it.isShellReadable })
		// The cover names a file in a sub-folder; the reference has to point at the
		// sub-folder or nothing decodes it.
		val cover = requireNotNull(LocalPageRef.decode(requireNotNull(coverUrlOf(row))))
		assertEquals(File(root, "Chapter 1").absolutePath, cover.container)
		assertEquals("001.png", cover.entry)
	}

	@Test
	fun `a folder of archives offers one title or many, and the user picks`() = runBlocking {
		val root = File(temp, "Boxset")
		Fixtures.zip(File(root, "Vol 1.cbz"), listOf("001.png", "002.png"))
		Fixtures.zip(File(root, "Vol 2.cbz"), listOf("001.png"))
		Fixtures.zip(File(root, "Vol 10.cbz"), listOf("001.png", "002.png", "003.png"))

		val preview = ready(importer.inspect(listOf(root)).single())
		assertEquals(listOf("one-title", "separate"), preview.options.map { it.key })

		importer.commit(preview.options.first { it.key == "one-title" }.candidates)
		val one = rows().single()
		assertEquals("Boxset", one.title)
		assertEquals(LocalxFormat.ARCHIVE_SET.dbName, one.format)
		assertEquals(3, one.chaptersCount)
		val chapters = readChapters(one)
		assertEquals(listOf("Vol 1", "Vol 2", "Vol 10"), chapters.map { it.title })
		assertEquals(listOf(2, 1, 3), chapters.map { it.pageCount })
		assertTrue(chapters.all { it.isShellReadable })

		// The other option is the older screen's behaviour, still available.
		importer.commit(preview.options.first { it.key == "separate" }.candidates)
		val titles = rows().map { it.title }
		assertTrue("both readings coexist: $titles", titles.containsAll(listOf("Boxset", "Vol 1", "Vol 2", "Vol 10")))
		assertEquals(4, rows().size)
	}

	@Test
	fun `ComicInfo improves the title and the chapter name`() = runBlocking {
		val file = File(temp, "ugly_scan_name_v2.cbz")
		Fixtures.zip(
			file,
			listOf("001.png", "002.png"),
			extras = mapOf(
				ComicInfoReader.ENTRY_NAME to Fixtures.comicInfo(
					series = "Real Series",
					number = "7",
					title = "The Good Part",
					writer = "Someone",
					genre = "Action, Drama",
				),
			),
		)

		val preview = ready(importer.inspect(listOf(file)).single())
		assertTrue(
			"the user must see what the metadata contributed: ${preview.notes}",
			preview.notes.any { it.contains("Real Series") && it.contains("writer Someone") },
		)
		importer.commit(preview.options.single().candidates)

		val row = rows().single()
		assertEquals("Real Series", row.title)
		assertEquals("7 - The Good Part", readChapters(row).single().title)
	}

	@Test
	fun `a malformed ComicInfo falls back to filenames and still imports`() = runBlocking {
		val file = File(temp, "Fallback Name.cbz")
		Fixtures.zip(
			file,
			listOf("001.png"),
			extras = mapOf(ComicInfoReader.ENTRY_NAME to "<ComicInfo><Series>never closed"),
		)

		val preview = ready(importer.inspect(listOf(file)).single())
		val report = importer.commit(preview.options.single().candidates)
		assertEquals(emptyList<Any>(), report.failures)

		val row = rows().single()
		assertEquals("Fallback Name", row.title)
		assertEquals("Fallback Name", readChapters(row).single().title)
		assertEquals(1, row.chaptersCount)
	}

	@Test
	fun `the ComicInfo number is not used to name twelve chapters at once`() = runBlocking {
		val file = File(temp, "Collected.cbz")
		Fixtures.zip(
			file,
			listOf("Ch 1/001.png", "Ch 2/001.png"),
			extras = mapOf(ComicInfoReader.ENTRY_NAME to Fixtures.comicInfo(series = "S", number = "1", title = "One")),
		)

		val preview = ready(importer.inspect(listOf(file)).single())
		importer.commit(preview.options.first().candidates)

		val row = rows().single()
		assertEquals("S", row.title)
		// One ComicInfo describes one issue; with two chapters it names neither.
		assertEquals(listOf("Ch 1", "Ch 2"), readChapters(row).map { it.title })
	}

	@Test
	fun `a zip that is not a comic is rejected and leaves no index row`() = runBlocking {
		val file = File(temp, "Docs.zip")
		Fixtures.zip(file, emptyList(), extras = mapOf("readme.txt" to "nothing to read here"))

		val preview = importer.inspect(listOf(file)).single()
		val rejected = preview as? ImportPreview.Rejected ?: error("expected a rejection, got $preview")
		assertTrue("the message must name the file: ${rejected.reason}", rejected.reason.contains("Docs.zip"))
		assertTrue("the message must say why: ${rejected.reason}", rejected.reason.contains("images"))

		assertEquals(emptyList<LocalMangaEntity>(), rows())
		assertNull(db.localLibraryDao().findByPath(file.absolutePath))
	}

	@Test
	fun `a corrupt archive is rejected and leaves no index row`() = runBlocking {
		val file = File(temp, "Truncated.cbz")
		file.writeBytes("this is definitely not a zip".toByteArray())

		val rejected = importer.inspect(listOf(file)).single() as ImportPreview.Rejected
		assertTrue(rejected.reason.contains("Truncated.cbz"))
		assertTrue(rejected.reason.contains("zip"))
		assertEquals(emptyList<LocalMangaEntity>(), rows())
	}

	@Test
	fun `an ambiguous archive is reported as ambiguous and offered as one chapter`() = runBlocking {
		val file = File(temp, "Unclear.cbz")
		Fixtures.zip(file, listOf("001.png", "002.png", "extras/a.png", "bonus/b.png"))

		val preview = ready(importer.inspect(listOf(file)).single())
		assertEquals("Unclear, importing as one chapter", preview.layout)
		assertEquals(listOf("single"), preview.options.map { it.key })
		assertTrue(
			"the reason must be shown, not swallowed: ${preview.notes}",
			preview.notes.any { it.contains("Chapter folders were not used") && it.contains("top level") },
		)

		importer.commit(preview.options.single().candidates)
		val row = rows().single()
		assertEquals(1, row.chaptersCount)
		assertEquals(4, readChapters(row).single().pageCount)
	}

	@Test
	fun `an EPUB imports as one text chapter per spine document`() = runBlocking {
		val file = File(temp, "novel.epub")
		Fixtures.epub(file, "A Novel", listOf("One" to "first", "Two" to "second", "Three" to "third"))

		val preview = ready(importer.inspect(listOf(file)).single())
		importer.commit(preview.options.single().candidates)

		val row = rows().single()
		assertEquals("A Novel", row.title)
		assertEquals(LocalxFormat.EPUB.dbName, row.format)
		assertEquals(3, row.chaptersCount)
		assertNull("there is nothing LocalArchives could decode inside an epub", row.coverEntry)

		val chapters = readChapters(row)
		assertEquals(listOf("One", "Two", "Three"), chapters.map { it.title })
		assertTrue(chapters.all { it.isText && !it.isShellReadable })
		assertTrue(EpubReader.chapterText(file, chapters[1].key).contains("second"))
	}

	@Test
	fun `re-importing keeps the row, the id and the chapter ids`() = runBlocking {
		val root = File(temp, "Growing")
		Fixtures.folder(root, listOf("Ch 1/001.png"))
		importer.commit(ready(importer.inspect(listOf(root)).single()).options.first().candidates)
		val before = rows().single()
		val beforeChapterIds = readChapters(before).map { localxChapterId(before, it.key) }

		// A chapter appears on disk. Re-importing must follow it, not duplicate the title.
		Fixtures.folder(root, listOf("Ch 2/001.png", "Ch 2/002.png"))
		importer.commit(ready(importer.inspect(listOf(root)).single()).options.first().candidates)

		val after = rows().single()
		assertEquals(before.id, after.id)
		assertEquals(before.addedAt, after.addedAt)
		assertEquals(2, after.chaptersCount)
		val afterChapterIds = readChapters(after).map { localxChapterId(after, it.key) }
		assertEquals(
			"the first chapter's id must survive, or its reading position is orphaned",
			beforeChapterIds,
			afterChapterIds.take(1),
		)
		assertEquals(2, afterChapterIds.toSet().size)
	}

	@Test
	fun `a flat cbz imported here is the same row the older screen would write`() = runBlocking {
		val file = File(temp, "Plain.cbz")
		Fixtures.zip(file, listOf("p1.png", "p10.png", "p2.png"))

		importer.commit(ready(importer.inspect(listOf(file)).single()).options.single().candidates)

		val row = rows().single()
		// Same format name, same cover entry, same chapter count: the two screens read
		// each other's rows and must not disagree about the same file.
		assertEquals("CBZ", row.format)
		assertEquals("p1.png", row.coverEntry)
		assertEquals(1, row.chaptersCount)
		assertEquals(file.length(), row.sizeBytes)
		assertEquals(FIXED_NOW, row.addedAt)
		assertEquals(
			listOf("p1.png", "p2.png", "p10.png"),
			readChapters(row).single().entries,
		)
	}

	@Test
	fun `a broken file among many does not stop the rest`() = runBlocking {
		val good = File(temp, "Good.cbz")
		Fixtures.zip(good, listOf("001.png"))
		val bad = File(temp, "Bad.cbz")
		bad.writeBytes("not a zip".toByteArray())

		val previews = importer.inspect(listOf(good, bad))
		assertEquals(1, previews.filterIsInstance<ImportPreview.Ready>().size)
		assertEquals(1, previews.filterIsInstance<ImportPreview.Rejected>().size)

		val candidates = previews.filterIsInstance<ImportPreview.Ready>().flatMap { it.options.first().candidates }
		val report = importer.commit(candidates)
		assertEquals(1, report.imported.size)
		assertTrue(report.summary().contains("Imported 1 comic"))
	}

	private fun ready(preview: ImportPreview): ImportPreview.Ready =
		preview as? ImportPreview.Ready ?: error("expected a usable preview, got $preview")

	private suspend fun rows(): List<LocalMangaEntity> = db.localLibraryDao().observeAll().first()

	private companion object {

		const val FIXED_NOW = 1_700_000_000_000L
	}
}
