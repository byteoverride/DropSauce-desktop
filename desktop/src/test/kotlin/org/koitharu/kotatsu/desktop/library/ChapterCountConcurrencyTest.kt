package org.koitharu.kotatsu.desktop.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parts of a refresh run that only misbehave when several workers land at once.
 *
 * A run is four coroutines writing one progress value and two counters, so every defect
 * in here is invisible against a single title and shows up against a real library.
 */
class ChapterCountConcurrencyTest {

	private val file = Files.createTempFile("chapter-counts", ".db").toFile().also { it.delete() }
	private val db: LibraryDatabase = openLibraryDatabase(file.toOkioPath())
	private val repo = LibraryRepository(db)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	@AfterTest
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `reported counts never go negative when failures outpace completions`() {
		// The shape the workers can briefly produce: a title is marked failed before it is
		// marked done, so two failures landing together leave failed ahead of done.
		val skewed = ChapterCountProgress(done = 1, total = 4, failed = 2, running = true)
		assertEquals(0, skewed.filled)
	}

	@Test
	fun `progress only ever moves forward`() = runBlocking {
		val category = repo.createCategory("Test")
		repeat(24) { repo.setFavourite(manga(it + 1L, "T$it"), category, true) }

		val seen = ConcurrentLinkedQueue<Int>()
		val refresher = ChapterCountRefresher(
			db = db,
			// Uneven delays so workers finish out of order, which is what produced a
			// backwards jump when progress was assigned rather than merged.
			fetcher = ChapterCountFetcher { _, m ->
				delay((m.id % 7) * 3)
				if (m.id % 3 == 0L) throw java.io.IOException("nope") else 42
			},
			scope = scope,
		)
		val watcher = scope.launch { refresher.progress.collect { it?.let { p -> seen += p.done } } }
		val result = refresher.run(categoryId = null)
		watcher.cancel()

		assertEquals(24, result.done)
		assertEquals(24, result.total)
		assertTrue(!result.running)
		// Every observed value is at least the one before it.
		val list = seen.toList()
		assertEquals(list.sorted(), list, "progress went backwards: $list")
	}

	@Test
	fun `pressing load twice does not run twice`() = runBlocking {
		val category = repo.createCategory("Test")
		repeat(6) { repo.setFavourite(manga(it + 1L, "T$it"), category, true) }

		val calls = AtomicInteger(0)
		val refresher = ChapterCountRefresher(
			db = db,
			fetcher = ChapterCountFetcher { _, _ ->
				calls.incrementAndGet()
				delay(40)
				10
			},
			scope = scope,
		)

		refresher.start(categoryId = null)
		refresher.start(categoryId = null)
		refresher.start(categoryId = null)

		withTimeout(20_000) {
			refresher.progress.first { it != null && !it.running }
		}
		// Six titles, fetched once each. A second run would have doubled the requests
		// every source sees for nothing.
		assertEquals(6, calls.get())
	}

	@Test
	fun `a run leaves no title counted twice and stores every success`() = runBlocking {
		val category = repo.createCategory("Test")
		repeat(12) { repo.setFavourite(manga(it + 1L, "T$it"), category, true) }

		val asked = ConcurrentLinkedQueue<Long>()
		val refresher = ChapterCountRefresher(
			db = db,
			fetcher = ChapterCountFetcher { _, m -> asked += m.id; (m.id * 10).toInt() },
			scope = scope,
		)
		val result = refresher.run(categoryId = null)

		assertEquals(12, asked.size)
		assertEquals(12, asked.toSet().size, "a title was fetched more than once")
		assertEquals(0, result.failed)
		val stored = repo.observeFavourites(null).first().associate { it.manga.id to it.chaptersCount }
		assertEquals((1L..12L).associateWith { (it * 10).toInt() }, stored)
	}

	@Test
	fun `no more than two requests hit the same source at once`() = runBlocking {
		val category = repo.createCategory("Test")
		// Lopsided the way a real shelf is: almost everything on one site.
		val sources = List(30) { MangaParserSource.MANGADEX } + List(4) { MangaParserSource.MANGAPARK }
		sources.forEachIndexed { index, source ->
			repo.setFavourite(manga(index + 1L, "T$index", source), category, true)
		}

		val inFlight = ConcurrentHashMap<String, AtomicInteger>()
		val peak = ConcurrentHashMap<String, AtomicInteger>()
		val refresher = ChapterCountRefresher(
			db = db,
			fetcher = ChapterCountFetcher { source, _ ->
				val now = inFlight.computeIfAbsent(source.name) { AtomicInteger() }.incrementAndGet()
				peak.computeIfAbsent(source.name) { AtomicInteger() }.updateAndGet { maxOf(it, now) }
				try {
					delay(25)
					7
				} finally {
					inFlight[source.name]!!.decrementAndGet()
				}
			},
			scope = scope,
		)
		val result = refresher.run(categoryId = null)

		assertEquals(34, result.done)
		assertEquals(0, result.failed)
		peak.forEach { (source, seen) ->
			assertTrue(
				seen.get() <= ChapterCountRefresher.DEFAULT_PER_SOURCE_CONCURRENCY,
				"$source saw ${seen.get()} at once",
			)
		}
		// The busy source must not have starved the quiet one out of the run.
		assertEquals(setOf("MANGADEX", "MANGAPARK"), peak.keys.toSet())
	}

	private fun manga(
		id: Long,
		title: String,
		source: MangaParserSource = MangaParserSource.MANGADEX,
	): Manga = Manga(
		id = id,
		title = title,
		altTitles = emptySet(),
		url = "/manga/$id",
		publicUrl = "https://example.test/manga/$id",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		source = source,
	)
}
