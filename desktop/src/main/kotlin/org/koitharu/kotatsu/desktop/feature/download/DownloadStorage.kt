package org.koitharu.kotatsu.desktop.feature.download

import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * Where downloaded pages live, and the only code allowed to create or remove them.
 *
 * Layout is `<data>/downloads/<source>/<mangaId>/<chapterId>/0001.jpg`. Page files are
 * numbered with a fixed width so a plain lexicographic sort is reading order; a bare
 * `1.jpg, 2.jpg, 10.jpg` sort would put page 10 second.
 *
 * Every page is written to a `.part` name and then atomically moved onto its real name.
 * An interrupted write therefore leaves a file that [pages] does not match, instead of a
 * truncated `0007.jpg` that a later read would treat as a valid page.
 */
class DownloadStorage(
	/**
	 * Where downloads go, read fresh every time rather than captured once.
	 *
	 * The reader can change this in Settings and a queue is long lived, so a root fixed
	 * at construction would keep writing to the old place until the app restarted, with
	 * nothing on screen to say so.
	 */
	private val rootProvider: () -> Path,
	private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {

	/** For a fixed location, which is every caller that is not the running app. */
	constructor(root: Path, fileSystem: FileSystem = FileSystem.SYSTEM) :
		this({ root / DIR }, fileSystem)

	val downloadsRoot: Path get() = rootProvider()

	fun mangaDir(sourceName: String, mangaId: Long): Path =
		downloadsRoot / safeName(sourceName) / mangaId.toString()

	fun chapterDir(sourceName: String, mangaId: Long, chapterId: Long): Path =
		mangaDir(sourceName, mangaId) / chapterId.toString()

	/**
	 * Empties [dir] and recreates it.
	 *
	 * A worker always starts from nothing. Pages left by an interrupted earlier attempt
	 * cannot be trusted to belong to the same page list: a source may return a different
	 * number of pages than it did last time, so page 4 of the old attempt is not
	 * necessarily page 4 of this one.
	 */
	fun prepare(dir: Path) {
		delete(dir)
		fileSystem.createDirectories(dir, mustCreate = false)
	}

	/** Writes one page and returns where it landed. [index] is zero based. */
	fun writePage(dir: Path, index: Int, data: PageBytes): Path {
		if (data.bytes.isEmpty()) {
			throw IOException("Refusing to store an empty page file for page ${index + 1}")
		}
		val name = pageName(index, data.extension)
		val temp = dir / (name + TEMP_SUFFIX)
		fileSystem.write(temp) { write(data.bytes) }
		val target = dir / name
		fileSystem.atomicMove(temp, target)
		return target
	}

	/**
	 * The stored pages of one chapter, in reading order.
	 *
	 * Only files matching the page naming scheme are returned, which is what keeps a
	 * leftover `.part` file out of a reader's page list.
	 */
	fun pages(dir: Path): List<Path> {
		if (!fileSystem.exists(dir)) return emptyList()
		return fileSystem.list(dir)
			.filter { PAGE_NAME.matches(it.name) }
			// By the number, not by the string. Fixed width makes those agree up to 9999
			// pages and disagree after it, and a chapter that long should still read in
			// order rather than almost in order.
			.sortedBy { it.name.substringBefore('.').toLong() }
	}

	fun delete(dir: Path) {
		fileSystem.deleteRecursively(dir, mustExist = false)
	}

	/** Removes a chapter, then the title's directory if that was its last chapter. */
	fun deleteChapter(sourceName: String, mangaId: Long, chapterId: Long) {
		delete(chapterDir(sourceName, mangaId, chapterId))
		val parent = mangaDir(sourceName, mangaId)
		if (fileSystem.exists(parent) && fileSystem.list(parent).isEmpty()) {
			delete(parent)
		}
	}

	fun exists(path: Path): Boolean = fileSystem.exists(path)

	private fun pageName(index: Int, extension: String): String {
		val clean = extension.trimStart('.').lowercase().filter { it.isLetterOrDigit() }
		return NUMBER_FORMAT.format(index + 1) + "." + clean.ifEmpty { DEFAULT_EXTENSION }
	}

	/**
	 * Keeps a source name usable as a single directory component.
	 *
	 * Source names are enum constants today, so this never changes anything; it exists
	 * so that a future non-enum source name cannot inject a path separator and write
	 * outside the downloads tree.
	 */
	private fun safeName(value: String): String {
		val cleaned = value.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
			.joinToString(separator = "")
		return cleaned.ifEmpty { "unknown" }
	}

	private companion object {

		const val DIR = "downloads"
		const val TEMP_SUFFIX = ".part"
		const val NUMBER_FORMAT = "%04d"
		const val DEFAULT_EXTENSION = "jpg"

		/** Four or more digits, so a chapter longer than 9999 pages still sorts right. */
		val PAGE_NAME = Regex("""^\d{4,}\.[a-z0-9]+$""")
	}
}
