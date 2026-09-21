package org.koitharu.kotatsu.desktop.feature.readerx

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import org.koitharu.kotatsu.shared.settings.ReadingMode
import java.nio.file.Files

/**
 * Per-title preferences against a real database file.
 *
 * A real file rather than an in-memory database, for the same reason the bookmarks tests
 * use one: the foreign key onto `manga` and the nullability of every override column are
 * schema behaviour, and the point of these tests is that the schema enforces what the
 * repository assumes.
 */
class TitlePrefsRepositoryTest {

	private lateinit var db: LibraryDatabase
	private lateinit var file: java.io.File
	private lateinit var repository: TitlePrefsRepository

	private fun manga(id: Long, title: String) = Manga(
		id = id,
		title = title,
		altTitles = emptySet(),
		url = "/m/$id",
		publicUrl = "https://example.test/m/$id",
		rating = 0.8f,
		contentRating = null,
		coverUrl = "https://example.test/c/$id.jpg",
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		source = MangaParserSource.MANGADEX,
	)

	@Before
	fun setUp() {
		file = Files.createTempFile("titleprefs", ".db").toFile()
		file.delete()
		db = openLibraryDatabase(file.toPath().toOkioPath(), now = { 1_000L })
		repository = TitlePrefsRepository(db)
	}

	@After
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `a fully populated row round-trips`() = runBlocking {
		val title = manga(1L, "First")
		val prefs = TitlePrefs(
			mangaId = 1L,
			readingMode = ReadingMode.PagedRtl,
			titleOverride = "My name for it",
			coverOverride = "https://example.test/mine.jpg",
			branch = "Scans Anonymous",
			incognito = true,
		)
		repository.save(title, prefs)
		assertEquals(prefs, repository.find(1L))
	}

	@Test
	fun `null stays null rather than becoming a default`() = runBlocking {
		// The whole reason every column is nullable. A title that overrides only its
		// branch must keep following the app's reading mode, including after the app
		// default later changes.
		val prefs = TitlePrefs(mangaId = 1L, branch = "Some Group")
		repository.save(manga(1L, "First"), prefs)

		val loaded = repository.find(1L)!!
		assertNull(loaded.readingMode)
		assertNull(loaded.titleOverride)
		assertNull(loaded.coverOverride)
		assertFalse(loaded.incognito)
		assertEquals("Some Group", loaded.branch)

		// And the stored column really is NULL, not the text "null" or an empty string.
		val row = db.mangaPrefsDao().find(1L)!!
		assertNull(row.readingMode)
		assertNull(row.titleOverride)
		assertNull(row.coverOverride)
	}

	@Test
	fun `a mode explicitly set to the current default is not the same as unset`() = runBlocking {
		repository.save(manga(1L, "Pinned"), TitlePrefs(mangaId = 1L, readingMode = ReadingMode.PagedLtr))
		repository.save(manga(2L, "Unset"), TitlePrefs(mangaId = 2L, branch = "b"))
		// Both would read as "paged left to right" if the repository substituted a
		// default on read. They must not be indistinguishable.
		assertEquals(ReadingMode.PagedLtr, repository.find(1L)?.readingMode)
		assertNull(repository.find(2L)?.readingMode)
	}

	@Test
	fun `saving writes the manga row the foreign key needs`() = runBlocking {
		// Nothing else has stored this title: it is not a favourite, not in history and
		// not bookmarked. Without the parent upsert the insert fails the foreign key.
		assertNull(db.mangaDao().find(7L))
		repository.save(manga(7L, "Seventh"), TitlePrefs(mangaId = 7L, incognito = true))
		assertEquals("Seventh", db.mangaDao().find(7L)?.title)
		assertTrue(repository.find(7L)!!.incognito)
	}

	@Test
	fun `saving does not reset a chapter count another area recorded`() = runBlocking {
		val title = manga(3L, "Third")
		db.mangaDao().upsert(
			MangaEntity(
				mangaId = 3L,
				title = "Third",
				altTitle = null,
				url = "/m/3",
				publicUrl = "https://example.test/m/3",
				rating = 0.5f,
				contentRating = null,
				coverUrl = null,
				largeCoverUrl = null,
				state = null,
				author = null,
				source = MangaParserSource.MANGADEX.name,
				chaptersCount = 120,
			),
		)
		// The editor only ever has the seed title, with no chapter list on it.
		repository.save(title, TitlePrefs(mangaId = 3L, incognito = true))
		assertEquals(120, db.mangaDao().find(3L)?.chaptersCount)
	}

	@Test
	fun `an unknown reading mode reads as not overridden`() = runBlocking {
		repository.save(manga(1L, "First"), TitlePrefs(mangaId = 1L, readingMode = ReadingMode.Webtoon))
		db.mangaPrefsDao().upsert(db.mangaPrefsDao().find(1L)!!.copy(readingMode = "PagedDiagonal"))
		// A file written by a newer build must not make this one refuse the title.
		assertNull(repository.find(1L)?.readingMode)
	}

	@Test
	fun `update starts from what is stored`() = runBlocking {
		val title = manga(1L, "First")
		repository.save(title, TitlePrefs(mangaId = 1L, branch = "Group A"))
		repository.update(title) { it.copy(readingMode = ReadingMode.Webtoon) }
		val loaded = repository.find(1L)!!
		assertEquals("Group A", loaded.branch)
		assertEquals(ReadingMode.Webtoon, loaded.readingMode)
	}

	@Test
	fun `update on a title with no row starts from empty`() = runBlocking {
		val title = manga(4L, "Fourth")
		repository.update(title) { it.copy(incognito = true) }
		val loaded = repository.find(4L)!!
		assertTrue(loaded.incognito)
		assertNull(loaded.branch)
		assertEquals(4L, loaded.mangaId)
	}

	@Test
	fun `clear removes the row entirely rather than blanking it`() = runBlocking {
		repository.save(manga(1L, "First"), TitlePrefs(mangaId = 1L, incognito = true))
		repository.clear(1L)
		assertNull(repository.find(1L))
		assertNull(db.mangaPrefsDao().find(1L))
		// The title itself survives: other areas reference that row.
		assertNotNull(db.mangaDao().find(1L))
	}

	@Test
	fun `observe reports the current row and null when there is none`() = runBlocking {
		assertNull(repository.observe(1L).first())
		repository.save(manga(1L, "First"), TitlePrefs(mangaId = 1L, readingMode = ReadingMode.Webtoon))
		assertEquals(ReadingMode.Webtoon, repository.observe(1L).first()?.readingMode)
	}

	@Test
	fun `the overrides list carries the title and skips rows that override nothing`() = runBlocking {
		repository.save(manga(1L, "Zulu"), TitlePrefs(mangaId = 1L, readingMode = ReadingMode.PagedRtl))
		repository.save(manga(2L, "alpha"), TitlePrefs(mangaId = 2L, incognito = true))
		// Written but overriding nothing, so it is not something the user configured.
		repository.save(manga(3L, "Mike"), TitlePrefs(mangaId = 3L))

		val listed = repository.listOverrides()
		assertEquals(listOf(2L, 1L), listed.map { it.prefs.mangaId })
		// Sorted by title, case insensitively, which is what a human scanning it expects.
		assertEquals(listOf("alpha", "Zulu"), listed.map { it.title })
		assertEquals("MANGADEX", listed.first().sourceName)
		assertEquals("https://example.test/c/2.jpg", listed.first().coverUrl)
		assertTrue(listed.first().prefs.incognito)
		assertEquals(ReadingMode.PagedRtl, listed.last().prefs.readingMode)
	}

	@Test
	fun `the overrides list keeps nulls null`() = runBlocking {
		repository.save(manga(1L, "First"), TitlePrefs(mangaId = 1L, branch = "Group A"))
		val entry = repository.listOverrides().single()
		assertNull(entry.prefs.readingMode)
		assertNull(entry.prefs.titleOverride)
		assertNull(entry.prefs.coverOverride)
		assertFalse(entry.prefs.incognito)
		assertEquals("Group A", entry.prefs.branch)
	}

	// Timed out rather than left to hang: this is the one call that depends on Room's
	// invalidation flow emitting an initial value, and a wrong assumption there would
	// otherwise wedge the whole test run instead of failing.
	@Test(timeout = 30_000)
	fun `observeOverrides emits the current list`() = runBlocking {
		repository.save(manga(1L, "First"), TitlePrefs(mangaId = 1L, incognito = true))
		assertEquals(1, repository.observeOverrides().first().size)
	}

	@Test
	fun `an empty override list is empty rather than failing`() = runBlocking {
		assertEquals(emptyList<TitleOverride>(), repository.listOverrides())
	}

	@Test
	fun `prefs for a title that does not exist are refused`() = runBlocking {
		// This is what makes the parent upsert in save() load-bearing rather than
		// defensive. If the foreign key were not enforced this would quietly succeed and
		// the whole reason save() touches two tables would evaporate.
		val thrown = runCatching {
			db.mangaPrefsDao().upsert(TitlePrefs(mangaId = 404L, incognito = true).toEntity())
		}.exceptionOrNull()
		assertNotNull("expected the foreign key to reject an orphan row", thrown)
		assertNull(db.mangaPrefsDao().find(404L))
	}

	@Test
	fun `saving prefs for the wrong title is rejected`() = runBlocking {
		val thrown = runCatching {
			repository.save(manga(1L, "First"), TitlePrefs(mangaId = 2L))
		}.exceptionOrNull()
		assertTrue("expected an IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
	}
}
