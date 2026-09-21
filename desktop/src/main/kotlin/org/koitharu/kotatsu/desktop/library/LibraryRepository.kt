package org.koitharu.kotatsu.desktop.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity
import org.koitharu.kotatsu.shared.db.FavouriteEntity
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity

/** A saved title plus which source it came from, ready for a grid. */
data class LibraryItem(
	val manga: Manga,
	val sourceName: String,
	/** Chapters the source last reported, or 0 when it has never been loaded. */
	val chaptersCount: Int,
)

/** A history entry with enough context to resume. */
data class HistoryItem(
	val manga: Manga,
	val sourceName: String,
	val chapterId: Long,
	val page: Int,
	val percent: Float,
	val updatedAt: Long,
)

/**
 * The library: favourites, categories and history.
 *
 * Translates between the parser library's [Manga] and the stored [MangaEntity]. The
 * stored row is a projection, not a full record: chapters and tags are not persisted
 * because they go stale and are cheap to refetch, so a library entry opens its details
 * screen and reloads rather than showing a snapshot.
 */
class LibraryRepository(private val db: LibraryDatabase) {

	fun observeCategories(): Flow<List<FavouriteCategoryEntity>> =
		db.favouriteCategoriesDao().observeAll()

	suspend fun createCategory(title: String): Long {
		val dao = db.favouriteCategoriesDao()
		return dao.insert(
			FavouriteCategoryEntity(
				categoryId = 0,
				createdAt = System.currentTimeMillis(),
				sortKey = dao.nextSortKey(),
				title = title,
				order = DEFAULT_ORDER,
				track = true,
				isVisibleInLibrary = true,
			),
		)
	}

	suspend fun renameCategory(id: Long, title: String) =
		db.favouriteCategoriesDao().rename(id, title)

	suspend fun deleteCategory(id: Long) = db.favouriteCategoriesDao().delete(id)

	fun observeFavourites(categoryId: Long?): Flow<List<LibraryItem>> {
		val dao = db.favouritesDao()
		val source = if (categoryId == null) dao.observeAll() else dao.observeByCategory(categoryId)
		return source.map { rows ->
			// One card per title. The DAO now returns every category membership, so a
			// title in three categories would otherwise appear three times in "All".
			rows.distinctBy { it.manga.mangaId }
				.map { LibraryItem(it.manga.toManga(), it.manga.source, it.manga.chaptersCount) }
		}
	}

	fun observeCategoriesOf(manga: Manga): Flow<Set<Long>> =
		db.favouritesDao().observeCategoriesOf(manga.id).map { it.toSet() }

	suspend fun setFavourite(manga: Manga, categoryId: Long, favourite: Boolean) {
		if (favourite) {
			// The manga row must exist first: favourites has a foreign key onto it.
			db.mangaDao().upsert(manga.toEntity(chaptersCountFor(manga)))
			db.favouritesDao().upsert(
				FavouriteEntity(
					mangaId = manga.id,
					categoryId = categoryId,
					sortKey = 0,
					createdAt = System.currentTimeMillis(),
					deletedAt = 0L,
				),
			)
		} else {
			db.favouritesDao().remove(manga.id, categoryId)
		}
	}

	/**
	 * Updates the stored row for a title that is already known, after its details have
	 * been loaded.
	 *
	 * Deliberately does not insert: a title the user merely looked at should not appear
	 * in the library. Without this, anything saved before its chapter count was known
	 * would stay "not loaded" forever, which makes the length filter useless for exactly
	 * the entries that have been in the library longest.
	 */
	suspend fun refreshStored(manga: Manga) {
		val existing = db.mangaDao().find(manga.id) ?: return
		val count = manga.chapters?.size?.takeIf { it > 0 } ?: existing.chaptersCount
		db.mangaDao().upsert(manga.toEntity(count))
	}

	fun observeHistory(limit: Int = HISTORY_LIMIT): Flow<List<HistoryItem>> =
		db.historyDao().observeRecent(limit).map { rows ->
			rows.map {
				HistoryItem(
					manga = it.manga.toManga(),
					sourceName = it.manga.source,
					chapterId = it.history.chapterId,
					page = it.history.page,
					percent = it.history.percent,
					updatedAt = it.history.updatedAt,
				)
			}
		}

	suspend fun findHistory(manga: Manga): HistoryEntity? = db.historyDao().find(manga.id)

	/** Records reading position. Called as the reader moves, so it must be cheap. */
	suspend fun recordProgress(
		manga: Manga,
		chapterId: Long,
		page: Int,
		chaptersCount: Int,
		percent: Float,
	) {
		db.mangaDao().upsert(manga.toEntity(chaptersCount.takeIf { it > 0 } ?: chaptersCountFor(manga)))
		val now = System.currentTimeMillis()
		val existing = db.historyDao().find(manga.id)
		db.historyDao().upsert(
			HistoryEntity(
				mangaId = manga.id,
				createdAt = existing?.createdAt ?: now,
				updatedAt = now,
				chapterId = chapterId,
				page = page,
				scroll = 0f,
				percent = percent,
				chaptersCount = chaptersCount,
				deletedAt = 0L,
			),
		)
	}

	suspend fun deleteHistory(manga: Manga) = db.historyDao().delete(manga.id)

	suspend fun clearHistory() = db.historyDao().clear()

	/**
	 * Best known chapter count for [manga].
	 *
	 * Prefers what the loaded object carries, falling back to whatever is already
	 * stored. Without the fallback, saving a title from a list screen (where chapters
	 * are null) would wipe a count learned earlier from its details screen.
	 */
	private suspend fun chaptersCountFor(manga: Manga): Int =
		manga.chapters?.size?.takeIf { it > 0 }
			?: db.mangaDao().find(manga.id)?.chaptersCount
			?: 0

	private companion object {

		const val DEFAULT_ORDER = "NEWEST"
		const val HISTORY_LIMIT = 100
	}
}

private fun Manga.toEntity(chaptersCount: Int) = MangaEntity(
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

private fun MangaEntity.toManga() = Manga(
	id = mangaId,
	title = title,
	altTitles = setOfNotNull(altTitle),
	url = url,
	publicUrl = publicUrl,
	rating = rating,
	contentRating = contentRating?.let { name ->
		org.koitharu.kotatsu.parsers.model.ContentRating.entries.firstOrNull { it.name == name }
	},
	coverUrl = coverUrl,
	largeCoverUrl = largeCoverUrl,
	tags = emptySet(),
	state = state?.let { name ->
		org.koitharu.kotatsu.parsers.model.MangaState.entries.firstOrNull { it.name == name }
	},
	authors = setOfNotNull(author),
	description = null,
	chapters = null,
	source = MangaSource(source),
)

/**
 * Resolves a stored source name back to a source.
 *
 * Kept local rather than reusing the Android app's resolver, which knows about Mihon and
 * LNReader sources that desktop does not have.
 */
private fun MangaSource(name: String): MangaSource =
	org.koitharu.kotatsu.parsers.model.MangaParserSource.entries.firstOrNull { it.name == name }
		?: org.koitharu.kotatsu.parsers.model.MangaParserSource.entries.first()
