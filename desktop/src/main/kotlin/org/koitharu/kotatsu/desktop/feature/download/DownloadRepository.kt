package org.koitharu.kotatsu.desktop.feature.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path
import okio.Path.Companion.toPath
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.shared.db.LibraryDatabase

/**
 * The downloads feature's whole public surface.
 *
 * Screens, the details screen's "download" action and the reader's offline path all go
 * through this. Nothing outside this package should touch [DownloadManager] or the
 * `downloads` table directly, because the manager's invariant is that it is the only
 * writer of a row's state.
 */
class DownloadRepository internal constructor(
	private val db: LibraryDatabase,
	private val storage: DownloadStorage,
	private val pageSource: PageSource,
	private val manager: DownloadManager,
) {

	/** True while the queue is holding back chapters that have not started yet. */
	val isPaused: StateFlow<Boolean> get() = manager.isPaused

	/**
	 * Recovers the queue after a restart and resumes it. Call once per process.
	 *
	 * Safe to call again: reviving only touches RUNNING rows, and queueing a chapter
	 * that is already in flight is a no-op.
	 */
	suspend fun start() = manager.start()

	/** Every download, newest title first, grouped by the title it belongs to. */
	fun observeGroups(): Flow<List<DownloadGroup>> = db.downloadsDao().observeAll().map { rows ->
		rows.groupBy { it.download.mangaId }
			.mapNotNull { (_, group) ->
				val entity = group.first().manga
				val manga = entity.toManga() ?: return@mapNotNull null
				DownloadGroup(
					manga = manga,
					sourceName = entity.source,
					// Chapter order, not insertion order: a title queued back to front
					// should still read top to bottom.
					items = group.map { it.download.toItem() }
						.sortedWith(compareBy({ it.chapterNumber }, { it.chapterTitle })),
				)
			}
	}

	/** The downloads of one title, for a details screen that wants to show them inline. */
	fun observeFor(mangaId: Long): Flow<List<DownloadItem>> =
		db.downloadsDao().observeByManga(mangaId).map { rows ->
			rows.map { it.toItem() }.sortedWith(compareBy({ it.chapterNumber }, { it.chapterTitle }))
		}

	suspend fun find(mangaId: Long, chapterId: Long): DownloadItem? =
		db.downloadsDao().find(mangaId, chapterId)?.toItem()

	/**
	 * Queues the given chapters of [manga].
	 *
	 * Returns how many were newly queued; chapters already downloaded or in flight are
	 * skipped rather than duplicated.
	 */
	suspend fun download(manga: Manga, chapters: List<MangaChapter>): Int {
		primeChapters(manga)
		return manager.enqueue(manga, chapters)
	}

	/**
	 * Queues every chapter of [manga].
	 *
	 * Uses the chapter list the caller already loaded when it has one, and asks the
	 * source only when it does not, so downloading a title from a screen that has the
	 * details open costs no extra request.
	 */
	suspend fun downloadAll(manga: Manga): Int {
		primeChapters(manga)
		val chapters = manga.chapters?.takeIf { it.isNotEmpty() } ?: pageSource.chapters(manga)
		return manager.enqueue(manga, chapters)
	}

	suspend fun cancel(mangaId: Long, chapterId: Long) = manager.cancel(mangaId, chapterId)

	suspend fun retry(mangaId: Long, chapterId: Long) = manager.retry(mangaId, chapterId)

	/** Removes the row and the pages on disk. */
	suspend fun delete(mangaId: Long, chapterId: Long) = manager.delete(mangaId, chapterId)

	/** Removes every download of one title, and its directory. */
	suspend fun deleteAll(mangaId: Long) {
		for (row in db.downloadsDao().observeByManga(mangaId).firstList()) {
			manager.delete(mangaId, row.chapterId)
		}
		val sourceName = db.mangaDao().find(mangaId)?.source ?: return
		storage.delete(storage.mangaDir(sourceName, mangaId))
	}

	/** Suspends until the queue has drained. */
	suspend fun awaitIdle() = manager.awaitIdle()

	fun pause() = manager.pause()

	fun resume() = manager.resume()

	/**
	 * The files of a downloaded chapter, in reading order, or empty if it is not fully
	 * downloaded.
	 *
	 * Gated on DONE on purpose. A RUNNING chapter has files on disk too, and handing
	 * those to a reader would show a chapter that silently ends early.
	 */
	suspend fun localPagePaths(mangaId: Long, chapterId: Long): List<Path> {
		val row = db.downloadsDao().find(mangaId, chapterId) ?: return emptyList()
		if (row.state != DownloadState.DONE) return emptyList()
		// Where the pages actually went, which the worker recorded when it wrote them.
		// Recomputing it from the current root was fine while the root could never
		// change; now that it is a setting, doing so would orphan every chapter
		// downloaded before the reader moved their downloads folder, while the files sat
		// on disk and the row still said DONE.
		val recorded = row.path.takeIf { it.isNotBlank() }?.toPath()
		if (recorded != null && storage.exists(recorded)) {
			return storage.pages(recorded)
		}
		val sourceName = db.mangaDao().find(mangaId)?.source ?: return emptyList()
		return storage.pages(storage.chapterDir(sourceName, mangaId, chapterId))
	}

	/**
	 * The same pages as [MangaPage] objects, for a reader that is written against the
	 * parser model.
	 *
	 * `MangaPage.url` here is an absolute filesystem path, not an http url. A caller
	 * must read it as a file; handing it to OkHttp would fail. [localPagePaths] is the
	 * unambiguous form and is what new code should prefer.
	 */
	suspend fun localPages(mangaId: Long, chapterId: Long): List<MangaPage> {
		val sourceName = db.mangaDao().find(mangaId)?.source ?: return emptyList()
		val source = parserSource(sourceName) ?: return emptyList()
		return localPagePaths(mangaId, chapterId).mapIndexed { index, path ->
			MangaPage(
				// Derived from the chapter and the position so it is stable across
				// launches, which is what a reader keys its page state on.
				id = chapterId * PAGE_ID_STRIDE + index,
				url = path.toString(),
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * The downloaded chapters of a title, as chapters a reader can page through
	 * offline.
	 *
	 * Built from the stored rows rather than from the source, so it works with no
	 * network. `url` is the chapter's directory; there is nothing to fetch.
	 */
	suspend fun offlineChapters(mangaId: Long): List<MangaChapter> {
		val entity = db.mangaDao().find(mangaId) ?: return emptyList()
		val source = parserSource(entity.source) ?: return emptyList()
		return db.downloadsDao().observeByManga(mangaId).firstList()
			.filter { it.state == DownloadState.DONE }
			.sortedWith(compareBy({ it.chapterNumber }, { it.chapterTitle }))
			.map { row ->
				MangaChapter(
					id = row.chapterId,
					title = row.chapterTitle,
					number = row.chapterNumber,
					volume = 0,
					url = storage.chapterDir(entity.source, mangaId, row.chapterId).toString(),
					scanlator = null,
					uploadDate = row.createdAt,
					branch = null,
					source = source,
				)
			}
	}

	/** The title a downloaded chapter belongs to, rebuilt from storage. */
	suspend fun offlineManga(mangaId: Long): Manga? = db.mangaDao().find(mangaId)?.toManga()

	private fun primeChapters(manga: Manga) {
		(pageSource as? ParserPageSource)?.prime(manga)
	}

	companion object {

		/**
		 * Spacing between synthetic page ids of consecutive chapters.
		 *
		 * Large enough that no real chapter overruns into the next chapter's ids, which
		 * would make two pages compare equal in a reader's state.
		 */
		private const val PAGE_ID_STRIDE = 10_000L

		/** Builds the feature's repository against a [FeatureContext]. */
		fun create(context: FeatureContext, scope: CoroutineScope = context.scope): DownloadRepository {
			// Read per call, so changing the folder in Settings takes effect on the next
			// chapter rather than at the next restart.
			val storage = DownloadStorage({ downloadsRootFor(context) })
			val pageSource = ParserPageSource(context.sources) { context.clientFor(it) }
			return DownloadRepository(
				db = context.db,
				storage = storage,
				pageSource = pageSource,
				manager = DownloadManager(
					db = context.db,
					storage = storage,
					pageSource = pageSource,
					scope = scope,
				),
			)
		}
	}
}

/**
 * The folder downloads are written to right now.
 *
 * A blank or unusable setting falls back to the default rather than refusing to
 * download: the setting is a convenience, and a typo in it should not be the reason a
 * chapter cannot be saved.
 */
internal fun downloadsRootFor(context: FeatureContext): Path {
    val configured = context.settings.data.value.downloadDir?.trim()
    if (!configured.isNullOrEmpty()) {
        val path = configured.toPath()
        if (path.isAbsolute) return path
    }
    return context.paths.data / "downloads"
}

/** First emission of a DAO flow, for the one-shot reads that Room only exposes as a flow. */
private suspend fun <T> Flow<List<T>>.firstList(): List<T> = first()
