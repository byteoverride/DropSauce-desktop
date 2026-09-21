package org.koitharu.kotatsu.desktop.feature.suggestions

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
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.shared.db.FavouriteEntity
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path as JavaPath

/**
 * The parts that touch the database: profile building from real rows, and the stored set.
 *
 * A real temp-file database rather than an in-memory one, for the same reason the other
 * areas use one: the foreign keys and the pre-populated default category are schema
 * behaviour, and this is where the assumptions about them are checked.
 */
class SuggestionsRepositoryTest {

	private lateinit var db: LibraryDatabase
	private lateinit var dbFile: File
	private lateinit var dir: JavaPath
	private lateinit var store: SuggestionStore
	private lateinit var repository: SuggestionsRepository

	private val source = MangaParserSource.MANGADEX

	@Before
	fun setUp() {
		dbFile = Files.createTempFile("suggestions", ".db").toFile()
		dbFile.delete()
		dir = Files.createTempDirectory("suggestions-store")
		db = openLibraryDatabase(dbFile.toPath().toOkioPath(), now = { 1_000L })
		store = SuggestionStore(dir.resolve(SuggestionStore.FILE_NAME).toOkioPath())
		repository = SuggestionsRepository(db, store, now = { 5_000L })
	}

	@After
	fun tearDown() {
		db.close()
		dbFile.delete()
		dir.toFile().deleteRecursively()
	}

	private fun manga(id: Long, title: String = "Title $id", vararg tags: String) = Manga(
		id = id,
		title = title,
		altTitles = emptySet(),
		url = "/m/$id",
		publicUrl = "https://example.test/m/$id",
		rating = 0.8f,
		contentRating = null,
		coverUrl = "https://example.test/c/$id.jpg",
		largeCoverUrl = null,
		tags = tags.map { MangaTag(title = it, key = it.lowercase(), source = source) }.toSet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		source = source,
	)

	private suspend fun insertManga(manga: Manga, chaptersCount: Int = 0) {
		db.mangaDao().upsert(manga.toEntity(chaptersCount))
	}

	private suspend fun addHistory(id: Long, percent: Float, updatedAt: Long) {
		db.historyDao().upsert(
			HistoryEntity(
				mangaId = id,
				createdAt = updatedAt,
				updatedAt = updatedAt,
				chapterId = 1L,
				page = 0,
				scroll = 0f,
				percent = percent,
				chaptersCount = 10,
				deletedAt = 0L,
			),
		)
	}

	private suspend fun addFavourite(id: Long) {
		val category = db.favouriteCategoriesDao().getAll().first()
		db.favouritesDao().upsert(
			FavouriteEntity(
				mangaId = id,
				categoryId = category.categoryId,
				sortKey = 0,
				createdAt = 1_000L,
				deletedAt = 0L,
			),
		)
	}

	private fun profiler(tags: Map<Long, List<String>>, failing: Set<Long> = emptySet()) =
		SuggestionProfiler(
			db = db,
			tags = TagCache(dir.resolve("tags-${tags.hashCode()}.json").toOkioPath()),
			resolver = { _, seed ->
				if (seed.id in failing) error("no details for ${seed.id}")
				tags[seed.id] ?: emptyList()
			},
		)

	// -- profile from real rows ---------------------------------------------------

	@Test
	fun `an empty library profiles as empty rather than throwing`() = runBlocking {
		val profile = profiler(emptyMap()).profile()
		assertTrue(profile.isEmpty)
		assertEquals(0, profile.sampleSize)
	}

	@Test
	fun `a title read to ninety percent outweighs one opened once, from real rows`() =
		runBlocking {
			insertManga(manga(1L, "Nearly done"))
			insertManga(manga(2L, "Barely started"))
			addHistory(1L, percent = 0.9f, updatedAt = 2_000L)
			addHistory(2L, percent = 0.01f, updatedAt = 3_000L)

			val profile = profiler(
				mapOf(1L to listOf("Deep"), 2L to listOf("Shallow")),
			).profile()

			assertEquals(2, profile.sampleSize)
			assertTrue(
				"Deep=${profile.affinityFor("Deep")} Shallow=${profile.affinityFor("Shallow")}",
				profile.affinityFor("Deep") > profile.affinityFor("Shallow"),
			)
		}

	@Test
	fun `an untouched favourite counts toward the profile, but less than a read title`() =
		runBlocking {
			insertManga(manga(1L, "Read"))
			insertManga(manga(2L, "Shelved"))
			addHistory(1L, percent = 0.01f, updatedAt = 2_000L)
			addFavourite(2L)

			val profile = profiler(
				mapOf(1L to listOf("Opened"), 2L to listOf("Saved")),
			).profile()

			assertTrue("a shelved title must count", profile.affinityFor("Saved") > 0f)
			assertTrue(
				"Saved=${profile.affinityFor("Saved")} Opened=${profile.affinityFor("Opened")}",
				profile.affinityFor("Saved") < profile.affinityFor("Opened"),
			)
		}

	@Test
	fun `a title that is both favourite and history is one signal, not two`() = runBlocking {
		insertManga(manga(1L, "Both"))
		addHistory(1L, percent = 0.5f, updatedAt = 2_000L)
		addFavourite(1L)
		val profile = profiler(mapOf(1L to listOf("Shared"))).profile()
		assertEquals(1, profile.sampleSize)
		assertEquals(1, profile.tags.getValue(tagKey("Shared")).titles.size)
	}

	@Test
	fun `one title whose details fail does not lose the rest of the profile`() = runBlocking {
		insertManga(manga(1L, "Fine"))
		insertManga(manga(2L, "Broken"))
		addHistory(1L, percent = 0.9f, updatedAt = 2_000L)
		addHistory(2L, percent = 0.9f, updatedAt = 3_000L)

		val profile = profiler(
			tags = mapOf(1L to listOf("Survives")),
			failing = setOf(2L),
		).profile()

		assertEquals("both titles still count as seeds", 2, profile.sampleSize)
		assertTrue(profile.affinityFor("Survives") > 0f)
	}

	@Test
	fun `the tag cache spares a second details fetch`() = runBlocking {
		insertManga(manga(1L, "Cached"))
		addHistory(1L, percent = 0.9f, updatedAt = 2_000L)
		val cache = TagCache(dir.resolve("shared-tags.json").toOkioPath())
		var calls = 0
		fun build() = SuggestionProfiler(
			db = db,
			tags = cache,
			resolver = { _, _ ->
				calls++
				listOf("Once")
			},
		)
		build().profile()
		build().profile()
		assertEquals("the second run must read the cache", 1, calls)
	}

	@Test
	fun `progress is reported only for titles that need fetching`() = runBlocking {
		insertManga(manga(1L, "A"))
		insertManga(manga(2L, "B"))
		addHistory(1L, percent = 0.5f, updatedAt = 2_000L)
		addHistory(2L, percent = 0.5f, updatedAt = 3_000L)
		val seen = mutableListOf<Pair<Int, Int>>()
		profiler(mapOf(1L to listOf("X"), 2L to listOf("Y"))).profile { done, total ->
			seen += done to total
		}
		assertEquals(listOf(1 to 2, 2 to 2), seen.sortedBy { it.first })
	}

	// -- the stored set -----------------------------------------------------------

	@Test
	fun `a stored suggestion round-trips with its reason intact`() = runBlocking {
		repository.replace(
			listOf(
				StoredSuggestion(manga(100L, "Recommended"), 0.8f, "Shares Action with 3 titles you read"),
			),
		)
		val cards = repository.observe().first()
		assertEquals(1, cards.size)
		val card = cards.single()
		assertEquals(100L, card.manga.id)
		assertEquals("Recommended", card.manga.title)
		assertEquals("Shares Action with 3 titles you read", card.reason)
		assertEquals(0.8f, card.relevance, 0.0001f)
		assertEquals(5_000L, card.createdAt)
		assertEquals("https://example.test/c/100.jpg", card.manga.coverUrl)
	}

	@Test
	fun `a refresh replaces the whole set rather than adding to it`() = runBlocking {
		repository.replace(listOf(StoredSuggestion(manga(100L), 0.8f, "first run")))
		repository.replace(listOf(StoredSuggestion(manga(101L), 0.7f, "second run")))
		assertEquals(listOf(101L), repository.observe().first().map { it.manga.id })
	}

	@Test
	fun `stored suggestions come back best first`() = runBlocking {
		repository.replace(
			listOf(
				StoredSuggestion(manga(100L), 0.2f, "weak"),
				StoredSuggestion(manga(101L), 0.9f, "strong"),
				StoredSuggestion(manga(102L), 0.5f, "middling"),
			),
		)
		assertEquals(listOf(101L, 102L, 100L), repository.observe().first().map { it.manga.id })
	}

	@Test
	fun `writing a suggestion never overwrites an existing library row`() = runBlocking {
		// A favourite with a real chapter count. The suggestion's projection has none,
		// and the library filters on that column, so clobbering it would be a data loss
		// visible on a completely different screen.
		insertManga(manga(100L, "Already mine"), chaptersCount = 42)
		addFavourite(100L)
		repository.replace(listOf(StoredSuggestion(manga(100L, "Renamed by a source"), 0.9f, "x")))

		val row = db.mangaDao().find(100L)
		assertNotNull(row)
		assertEquals(42, row!!.chaptersCount)
		assertEquals("Already mine", row.title)
	}

	@Test
	fun `a dismissed suggestion disappears from the grid without another refresh`() =
		runBlocking {
			repository.replace(
				listOf(
					StoredSuggestion(manga(100L), 0.8f, "one"),
					StoredSuggestion(manga(101L), 0.7f, "two"),
				),
			)
			repository.dismiss(100L)
			assertEquals(listOf(101L), repository.observe().first().map { it.manga.id })
		}

	@Test
	fun `a dismissal outlives the process`() = runBlocking {
		repository.replace(listOf(StoredSuggestion(manga(100L), 0.8f, "one")))
		repository.dismiss(100L)

		val reopened = SuggestionsRepository(
			db,
			SuggestionStore(dir.resolve(SuggestionStore.FILE_NAME).toOkioPath()),
		)
		assertTrue(reopened.observe().first().isEmpty())
	}

	@Test
	fun `a suggestion whose manga row was deleted is skipped, not rendered as a gap`() =
		runBlocking {
			repository.replace(listOf(StoredSuggestion(manga(100L), 0.8f, "one")))
			// Deleting the history row cascades nothing here, so remove the manga row the
			// way the library would: through the favourites cascade path is not available,
			// so assert on the skip by pointing a suggestion at an id with no row.
			db.suggestionsDao().clear()
			db.suggestionsDao().upsertAll(
				listOf(
					org.koitharu.kotatsu.shared.db.SuggestionEntity(
						mangaId = 999L,
						relevance = 0.9f,
						createdAt = 1L,
						reason = "orphan",
					),
				),
			)
			assertTrue(repository.observe().first().isEmpty())
			assertNull(db.mangaDao().find(999L))
		}

	@Test
	fun `clearing forgets everything`() = runBlocking {
		repository.replace(listOf(StoredSuggestion(manga(100L), 0.8f, "one")))
		repository.clear()
		assertTrue(repository.observe().first().isEmpty())
	}
}
