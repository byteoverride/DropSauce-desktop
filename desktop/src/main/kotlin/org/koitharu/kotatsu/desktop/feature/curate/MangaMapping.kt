package org.koitharu.kotatsu.desktop.feature.curate

import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.shared.db.MangaEntity

/**
 * Conversion between the stored row and the parser library's [Manga].
 *
 * `LibraryRepository` has equivalent mappers, but they are private to that file and this
 * area may not edit it. Two other feature areas made the same copy for the same reason;
 * the alternative is a contract change to a file five agents share.
 *
 * The stored row is a projection: tags, description and chapters are not persisted, so a
 * [Manga] built from a row is a seed for a details fetch, never a complete title.
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

/**
 * The parser source a stored name refers to, or null when the catalogue no longer has it.
 *
 * A row whose source has left the build still has to be organisable: the whole point of
 * this screen is clearing out entries like that. So the list shows it and marks it
 * unavailable rather than hiding it, and only the actions that would fetch something
 * need the null handled.
 */
internal fun parserSourceOrNull(name: String): MangaParserSource? =
	MangaParserSource.entries.firstOrNull { it.name == name }
