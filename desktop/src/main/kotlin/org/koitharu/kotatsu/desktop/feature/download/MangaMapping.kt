package org.koitharu.kotatsu.desktop.feature.download

import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.shared.db.MangaEntity

/**
 * Translation between the parser library's [Manga] and the stored row.
 *
 * Duplicated deliberately rather than shared with `LibraryRepository`: that file's
 * mappers are private to it, and downloads must not force a contract change on another
 * feature area to get at them. Both are projections of the same table, so they agree on
 * column meaning; nothing here writes a column the library does not also write.
 */
internal fun Manga.toEntity(chaptersCount: Int) = MangaEntity(
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

/**
 * Rebuilds a [Manga] from its stored row.
 *
 * Chapters and tags are not stored, so they come back null/empty. That is enough for a
 * download worker, which only needs identity plus the source to re-resolve a chapter,
 * and it is what makes resuming after a restart possible at all.
 *
 * Null when the stored source name is not in this build's catalogue, which a downgrade
 * of the parsers jar can cause. Guessing a source instead would point the download at
 * the wrong site.
 */
internal fun MangaEntity.toManga(): Manga? {
	val parserSource = parserSource(source) ?: return null
	return Manga(
		id = mangaId,
		title = title,
		altTitles = setOfNotNull(altTitle),
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		contentRating = contentRating?.let { name -> ContentRating.entries.firstOrNull { it.name == name } },
		coverUrl = coverUrl,
		largeCoverUrl = largeCoverUrl,
		tags = emptySet(),
		state = state?.let { name -> MangaState.entries.firstOrNull { it.name == name } },
		authors = setOfNotNull(author),
		description = null,
		chapters = null,
		source = parserSource,
	)
}

/**
 * Resolves a stored source name to a parser source.
 *
 * Returns null rather than substituting a default: downloading from the wrong source
 * would silently write someone else's pages into this title's directory.
 */
internal fun parserSource(name: String): MangaParserSource? =
	MangaParserSource.entries.firstOrNull { it.name == name }
