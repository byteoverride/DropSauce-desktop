package org.koitharu.kotatsu.desktop.feature.curate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Duplicate detection, in both directions.
 *
 * The false-positive cases matter more than the positives here. A missed duplicate costs
 * the user a second look at their library; a wrong group offers a merge button that
 * deletes rows belonging to a different work.
 */
class DuplicatesTest {

	@Test
	fun `the same title from two sources is one group`() {
		val groups = Duplicates.find(
			listOf(
				testItem(1L, "One Piece", MangaParserSource.MANGADEX),
				testItem(2L, "One Piece", MangaParserSource.MANGAKAKALOT),
				testItem(3L, "Berserk", MangaParserSource.MANGADEX),
			),
		)
		assertEquals(1, groups.size)
		assertEquals(setOf(1L, 2L), groups.single().items.map { it.id }.toSet())
		assertTrue(groups.single().crossSource)
	}

	@Test
	fun `case and whitespace differences group together`() {
		val groups = Duplicates.find(
			listOf(
				testItem(1L, "Attack on Titan"),
				testItem(2L, "attack   on titan"),
				testItem(3L, "  ATTACK ON TITAN  "),
			),
		)
		assertEquals(1, groups.size)
		assertEquals(3, groups.single().size)
		assertEquals("attack on titan", groups.single().key)
	}

	@Test
	fun `punctuation differences group together`() {
		val groups = Duplicates.find(
			listOf(
				testItem(1L, "Re:Zero - Starting Life in Another World"),
				testItem(2L, "Re Zero, Starting Life in Another World"),
			),
		)
		assertEquals(1, groups.size)
	}

	@Test
	fun `a near-identical name groups`() {
		// One character apart in a long title: a source's typo, not a different work.
		val groups = Duplicates.find(
			listOf(
				testItem(1L, "The Rising of the Shield Hero"),
				testItem(2L, "The Rising of the Shield Heroo"),
			),
		)
		assertEquals(1, groups.size)
		assertEquals(2, groups.single().size)
	}

	@Test
	fun `two different titles sharing a common word are not grouped`() {
		// The whole reason detection is edit distance and not word overlap. Every pair
		// here shares a word, and none of them is the same work.
		val items = listOf(
			testItem(1L, "Dragon Ball"),
			testItem(2L, "Dragon Quest"),
			testItem(3L, "Attack on Titan"),
			testItem(4L, "Attack on Avatar"),
			testItem(5L, "One Piece"),
			testItem(6L, "One Punch Man"),
			testItem(7L, "Tokyo Ghoul"),
			testItem(8L, "Tokyo Revengers"),
		)
		assertEquals(emptyList<DuplicateGroup>(), Duplicates.find(items))
	}

	@Test
	fun `a sequel is not a duplicate of the work it follows`() {
		// Digits survive normalisation precisely so this stays two entries.
		assertTrue(Duplicates.find(listOf(testItem(1L, "Gantz"), testItem(2L, "Gantz 2"))).isEmpty())
		assertFalse(Duplicates.isNearIdentical("bleach", "bleach 2"))
	}

	@Test
	fun `a single entry is never a group`() {
		assertTrue(Duplicates.find(listOf(testItem(1L, "Berserk"))).isEmpty())
		assertTrue(Duplicates.find(emptyList()).isEmpty())
	}

	@Test
	fun `titles made only of punctuation are not lumped together`() {
		// They all normalise to the empty string, which is evidence of nothing.
		assertTrue(Duplicates.find(listOf(testItem(1L, "!!!"), testItem(2L, "???"))).isEmpty())
	}

	@Test
	fun `the survivor suggestion prefers the furthest read`() {
		val group = Duplicates.find(
			listOf(
				testItem(1L, "Vinland Saga", chaptersCount = 200, progress = 0.1f, lastReadAt = 5L),
				testItem(2L, "vinland saga", chaptersCount = 50, progress = 0.8f, lastReadAt = 9L),
			),
		).single()
		assertEquals(2L, group.suggestedSurvivorId)
	}

	@Test
	fun `with equal progress the longest entry survives, then the oldest`() {
		val byLength = Duplicates.find(
			listOf(
				testItem(1L, "Naruto", chaptersCount = 100, addedAt = 10L),
				testItem(2L, "naruto", chaptersCount = 700, addedAt = 20L),
			),
		).single()
		assertEquals(2L, byLength.suggestedSurvivorId)

		val byAge = Duplicates.find(
			listOf(
				testItem(3L, "Naruto", chaptersCount = 700, addedAt = 90L),
				testItem(4L, "naruto", chaptersCount = 700, addedAt = 20L),
			),
		).single()
		assertEquals(4L, byAge.suggestedSurvivorId)
	}

	@Test
	fun `normalisation keeps letters and digits and collapses the rest`() {
		assertEquals("one piece", Duplicates.normalize("  One   Piece!  "))
		assertEquals("jojo s bizarre adventure", Duplicates.normalize("JoJo's Bizarre Adventure"))
		assertEquals("", Duplicates.normalize("---"))
	}

	@Test
	fun `edit distance gives up once it passes the budget`() {
		// The early exit must not change the answer for anything inside the budget.
		assertEquals(0, Duplicates.distance("same", "same"))
		assertEquals(1, Duplicates.distance("same", "sane"))
		assertEquals(4, Duplicates.distance("", "four"))
		assertTrue(Duplicates.distance("completely different", "nothing alike", budget = 2) > 2)
	}

	@Test
	fun `short titles need an exact match`() {
		// At a 0.9 similarity threshold a five-character title has no edit budget at all,
		// which is deliberate: one character is a third of the evidence.
		assertFalse(Duplicates.isNearIdentical("gantz", "gants"))
		assertTrue(Duplicates.isNearIdentical("gantz", "gantz"))
	}

	@Test
	fun `groups are transitive through a middle spelling`() {
		val groups = Duplicates.find(
			listOf(
				testItem(1L, "Kingdom of the Gods"),
				testItem(2L, "Kingdom of the God"),
				testItem(3L, "Kingdom of the Gads"),
			),
		)
		assertEquals(1, groups.size)
		assertEquals(3, groups.single().size)
	}
}
