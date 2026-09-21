package org.koitharu.kotatsu.desktop.feature.suggestions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One source to ask for candidates, and how to ask it.
 *
 * A function rather than a parser, so the engine has no idea what a source is and a test
 * can drive a whole refresh without a network. [key] is the enum name, stable across
 * runs; [title] is what a person reads.
 *
 * The function is handed the tag titles the profile cares about, strongest first. What a
 * source does with them is its own business: some can filter by tag, some can only be
 * browsed by popularity, and the engine must work either way.
 */
class CandidateSource(
	val key: String,
	val title: String,
	val fetch: suspend (wantedTags: List<String>) -> List<Candidate>,
)

/** How one source answered. */
sealed interface FetchOutcome {

	/** Queued, not started: only a few sources run at a time. */
	data object Waiting : FetchOutcome

	data object Running : FetchOutcome

	/** Answered. An empty list is an answer, not a failure. */
	data class Answered(val items: List<Candidate>) : FetchOutcome

	data class Failed(val message: String) : FetchOutcome
}

data class SourceProgress(val source: CandidateSource, val outcome: FetchOutcome) {

	val items: List<Candidate> get() = (outcome as? FetchOutcome.Answered)?.items.orEmpty()
}

/**
 * The whole refresh, recomputed as each source answers.
 *
 * Whole snapshots rather than deltas: the screen renders the latest one and nothing has
 * to be replayed. [suggestions] is re-ranked on every event, which is cheap because
 * ranking is a pure fold over a few hundred candidates and is what lets the grid fill in
 * while slow sources are still running.
 */
data class RefreshState(
	val profile: TasteProfile,
	val progress: List<SourceProgress>,
	val suggestions: List<ScoredSuggestion>,
) {

	val total: Int get() = progress.size

	val answered: Int get() = progress.count { it.outcome is FetchOutcome.Answered }

	val failed: Int get() = progress.count { it.outcome is FetchOutcome.Failed }

	val settled: Int get() = answered + failed

	val isFinished: Boolean get() = settled == total

	val failures: List<SourceProgress> get() = progress.filter { it.outcome is FetchOutcome.Failed }

	/** Raw candidates seen so far, before scoring. Useful for saying why nothing survived. */
	val candidatesSeen: Int get() = progress.sumOf { it.items.size }

	fun fraction(): Float = if (total == 0) 1f else settled.toFloat() / total

	/**
	 * Why the grid is empty, or null when it is not.
	 *
	 * Spelled out rather than left blank because every one of these is a different thing
	 * for the user to do next, and an empty grid with no sentence under it reads as a
	 * broken screen.
	 */
	fun emptyExplanation(): String? = when {
		profile.isEmpty -> "Nothing to go on yet. Add a title to your library or read a " +
			"few chapters, then refresh and this fills in."

		!isFinished -> null
		suggestions.isNotEmpty() -> null
		total == 0 -> "No sources to ask. Every source is hidden by your catalogue settings."
		failed == total -> "Every source failed. Check the connection and try again."
		candidatesSeen == 0 -> "The sources answered but had nothing to offer."
		else -> "Everything the sources returned is already in your library, was dismissed, " +
			"or shares nothing with what you read."
	}
}

/** What happened to one source. Folded into a [RefreshState] by [SuggestionEngine.refresh]. */
sealed interface FetchEvent {

	val key: String

	data class Started(override val key: String) : FetchEvent

	data class Answered(override val key: String, val items: List<Candidate>) : FetchEvent

	data class Failed(override val key: String, val message: String) : FetchEvent
}

/**
 * Asks several sources for candidates at once and scores what comes back.
 *
 * Concurrency is capped because the alternative is several hundred sockets to several
 * hundred third-party sites, which is slow and rude, and because the user reads the
 * first rows long before the last source answers. A source that fails is recorded and
 * stepped over: one dead site must not cost the others, which is why every call is
 * caught individually rather than letting one exception unwind the scope.
 *
 * Nothing here touches the database or the disk. Persisting the result is the
 * repository's job, and keeping that out means a refresh can be run end to end in a test
 * with three fake sources and no temp files.
 */
class SuggestionEngine(
	private val concurrency: Int = DEFAULT_CONCURRENCY,
	private val limit: Int = MAX_SUGGESTIONS,
) {

	init {
		require(concurrency > 0) { "concurrency must be positive, was $concurrency" }
	}

	/** Raw per-source events, in completion order. */
	fun events(sources: List<CandidateSource>, wantedTags: List<String>): Flow<FetchEvent> =
		channelFlow {
			if (sources.isEmpty()) return@channelFlow
			val permits = Semaphore(concurrency)
			coroutineScope {
				for (source in sources) {
					launch {
						permits.withPermit {
							send(FetchEvent.Started(source.key))
							val event = try {
								FetchEvent.Answered(source.key, source.fetch(wantedTags))
							} catch (e: CancellationException) {
								// The caller stopped us. Not this source's failure, and
								// swallowing it would break cancellation of the refresh.
								throw e
							} catch (e: Throwable) {
								FetchEvent.Failed(source.key, e.describe())
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
	 * An empty profile short-circuits to a single state rather than fetching: there is
	 * nothing to score against, so every request would be wasted and every answer
	 * discarded. The state still carries [RefreshState.emptyExplanation], so the screen
	 * can say so.
	 */
	fun refresh(
		profile: TasteProfile,
		sources: List<CandidateSource>,
		dismissed: Set<Long> = emptySet(),
	): Flow<RefreshState> {
		val wanted = profile.rankedTags.take(TAGS_TO_ASK_FOR).map { it.label }
		val initial = RefreshState(
			profile = profile,
			progress = if (profile.isEmpty) emptyList() else sources.map { SourceProgress(it, FetchOutcome.Waiting) },
			suggestions = emptyList(),
		)
		if (profile.isEmpty) return flowOf(initial)
		return events(sources, wanted).runningFold(initial) { state, event ->
			reduce(state, event, dismissed, limit)
		}
	}

	companion object {

		/** Four at a time, as decided for the desktop refresh. */
		const val DEFAULT_CONCURRENCY = 4

		/**
		 * How many of the profile's tags to hand a source.
		 *
		 * More than a handful is pointless: a source can usually filter on one tag, so
		 * the rest are only there as fallbacks when the top tag is not in its vocabulary.
		 */
		const val TAGS_TO_ASK_FOR = 10
	}
}

/**
 * Applies one event and re-ranks.
 *
 * Separate from the engine so the fold is testable on its own, which is how the
 * "one source throwing does not lose the others" property is checked without timing.
 */
fun reduce(
	state: RefreshState,
	event: FetchEvent,
	dismissed: Set<Long> = emptySet(),
	limit: Int = MAX_SUGGESTIONS,
): RefreshState {
	val index = state.progress.indexOfFirst { it.source.key == event.key }
	if (index < 0) return state
	val outcome = when (event) {
		is FetchEvent.Started -> FetchOutcome.Running
		is FetchEvent.Answered -> FetchOutcome.Answered(event.items)
		is FetchEvent.Failed -> FetchOutcome.Failed(event.message)
	}
	val progress = state.progress.toMutableList()
	progress[index] = progress[index].copy(outcome = outcome)
	// Re-ranked from every source that has answered, not appended to. A candidate the
	// user already has can arrive from a second source, and appending would let the
	// duplicate through.
	val suggestions = rank(
		profile = state.profile,
		candidates = progress.flatMap { it.items },
		dismissed = dismissed,
		limit = limit,
	)
	return state.copy(progress = progress, suggestions = suggestions)
}

/**
 * A one-line reason for a row in a list of failures.
 *
 * Sources fail with everything from a socket timeout to a parse error on a redesigned
 * page, and many of those exceptions carry no message at all, so the class name is the
 * fallback rather than an empty cell.
 */
internal fun Throwable.describe(): String =
	message?.takeIf { it.isNotBlank() }?.lineSequence()?.first()?.take(MAX_REASON_LENGTH)
		?: this::class.simpleName
		?: "failed"

private const val MAX_REASON_LENGTH = 160
