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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Learns how many chapters a title has.
 *
 * An interface rather than a call into the source registry so the refresher can be tested
 * without a network, and narrower than a full details fetch because the count is all the
 * library wants. It may throw; [ChapterCountRefresher] counts that rather than stopping.
 */
/** The catalogue indexed by name, so resolving a stored source is not a scan of 1270. */
private val catalogueByName: Map<String, MangaParserSource> by lazy {
	MangaParserSource.entries.associateBy { it.name }
}

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

	/**
	 * Counts actually learned.
	 *
	 * Floored at zero because the two fields can be read an instant apart while a run is
	 * going: a worker marks its title failed before it marks it done, so two failures
	 * landing together can briefly leave [failed] ahead of [done]. The finished value is
	 * always consistent, but nothing should be able to render "loaded -1 counts".
	 */
	val filled: Int get() = (done - failed).coerceAtLeast(0)
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

	private val lock = Any()

	private var job: Job? = null

	/** Saved titles in [categoryId] with no known count; null means the whole library. */
	fun observeMissing(categoryId: Long?): Flow<Int> =
		db.mangaDao().observeFavouritesWithoutChaptersCount(categoryId)

	/**
	 * Starts a run in the background, or does nothing if one is already going.
	 *
	 * Guarded because reading [job] and replacing it is two steps, and the second press
	 * of a double-clicked button can land between them. Two runs over the same rows would
	 * both fetch every title and then race each other's progress, doubling the requests a
	 * source sees for no gain.
	 */
	fun start(categoryId: Long?, concurrency: Int = DEFAULT_CONCURRENCY) {
		synchronized(lock) {
			if (job?.isActive == true) return
			job = scope.launch { run(categoryId, concurrency) }
		}
	}

	fun cancel() {
		synchronized(lock) { job }?.cancel()
	}

	/** Clears the finished run, so the control goes back to offering a new one. */
	fun dismiss() {
		synchronized(lock) {
			if (job?.isActive != true) state.value = null
		}
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
		perSourceConcurrency: Int = DEFAULT_PER_SOURCE_CONCURRENCY,
	): ChapterCountProgress {
		db.mangaDao().backfillChaptersCountFromHistory()
		val rows = db.mangaDao().favouritesWithoutChaptersCount(categoryId)
		if (rows.isEmpty()) {
			return ChapterCountProgress(0, 0, 0, running = false).also { state.value = it }
		}
		state.value = ChapterCountProgress(0, rows.size, 0, running = true)
		val permits = Semaphore(concurrency.coerceAtLeast(1))
		// A second ceiling, per source. The global one alone says nothing about where the
		// requests go, and a library is not spread evenly: one shelf here is 224 titles on
		// a single site, so a run pointed four at a time at that one host for minutes. The
		// likely answer is throttling, and a throttled response is indistinguishable here
		// from a dead source: it counts as failed, stores nothing, and is never retried.
		// So the run would report hundreds of failures and look broken when the only
		// problem was its own manners.
		val perSource = ConcurrentHashMap<String, Semaphore>()
		val done = AtomicInteger(0)
		val failed = AtomicInteger(0)
		try {
			coroutineScope {
				rows.map { row ->
					async(Dispatchers.IO) {
						// Source permit first, then the global one. Taking the global permit
						// first would let four workers queued on one busy source hold every
						// permit there is, leaving other sources idle behind them.
						val host = perSource.computeIfAbsent(row.source) {
							Semaphore(perSourceConcurrency.coerceAtLeast(1))
						}
						host.withPermit {
							permits.withPermit { fetchOne(row, rows.size, done, failed) }
						}
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
		val source = catalogueByName[row.source]
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
		publish(done.incrementAndGet(), total, failed.get())
	}

	/**
	 * Moves the reported progress forward, never back.
	 *
	 * The two counters are atomic on their own, but reading both and assigning the result
	 * is not: with four workers finishing at once, a slower thread could overwrite a
	 * higher count with its own stale one and the bar would jump backwards. Taking the
	 * maximum under [MutableStateFlow.update] keeps it monotonic whatever order the
	 * writes land in.
	 */
	private fun publish(done: Int, total: Int, failed: Int) {
		state.update { previous ->
			ChapterCountProgress(
				done = maxOf(done, previous?.done ?: 0),
				total = total,
				failed = maxOf(failed, previous?.failed ?: 0),
				running = true,
			)
		}
	}

	companion object {

		/** Four at a time, matching the tracker: quick enough to be worth waiting for. */
		const val DEFAULT_CONCURRENCY = 4

		/**
		 * And never more than two against the same site.
		 *
		 * Two rather than one because a library spread over many sources would otherwise
		 * be needlessly slow, and two is the sort of load an ordinary reader with a couple
		 * of tabs open already produces.
		 */
		const val DEFAULT_PER_SOURCE_CONCURRENCY = 2
	}
}
