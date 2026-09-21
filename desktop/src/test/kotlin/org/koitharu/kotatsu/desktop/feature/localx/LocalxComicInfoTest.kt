package org.koitharu.kotatsu.desktop.feature.localx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `ComicInfo.xml`, including every way it is allowed to be useless. */
class LocalxComicInfoTest {

	@Test
	fun `a full file yields every field this app uses`() {
		val info = ComicInfoReader.parse(
			Fixtures.comicInfo(
				series = "Blame!",
				number = "3",
				title = "The City",
				writer = "Tsutomu Nihei",
				summary = "A very long staircase.",
				genre = "Science Fiction, Horror",
			).toByteArray(),
		)!!

		assertEquals("Blame!", info.series)
		assertEquals("3", info.number)
		assertEquals("The City", info.title)
		assertEquals("Tsutomu Nihei", info.writer)
		assertEquals("A very long staircase.", info.summary)
		assertEquals(listOf("Science Fiction", "Horror"), info.genres())
		assertEquals("Blame!", info.seriesTitle())
		assertEquals("3 - The City", info.chapterTitle())
	}

	@Test
	fun `a number without a title still names the chapter`() {
		val info = ComicInfoReader.parse(Fixtures.comicInfo(series = "S", number = "12").toByteArray())!!
		assertEquals("Chapter 12", info.chapterTitle())
	}

	@Test
	fun `a file with nothing usable in it counts as absent`() {
		assertNull(ComicInfoReader.parse("<ComicInfo></ComicInfo>".toByteArray()))
		assertNull(ComicInfoReader.parse(ByteArray(0)))
	}

	@Test
	fun `malformed xml is absent metadata, not an error`() {
		assertNull(ComicInfoReader.parse("<ComicInfo><Series>Unclosed".toByteArray()))
		assertNull(ComicInfoReader.parse("not xml at all".toByteArray()))
		assertNull(ComicInfoReader.parse("<Something><Series>Elsewhere</Series></Something>".toByteArray()))
	}

	@Test
	fun `an external entity is not resolved`() {
		// A comic is a file from a stranger. A parser left at its defaults would read
		// /etc/passwd into the series name here.
		val hostile = """
			<?xml version="1.0"?>
			<!DOCTYPE ComicInfo [ <!ENTITY x SYSTEM "file:///etc/passwd"> ]>
			<ComicInfo><Series>&x;</Series></ComicInfo>
		""".trimIndent()
		assertNull("a doctype must be refused outright", ComicInfoReader.parse(hostile.toByteArray()))
	}

	@Test
	fun `the entry is recognised wherever it sits and however it is cased`() {
		assertEquals(true, ComicInfoReader.isComicInfo("ComicInfo.xml"))
		assertEquals(true, ComicInfoReader.isComicInfo("Ch 1/comicinfo.XML"))
		assertEquals(false, ComicInfoReader.isComicInfo("ComicInfo.xml.bak"))
	}
}
