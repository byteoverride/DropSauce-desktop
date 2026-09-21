package org.koitharu.kotatsu.desktop.feature.curate

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The view settings, in a JSON file under the app's config directory. */
class CurateConfigStoreTest {

	private lateinit var dir: File

	private fun store(name: String = CurateConfigStore.FILE_NAME) =
		CurateConfigStore(File(dir, name).toOkioPath())

	@Before
	fun setUp() {
		dir = Files.createTempDirectory("curate-config").toFile()
	}

	@After
	fun tearDown() {
		dir.deleteRecursively()
	}

	@Test
	fun `defaults apply when there is no file`() {
		val prefs = store().current
		assertEquals(LibrarySortKey.TITLE, prefs.sort)
		assertEquals(LibraryViewMode.GRID, prefs.view)
		assertFalse(prefs.descending)
		assertFalse(prefs.incognito)
	}

	@Test
	fun `every setting round-trips through the file`() = runBlocking {
		val first = store()
		first.setSort(LibrarySortKey.RECENTLY_READ, descending = true)
		first.setView(LibraryViewMode.LIST)
		first.setIncognito(true)

		val reopened = store().current
		assertEquals(LibrarySortKey.RECENTLY_READ, reopened.sort)
		assertTrue(reopened.descending)
		assertEquals(LibraryViewMode.LIST, reopened.view)
		assertTrue(reopened.incognito)
	}

	@Test
	fun `the flow reports the change`() = runBlocking {
		val subject = store()
		subject.setView(LibraryViewMode.LIST)
		assertEquals(LibraryViewMode.LIST, subject.data.first().view)
	}

	@Test
	fun `a corrupt file falls back to defaults instead of failing`() {
		val file = File(dir, CurateConfigStore.FILE_NAME)
		file.writeText("{ this is not json")
		// Losing a sort order is a shrug. Refusing to open the screen over it is not.
		assertEquals(LibrarySortKey.TITLE, store().current.sort)
	}

	@Test
	fun `a file from a later build keeps the fields this build understands`() {
		File(dir, CurateConfigStore.FILE_NAME).writeText(
			"""{"version":99,"sort":"PROGRESS","descending":true,"view":"LIST",""" +
				""""incognito":false,"somethingNew":{"a":1}}""",
		)
		val prefs = store().current
		assertEquals(LibrarySortKey.PROGRESS, prefs.sort)
		assertEquals(LibraryViewMode.LIST, prefs.view)
		assertTrue(prefs.descending)
	}

	@Test
	fun `writing the same value again does not rewrite the file`() = runBlocking {
		val subject = store()
		subject.setView(LibraryViewMode.LIST)
		val file = File(dir, CurateConfigStore.FILE_NAME)
		val stamp = file.lastModified()
		val contents = file.readText()
		subject.setView(LibraryViewMode.LIST)
		assertEquals(stamp, file.lastModified())
		assertEquals(contents, file.readText())
	}

	@Test
	fun `shared returns one store per path, and separate ones for separate paths`() = runBlocking {
		val file = File(dir, CurateConfigStore.FILE_NAME).toOkioPath()
		val first = CurateConfigStore.shared(file)
		assertSame(first, CurateConfigStore.shared(file))
		// The bug this prevents: the reader holding a second instance, still reporting
		// incognito off after the organise screen turned it on.
		first.setIncognito(true)
		assertTrue(CurateConfigStore.shared(file).current.incognito)
		assertNotSame(first, CurateConfigStore.shared(File(dir, "other.json").toOkioPath()))
	}

	@Test
	fun `no temp file is left behind`() = runBlocking {
		store().setSort(LibrarySortKey.CHAPTER_COUNT, descending = false)
		assertEquals(listOf(CurateConfigStore.FILE_NAME), dir.list()!!.toList())
	}
}
