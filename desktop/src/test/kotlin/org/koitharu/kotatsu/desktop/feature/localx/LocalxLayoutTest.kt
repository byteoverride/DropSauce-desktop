package org.koitharu.kotatsu.desktop.feature.localx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout detection, exhaustively, without touching a disk.
 *
 * Detection is a pure function of the entry paths exactly so this suite can enumerate the
 * shapes a real library contains instead of building an archive for each one. Every case
 * here is a layout somebody actually ships.
 */
class LocalxLayoutTest {

	@Test
	fun `a flat list of images is one chapter`() {
		val detected = detect(listOf("001.png", "002.png", "003.png"))
		assertEquals(EntryLayoutKind.SINGLE, detected.kind)
		assertEquals(1, detected.chapters.size)
		assertEquals(listOf("001.png", "002.png", "003.png"), detected.chapters.single().entries)
	}

	@Test
	fun `sub-directories become one chapter each, in natural order`() {
		val detected = detect(
			listOf(
				"Chapter 10/001.png",
				"Chapter 2/002.png",
				"Chapter 2/001.png",
				"Chapter 1/001.png",
			),
		)
		assertEquals(EntryLayoutKind.CHAPTER_DIRECTORIES, detected.kind)
		assertEquals(
			"Chapter 2 must come before Chapter 10",
			listOf("Chapter 1", "Chapter 2", "Chapter 10"),
			detected.chapters.map { it.title },
		)
		assertEquals(listOf(1, 2, 1), detected.chapters.map { it.entries.size })
		// Pages inside a chapter are ordered too, not left in zip order.
		assertEquals(listOf("Chapter 2/001.png", "Chapter 2/002.png"), detected.chapters[1].entries)
	}

	@Test
	fun `a single stray image at the root is a cover, not a chapter`() {
		val detected = detect(
			listOf("cover.jpg", "Ch 1/001.png", "Ch 2/001.png", "Ch 3/001.png"),
		)
		assertEquals(EntryLayoutKind.CHAPTER_DIRECTORIES, detected.kind)
		assertEquals(listOf("Ch 1", "Ch 2", "Ch 3"), detected.chapters.map { it.title })
		assertTrue(
			"the user must be told the stray was not made a chapter: ${detected.notes}",
			detected.notes.any { it.contains("cover.jpg") && it.contains("cover") },
		)
	}

	@Test
	fun `loose images beside sub-folders are ambiguous and are not guessed at`() {
		val layout = detectEntryLayout(
			listOf("001.png", "002.png", "003.png", "extras/sketch.png", "extras2/sketch.png"),
		)
		val ambiguous = layout as? EntryLayout.Ambiguous
			?: error("a mixed layout must not be resolved silently, got $layout")
		assertTrue(ambiguous.reason.contains("top level"))
		// The fallback is still offered, so the user is never stuck with nothing.
		assertEquals(5, ambiguous.flat.size)
	}

	@Test
	fun `sub-folders at different depths are ambiguous`() {
		val layout = detectEntryLayout(
			listOf("Vol 1/Ch 1/001.png", "Vol 1/Ch 2/001.png", "Extras/001.png"),
		)
		val ambiguous = layout as? EntryLayout.Ambiguous
			?: error("inconsistent nesting must not be resolved silently, got $layout")
		assertTrue(ambiguous.reason.contains("depths"))
	}

	@Test
	fun `a uniform two-level nesting is still one chapter per folder`() {
		val detected = detect(listOf("Vol 1/Ch 1/001.png", "Vol 1/Ch 2/001.png"))
		assertEquals(EntryLayoutKind.CHAPTER_DIRECTORIES, detected.kind)
		assertEquals(listOf("Ch 1", "Ch 2"), detected.chapters.map { it.title })
		assertEquals(listOf("Vol 1/Ch 1", "Vol 1/Ch 2"), detected.chapters.map { it.key })
	}

	@Test
	fun `one sub-folder is a comic zipped with its folder, not a series of one`() {
		val detected = detect(listOf("My Comic/001.png", "My Comic/002.png"))
		assertEquals(EntryLayoutKind.SINGLE, detected.kind)
		assertEquals(1, detected.chapters.size)
		assertEquals(2, detected.chapters.single().entries.size)
	}

	@Test
	fun `a container with no images is not a comic`() {
		val layout = detectEntryLayout(listOf("readme.txt", "ComicInfo.xml"))
		val rejected = layout as? EntryLayout.NotAComic ?: error("expected a rejection, got $layout")
		assertTrue(rejected.reason.contains("none of them images"))
	}

	@Test
	fun `an empty container is not a comic`() {
		val layout = detectEntryLayout(emptyList())
		assertTrue(layout is EntryLayout.NotAComic)
	}

	@Test
	fun `non-image entries never become pages`() {
		val detected = detect(listOf("001.png", "ComicInfo.xml", "notes.txt", "002.png"))
		assertEquals(listOf("001.png", "002.png"), detected.chapters.single().entries)
	}

	@Test
	fun `macOS packaging entries are ignored and reported`() {
		val detected = detect(
			listOf(
				"001.png",
				"002.png",
				"__MACOSX/._001.png",
				"__MACOSX/._002.png",
				".DS_Store",
			),
		)
		assertEquals(listOf("001.png", "002.png"), detected.chapters.single().entries)
		assertTrue(detected.notes.any { it.contains("packaging") })
	}

	@Test
	fun `windows separators and dot-slash prefixes are the same paths`() {
		val detected = detect(
			listOf("Ch 1\\001.png", "./Ch 1/002.png", "Ch 2\\001.png", "Ch 2/002.png"),
		)
		assertEquals(EntryLayoutKind.CHAPTER_DIRECTORIES, detected.kind)
		assertEquals(listOf("Ch 1", "Ch 2"), detected.chapters.map { it.title })
		assertEquals(listOf(2, 2), detected.chapters.map { it.entries.size })
	}

	@Test
	fun `grouping honours the recorded decision instead of re-deciding it`() {
		// The same entries detection calls ambiguous. Once the user has chosen "chapter
		// folders", grouping must produce them anyway, and must not drop the loose pages.
		val entries = listOf("001.png", "002.png", "Ch 1/001.png", "Ch 2/001.png")
		assertTrue(detectEntryLayout(entries) is EntryLayout.Ambiguous)
		val grouped = groupByDirectory(entries)
		assertEquals(listOf("Loose pages", "Ch 1", "Ch 2"), grouped.map { it.title })
		assertEquals(listOf(2, 1, 1), grouped.map { it.entries.size })
	}

	@Test
	fun `grouping keeps a lone root image out of the chapter list`() {
		val grouped = groupByDirectory(listOf("cover.png", "Ch 1/001.png", "Ch 2/001.png"))
		assertEquals(listOf("Ch 1", "Ch 2"), grouped.map { it.title })
	}

	@Test
	fun `grouping a flat container yields its one chapter even at a single page`() {
		val grouped = groupByDirectory(listOf("only.png"))
		assertEquals(1, grouped.size)
		assertEquals(listOf("only.png"), grouped.single().entries)
	}

	private fun detect(entries: List<String>): EntryLayout.Detected =
		detectEntryLayout(entries) as? EntryLayout.Detected
			?: error("expected a detected layout for $entries, got ${detectEntryLayout(entries)}")
}
