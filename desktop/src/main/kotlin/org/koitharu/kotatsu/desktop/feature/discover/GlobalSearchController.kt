package org.koitharu.kotatsu.desktop.feature.discover

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Holds a running global search.
 *
 * Lives above the screen and runs on a scope that outlives it, so moving to another tab
 * and back shows the search still going rather than restarting it. A second search
 * cancels the first: two sets of results on one screen would be unreadable, and the
 * sockets are better spent on what the user just asked for.
 */
@Stable
class GlobalSearchController(
	private val scope: CoroutineScope,
	private val engine: GlobalSearchEngine = GlobalSearchEngine(),
) {

	var state: GlobalSearchState? by mutableStateOf(null)
		private set

	var isRunning: Boolean by mutableStateOf(false)
		private set

	private var job: Job? = null

	/**
	 * Which search the collector belongs to.
	 *
	 * Cancellation is not instant, so a superseded run can still be unwinding when the
	 * next one starts; without this, its `finally` would clear the new run's flag.
	 */
	@Volatile
	private var generation = 0

	fun start(query: String, targets: List<SearchTarget>) {
		job?.cancel()
		val mine = ++generation
		state = initialState(query, targets)
		isRunning = targets.isNotEmpty()
		job = scope.launch {
			try {
				engine.search(query, targets).collect { snapshot ->
					if (generation == mine) state = snapshot
				}
			} finally {
				if (generation == mine) isRunning = false
			}
		}
	}

	/** Stops the search but keeps what has arrived so far, which is still useful. */
	fun stop() {
		generation++
		job?.cancel()
		job = null
		isRunning = false
	}
}

/**
 * Turns sources into things the engine can run.
 *
 * The engine is given functions rather than parsers so it stays testable without a
 * network; this is the one place that knows a target is really an HTTP call. Building
 * the parser happens inside the IO dispatcher because creating one is not free and, for
 * some sources, touches the config file.
 */
fun FeatureContext.searchTargets(candidates: List<MangaParserSource>): List<SearchTarget> =
	candidates.map { source ->
		SearchTarget(key = source.name, title = source.title) { query ->
			withContext(Dispatchers.IO) {
				val parser = sources.session(source).parser
				val capabilities = parser.filterCapabilities
				if (!capabilities.isSearchSupported) {
					// Reported as a failure rather than an empty answer: "this source
					// cannot be searched" and "this source has nothing" are different
					// facts and the results screen shows them differently.
					throw UnsupportedOperationException("no text search on this source")
				}
				val spec = FilterSpec.of(capabilities, options = null, availableSortOrders = parser.availableSortOrders)
				parser.getList(0, spec.defaultSort(isSearch = true), MangaListFilter(query = query))
			}
		}
	}
