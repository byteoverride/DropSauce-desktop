package org.koitharu.kotatsu.desktop.feature.migration

import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.shared.db.MangaEntity

/**
 * Conversion between the stored row and the parser library's [Manga].
 *
 * A third copy of these twenty lines (LibraryRepository keeps its own privately, and so
 * do the reading and download areas). The alternative is a contract change to a file
 * several agents share, which costs more than the duplication does.
 *
 * The stored row is a projection: tags, description and chapters are not persisted. So
 * anything built from a row is a seed for a details fetch, never a complete title, and
 * migration in particular must fetch details before it can match chapters.
 */
internal fun MangaEntity.toSeedManga(): Manga = Manga(
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
	// A row whose source has left the catalogue still has to produce a Manga, because
	// that is exactly the row this feature exists to repair. Callers that are about to
	// fetch must go through parserSourceOrNull and handle the null instead.
	source = parserSourceOrNull(source) ?: MangaParserSource.entries.first(),
)

internal fun Manga.toEntity(chaptersCount: Int): MangaEntity = MangaEntity(
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

/** The parser source a stored name refers to, or null when the catalogue no longer has it. */
internal fun parserSourceOrNull(name: String): MangaParserSource? =
	MangaParserSource.entries.firstOrNull { it.name == name }
