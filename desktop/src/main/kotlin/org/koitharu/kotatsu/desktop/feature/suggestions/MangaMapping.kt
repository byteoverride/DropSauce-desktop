package org.koitharu.kotatsu.desktop.feature.suggestions

import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.shared.db.MangaEntity

/**
 * Conversion between the stored row and the parser library's [Manga].
 *
 * LibraryRepository has equivalent mappers but they are private to that file, and this
 * area may not edit it. Three other feature areas made the same call and carry the same
 * twenty lines; duplicating them again is cheaper than a contract change to a file five
 * agents share.
 *
 * The stored row is a projection: tags, description and chapters are not persisted, so
 * anything built from a row is a seed, never a complete title. That projection is
 * exactly why this area keeps a tag cache of its own.
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
	source = parserSourceOrNull(source) ?: MangaParserSource.entries.first(),
)

internal fun Manga.toEntity(chaptersCount: Int = 0): MangaEntity = MangaEntity(
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
 * Anything about to fetch must use this and handle the null. Substituting a source would
 * fetch a different comic entirely, which is worse than a row that refuses to open.
 */
internal fun parserSourceOrNull(name: String): MangaParserSource? =
	MangaParserSource.entries.firstOrNull { it.name == name }

/** A [Manga] from a source, flattened into the shape the scorer takes. */
internal fun Manga.toCandidate(source: MangaParserSource): Candidate = Candidate(
	mangaId = id,
	title = title,
	sourceName = source.name,
	sourceTitle = source.title,
	tags = tags.map { it.title },
	contentType = source.contentType.name,
	rating = rating.takeIf { it > 0f } ?: 0f,
)
