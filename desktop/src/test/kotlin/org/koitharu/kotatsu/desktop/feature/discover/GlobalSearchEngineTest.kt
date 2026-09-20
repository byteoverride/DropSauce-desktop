package org.koitharu.kotatsu.desktop.feature.discover

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The global search is the screen this feature exists for, and the failure that matters
 * is one dead source taking the other thirty-nine with it. Every test here drives the
 * engine with plain functions, so none of it touches a network.
 */
class GlobalSearchEngineTest {

	private val source = MangaParserSource.entries.first()

	private fun manga(title: String) = Manga(
		id = title.hashCode().toLong(),
		title = title,
		altTitles = emptySet(),
		url = "/$title",
		publicUrl = "https://example.invalid/$title",
		rating = -1f,
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

	private fun target(name: String, answer: suspend (String) -> List<Manga>) =
		SearchTarget(key = name, title = name, search = answer)

	@Test
	fun `results from several sources are aggregated`() = runTest {
		val engine = GlobalSearchEngine(concurrency = 2)
		val targets = listOf(
			target("a") { listOf(manga("a1"), manga("a2")) },
			target("b") { listOf(manga("b1")) },
			target("c") { emptyList() },
		)
		val last = engine.search("q", targets).toList().last()
		assertTrue(last.isFinished)
		assertEquals(3, last.total)
		assertEquals(3, last.answered)
		assertEquals(0, last.failed)
		assertEquals(3, last.hitCount)
		assertEquals(listOf("a", "b"), last.groupsWithHits.map { it.target.key })
	}

	@Test
	fun `one source throwing loses neither the others nor their results`() = runTest {
		val engine = GlobalSearchEngine(concurrency = 2)
		val targets = listOf(
			target("good1") { listOf(manga("x")) },
			target("bad") { throw IOException("connection reset") },
			target("good2") { listOf(manga("y"), manga("z")) },
		)
		val last = engine.search("q", targets).toList().last()
		assertTrue(last.isFinished)
		assertEquals(2, last.answered)
		assertEquals(1, last.failed)
		assertEquals(3, last.hitCount)
		assertEquals(
			SourceOutcome.Failed("connection reset"),
			last.results.first { it.target.key == "bad" }.outcome,
		)
		assertEquals(listOf("good1", "good2"), last.groupsWithHits.map { it.target.key })
	}

	@Test
	fun `every source failing is reported as a failure, not as an empty result`() = runTest {
		val engine = GlobalSearchEngine(concurrency = 3)
		val targets = List(3) { i -> target("s$i") { throw IOException("down") } }
		val last = engine.search("q", targets).toList().last()
		assertTrue(last.isTotalFailure)
		assertFalse(last.isEmptyResult)
	}

	@Test
	fun `every source answering nothing is an empty result, not a failure`() = runTest {
		val engine = GlobalSearchEngine(concurrency = 3)
		val targets = List(3) { i -> target("s$i") { emptyList() } }
		val last = engine.search("q", targets).toList().last()
		assertTrue(last.isEmptyResult)
		assertFalse(last.isTotalFailure)
		assertEquals(0, last.hitCount)
		assertEquals(3, last.answered)
	}

	@Test
	fun `a failure with no message still says something`() = runTest {
		val engine = GlobalSearchEngine(concurrency = 1)
		val last = engine.search("q", listOf(target("s") { throw IOException() })).toList().last()
		assertEquals(SourceOutcome.Failed("IOException"), last.results.single().outcome)
	}

	@Test
	fun `no more than the configured number of sources run at once`() = runTest {
		val concurrency = 6
		val live = AtomicInteger()
		val peak = AtomicInteger()
		val engine = GlobalSearchEngine(concurrency = concurrency)
		val targets = List(20) { i ->
			target("s$i") {
				val now = live.incrementAndGet()
				peak.updateAndGet { maxOf(it, now) }
				delay(5)
				live.decrementAndGet()
				emptyList()
			}
		}
		val last = engine.search("q", targets).toList().last()
		assertTrue(last.isFinished)
		assertTrue("peak concurrency was ${peak.get()}", peak.get() <= concurrency)
		// Proves the cap is a cap and not accidental serialisation.
		assertEquals(concurrency, peak.get())
	}

	@Test
	fun `results stream in rather than arriving all at once`() = runTest {
		val slow = CompletableDeferred<Unit>()
		val engine = GlobalSearchEngine(concurrency = 2)
		val targets = listOf(
			target("fast") { listOf(manga("f")) },
			target("slow") {
				slow.await()
				listOf(manga("s"))
			},
		)
		val states = async { engine.search("q", targets).toList() }
		slow.complete(Unit)
		val all = states.await()
		// The state carrying the fast source's hits exists before the slow source has
		// answered, which is what "stream in" means; a batched engine would only ever
		// produce the finished state.
		val partial = all.first { state -> state.results.any { it.target.key == "fast" && it.items.isNotEmpty() } }
		assertFalse(partial.isFinished)
		assertTrue(all.last().isFinished)
	}

	@Test
	fun `an empty source list finishes immediately`() = runTest {
		val states = GlobalSearchEngine().search("q", emptyList()).toList()
		assertEquals(1, states.size)
		assertTrue(states.single().isFinished)
		assertTrue(states.single().results.isEmpty())
		// Nothing was searched, so this is neither an empty result nor a failure.
		assertFalse(states.single().isEmptyResult)
		assertFalse(states.single().isTotalFailure)
	}

	@Test
	fun `progress runs from nothing to everything`() = runTest {
		val engine = GlobalSearchEngine(concurrency = 1)
		val targets = List(4) { i -> target("s$i") { emptyList() } }
		val states = engine.search("q", targets).toList()
		assertEquals(0f, states.first().progress(), 0f)
		assertEquals(1f, states.last().progress(), 0f)
		// Monotonic: a progress bar that goes backwards is worse than none.
		val progress = states.map { it.progress() }
		assertEquals(progress.sorted(), progress)
	}

	@Test
	fun `an event for a source that is not in the list is ignored`() {
		val state = initialState("q", listOf(target("a") { emptyList() }))
		assertEquals(state, reduce(state, SearchEvent.Failed("nope", "x")))
	}
}
