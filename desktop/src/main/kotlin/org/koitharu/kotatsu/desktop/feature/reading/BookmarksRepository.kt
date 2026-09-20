package org.koitharu.kotatsu.desktop.feature.reading

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.shared.db.BookmarkEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase

/** A saved page, with enough of its title to render a row without a second query. */
data class Bookmark(
	val manga: Manga,
	/** The stored source name, which may no longer resolve to a parser. */
	val sourceName: String,
	val chapterId: Long,
	val pageId: Long,
	/** Zero-based index of the page inside its chapter. */
	val page: Int,
	/** The page's image address at the time it was saved. */
	val imageUrl: String,
	/** How far through the whole title this page is, 0..1. */
	val percent: Float,
	val createdAt: Long,
)

/**
 * Saved pages.
 *
 * A bookmark is keyed by (manga, page), which is what makes saving the same page twice a
 * no-op rather than a duplicate row: the table's primary key is that pair and the write
 * is an upsert. Re-saving refreshes the image url, which matters because several sources
 * hand out per-request page addresses that go stale.
 */
class BookmarksRepository(
	private val db: LibraryDatabase,
	private val now: () -> Long = System::currentTimeMillis,
) {

	fun observeAll(): Flow<List<Bookmark>> = db.bookmarksDao().observeAll().map { rows ->
		rows.map { row ->
			Bookmark(
				manga = row.manga.toSeedManga(),
				sourceName = row.manga.source,
				chapterId = row.bookmark.chapterId,
				pageId = row.bookmark.pageId,
				page = row.bookmark.page,
				imageUrl = row.bookmark.imageUrl,
				percent = row.bookmark.percent,
				createdAt = row.bookmark.createdAt,
			)
		}
	}

	/** Only [mangaId]'s bookmarks, for the reader's own toggle state. */
	fun observeFor(mangaId: Long): Flow<List<BookmarkEntity>> =
		db.bookmarksDao().observeByManga(mangaId)

	suspend fun isBookmarked(mangaId: Long, pageId: Long): Boolean =
		db.bookmarksDao().find(mangaId, pageId) != null

	suspend fun add(
		manga: Manga,
		chapterId: Long,
		pageId: Long,
		page: Int,
		imageUrl: String,
		percent: Float,
	) {
		// bookmarks has a foreign key onto manga, so the title row has to exist first.
		// A bookmarked title is not necessarily a favourite, so nothing else will have
		// written it.
		db.mangaDao().upsert(manga.toEntity(chaptersCountFor(manga)))
		db.bookmarksDao().upsert(
			BookmarkEntity(
				mangaId = manga.id,
				pageId = pageId,
				chapterId = chapterId,
				page = page,
				// Desktop has no per-page scroll offset to restore: paged mode shows one
				// whole page and webtoon scrolls the strip, so the page index is the
				// whole position. The column stays for Android backup interop (D15).
				scroll = 0,
				imageUrl = imageUrl,
				createdAt = now(),
				percent = percent.coerceIn(0f, 1f),
			),
		)
	}

	suspend fun remove(mangaId: Long, pageId: Long) = db.bookmarksDao().delete(mangaId, pageId)

	suspend fun removeAllFor(mangaId: Long) = db.bookmarksDao().deleteAllFor(mangaId)

	/** Adds or removes, and reports the state the page ended in. */
	suspend fun toggle(
		manga: Manga,
		chapterId: Long,
		pageId: Long,
		page: Int,
		imageUrl: String,
		percent: Float,
	): Boolean {
		return if (isBookmarked(manga.id, pageId)) {
			remove(manga.id, pageId)
			false
		} else {
			add(manga, chapterId, pageId, page, imageUrl, percent)
			true
		}
	}

	/**
	 * Best known chapter count, preferring what the loaded title carries.
	 *
	 * Without the fallback, bookmarking from a screen that never loaded chapters would
	 * overwrite a count an earlier details fetch had already stored.
	 */
	private suspend fun chaptersCountFor(manga: Manga): Int =
		manga.chapters?.size?.takeIf { it > 0 }
			?: db.mangaDao().find(manga.id)?.chaptersCount
			?: 0
}
