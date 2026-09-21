package org.koitharu.kotatsu.desktop.feature.curate

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import java.io.File
import java.nio.file.Files

/**
 * Incognito, both switches.
 *
 * Each is tested on its own, because the failure this guards against is one of them
 * quietly depending on the other: a per-title flag that only works while the global
 * switch is off records history for exactly the title the user singled out.
 */
class IncognitoTest {

	private lateinit var db: LibraryDatabase
	private lateinit var dbFile: File
	private lateinit var configDir: File
	private lateinit var repository: CurateRepository
	private lateinit var config: CurateConfigStore
	private lateinit var incognito: IncognitoController

	@Before
	fun setUp() = runBlocking {
		val opened = openTempDatabase("curate-incognito")
		db = opened.first
		dbFile = opened.second
		configDir = Files.createTempDirectory("curate-config").toFile()
		repository = CurateRepository(db) { 3_000L }
		config = CurateConfigStore(File(configDir, CurateConfigStore.FILE_NAME).toOkioPath())
		incognito = IncognitoController(repository, config)
		val category = db.putCategory("Reading")
		for (id in 1L..3L) {
			db.putManga(id, "Title $id")
			db.putFavourite(id, category)
		}
	}

	@After
	fun tearDown() {
		db.close()
		dbFile.delete()
		configDir.deleteRecursively()
	}

	@Test
	fun `by default everything is recorded`() = runBlocking {
		assertTrue(incognito.shouldRecordHistory(1L))
		assertFalse(config.current.incognito)
		assertNull(db.mangaPrefsDao().find(1L))
	}

	@Test
	fun `the global flag alone suppresses history for every title`() = runBlocking {
		incognito.setGlobal(true)
		assertFalse(incognito.shouldRecordHistory(1L))
		assertFalse(incognito.shouldRecordHistory(2L))
		// Nothing was written per title: turning the switch back off must restore every
		// title at once, not leave three rows behind that outlive the session.
		assertNull(db.mangaPrefsDao().find(1L))
		incognito.setGlobal(false)
		assertTrue(incognito.shouldRecordHistory(1L))
	}

	@Test
	fun `the per-title flag alone suppresses history for that title only`() = runBlocking {
		assertFalse(config.current.incognito)
		assertTrue(incognito.setForManga(2L, true))
		assertFalse(incognito.shouldRecordHistory(2L))
		assertTrue(incognito.shouldRecordHistory(1L))
		assertTrue(incognito.shouldRecordHistory(3L))
		assertTrue(db.mangaPrefsDao().find(2L)!!.incognito)
	}

	@Test
	fun `turning the global flag off does not un-hide a title marked on its own`() = runBlocking {
		incognito.setForManga(2L, true)
		incognito.setGlobal(true)
		incognito.setGlobal(false)
		// The two switches are independent, so clearing one must not clear the other.
		assertFalse(incognito.shouldRecordHistory(2L))
		assertTrue(incognito.shouldRecordHistory(1L))
	}

	@Test
	fun `the per-title flag survives a fresh config file`() = runBlocking {
		incognito.setForManga(3L, true)
		// A new store over an empty directory: the per-title flag lives in the database
		// precisely so it is not a casualty of a deleted config.
		val freshConfig = CurateConfigStore(File(configDir, "other.json").toOkioPath())
		val fresh = IncognitoController(repository, freshConfig)
		assertFalse(fresh.shouldRecordHistory(3L))
	}

	@Test
	fun `setting the flag keeps the other per-title overrides`() = runBlocking {
		db.mangaPrefsDao().upsert(
			org.koitharu.kotatsu.shared.db.MangaPrefsEntity(
				mangaId = 1L,
				readingMode = "WEBTOON",
				titleOverride = "My name for it",
				coverOverride = null,
				branch = "Official",
				incognito = false,
			),
		)
		incognito.setForManga(1L, true)
		val prefs = db.mangaPrefsDao().find(1L)!!
		assertTrue(prefs.incognito)
		// Another area owns these columns. Overwriting the row instead of updating it
		// would silently reset a reading mode the user had chosen per title.
		assertEquals("WEBTOON", prefs.readingMode)
		assertEquals("My name for it", prefs.titleOverride)
		assertEquals("Official", prefs.branch)
	}

	@Test
	fun `a title with no stored row cannot take the flag, and says so`() = runBlocking {
		// `manga_prefs` has a foreign key onto `manga`. Reporting false beats throwing:
		// the reader treats a missing flag as "record", and a title never stored has
		// never been read either.
		assertFalse(incognito.setForManga(999L, true))
		assertTrue(incognito.shouldRecordHistory(999L))
	}

	@Test
	fun `a batch sets the flag on a whole selection`() = runBlocking {
		val result = repository.setIncognito(listOf(1L, 2L, 999L), true)
		assertEquals(2, result.affected)
		assertEquals(1, result.skipped)
		assertEquals("no longer saved", result.skippedReason)
		assertFalse(incognito.shouldRecordHistory(1L))
		assertFalse(incognito.shouldRecordHistory(2L))
		assertTrue(incognito.shouldRecordHistory(3L))
	}

	@Test
	fun `the flag shows up on the item the screen draws`() = runBlocking {
		repository.setIncognito(listOf(2L), true)
		val items = repository.observeItems().first().associateBy { it.id }
		assertTrue(items.getValue(2L).incognito)
		assertFalse(items.getValue(1L).incognito)
	}

	@Test
	fun `the global flag is remembered across restarts`() = runBlocking {
		incognito.setGlobal(true)
		val reopened = CurateConfigStore(File(configDir, CurateConfigStore.FILE_NAME).toOkioPath())
		assertTrue(reopened.current.incognito)
		assertFalse(IncognitoController(repository, reopened).shouldRecordHistory(1L))
	}
}
