package org.koitharu.kotatsu.desktop.feature.migration

import kotlinx.coroutines.runBlocking
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A title whose own source will not serve it at all.
 *
 * The screen has only the seed in this state: `getDetails` is what failed, so there is no
 * description, no chapter list, and nothing but the title to search other sources with.
 * These assert that is enough, because if it is not then the way out offered on the error
 * screen does not work and nothing else would notice.
 */
class UnavailableTitleTest {

	private val dead = MangaParserSource.MANGADEX

	@Test
	fun `a seed carries what an alternatives search needs`() {
		val seed = seed()
		// No details were ever loaded. This is the whole state the error screen has.
		assertTrue(seed.chapters == null)
		assertTrue(seed.description == null)
		assertTrue(seed.title.isNotBlank(), "without a title there is nothing to search for")
	}

	@Test
	fun `an unloadable title still produces candidate sources to try`() {
		val engine = AlternativesEngine({ _, _ -> emptyList() }, { it })
		val candidates = engine.candidateSources(seed())
		assertTrue(candidates.isNotEmpty())
		assertTrue(candidates.none { it.name == dead.name })
	}

	// A source that throws must be counted and stepped over, not allowed to end the
	// search. The reader arrived here because a source was failing; more of them failing
	// is the expected weather, not an exceptional case.
	@Test
	fun `sources that answer with an error do not stop the ones that work`() = runBlocking {
		val failures = mutableListOf<AlternativeFailure>()
		val engine = AlternativesEngine(
			searcher = { source, _ ->
				when (source) {
					MangaParserSource.MANGAPARK -> listOf(alternative(source))
					else -> throw java.io.IOException("HTTP 502")
				}
			},
			details = { it },
			concurrency = 2,
		)
		val found = engine.alternatives(
			seed = seed(),
			sources = listOf(MangaParserSource.MANGAPARK, MangaParserSource.BATOTO, MangaParserSource.MANGAREADERTO),
			onFailure = { failures += it },
		).let { flow ->
			val collected = mutableListOf<Alternative>()
			flow.collect { collected += it }
			collected
		}

		assertEquals(1, found.size, "the working source was lost when the others failed")
		assertEquals(2, failures.size, "the failures were swallowed rather than reported")
		assertTrue(failures.all { it.message.isNotBlank() })
	}

	private fun seed() = Manga(
		id = 1L,
		title = "A Title Its Source Will Not Serve",
		altTitles = emptySet(),
		url = "/manga/1",
		publicUrl = "https://example.test/manga/1",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		source = dead,
	)

	private fun alternative(source: MangaParserSource) = seed().copy(
		id = 2L,
		source = source,
		chapters = listOf(
			org.koitharu.kotatsu.parsers.model.MangaChapter(
				id = 20L,
				title = "Chapter 1",
				number = 1f,
				volume = 0,
				url = "/chapter/20",
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			),
		),
	)
}
