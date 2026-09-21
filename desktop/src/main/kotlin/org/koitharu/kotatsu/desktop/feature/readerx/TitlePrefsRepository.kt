package org.koitharu.kotatsu.desktop.feature.readerx

import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import org.koitharu.kotatsu.shared.db.MangaPrefsEntity
import org.koitharu.kotatsu.shared.settings.ReadingMode

/**
 * The overrides one title carries.
 *
 * Every optional field is nullable and null means "follow the app default". That is not
 * the same as holding today's default value: a title explicitly pinned to right-to-left
 * must stay right-to-left when the global default later changes, and a title that was
 * never touched must follow it. Collapsing the two is the single most likely way to get
 * this table wrong, so nothing in this file ever substitutes a default on read.
 *
 * [incognito] is not nullable because the column is not: the Android schema this mirrors
 * declares it `INTEGER NOT NULL DEFAULT 0` (DECISIONS.md D15), and false is a genuine
 * absence of the flag rather than a stand-in for one.
 */
data class TitlePrefs(
	val mangaId: Long,
	val readingMode: ReadingMode? = null,
	val titleOverride: String? = null,
	val coverOverride: String? = null,
	val branch: String? = null,
	val incognito: Boolean = false,
) {

	/** True when this row overrides nothing, so the title is effectively unconfigured. */
	val isEmpty: Boolean
		get() = readingMode == null &&
			titleOverride == null &&
			coverOverride == null &&
			branch == null &&
			!incognito
}

/** A configured title, with just enough of the title itself to render a row. */
data class TitleOverride(
	val prefs: TitlePrefs,
	val title: String,
	val coverUrl: String?,
	/** The stored source name. Kept as text because the catalogue may no longer have it. */
	val sourceName: String,
)

/**
 * Per-title reading preferences.
 *
 * Writes have to upsert the title row first: `manga_prefs` has a foreign key onto `manga`
 * and a title can be configured from the reader without ever having been favourited or
 * downloaded, so nothing else guarantees the parent row exists.
 */
class TitlePrefsRepository(private val db: LibraryDatabase) {

	suspend fun find(mangaId: Long): TitlePrefs? = db.mangaPrefsDao().find(mangaId)?.toPrefs()

	fun observe(mangaId: Long): Flow<TitlePrefs?> =
		db.mangaPrefsDao().observe(mangaId).map { it?.toPrefs() }

	/**
	 * Writes [prefs] exactly as given, including its nulls.
	 *
	 * Deliberately not "delete the row if it is empty". An empty row is harmless and a
	 * save that sometimes deletes makes the round trip depend on the contents, which is
	 * surprising for callers. [clear] is how a caller asks for removal, and
	 * [observeOverrides] is what filters empty rows out of the list the user sees.
	 */
	suspend fun save(manga: Manga, prefs: TitlePrefs) {
		require(prefs.mangaId == manga.id) {
			"prefs are for ${prefs.mangaId} but the title is ${manga.id}"
		}
		// Preserve a chapter count another area already recorded. This write knows the
		// count only when the caller happened to pass a fully fetched title, and an
		// upsert replaces the whole row, so taking the naive path would silently reset
		// the library's length filter for every title whose prefs were edited.
		val existing = db.mangaDao().find(manga.id)
		val chaptersCount = manga.chapters?.size ?: existing?.chaptersCount ?: 0
		db.mangaDao().upsert(manga.toPrefsParentRow(chaptersCount))
		db.mangaPrefsDao().upsert(prefs.toEntity())
	}

	/** Updates the row for [manga], starting from whatever is already stored. */
	suspend fun update(manga: Manga, transform: (TitlePrefs) -> TitlePrefs) {
		val current = find(manga.id) ?: TitlePrefs(mangaId = manga.id)
		save(manga, transform(current))
	}

	/**
	 * Writes [prefs] for a title whose row is already in the database.
	 *
	 * Exists for the settings list, which edits rows it read out of the join and has no
	 * [Manga] to hand. Requiring one there would mean rebuilding a title from a stored
	 * projection purely to satisfy a foreign key that is already satisfied, and that
	 * reconstruction is lossy. Anywhere the title might be new, use [save].
	 */
	suspend fun saveExisting(prefs: TitlePrefs) = db.mangaPrefsDao().upsert(prefs.toEntity())

	suspend fun clear(mangaId: Long) = db.mangaPrefsDao().delete(mangaId)

	/**
	 * Every title that actually overrides something, by title.
	 *
	 * Room's generated DAO has no listing query and this feature may not change the
	 * shared schema file, so this reads the join directly off a pooled connection and
	 * re-runs on invalidation. Sorting by title rather than by id because the list is for
	 * a human looking for one entry.
	 */
	fun observeOverrides(): Flow<List<TitleOverride>> =
		db.invalidationTracker.createFlow(TABLE_PREFS, TABLE_MANGA).map { listOverrides() }

	suspend fun listOverrides(): List<TitleOverride> = db.useReaderConnection { connection ->
		connection.usePrepared(OVERRIDES_QUERY) { statement ->
			buildList {
				while (statement.step()) {
					add(
						TitleOverride(
							prefs = TitlePrefs(
								mangaId = statement.getLong(0),
								readingMode = readingModeOrNull(statement.textOrNull(1)),
								titleOverride = statement.textOrNull(2),
								coverOverride = statement.textOrNull(3),
								branch = statement.textOrNull(4),
								incognito = statement.getLong(5) != 0L,
							),
							title = statement.getText(6),
							coverUrl = statement.textOrNull(7),
							sourceName = statement.getText(8),
						),
					)
				}
			}
		}
	}

	private companion object {

		const val TABLE_PREFS = "manga_prefs"
		const val TABLE_MANGA = "manga"

		val OVERRIDES_QUERY = """
			SELECT p.manga_id, p.reading_mode, p.title_override, p.cover_override, p.branch,
			       p.incognito, m.title, m.cover_url, m.source
			FROM manga_prefs AS p
			JOIN manga AS m ON m.manga_id = p.manga_id
			WHERE p.reading_mode IS NOT NULL
			   OR p.title_override IS NOT NULL
			   OR p.cover_override IS NOT NULL
			   OR p.branch IS NOT NULL
			   OR p.incognito <> 0
			ORDER BY m.title COLLATE NOCASE ASC
		""".trimIndent()
	}
}

private fun SQLiteStatement.textOrNull(index: Int): String? =
	if (isNull(index)) null else getText(index)

internal fun MangaPrefsEntity.toPrefs() = TitlePrefs(
	mangaId = mangaId,
	readingMode = readingModeOrNull(readingMode),
	titleOverride = titleOverride,
	coverOverride = coverOverride,
	branch = branch,
	incognito = incognito,
)

internal fun TitlePrefs.toEntity() = MangaPrefsEntity(
	mangaId = mangaId,
	readingMode = readingMode?.name,
	titleOverride = titleOverride,
	coverOverride = coverOverride,
	branch = branch,
	incognito = incognito,
)

/**
 * The stored mode name, or null when it is absent or no longer exists.
 *
 * An unrecognised name has to read as "not overridden" rather than as an error: a config
 * written by a newer build must not make the older one refuse to open the title.
 */
internal fun readingModeOrNull(name: String?): ReadingMode? =
	name?.let { stored -> ReadingMode.entries.firstOrNull { it.name == stored } }

/**
 * Enough of [Manga] to satisfy the foreign key.
 *
 * A local copy of a mapper that also exists in the reading area. That duplication is
 * deliberate and is already documented there: the alternative is for two feature areas
 * built in parallel to share a file, and twenty lines is cheaper than that coupling.
 */
private fun Manga.toPrefsParentRow(chaptersCount: Int): MangaEntity = MangaEntity(
	mangaId = id,
	title = title,
	altTitle = altTitles.firstOrNull(),
	url = url,
	publicUrl = publicUrl,
	rating = rating,
	contentRating = contentRating?.name,
	coverUrl = coverUrl,
	largeCoverUrl = largeCoverUrl,
	state = state?.name,
	author = authors.firstOrNull(),
	source = source.name,
	chaptersCount = chaptersCount,
)
