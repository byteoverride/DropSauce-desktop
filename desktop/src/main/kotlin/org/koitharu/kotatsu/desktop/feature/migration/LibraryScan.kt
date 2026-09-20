package org.koitharu.kotatsu.desktop.feature.migration

import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity

/** Why a library entry cannot be read any more. */
enum class EntryHealth {

	/** The source is in the catalogue and is not flagged. Nothing to fix. */
	OK,

	/**
	 * The source is in the catalogue but `isBroken`.
	 *
	 * 380 of the 1270 sources are in this state (DECISIONS.md D18), which is what makes
	 * this whole feature worth building rather than a convenience.
	 */
	BROKEN,

	/**
	 * The stored source name is not in the catalogue at all.
	 *
	 * Either it was removed from `kotatsu-parsers` between releases, or the row came from
	 * an Android backup that carried a Mihon or LNReader source desktop does not have.
	 * Unreadable, and unlike [BROKEN] there is not even a parser to ask.
	 */
	MISSING,
}

/** A title the user keeps, plus whether its source still works. */
data class LibraryEntry(
	val manga: Manga,
	/** The name as stored, which is all there is when the source has left the catalogue. */
	val sourceName: String,
	/** Null exactly when [health] is [EntryHealth.MISSING]. */
	val source: MangaParserSource?,
	val health: EntryHealth,
	val isFavourite: Boolean,
	val hasHistory: Boolean,
) {

	val needsFixing: Boolean get() = health != EntryHealth.OK

	/** What to show next to the entry when it is broken. */
	fun reason(): String = when (health) {
		EntryHealth.OK -> "working"
		EntryHealth.BROKEN -> "$sourceName is flagged broken"
		EntryHealth.MISSING -> "$sourceName is no longer in the catalogue"
	}
}

/**
 * Finds which library entries sit on a source that no longer works.
 *
 * "Library entry" means a title the user deliberately keeps: favourited, or with reading
 * history. Not every row in `manga`, which is also a cache of things merely looked at and
 * would bury the real entries under titles nobody would miss.
 *
 * Read with one statement on a reader connection rather than by joining DAO flows,
 * because the two DAOs involved expose flows only and combining them in Kotlin would pull
 * the whole `manga` table into memory to answer a question SQLite can answer with an
 * index.
 */
class LibraryScanner(private val db: LibraryDatabase) {

	/**
	 * On IO explicitly.
	 *
	 * `useReaderConnection` runs the statement on the caller's dispatcher, and the caller
	 * here is a composable's LaunchedEffect on the main thread, where a full-library scan
	 * would show up as a dropped frame.
	 */
	suspend fun scan(): List<LibraryEntry> = withContext(Dispatchers.IO) {
		db.useReaderConnection { connection ->
			connection.usePrepared(QUERY) { statement ->
				buildList {
					while (statement.step()) {
						val entity = MangaEntity(
							mangaId = statement.getLong(0),
							title = statement.getText(1),
							altTitle = statement.textOrNull(2),
							url = statement.getText(3),
							publicUrl = statement.getText(4),
							rating = statement.getFloat(5),
							contentRating = statement.textOrNull(6),
							coverUrl = statement.textOrNull(7),
							largeCoverUrl = statement.textOrNull(8),
							state = statement.textOrNull(9),
							author = statement.textOrNull(10),
							source = statement.getText(11),
							chaptersCount = statement.getInt(12),
						)
						val source = parserSourceOrNull(entity.source)
						add(
							LibraryEntry(
								manga = entity.toSeedManga(),
								sourceName = entity.source,
								source = source,
								health = when {
									source == null -> EntryHealth.MISSING
									source.isBroken -> EntryHealth.BROKEN
									else -> EntryHealth.OK
								},
								isFavourite = statement.getBoolean(13),
								hasHistory = statement.getBoolean(14),
							),
						)
					}
				}
			}
		}
	}

	/** Only the entries that need repairing, which is what the broken-sources screen lists. */
	suspend fun scanBroken(): List<LibraryEntry> = scan().filter { it.needsFixing }

	private companion object {

		// The EXISTS clauses are repeated in the WHERE rather than referenced by their
		// output alias: SQLite does not allow a result alias in WHERE.
		const val QUERY =
			"SELECT m.manga_id, m.title, m.alt_title, m.url, m.public_url, m.rating, " +
				"m.content_rating, m.cover_url, m.large_cover_url, m.state, m.author, " +
				"m.source, m.chapters_count, " +
				"EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = m.manga_id AND f.deleted_at = 0), " +
				"EXISTS(SELECT 1 FROM history h WHERE h.manga_id = m.manga_id AND h.deleted_at = 0) " +
				"FROM manga m WHERE " +
				"EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = m.manga_id AND f.deleted_at = 0) " +
				"OR EXISTS(SELECT 1 FROM history h WHERE h.manga_id = m.manga_id AND h.deleted_at = 0) " +
				"ORDER BY m.title COLLATE NOCASE ASC"
	}
}

/**
 * A nullable TEXT column.
 *
 * `getText` on a NULL column is not specified to return null (the driver gives an empty
 * string), so the null check has to happen first or an absent alt-title comes back as a
 * present empty one.
 */
private fun SQLiteStatement.textOrNull(index: Int): String? =
	if (isNull(index)) null else getText(index)
