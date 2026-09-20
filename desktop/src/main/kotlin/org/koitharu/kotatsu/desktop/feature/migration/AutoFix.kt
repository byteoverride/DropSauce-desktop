package org.koitharu.kotatsu.desktop.feature.migration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Migration with the network steps attached.
 *
 * Split from [MigrationRepository] so the database half can be tested exhaustively with
 * no network at all, and so this half can be tested with a substituted loader.
 */
class MigrationService(
	private val repository: MigrationRepository,
	private val details: AlternativeDetailsLoader,
) {

	/**
	 * Loads whatever chapter lists are missing, then migrates.
	 *
	 * The old title's chapters are fetched only when progress is actually moving; without
	 * that flag the list is never looked at, so the request would be waste. The fetch is
	 * allowed to fail and degrade to no chapter list, because the usual reason for
	 * migrating is that the old source stopped answering: refusing to migrate a title
	 * because its dead source is dead would make the feature useless in its main case.
	 *
	 * The new title's chapters are not optional. A migration onto a title whose chapters
	 * could not be loaded has nowhere to put the reading position, so that failure is
	 * propagated.
	 */
	suspend fun migrate(
		oldManga: Manga,
		newManga: Manga,
		migrateProgress: Boolean = true,
	): MigrationResult {
		val oldDetails = if (migrateProgress && oldManga.chapters.isNullOrEmpty()) {
			runCatchingCancellable { details.load(oldManga) }.getOrDefault(oldManga)
		} else {
			oldManga
		}
		val newDetails = if (newManga.chapters.isNullOrEmpty()) details.load(newManga) else newManga
		return repository.migrate(oldDetails, newDetails, migrateProgress)
	}
}

/** What happened to one entry in a bulk run. */
sealed interface AutoFixEvent {

	val entry: LibraryEntry

	data class Started(override val entry: LibraryEntry, val index: Int, val total: Int) : AutoFixEvent

	data class Fixed(
		override val entry: LibraryEntry,
		val replacement: Alternative,
		val result: MigrationResult,
	) : AutoFixEvent

	/** Searched, nothing usable came back. Not an error: plenty of titles exist on one site only. */
	data class NoAlternative(override val entry: LibraryEntry) : AutoFixEvent

	data class Failed(override val entry: LibraryEntry, val message: String) : AutoFixEvent
}

/** The outcome of one entry, for the results list on the screen. */
data class AutoFixOutcome(
	val entry: LibraryEntry,
	val movedTo: Alternative?,
	val result: MigrationResult?,
	val message: String,
	val isSuccess: Boolean,
)

/**
 * Repairs broken library entries in bulk.
 *
 * One entry at a time. Each entry already fans out across the whole catalogue with
 * bounded parallelism, so running entries concurrently as well would multiply the load on
 * every source by the size of the user's library.
 *
 * Every entry is caught individually. A run over forty entries that aborts on the third
 * leaves the user worse off than no run at all, because they now have to work out which
 * of the forty were done.
 *
 * Health is judged by "the candidate has chapters", not by fetching a page url as Android
 * does. That probe costs two extra requests per candidate, and with up to four candidates
 * per entry and dozens of entries it turns a bulk fix into a very long run against
 * third-party sites. Cut deliberately; the cost is that a source which lists chapters but
 * cannot serve pages is still offered.
 */
class AutoFixRunner(
	private val engine: AlternativesEngine,
	private val service: MigrationService,
	private val catalogue: List<MangaParserSource> = MangaParserSource.entries,
	private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
	private val timeoutPerEntryMs: Long = DEFAULT_TIMEOUT_MS,
) {

	fun run(entries: List<LibraryEntry>, migrateProgress: Boolean = true): Flow<AutoFixEvent> = flow {
		entries.forEachIndexed { index, entry ->
			emit(AutoFixEvent.Started(entry, index, entries.size))
			val event = try {
				val best = bestAlternative(entry)
				if (best == null) {
					AutoFixEvent.NoAlternative(entry)
				} else {
					val result = service.migrate(entry.manga, best.manga, migrateProgress)
					AutoFixEvent.Fixed(entry, best, result)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				AutoFixEvent.Failed(entry, e.describeFailure())
			}
			emit(event)
		}
	}

	/**
	 * The best usable replacement for one entry, or null.
	 *
	 * Bounded twice, because the catalogue has over a thousand sources and the flow would
	 * otherwise run until every one of them had answered or timed out: stop after
	 * [maxCandidates] answers, or after [timeoutPerEntryMs], whichever comes first. What
	 * has arrived by then is kept and ranked; the timeout is a deadline, not a failure.
	 */
	suspend fun bestAlternative(entry: LibraryEntry): Alternative? {
		val sources = engine.candidateSources(
			currentSourceName = entry.sourceName,
			// A missing source cannot say what kind it was. Treated as an image source,
			// which is what all but a handful of the catalogue is.
			isNovel = entry.source?.contentType == ContentType.NOVEL,
			catalogue = catalogue,
		)
		if (sources.isEmpty()) {
			return null
		}
		val found = ArrayList<Alternative>(maxCandidates)
		withTimeoutOrNull(timeoutPerEntryMs) {
			engine.alternatives(entry.manga, sources)
				.take(maxCandidates)
				.collect { found.add(it) }
		}
		return found.filter { it.chaptersCount > 0 }.minWithOrNull(alternativeOrder)
	}

	companion object {

		/**
		 * Four answers is enough to rank against. Android waits for four too, then takes
		 * the best of whatever arrived.
		 */
		const val DEFAULT_MAX_CANDIDATES = 4

		/** Android's own per-title deadline. */
		const val DEFAULT_TIMEOUT_MS = 40_000L
	}
}

/** Turns the event stream into the rows the results list shows. */
fun AutoFixEvent.toOutcome(): AutoFixOutcome? = when (this) {
	is AutoFixEvent.Started -> null
	is AutoFixEvent.Fixed -> AutoFixOutcome(
		entry = entry,
		movedTo = replacement,
		result = result,
		message = "moved to ${replacement.manga.source.name}: ${result.describe()}",
		isSuccess = true,
	)

	is AutoFixEvent.NoAlternative -> AutoFixOutcome(
		entry = entry,
		movedTo = null,
		result = null,
		message = "no other source has this title",
		isSuccess = false,
	)

	is AutoFixEvent.Failed -> AutoFixOutcome(
		entry = entry,
		movedTo = null,
		result = null,
		message = message,
		isSuccess = false,
	)
}

/**
 * `runCatching` that lets cancellation through.
 *
 * Plain `runCatching` swallows [CancellationException], which turns a cancelled screen
 * into a coroutine that keeps running and writing to a database nobody is watching.
 */
internal inline fun <R> runCatchingCancellable(block: () -> R): Result<R> = try {
	Result.success(block())
} catch (e: CancellationException) {
	throw e
} catch (e: Throwable) {
	Result.failure(e)
}
