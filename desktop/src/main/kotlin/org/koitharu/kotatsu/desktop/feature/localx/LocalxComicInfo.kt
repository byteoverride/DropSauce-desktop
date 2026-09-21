package org.koitharu.kotatsu.desktop.feature.localx

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The fields of `ComicInfo.xml` this app can use.
 *
 * A small subset of the schema on purpose: the rest is release metadata that a reader has
 * nowhere to show. Every field is nullable because the file is written by a dozen
 * different tools and half of them omit most of it.
 */
data class ComicInfo(
	val series: String?,
	val number: String?,
	val title: String?,
	val writer: String?,
	val summary: String?,
	val genre: String?,
) {

	val isEmpty: Boolean
		get() = series == null && number == null && title == null &&
			writer == null && summary == null && genre == null

	/**
	 * The title this metadata suggests for the whole comic, or null when it suggests none.
	 *
	 * Series alone, not series plus number: the number belongs to the chapter, and folding
	 * it into the title would give a folder of twelve issues twelve differently named
	 * titles when the user asked for one.
	 */
	fun seriesTitle(): String? = series?.takeIf { it.isNotBlank() }

	/**
	 * The name for the chapter this metadata came from, or null.
	 *
	 * "12 - The Part Where It Happens" reads better than either half alone, and either
	 * half alone is what most files actually carry.
	 */
	fun chapterTitle(): String? {
		val n = number?.takeIf { it.isNotBlank() }
		val t = title?.takeIf { it.isNotBlank() }
		return when {
			n != null && t != null -> "$n - $t"
			t != null -> t
			n != null -> "Chapter $n"
			else -> null
		}
	}

	/** The genres, split on the comma the schema uses for multiple values. */
	fun genres(): List<String> =
		genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/**
 * Reads `ComicInfo.xml`, the de facto metadata standard for comic archives.
 *
 * Every entry point returns null rather than throwing. Metadata is a bonus: an import
 * that fails because somebody's tagger wrote a stray ampersand into a summary would be a
 * worse product than one that quietly falls back to the filename, which is what the user
 * had before the file was tagged anyway.
 */
object ComicInfoReader {

	const val ENTRY_NAME = "ComicInfo.xml"

	/** Whether [entry] is a ComicInfo document, wherever in the container it sits. */
	fun isComicInfo(entry: String): Boolean =
		entry.substringAfterLast('/').equals(ENTRY_NAME, ignoreCase = true)

	/** Parses [xml], or returns null when it is not usable ComicInfo. */
	fun parse(xml: ByteArray): ComicInfo? {
		if (xml.isEmpty()) return null
		val root = try {
			newDocumentBuilderFactory()
				.newDocumentBuilder()
				.apply { setErrorHandler(QuietXmlErrors) }
				.parse(ByteArrayInputStream(xml))
				.documentElement
		} catch (e: Exception) {
			// SAXParseException for malformed markup, IOException for a truncated entry,
			// ParserConfigurationException if a JDK ever refuses the hardening below. None
			// of them is a reason to refuse the comic, so they all mean "no metadata".
			return null
		} ?: return null
		// A file whose root is something else entirely is metadata for a different thing.
		if (!root.localName().equals("ComicInfo", ignoreCase = true)) return null
		val info = ComicInfo(
			series = root.childText("Series"),
			number = root.childText("Number"),
			title = root.childText("Title"),
			writer = root.childText("Writer"),
			summary = root.childText("Summary"),
			genre = root.childText("Genre"),
		)
		return info.takeIf { !it.isEmpty }
	}

	/**
	 * A parser that will not fetch anything.
	 *
	 * ComicInfo.xml arrives inside a file the user downloaded from a stranger. Left at its
	 * defaults a JDK parser resolves external entities, which turns opening a comic into
	 * reading local files and making network requests on that stranger's behalf.
	 */
	private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
		DocumentBuilderFactory.newInstance().apply {
			setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
			setFeature("http://xml.org/sax/features/external-general-entities", false)
			setFeature("http://xml.org/sax/features/external-parameter-entities", false)
			isXIncludeAware = false
			isExpandEntityReferences = false
			setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
			setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
		}
}

/**
 * Fails the parse without narrating it.
 *
 * The default handler prints every complaint to stderr and then throws anyway. Both
 * callers already treat a throw as "no metadata", so the printing is pure noise on a user
 * whose only mistake was owning a comic somebody tagged badly.
 */
internal object QuietXmlErrors : ErrorHandler {

	override fun warning(exception: SAXParseException) = Unit

	override fun error(exception: SAXParseException) = Unit

	override fun fatalError(exception: SAXParseException) = throw exception
}

/** The tag name without its namespace prefix, which ComicInfo files use inconsistently. */
internal fun Node.localName(): String = (localName ?: nodeName).substringAfterLast(':')

/** The text of the first direct child element called [name], trimmed, or null if blank. */
internal fun Element.childText(name: String): String? {
	val children = childNodes
	for (i in 0 until children.length) {
		val node = children.item(i)
		if (node.nodeType != Node.ELEMENT_NODE) continue
		if (node.localName().equals(name, ignoreCase = true)) {
			return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
		}
	}
	return null
}
