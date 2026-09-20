package org.koitharu.kotatsu.desktop.feature.discover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * One source to search, and how to search it.
 *
 * A function rather than a parser, so the engine has no idea what a source is and a test
 * can drive it without a network. [key] is stable across runs (the enum name);
 * [title] is what a person reads.
 */
class SearchTarget(
	val key: String,
	val title: String,
	val search: suspend (String) -> List<Manga>,
)

/** How one source answered. */
sealed interface SourceOutcome {

	/** Queued, not started: the engine runs a bounded number of sources at a time. */
	data object Waiting : SourceOutcome

	data object Running : SourceOutcome

	/**
	 * The source answered. [items] may be empty, and that is not a failure.
	 *
	 * Keeping "answered with nothing" and "did not answer" as separate states is the
	 * point: with 40 sources in flight, a user who cannot tell them apart has no idea
	 * whether to retry or to accept that the title is not there.
	 */
	data class Answered(val items: List<Manga>) : SourceOutcome

	data class Failed(val message: String) : SourceOutcome
}

/** One source's row in the results. */
data class SourceResult(
	val target: SearchTarget,
	val outcome: SourceOutcome,
) {

	val items: List<Manga>
		get() = (outcome as? SourceOutcome.Answered)?.items.orEmpty()
}

/** The whole picture, recomputed as each source answers. */
data class GlobalSearchState(
	val query: String,
	val results: List<SourceResult>,
) {

	val total: Int get() = results.size

	val answered: Int get() = results.count { it.outcome is SourceOutcome.Answered }

	val failed: Int get() = results.count { it.outcome is SourceOutcome.Failed }

	val running: Int get() = results.count { it.outcome is SourceOutcome.Running }

	val settled: Int get() = answered + failed

	val isFinished: Boolean get() = settled == total

	/** Titles found so far, across every source. */
	val hitCount: Int get() = results.sumOf { it.items.size }

	/** Sources that answered with at least one title, in the order they were given. */
	val groupsWithHits: List<SourceResult> get() = results.filter { it.items.isNotEmpty() }

	val failures: List<SourceResult> get() = results.filter { it.outcome is SourceOutcome.Failed }

	/** True once every source has answered and not one of them found anything. */
	val isEmptyResult: Boolean get() = isFinished && hitCount == 0 && failed < total

	/** True when every source failed, which is a different thing to say to the user. */
	val isTotalFailure: Boolean get() = isFinished && total > 0 && failed == total

	fun progress(): Float = if (total == 0) 1f else settled.toFloat() / total
}

/** What happened to one source. Folded into a [GlobalSearchState] by [GlobalSearchEngine.search]. */
sealed interface SearchEvent {

	val key: String

	data class Started(override val key: String) : SearchEvent

	data class Answered(override val key: String, val items: List<Manga>) : SearchEvent

	data class Failed(override val key: String, val message: String) : SearchEvent
}

/**
 * Searches many sources at once and streams what comes back.
 *
 * Concurrency is capped because the alternative is opening several hundred sockets to
 * several hundred third-party sites at once, which is both slow and rude, and because a
 * user reads the first few groups long before the last source answers. A source that
 * fails is recorded and stepped over; one dead site out of forty must not cost the other
 * thirty-nine, which is why every call is caught individually.
 */
class GlobalSearchEngine(
	private val concurrency: Int = DEFAULT_CONCURRENCY,
) {

	init {
		require(concurrency > 0) { "concurrency must be positive, was $concurrency" }
	}

	/** Raw per-source events, in completion order. */
	fun events(query: String, targets: List<SearchTarget>): Flow<SearchEvent> = channelFlow {
		if (targets.isEmpty()) return@channelFlow
		val permits = Semaphore(concurrency)
		coroutineScope {
			for (target in targets) {
				launch {
					permits.withPermit {
						send(SearchEvent.Started(target.key))
						val event = try {
							SearchEvent.Answered(target.key, target.search(query))
						} catch (e: CancellationException) {
							// The caller stopped us. Not this source's failure, and
							// swallowing it would break cancellation of the whole search.
							throw e
						} catch (e: Throwable) {
							SearchEvent.Failed(target.key, e.describe())
						}
						send(event)
					}
				}
			}
		}
	}

	/**
	 * The accumulated state, emitted once up front and again after every event.
	 *
	 * Emitting whole snapshots rather than deltas is what lets the screen stay usable
	 * while the search runs: it renders the latest snapshot and nothing has to be
	 * replayed or reconciled.
	 */
	fun search(query: String, targets: List<SearchTarget>): Flow<GlobalSearchState> =
		events(query, targets).runningFold(initialState(query, targets), ::reduce)

	companion object {

		/**
		 * Six at a time. Enough that the first screenful fills quickly, few enough that
		 * a laptop on a domestic connection is not saturated by cover images competing
		 * with the searches themselves.
		 */
		const val DEFAULT_CONCURRENCY = 6
	}
}

/** Everything queued, nothing run. Also the state to show for an empty source selection. */
fun initialState(query: String, targets: List<SearchTarget>) = GlobalSearchState(
	query = query,
	results = targets.map { SourceResult(it, SourceOutcome.Waiting) },
)

/** Applies one event. Kept separate from the engine so the fold is testable on its own. */
fun reduce(state: GlobalSearchState, event: SearchEvent): GlobalSearchState {
	val index = state.results.indexOfFirst { it.target.key == event.key }
	if (index < 0) return state
	val outcome = when (event) {
		is SearchEvent.Started -> SourceOutcome.Running
		is SearchEvent.Answered -> SourceOutcome.Answered(event.items)
		is SearchEvent.Failed -> SourceOutcome.Failed(event.message)
	}
	val updated = state.results.toMutableList()
	updated[index] = updated[index].copy(outcome = outcome)
	return state.copy(results = updated)
}

/**
 * A one-line reason, for a row in a list of failures.
 *
 * Sources fail with everything from a socket timeout to a parse error on a redesigned
 * page, and many of those exceptions have no message at all, so the class name is the
 * fallback rather than an empty cell.
 */
internal fun Throwable.describe(): String =
	message?.takeIf { it.isNotBlank() }?.lineSequence()?.first()?.take(MAX_REASON_LENGTH)
		?: this::class.simpleName
		?: "failed"

private const val MAX_REASON_LENGTH = 160
