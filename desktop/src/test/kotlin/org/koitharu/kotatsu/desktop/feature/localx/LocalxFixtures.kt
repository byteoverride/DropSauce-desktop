package org.koitharu.kotatsu.desktop.feature.localx

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real files for the import tests: real zip bytes and real PNGs.
 *
 * A fake archive abstraction would pass where `ZipFile` and `ImageIO` fail on the same
 * input, and those two are the only things in this area that can fail on input the tests
 * cannot imagine.
 */
object Fixtures {

	/** A PNG of the given size, which `ImageIO` and Skia both decode. */
	fun png(width: Int = 4, height: Int = 6): ByteArray {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val graphics = image.createGraphics()
		try {
			graphics.color = Color(0x33, 0x66, 0x99)
			graphics.fillRect(0, 0, width, height)
		} finally {
			graphics.dispose()
		}
		val out = ByteArrayOutputStream()
		check(ImageIO.write(image, "png", out)) { "no PNG writer available" }
		return out.toByteArray()
	}

	/** A zip whose image entries are [names], plus any extra text entries. */
	fun zip(file: File, names: List<String>, extras: Map<String, String> = emptyMap()) {
		file.parentFile?.mkdirs()
		ZipOutputStream(file.outputStream().buffered()).use { zip ->
			for (name in names) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(png())
				zip.closeEntry()
			}
			for ((name, body) in extras) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(body.toByteArray())
				zip.closeEntry()
			}
		}
	}

	/** A folder holding [names] as real PNG files, sub-folders created as needed. */
	fun folder(root: File, names: List<String>): File {
		for (name in names) {
			val target = File(root, name)
			target.parentFile?.mkdirs()
			target.writeBytes(png())
		}
		return root
	}

	fun comicInfo(
		series: String? = null,
		number: String? = null,
		title: String? = null,
		writer: String? = null,
		summary: String? = null,
		genre: String? = null,
	): String = buildString {
		append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
		append("<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
		series?.let { append("  <Series>$it</Series>\n") }
		number?.let { append("  <Number>$it</Number>\n") }
		title?.let { append("  <Title>$it</Title>\n") }
		writer?.let { append("  <Writer>$it</Writer>\n") }
		summary?.let { append("  <Summary>$it</Summary>\n") }
		genre?.let { append("  <Genre>$it</Genre>\n") }
		append("</ComicInfo>\n")
	}

	/**
	 * A minimal but real EPUB 2.
	 *
	 * [chapters] are title to body text. When [withNcx] is false the NCX is left out
	 * entirely, which is the case a reader has to survive by falling back to the spine.
	 */
	fun epub(file: File, bookTitle: String, chapters: List<Pair<String, String>>, withNcx: Boolean = true) {
		val ids = chapters.indices.map { "ch${it + 1}" }
		// Built by concatenation, not trimIndent: an interpolated multi-line value changes
		// what trimIndent thinks the common indent is, and a single space before the XML
		// declaration makes every parser reject the document.
		val manifest = buildString {
			if (withNcx) append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
			for (id in ids) {
				append("    <item id=\"$id\" href=\"text/$id.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
			}
		}
		val spine = ids.joinToString("\n") { "    <itemref idref=\"$it\"/>" }
		val opf = buildString {
			append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
			append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"bookid\">\n")
			append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
			append("    <dc:title>$bookTitle</dc:title>\n")
			append("    <dc:creator>A Writer</dc:creator>\n")
			append("    <dc:description>Made by a test.</dc:description>\n")
			append("  </metadata>\n")
			append("  <manifest>\n")
			append(manifest)
			append("  </manifest>\n")
			append(if (withNcx) "  <spine toc=\"ncx\">\n" else "  <spine>\n")
			append(spine)
			append("\n  </spine>\n</package>\n")
		}
		val ncx = buildString {
			append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
			append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n  <navMap>\n")
			chapters.forEachIndexed { i, (title, _) ->
				append("    <navPoint id=\"np${i + 1}\" playOrder=\"${i + 1}\">\n")
				append("      <navLabel><text>$title</text></navLabel>\n")
				append("      <content src=\"text/${ids[i]}.xhtml\"/>\n")
				append("    </navPoint>\n")
			}
			append("  </navMap>\n</ncx>\n")
		}
		val container = buildString {
			append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
			append("<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n")
			append("  <rootfiles>\n")
			append("    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n")
			append("  </rootfiles>\n</container>\n")
		}

		file.parentFile?.mkdirs()
		ZipOutputStream(file.outputStream().buffered()).use { zip ->
			fun put(name: String, body: String) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(body.toByteArray())
				zip.closeEntry()
			}
			put("mimetype", "application/epub+zip")
			put("META-INF/container.xml", container)
			put("OEBPS/content.opf", opf)
			if (withNcx) put("OEBPS/toc.ncx", ncx)
			chapters.forEachIndexed { i, (title, body) ->
				put(
					"OEBPS/text/${ids[i]}.xhtml",
					"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
						"<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>$title</title>" +
						"<style>p { color: red; }</style></head>" +
						"<body><h1>$title</h1><p>$body</p></body></html>",
				)
			}
		}
	}
}
