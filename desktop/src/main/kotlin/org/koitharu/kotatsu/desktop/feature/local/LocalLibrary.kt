package org.koitharu.kotatsu.desktop.feature.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.shared.db.LocalLibraryDao
import org.koitharu.kotatsu.shared.db.LocalMangaEntity
import java.io.File
import java.io.IOException

/**
 * The source a comic read off disk belongs to.
 *
 * Same name as the Android app's `LocalMangaSource`, so a `local_index` row and a
 * `manga.source` column written by either app mean the same thing (DECISIONS.md D15).
 * Declared here rather than reused because the Android declaration sits in `:app`
 * alongside Mihon and LNReader types that desktop does not have.
 */
data object LocalMangaSource : MangaSource {

	override val name = "LOCAL"
}

/** One comic that failed to import, with a message fit to show the user. */
data class ImportFailure(val path: String, val reason: String)

/**
 * The outcome of one import gesture.
 *
 * Both lists, not a success/failure choice: picking a folder of thirty archives where
 * two are truncated must import twenty-eight and say so, not abort.
 */
data class ImportReport(
	val imported: List<LocalMangaEntity>,
	val failures: List<ImportFailure>,
) {

	val isEmpty: Boolean get() = imported.isEmpty() && failures.isEmpty()

	/** One line for the screen. */
	fun summary(): String = when {
		isEmpty -> "Nothing selected."
		failures.isEmpty() -> "Imported ${count(imported.size)}."
		imported.isEmpty() -> failures.joinToString("\n") { it.reason }
		else -> "Imported ${count(imported.size)}. " +
			"${failures.size} failed:\n" + failures.joinToString("\n") { it.reason }
	}

	private fun count(n: Int): String = if (n == 1) "1 comic" else "$n comics"
}

/**
 * The imported-comics index.
 *
 * Owns `local_index` and nothing else. Files on disk are read but never written, moved
 * or deleted: this is an index over the user's own library, and an import tool that can
 * destroy the thing it indexes is a different, worse product.
 */
class LocalLibrary(
	private val dao: LocalLibraryDao,
	private val now: () -> Long = System::currentTimeMillis,
) {

	fun observeAll(): Flow<List<LocalMangaEntity>> = dao.observeAll()

	suspend fun find(id: Long): LocalMangaEntity? = dao.find(id)

	/**
	 * Imports everything under [targets].
	 *
	 * Runs on IO: it opens archives and hits the database, and it is started from a
	 * click.
	 */
	suspend fun importAll(targets: List<File>): ImportReport = withContext(Dispatchers.IO) {
		val imported = ArrayList<LocalMangaEntity>()
		val failures = ArrayList<ImportFailure>()
		for (candidate in targets.flatMap(::expand)) {
			try {
				imported += importOne(candidate)
			} catch (e: LocalImportException) {
				failures += ImportFailure(candidate.absolutePath, e.message ?: "could not be imported")
			} catch (e: IOException) {
				failures += ImportFailure(
					candidate.absolutePath,
					"${candidate.name}: ${e.message ?: e::class.simpleName}",
				)
			}
		}
		ImportReport(imported, failures)
	}

	suspend fun import(target: File): ImportReport = importAll(listOf(target))

	/** Forgets a comic. The file it points at is left exactly where it is. */
	suspend fun remove(entity: LocalMangaEntity) = dao.delete(entity.id)

	/**
	 * What one selected path actually contains.
	 *
	 * A folder is either one comic (it holds images) or a batch (it holds archives). A
	 * folder holding neither is returned unchanged so it produces one clear failure
	 * instead of vanishing from the report.
	 */
	private fun expand(target: File): List<File> {
		if (!target.isDirectory) return listOf(target)
		val children = target.listFiles() ?: return listOf(target)
		if (children.any(LocalArchives::isImage)) return listOf(target)
		val archives = LocalArchives.archivesIn(target)
		return archives.ifEmpty { listOf(target) }
	}

	private suspend fun importOne(file: File): LocalMangaEntity {
		if (!file.exists()) {
			throw LocalImportException("${file.name}: no longer exists")
		}
		val format = LocalArchives.detect(file)
			?: throw LocalImportException("${file.name}: not a .cbz, a .zip or a folder")
		// Read the container before touching the database. A row written first and rolled
		// back on failure is the shape that leaves orphans behind when the failure is a
		// crash rather than an exception.
		val pages = LocalArchives.listPages(file, format)
		val path = file.absolutePath
		val existing = dao.findByPath(path)
		val entity = LocalMangaEntity(
			// The unique index on `path` makes the upsert an update, but only if the row
			// carries the existing id; a zero id would insert and hit the constraint.
			id = existing?.id ?: 0L,
			path = path,
			title = titleOf(file, format),
			coverEntry = pages.first(),
			format = format.name,
			chaptersCount = 1,
			sizeBytes = LocalArchives.sizeOf(file, format),
			// Re-importing is a refresh, not a new acquisition, so the original date stands.
			addedAt = existing?.addedAt ?: now(),
		)
		dao.upsert(entity)
		// @Upsert returns -1 for the update branch, so the generated id is read back
		// rather than taken from the return value.
		return dao.findByPath(path) ?: entity
	}

	private fun titleOf(file: File, format: LocalFormat): String = when (format) {
		LocalFormat.DIRECTORY -> file.name
		LocalFormat.CBZ, LocalFormat.ZIP -> file.nameWithoutExtension
	}.ifBlank { file.absolutePath }
}

/**
 * The [Manga] a stored row describes.
 *
 * A pure function of the row, so it can be rebuilt from history or from the grid without
 * a database round trip and always yields the same [Manga.id].
 */
fun mangaOf(entity: LocalMangaEntity): Manga = Manga(
	id = localId(entity.path),
	title = entity.title,
	altTitles = emptySet(),
	url = entity.path,
	publicUrl = entity.path,
	rating = -1f,
	contentRating = null,
	coverUrl = entity.coverEntry?.let { LocalPageRef(entity.path, it).encode() },
	largeCoverUrl = null,
	tags = emptySet(),
	state = null,
	authors = emptySet(),
	description = null,
	chapters = chaptersOf(entity),
	source = LocalMangaSource,
)

/**
 * The chapters of a stored row.
 *
 * Always one. A `.cbz` is one volume of pages with no chapter boundaries recorded
 * anywhere in the format, and inventing boundaries from filenames guesses wrong on every
 * scanlation naming scheme. Multi-chapter local comics are out of v1 scope.
 */
fun chaptersOf(entity: LocalMangaEntity): List<MangaChapter> = listOf(
	MangaChapter(
		id = localId(entity.path + CHAPTER_KEY),
		title = entity.title,
		number = 1f,
		volume = 0,
		url = entity.path,
		scanlator = null,
		uploadDate = entity.addedAt,
		branch = null,
		source = LocalMangaSource,
	),
)

/**
 * The pages of a local chapter, read from its container.
 *
 * This is what the shell's local reader calls in place of `MangaParser.getPages`: a
 * local comic has no parser, so the page list comes from the archive directory.
 */
suspend fun pagesOf(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
	val file = File(chapter.url)
	val format = LocalArchives.detect(file)
		?: throw LocalImportException("${file.name}: no longer a readable comic")
	LocalArchives.listPages(file, format).map { entry ->
		val ref = LocalPageRef(chapter.url, entry)
		val url = ref.encode()
		MangaPage(id = localId(url), url = url, preview = null, source = LocalMangaSource)
	}
}

/**
 * A stable 64-bit id for a local key.
 *
 * FNV-1a over the path, so the same file always produces the same [Manga.id] across
 * restarts and across a re-import. That matters because history and bookmarks key on
 * that id; deriving it from the autoincrement row id instead would orphan a title's
 * reading position the first time its row was deleted and re-added.
 */
fun localId(key: String): Long {
	var hash = FNV_OFFSET
	for (byte in key.encodeToByteArray()) {
		hash = hash xor (byte.toLong() and 0xFF)
		hash *= FNV_PRIME
	}
	// Zero is the "no id" value everywhere else in this schema.
	return if (hash == 0L) 1L else hash
}

private const val CHAPTER_KEY = "#chapter-1"

private const val FNV_OFFSET: Long = -0x340d631b7bdddcdbL

private const val FNV_PRIME: Long = 0x100000001b3L
