package org.koitharu.kotatsu.desktop.feature.migration

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
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
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity
import org.koitharu.kotatsu.shared.db.FavouriteEntity
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import org.koitharu.kotatsu.shared.db.TrackEntity
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.nio.file.Files

/**
 * Detection of broken library entries, and the bulk repair over them.
 *
 * Everything that would be a network call is injected, so this runs offline and
 * deterministically.
 */
class AutoFixTest {

	private lateinit var db: LibraryDatabase
	private lateinit var file: java.io.File
	private var categoryId = 0L

	private val brokenSource: MangaParserSource = MangaParserSource.entries.first { it.isBroken }

	private val healthySource: MangaParserSource =
		MangaParserSource.entries.first { !it.isBroken && it.contentType != ContentType.NOVEL }

	/** Small, so a test does not fan out over a thousand sources. */
	private val catalogue: List<MangaParserSource> = MangaParserSource.entries
		.filter { !it.isBroken && it.contentType != ContentType.NOVEL }
		.take(3)

	@Before
	fun setUp() {
		file = Files.createTempFile("autofix", ".db").toFile()
		file.delete()
		db = openLibraryDatabase(file.toPath().toOkioPath(), now = { 1_000L })
		categoryId = runBlocking {
			db.favouriteCategoriesDao().insert(
				FavouriteCategoryEntity(0L, 1_000L, 0, "Reading", "NEWEST", true, true),
			)
		}
	}

	@After
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `the scan reports broken and missing sources and ignores healthy ones`() = runBlocking {
		storeEntry(1L, "On a broken source", brokenSource.name)
		storeEntry(2L, "On a vanished source", "A_SOURCE_THAT_NO_LONGER_EXISTS")
		storeEntry(3L, "Perfectly fine", healthySource.name)

		val scanner = LibraryScanner(db)
		val all = scanner.scan().associateBy { it.manga.id }
		assertEquals(3, all.size)
		assertEquals(EntryHealth.BROKEN, all.getValue(1L).health)
		assertEquals(EntryHealth.MISSING, all.getValue(2L).health)
		assertEquals(EntryHealth.OK, all.getValue(3L).health)
		assertNull("a vanished source has no parser to offer", all.getValue(2L).source)

		val broken = scanner.scanBroken().map { it.manga.id }
		assertEquals(listOf(1L, 2L), broken.sorted())
	}

	@Test
	fun `a manga row nobody keeps is not a library entry`() = runBlocking {
		// Looked at once, never favourited and never read: it is cache, not library, and
		// listing it would bury the entries the user would actually miss.
		db.mangaDao().upsert(row(9L, "Merely browsed", brokenSource.name))

		assertEquals(emptyList<LibraryEntry>(), LibraryScanner(db).scan())
	}

	@Test
	fun `history alone is enough to make something a library entry`() = runBlocking {
		db.mangaDao().upsert(row(9L, "Read but not saved", brokenSource.name))
		db.historyDao().upsert(HistoryEntity(9L, 10L, 20L, 90L, 0, 0f, 0.1f, 1, 0L))

		val entry = LibraryScanner(db).scan().single()
		assertEquals(9L, entry.manga.id)
		assertFalse(entry.isFavourite)
		assertTrue(entry.hasHistory)
	}

	@Test
	fun `a bulk run keeps going after one entry throws`() = runBlocking {
		storeEntry(1L, "First", brokenSource.name)
		storeEntry(2L, "Second", brokenSource.name)
		storeEntry(3L, "Third", brokenSource.name)
		// Only the second entry is tracked, so only the second entry reaches the clock.
		db.tracksDao().upsert(TrackEntity(2L, 0L, 0L, 0, 0L, null))

		val entries = LibraryScanner(db).scanBroken()
		assertEquals(3, entries.size)

		val runner = AutoFixRunner(
			engine = AlternativesEngine(
				searcher = { source, query -> listOf(candidateFor(query, source)) },
				details = { hit -> hit.copy(chapters = listOf(chapter(hit.id * 10, 1f, sourceOf(hit)))) },
				concurrency = 2,
			),
			service = MigrationService(
				// The failure is injected through the clock, which the migration only reads
				// when it has a tracking row to rewrite. That puts the throw part-way
				// through entry two and nowhere near entries one and three.
				MigrationRepository(db, now = { error("the clock exploded") }),
				details = { hit -> hit },
			),
			catalogue = catalogue,
		)

		val events = runner.run(entries).toList()
		val outcomes = events.mapNotNull { it.toOutcome() }.associateBy { it.entry.manga.id }

		assertEquals(3, outcomes.size)
		assertTrue("first entry", outcomes.getValue(1L).isSuccess)
		assertFalse("second entry throws", outcomes.getValue(2L).isSuccess)
		assertTrue("third entry runs after the failure", outcomes.getValue(3L).isSuccess)
		assertTrue(outcomes.getValue(2L).message.contains("the clock exploded"))
		// Every entry was started, including the one after the failure.
		assertEquals(
			listOf(0, 1, 2),
			events.filterIsInstance<AutoFixEvent.Started>().map { it.index },
		)

		// The two that worked really moved, and the one that failed really did not.
		assertEquals(setOf(categoryId), categoriesOf(candidateIdFor("First")))
		assertEquals(setOf(categoryId), categoriesOf(candidateIdFor("Third")))
		assertEquals(setOf(categoryId), categoriesOf(2L))
		assertEquals(emptySet<Long>(), categoriesOf(candidateIdFor("Second")))
		assertNotNull("the failed entry keeps its tracking row", db.tracksDao().find(2L))
	}

	@Test
	fun `an entry with no usable alternative is reported rather than failed`() = runBlocking {
		storeEntry(1L, "Nowhere else", brokenSource.name)
		val entries = LibraryScanner(db).scanBroken()

		val runner = AutoFixRunner(
			engine = AlternativesEngine(
				// Every source answers, none of them with chapters.
				searcher = { source, query -> listOf(candidateFor(query, source)) },
				details = { it },
				concurrency = 2,
			),
			service = MigrationService(MigrationRepository(db), details = { it }),
			catalogue = catalogue,
		)

		val outcome = runner.run(entries).toList().mapNotNull { it.toOutcome() }.single()
		assertFalse(outcome.isSuccess)
		assertEquals("no other source has this title", outcome.message)
		// Untouched: nothing was written for an entry that could not be repaired.
		assertEquals(setOf(categoryId), categoriesOf(1L))
	}

	/**
	 * Fixed ids per title.
	 *
	 * Derived ids are a trap here: "First" and "Third" are the same length, so anything
	 * based on the title's shape would silently give two entries one id and the test
	 * would pass for the wrong reason.
	 */
	private val candidateIds = mapOf(
		"First" to 101L,
		"Second" to 102L,
		"Third" to 103L,
		"Nowhere else" to 104L,
	)

	private fun candidateIdFor(title: String): Long = candidateIds.getValue(title)

	private fun candidateFor(title: String, source: MangaParserSource): Manga =
		manga(candidateIdFor(title), title, source)

	private suspend fun storeEntry(id: Long, title: String, source: String) {
		db.mangaDao().upsert(row(id, title, source))
		db.favouritesDao().upsert(FavouriteEntity(id, categoryId, 0, 500L, 0L))
	}

	private fun row(id: Long, title: String, source: String) = MangaEntity(
		mangaId = id,
		title = title,
		altTitle = null,
		url = "/manga/$id",
		publicUrl = "https://example.test/manga/$id",
		rating = 0.5f,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		state = null,
		author = null,
		source = source,
		chaptersCount = 0,
	)

	private suspend fun categoriesOf(mangaId: Long): Set<Long> =
		db.favouritesDao().observeCategoriesOf(mangaId).first().toSet()
}
