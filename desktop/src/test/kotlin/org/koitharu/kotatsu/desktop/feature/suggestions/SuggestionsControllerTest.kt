package org.koitharu.kotatsu.desktop.feature.suggestions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.image.ImageCache
import org.koitharu.kotatsu.desktop.library.LibraryRepository
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import org.koitharu.kotatsu.shared.io.AppPaths
import org.koitharu.kotatsu.shared.settings.SettingsData
import org.koitharu.kotatsu.shared.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path as JavaPath

/**
 * A whole refresh through the controller, with fake sources and a real database.
 *
 * This is the one path the unit tests underneath cannot reach: ranking produces a
 * [Candidate], which carries no cover, and persisting needs the [Manga] that
 * [CandidateCollector] recorded on the way past. If that hand-off broke, every refresh
 * would store nothing and the screen would look exactly like a source that answered with
 * nothing. So the fake sources record into the real collector and the assertions are
 * made against what actually landed in the table.
 */
class SuggestionsControllerTest {

	private lateinit var db: LibraryDatabase
	private lateinit var dbFile: File
	private lateinit var dir: JavaPath
	private lateinit var store: SuggestionStore
	private lateinit var repository: SuggestionsRepository
	private lateinit var context: FeatureContext

	private val source = MangaParserSource.MANGADEX

	@Before
	fun setUp() {
		dbFile = Files.createTempFile("controller", ".db").toFile()
		dbFile.delete()
		dir = Files.createTempDirectory("controller-store")
		db = openLibraryDatabase(dbFile.toPath().toOkioPath(), now = { 1_000L })
		store = SuggestionStore(dir.resolve(SuggestionStore.FILE_NAME).toOkioPath())
		repository = SuggestionsRepository(db, store, now = { 5_000L })
		context = FakeContext(db, dir)
	}

	@After
	fun tearDown() {
		db.close()
		dbFile.delete()
		dir.toFile().deleteRecursively()
	}

	private fun manga(id: Long, vararg tags: String) = Manga(
		id = id,
		title = "Title $id",
		altTitles = emptySet(),
		url = "/m/$id",
		publicUrl = "https://example.test/m/$id",
		rating = 0.5f,
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

	private suspend fun seedHistory(id: Long, percent: Float) {
		db.mangaDao().upsert(manga(id).toEntity())
		db.historyDao().upsert(
			HistoryEntity(
				mangaId = id,
				createdAt = 1_000L,
				updatedAt = 1_000L + id,
				chapterId = 1L,
				page = 0,
				scroll = 0f,
				percent = percent,
				chaptersCount = 10,
				deletedAt = 0L,
			),
		)
	}

	/**
	 * A controller wired to [offers] instead of to the catalogue.
	 *
	 * The fake fetcher records into the same collector the real one uses, which is the
	 * whole point: everything after the network call is the production path.
	 */
	private fun controller(
		offers: List<Manga>,
		libraryTags: Map<Long, List<String>>,
		failing: Boolean = false,
	): Pair<SuggestionsController, CandidateCollector> {
		val collector = CandidateCollector()
		val controller = SuggestionsController(
			context = context,
			repository = repository,
			profiler = SuggestionProfiler(
				db = db,
				tags = TagCache(dir.resolve("tags.json").toOkioPath()),
				resolver = { _, seed -> libraryTags[seed.id].orEmpty() },
			),
			store = store,
			collector = collector,
			candidateSources = {
				listOf(
					CandidateSource("GOOD", "Good source") {
						offers.forEach(collector::record)
						offers.map { it.toCandidate(source) }
					},
				) + if (failing) {
					listOf(CandidateSource("BAD", "Bad source") { error("unreachable") })
				} else {
					emptyList()
				}
			},
		)
		return controller to collector
	}

	@Test
	fun `a refresh stores what it ranked, with reasons, and the grid can read it back`() =
		runBlocking {
			seedHistory(1L, percent = 0.9f)
			val (controller, _) = controller(
				offers = listOf(manga(100L, "Action", "Fantasy"), manga(101L, "Action")),
				libraryTags = mapOf(1L to listOf("Action", "Fantasy")),
			)

			controller.refresh(this).join()

			val phase = controller.phase
			assertTrue("phase was $phase", phase is RefreshPhase.Finished)
			assertEquals(2, (phase as RefreshPhase.Finished).stored)

			val cards = repository.observe().first()
			assertEquals(listOf(100L, 101L), cards.map { it.manga.id })
			assertEquals(
				"the cover must survive the round trip",
				"https://example.test/c/100.jpg",
				cards.first().manga.coverUrl,
			)
			assertTrue(cards.first().reason, cards.first().reason.contains("Action"))
			assertTrue(cards.first().reason, cards.first().reason.contains("Fantasy"))
			assertTrue(cards.first().reason, cards.first().reason.contains("1 title you read"))
		}

	@Test
	fun `a failing source does not stop the run from storing the rest`() = runBlocking {
		seedHistory(1L, percent = 0.9f)
		val (controller, _) = controller(
			offers = listOf(manga(100L, "Action")),
			libraryTags = mapOf(1L to listOf("Action")),
			failing = true,
		)

		controller.refresh(this).join()

		val phase = controller.phase as RefreshPhase.Finished
		assertEquals(1, phase.stored)
		assertEquals(1, phase.state.failed)
		assertEquals(listOf(100L), repository.observe().first().map { it.manga.id })
	}

	@Test
	fun `a dismissed title does not come back on the next refresh`() = runBlocking {
		seedHistory(1L, percent = 0.9f)
		val offers = listOf(manga(100L, "Action"), manga(101L, "Action"))
		val tags = mapOf(1L to listOf("Action"))

		controller(offers, tags).first.refresh(this).join()
		assertEquals(2, repository.observe().first().size)

		repository.dismiss(100L)
		// A brand new controller over the same store, which is what a restart looks like.
		controller(offers, tags).first.refresh(this).join()

		val cards = repository.observe().first()
		assertEquals(listOf(101L), cards.map { it.manga.id })
	}

	@Test
	fun `an empty library refreshes to nothing and says why, without asking a source`() =
		runBlocking {
			var asked = false
			val controller = SuggestionsController(
				context = context,
				repository = repository,
				profiler = SuggestionProfiler(
					db = db,
					tags = TagCache(dir.resolve("tags.json").toOkioPath()),
					resolver = { _, _ -> emptyList() },
				),
				store = store,
				candidateSources = {
					listOf(
						CandidateSource("GOOD", "Good source") {
							asked = true
							emptyList()
						},
					)
				},
			)

			controller.refresh(this).join()

			val phase = controller.phase as RefreshPhase.Finished
			assertEquals(0, phase.stored)
			assertTrue("an empty library must not hit the network", !asked)
			assertTrue(
				phase.state.emptyExplanation().orEmpty(),
				phase.state.emptyExplanation().orEmpty().contains("Add a title to your library"),
			)
			assertTrue(repository.observe().first().isEmpty())
		}

	@Test
	fun `a refresh replaces the previous set rather than growing it`() = runBlocking {
		seedHistory(1L, percent = 0.9f)
		val tags = mapOf(1L to listOf("Action"))
		controller(listOf(manga(100L, "Action")), tags).first.refresh(this).join()
		controller(listOf(manga(101L, "Action")), tags).first.refresh(this).join()
		assertEquals(listOf(101L), repository.observe().first().map { it.manga.id })
	}
}

/**
 * The contract, with the pieces this area does not use left unusable.
 *
 * `clientFor` and `images` are only reached by the composables, which are not under test
 * here, so they throw rather than pretend: a test that silently started making HTTP
 * calls would be worse than one that fails loudly.
 */
private class FakeContext(
	override val db: LibraryDatabase,
	private val dir: JavaPath,
) : FeatureContext {

	override val sources = SourceRegistry()

	override val images = ImageCache(maxEntries = 1)

	override val settings: SettingsStore = object : SettingsStore {
		override val data: StateFlow<SettingsData> = MutableStateFlow(SettingsData())
		override suspend fun update(transform: (SettingsData) -> SettingsData) = Unit
	}

	override val paths: AppPaths = object : AppPaths {
		override val data: Path = dir.toOkioPath()
		override val cache: Path = dir.toOkioPath()
		override val config: Path = dir.toOkioPath()
		override val localLibrary: Path = dir.toOkioPath()
	}

	override val library = LibraryRepository(db)

	override val scope: CoroutineScope get() = error("the test supplies its own scope")

	override fun clientFor(source: MangaParserSource): OkHttpClient =
		error("no HTTP in this test")

	override val httpClient: OkHttpClient
		get() = error("no HTTP in this test")

	override suspend fun details(source: MangaParserSource, manga: Manga): Manga =
		error("the profiler's resolver is faked, so this must not be reached")

	override fun visibleSources(): List<MangaParserSource> = listOf(MangaParserSource.MANGADEX)
}
