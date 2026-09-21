package org.koitharu.kotatsu.desktop.feature.localx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.local.ImportFailure
import org.koitharu.kotatsu.desktop.feature.local.ImportReport
import org.koitharu.kotatsu.desktop.feature.local.LocalArchives
import org.koitharu.kotatsu.desktop.feature.local.LocalImportException
import org.koitharu.kotatsu.desktop.feature.local.NaturalOrder
import org.koitharu.kotatsu.shared.db.LocalLibraryDao
import org.koitharu.kotatsu.shared.db.LocalMangaEntity
import java.io.File
import java.io.IOException

/** One title the importer proposes to add, fully resolved but not yet written down. */
data class ImportCandidate(
	val file: File,
	val format: LocalxFormat,
	val title: String,
	val chapters: List<LocalxChapter>,
	val coverEntry: String?,
	val sizeBytes: Long,
	val metadata: ComicInfo?,
) {

	val pageCount: Int get() = chapters.sumOf { it.pageCount }

	/** "12 chapters, 340 pages", or the text equivalent for a book. */
	fun describe(): String = when {
		format.isText -> "${chapters.size} ${plural(chapters.size, "chapter")} of text"
		chapters.size == 1 -> "1 chapter, $pageCount ${plural(pageCount, "page")}"
		else -> "${chapters.size} chapters, $pageCount pages"
	}
}

/**
 * One way a selected path could be imported.
 *
 * A list rather than a single answer because the honest answer is sometimes "either of
 * these". A folder of .cbz files is a series of chapters to one user and a shelf of
 * separate books to the next, and nothing in the folder says which; the older import
 * path picked one silently, and this one asks.
 */
data class ImportOption(
	val key: String,
	val label: String,
	val candidates: List<ImportCandidate>,
) {

	fun describe(): String = when (candidates.size) {
		0 -> "nothing"
		1 -> candidates.single().describe()
		else -> "${candidates.size} titles, " +
			"${candidates.sumOf { it.chapters.size }} chapters, " +
			"${candidates.sumOf { it.pageCount }} pages"
	}
}

/**
 * What one selected path turned out to be.
 *
 * Nothing is written until the user has seen this, which is the whole difference between
 * this screen and the older one: chapter detection is a judgement call, and a judgement
 * call made silently on somebody's library is a bug they find months later.
 */
sealed interface ImportPreview {

	val source: File

	data class Ready(
		override val source: File,
		/** What the layout was found to be, in words. */
		val layout: String,
		val options: List<ImportOption>,
		/** What was ignored, assumed, or could not be decided. */
		val notes: List<String>,
	) : ImportPreview

	data class Rejected(override val source: File, val reason: String) : ImportPreview
}

/**
 * Turns selected paths into a reviewable plan, then writes the plan down.
 *
 * Writes only `local_index`, and only rows; files on disk are read and never touched,
 * exactly as in the `local` area this builds on.
 */
class LocalxImporter(
	private val dao: LocalLibraryDao,
	private val now: () -> Long = System::currentTimeMillis,
) {

	/** Looks at every selected path without writing anything. */
	suspend fun inspect(targets: List<File>): List<ImportPreview> = withContext(Dispatchers.IO) {
		targets.map { target ->
			try {
				inspectOne(target)
			} catch (e: LocalImportException) {
				ImportPreview.Rejected(target, e.message ?: "${target.name}: could not be read")
			} catch (e: IOException) {
				ImportPreview.Rejected(target, "${target.name}: ${e.message ?: e::class.simpleName}")
			}
		}
	}

	/** Writes the chosen candidates. Partial failure imports the rest and reports it. */
	suspend fun commit(candidates: List<ImportCandidate>): ImportReport = withContext(Dispatchers.IO) {
		val imported = ArrayList<LocalMangaEntity>(candidates.size)
		val failures = ArrayList<ImportFailure>()
		for (candidate in candidates) {
			try {
				imported += write(candidate)
			} catch (e: LocalImportException) {
				failures += ImportFailure(candidate.file.absolutePath, e.message ?: "could not be imported")
			} catch (e: IOException) {
				failures += ImportFailure(
					candidate.file.absolutePath,
					"${candidate.file.name}: ${e.message ?: e::class.simpleName}",
				)
			}
		}
		ImportReport(imported, failures)
	}

	private suspend fun write(candidate: ImportCandidate): LocalMangaEntity {
		if (candidate.chapters.isEmpty()) {
			throw LocalImportException("${candidate.file.name}: nothing to import")
		}
		val path = candidate.file.absolutePath
		val existing = dao.findByPath(path)
		val entity = LocalMangaEntity(
			// The unique index on `path` makes the upsert an update, but only if the row
			// carries the existing id; a zero id would insert and hit the constraint.
			id = existing?.id ?: 0L,
			path = path,
			title = candidate.title,
			coverEntry = candidate.coverEntry,
			format = candidate.format.dbName,
			chaptersCount = candidate.chapters.size,
			sizeBytes = candidate.sizeBytes,
			// Re-importing is a refresh, not a new acquisition, so the original date stands.
			addedAt = existing?.addedAt ?: now(),
		)
		dao.upsert(entity)
		// @Upsert returns -1 for the update branch, so the generated id is read back.
		return dao.findByPath(path) ?: entity
	}

	private fun inspectOne(target: File): ImportPreview {
		if (!target.exists()) {
			return ImportPreview.Rejected(target, "${target.name}: no longer exists")
		}
		return when {
			EpubReader.isEpub(target) -> epubPreview(target)
			target.isDirectory -> folderPreview(target)
			LocalArchives.isArchive(target) -> archivePreview(target)
			else -> ImportPreview.Rejected(
				target,
				"${target.name}: not a .cbz, a .zip, an .epub or a folder",
			)
		}
	}

	private fun epubPreview(file: File): ImportPreview {
		val book = EpubReader.read(file)
		val chapters = chaptersFor(file, LocalxFormat.EPUB)
		val notes = ArrayList<String>()
		book.title?.let { notes += "The book calls itself \"$it\"." }
		if (book.authors.isNotEmpty()) {
			notes += "By ${book.authors.joinToString(", ")}."
		}
		val candidate = ImportCandidate(
			file = file,
			format = LocalxFormat.EPUB,
			title = book.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
			chapters = chapters,
			// An EPUB cover is inside a container `LocalArchives` does not recognise, so
			// there is nothing the grid could decode; see coverUrlOf.
			coverEntry = null,
			sizeBytes = file.length(),
			metadata = null,
		)
		return ImportPreview.Ready(
			source = file,
			layout = LocalxFormat.EPUB.label,
			options = listOf(ImportOption("epub", "Import as a book", listOf(candidate))),
			notes = notes,
		)
	}

	private fun archivePreview(file: File): ImportPreview {
		val contents = readArchive(file)
		val detected = detectEntryLayout(contents.entries)
		val single = LocalxFormat.of(
			if (file.extension.equals("zip", ignoreCase = true)) "ZIP" else "CBZ",
		) ?: LocalxFormat.CBZ
		val notes = ArrayList<String>()
		contents.comicInfo?.let { notes += describeMetadata(it) }

		return when (detected) {
			is EntryLayout.NotAComic -> ImportPreview.Rejected(file, "${file.name}: ${detected.reason}")

			is EntryLayout.Ambiguous -> {
				notes += "Chapter folders were not used: " + detected.reason
				notes += detected.notes
				ImportPreview.Ready(
					source = file,
					layout = "Unclear, importing as one chapter",
					options = listOf(
						ImportOption(
							key = "single",
							label = "One chapter",
							candidates = listOf(archiveCandidate(file, single, contents)),
						),
					),
					notes = notes,
				)
			}

			is EntryLayout.Detected -> {
				notes += detected.notes
				val options = if (detected.kind == EntryLayoutKind.CHAPTER_DIRECTORIES) {
					listOf(
						ImportOption(
							key = "chapters",
							label = "${detected.chapters.size} chapters, one per folder inside the archive",
							candidates = listOf(archiveCandidate(file, LocalxFormat.CBZ_CHAPTERS, contents)),
						),
						ImportOption(
							key = "single",
							label = "One chapter of everything",
							candidates = listOf(archiveCandidate(file, single, contents)),
						),
					)
				} else {
					listOf(
						ImportOption(
							key = "single",
							label = "One chapter",
							candidates = listOf(archiveCandidate(file, single, contents)),
						),
					)
				}
				ImportPreview.Ready(
					source = file,
					layout = options.first().candidates.single().format.label,
					options = options,
					notes = notes,
				)
			}
		}
	}

	private fun folderPreview(root: File): ImportPreview {
		val entries = listDirectoryEntries(root)
		val images = entries.filter(::isImageEntry).filterNot(::isPackagingJunk)
		val archives = LocalArchives.archivesIn(root)
		val books = (root.listFiles() ?: emptyArray()).filter(EpubReader::isEpub)
			.sortedWith(compareBy(NaturalOrder) { it.name })
		val notes = ArrayList<String>()

		if (images.isEmpty()) {
			if (archives.isNotEmpty()) return archiveSetPreview(root, archives, notes)
			if (books.isNotEmpty()) return bookShelfPreview(root, books)
			return ImportPreview.Rejected(root, "${root.name}: folder holds no comics")
		}
		if (archives.isNotEmpty()) {
			notes += "${archives.size} ${plural(archives.size, "archive")} in this folder " +
				"${if (archives.size == 1) "was" else "were"} left out; the loose images were imported instead."
		}
		val detected = detectEntryLayout(entries)
		val comicInfo = readDirectoryComicInfo(root)
		comicInfo?.let { notes += describeMetadata(it) }
		val title = comicInfo?.seriesTitle() ?: root.name

		return when (detected) {
			is EntryLayout.NotAComic -> ImportPreview.Rejected(root, "${root.name}: ${detected.reason}")

			is EntryLayout.Ambiguous -> {
				notes += "Chapter folders were not used: " + detected.reason
				notes += detected.notes
				val nested = images.count { it.contains('/') }
				val options = ArrayList<ImportOption>(2)
				options += ImportOption(
					key = "chapters",
					label = "One chapter per sub-folder",
					candidates = listOf(folderCandidate(root, LocalxFormat.DIRECTORY_CHAPTERS, title, comicInfo)),
				)
				if (images.size > nested) {
					options += ImportOption(
						key = "loose",
						label = "Only the ${images.size - nested} images at the top level, as one chapter",
						candidates = listOf(folderCandidate(root, LocalxFormat.DIRECTORY, title, comicInfo)),
					)
				}
				ImportPreview.Ready(root, "Unclear, choose below", options, notes)
			}

			is EntryLayout.Detected -> {
				notes += detected.notes
				// DIRECTORY means what it means in the `local` area: the images sitting
				// directly in the folder. A folder whose images are all one level down has
				// no pages by that definition, so even its single chapter is a sub-folder
				// chapter and has to be recorded as one.
				val format = if (images.any { it.contains('/') }) {
					LocalxFormat.DIRECTORY_CHAPTERS
				} else {
					LocalxFormat.DIRECTORY
				}
				val candidate = folderCandidate(root, format, title, comicInfo)
				val label = if (candidate.chapters.size == 1) {
					"One chapter"
				} else {
					"${candidate.chapters.size} chapters, one per sub-folder"
				}
				ImportPreview.Ready(
					source = root,
					layout = if (candidate.chapters.size == 1) "One chapter" else format.label,
					options = listOf(ImportOption("detected", label, listOf(candidate))),
					notes = notes,
				)
			}
		}
	}

	/** A folder of archives: the one case where the user, not the layout, decides. */
	private fun archiveSetPreview(
		root: File,
		archives: List<File>,
		notes: MutableList<String>,
	): ImportPreview {
		val asOne = folderCandidate(
			root = root,
			format = LocalxFormat.ARCHIVE_SET,
			title = firstSeriesTitle(archives) ?: root.name,
			metadata = null,
		)
		val separate = archives.mapNotNull { archive ->
			// A broken file among thirty must not take the other twenty-nine with it, so
			// it drops out of the plan here and is reported as a note.
			try {
				val contents = readArchive(archive)
				archiveCandidate(
					archive,
					if (archive.extension.equals("zip", ignoreCase = true)) LocalxFormat.ZIP else LocalxFormat.CBZ,
					contents,
				)
			} catch (e: LocalImportException) {
				notes += e.message ?: "${archive.name}: could not be read"
				null
			}
		}
		return ImportPreview.Ready(
			source = root,
			layout = "Folder of ${archives.size} archives",
			options = listOf(
				ImportOption("one-title", "One title, one chapter per archive", listOf(asOne)),
				ImportOption("separate", "A separate title per archive", separate),
			),
			notes = notes,
		)
	}

	private fun bookShelfPreview(root: File, books: List<File>): ImportPreview {
		val notes = ArrayList<String>()
		val candidates = books.mapNotNull { book ->
			try {
				(epubPreview(book) as? ImportPreview.Ready)?.options?.first()?.candidates?.single()
			} catch (e: LocalImportException) {
				notes += e.message ?: "${book.name}: could not be read"
				null
			}
		}
		return ImportPreview.Ready(
			source = root,
			layout = "Folder of ${books.size} EPUB ${plural(books.size, "book")}",
			options = listOf(ImportOption("books", "A separate book per file", candidates)),
			notes = notes,
		)
	}

	private fun archiveCandidate(
		file: File,
		format: LocalxFormat,
		contents: ArchiveContents,
	): ImportCandidate {
		val chapters = chaptersFor(file, format)
		val info = contents.comicInfo
		return ImportCandidate(
			file = file,
			format = format,
			// The series name is what belongs in a library grid; the issue number belongs
			// on the chapter, and folding it in would give a shelf twelve near-duplicates.
			title = info?.seriesTitle()
				?: info?.title?.takeIf { it.isNotBlank() }
				?: file.nameWithoutExtension,
			chapters = chapters,
			coverEntry = coverEntryFor(file, chapters),
			sizeBytes = file.length(),
			metadata = info,
		)
	}

	private fun folderCandidate(
		root: File,
		format: LocalxFormat,
		title: String,
		metadata: ComicInfo?,
	): ImportCandidate {
		val chapters = chaptersFor(root, format)
		return ImportCandidate(
			file = root,
			format = format,
			title = title,
			chapters = chapters,
			coverEntry = coverEntryFor(root, chapters),
			sizeBytes = folderSize(root),
			metadata = metadata,
		)
	}

	private fun firstSeriesTitle(archives: List<File>): String? = archives.firstNotNullOfOrNull { archive ->
		try {
			readArchive(archive).comicInfo?.seriesTitle()
		} catch (e: LocalImportException) {
			// Unreadable here means unreadable in the plan too, where it is reported.
			null
		}
	}
}

/** What the metadata added, in one line for the confirmation panel. */
fun describeMetadata(info: ComicInfo): String {
	val parts = ArrayList<String>(4)
	info.series?.let { parts += "series \"$it\"" }
	info.number?.let { parts += "number $it" }
	info.writer?.let { parts += "writer $it" }
	val genres = info.genres()
	if (genres.isNotEmpty()) {
		parts += "${genres.size} ${plural(genres.size, "genre")}"
	}
	return if (parts.isEmpty()) {
		"ComicInfo.xml found but empty; names come from the files."
	} else {
		"ComicInfo.xml: " + parts.joinToString(", ") + "."
	}
}

/**
 * The first page of a title, expressed relative to the title's own path.
 *
 * Relative to the title rather than to the chapter's container because that is what
 * `local_index.cover_entry` holds for the rows the older screen writes, and the two
 * screens read each other's rows.
 */
fun coverEntryFor(root: File, chapters: List<LocalxChapter>): String? {
	val chapter = chapters.firstOrNull { !it.isText && it.entries.isNotEmpty() } ?: return null
	val entry = chapter.entries.first()
	val rootPath = root.absolutePath
	val container = chapter.container
	if (container == rootPath) return entry
	val relative = container.removePrefix(rootPath + File.separator).replace(File.separatorChar, '/')
	return "$relative/$entry"
}

/** Total bytes under [root], for display only. */
fun folderSize(root: File): Long = root.walkTopDown().maxDepth(3).filter { it.isFile }.sumOf { it.length() }
