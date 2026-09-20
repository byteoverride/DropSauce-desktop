package org.koitharu.kotatsu.desktop.feature.reading

import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.shared.db.MangaEntity

/**
 * Conversion between the stored row and the parser library's [Manga].
 *
 * LibraryRepository has equivalent mappers but they are private to that file, and this
 * feature area may not edit it. Duplicating twenty lines is the cheaper of the two
 * options; the alternative is a contract change to a file five agents share.
 *
 * The stored row is a projection: tags, description and chapters are not persisted, so
 * anything built from a row is a seed for a details fetch, never a complete title.
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
	source = resolveSource(source),
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

/**
 * The parser source a stored name refers to, or null when the catalogue no longer has it.
 *
 * Callers that are about to fetch something must use this and handle the null: opening a
 * bookmark against a substituted source would fetch a different comic entirely, which is
 * worse than a row that refuses to open. [toSeedManga] cannot do that, because [Manga]
 * requires a source, so it substitutes and relies on the caller having checked.
 */
internal fun parserSourceOrNull(name: String): MangaParserSource? =
	MangaParserSource.entries.firstOrNull { it.name == name }

private fun resolveSource(name: String) =
	parserSourceOrNull(name) ?: MangaParserSource.entries.first()
