package org.koitharu.kotatsu.desktop.feature.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.IOException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.shared.db.DownloadEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs chapter downloads, and is the only writer of the `downloads` table.
 *
 * Concurrency is bounded by a semaphore rather than by the scope: a title with two
 * hundred chapters must not open two hundred connections to a source that would
 * rate-limit or ban for it. Work is launched on the context's long-lived scope, so
 * navigating away from the downloads screen does not stop a download.
 *
 * The row is the source of truth, not this object's memory. Every transition is written
 * before the next step starts, which is what makes a download that was interrupted by
 * quitting the app recoverable at next launch.
 */
internal class DownloadManager(
	private val db: LibraryDatabase,
	private val storage: DownloadStorage,
	private val pageSource: PageSource,
	private val scope: CoroutineScope,
	private val now: () -> Long = System::currentTimeMillis,
	maxConcurrent: Int = MAX_CONCURRENT,
) {

	private data class Key(val mangaId: Long, val chapterId: Long)

	private val gate = Semaphore(maxConcurrent.coerceAtLeast(1))

	private val jobs = ConcurrentHashMap<Key, Job>()

	private val _isPaused = MutableStateFlow(false)

	/** While true, no further chapter is started. Chapters already running finish. */
	val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

	private val dao get() = db.downloadsDao()

	/**
	 * Brings the queue back after a restart.
	 *
	 * A row left RUNNING belongs to a process that no longer exists, and nothing else
	 * will ever move it, so it would sit at "downloading" forever. Reviving to QUEUED is
	 * the whole reason `reviveState` exists.
	 */
	suspend fun start() {
		dao.reviveState(from = DownloadState.RUNNING, state = DownloadState.QUEUED, now = now())
		for (row in dao.getByState(DownloadState.QUEUED)) {
			val manga = db.mangaDao().find(row.mangaId)?.toManga()
			if (manga == null) {
				// Nothing can download this: either the title row is gone or its source
				// is not in this build. Fail it loudly instead of requeueing forever.
				write(row.copy(state = DownloadState.FAILED, error = UNRESOLVED_MANGA, updatedAt = now()))
				continue
			}
			submit(manga, row.mangaId, row.chapterId)
		}
	}

	/**
	 * Queues [chapters] of [manga], skipping any that is already done or in flight.
	 *
	 * Returns how many rows were actually queued, so a caller can tell "queued 12" from
	 * "everything was already there".
	 */
	suspend fun enqueue(manga: Manga, chapters: List<MangaChapter>): Int {
		if (chapters.isEmpty()) return 0
		// The downloads screen joins onto `manga`, and that join is the only thing that
		// gives a download a title, so the row has to exist before the download does.
		val stored = db.mangaDao().find(manga.id)
		val chaptersCount = manga.chapters?.size?.takeIf { it > 0 } ?: stored?.chaptersCount ?: 0
		db.mangaDao().upsert(manga.toEntity(chaptersCount))
		var queued = 0
		for (chapter in chapters) {
			val existing = dao.find(manga.id, chapter.id)
			if (existing != null && (existing.state == DownloadState.DONE || DownloadState.isActive(existing.state))) {
				continue
			}
			val timestamp = now()
			write(
				DownloadEntity(
					mangaId = manga.id,
					chapterId = chapter.id,
					chapterTitle = chapter.title ?: "Chapter ${chapter.numberString()}",
					chapterNumber = chapter.number,
					state = DownloadState.QUEUED,
					pagesTotal = 0,
					pagesDone = 0,
					path = "",
					error = null,
					createdAt = existing?.createdAt ?: timestamp,
					updatedAt = timestamp,
				),
			)
			submit(manga, manga.id, chapter.id)
			queued++
		}
		return queued
	}

	/** Puts a finished-but-unsuccessful row back in the queue. */
	suspend fun retry(mangaId: Long, chapterId: Long) {
		val row = dao.find(mangaId, chapterId) ?: return
		if (DownloadState.isActive(row.state)) return
		val manga = db.mangaDao().find(mangaId)?.toManga() ?: run {
			write(row.copy(state = DownloadState.FAILED, error = UNRESOLVED_MANGA, updatedAt = now()))
			return
		}
		write(row.copy(state = DownloadState.QUEUED, pagesDone = 0, error = null, updatedAt = now()))
		submit(manga, mangaId, chapterId)
	}

	/**
	 * Stops a download and waits for it to have finished stopping.
	 *
	 * Suspending rather than fire and forget on purpose: the caller, and the test, need
	 * the row and the files to be settled when this returns.
	 */
	suspend fun cancel(mangaId: Long, chapterId: Long) {
		val key = Key(mangaId, chapterId)
		jobs.remove(key)?.cancelAndJoin()
		val row = dao.find(mangaId, chapterId) ?: return
		if (DownloadState.isActive(row.state)) {
			// No worker was running, so nothing else is going to write this row.
			deleteFiles(mangaId, chapterId)
			write(row.copy(state = DownloadState.CANCELLED, error = null, updatedAt = now()))
		}
	}

	/** Cancels if needed, then removes the row and the chapter's files. */
	suspend fun delete(mangaId: Long, chapterId: Long) {
		jobs.remove(Key(mangaId, chapterId))?.cancelAndJoin()
		deleteFiles(mangaId, chapterId)
		dao.delete(mangaId, chapterId)
	}

	/**
	 * Suspends until nothing is queued or running.
	 *
	 * The queue is otherwise fire and forget, and something has to be able to ask
	 * whether it has drained: a test, and an orderly shutdown that would rather not kill
	 * a chapter one page from the end.
	 */
	suspend fun awaitIdle() {
		while (true) {
			val job = jobs.values.firstOrNull() ?: return
			job.join()
		}
	}

	fun pause() {
		_isPaused.value = true
	}

	fun resume() {
		_isPaused.value = false
	}

	private fun submit(manga: Manga, mangaId: Long, chapterId: Long) {
		val key = Key(mangaId, chapterId)
		// LAZY so the job is registered before it can complete. Started eagerly, a very
		// short download could finish and try to deregister before the map ever held it,
		// leaving a completed job behind that a later cancel would wait on.
		val job = scope.launch(start = CoroutineStart.LAZY) {
			// Waiting for the pause to lift before taking a permit, not after, so a
			// paused queue does not sit on permits that nothing is using.
			_isPaused.first { !it }
			gate.withPermit { run(manga, mangaId, chapterId) }
		}
		val previous = jobs.put(key, job)
		if (previous != null && previous.isActive) {
			// Should not happen: enqueue and retry both refuse an active row. Cancel it
			// rather than leak a second worker writing the same files.
			previous.cancel()
		}
		job.invokeOnCompletion { jobs.remove(key, job) }
		job.start()
	}

	private suspend fun run(manga: Manga, mangaId: Long, chapterId: Long) {
		val dir = storage.chapterDir(manga.source.name, mangaId, chapterId)
		val started = dao.find(mangaId, chapterId) ?: return
		if (started.state == DownloadState.CANCELLED) return
		write(
			started.copy(
				state = DownloadState.RUNNING,
				path = dir.toString(),
				pagesDone = 0,
				error = null,
				updatedAt = now(),
			),
		)
		try {
			storage.prepare(dir)
			val chapter = pageSource.chapters(manga).firstOrNull { it.id == chapterId }
				?: throw IOException("This chapter is no longer listed by the source")
			val pages = pageSource.pages(manga, chapter)
			if (pages.isEmpty()) throw IOException("The source returned no pages for this chapter")
			writeProgress(mangaId, chapterId, total = pages.size, done = 0)
			pages.forEachIndexed { index, page ->
				val data = pageSource.fetch(manga, page)
				storage.writePage(dir, index, data)
				writeProgress(mangaId, chapterId, total = pages.size, done = index + 1)
			}
			// Only now, with every page on disk under its final name, is the chapter
			// readable offline. Nothing else writes DONE.
			finish(mangaId, chapterId, DownloadState.DONE, error = null)
		} catch (e: CancellationException) {
			// NonCancellable: this coroutine is already cancelled, so an ordinary
			// suspending write here would itself be cancelled and the row would stay
			// RUNNING, which is the exact state that resume-on-start has to clean up.
			withContext(NonCancellable) {
				storage.delete(dir)
				finish(mangaId, chapterId, DownloadState.CANCELLED, error = null)
			}
			throw e
		} catch (e: Exception) {
			// A partial chapter is not worth keeping. Retry re-fetches everything
			// anyway, because page N of the next attempt need not be page N of this one.
			storage.delete(dir)
			finish(mangaId, chapterId, DownloadState.FAILED, error = describe(e))
		}
	}

	private suspend fun writeProgress(mangaId: Long, chapterId: Long, total: Int, done: Int) {
		val row = dao.find(mangaId, chapterId) ?: return
		write(row.copy(pagesTotal = total, pagesDone = done, updatedAt = now()))
	}

	private suspend fun finish(mangaId: Long, chapterId: Long, state: String, error: String?) {
		val row = dao.find(mangaId, chapterId) ?: return
		write(row.copy(state = state, error = error, updatedAt = now()))
	}

	private suspend fun deleteFiles(mangaId: Long, chapterId: Long) {
		val sourceName = db.mangaDao().find(mangaId)?.source ?: return
		storage.deleteChapter(sourceName, mangaId, chapterId)
	}

	private suspend fun write(row: DownloadEntity) {
		dao.upsert(row)
	}

	private fun describe(e: Exception): String =
		e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Download failed"

	companion object {

		/**
		 * Chapters downloaded at once.
		 *
		 * Three is a compromise the sources force: the parsers carry their own
		 * rate-limit interceptors and a site that sees a burst answers with 429s or a
		 * challenge page, which fails a whole batch instead of slowing it.
		 */
		const val MAX_CONCURRENT = 3

		const val UNRESOLVED_MANGA = "This title's source is not available in this build"
	}
}
