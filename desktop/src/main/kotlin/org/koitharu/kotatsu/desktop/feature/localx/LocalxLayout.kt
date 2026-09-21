package org.koitharu.kotatsu.desktop.feature.localx

import org.koitharu.kotatsu.desktop.feature.local.LocalArchives
import org.koitharu.kotatsu.desktop.feature.local.NaturalOrder

/**
 * One chapter that layout detection proposes, before anything has been written down.
 *
 * [key] is container-relative and stable across re-imports, so a chapter id derived from
 * it survives the user adding a file to the folder. [entries] are container-relative too:
 * the container is whatever holds them, which detection does not need to know.
 */
data class ProposedChapter(
	val key: String,
	val title: String,
	val entries: List<String>,
)

/** The shape a list of entry paths turned out to have. */
enum class EntryLayoutKind {

	/** Every image belongs to one chapter. What a plain scanlation .cbz looks like. */
	SINGLE,

	/** Images are grouped into sub-directories, one chapter per directory. */
	CHAPTER_DIRECTORIES,
}

/**
 * The result of looking at a container's entry list.
 *
 * Three outcomes rather than two, because "I cannot tell" is a real answer and the one
 * the existing `local` area was right to avoid guessing past. [Ambiguous] is not a
 * failure: it carries the flat fallback so the importer can still offer the safe
 * single-chapter reading, with the reason shown to the user.
 */
sealed interface EntryLayout {

	data class Detected(
		val kind: EntryLayoutKind,
		val chapters: List<ProposedChapter>,
		/** Things the user should know about what was ignored or assumed. */
		val notes: List<String>,
	) : EntryLayout

	data class Ambiguous(
		val reason: String,
		/** Every image entry in reading order, for the single-chapter fallback. */
		val flat: List<String>,
		val notes: List<String>,
	) : EntryLayout

	data class NotAComic(val reason: String) : EntryLayout
}

/**
 * Groups [entries] into chapters, or refuses to.
 *
 * Pure: a list of paths in, a decision out, no file system. That is deliberate. Chapter
 * detection is the part of importing that is easy to get subtly wrong on somebody else's
 * library, and the only way to be sure it is right is to be able to enumerate the inputs
 * in a test rather than build an archive for every case.
 *
 * The rules, in order, and each one refuses rather than guesses:
 *
 *  1. no images at all is not a comic;
 *  2. no sub-directories is one chapter;
 *  3. two or more loose images sitting beside sub-directories is ambiguous, because that
 *     is equally a flat comic with extras and chapter folders with pages left outside;
 *  4. sub-directories at differing nesting depths are ambiguous, because nothing in the
 *     paths says which level is the chapter;
 *  5. exactly one sub-directory is one chapter, not a one-chapter series;
 *  6. otherwise each sub-directory is a chapter, in natural order.
 */
fun detectEntryLayout(entries: List<String>): EntryLayout {
	val notes = ArrayList<String>()
	val kept = ArrayList<String>(entries.size)
	var junk = 0
	for (raw in entries) {
		val path = normalizeEntry(raw)
		if (path.isEmpty() || path.endsWith('/')) continue
		if (isPackagingJunk(path)) {
			junk++
			continue
		}
		kept += path
	}
	if (junk > 0) {
		notes += "Ignored $junk packaging ${plural(junk, "file")} (__MACOSX, .DS_Store, Thumbs.db)."
	}
	val images = kept.filter(::isImageEntry)
	if (images.isEmpty()) {
		return EntryLayout.NotAComic(
			if (kept.isEmpty()) "it is empty" else "it holds ${kept.size} ${plural(kept.size, "file")}, none of them images",
		)
	}
	val flat = images.sortedWith(NaturalOrder)
	val byDirectory = images.groupBy { it.substringBeforeLast('/', "") }
	val loose = byDirectory[""].orEmpty()
	val directories = byDirectory.keys.filter { it.isNotEmpty() }.sortedWith(NaturalOrder)

	if (directories.isEmpty()) {
		return EntryLayout.Detected(EntryLayoutKind.SINGLE, listOf(wholeOf(flat)), notes)
	}
	if (loose.size > 1) {
		return EntryLayout.Ambiguous(
			reason = "${loose.size} images sit at the top level beside ${directories.size} " +
				"${plural(directories.size, "sub-folder")}. That is either one comic with extra " +
				"folders or chapter folders with pages left outside, and the names do not say which.",
			flat = flat,
			notes = notes,
		)
	}
	val depths = directories.mapTo(LinkedHashSet()) { it.count { c -> c == '/' } }
	if (depths.size > 1) {
		return EntryLayout.Ambiguous(
			reason = "sub-folders are nested at ${depths.size} different depths " +
				"(\"${directories.first()}\" against \"${directories.last()}\"). " +
				"Nothing in the paths says which level is a chapter.",
			flat = flat,
			notes = notes,
		)
	}
	if (directories.size == 1) {
		// A comic zipped together with its own folder, not a series of one. Calling this a
		// chapter layout would give every such file a pointless single-entry chapter list.
		notes += "Everything is inside one folder (\"${directories.single()}\"), so this is one chapter."
		return EntryLayout.Detected(EntryLayoutKind.SINGLE, listOf(wholeOf(flat)), notes)
	}
	if (loose.size == 1) {
		notes += "\"${loose.single()}\" sits at the top level on its own; it is kept as the cover " +
			"and is not a chapter."
	}
	val chapters = directories.map { directory ->
		ProposedChapter(
			key = directory,
			title = directory.substringAfterLast('/'),
			entries = byDirectory.getValue(directory).sortedWith(NaturalOrder),
		)
	}
	return EntryLayout.Detected(EntryLayoutKind.CHAPTER_DIRECTORIES, chapters, notes)
}

/** The single chapter that covers a whole container. */
private fun wholeOf(entries: List<String>) = ProposedChapter(key = "", title = "", entries = entries)

/**
 * A zip entry path as this area wants to see it.
 *
 * Zips written on Windows carry backslashes, and some writers prefix "./" or a leading
 * slash; all three would otherwise split into different, wrong directories.
 */
fun normalizeEntry(raw: String): String {
	var path = raw.replace('\\', '/')
	while (path.startsWith("./")) path = path.substring(2)
	path = path.trimStart('/')
	return path
}

/** Whether [entry] is something a decoder could draw. */
fun isImageEntry(entry: String): Boolean =
	entry.substringAfterLast('/').substringAfterLast('.', "").lowercase() in LocalArchives.IMAGE_EXTENSIONS

/**
 * Files the archiver added, not the person who made the comic.
 *
 * `__MACOSX` in particular holds an `._name` twin of every real entry, so without this a
 * macOS-zipped comic detects twice as many pages as it has and half of them fail to
 * decode.
 */
fun isPackagingJunk(entry: String): Boolean {
	val segments = entry.split('/')
	if (segments.any { it == "__MACOSX" }) return true
	val name = segments.last()
	return name.startsWith("._") || name == ".DS_Store" || name.equals("Thumbs.db", ignoreCase = true)
}

internal fun plural(count: Int, word: String): String = if (count == 1) word else word + "s"

/**
 * Splits [entries] into one chapter per directory, without deciding whether it should.
 *
 * The counterpart to [detectEntryLayout]: detection proposes and the user confirms, and
 * from then on the stored format string says "these are chapter folders" and this is what
 * honours it. Re-running detection on every read instead would let a chapter list change
 * shape because somebody dropped a file beside the pages, which is the sort of thing a
 * library index must never do on its own.
 *
 * A lone image at the top level is the cover, not a one-page chapter; two or more get a
 * chapter of their own rather than being dropped, because dropping pages silently is
 * worse than an oddly named chapter.
 */
fun groupByDirectory(entries: List<String>): List<ProposedChapter> {
	val images = entries.asSequence()
		.map(::normalizeEntry)
		.filter { it.isNotEmpty() && !it.endsWith('/') }
		.filterNot(::isPackagingJunk)
		.filter(::isImageEntry)
		.toList()
	if (images.isEmpty()) return emptyList()
	val byDirectory = images.groupBy { it.substringBeforeLast('/', "") }
	val directories = byDirectory.keys.filter { it.isNotEmpty() }.sortedWith(NaturalOrder)
	val loose = byDirectory[""].orEmpty()
	val chapters = ArrayList<ProposedChapter>(directories.size + 1)
	if (loose.isNotEmpty() && (directories.isEmpty() || loose.size > 1)) {
		chapters += ProposedChapter(
			key = "",
			title = if (directories.isEmpty()) "" else "Loose pages",
			entries = loose.sortedWith(NaturalOrder),
		)
	}
	for (directory in directories) {
		chapters += ProposedChapter(
			key = directory,
			title = directory.substringAfterLast('/'),
			entries = byDirectory.getValue(directory).sortedWith(NaturalOrder),
		)
	}
	return chapters
}
