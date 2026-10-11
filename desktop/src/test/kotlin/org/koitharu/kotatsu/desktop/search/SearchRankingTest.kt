package org.koitharu.kotatsu.desktop.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.util.levenshteinDistance

/**
 * The ranking that stands between a loose source and the results grid.
 *
 * The lists in here are not invented. They are what the sources in this machine's
 * `discover.json` actually returned for a query of "Berserk", captured by
 * `SearchQualityLiveTest`, so what these assert is the real complaint: a search returning
 * titles that have nothing to do with the query.
 */
class SearchRankingTest {

	/** ATSUMARU's real first eight rows, in the order it sent them, for query "Berserk". */
	private val atsumaru = listOf(
		"BERSERK",
		"Berserk of Gluttony",
		"Tantei Akechi wa Kyouransu",
		"I Was Possessed by the Mad Lady's Body That I Serve!",
		"Peerless Battle Spirit",
		"The Berserker's Second Playthrough",
		"I Became the Berserker of the World I Created",
		"Black Summoner",
	).mapIndexed { i, title -> manga(i.toLong(), title) }

	@Test
	fun `the exact title leads and the unrelated rows are not shown at all`() {
		val ranked = rankForQuery("Berserk", atsumaru)

		assertEquals("BERSERK", ranked.shown.first().title)
		assertEquals(
			listOf(
				"Tantei Akechi wa Kyouransu",
				"I Was Possessed by the Mad Lady's Body That I Serve!",
				"Peerless Battle Spirit",
				"Black Summoner",
			),
			ranked.hidden.map { it.title },
		)
	}

	@Test
	fun `nothing is lost, only moved`() {
		val ranked = rankForQuery("Berserk", atsumaru)

		assertEquals(atsumaru.size, ranked.all.size)
		assertEquals(atsumaru.map { it.title }.toSet(), ranked.all.map { it.title }.toSet())
	}

	/**
	 * The positive control for the choice of algorithm.
	 *
	 * This is the sort the Android app's `SearchV2Helper` does, run on the same real
	 * answer. It puts a title sharing no word with the query in the top three, which is
	 * why edit distance is not what ships here. If this assert ever fails, the premise
	 * behind [rankForQuery] has changed and the comparison should be redone, not deleted.
	 */
	@Test
	fun `sorting by edit distance, as the Android app does, promotes an unrelated title`() {
		val query = "Berserk"
		val byDistance = (atsumaru + manga(99, "SPRIGGAN")).sortedBy { m ->
			minOf(
				m.title.levenshteinDistance(query),
				m.altTitles.firstOrNull()?.levenshteinDistance(query) ?: Int.MAX_VALUE,
			)
		}

		assertEquals("SPRIGGAN", byDistance[1].title)
		// And the ranking that ships does not.
		val ranked = rankForQuery(query, atsumaru + manga(99, "SPRIGGAN"))
		assertTrue(
			"SPRIGGAN shares no word with the query and must not be shown",
			ranked.hidden.any { it.title == "SPRIGGAN" },
		)
	}

	@Test
	fun `an exact match beats the query as a prefix, which beats the query in the middle`() {
		val items = listOf(
			manga(1, "Tensei Shitara Berserk Datta Ken"),
			manga(2, "Berserk of Gluttony"),
			manga(3, "Berserk"),
		)

		assertEquals(
			listOf("Berserk", "Berserk of Gluttony", "Tensei Shitara Berserk Datta Ken"),
			rankForQuery("berserk", items).shown.map { it.title },
		)
	}

	@Test
	fun `more of the query accounted for ranks higher, and a stem counts for part of a word`() {
		val items = listOf(
			manga(1, "Solo Max-Level Newbie"),
			manga(2, "Leveling Up With the Gods"),
			manga(3, "Solo Levelers Guild"),
			manga(4, "Solo Leveling"),
		)

		val shown = rankForQuery("solo leveling", items).shown.map { it.title }
		assertEquals("Solo Leveling", shown.first())
		assertTrue(
			"a row answering both words, even one of them by its stem, beats a row answering one",
			maxOf(shown.indexOf("Solo Levelers Guild"), shown.indexOf("Solo Max-Level Newbie")) <
				shown.indexOf("Leveling Up With the Gods"),
		)
	}

	@Test
	fun `a stem earns less than the whole word, so the outright match ranks first`() {
		val items = listOf(
			manga(1, "Berserkers of Gluttony"),
			manga(2, "Berserk of Gluttony"),
		)

		assertEquals(
			listOf("Berserk of Gluttony", "Berserkers of Gluttony"),
			rankForQuery("berserk gluttony", items).shown.map { it.title },
		)
	}

	@Test
	fun `two letters in common are not a stem`() {
		val items = listOf(manga(1, "Italy Travelogue"))

		assertEquals(0, rankForQuery("it works", items).shown.size)
	}

	@Test
	fun `an inflected word still counts, below the titles that match outright`() {
		val items = listOf(
			manga(1, "Tensei Berserker"),
			manga(2, "Berserk"),
		)

		assertEquals(
			listOf("Berserk", "Tensei Berserker"),
			rankForQuery("berserk", items).shown.map { it.title },
		)
	}

	@Test
	fun `a title with no spaces is matched on the substring, which is the only thing there is`() {
		// Normalisation leaves CJK as one token, so every word tier reads it as a single
		// mismatched word. Without the substring tier this row would be hidden.
		val items = listOf(manga(1, "ベルセルク"))

		assertEquals(1, rankForQuery("ベルセルク", items).shown.size)
	}

	@Test
	fun `an alternative title is as good a hit as the main one`() {
		val items = listOf(
			manga(1, "Unrelated Thing"),
			manga(2, "Shingeki no Kyojin", altTitles = setOf("Attack on Titan")),
		)

		val ranked = rankForQuery("attack on titan", items)
		assertEquals("Shingeki no Kyojin", ranked.shown.single().title)
		assertEquals(listOf("Unrelated Thing"), ranked.hidden.map { it.title })
	}

	@Test
	fun `a title the query swallows is still a strong hit`() {
		val items = listOf(manga(1, "Berserk"), manga(2, "Vinland Saga"))

		val ranked = rankForQuery("berserk deluxe edition", items)
		assertEquals("Berserk", ranked.shown.single().title)
	}

	@Test
	fun `a blank query is a browse and keeps the source's order untouched`() {
		val ranked = rankForQuery("   ", atsumaru)

		assertEquals(atsumaru.map { it.title }, ranked.shown.map { it.title })
		assertEquals(0, ranked.hiddenCount)
	}

	@Test
	fun `equal scores keep the order the source sent them in`() {
		val items = listOf(
			manga(1, "Berserk Gaiden"),
			manga(2, "Berserk Sacrifice"),
			manga(3, "Berserk Toxic"),
		)

		assertEquals(
			listOf("Berserk Gaiden", "Berserk Sacrifice", "Berserk Toxic"),
			rankForQuery("berserk", items).shown.map { it.title },
		)
	}

	@Test
	fun `a one-character query is too little to rank on, so the source's order stands`() {
		// "a" is a prefix of half the catalogue, so every tier would fire on rows that
		// are not hits. Hiding them would be worse than not ranking at all.
		val items = listOf(manga(1, "A Returner's Magic Should Be Special"), manga(2, "Berserk"))

		val ranked = rankForQuery("a", items)
		assertEquals(items.map { it.title }, ranked.shown.map { it.title })
		assertEquals(0, ranked.hiddenCount)
	}

	@Test
	fun `a stray number in the query does not score against every title holding one`() {
		val items = listOf(manga(1, "Berserk"), manga(2, "Chapter 1 Collection"))

		val ranked = rankForQuery("berserk 1", items)
		assertEquals("Berserk", ranked.shown.single().title)
		assertEquals(listOf("Chapter 1 Collection"), ranked.hidden.map { it.title })
	}

	@Test
	fun `punctuation and case are not part of the comparison`() {
		val items = listOf(manga(1, "Re:Zero - Starting Life in Another World"))

		assertEquals(1, rankForQuery("re zero", items).shown.size)
		assertEquals(1, rankForQuery("RE:ZERO", items).shown.size)
	}

	private fun manga(id: Long, title: String, altTitles: Set<String> = emptySet()) = Manga(
		id = id,
		title = title,
		altTitles = altTitles,
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
		source = MangaParserSource.MANGADEX,
	)
}
