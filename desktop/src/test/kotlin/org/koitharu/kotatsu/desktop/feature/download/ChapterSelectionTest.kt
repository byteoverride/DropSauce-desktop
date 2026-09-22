package org.koitharu.kotatsu.desktop.feature.download

import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which chapters each download option covers.
 *
 * Arithmetic over a list, which is exactly the kind of thing that looks obviously right
 * and is off by one. The cases that matter are the ones where a selection could silently
 * cover nothing, or silently cover everything.
 */
class ChapterSelectionTest {

	private val english = List(5) { chapter(it + 1L, "English") }
	private val spanish = List(3) { chapter(it + 10L, "Español") }

	// Interleaved, which is how a source really lists two translations.
	private val mixed = listOf(
		english[0], spanish[0], english[1], spanish[1], english[2], spanish[2], english[3], english[4],
	)

	@Test
	fun `everything is everything, both branches included`() {
		assertEquals(mixed, ChapterSelection.Everything.select(mixed))
	}

	@Test
	fun `a branch takes only its own chapters, in order`() {
		assertEquals(english, ChapterSelection.Branch("English").select(mixed))
		assertEquals(spanish, ChapterSelection.Branch("Español").select(mixed))
	}

	// A branch that is not there must come back empty rather than fall through to
	// everything, which would download three translations for someone who asked for one.
	@Test
	fun `a branch nobody publishes selects nothing`() {
		assertEquals(emptyList(), ChapterSelection.Branch("Deutsch").select(mixed))
	}

	@Test
	fun `a null branch is a real branch, not a wildcard`() {
		val unnamed = listOf(chapter(90L, null), chapter(91L, "English"))
		assertEquals(listOf(unnamed[0]), ChapterSelection.Branch(null).select(unnamed))
	}

	@Test
	fun `first takes from the front and stops`() {
		assertEquals(mixed.take(3), ChapterSelection.First(3, null).select(mixed))
	}

	@Test
	fun `first asks for more than there is and gets what there is`() {
		assertEquals(mixed, ChapterSelection.First(500, null).select(mixed))
		assertEquals(emptyList(), ChapterSelection.First(0, null).select(mixed))
		// A negative count is nonsense, not a reason to download the archive.
		assertEquals(emptyList(), ChapterSelection.First(-4, null).select(mixed))
	}

	@Test
	fun `first within a branch counts that branch, not the list`() {
		assertEquals(english.take(2), ChapterSelection.First(2, "English").select(mixed))
	}

	@Test
	fun `unread is what comes after the chapter last read`() {
		assertEquals(mixed.drop(3), ChapterSelection.Unread(mixed[2].id).select(mixed))
	}

	@Test
	fun `having read the last chapter leaves nothing to download`() {
		assertEquals(emptyList(), ChapterSelection.Unread(mixed.last().id).select(mixed))
	}

	// A source renumbers and the remembered chapter is gone. Offering the whole title is
	// the safe direction: it is visibly larger before confirming, where the opposite
	// silently skips chapters that were wanted.
	@Test
	fun `a forgotten reading position means everything is unread`() {
		assertEquals(mixed, ChapterSelection.Unread(9999L).select(mixed))
		assertEquals(mixed, ChapterSelection.Unread(null).select(mixed))
	}

	@Test
	fun `an empty chapter list never throws`() {
		val empty = emptyList<MangaChapter>()
		assertEquals(empty, ChapterSelection.Everything.select(empty))
		assertEquals(empty, ChapterSelection.Branch("English").select(empty))
		assertEquals(empty, ChapterSelection.First(10, null).select(empty))
		assertEquals(empty, ChapterSelection.Unread(1L).select(empty))
	}

	@Test
	fun `branches are listed once, in the order they appear`() {
		assertEquals(listOf("English", "Español"), branchesOf(mixed))
		assertEquals(listOf<String?>(null), branchesOf(listOf(chapter(1L, null))))
		assertEquals(emptyList(), branchesOf(emptyList()))
	}

	private fun chapter(id: Long, branch: String?) = MangaChapter(
		id = id,
		title = "Chapter $id",
		number = id.toFloat(),
		volume = 0,
		url = "/chapter/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = branch,
		source = MangaParserSource.MANGADEX,
	)
}
