package org.koitharu.kotatsu.desktop.feature.migration

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Finding another source for a title whose own source works but has nothing to read.
 *
 * The migrate area was built around a library scan, which can only see two problems: a
 * source with no parser, and one the catalogue flags broken. A source that answers
 * normally and returns an empty chapter list passes both tests and is the case a reader
 * actually hits, so it reaches the same screen by a different door.
 */
class AlternativeForWorkingSourceTest {

	private val healthy = MangaParserSource.MANGADEX

	@Test
	fun `a title on a working source is not something the library scan would offer to fix`() {
		val entry = entryFor(healthy)
		// This is the whole point: nothing about it looks broken.
		assertTrue(!entry.needsFixing)
		assertEquals(EntryHealth.OK, entry.health)
		assertEquals("working", entry.reason())
	}

	// The search has to run for it anyway, which it would not if it were gated on health.
	@Test
	fun `alternatives are still searched for, across every other usable source`() {
		val engine = AlternativesEngine({ _, _ -> emptyList() }, { it })
		val candidates = engine.candidateSources(
			currentSourceName = healthy.name,
			isNovel = false,
		)
		assertTrue(candidates.isNotEmpty())
		assertTrue(candidates.none { it.name == healthy.name }, "it offered the source it came from")
		assertTrue(candidates.none { it.isBroken }, "it offered a source the catalogue flags broken")
	}

	@Test
	fun `a source with chapters outranks one without, whatever the title match`() = runBlocking {
		val wanted = manga(1L, "Solo Leveling", healthy)
		val engine = AlternativesEngine(
			searcher = { source, _ ->
				listOf(
					when (source) {
						MangaParserSource.MANGAPARK -> manga(2L, "Solo Leveling", source, chapters = 120)
						else -> manga(3L, "Solo Leveling", source, chapters = 0)
					},
				)
			},
			details = { it },
			concurrency = 2,
		)
		val found = engine.alternatives(
			seed = wanted,
			sources = listOf(MangaParserSource.MANGAPARK, MangaParserSource.MANGADEX),
		).toList().sortedWith(alternativeOrder)

		// An exact-title match with nothing to read is not an answer to "this has no
		// chapters", so it must not be the one offered first.
		assertEquals(120, found.first().chaptersCount)
	}

	private fun entryFor(source: MangaParserSource) = LibraryEntry(
		manga = manga(1L, "Solo Leveling", source),
		sourceName = source.name,
		source = source,
		health = EntryHealth.OK,
		isFavourite = false,
		hasHistory = false,
	)

	private fun manga(
		id: Long,
		title: String,
		source: MangaParserSource,
		chapters: Int = 0,
	) = Manga(
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
		chapters = if (chapters == 0) {
			null
		} else {
			List(chapters) { index ->
				MangaChapter(
					id = id * 1000 + index,
					title = "Chapter ${index + 1}",
					number = (index + 1).toFloat(),
					volume = 0,
					url = "/chapter/$id/$index",
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			}
		},
		source = source,
	)
}
