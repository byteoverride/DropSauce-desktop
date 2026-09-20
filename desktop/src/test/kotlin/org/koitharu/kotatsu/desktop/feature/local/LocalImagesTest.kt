package org.koitharu.kotatsu.desktop.feature.local

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The decode path the grid and the reader actually run.
 *
 * Goes all the way to a Skia-backed `ImageBitmap`, because the interesting failure is
 * not "the bytes came out of the zip" but "Skia refused them", and that only shows up
 * when Skia is the one doing the decoding.
 */
class LocalImagesTest {

	private lateinit var temp: File
	private lateinit var images: LocalImages

	@Before
	fun setUp() {
		temp = Files.createTempDirectory("local-images-test").toFile()
		images = LocalImages(coverWidth = 40)
	}

	@After
	fun tearDown() {
		temp.deleteRecursively()
	}

	@Test
	fun `a cover is decoded and downscaled`() = runBlocking {
		val file = File(temp, "Wide.cbz")
		writeCbz(file, "cover.png" to png(200, 300))

		val cover = images.cover(file.absolutePath, "cover.png")

		assertNotNull("the cover must decode", cover)
		assertEquals("the cover must be shrunk to the grid's width", 40, cover!!.width)
		assertEquals(60, cover.height)
	}

	@Test
	fun `a page keeps its full resolution`() = runBlocking {
		val file = File(temp, "Pages.cbz")
		writeCbz(file, "001.png" to png(180, 260))
		val url = LocalPageRef(file.absolutePath, "001.png").encode()

		val page = images.page(url)

		assertNotNull("the page must decode", page)
		assertEquals(180, page!!.width)
		assertEquals(260, page.height)
	}

	@Test
	fun `a cover smaller than the target is not enlarged`() = runBlocking {
		val file = File(temp, "Tiny.cbz")
		writeCbz(file, "a.png" to png(12, 18))

		val cover = images.cover(file.absolutePath, "a.png")

		assertEquals(12, cover?.width)
		assertEquals(18, cover?.height)
	}

	@Test
	fun `an entry that is not an image yields null rather than throwing`() = runBlocking {
		val file = File(temp, "Liar.cbz")
		// A text file with an image extension: a real thing inside real scan archives,
		// and the case that made an earlier version throw out of a composition.
		writeCbz(file, "001.png" to "this is not a png".toByteArray())

		assertNull(images.cover(file.absolutePath, "001.png"))
		assertNull(images.page(LocalPageRef(file.absolutePath, "001.png").encode()))
	}

	@Test
	fun `a missing container yields null rather than throwing`() = runBlocking {
		val missing = File(temp, "Gone.cbz").absolutePath

		assertNull(images.cover(missing, "001.png"))
		assertNull(images.page(LocalPageRef(missing, "001.png").encode()))
		assertNull("a url that is not a local ref is not ours", images.page("https://example.invalid/a.png"))
	}

	@Test
	fun `forget drops a container so a re-import is re-read`() = runBlocking {
		val file = File(temp, "Changing.cbz")
		writeCbz(file, "cover.png" to png(200, 300))
		assertEquals(40, images.cover(file.absolutePath, "cover.png")?.width)

		// Same path, different bytes. Without forget the cache would keep serving the
		// old cover for the rest of the session.
		writeCbz(file, "cover.png" to png(100, 100))
		assertEquals("the stale cover must still be cached", 40, images.cover(file.absolutePath, "cover.png")?.width)

		images.forget(file.absolutePath)
		val refreshed = images.cover(file.absolutePath, "cover.png")
		assertNotNull(refreshed)
		assertTrue("a square cover shrunk to 40 wide is 40 tall", refreshed!!.height == 40)
	}

	private fun writeCbz(file: File, vararg entries: Pair<String, ByteArray>) {
		ZipOutputStream(file.outputStream().buffered()).use { zip ->
			for ((name, bytes) in entries) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(bytes)
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
}
