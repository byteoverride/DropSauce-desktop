package org.koitharu.kotatsu.desktop.feature.localx

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.desktop.feature.local.LocalImportException
import java.io.File
import java.nio.file.Files

/** EPUB reading, against real generated books. */
class LocalxEpubTest {

	private lateinit var temp: File

	@Before
	fun setUp() {
		temp = Files.createTempDirectory("localx-epub-test").toFile()
	}

	@After
	fun tearDown() {
		temp.deleteRecursively()
	}

	@Test
	fun `a book yields its spine in order with the titles from the NCX`() {
		val file = File(temp, "Book.epub")
		Fixtures.epub(
			file,
			bookTitle = "The Long Book",
			chapters = listOf(
				"A Beginning" to "It started badly.",
				"A Middle" to "It continued.",
				"An End" to "It stopped.",
			),
		)

		val book = EpubReader.read(file)
		assertEquals("The Long Book", book.title)
		assertEquals(listOf("A Writer"), book.authors)
		assertEquals("Made by a test.", book.description)
		assertEquals(
			listOf("A Beginning", "A Middle", "An End"),
			book.chapters.map { it.title },
		)
		// Hrefs are resolved against the OPF directory, not left relative to it.
		assertEquals(
			listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml", "OEBPS/text/ch3.xhtml"),
			book.chapters.map { it.href },
		)
	}

	@Test
	fun `a book with no NCX still reads through the spine`() {
		val file = File(temp, "Bare.epub")
		Fixtures.epub(
			file,
			bookTitle = "No Contents",
			chapters = listOf("First" to "one", "Second" to "two"),
			withNcx = false,
		)

		val book = EpubReader.read(file)
		assertEquals(2, book.chapters.size)
		assertEquals(
			listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"),
			book.chapters.map { it.href },
		)
		// Without a table of contents the filename is the only title there is.
		assertEquals(listOf("ch1", "ch2"), book.chapters.map { it.title })
	}

	@Test
	fun `chapter text comes back as readable prose`() {
		val file = File(temp, "Text.epub")
		Fixtures.epub(file, "Prose", listOf("Chapter One" to "Two sentences. Then another."))

		val text = EpubReader.chapterText(file, "OEBPS/text/ch1.xhtml")
		assertTrue("the heading must survive: $text", text.contains("Chapter One"))
		assertTrue("the body must survive: $text", text.contains("Two sentences. Then another."))
		assertFalse("markup must not: $text", text.contains("<"))
		// The <style> block is markup, not prose, and must not be read out as text.
		assertFalse("stylesheets must not leak: $text", text.contains("color"))
	}

	@Test
	fun `a file that is not an EPUB fails with a message naming it`() {
		val file = File(temp, "Wrong.epub")
		Fixtures.zip(file, listOf("001.png"))

		val thrown = runCatching { EpubReader.read(file) }.exceptionOrNull()
		assertTrue("expected a user-facing failure, got $thrown", thrown is LocalImportException)
		assertTrue(thrown!!.message!!.contains("Wrong.epub"))
		assertTrue(thrown.message!!.contains("container.xml"))
	}

	@Test
	fun `chapters are addressed by a resolved href`() {
		assertEquals("OEBPS/text/ch1.xhtml", resolveHref("OEBPS", "text/ch1.xhtml"))
		assertEquals("images/cover.png", resolveHref("OEBPS/text", "../../images/cover.png"))
		assertEquals("a/b.xhtml", resolveHref("", "/a/b.xhtml"))
		// Percent-escaped hrefs address the plain entry name.
		assertEquals("OEBPS/a b.xhtml", resolveHref("OEBPS", "a%20b.xhtml"))
	}

	@Test
	fun `markup is flattened into paragraphs, not into one run-on line`() {
		val text = htmlToText(
			"<html><body><h1>Title</h1><p>One &amp; two.</p><p>Three&#8212;four</p>" +
				"<script>var x = 1 < 2;</script><!-- hidden --></body></html>",
		)
		assertEquals("Title\n\nOne & two.\n\nThree—four", text)
	}

	@Test
	fun `a malformed entity is left alone rather than mangled`() {
		assertEquals("100% & rising", htmlToText("<p>100% &amp; rising</p>"))
		assertEquals("a &notanentity; b", htmlToText("a &notanentity; b"))
	}

	@Test
	fun `an EPUB is recognised by extension only when it is a file`() {
		val directory = File(temp, "notabook.epub").also { it.mkdirs() }
		assertFalse(EpubReader.isEpub(directory))
		val file = File(temp, "Real.epub")
		Fixtures.epub(file, "Real", listOf("One" to "text"))
		assertTrue(EpubReader.isEpub(file))
		assertNull(LocalxFormat.of("NOT_A_FORMAT"))
	}
}
