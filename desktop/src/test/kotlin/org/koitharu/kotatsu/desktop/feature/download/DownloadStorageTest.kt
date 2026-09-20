package org.koitharu.kotatsu.desktop.feature.download

import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class DownloadStorageTest {

	private lateinit var root: java.io.File
	private lateinit var storage: DownloadStorage

	@Before
	fun setUp() {
		root = Files.createTempDirectory("downloads-storage-test").toFile()
		storage = DownloadStorage(root.toPath().toOkioPath())
	}

	@After
	fun tearDown() {
		root.deleteRecursively()
	}

	@Test
	fun `the layout is source then manga then chapter`() {
		val dir = storage.chapterDir("MANGADEX", 12L, 34L)
		assertTrue(
			dir.toString(),
			dir.toString().endsWith("/downloads/MANGADEX/12/34"),
		)
	}

	@Test
	fun `a written page leaves no temporary file behind`() {
		val dir = storage.chapterDir("MANGADEX", 1L, 1L)
		storage.prepare(dir)

		storage.writePage(dir, index = 0, data = PageBytes("bytes".encodeToByteArray(), "png"))

		val names = FileSystem.SYSTEM.list(dir).map { it.name }
		assertEquals(listOf("0001.png"), names)
	}

	@Test
	fun `a leftover part file is never offered as a page`() {
		val dir = storage.chapterDir("MANGADEX", 1L, 1L)
		storage.prepare(dir)
		storage.writePage(dir, index = 0, data = PageBytes("one".encodeToByteArray(), "jpg"))
		// Exactly what a process killed mid-write leaves: the temp name of page two,
		// holding a truncated body. Positive control for the naming rule, because the
		// rule is only worth anything if a file like this exists and is still skipped.
		FileSystem.SYSTEM.write(dir / "0002.jpg.part") { writeUtf8("half a p") }

		val pages = storage.pages(dir)

		assertEquals(listOf("0001.jpg"), pages.map { it.name })
		assertTrue(FileSystem.SYSTEM.exists(dir / "0002.jpg.part"))
	}

	@Test
	fun `pages come back in numeric order, not string order`() {
		val dir = storage.chapterDir("MANGADEX", 1L, 1L)
		storage.prepare(dir)
		for (index in 0 until 12) {
			storage.writePage(dir, index, PageBytes("p$index".encodeToByteArray(), "jpg"))
		}

		val names = storage.pages(dir).map { it.name }

		assertEquals("0001.jpg", names.first())
		assertEquals("0002.jpg", names[1])
		assertEquals("0012.jpg", names.last())
	}

	@Test
	fun `prepare clears whatever an earlier attempt left`() {
		val dir = storage.chapterDir("MANGADEX", 1L, 1L)
		storage.prepare(dir)
		storage.writePage(dir, index = 0, data = PageBytes("stale".encodeToByteArray(), "jpg"))

		storage.prepare(dir)

		assertTrue(storage.pages(dir).isEmpty())
		assertTrue(storage.exists(dir))
	}

	@Test
	fun `a source name cannot escape the downloads tree`() {
		val dir = storage.chapterDir("../../etc", 1L, 1L)

		assertTrue(dir.toString(), dir.toString().startsWith(storage.downloadsRoot.toString()))
		assertFalse(dir.toString().contains(".."))
	}

	@Test
	fun `the extension follows the content type, then the url`() {
		assertEquals("webp", imageExtension("https://a.test/1.jpg", "image/webp"))
		assertEquals("png", imageExtension("https://a.test/1", "image/png; charset=binary"))
		assertEquals("jpg", imageExtension("https://a.test/1.jpg", null))
		assertEquals("jpg", imageExtension("https://a.test/1.jpg?token=abc", "application/octet-stream"))
		// No usable hint anywhere: a decoder that sniffs bytes will cope, a wrong
		// five-letter "extension" scraped off a path would not help anyone.
		assertEquals("jpg", imageExtension("https://a.test/image", null))
	}
}
