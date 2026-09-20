package org.koitharu.kotatsu.desktop.feature.migration

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * The search half, driven entirely through the two injected functions, so none of this
 * touches a network.
 */
class AlternativesEngineTest {

	private val catalogue: List<MangaParserSource> = MangaParserSource.entries
		.filter { !it.isBroken && it.contentType != ContentType.NOVEL }
		.take(4)

	@Test
	fun `candidate sources exclude the current one, broken ones and the other kind`() {
		val engine = AlternativesEngine({ _, _ -> emptyList() }, { it })
		val current = catalogue.first()
		val broken = MangaParserSource.entries.first { it.isBroken }
		val novel = MangaParserSource.entries.firstOrNull { it.contentType == ContentType.NOVEL }
		val given = catalogue + broken + listOfNotNull(novel)

		val chosen = engine.candidateSources(
			currentSourceName = current.name,
			isNovel = false,
			catalogue = given,
		)

		assertFalse("the current source must never be offered", chosen.contains(current))
		assertFalse("a broken source is not an improvement", chosen.contains(broken))
		if (novel != null) {
			assertFalse("a novel source cannot render a comic", chosen.contains(novel))
		}
		assertEquals(catalogue.drop(1), chosen)
	}

	@Test
	fun `a novel title is only offered novel sources`() {
		val engine = AlternativesEngine({ _, _ -> emptyList() }, { it })
		val novels = MangaParserSource.entries.filter { !it.isBroken && it.contentType == ContentType.NOVEL }
		if (novels.isEmpty()) {
			// Nothing to assert on this catalogue build; the comic direction is covered above.
			return
		}
		val chosen = engine.candidateSources(
			currentSourceName = "NOT_A_REAL_SOURCE",
			isNovel = true,
			catalogue = catalogue + novels,
		)
		assertEquals(novels, chosen)
	}

	@Test
	fun `an exact title match ranks above a partial one`() = runBlocking {
		val seed = manga(1L, "Solo Leveling")
		val exactSource = catalogue[1]
		val partialSource = catalogue[2]
		val engine = AlternativesEngine(
			searcher = { source, _ ->
				when (source) {
					exactSource -> listOf(manga(20L, "Solo Leveling", exactSource))
					partialSource -> listOf(manga(30L, "Solo Leveling Ragnarok", partialSource))
					else -> emptyList()
				}
			},
			// The partial match is given far more chapters on purpose: chapter count must
			// not be allowed to outrank being the right comic.
			details = { hit ->
				val count = if (hit.id == 20L) 3 else 300
				hit.copy(chapters = (1..count).map { chapter(hit.id * 100 + it, it.toFloat(), sourceOf(hit)) })
			},
			concurrency = 2,
		)

		val sources = engine.candidateSources(seed, catalogue)
		val found = engine.alternatives(seed, sources).toList().sortedWith(alternativeOrder)

		assertEquals(listOf(20L, 30L), found.map { it.manga.id })
		assertEquals(1f, found.first().similarity, 0.0001f)
		assertTrue(found[1].similarity < 1f)
	}

	@Test
	fun `a candidate with no chapters sinks below one that has them`() {
		val withChapters = Alternative(manga(2L, "A"), chaptersCount = 1, similarity = 0.2f)
		val withoutChapters = Alternative(manga(3L, "A"), chaptersCount = 0, similarity = 1f)
		assertEquals(
			listOf(2L, 3L),
			listOf(withoutChapters, withChapters).sortedWith(alternativeOrder).map { it.manga.id },
		)
	}

	@Test
	fun `one source failing does not stop the others`() = runBlocking {
		val seed = manga(1L, "Berserk")
		val failing = catalogue[1]
		val failures = mutableListOf<AlternativeFailure>()
		val engine = AlternativesEngine(
			searcher = { source, _ ->
				if (source == failing) throw java.io.IOException("connection reset")
				listOf(manga(source.ordinal + 100L, "Berserk", source))
			},
			details = { it.copy(chapters = listOf(chapter(1L, 1f, sourceOf(it)))) },
		)
		val sources = engine.candidateSources(seed, catalogue)

		val found = engine.alternatives(seed, sources, onFailure = { failures.add(it) }).toList()

		assertEquals(sources.size - 1, found.size)
		assertEquals(listOf(failing), failures.map { it.source })
		assertEquals("connection reset", failures.single().message)
	}

	@Test
	fun `only the best hit from a source is offered`() = runBlocking {
		val seed = manga(1L, "Naruto")
		val source = catalogue[1]
		val engine = AlternativesEngine(
			searcher = { s, _ ->
				if (s == source) {
					listOf(manga(10L, "Naruto", s), manga(11L, "Naruto", s))
				} else {
					emptyList()
				}
			},
			details = { hit ->
				val count = if (hit.id == 11L) 5 else 1
				hit.copy(chapters = (1..count).map { chapter(hit.id * 10 + it, it.toFloat(), sourceOf(hit)) })
			},
		)

		val found = engine.alternatives(seed, listOf(source)).toList()

		assertEquals(1, found.size)
		assertEquals(11L, found.single().manga.id)
		assertEquals(5, found.single().chaptersCount)
	}

	@Test
	fun `title similarity is exact, containment or overlap`() {
		assertEquals(1f, titleSimilarity("Solo Leveling", "solo  leveling!"), 0.0001f)
		assertTrue(titleSimilarity("Naruto", "Naruto Shippuden") >= 0.9f)
		assertTrue(titleSimilarity("Naruto", "Naruto Shippuden") < 1f)
		assertEquals(0f, titleSimilarity("Berserk", "Vinland Saga"), 0.0001f)
		assertEquals(0f, titleSimilarity("", "Berserk"), 0.0001f)
	}
}

/** The concrete source behind a [Manga], for building chapters that belong to it. */
internal fun sourceOf(manga: Manga): MangaParserSource =
	manga.source as? MangaParserSource ?: OLD_SOURCE
