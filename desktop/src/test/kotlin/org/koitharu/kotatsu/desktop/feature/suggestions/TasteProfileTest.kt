package org.koitharu.kotatsu.desktop.feature.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile and the scorer, exercised directly.
 *
 * Nothing here touches a database, a file or a network, which is the point of keeping
 * both of them pure functions over plain records: the ranking rules can be stated as
 * assertions instead of inferred from what a refresh happened to return.
 */
class TasteProfileTest {

	private fun signal(
		id: Long,
		tags: Set<String> = setOf("Action"),
		favourite: Boolean = false,
		read: Float? = null,
		source: String = "MANGADEX",
	) = TasteSignal(
		mangaId = id,
		title = "Title $id",
		sourceName = source,
		tags = tags,
		contentType = "MANGA",
		isFavourite = favourite,
		readFraction = read,
	)

	@Test
	fun `a title read to ninety percent weighs more than one opened once`() {
		val nearlyFinished = signalWeight(signal(1L, read = 0.9f))
		val barelyOpened = signalWeight(signal(2L, read = 0.01f))
		assertTrue(
			"read=0.9 weighed $nearlyFinished, read=0.01 weighed $barelyOpened",
			nearlyFinished > barelyOpened,
		)
	}

	@Test
	fun `an untouched favourite counts, but less than a title that was opened`() {
		val favourite = signalWeight(signal(1L, favourite = true, read = null))
		val barelyOpened = signalWeight(signal(2L, read = 0.01f))
		assertTrue("an untouched favourite must count at all, got $favourite", favourite > 0f)
		assertTrue(
			"favourite weighed $favourite, opened-once weighed $barelyOpened",
			favourite < barelyOpened,
		)
	}

	@Test
	fun `a favourite that was also read outweighs either on its own`() {
		val both = signalWeight(signal(1L, favourite = true, read = 0.5f))
		val readOnly = signalWeight(signal(2L, read = 0.5f))
		val favouriteOnly = signalWeight(signal(3L, favourite = true))
		assertTrue(both > readOnly)
		assertTrue(readOnly > favouriteOnly)
	}

	@Test
	fun `the strongest tag is scaled to one and a weaker one sits below it`() {
		val profile = buildProfile(
			listOf(
				signal(1L, tags = setOf("Action"), read = 1f),
				signal(2L, tags = setOf("Action"), read = 1f),
				signal(3L, tags = setOf("Cooking"), favourite = true),
			),
		)
		assertEquals(1f, profile.affinityFor("Action"), 0.0001f)
		assertTrue(profile.affinityFor("Cooking") < profile.affinityFor("Action"))
		assertTrue(profile.affinityFor("Cooking") > 0f)
	}

	@Test
	fun `tag spellings that differ only in case or hyphens are one tag`() {
		val profile = buildProfile(
			listOf(
				signal(1L, tags = setOf("Slice of Life"), read = 1f),
				signal(2L, tags = setOf("slice-of-life"), read = 1f),
			),
		)
		assertEquals("one tag expected, got ${profile.tags.keys}", 1, profile.tags.size)
		assertEquals(2, profile.tags.values.single().titles.size)
	}

	@Test
	fun `an empty library produces an empty profile rather than throwing`() {
		val profile = buildProfile(emptyList())
		assertTrue(profile.isEmpty)
		assertTrue(profile.tags.isEmpty())
		assertEquals(0, profile.sampleSize)
	}

	@Test
	fun `a library whose titles have no tags at all still builds`() {
		val profile = buildProfile(listOf(signal(1L, tags = emptySet(), read = 1f)))
		assertTrue(profile.tags.isEmpty())
		assertTrue("the source is still a signal", profile.sourceAffinity("MANGADEX") > 0f)
	}

	// -- scoring ------------------------------------------------------------------

	private val profile = buildProfile(
		listOf(
			signal(1L, tags = setOf("Action", "Fantasy", "Adventure"), read = 0.9f),
			signal(2L, tags = setOf("Action", "Fantasy"), read = 0.8f),
			signal(3L, tags = setOf("Action", "Comedy"), favourite = true),
			signal(4L, tags = setOf("Action"), read = 0.3f),
		),
	)

	private fun candidate(
		id: Long,
		tags: List<String>,
		rating: Float = 0f,
		source: String = "MANGADEX",
		title: String = "Candidate $id",
	) = Candidate(
		mangaId = id,
		title = title,
		sourceName = source,
		sourceTitle = "MangaDex",
		tags = tags,
		contentType = "MANGA",
		rating = rating,
	)

	@Test
	fun `a candidate sharing three tags outranks one sharing a single tag`() {
		val three = score(profile, candidate(100L, listOf("Action", "Fantasy", "Adventure")))
			as Verdict.Suggest
		val one = score(profile, candidate(101L, listOf("Adventure"))) as Verdict.Suggest
		assertEquals(3, three.sharedTags.size)
		assertEquals(1, one.sharedTags.size)
		assertTrue(
			"three-tag scored ${three.relevance}, one-tag scored ${one.relevance}",
			three.relevance > one.relevance,
		)
	}

	@Test
	fun `three shared tags beat one even when the single-tag candidate is perfectly rated`() {
		// The rating term must never be able to overturn a subject match, which is the
		// whole reason the weights are lopsided.
		val three = score(profile, candidate(100L, listOf("Action", "Fantasy", "Adventure"), rating = 0f))
		val one = score(profile, candidate(101L, listOf("Comedy"), rating = 1f))
		assertTrue(
			(three as Verdict.Suggest).relevance > (one as Verdict.Suggest).relevance,
		)
	}

	@Test
	fun `a candidate already in the library is excluded, not merely ranked low`() {
		val verdict = score(profile, candidate(1L, listOf("Action", "Fantasy", "Adventure")))
		assertEquals(Verdict.Exclude(ExclusionCause.ALREADY_KNOWN), verdict)
	}

	@Test
	fun `a dismissed candidate is excluded with its own cause`() {
		val verdict = score(profile, candidate(100L, listOf("Action")), dismissed = setOf(100L))
		assertEquals(Verdict.Exclude(ExclusionCause.DISMISSED), verdict)
	}

	@Test
	fun `a candidate from a hidden source is excluded even when it matches perfectly`() {
		val verdict = score(
			profile = profile,
			candidate = candidate(100L, listOf("Action", "Fantasy", "Adventure"), source = "HIDDEN"),
			visibleSources = setOf("MANGADEX"),
		)
		assertEquals(Verdict.Exclude(ExclusionCause.HIDDEN_SOURCE), verdict)
	}

	@Test
	fun `no shared tags and an unknown source means no connection, not a weak score`() {
		val verdict = score(profile, candidate(100L, listOf("Mecha"), source = "SOMEWHERE_ELSE"))
		assertEquals(Verdict.Exclude(ExclusionCause.NO_CONNECTION), verdict)
	}

	@Test
	fun `scoring against an empty profile excludes rather than inventing a number`() {
		val verdict = score(TasteProfile.EMPTY, candidate(100L, listOf("Action")))
		assertEquals(Verdict.Exclude(ExclusionCause.NO_PROFILE), verdict)
	}

	@Test
	fun `the reason names the tags that actually matched and the real title count`() {
		// Action is on titles 1, 2, 3 and 4; Comedy is on 3. The union is four titles,
		// and both tags must be named because both actually matched.
		val verdict = score(profile, candidate(100L, listOf("Action", "Comedy", "Mecha")))
			as Verdict.Suggest
		assertEquals(listOf("Action", "Comedy"), verdict.sharedTags)
		assertEquals(4, verdict.matchedTitles)
		assertTrue(verdict.reason, verdict.reason.contains("Action"))
		assertTrue(verdict.reason, verdict.reason.contains("Comedy"))
		assertTrue("must not name a tag that did not match", !verdict.reason.contains("Mecha"))
		assertTrue(verdict.reason, verdict.reason.contains("4 titles you read"))
	}

	@Test
	fun `a reason for a single matched title is not pluralised`() {
		val narrow = buildProfile(listOf(signal(1L, tags = setOf("Cooking"), read = 1f)))
		val verdict = score(narrow, candidate(100L, listOf("Cooking"))) as Verdict.Suggest
		assertEquals(1, verdict.matchedTitles)
		assertTrue(verdict.reason, verdict.reason.contains("1 title you read"))
	}

	@Test
	fun `a reason lists at most three tags and counts the rest`() {
		val wide = buildProfile(
			listOf(signal(1L, tags = setOf("A", "B", "C", "D", "E"), read = 1f)),
		)
		val verdict = score(wide, candidate(100L, listOf("A", "B", "C", "D", "E")))
			as Verdict.Suggest
		assertEquals(5, verdict.sharedTags.size)
		assertTrue(verdict.reason, verdict.reason.contains("and 2 more"))
	}

	@Test
	fun `ranking drops the excluded and orders the rest by relevance`() {
		val ranked = rank(
			profile = profile,
			candidates = listOf(
				candidate(1L, listOf("Action", "Fantasy")),
				candidate(100L, listOf("Action")),
				candidate(101L, listOf("Action", "Fantasy", "Adventure")),
			),
		)
		assertEquals(listOf(101L, 100L), ranked.map { it.candidate.mangaId })
	}

	@Test
	fun `ranking is stable for candidates that score identically`() {
		val candidates = listOf(
			candidate(100L, listOf("Action"), title = "Bravo"),
			candidate(101L, listOf("Action"), title = "Alpha"),
		)
		val first = rank(profile, candidates).map { it.candidate.mangaId }
		val second = rank(profile, candidates.reversed()).map { it.candidate.mangaId }
		assertEquals(first, second)
		assertEquals("ties break on title", listOf(101L, 100L), first)
	}

	@Test
	fun `ranking against an empty profile yields nothing at all`() {
		val ranked = rank(TasteProfile.EMPTY, listOf(candidate(100L, listOf("Action"))))
		assertTrue(ranked.isEmpty())
	}
}
