package org.koitharu.kotatsu.desktop.feature.localx

import org.koitharu.kotatsu.desktop.feature.local.LocalImportException
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** One document in an EPUB's reading order. */
data class EpubChapter(
	/** The zip entry path of the document, already resolved against the OPF directory. */
	val href: String,
	val title: String,
)

/** What an EPUB says about itself, plus its reading order. */
data class EpubBook(
	val title: String?,
	val authors: List<String>,
	val description: String?,
	val chapters: List<EpubChapter>,
)

/**
 * Reading EPUB, enough of it to show the text in order with the right chapter names.
 *
 * EPUB 2 and 3 both, because a user's shelf is both. The path is always the same three
 * steps: `META-INF/container.xml` names the OPF, the OPF's manifest and spine give the
 * reading order, and the NCX (EPUB 2) or nav document (EPUB 3) gives the chapter titles.
 * Only the third step is optional, and a book missing it still reads: the spine alone is
 * the reading order, and a filename is a worse title than a real one but not no title.
 *
 * Self-contained, and deliberately not routed through `LocalArchives`: that one addresses
 * pages as images inside a container, and an EPUB chapter is a document, not an image.
 */
object EpubReader {

	fun isEpub(file: File): Boolean = file.isFile && file.extension.equals("epub", ignoreCase = true)

	/**
	 * The structure of [file].
	 *
	 * Throws [LocalImportException] with a message meant for the user, because every
	 * failure here is "this file is not a book I can read" and the import loop reports
	 * that per file rather than aborting.
	 */
	fun read(file: File): EpubBook = openEpub(file).use { zip ->
		val containerXml = zip.readEntry("META-INF/container.xml")
			?: throw LocalImportException("${file.name}: not an EPUB (no META-INF/container.xml)")
		val opfPath = parseContainer(containerXml)
			?: throw LocalImportException("${file.name}: container.xml names no package document")
		val opfDir = opfPath.substringBeforeLast('/', "")
		val opfXml = zip.readEntry(opfPath)
			?: throw LocalImportException("${file.name}: package document \"$opfPath\" is missing")
		val opf = parseOpf(opfXml)
			?: throw LocalImportException("${file.name}: package document is not readable XML")

		val navHref = opf.manifest.values.firstOrNull { "nav" in it.properties }
			?.let { resolveHref(opfDir, it.href) }
		val ncxHref = (
			opf.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
				?: opf.tocId?.let { opf.manifest[it] }
			)?.let { resolveHref(opfDir, it.href) }
		// Titles are a best effort. A book whose NCX is absent, unparseable or points at
		// documents that are not in the spine still has a spine, and the spine is the part
		// that makes it readable at all.
		val labels = when {
			ncxHref != null -> zip.readEntry(ncxHref)?.let { parseNcx(it, ncxHref) }
			navHref != null -> zip.readEntry(navHref)?.let { parseNav(it, navHref) }
			else -> null
		}.orEmpty()

		val chapters = ArrayList<EpubChapter>(opf.spine.size)
		for (idref in opf.spine) {
			val item = opf.manifest[idref] ?: continue
			val href = resolveHref(opfDir, item.href)
			// The nav document is a table of contents, not a chapter; showing it as one
			// puts a list of links where the first page of the book should be.
			if (href == navHref) continue
			chapters += EpubChapter(href, labels[href] ?: titleFromHref(href))
		}
		if (chapters.isEmpty()) {
			throw LocalImportException("${file.name}: the package document lists no readable chapters")
		}
		EpubBook(
			title = opf.title,
			authors = opf.authors,
			description = opf.description,
			chapters = chapters,
		)
	}

	/** The readable text of one chapter, with its markup flattened. */
	fun chapterText(file: File, href: String): String = openEpub(file).use { zip ->
		val raw = zip.readEntry(href)
			?: throw LocalImportException("${file.name}: chapter \"$href\" is missing")
		htmlToText(raw)
	}

	private fun openEpub(file: File): ZipFile = try {
		ZipFile(file)
	} catch (e: IOException) {
		throw LocalImportException("${file.name}: not a readable EPUB (${e.message ?: e::class.simpleName})")
	}

	private fun ZipFile.readEntry(name: String): String? {
		val entry = getEntry(name)
			?: getEntry(name.removePrefix("/"))
			// Some writers percent-encode hrefs that the entry names do not carry, and the
			// reverse; try the literal name before giving up.
			?: entries().asSequence().firstOrNull { normalizeEntry(it.name) == normalizeEntry(name) }
			?: return null
		val bytes = getInputStream(entry).use { it.readBytes() }
		// A BOM in front of the declaration makes every XML parser refuse the document.
		return bytes.toString(Charsets.UTF_8).removePrefix("﻿")
	}

	private fun parseContainer(xml: String): String? =
		parseXml(xml)?.elements("rootfile")?.firstNotNullOfOrNull { it.attr("full-path") }
			?.let { normalizeEntry(it) }

	private class ManifestItem(
		val href: String,
		val mediaType: String,
		val properties: String,
	)

	private class Opf(
		val title: String?,
		val authors: List<String>,
		val description: String?,
		val tocId: String?,
		val manifest: Map<String, ManifestItem>,
		val spine: List<String>,
	)

	private fun parseOpf(xml: String): Opf? {
		val document = parseXml(xml) ?: return null
		val manifest = LinkedHashMap<String, ManifestItem>()
		for (item in document.elements("item")) {
			val id = item.attr("id") ?: continue
			val href = item.attr("href") ?: continue
			manifest[id] = ManifestItem(
				href = href,
				mediaType = item.attr("media-type").orEmpty(),
				properties = item.attr("properties").orEmpty(),
			)
		}
		val spine = ArrayList<String>()
		for (ref in document.elements("itemref")) {
			// linear="no" marks front matter the reader is meant to reach from a link, not
			// by turning the page into it.
			if (ref.attr("linear").equals("no", ignoreCase = true)) continue
			ref.attr("idref")?.let { spine += it }
		}
		return Opf(
			title = document.elements("title").firstNotNullOfOrNull { it.trimmedText() },
			authors = document.elements("creator").mapNotNull { it.trimmedText() }.distinct(),
			description = document.elements("description").firstNotNullOfOrNull { it.trimmedText() },
			tocId = document.elements("spine").firstNotNullOfOrNull { it.attr("toc") },
			manifest = manifest,
			spine = spine,
		)
	}

	/** EPUB 2 table of contents: entry path to chapter title. */
	private fun parseNcx(xml: String, ncxHref: String): Map<String, String> {
		val document = parseXml(xml) ?: return emptyMap()
		val base = ncxHref.substringBeforeLast('/', "")
		val labels = LinkedHashMap<String, String>()
		for (point in document.elements("navPoint")) {
			val text = point.elements("navLabel").firstOrNull()?.elements("text")?.firstOrNull()?.trimmedText()
				?: continue
			val src = point.elements("content").firstNotNullOfOrNull { it.attr("src") } ?: continue
			labels.putIfAbsent(resolveHref(base, src.substringBefore('#')), text)
		}
		return labels
	}

	/** EPUB 3 table of contents: the first `nav`, whose hrefs are relative to itself. */
	private fun parseNav(xml: String, navHref: String): Map<String, String> {
		val document = parseXml(xml) ?: return emptyMap()
		val base = navHref.substringBeforeLast('/', "")
		val nav = document.elements("nav").firstOrNull { nav ->
			val type = nav.attr("type")
			type == null || type.equals("toc", ignoreCase = true)
		} ?: return emptyMap()
		val labels = LinkedHashMap<String, String>()
		for (link in nav.elements("a")) {
			val href = link.attr("href")?.substringBefore('#')?.takeIf { it.isNotEmpty() } ?: continue
			val text = link.trimmedText() ?: continue
			labels.putIfAbsent(resolveHref(base, href), text)
		}
		return labels
	}

	private fun parseXml(xml: String): Document? = try {
		hardenedFactory().newDocumentBuilder()
			.apply { setErrorHandler(QuietXmlErrors) }
			.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
	} catch (e: Exception) {
		// Same reasoning as ComicInfoReader: a book with one broken auxiliary document
		// still reads, so a parse failure is an absent document, not a failed import.
		null
	}

	/** See [ComicInfoReader]; an EPUB is likewise a file from a stranger. */
	private fun hardenedFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
		isNamespaceAware = true
		setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
		setFeature("http://xml.org/sax/features/external-general-entities", false)
		setFeature("http://xml.org/sax/features/external-parameter-entities", false)
		isXIncludeAware = false
		isExpandEntityReferences = false
		setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
		setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
	}

	private fun titleFromHref(href: String): String = href.substringAfterLast('/')
		.substringBeforeLast('.')
		.replace('_', ' ')
		.replace('-', ' ')
		.trim()
		.ifEmpty { href }
}

/**
 * Resolves a content href against [baseDir], yielding a zip entry path.
 *
 * Hrefs inside an EPUB are relative to the document that carries them, and ".." appears
 * constantly because the usual layout puts text and images in sibling folders.
 */
fun resolveHref(baseDir: String, href: String): String {
	val decoded = try {
		URLDecoder.decode(href, Charsets.UTF_8)
	} catch (e: IllegalArgumentException) {
		// A stray '%' that is not an escape. The raw href is still the best guess.
		href
	}
	val segments = ArrayList<String>()
	if (baseDir.isNotEmpty() && !decoded.startsWith('/')) {
		segments.addAll(baseDir.split('/').filter { it.isNotEmpty() })
	}
	for (part in decoded.replace('\\', '/').trimStart('/').split('/')) {
		when (part) {
			"", "." -> Unit
			".." -> segments.removeLastOrNull()
			else -> segments += part
		}
	}
	return segments.joinToString("/")
}

/**
 * Flattens an (X)HTML document to readable text.
 *
 * Regex rather than the DOM parser used everywhere else in this file, because chapter
 * documents are the part of an EPUB most likely to be almost-XHTML, and a chapter that
 * will not parse must still be readable. Block tags become line breaks so paragraphs
 * survive; everything else is dropped.
 */
fun htmlToText(html: String): String {
	var text = html
	text = COMMENT.replace(text, "")
	text = SCRIPT_OR_STYLE.replace(text, "")
	text = BLOCK_BREAK.replace(text, "\n")
	text = TAG.replace(text, "")
	text = decodeEntities(text)
	return text.lineSequence()
		.map { it.replace(' ', ' ').trim() }
		.joinToString("\n")
		.replace(BLANK_RUN, "\n\n")
		.trim()
}

private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
	val body = match.groupValues[1]
	when {
		body.startsWith("#x", ignoreCase = true) -> codePoint(body.drop(2).toIntOrNull(16)) ?: match.value

		body.startsWith("#") -> codePoint(body.drop(1).toIntOrNull()) ?: match.value

		else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
	}
}

/** A numeric entity's character, or null when the number is not one. */
private fun codePoint(value: Int?): String? =
	if (value != null && Character.isValidCodePoint(value)) String(Character.toChars(value)) else null

private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

private val SCRIPT_OR_STYLE = Regex(
	"<(script|style)\\b[^>]*>.*?</\\1\\s*>",
	setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

private val BLOCK_BREAK = Regex(
	"</?(p|div|br|hr|li|tr|h[1-6]|blockquote|section|article|figure|figcaption|pre|table)\\b[^>]*>",
	RegexOption.IGNORE_CASE,
)

private val TAG = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)

private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,31});")

private val BLANK_RUN = Regex("\n{3,}")

private val NAMED_ENTITIES = mapOf(
	"amp" to "&",
	"lt" to "<",
	"gt" to ">",
	"quot" to "\"",
	"apos" to "'",
	"nbsp" to " ",
	"mdash" to "—",
	"ndash" to "–",
	"hellip" to "…",
	"ldquo" to "“",
	"rdquo" to "”",
	"lsquo" to "‘",
	"rsquo" to "’",
	"copy" to "©",
	"reg" to "®",
	"trade" to "™",
	"deg" to "°",
	"laquo" to "«",
	"raquo" to "»",
	"middot" to "·",
	"bull" to "•",
	"eacute" to "é",
	"egrave" to "è",
	"agrave" to "à",
	"uuml" to "ü",
	"ouml" to "ö",
	"auml" to "ä",
	"szlig" to "ß",
)

/** Every descendant element with this local name, namespace prefixes ignored. */
private fun Document.elements(name: String): List<Element> = documentElement.elements(name)

private fun Element.elements(name: String): List<Element> {
	val result = ArrayList<Element>()
	if (localName().equals(name, ignoreCase = true)) result += this
	val children = getElementsByTagName("*")
	for (i in 0 until children.length) {
		val node = children.item(i) as? Element ?: continue
		if (node.localName().equals(name, ignoreCase = true)) result += node
	}
	return result
}

/** An attribute by local name, so `epub:type` and `type` are the same attribute. */
private fun Element.attr(name: String): String? {
	val attributes = attributes ?: return null
	for (i in 0 until attributes.length) {
		val node = attributes.item(i)
		if (node.localName().equals(name, ignoreCase = true)) {
			return node.nodeValue
		}
	}
	return null
}

private fun Node.trimmedText(): String? = textContent?.trim()?.takeIf { it.isNotEmpty() }
