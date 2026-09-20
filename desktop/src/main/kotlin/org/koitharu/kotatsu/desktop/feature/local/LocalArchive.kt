package org.koitharu.kotatsu.desktop.feature.local

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.ZipFile

/**
 * How a local comic is stored.
 *
 * The name is what goes into `local_index.format`, so these constants are part of the
 * on-disk contract and must not be renamed casually.
 */
enum class LocalFormat {

	CBZ,
	ZIP,
	DIRECTORY,
	;

	companion object {

		fun of(name: String): LocalFormat? = entries.firstOrNull { it.name == name }
	}
}

/**
 * An import that cannot proceed, carrying a message meant for the user rather than a log.
 *
 * A distinct type because the import loop has to tell "this one file is not a comic"
 * apart from "the database write failed"; only the first is reportable per item and
 * survivable for the rest of a bulk import.
 */
class LocalImportException(message: String) : IOException(message)

/**
 * Where a single page lives: a container (archive file or directory) plus an entry in it.
 *
 * Encoded into [org.koitharu.kotatsu.parsers.model.MangaPage.url] so a local page carries
 * everything needed to decode it, exactly as a remote page carries an http url. Without
 * this the reader would need a side channel to find out which archive a page came from.
 */
data class LocalPageRef(val container: String, val entry: String) {

	fun encode(): String = SCHEME + enc(container) + SEPARATOR + enc(entry)

	companion object {

		const val SCHEME = "dropsauce-local:"

		private const val SEPARATOR = "!"

		fun decode(url: String): LocalPageRef? {
			if (!url.startsWith(SCHEME)) return null
			val body = url.removePrefix(SCHEME)
			val split = body.indexOf(SEPARATOR)
			if (split <= 0 || split == body.lastIndex) return null
			return LocalPageRef(
				container = dec(body.substring(0, split)),
				entry = dec(body.substring(split + 1)),
			)
		}

		// URLEncoder escapes '!' as %21, so the separator can never appear inside either
		// half and a plain indexOf is enough to split them again.
		private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

		private fun dec(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)
	}
}

/**
 * Orders names the way a reader expects: page2 before page10.
 *
 * Plain lexicographic order is wrong for every comic ever zipped without zero padding,
 * and zero padding is exactly what a scanner tool forgets to do. Digit runs are compared
 * as numbers, everything else case-insensitively.
 */
object NaturalOrder : Comparator<String> {

	override fun compare(a: String, b: String): Int {
		var i = 0
		var j = 0
		while (i < a.length && j < b.length) {
			val ca = a[i]
			val cb = b[j]
			if (ca.isDigit() && cb.isDigit()) {
				var endA = i
				while (endA < a.length && a[endA].isDigit()) endA++
				var endB = j
				while (endB < b.length && b[endB].isDigit()) endB++
				// Leading zeros carry no value, so strip them before comparing; once
				// stripped, the longer run is the larger number.
				val numA = a.substring(i, endA).trimStart('0')
				val numB = b.substring(j, endB).trimStart('0')
				if (numA.length != numB.length) return numA.length - numB.length
				val byDigits = numA.compareTo(numB)
				if (byDigits != 0) return byDigits
				i = endA
				j = endB
			} else {
				val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
				if (byChar != 0) return byChar
				i++
				j++
			}
		}
		val byRemainder = (a.length - i) - (b.length - j)
		// "page1" and "page01" compare equal on value alone; fall back to the raw string
		// so distinct names never collapse into an arbitrary order.
		return if (byRemainder != 0) byRemainder else a.compareTo(b)
	}
}

/**
 * Reading comics out of zip archives and image folders.
 *
 * Every call opens and closes the archive. Holding a [ZipFile] open across the UI's
 * lifetime would leak a file descriptor per imported comic and pin a file the user may
 * want to move, and pages are read rarely enough that the open cost does not matter.
 */
object LocalArchives {

	/** Extensions Skia can decode, lowercase, without the dot. */
	val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")

	private val ARCHIVE_EXTENSIONS = setOf("cbz", "zip")

	fun isArchive(file: File): Boolean =
		file.isFile && file.extension.lowercase() in ARCHIVE_EXTENSIONS

	fun isImage(file: File): Boolean =
		file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS

	/** The format of [file], or null when it is neither an archive nor a folder. */
	fun detect(file: File): LocalFormat? = when {
		file.isDirectory -> LocalFormat.DIRECTORY
		!file.isFile -> null
		file.extension.equals("cbz", ignoreCase = true) -> LocalFormat.CBZ
		file.extension.equals("zip", ignoreCase = true) -> LocalFormat.ZIP
		else -> null
	}

	/**
	 * Every page in [file], in reading order.
	 *
	 * Throws [LocalImportException] rather than returning an empty list for a broken
	 * container, because "this file is not a zip" and "this zip holds no images" need
	 * different messages and neither should be mistaken for a comic with no pages.
	 */
	fun listPages(file: File, format: LocalFormat): List<String> = when (format) {
		LocalFormat.DIRECTORY -> listDirectoryPages(file)
		LocalFormat.CBZ, LocalFormat.ZIP -> listArchivePages(file)
	}

	/** The bytes of one page. */
	fun readPage(file: File, format: LocalFormat, entry: String): ByteArray = when (format) {
		LocalFormat.DIRECTORY -> readDirectoryPage(file, entry)
		LocalFormat.CBZ, LocalFormat.ZIP -> readArchivePage(file, entry)
	}

	/** Reads a page addressed by [ref], detecting the container's format itself. */
	fun readPage(ref: LocalPageRef): ByteArray {
		val file = File(ref.container)
		val format = detect(file)
			?: throw LocalImportException("${file.name}: no longer a readable comic")
		return readPage(file, format, ref.entry)
	}

	/** Archives directly inside [directory], for a folder used as a bulk import. */
	fun archivesIn(directory: File): List<File> =
		(directory.listFiles() ?: emptyArray())
			.filter(::isArchive)
			.sortedWith(compareBy(NaturalOrder) { it.name })

	/** Total bytes on disk, used only for display. */
	fun sizeOf(file: File, format: LocalFormat): Long = when (format) {
		LocalFormat.DIRECTORY -> (file.listFiles() ?: emptyArray()).filter(::isImage).sumOf { it.length() }
		LocalFormat.CBZ, LocalFormat.ZIP -> file.length()
	}

	private fun listDirectoryPages(directory: File): List<String> {
		val children = directory.listFiles()
			?: throw LocalImportException("${directory.name}: folder could not be read")
		// Top level only. Nested folders are a chapter layout, which v1 does not model;
		// treating them as one flat comic would silently interleave chapters.
		val names = children.filter(::isImage).map { it.name }
		if (names.isEmpty()) {
			throw LocalImportException("${directory.name}: folder contains no images")
		}
		return names.sortedWith(NaturalOrder)
	}

	private fun listArchivePages(file: File): List<String> {
		val names = openZip(file).use { zip ->
			zip.entries().asSequence()
				.filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
				.map { it.name }
				.toList()
		}
		if (names.isEmpty()) {
			throw LocalImportException("${file.name}: archive contains no images")
		}
		return names.sortedWith(NaturalOrder)
	}

	private fun readDirectoryPage(directory: File, entry: String): ByteArray {
		// The entry comes from our own index, but the index is durable and the folder is
		// the user's; rejecting anything that escapes the folder keeps a stale or edited
		// row from reading an unrelated file.
		val target = File(directory, entry).canonicalFile
		val root = directory.canonicalFile
		if (target.parentFile != root || !target.isFile) {
			throw LocalImportException("${directory.name}: page \"$entry\" is missing")
		}
		return target.readBytes()
	}

	private fun readArchivePage(file: File, entry: String): ByteArray = openZip(file).use { zip ->
		val target = zip.getEntry(entry)
			?: throw LocalImportException("${file.name}: page \"$entry\" is missing")
		zip.getInputStream(target).use { it.readBytes() }
	}

	private fun openZip(file: File): ZipFile = try {
		ZipFile(file)
	} catch (e: IOException) {
		// ZipException, EOFException and a plain read error all mean the same thing to
		// the user, and the original message ("error in opening zip file") does not name
		// the file.
		throw LocalImportException("${file.name}: not a readable zip archive (${e.message ?: e::class.simpleName})")
	}
}
