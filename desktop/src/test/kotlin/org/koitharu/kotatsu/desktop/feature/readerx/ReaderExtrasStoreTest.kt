package org.koitharu.kotatsu.desktop.feature.readerx

import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The defaults store, against a real file.
 *
 * Worth a test of its own because two of its fields are maps with non-string keys, and a
 * map keyed by an enum or a Long is exactly the shape that compiles and then fails at
 * runtime on the first write.
 */
class ReaderExtrasStoreTest {

	private lateinit var dir: java.io.File

	private fun store() = ReaderExtrasStore(
		dir.toPath().resolve(ReaderExtrasStore.FILE_NAME).toOkioPath(),
	)

	@Before
	fun setUp() {
		dir = Files.createTempDirectory("readerx").toFile()
	}

	@After
	fun tearDown() {
		dir.deleteRecursively()
	}

	@Test
	fun `an absent file reads as defaults`() {
		val prefs = store().data.value
		assertEquals(ReaderColorParams.DEFAULT, prefs.colorFilter)
		assertEquals(TapZoneGrid.DEFAULT, prefs.tapZones)
		assertFalse(prefs.doublePage)
		assertTrue(prefs.coverFirst)
	}

	@Test
	fun `everything round-trips through the file`() = runBlocking {
		val filter = ReaderColorParams(brightness = 0.3f, contrast = -0.2f, grayscale = 0.5f, sepia = 0.1f, invert = true)
		val grid = TapZoneGrid.DEFAULT
			.with(TapZone.CENTER, TapAction.NONE)
			.with(TapZone.TOP_LEFT, TapAction.NEXT_PAGE)
		val first = store()
		first.setColorFilter(filter)
		first.setTapZones(grid)
		first.setDoublePage(true)
		first.setCoverFirst(false)
		first.setTitleFilter(4242L, ReaderColorParams(sepia = 0.8f))

		// A second instance reads the file rather than the first one's memory.
		val reloaded = store().data.value
		assertEquals(filter, reloaded.colorFilter)
		assertEquals(grid, reloaded.tapZones)
		assertTrue(reloaded.doublePage)
		assertFalse(reloaded.coverFirst)
		assertEquals(ReaderColorParams(sepia = 0.8f), reloaded.filterFor(4242L))
		assertTrue(reloaded.hasOwnFilter(4242L))
	}

	@Test
	fun `a title without its own filter follows the default`() = runBlocking {
		val store = store()
		store.setColorFilter(ReaderColorParams(invert = true))
		assertFalse(store.data.value.hasOwnFilter(1L))
		assertEquals(ReaderColorParams(invert = true), store.data.value.filterFor(1L))
	}

	@Test
	fun `clearing a title filter puts it back on the default`() = runBlocking {
		val store = store()
		store.setColorFilter(ReaderColorParams(grayscale = 1f))
		store.setTitleFilter(1L, ReaderColorParams(invert = true))
		assertTrue(store.data.value.hasOwnFilter(1L))
		store.clearTitleFilter(1L)
		assertFalse(store.data.value.hasOwnFilter(1L))
		assertEquals(ReaderColorParams(grayscale = 1f), store.data.value.filterFor(1L))
	}

	@Test
	fun `out of range values are clamped before they are written`() = runBlocking {
		val store = store()
		store.setColorFilter(ReaderColorParams(brightness = 5f, sepia = -3f))
		assertEquals(1f, store().data.value.colorFilter.brightness, 1e-4f)
		assertEquals(0f, store().data.value.colorFilter.sepia, 1e-4f)
	}

	@Test
	fun `a corrupt file falls back to defaults rather than refusing to start`() {
		val file = dir.toPath().resolve(ReaderExtrasStore.FILE_NAME).toFile()
		file.writeText("{ this is not json")
		assertEquals(ReaderExtrasPrefs(), store().data.value)
	}

	@Test
	fun `a file from a newer build with unknown keys still loads`() {
		val file = dir.toPath().resolve(ReaderExtrasStore.FILE_NAME).toFile()
		file.writeText("""{"double_page":true,"something_new":{"a":1}}""")
		val prefs = store().data.value
		assertTrue(prefs.doublePage)
		// Missing keys fall back to their declared defaults rather than failing.
		assertEquals(TapZoneGrid.DEFAULT, prefs.tapZones)
	}

	@Test
	fun `no temp file is left behind after a write`() = runBlocking {
		store().setDoublePage(true)
		val leftovers = dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }
		assertEquals(emptyList<String>(), leftovers)
	}
}
