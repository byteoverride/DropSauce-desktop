package org.koitharu.kotatsu.desktop.feature.suggestions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A whole refresh, with fake sources and no network.
 *
 * The fetch is a function the engine is handed, which is what makes this possible: a
 * source that throws, a source that hangs and a source that answers are three lines of
 * test data rather than three unreachable websites.
 */
class SuggestionEngineTest {

	private val profile = buildProfile(
		listOf(
			TasteSignal(
				mangaId = 1L,
				title = "Read",
				sourceName = "ALPHA",
				tags = setOf("Action", "Fantasy"),
				contentType = "MANGA",
				isFavourite = false,
				readFraction = 0.9f,
			),
			TasteSignal(
				mangaId = 2L,
				title = "Shelved",
				sourceName = "ALPHA",
				tags = setOf("Action"),
				contentType = "MANGA",
				isFavourite = true,
				readFraction = null,
			),
		),
	)

	private fun candidate(id: Long, tags: List<String> = listOf("Action"), source: String = "ALPHA") =
		Candidate(
			mangaId = id,
			title = "Candidate $id",
			sourceName = source,
			sourceTitle = source.lowercase().replaceFirstChar { it.uppercase() },
			tags = tags,
			contentType = "MANGA",
			rating = 0f,
		)

	private fun answering(key: String, vararg items: Candidate) =
		CandidateSource(key, key) { items.toList() }

	private fun failing(key: String, message: String = "boom") =
		CandidateSource(key, key) { throw IOException(message) }

	@Test
	fun `one source throwing does not lose the other sources' candidates`() = runBlocking {
		val states = SuggestionEngine(concurrency = 4).refresh(
			profile = profile,
			sources = listOf(
				answering("ALPHA", candidate(100L)),
				failing("BETA"),
				answering("GAMMA", candidate(101L, listOf("Action", "Fantasy"))),
				failing("DELTA", "timeout"),
			),
		).toList()

		val last = states.last()
		assertTrue("the run must finish even with failures", last.isFinished)
		assertEquals(2, last.failed)
		assertEquals(2, last.answered)
		assertEquals(
			"both surviving sources' candidates must be present",
			setOf(100L, 101L),
			last.suggestions.map { it.candidate.mangaId }.toSet(),
		)
		assertEquals(
			setOf("BETA", "DELTA"),
			last.failures.map { it.source.key }.toSet(),
		)
		assertTrue(
			"the failure reason must survive to the screen",
			last.failures.any { (it.outcome as FetchOutcome.Failed).message == "timeout" },
		)
	}

	@Test
	fun `every source failing is reported as such rather than as an empty catalogue`() =
		runBlocking {
			val last = SuggestionEngine().refresh(
				profile = profile,
				sources = listOf(failing("ALPHA"), failing("BETA")),
			).toList().last()
			assertTrue(last.isFinished)
			assertEquals(2, last.failed)
			assertEquals(
				"Every source failed. Check the connection and try again.",
				last.emptyExplanation(),
			)
		}

	@Test
	fun `an empty library produces an empty result with an explanation, not a crash`() =
		runBlocking {
			val asked = AtomicInteger()
			val states = SuggestionEngine().refresh(
				profile = TasteProfile.EMPTY,
				sources = listOf(
					CandidateSource("ALPHA", "Alpha") {
						asked.incrementAndGet()
						listOf(candidate(100L))
					},
				),
			).toList()

			assertEquals("one state, and no fetching at all", 1, states.size)
			val only = states.single()
			assertTrue(only.suggestions.isEmpty())
			assertEquals("no source should have been asked", 0, asked.get())
			val explanation = only.emptyExplanation()
			assertNotNull(explanation)
			assertTrue(
				explanation!!,
				explanation.contains("Add a title to your library"),
			)
		}

	@Test
	fun `no sources to ask is a different sentence from no results`() = runBlocking {
		val last = SuggestionEngine().refresh(profile, sources = emptyList()).toList().last()
		assertTrue(last.isFinished)
		assertEquals(
			"No sources to ask. Every source is hidden by your catalogue settings.",
			last.emptyExplanation(),
		)
	}

	@Test
	fun `sources that answer with nothing are not failures`() = runBlocking {
		val last = SuggestionEngine().refresh(
			profile = profile,
			sources = listOf(answering("ALPHA"), answering("BETA")),
		).toList().last()
		assertEquals(0, last.failed)
		assertEquals(2, last.answered)
		assertEquals(
			"The sources answered but had nothing to offer.",
			last.emptyExplanation(),
		)
	}

	@Test
	fun `candidates already in the library never reach the result`() = runBlocking {
		val last = SuggestionEngine().refresh(
			profile = profile,
			// 1L and 2L are the profile's own titles.
			sources = listOf(answering("ALPHA", candidate(1L), candidate(2L))),
		).toList().last()
		assertTrue(last.suggestions.isEmpty())
		assertEquals(2, last.candidatesSeen)
		assertEquals(
			"Everything the sources returned is already in your library, was dismissed, " +
				"or shares nothing with what you read.",
			last.emptyExplanation(),
		)
	}

	@Test
	fun `a dismissed candidate is kept out of a fresh run`() = runBlocking {
		val last = SuggestionEngine().refresh(
			profile = profile,
			sources = listOf(answering("ALPHA", candidate(100L), candidate(101L))),
			dismissed = setOf(100L),
		).toList().last()
		assertEquals(listOf(101L), last.suggestions.map { it.candidate.mangaId })
	}

	@Test
	fun `the same title from two sources is suggested once`() = runBlocking {
		val last = SuggestionEngine().refresh(
			profile = profile,
			sources = listOf(
				answering("ALPHA", candidate(100L)),
				answering("BETA", candidate(100L, source = "BETA")),
			),
		).toList().last()
		assertEquals(1, last.suggestions.size)
	}

	@Test
	fun `progress runs from nothing settled to everything settled`() = runBlocking {
		val states = SuggestionEngine().refresh(
			profile = profile,
			sources = listOf(answering("ALPHA", candidate(100L)), answering("BETA", candidate(101L))),
		).toList()
		assertEquals(0f, states.first().fraction(), 0.0001f)
		assertFalse(states.first().isFinished)
		assertEquals(1f, states.last().fraction(), 0.0001f)
		assertNull(states.last().emptyExplanation())
	}

	@Test
	fun `concurrency is bounded`() = runBlocking {
		val live = AtomicInteger()
		val peak = AtomicInteger()
		val gate = CompletableDeferred<Unit>()
		val sources = (1..10).map { index ->
			CandidateSource("S$index", "S$index") {
				val now = live.incrementAndGet()
				peak.updateAndGet { seen -> maxOf(seen, now) }
				// Hold every started source until the last permit-holder is counted, so
				// the peak is a real measurement and not a scheduling accident.
				if (now >= 4) gate.complete(Unit)
				gate.await()
				live.decrementAndGet()
				listOf(candidate(100L + index))
			}
		}
		val last = SuggestionEngine(concurrency = 4).refresh(profile, sources).toList().last()
		assertTrue(last.isFinished)
		assertEquals("four at a time, no more", 4, peak.get())
	}

	@Test
	fun `a source answering after a failure still contributes`() {
		// The fold directly, with no timing involved at all.
		val sources = listOf(answering("ALPHA"), answering("BETA"))
		var state = RefreshState(
			profile = profile,
			progress = sources.map { SourceProgress(it, FetchOutcome.Waiting) },
			suggestions = emptyList(),
		)
		state = reduce(state, FetchEvent.Failed("ALPHA", "gone"))
		state = reduce(state, FetchEvent.Answered("BETA", listOf(candidate(100L))))
		assertEquals(listOf(100L), state.suggestions.map { it.candidate.mangaId })
		assertEquals(1, state.failed)
		assertTrue(state.isFinished)
	}

	@Test
	fun `an event for a source that is not in the run is ignored`() {
		val state = RefreshState(
			profile = profile,
			progress = listOf(SourceProgress(answering("ALPHA"), FetchOutcome.Waiting)),
			suggestions = emptyList(),
		)
		assertEquals(state, reduce(state, FetchEvent.Answered("NOT_HERE", listOf(candidate(1L)))))
	}
}
