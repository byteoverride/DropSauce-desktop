package org.koitharu.kotatsu.desktop.feature.migration

import androidx.room.useReaderConnection
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase

/**
 * Fixtures shared by the migration tests.
 *
 * Two real, non-broken sources are used rather than made-up ones, because
 * [AlternativesEngine.candidateSources] filters on `isBroken` and `contentType` and a
 * fake source could not exercise that.
 */
internal val OLD_SOURCE: MangaParserSource = MangaParserSource.MANGADEX

internal val NEW_SOURCE: MangaParserSource = MangaParserSource.MANGANATO

internal fun chapter(
	id: Long,
	number: Float,
	source: MangaParserSource = OLD_SOURCE,
	volume: Int = 0,
) = MangaChapter(
	id = id,
	title = "Chapter $number",
	number = number,
	volume = volume,
	url = "/chapter/$id",
	scanlator = null,
	uploadDate = 0L,
	branch = null,
	source = source,
)

internal fun manga(
	id: Long,
	title: String,
	source: MangaParserSource = OLD_SOURCE,
	chapters: List<MangaChapter>? = null,
) = Manga(
	id = id,
	title = title,
	altTitles = emptySet(),
	url = "/manga/$id",
	publicUrl = "https://example.test/manga/$id",
	rating = 0.7f,
	contentRating = null,
	coverUrl = "https://example.test/cover/$id.jpg",
	largeCoverUrl = null,
	tags = emptySet(),
	state = null,
	authors = emptySet(),
	description = null,
	chapters = chapters,
	source = source,
)

/** A row count, read the same way in the before and after halves of the rollback test. */
internal suspend fun LibraryDatabase.countOf(table: String): Int = useReaderConnection { connection ->
	connection.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
		statement.step()
		statement.getInt(0)
	}
}

/** Every column of every row of [table], as text, so a test can assert nothing moved at all. */
internal suspend fun LibraryDatabase.dumpOf(table: String, orderBy: String): List<String> =
	useReaderConnection { connection ->
		connection.usePrepared("SELECT * FROM $table ORDER BY $orderBy") { statement ->
			buildList {
				while (statement.step()) {
					add(
						(0 until statement.getColumnCount()).joinToString("|") { index ->
							if (statement.isNull(index)) "null" else statement.getText(index)
						},
					)
				}
			}
		}
	}
