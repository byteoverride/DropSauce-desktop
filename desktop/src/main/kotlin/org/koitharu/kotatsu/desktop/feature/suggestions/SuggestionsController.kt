package org.koitharu.kotatsu.desktop.feature.suggestions

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/** Where a refresh has got to. Each phase says something different to the user. */
sealed interface RefreshPhase {

	data object Idle : RefreshPhase

	/** Working out what the user likes. [total] is 0 when every seed was already cached. */
	data class Profiling(val done: Int, val total: Int) : RefreshPhase

	data class Fetching(val state: RefreshState) : RefreshPhase

	data class Finished(val state: RefreshState, val stored: Int) : RefreshPhase

	/** The refresh itself broke, as opposed to some of its sources. */
	data class Broken(val message: String) : RefreshPhase
}

/**
 * Drives a refresh and holds its progress.
 *
 * Lives above the screen, on a scope that outlives it, so leaving the tab and coming
 * back shows the refresh still running rather than restarting it. A second refresh
 * cancels the first: two runs writing the same table would interleave, and the sockets
 * are better spent on the one the user just asked for.
 *
 * Manual only. There is no scheduler and no background trigger, per DECISIONS.md D11.
 */
@Stable
class SuggestionsController(
	private val context: FeatureContext,
	private val repository: SuggestionsRepository,
	private val profiler: SuggestionProfiler,
	private val store: SuggestionStore,
	private val engine: SuggestionEngine = SuggestionEngine(),
	private val collector: CandidateCollector = CandidateCollector(),
	private val maxSources: Int = MAX_SOURCES,
	/**
	 * How the chosen sources become fetchers.
	 *
	 * A parameter so a test can run a whole refresh, including the write to the
	 * database, without a parser or a socket. The default is the real thing.
	 */
	private val candidateSources: (List<MangaParserSource>) -> List<CandidateSource> =
		{ collector.sourcesFor(context, it) },
) {

	var phase: RefreshPhase by mutableStateOf(RefreshPhase.Idle)
		private set

	val isRunning: Boolean
		get() = phase is RefreshPhase.Profiling || phase is RefreshPhase.Fetching

	private var job: Job? = null

	/**
	 * Which run a state update belongs to.
	 *
	 * Cancellation is not instant, so a superseded run can still be unwinding when the
	 * next one starts; without this, its last emission would overwrite the new run's.
	 */
	@Volatile
	private var generation = 0

	/** Returns the run's job, so a caller that needs to wait for it can. */
	fun refresh(scope: CoroutineScope = context.scope): Job {
		job?.cancel()
		val mine = ++generation
		collector.reset()
		phase = RefreshPhase.Profiling(0, 0)
		val started = scope.launch {
			try {
				val profile = profiler.profile { done, total ->
					if (generation == mine) phase = RefreshPhase.Profiling(done, total)
				}
				if (generation != mine) return@launch
				val targets = candidateSources(pickSources(profile))
				var last: RefreshState? = null
				engine.refresh(profile, targets, store.dismissed).collect { state ->
					last = state
					if (generation == mine) phase = RefreshPhase.Fetching(state)
				}
				val settled = last ?: return@launch
				if (generation != mine) return@launch
				val stored = persist(settled)
				phase = RefreshPhase.Finished(settled, stored)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				// The run itself, not one source. A source failure never reaches here;
				// the engine records those and carries on.
				if (generation == mine) phase = RefreshPhase.Broken(e.describe())
			}
		}
		job = started
		return started
	}

	/** Stops the run but keeps whatever arrived, which is still worth showing. */
	fun stop() {
		generation++
		job?.cancel()
		job = null
		val current = phase
		phase = if (current is RefreshPhase.Fetching) {
			RefreshPhase.Finished(current.state, stored = 0)
		} else {
			RefreshPhase.Idle
		}
	}

	suspend fun dismiss(mangaId: Long) = repository.dismiss(mangaId)

	/**
	 * Writes the ranked set, dropping anything whose full title was lost.
	 *
	 * A candidate with no recorded [org.koitharu.kotatsu.parsers.model.Manga] cannot be
	 * stored: the table keeps only an id and the grid reads the title back out of
	 * `manga`, so a row with no manga row behind it would render as a gap.
	 */
	private suspend fun persist(state: RefreshState): Int {
		val entries = state.suggestions.mapNotNull { scored ->
			collector.manga(scored.candidate.mangaId)?.let { manga ->
				StoredSuggestion(manga = manga, relevance = scored.relevance, reason = scored.reason)
			}
		}
		repository.replace(entries)
		return entries.size
	}

	/**
	 * Which sources to ask, out of the hundreds the catalogue offers.
	 *
	 * Sources the user already reads come first, because a recommendation from a site
	 * they cannot open is worthless. The rest is a shuffled sample, deliberately: asking
	 * the same twelve sites on every refresh would return roughly the same titles
	 * forever, and the point of a manual refresh is to see something new.
	 *
	 * Hidden sources never appear. The filter comes from the contract rather than being
	 * re-derived here, so it cannot disagree with the catalogue screen.
	 */
	private fun pickSources(profile: TasteProfile): List<MangaParserSource> {
		val visible = context.visibleSources()
		if (visible.isEmpty()) return emptyList()
		val familiar = visible.filter { profile.sourceAffinity(it.name) > 0f }
		val rest = visible.filterNot { it in familiar }.shuffled()
		return (familiar + rest).take(maxSources)
	}

	companion object {

		/**
		 * How many sources one refresh asks.
		 *
		 * Four run at a time, so twelve is three rounds. Enough for variety, few enough
		 * that a refresh finishes while the user is still looking at the screen.
		 */
		const val MAX_SOURCES = 12
	}
}
