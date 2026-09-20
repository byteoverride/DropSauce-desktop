package org.koitharu.kotatsu.desktop.feature.local

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
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real files, a real Room database on disk, no mocks.
 *
 * The point of this suite is that the import path is exercised against bytes a zip
 * reader actually has to parse. A fake archive abstraction would pass while
 * `ZipFile` failed on the same input, which is the failure mode worth catching.
 */
class LocalLibraryTest {

	private lateinit var temp: File
	private lateinit var db: LibraryDatabase
	private lateinit var library: LocalLibrary

	@Before
	fun setUp() {
		temp = Files.createTempDirectory("local-library-test").toFile()
		db = openLibraryDatabase(File(temp, "library.db").toOkioPath())
		library = LocalLibrary(db.localLibraryDao()) { FIXED_NOW }
	}

	@After
	fun tearDown() {
		db.close()
		temp.deleteRecursively()
	}

	@Test
	fun `a cbz imports, lists and yields its pages in order`() = runBlocking {
		val file = File(temp, "Some Comic.cbz")
		writeCbz(file, listOf("001.png", "002.png", "003.png"))

		val report = library.importAll(listOf(file))
		assertEquals(emptyList<ImportFailure>(), report.failures)
		assertEquals(1, report.imported.size)

		val rows = library.observeAll().first()
		assertEquals(1, rows.size)
		val row = rows.single()
		assertEquals("Some Comic", row.title)
		assertEquals(file.absolutePath, row.path)
		assertEquals(LocalFormat.CBZ.name, row.format)
		assertEquals("001.png", row.coverEntry)
		assertEquals(file.length(), row.sizeBytes)
		assertEquals(FIXED_NOW, row.addedAt)

		val chapters = chaptersOf(row)
		assertEquals(1, chapters.size)
		val pages = pagesOf(chapters.single())
		assertEquals(
			listOf("001.png", "002.png", "003.png"),
			pages.map { LocalPageRef.decode(it.url)?.entry },
		)
		// Page ids must be distinct, or the reader's remember(page.id) keys collide.
		assertEquals(pages.size, pages.map { it.id }.toSet().size)
	}

	@Test
	fun `pages come back as the bytes that were written`() = runBlocking {
		val file = File(temp, "Bytes.cbz")
		writeCbz(file, listOf("a.png"), width = 7, height = 11)

		library.importAll(listOf(file))
		val page = pagesOf(chaptersOf(library.observeAll().first().single()).single()).single()
		val ref = requireNotNull(LocalPageRef.decode(page.url))
		val decoded = ImageIO.read(ByteArrayInputStream(LocalArchives.readPage(ref)))

		assertNotNull("the page bytes must be a decodable image", decoded)
		assertEquals(7, decoded.width)
		assertEquals(11, decoded.height)
	}

	@Test
	fun `page10 sorts after page2, not after page1`() = runBlocking {
		val file = File(temp, "Unpadded.cbz")
		// Deliberately written to the zip in an order that is neither the right one nor
		// the lexicographic one, so passing cannot be an accident of insertion order.
		writeCbz(file, listOf("page10.png", "page1.png", "page21.png", "page2.png", "page3.png"))

		library.importAll(listOf(file))
		val row = library.observeAll().first().single()
		val pages = pagesOf(chaptersOf(row).single()).mapNotNull { LocalPageRef.decode(it.url)?.entry }

		assertEquals(
			listOf("page1.png", "page2.png", "page3.png", "page10.png", "page21.png"),
			pages,
		)
		assertEquals("page1.png", row.coverEntry)
	}

	@Test
	fun `re-importing the same path updates the row instead of duplicating it`() = runBlocking {
		val file = File(temp, "Growing.cbz")
		writeCbz(file, listOf("01.png"))
		library.importAll(listOf(file))
		val before = library.observeAll().first().single()
		val sizeBefore = before.sizeBytes

		// Same path, different contents: the row must follow the file.
		writeCbz(file, listOf("01.png", "02.png", "03.png"))
		val report = library.importAll(listOf(file))
		assertEquals(emptyList<ImportFailure>(), report.failures)

		val rows = library.observeAll().first()
		assertEquals("re-import must not create a second row", 1, rows.size)
		val after = rows.single()
		assertEquals(before.id, after.id)
		assertEquals(before.addedAt, after.addedAt)
		assertTrue("size must be refreshed", after.sizeBytes > sizeBefore)
		assertEquals(3, pagesOf(chaptersOf(after).single()).size)
		// The derived ids are a function of the path, so they survive the re-import.
		assertEquals(mangaOf(before).id, mangaOf(after).id)
	}

	@Test
	fun `a folder of images imports as one comic`() = runBlocking {
		val folder = File(temp, "Loose Pages").also { it.mkdirs() }
		for (name in listOf("p1.png", "p10.png", "p2.png")) {
			File(folder, name).writeBytes(png(5, 5))
		}
		// A non-image sitting alongside the pages must not become a page.
		File(folder, "notes.txt").writeText("ignore me")

		val report = library.importAll(listOf(folder))
		assertEquals(emptyList<ImportFailure>(), report.failures)

		val row = library.observeAll().first().single()
		assertEquals("Loose Pages", row.title)
		assertEquals(LocalFormat.DIRECTORY.name, row.format)
		assertEquals("p1.png", row.coverEntry)

		val pages = pagesOf(chaptersOf(row).single()).mapNotNull { LocalPageRef.decode(it.url)?.entry }
		assertEquals(listOf("p1.png", "p2.png", "p10.png"), pages)
	}

	@Test
	fun `a folder of archives imports each one separately`() = runBlocking {
		val folder = File(temp, "Batch").also { it.mkdirs() }
		writeCbz(File(folder, "Vol 1.cbz"), listOf("a.png"))
		writeCbz(File(folder, "Vol 2.cbz"), listOf("a.png", "b.png"))

		val report = library.importAll(listOf(folder))
		assertEquals(emptyList<ImportFailure>(), report.failures)
		assertEquals(2, report.imported.size)

		val titles = library.observeAll().first().map { it.title }
		assertEquals(listOf("Vol 1", "Vol 2"), titles)
	}

	@Test
	fun `a corrupt archive fails with a message and leaves no row`() = runBlocking {
		val file = File(temp, "Truncated.cbz")
		file.writeBytes("this is definitely not a zip".toByteArray())

		val report = library.importAll(listOf(file))
		assertTrue("a corrupt file must import nothing", report.imported.isEmpty())
		assertEquals(1, report.failures.size)
		val failure = report.failures.single()
		assertEquals(file.absolutePath, failure.path)
		assertTrue(
			"the message must name the file: ${failure.reason}",
			failure.reason.contains("Truncated.cbz"),
		)
		assertTrue(
			"the message must say why: ${failure.reason}",
			failure.reason.contains("zip"),
		)

		assertEquals(emptyList<Any>(), library.observeAll().first())
		assertNull(db.localLibraryDao().findByPath(file.absolutePath))
	}

	@Test
	fun `a zip holding no images fails and leaves no row`() = runBlocking {
		val file = File(temp, "Docs.zip")
		ZipOutputStream(file.outputStream().buffered()).use { zip ->
			zip.putNextEntry(ZipEntry("readme.txt"))
			zip.write("nothing to read here".toByteArray())
			zip.closeEntry()
		}

		val report = library.importAll(listOf(file))
		assertTrue(report.imported.isEmpty())
		assertTrue(report.failures.single().reason.contains("no images"))
		assertEquals(emptyList<Any>(), library.observeAll().first())
	}

	@Test
	fun `a file that is neither archive nor folder fails cleanly`() = runBlocking {
		val file = File(temp, "cover.png")
		file.writeBytes(png(4, 4))

		val report = library.importAll(listOf(file))
		assertTrue(report.imported.isEmpty())
		assertTrue(report.failures.single().reason.contains("not a .cbz"))
		assertEquals(emptyList<Any>(), library.observeAll().first())
	}

	@Test
	fun `a partly broken batch imports the rest and reports the failure`() = runBlocking {
		val folder = File(temp, "Mixed").also { it.mkdirs() }
		writeCbz(File(folder, "Good.cbz"), listOf("a.png"))
		File(folder, "Bad.cbz").writeBytes("not a zip".toByteArray())

		val report = library.importAll(listOf(folder))
		assertEquals(1, report.imported.size)
		assertEquals(1, report.failures.size)
		assertEquals("Good", library.observeAll().first().single().title)
		assertTrue(report.summary().contains("Imported 1 comic"))
	}

	@Test
	fun `removing an entry keeps the file on disk`() = runBlocking {
		val file = File(temp, "Keep Me.cbz")
		writeCbz(file, listOf("a.png"))
		library.importAll(listOf(file))

		library.remove(library.observeAll().first().single())

		assertEquals(emptyList<Any>(), library.observeAll().first())
		assertTrue("removal must never touch the user's file", file.isFile)
	}

	@Test
	fun `a page reference survives a round trip through the url`() {
		val ref = LocalPageRef(
			container = "/home/someone/comics/Weird name (2019) #1!.cbz",
			entry = "sub dir/page 001 [v2].png",
		)
		assertEquals(ref, LocalPageRef.decode(ref.encode()))
		assertNull(LocalPageRef.decode("https://example.invalid/page.png"))
	}

	private fun writeCbz(file: File, names: List<String>, width: Int = 4, height: Int = 6) {
		ZipOutputStream(file.outputStream().buffered()).use { zip ->
			for (name in names) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(png(width, height))
				zip.closeEntry()
			}
		}
	}

	private fun png(width: Int, height: Int): ByteArray {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val graphics = image.createGraphics()
		try {
			graphics.color = Color(0x33, 0x66, 0x99)
			graphics.fillRect(0, 0, width, height)
		} finally {
			graphics.dispose()
		}
		val out = ByteArrayOutputStream()
		check(ImageIO.write(image, "png", out)) { "no PNG writer available" }
		return out.toByteArray()
	}

	private companion object {

		const val FIXED_NOW = 1_700_000_000_000L
	}
}
