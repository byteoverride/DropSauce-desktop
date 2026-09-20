package org.koitharu.kotatsu.desktop.feature.sync

import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import org.koitharu.kotatsu.shared.settings.ReadingMode
import org.koitharu.kotatsu.shared.settings.SettingsData
import org.koitharu.kotatsu.shared.settings.SettingsStore
import org.koitharu.kotatsu.shared.settings.ThemeMode
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** How a restore treats rows that already exist locally. */
enum class RestoreMode {

	/** Default. Adds and updates; nothing local is deleted. */
	Merge,

	/**
	 * Empties the tables this backup owns first, so the result is exactly the file.
	 *
	 * The `manga` table is deliberately *not* emptied. Its rows are referenced by tracks,
	 * downloads and stats, which belong to other feature areas, and a cascade delete would
	 * silently destroy their data. Manga rows are upserted, so nothing stale survives that
	 * the backup actually mentions.
	 */
	Replace,
}

/** Coarse progress, for a label and a bar. */
data class BackupProgress(val step: String, val done: Int, val total: Int)

data class BackupSummary(
	val file: Path,
	val createdAt: Long,
	val categories: Int,
	val favourites: Int,
	val history: Int,
	val bookmarks: Int,
)

data class RestoreSummary(
	val index: BackupIndex,
	val mode: RestoreMode,
	val categoriesCreated: Int,
	val categoriesMatched: Int,
	val favourites: Int,
	val favouritesSkipped: Int,
	val historyApplied: Int,
	val historySkipped: Int,
	val bookmarks: Int,
	val manga: Int,
	val settingsApplied: Boolean,
	val warnings: List<String>,
)

/**
 * Writes and reads the backup zip.
 *
 * Restore is two phases on purpose: the whole archive is read and parsed before a single
 * row is written, and then everything is written inside one writer transaction. A corrupt
 * or foreign file therefore fails during phase one, when nothing has been touched, and a
 * failure during phase two rolls back. "Validate before writing" is not a claim about
 * carefulness here, it is the structure.
 */
class BackupRepository(
	private val db: LibraryDatabase,
	private val settings: SettingsStore,
	/** Where "Back up now" writes by default, and where the last-backup marker lives. */
	private val backupDirectory: Path,
	private val fileSystem: FileSystem = FileSystem.SYSTEM,
	private val now: () -> Long = System::currentTimeMillis,
) {

	/** A timestamped name inside [backupDirectory]; the user can still choose another. */
	fun suggestedFile(): Path {
		val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now()))
		return backupDirectory / "dropsauce-backup-$stamp.zip"
	}

	/** When "Back up now" last succeeded, or null if it never has on this machine. */
	suspend fun lastBackupAt(): Long? = withContext(Dispatchers.IO) {
		val marker = backupDirectory / MARKER_FILE
		if (!fileSystem.exists(marker)) {
			return@withContext null
		}
		// A marker that has been hand-edited into nonsense is not worth failing over; the
		// screen simply shows "never".
		runCatching {
			fileSystem.source(marker).buffer().use { it.readUtf8() }.trim().toLong()
		}.getOrNull()
	}

	suspend fun createBackup(
		target: Path,
		progress: (BackupProgress) -> Unit = {},
	): BackupSummary = withContext(Dispatchers.IO) {
		val createdAt = now()
		progress(BackupProgress("Reading the library", 0, TOTAL_STEPS))
		val categories = db.favouriteCategoriesDao().getAll()
		// FavouritesDao has no "every row" query, and observeAll() collapses a title that
		// sits in two categories with GROUP BY. Walking the categories keeps both rows.
		val favourites = categories.flatMap { category ->
			db.favouritesDao().observeByCategory(category.categoryId).first()
		}
		val history = db.historyDao().observeRecent(Int.MAX_VALUE).first()
		val bookmarks = db.bookmarksDao().observeAll().first()

		fileSystem.createDirectories(target.parent ?: backupDirectory, mustCreate = false)
		val targetFile = target.toFile()
		// Written beside the target and moved into place, so a crash halfway through cannot
		// leave a truncated file where a good backup used to be.
		val partFile = File(targetFile.parentFile, targetFile.name + ".part")
		try {
			ZipOutputStream(BufferedOutputStream(FileOutputStream(partFile))).use { zip ->
				progress(BackupProgress("Writing the index", 1, TOTAL_STEPS))
				// Android writes the index as a one-element array; matching that keeps its
				// decoder, which expects a list, able to read this file.
				zip.writeArraySection(BackupSection.INDEX, listOf(BackupIndex.current(createdAt).toJson()))

				progress(BackupProgress("Writing categories", 2, TOTAL_STEPS))
				zip.writeArraySection(
					BackupSection.CATEGORIES,
					categories.map { CategoryBackup.of(it).toJson() },
				)

				progress(BackupProgress("Writing favourites", 3, TOTAL_STEPS))
				zip.writeArraySection(
					BackupSection.FAVOURITES,
					favourites.map { FavouriteBackup.of(it.favourite, it.manga).toJson() },
				)

				progress(BackupProgress("Writing history", 4, TOTAL_STEPS))
				zip.writeArraySection(
					BackupSection.HISTORY,
					history.map { HistoryBackup.of(it.history, it.manga).toJson() },
				)

				progress(BackupProgress("Writing bookmarks", 5, TOTAL_STEPS))
				zip.writeArraySection(
					BackupSection.BOOKMARKS,
					bookmarks.groupBy { it.manga.mangaId }.values.map { rows ->
						BookmarkBackup(
							manga = MangaBackup.of(rows.first().manga),
							bookmarks = rows.map { BookmarkBackup.Item.of(it.bookmark) },
						).toJson()
					},
				)

				progress(BackupProgress("Writing settings", 6, TOTAL_STEPS))
				zip.writeObjectSection(BackupSection.SETTINGS, settings.data.value.toBackupJson())
			}
			moveIntoPlace(partFile, targetFile)
		} catch (e: IOException) {
			partFile.delete()
			throw e
		}
		recordBackup(createdAt)
		progress(BackupProgress("Done", TOTAL_STEPS, TOTAL_STEPS))
		BackupSummary(
			file = target,
			createdAt = createdAt,
			categories = categories.size,
			favourites = favourites.size,
			history = history.size,
			bookmarks = bookmarks.size,
		)
	}

	/**
	 * Reads [source] back into the database.
	 *
	 * Throws [BackupFormatException] for anything wrong with the file, before writing.
	 */
	suspend fun restore(
		source: Path,
		mode: RestoreMode = RestoreMode.Merge,
		progress: (BackupProgress) -> Unit = {},
	): RestoreSummary = withContext(Dispatchers.IO) {
		progress(BackupProgress("Checking the file", 0, 2))
		val parsed = readAndValidate(source.toFile())
		progress(BackupProgress("Restoring", 1, 2))
		val summary = applyToDatabase(parsed, mode)
		val settingsApplied = parsed.settings?.let { applySettings(it) } == true
		progress(BackupProgress("Done", 2, 2))
		summary.copy(settingsApplied = settingsApplied)
	}

	// region backup writing

	private fun ZipOutputStream.writeArraySection(section: BackupSection, records: List<JsonObject>) {
		putNextEntry(ZipEntry(section.entryName))
		// Not closed: closing an OutputStreamWriter closes the ZipOutputStream under it and
		// the next entry would then fail. Flushing is what pushes the buffer through.
		val writer = OutputStreamWriter(this, Charsets.UTF_8)
		writer.append('[')
		records.forEachIndexed { index, record ->
			if (index > 0) writer.append(',')
			writeJson(record, writer)
		}
		writer.append(']')
		writer.flush()
		closeEntry()
	}

	private fun ZipOutputStream.writeObjectSection(section: BackupSection, value: JsonObject) {
		putNextEntry(ZipEntry(section.entryName))
		val writer = OutputStreamWriter(this, Charsets.UTF_8)
		writeJson(value, writer)
		writer.flush()
		closeEntry()
	}

	private fun moveIntoPlace(part: File, target: File) {
		try {
			Files.move(
				part.toPath(),
				target.toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE,
			)
		} catch (e: AtomicMoveNotSupportedException) {
			// The chosen directory may be on a filesystem that cannot do it; a plain replace
			// is still better than writing straight onto the target.
			Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
		}
	}

	private fun recordBackup(at: Long) {
		fileSystem.createDirectories(backupDirectory, mustCreate = false)
		fileSystem.sink(backupDirectory / MARKER_FILE).buffer().use { it.writeUtf8(at.toString()) }
	}

	// endregion

	// region restore, phase one: read and validate

	private class ParsedBackup(
		val index: BackupIndex,
		val categories: List<CategoryBackup>,
		val favourites: List<FavouriteBackup>,
		val history: List<HistoryBackup>,
		val bookmarks: List<BookmarkBackup>,
		val settings: Map<String, BackupPrimitive>?,
		val warnings: List<String>,
	)

	private fun readAndValidate(file: File): ParsedBackup {
		if (!file.isFile) {
			throw BackupFormatException("There is no file at ${file.absolutePath}.")
		}
		val zip = try {
			ZipFile(file)
		} catch (e: IOException) {
			throw BackupFormatException(
				"${file.name} is not a readable zip archive (${e.message ?: e::class.simpleName}).",
				e,
			)
		}
		zip.use {
			val entries = zip.entries().toList()
			val bySection = LinkedHashMap<BackupSection, ZipEntry>()
			val unknown = ArrayList<String>()
			for (entry in entries) {
				if (entry.isDirectory) continue
				val section = BackupSection.of(entry.name)
				if (section == null) unknown += entry.name else bySection[section] = entry
			}

			val indexEntry = bySection[BackupSection.INDEX]
				?: throw BackupFormatException(
					"${file.name} has no \"index\" entry, so it is not a DropSauce backup. " +
						"It contains: ${entries.joinToString { it.name }.ifEmpty { "nothing" }}",
				)
			val index = try {
				BackupIndex.fromJson(parseJson(zip.textOf(indexEntry)))
			} catch (e: BackupFormatException) {
				throw BackupFormatException("${file.name} has a damaged index entry: ${e.message}", e)
			}
			if (index.formatVersion > BackupFormat.FORMAT_VERSION) {
				throw BackupFormatException(
					"${file.name} uses backup format ${index.formatVersion}; " +
						"this build understands up to ${BackupFormat.FORMAT_VERSION}.",
				)
			}

			val warnings = ArrayList<String>()
			if (index.appId != BackupFormat.APP_ID) {
				// Not fatal. An upstream Kotatsu file has the same section shapes and is worth
				// letting through; the user just gets told where it came from.
				warnings += "This backup was written by \"${index.appId}\", not ${BackupFormat.APP_ID}."
			}
			if (unknown.isNotEmpty()) {
				warnings += "Ignored ${unknown.size} section(s) this build does not read: " +
					unknown.joinToString()
			}

			val categories = zip.records(bySection[BackupSection.CATEGORIES], "categories") {
				CategoryBackup.fromJson(it)
			}
			val favourites = zip.records(bySection[BackupSection.FAVOURITES], "favourites") {
				FavouriteBackup.fromJson(it)
			}
			val history = zip.records(bySection[BackupSection.HISTORY], "history") {
				HistoryBackup.fromJson(it)
			}
			val bookmarks = zip.records(bySection[BackupSection.BOOKMARKS], "bookmarks") {
				BookmarkBackup.fromJson(it)
			}
			val settings = bySection[BackupSection.SETTINGS]?.let { entry ->
				try {
					parseJson(zip.textOf(entry)).asObject("the settings section").fields
						.mapValues { (_, v) -> BackupPrimitive.fromJson(v.asObject("a setting")) }
				} catch (e: BackupFormatException) {
					throw BackupFormatException("The settings section is damaged: ${e.message}", e)
				}
			}

			return ParsedBackup(index, categories, favourites, history, bookmarks, settings, warnings)
		}
	}

	private fun ZipFile.textOf(entry: ZipEntry): String =
		getInputStream(entry).use { it.readBytes().decodeToString() }

	private fun <T> ZipFile.records(
		entry: ZipEntry?,
		label: String,
		map: (JsonObject) -> T,
	): List<T> {
		if (entry == null) return emptyList()
		return try {
			parseJson(textOf(entry)).asArray("the $label section").items
				.map { map(it.asObject("a $label record")) }
		} catch (e: BackupFormatException) {
			throw BackupFormatException("The $label section is damaged: ${e.message}", e)
		}
	}

	// endregion

	// region restore, phase two: write

	private suspend fun applyToDatabase(parsed: ParsedBackup, mode: RestoreMode): RestoreSummary {
		val warnings = ArrayList(parsed.warnings)
		var categoriesCreated = 0
		var categoriesMatched = 0
		var favourites = 0
		var favouritesSkipped = 0
		var historyApplied = 0
		var historySkipped = 0
		var bookmarks = 0
		val mangaSeen = HashSet<Long>()

		db.useWriterConnection { transactor ->
			transactor.immediateTransaction {
				if (mode == RestoreMode.Replace) {
					// Order matters: children before parents, even with cascades, so the intent
					// is readable rather than depending on the foreign keys firing.
					execSQL("DELETE FROM bookmarks")
					execSQL("DELETE FROM history")
					execSQL("DELETE FROM favourites")
					execSQL("DELETE FROM favourite_categories")
				}

				val categoryIds = restoreCategories(parsed.categories) { created ->
					if (created) categoriesCreated++ else categoriesMatched++
				}

				for (favourite in parsed.favourites) {
					val localCategory = categoryIds[favourite.categoryId]
					if (localCategory == null) {
						// A favourite with no category has nowhere to live: the table's primary
						// key includes category_id and its foreign key requires the row.
						favouritesSkipped++
						continue
					}
					if (upsertManga(favourite.manga)) mangaSeen += favourite.manga.id
					db.favouritesDao().upsert(favourite.toEntity(localCategory))
					favourites++
				}

				for (record in parsed.history) {
					if (upsertManga(record.manga)) mangaSeen += record.manga.id
					val existing = db.historyDao().find(record.mangaId)
					if (mode == RestoreMode.Merge && existing != null && existing.updatedAt >= record.updatedAt) {
						// Merge means the newer read position wins, not the imported one.
						historySkipped++
						continue
					}
					// The earliest known "first read" wins, so re-importing an old backup does
					// not make a title look newer than it is.
					val firstSeen = listOfNotNull(
						record.createdAt.takeIf { it > 0 },
						existing?.createdAt?.takeIf { it > 0 },
					).minOrNull() ?: record.updatedAt
					db.historyDao().upsert(record.toEntity().copy(createdAt = firstSeen))
					historyApplied++
				}

				for (record in parsed.bookmarks) {
					if (upsertManga(record.manga)) mangaSeen += record.manga.id
					for (item in record.bookmarks) {
						db.bookmarksDao().upsert(item.toEntity())
						bookmarks++
					}
				}
			}
		}

		if (favouritesSkipped > 0) {
			warnings += "$favouritesSkipped favourite(s) referenced a category the file does not define."
		}
		return RestoreSummary(
			index = parsed.index,
			mode = mode,
			categoriesCreated = categoriesCreated,
			categoriesMatched = categoriesMatched,
			favourites = favourites,
			favouritesSkipped = favouritesSkipped,
			historyApplied = historyApplied,
			historySkipped = historySkipped,
			bookmarks = bookmarks,
			manga = mangaSeen.size,
			settingsApplied = false,
			warnings = warnings,
		)
	}

	/**
	 * Maps every backed-up category id onto a local one, creating what is missing.
	 *
	 * **Categories are matched by title, trimmed and case-folded, never by id.** Two
	 * reasons, and the second is the Phase 1 trap:
	 *
	 *  1. `category_id` is `autoGenerate`, so the same category has different ids on two
	 *     installs. Reusing the stored id would either collide with an unrelated local
	 *     category or create a duplicate every time the ids happened to differ.
	 *  2. The default "Read later" category is seeded by `PrePopulateCallback` on every
	 *     fresh database and is dumped into every backup, so a restore always brings a
	 *     second copy of it. Android deals with that by *deleting* a category whose
	 *     localized title matches and which is empty
	 *     (`LocalBackupRepository.removeEmptyReadLaterCategory`), which is fragile in one
	 *     language and wrong in another. Matching on title on the way in means the
	 *     duplicate is never created, so nothing has to be deleted afterwards and no
	 *     localized string is load-bearing.
	 *
	 * The cost, stated: two backed-up categories whose titles differ only in case or
	 * surrounding space collapse into one local category, and their favourites merge.
	 * That is the same collapse the user would see in the category list, so it is the
	 * behaviour that matches what they can observe.
	 */
	private suspend fun restoreCategories(
		categories: List<CategoryBackup>,
		onResolved: (created: Boolean) -> Unit,
	): Map<Long, Long> {
		val dao = db.favouriteCategoriesDao()
		val byTitle = HashMap<String, FavouriteCategoryEntity>()
		for (existing in dao.getAll()) {
			byTitle.putIfAbsent(existing.title.categoryKey(), existing)
		}
		val mapping = HashMap<Long, Long>(categories.size)
		for (category in categories) {
			val key = category.title.categoryKey()
			val local = byTitle[key]
			if (local != null) {
				// Merge leaves a matched category's own sort key, order and visibility alone:
				// the user set those on this machine and an import is not a reason to undo
				// them. In replace mode the table was emptied first, so anything matched here
				// was created by this same restore and there is nothing to overwrite either.
				mapping[category.categoryId] = local.categoryId
				onResolved(false)
				continue
			}
			val newId = dao.insert(category.toEntity(0L))
			mapping[category.categoryId] = newId
			byTitle[key] = category.toEntity(newId)
			onResolved(true)
		}
		return mapping
	}

	/**
	 * Writes the manga row, returning true.
	 *
	 * `chapters_count` takes the larger of the two values. It is a cache, not user data, and
	 * an Android backup never carries the field at all (it is desktop-only, see
	 * [BackupFormat]), so taking the file's value blindly would zero every count in the
	 * library and break the length filter for exactly the titles that have been there
	 * longest.
	 */
	private suspend fun upsertManga(manga: MangaBackup): Boolean {
		val existing: MangaEntity? = db.mangaDao().find(manga.id)
		val count = maxOf(manga.chaptersCount, existing?.chaptersCount ?: 0)
		db.mangaDao().upsert(manga.toEntity().copy(chaptersCount = count))
		return true
	}

	// endregion

	// region settings

	private suspend fun applySettings(values: Map<String, BackupPrimitive>): Boolean {
		if (values.isEmpty()) return false
		settings.update { current -> current.withBackupValues(values) }
		return true
	}

	// endregion

	private companion object {

		const val TOTAL_STEPS = 6
		const val MARKER_FILE = ".last_backup"
	}
}

/** Trimmed and case-folded, which is how a person tells two category names apart. */
internal fun String.categoryKey(): String = trim().lowercase()

internal fun SettingsData.toBackupJson(): JsonObject = jsonObject(
	"theme" to BackupPrimitive.Str(theme.name).toJson(),
	"reading_mode" to BackupPrimitive.Str(readingMode.name).toJson(),
	"hide_adult_sources" to BackupPrimitive.Bool(hideAdultSources).toJson(),
	"hidden_source_terms" to BackupPrimitive.Strings(hiddenSourceTerms).toJson(),
	"image_cache_entries" to BackupPrimitive.Integer(imageCacheEntries).toJson(),
	"page_attempts" to BackupPrimitive.Integer(pageAttempts).toJson(),
	"user_agent" to BackupPrimitive.Str(userAgent).toJson(),
)

/**
 * Applies what the file carries, keeping the current value for anything absent or unusable.
 *
 * An unknown enum name is the realistic failure here: a backup from a later build can name
 * a reading mode this one does not have. Keeping the current value is better than refusing
 * the restore over a preference.
 */
internal fun SettingsData.withBackupValues(values: Map<String, BackupPrimitive>): SettingsData {
	fun str(key: String): String? = (values[key] as? BackupPrimitive.Str)?.value
	fun bool(key: String): Boolean? = (values[key] as? BackupPrimitive.Bool)?.value
	fun int(key: String): Int? = (values[key] as? BackupPrimitive.Integer)?.value
	return copy(
		theme = str("theme")?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: theme,
		readingMode = str("reading_mode")
			?.let { name -> ReadingMode.entries.firstOrNull { it.name == name } }
			?: readingMode,
		hideAdultSources = bool("hide_adult_sources") ?: hideAdultSources,
		hiddenSourceTerms = (values["hidden_source_terms"] as? BackupPrimitive.Strings)?.value
			?: hiddenSourceTerms,
		imageCacheEntries = int("image_cache_entries")?.takeIf { it > 0 } ?: imageCacheEntries,
		pageAttempts = int("page_attempts")?.takeIf { it > 0 } ?: pageAttempts,
		userAgent = str("user_agent")?.takeIf { it.isNotBlank() } ?: userAgent,
	)
}
