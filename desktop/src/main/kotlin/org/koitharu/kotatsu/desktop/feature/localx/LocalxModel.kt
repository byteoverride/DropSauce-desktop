package org.koitharu.kotatsu.desktop.feature.localx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.local.LocalArchives
import org.koitharu.kotatsu.desktop.feature.local.LocalFormat
import org.koitharu.kotatsu.desktop.feature.local.LocalImportException
import org.koitharu.kotatsu.desktop.feature.local.LocalMangaSource
import org.koitharu.kotatsu.desktop.feature.local.LocalPageRef
import org.koitharu.kotatsu.desktop.feature.local.NaturalOrder
import org.koitharu.kotatsu.desktop.feature.local.localId
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.shared.db.LocalMangaEntity
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * How a multi-chapter local comic is laid out on disk.
 *
 * The [dbName] goes into `local_index.format`, beside the three names the `local` area
 * already writes, so these are an on-disk contract and must not be renamed casually. The
 * three single-chapter shapes deliberately keep `local`'s own names: a flat .cbz imported
 * through this screen must produce a row byte-for-byte identical to one imported through
 * the older one, or the two screens would disagree about the same file.
 *
 * Nothing about the chapter *structure* is stored. `local_index` has no column for it,
 * and inventing a side table would make the row and the table able to disagree. The
 * structure is re-derived from the container on every read, which also means a chapter
 * the user adds to a folder appears without a re-import.
 */
enum class LocalxFormat(val dbName: String, val label: String) {

	CBZ(LocalFormat.CBZ.name, "Single archive"),
	ZIP(LocalFormat.ZIP.name, "Single archive"),
	DIRECTORY(LocalFormat.DIRECTORY.name, "Folder of images"),

	/** One archive whose entries are grouped into chapter directories. */
	CBZ_CHAPTERS("CBZ_CHAPTERS", "Chapter folders inside one archive"),

	/** A folder whose sub-folders are chapters. */
	DIRECTORY_CHAPTERS("DIRECTORY_CHAPTERS", "Folder of chapter folders"),

	/** A folder of archives read as one title, one chapter per archive. */
	ARCHIVE_SET("ARCHIVE_SET", "Folder of archives, one chapter each"),

	/** A single EPUB, one chapter per spine document. */
	EPUB("EPUB", "EPUB book"),
	;

	val isText: Boolean get() = this == EPUB

	companion object {

		fun of(dbName: String): LocalxFormat? = entries.firstOrNull { it.dbName == dbName }
	}
}

/**
 * One chapter of a locally stored title, resolved against the file system.
 *
 * [container] plus [entries] is the same addressing the `local` area already uses for a
 * page, which is what lets chapters read back through `LocalArchives` and `LocalImages`
 * without either of them learning what a chapter is.
 */
data class LocalxChapter(
	/** Container-relative and stable, so the derived chapter id survives a re-import. */
	val key: String,
	val title: String,
	val number: Float,
	val container: String,
	val entries: List<String>,
	val isText: Boolean,
	/**
	 * Whether the shell's own local reader can open this chapter.
	 *
	 * True when listing [container] in full yields exactly [entries], which is what
	 * `LocalFeature.pages` does. False for a chapter that is a subset of its container,
	 * and for text, both of which this area has to read itself.
	 */
	val isShellReadable: Boolean,
) {

	val pageCount: Int get() = entries.size

	/** The page urls of this chapter, in reading order. */
	fun pageUrls(): List<String> = entries.map { LocalPageRef(container, it).encode() }
}

/** What one archive holds, from a single open. */
class ArchiveContents(
	val entries: List<String>,
	val comicInfo: ComicInfo?,
)

/**
 * Reads an archive's entry names and its `ComicInfo.xml` in one open.
 *
 * One open rather than two because the importer wants both for every file in a folder,
 * and opening a hundred archives twice to show a preview is a visible pause.
 */
fun readArchive(file: File): ArchiveContents {
	val zip = try {
		ZipFile(file)
	} catch (e: IOException) {
		throw LocalImportException(
			"${file.name}: not a readable zip archive (${e.message ?: e::class.simpleName})",
		)
	}
	return zip.use {
		val names = ArrayList<String>()
		var info: ComicInfo? = null
		for (entry in zip.entries()) {
			if (entry.isDirectory) continue
			val name = normalizeEntry(entry.name)
			if (name.isEmpty()) continue
			if (info == null && ComicInfoReader.isComicInfo(name)) {
				info = ComicInfoReader.parse(zip.getInputStream(entry).use { stream -> stream.readBytes() })
			}
			names += name
		}
		ArchiveContents(names, info)
	}
}

/**
 * Entry paths under [root], relative to it, the way an archive would list them.
 *
 * Bounded depth so the same layout rules apply to a folder and to an archive, and so a
 * symlink pointing at an ancestor cannot walk forever. Three levels is one more than any
 * layout this area recognises, which is what lets detection see an over-nested folder and
 * call it ambiguous instead of silently flattening it.
 */
fun listDirectoryEntries(root: File, maxDepth: Int = 3): List<String> {
	val prefix = root.path.length + 1
	return root.walkTopDown()
		.maxDepth(maxDepth)
		.filter { it.isFile }
		.map { normalizeEntry(it.path.substring(prefix)) }
		.filter { it.isNotEmpty() }
		.toList()
}

/** The `ComicInfo.xml` of a folder of loose pages, if it has one. */
fun readDirectoryComicInfo(root: File): ComicInfo? {
	val file = (root.listFiles() ?: return null)
		.firstOrNull { it.isFile && ComicInfoReader.isComicInfo(it.name) }
		?: return null
	return try {
		ComicInfoReader.parse(file.readBytes())
	} catch (e: IOException) {
		// Unreadable metadata is absent metadata; see ComicInfoReader.
		null
	}
}

/**
 * The chapters of a stored row, re-derived from what is on disk right now.
 *
 * Throws [LocalImportException] when the container has gone or stopped being readable,
 * which is a thing the user has to be told rather than an empty chapter list.
 */
suspend fun readChapters(entity: LocalMangaEntity): List<LocalxChapter> = withContext(Dispatchers.IO) {
	val file = File(entity.path)
	if (!file.exists()) {
		throw LocalImportException("${file.name}: no longer exists")
	}
	val format = LocalxFormat.of(entity.format)
		?: throw LocalImportException("${file.name}: unknown layout \"${entity.format}\"")
	chaptersFor(file, format)
}

/** The chapters [file] holds under [format]. Blocking; call it off the UI thread. */
fun chaptersFor(file: File, format: LocalxFormat): List<LocalxChapter> = when (format) {
	LocalxFormat.CBZ, LocalxFormat.ZIP -> {
		val contents = readArchive(file)
		val entries = contents.entries.filter(::isImageEntry).filterNot(::isPackagingJunk)
		// The metadata names the chapter here rather than at import time, because the
		// chapter list is re-derived on every read and a name applied only once would
		// vanish the first time the title was reopened.
		listOf(
			wholeChapter(
				file = file,
				entries = entries.sortedWith(NaturalOrder),
				title = contents.comicInfo?.chapterTitle() ?: file.nameWithoutExtension,
			),
		)
	}

	LocalxFormat.DIRECTORY -> {
		val entries = (file.listFiles() ?: emptyArray())
			.filter(LocalArchives::isImage)
			.map { it.name }
			.sortedWith(NaturalOrder)
		listOf(
			wholeChapter(
				file = file,
				entries = entries,
				title = readDirectoryComicInfo(file)?.chapterTitle() ?: file.name,
			),
		)
	}

	LocalxFormat.CBZ_CHAPTERS -> archiveChapters(file)

	LocalxFormat.DIRECTORY_CHAPTERS -> directoryChapters(file)

	LocalxFormat.ARCHIVE_SET -> archiveSetChapters(file)

	LocalxFormat.EPUB -> epubChapters(file)
}

private fun wholeChapter(file: File, entries: List<String>, title: String): LocalxChapter {
	if (entries.isEmpty()) {
		throw LocalImportException("${file.name}: contains no images")
	}
	return LocalxChapter(
		key = "",
		title = title,
		number = 1f,
		container = file.absolutePath,
		entries = entries,
		isText = false,
		isShellReadable = true,
	)
}

private fun archiveChapters(file: File): List<LocalxChapter> {
	// Detection is not re-run here. The format string is the record of what the user was
	// shown and confirmed at import time; re-deciding on every read would let a chapter
	// list change shape because somebody dropped a file next to the pages.
	val chapters = groupByDirectory(readArchive(file).entries)
	if (chapters.isEmpty()) {
		throw LocalImportException("${file.name}: contains no images")
	}
	return chapters.mapIndexed { index, chapter ->
		LocalxChapter(
			key = chapter.key,
			title = chapter.title.ifEmpty { file.nameWithoutExtension },
			number = (index + 1).toFloat(),
			container = file.absolutePath,
			entries = chapter.entries,
			isText = false,
			// A chapter inside an archive is a subset of it, and the shell's reader lists
			// the whole archive; see LocalxChapter.isShellReadable.
			isShellReadable = chapters.size == 1,
		)
	}
}

private fun directoryChapters(root: File): List<LocalxChapter> {
	val chapters = groupByDirectory(listDirectoryEntries(root))
	if (chapters.isEmpty()) {
		throw LocalImportException("${root.name}: folder contains no images")
	}
	return chapters.mapIndexed { index, chapter ->
		// The container is the sub-folder itself, so the entries are plain file names and
		// `LocalArchives.readPage` accepts them: it refuses anything below the folder it
		// was handed, which is exactly the guard that would reject "Chapter 1/001.png".
		val container = if (chapter.key.isEmpty()) root else File(root, chapter.key)
		val prefix = if (chapter.key.isEmpty()) "" else chapter.key + "/"
		LocalxChapter(
			key = chapter.key,
			title = chapter.title.ifEmpty { root.name },
			number = (index + 1).toFloat(),
			container = container.absolutePath,
			entries = chapter.entries.map { it.removePrefix(prefix) },
			isText = false,
			isShellReadable = true,
		)
	}
}

private fun archiveSetChapters(root: File): List<LocalxChapter> {
	val archives = LocalArchives.archivesIn(root)
	if (archives.isEmpty()) {
		throw LocalImportException("${root.name}: folder holds no archives")
	}
	return archives.mapIndexed { index, archive ->
		val contents = readArchive(archive)
		val entries = contents.entries
			.filter(::isImageEntry)
			.filterNot(::isPackagingJunk)
			.sortedWith(NaturalOrder)
		LocalxChapter(
			key = archive.name,
			title = contents.comicInfo?.chapterTitle() ?: archive.nameWithoutExtension,
			number = (index + 1).toFloat(),
			container = archive.absolutePath,
			entries = entries,
			isText = false,
			isShellReadable = true,
		)
	}
}

private fun epubChapters(file: File): List<LocalxChapter> =
	EpubReader.read(file).chapters.mapIndexed { index, chapter ->
		LocalxChapter(
			key = chapter.href,
			title = chapter.title,
			number = (index + 1).toFloat(),
			container = file.absolutePath,
			entries = listOf(chapter.href),
			isText = true,
			isShellReadable = false,
		)
	}

/**
 * The [Manga] a stored row describes, carrying [chapters].
 *
 * Shares [localId] with the `local` area so a title imported through either screen is the
 * same title to history and bookmarks. Only the chapter list differs, which is the whole
 * point of this area.
 */
fun localxManga(entity: LocalMangaEntity, chapters: List<LocalxChapter>): Manga = Manga(
	id = localId(entity.path),
	title = entity.title,
	altTitles = emptySet(),
	url = entity.path,
	publicUrl = entity.path,
	rating = -1f,
	contentRating = null,
	coverUrl = coverUrlOf(entity),
	largeCoverUrl = null,
	tags = emptySet(),
	state = null,
	authors = emptySet(),
	description = null,
	chapters = chapters.map { localxMangaChapter(entity, it) },
	source = LocalMangaSource,
)

/**
 * The cover reference for a row, or null when there is nothing decodable to point at.
 *
 * A cover entry that names a file in a sub-folder cannot be addressed against the title's
 * own folder, because `LocalArchives` refuses to read below the container it was given.
 * Splitting it at the last separator points the reference at the sub-folder instead,
 * which is the same file and a reference that resolves.
 */
fun coverUrlOf(entity: LocalMangaEntity): String? {
	val entry = entity.coverEntry ?: return null
	val format = LocalxFormat.of(entity.format) ?: return null
	if (format.isText) return null
	val nested = entry.contains('/')
	return when {
		format == LocalxFormat.DIRECTORY_CHAPTERS && nested -> LocalPageRef(
			container = File(entity.path, entry.substringBeforeLast('/')).absolutePath,
			entry = entry.substringAfterLast('/'),
		)

		format == LocalxFormat.ARCHIVE_SET && nested -> LocalPageRef(
			container = File(entity.path, entry.substringBeforeLast('/')).absolutePath,
			entry = entry.substringAfterLast('/'),
		)

		else -> LocalPageRef(entity.path, entry)
	}.encode()
}

/** The chapter id a row plus a chapter key always produces. */
fun localxChapterId(entity: LocalMangaEntity, key: String): Long = localId(entity.path + "#" + key)

private fun localxMangaChapter(entity: LocalMangaEntity, chapter: LocalxChapter): MangaChapter = MangaChapter(
	id = localxChapterId(entity, chapter.key),
	title = chapter.title,
	number = chapter.number,
	volume = 0,
	// The shell's reader resolves pages by listing this path, so it has to be the
	// container and not the title, or a chapter would read its neighbours' pages.
	url = chapter.container,
	scanlator = null,
	uploadDate = entity.addedAt,
	branch = null,
	source = LocalMangaSource,
)

/** The pages of one resolved chapter, for this area's own reader. */
fun localxPages(chapter: LocalxChapter): List<MangaPage> = chapter.pageUrls().map { url ->
	MangaPage(id = localId(url), url = url, preview = null, source = LocalMangaSource)
}
