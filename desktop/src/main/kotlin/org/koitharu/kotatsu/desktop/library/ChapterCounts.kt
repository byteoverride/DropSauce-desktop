package org.koitharu.kotatsu.desktop.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Learns how many chapters a title has.
 *
 * An interface rather than a call into the source registry so the refresher can be tested
 * without a network, and narrower than a full details fetch because the count is all the
 * library wants. It may throw; [ChapterCountRefresher] counts that rather than stopping.
 */
fun interface ChapterCountFetcher {

	suspend fun chapterCount(source: MangaParserSource, manga: Manga): Int
}

/** How far a run has got, for the control that started it. */
data class ChapterCountProgress(
	val done: Int,
	val total: Int,
	/** Titles whose count could not be learned: the source failed, or reported nothing. */
	val failed: Int,
	val running: Boolean,
) {

	val filled: Int get() = done - failed
}

/**
 * Fills in the chapter counts the library's length filter reads.
 *
 * Every other writer of `manga.chapters_count` is a side effect of visiting one title:
 * opening its details, reading it, tracking it. That is fine for a library built up a
 * title at a time in this app and useless for one that arrived whole, where the filter
 * then reports every shelf as empty because nothing has a count rather than because
 * nothing matches. This is the deliberate version of that work, over a whole category.
 *
 * Bounded and user-started on purpose. Each title is a live request to a third-party
 * site, so a library-sized fan-out is both slow and rude, and starting one without being
 * asked would be a surprise the user pays for in traffic.
 */
class ChapterCountRefresher(
	private val db: LibraryDatabase,
	private val fetcher: ChapterCountFetcher,
	private val scope: CoroutineScope,
) {

	private val state = MutableStateFlow<ChapterCountProgress?>(null)

	/** Null until a run starts, then the last run's progress, which survives navigation. */
	val progress: StateFlow<ChapterCountProgress?> = state.asStateFlow()

	private var job: Job? = null

	/** Saved titles in [categoryId] with no known count; null means the whole library. */
	fun observeMissing(categoryId: Long?): Flow<Int> =
		db.mangaDao().observeFavouritesWithoutChaptersCount(categoryId)

	/** Starts a run in the background, or does nothing if one is already going. */
	fun start(categoryId: Long?, concurrency: Int = DEFAULT_CONCURRENCY) {
		if (job?.isActive == true) return
		job = scope.launch { run(categoryId, concurrency) }
	}

	fun cancel() {
		job?.cancel()
	}

	/** Clears the finished run, so the control goes back to offering a new one. */
	fun dismiss() {
		if (job?.isActive != true) state.value = null
	}

	/**
	 * Fetches every missing count in [categoryId], [concurrency] at a time.
	 *
	 * Backfills from history first: those titles are already known and asking the source
	 * for something the database can answer is the request most worth not making.
	 */
	suspend fun run(
		categoryId: Long?,
		concurrency: Int = DEFAULT_CONCURRENCY,
	): ChapterCountProgress {
		db.mangaDao().backfillChaptersCountFromHistory()
		val rows = db.mangaDao().favouritesWithoutChaptersCount(categoryId)
		if (rows.isEmpty()) {
			return ChapterCountProgress(0, 0, 0, running = false).also { state.value = it }
		}
		state.value = ChapterCountProgress(0, rows.size, 0, running = true)
		val permits = Semaphore(concurrency.coerceAtLeast(1))
		val done = AtomicInteger(0)
		val failed = AtomicInteger(0)
		try {
			coroutineScope {
				rows.map { row ->
					async(Dispatchers.IO) {
						permits.withPermit { fetchOne(row, rows.size, done, failed) }
					}
				}.awaitAll()
			}
		} finally {
			// Also runs when the user cancels, so the control stops claiming a live run
			// and still shows what the run managed to fill in before it was stopped.
			state.value = ChapterCountProgress(done.get(), rows.size, failed.get(), running = false)
		}
		return checkNotNull(state.value)
	}

	private suspend fun fetchOne(
		row: MangaEntity,
		total: Int,
		done: AtomicInteger,
		failed: AtomicInteger,
	) {
		val source = MangaParserSource.entries.firstOrNull { it.name == row.source }
		if (source == null) {
			// A source this build does not have. DECISIONS.md D1: a library restored from
			// Android can name Mihon sources desktop cannot browse, and those titles can
			// never get a count. Counting it as failed is the honest answer.
			failed.incrementAndGet()
		} else {
			try {
				val count = fetcher.chapterCount(source, row.toManga())
				if (count > 0) {
					db.mangaDao().setChaptersCount(row.mangaId, count)
				} else {
					// The fetch worked and the source lists nothing. Storing zero would be
					// indistinguishable from never having asked, which it is not.
					failed.incrementAndGet()
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				failed.incrementAndGet()
			}
		}
		state.value = ChapterCountProgress(done.incrementAndGet(), total, failed.get(), running = true)
	}

	companion object {

		/** Four at a time, matching the tracker: quick enough to be worth waiting for. */
		const val DEFAULT_CONCURRENCY = 4
	}
}
