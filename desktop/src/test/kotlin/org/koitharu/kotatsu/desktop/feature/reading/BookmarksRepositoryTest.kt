package org.koitharu.kotatsu.desktop.feature.reading

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
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.nio.file.Files

/**
 * Bookmarks against a real database file.
 *
 * A real file rather than an in-memory database on purpose: the foreign key onto `manga`
 * and the composite primary key are schema behaviour, and the whole point of these tests
 * is that the schema enforces what the repository assumes it does.
 */
class BookmarksRepositoryTest {

	private lateinit var db: LibraryDatabase
	private lateinit var file: java.io.File
	private lateinit var repository: BookmarksRepository

	private var clock = 1_000L

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
		file = Files.createTempFile("bookmarks", ".db").toFile()
		file.delete()
		db = openLibraryDatabase(file.toPath().toOkioPath(), now = { 1_000L })
		repository = BookmarksRepository(db, now = { clock })
	}

	@After
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `a bookmark round-trips through the database`() = runBlocking {
		val manga = manga(1L, "First")
		repository.add(
			manga = manga,
			chapterId = 77L,
			pageId = 501L,
			page = 12,
			imageUrl = "https://example.test/p/501.jpg",
			percent = 0.42f,
		)
		val saved = repository.observeAll().first()
		assertEquals(1, saved.size)
		val bookmark = saved.single()
		assertEquals(1L, bookmark.manga.id)
		assertEquals("First", bookmark.manga.title)
		assertEquals("MANGADEX", bookmark.sourceName)
		assertEquals(77L, bookmark.chapterId)
		assertEquals(501L, bookmark.pageId)
		assertEquals(12, bookmark.page)
		assertEquals("https://example.test/p/501.jpg", bookmark.imageUrl)
		assertEquals(0.42f, bookmark.percent, 0.0001f)
		assertEquals(1_000L, bookmark.createdAt)
		assertTrue(repository.isBookmarked(1L, 501L))
	}

	@Test
	fun `adding the same page twice does not create a duplicate`() = runBlocking {
		val manga = manga(1L, "First")
		repository.add(manga, 77L, 501L, 12, "https://example.test/p/a.jpg", 0.4f)
		clock = 2_000L
		// A source that hands out a fresh page address per request must not produce a
		// second bookmark; the pair (manga, page) is the identity.
		repository.add(manga, 77L, 501L, 12, "https://example.test/p/b.jpg", 0.5f)
		val saved = repository.observeAll().first()
		assertEquals(1, saved.size)
		assertEquals("https://example.test/p/b.jpg", saved.single().imageUrl)
		assertEquals(2_000L, saved.single().createdAt)
	}

	@Test
	fun `delete removes only the page asked for`() = runBlocking {
		val manga = manga(1L, "First")
		repository.add(manga, 77L, 501L, 12, "a", 0.4f)
		repository.add(manga, 77L, 502L, 13, "b", 0.5f)
		repository.remove(1L, 501L)
		val saved = repository.observeAll().first()
		assertEquals(1, saved.size)
		assertEquals(502L, saved.single().pageId)
		assertFalse(repository.isBookmarked(1L, 501L))
		assertNull(db.bookmarksDao().find(1L, 501L))
	}

	@Test
	fun `removeAllFor clears one title and leaves the others`() = runBlocking {
		repository.add(manga(1L, "First"), 77L, 501L, 0, "a", 0.1f)
		repository.add(manga(1L, "First"), 77L, 502L, 1, "b", 0.2f)
		repository.add(manga(2L, "Second"), 88L, 601L, 0, "c", 0.3f)
		repository.removeAllFor(1L)
		val saved = repository.observeAll().first()
		assertEquals(1, saved.size)
		assertEquals(2L, saved.single().manga.id)
	}

	@Test
	fun `observing by manga returns only that manga's bookmarks`() = runBlocking {
		repository.add(manga(1L, "First"), 77L, 501L, 0, "a", 0.1f)
		repository.add(manga(2L, "Second"), 88L, 601L, 5, "b", 0.3f)
		repository.add(manga(2L, "Second"), 88L, 602L, 6, "c", 0.4f)
		assertEquals(1, repository.observeFor(1L).first().size)
		val second = repository.observeFor(2L).first()
		assertEquals(2, second.size)
		assertTrue(second.all { it.mangaId == 2L })
	}

	@Test
	fun `toggle adds then removes and reports the resulting state`() = runBlocking {
		val manga = manga(1L, "First")
		assertTrue(repository.toggle(manga, 77L, 501L, 3, "a", 0.2f))
		assertEquals(1, repository.observeAll().first().size)
		assertFalse(repository.toggle(manga, 77L, 501L, 3, "a", 0.2f))
		assertEquals(0, repository.observeAll().first().size)
	}

	@Test
	fun `bookmarking writes the manga row the foreign key needs`() = runBlocking {
		// Nothing else has stored this title: it is not a favourite and not in history.
		// If add() did not upsert the manga row first the insert would fail the
		// foreign key, which is the failure this test exists to catch.
		assertNull(db.mangaDao().find(9L))
		repository.add(manga(9L, "Ninth"), 1L, 2L, 0, "a", 0f)
		assertEquals("Ninth", db.mangaDao().find(9L)?.title)
	}

	@Test
	fun `the newest bookmark comes first`() = runBlocking {
		clock = 1_000L
		repository.add(manga(1L, "First"), 77L, 501L, 0, "a", 0.1f)
		clock = 3_000L
		repository.add(manga(2L, "Second"), 88L, 601L, 0, "b", 0.2f)
		clock = 2_000L
		repository.add(manga(3L, "Third"), 99L, 701L, 0, "c", 0.3f)
		assertEquals(listOf(2L, 3L, 1L), repository.observeAll().first().map { it.manga.id })
	}
}
