package org.koitharu.kotatsu.desktop.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.search.MAX_FRUITLESS_PAGES
import org.koitharu.kotatsu.desktop.search.rankForQuery
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LoadingBox
import org.koitharu.kotatsu.desktop.ui.onEnter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/** What is known about a source's filtering, once the parser has been asked. */
private sealed interface SpecState {

	data object Loading : SpecState

	data class Ready(val spec: FilterSpec, val optionsFailed: String?) : SpecState
}

/**
 * Browse one source with its real filters.
 *
 * Paging is offset-based because that is the shape the parser API exposes, and an empty
 * page is the only end-of-data signal it gives. A failure part-way through paging keeps
 * what has already loaded and offers a retry under the grid, rather than throwing away
 * a screenful of results the user was reading.
 */
@Composable
fun SourceBrowseScreen(
	context: FeatureContext,
	source: MangaParserSource,
	onOpen: (Manga) -> Unit,
	modifier: Modifier = Modifier,
) {
	val session = remember(source) { context.sources.session(source) }
	var specState: SpecState by remember(source) { mutableStateOf(SpecState.Loading) }
	var showFilters by remember(source) { mutableStateOf(false) }
	var draft by remember(source) { mutableStateOf(FilterSelection()) }
	var applied by remember(source) { mutableStateOf(FilterSelection()) }
	var queryText by remember(source) { mutableStateOf("") }

	LaunchedEffect(source) {
		// filterCapabilities and availableSortOrders are plain properties, but the option
		// lists are a network call, and a source that cannot serve them can still be
		// browsed. So a failure here degrades the panel instead of the screen.
		val capabilities = session.parser.filterCapabilities
		val orders = session.parser.availableSortOrders
		val loaded = runCatching {
			withContext(Dispatchers.IO) { session.parser.getFilterOptions() }
		}
		specState = SpecState.Ready(
			spec = FilterSpec.of(
				capabilities = capabilities,
				options = loaded.getOrNull(),
				availableSortOrders = orders,
			),
			optionsFailed = loaded.exceptionOrNull()?.describe(),
		)
	}

	val ready = specState as? SpecState.Ready

	Column(modifier.fillMaxSize()) {
		BrowseToolbar(
			source = source,
			spec = ready?.spec,
			queryText = queryText,
			onQueryText = { queryText = it },
			onSubmitQuery = {
				val next = draft.copy(query = queryText.trim())
				draft = next
				applied = ready?.spec?.sanitize(next) ?: next
			},
			showFilters = showFilters,
			onToggleFilters = { showFilters = !showFilters },
			appliedCount = ready?.let { countActive(it.spec.sanitize(applied)) } ?: 0,
		)
		if (ready == null) {
			LoadingBox()
			return@Column
		}
		val spec = ready.spec
		ready.optionsFailed?.let { reason ->
			Text(
				text = "This source did not return its filter lists ($reason), so only the " +
					"controls that need no list are available.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
			)
		}
		Row(Modifier.fillMaxSize()) {
			if (showFilters) {
				FilterPanel(
					spec = spec,
					draft = draft,
					applied = applied,
					onDraftChange = { draft = it },
					onApply = {
						val sanitized = spec.sanitize(draft)
						// The panel may have had to drop the query, so keep the box honest.
						queryText = sanitized.query
						draft = sanitized
						applied = sanitized
					},
					modifier = Modifier.fillMaxHeight(),
				)
				VerticalDivider()
			}
			ResultGrid(
				context = context,
				source = source,
				spec = spec,
				applied = applied,
				onOpen = onOpen,
				modifier = Modifier.weight(1f),
			)
		}
	}
}

@Composable
private fun BrowseToolbar(
	source: MangaParserSource,
	spec: FilterSpec?,
	queryText: String,
	onQueryText: (String) -> Unit,
	onSubmitQuery: () -> Unit,
	showFilters: Boolean,
	onToggleFilters: () -> Unit,
	appliedCount: Int,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Column(Modifier.weight(1f)) {
			Text(source.title, style = MaterialTheme.typography.titleMedium)
			Text(
				text = buildString {
					append(source.contentType.name.lowercase())
					if (source.locale.isNotEmpty()) append("  ").append(source.locale)
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		if (spec != null && FilterControl.QUERY in spec) {
			OutlinedTextField(
				value = queryText,
				onValueChange = onQueryText,
				label = { Text("Search this source, then press Enter") },
				singleLine = true,
				modifier = Modifier.weight(2f).onEnter(onSubmitQuery),
			)
		} else if (spec != null) {
			Text(
				text = "No text search here",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		TextButton(onClick = onToggleFilters) {
			Text(
				when {
					showFilters -> "Hide filters"
					appliedCount > 0 -> "Filters ($appliedCount)"
					else -> "Filters"
				},
			)
		}
	}
}

@Composable
private fun ResultGrid(
	context: FeatureContext,
	source: MangaParserSource,
	spec: FilterSpec,
	applied: FilterSelection,
	onOpen: (Manga) -> Unit,
	modifier: Modifier = Modifier,
) {
	val session = remember(source) { context.sources.session(source) }
	val items = remember(source, applied) { mutableStateListOf<Manga>() }
	var loading by remember(source, applied) { mutableStateOf(false) }
	var exhausted by remember(source, applied) { mutableStateOf(false) }
	var error: String? by remember(source, applied) { mutableStateOf(null) }
	var showLoose by remember(source, applied) { mutableStateOf(false) }
	/**
	 * Consecutive pages that answered the query with nothing.
	 *
	 * A loose source asked for a title it does not have answers with its front page and
	 * keeps answering, so paging towards a screenful of matches would walk the whole site.
	 * After [MAX_FRUITLESS_PAGES] the screen stops and offers the choice instead.
	 */
	var fruitlessPages by remember(source, applied) { mutableStateOf(0) }
	val uiScope = rememberCoroutineScope()

	// What the source said, ranked against the query; see rankForQuery.
	//
	// derivedStateOf rather than a plain val: the paging collector below runs in a
	// coroutine that outlives the composition it started in, and a plain val would be
	// captured once, at a point where the list is still empty. These read live there.
	val ranked by remember(source, applied) {
		derivedStateOf { rankForQuery(applied.query, items.toList()) }
	}
	val shown by remember(source, applied) {
		derivedStateOf { if (showLoose) ranked.all else ranked.shown }
	}

	suspend fun loadMore() {
		if (loading || exhausted) return
		loading = true
		error = null
		val offset = items.size
		runCatching {
			withContext(Dispatchers.IO) {
				session.parser.getList(offset, spec.sortOrderFor(applied), spec.toFilter(applied))
			}
		}.onSuccess { page ->
			if (page.isEmpty()) {
				exhausted = true
			} else {
				items.addAll(page)
				fruitlessPages = if (rankForQuery(applied.query, page).shown.isEmpty()) {
					fruitlessPages + 1
				} else {
					0
				}
			}
		}.onFailure { e ->
			error = e.describe()
		}
		loading = false
	}

	val stalled by remember(source, applied) {
		derivedStateOf { fruitlessPages >= MAX_FRUITLESS_PAGES }
	}

	val gridState = rememberLazyGridState()
	LaunchedEffect(source, applied) { loadMore() }
	LaunchedEffect(gridState, source, applied) {
		snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
			.distinctUntilChanged()
			.collect { last ->
				// Only page on once the first page is in; otherwise an empty grid at
				// index 0 asks for more before anything has arrived. Measured against
				// what is on screen rather than what was fetched: with the unrelated rows
				// held back those two numbers differ, and paging off the fetched count
				// would stop while the grid still had room.
				if (items.isNotEmpty() && error == null && !stalled &&
					last >= shown.size - GRID_PREFETCH_DISTANCE
				) {
					loadMore()
				}
			}
	}

	Column(modifier.fillMaxSize()) {
		if (ranked.hiddenCount > 0) {
			Row(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = "${ranked.hiddenCount} of ${items.size} returned " +
						"${if (ranked.hiddenCount == 1) "title has" else "titles have"} nothing to do " +
						"with \"${applied.query}\"",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.weight(1f),
				)
				TextButton(onClick = { showLoose = !showLoose }) {
					Text(if (showLoose) "Hide them" else "Show them")
				}
			}
		}
		if (stalled && !exhausted) {
			Row(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = "Stopped paging: the last $MAX_FRUITLESS_PAGES pages held no match for " +
						"\"${applied.query}\".",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.weight(1f),
				)
				TextButton(
					onClick = {
						fruitlessPages = 0
						uiScope.launch { loadMore() }
					},
				) {
					Text("Keep looking")
				}
			}
		}
		if (loading && items.isNotEmpty()) {
			// A thin "more on the way" line above the grid, so the page the user is
			// reading does not move while the next one loads.
			Row(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				CircularProgressIndicator(modifier = Modifier.size(14.dp))
				Text(
					text = "Loading more",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		when {
			items.isEmpty() && loading -> LoadingBox()

			items.isEmpty() && error != null -> ErrorBox(
				message = "This source rejected that request.\n$error",
				onRetry = { uiScope.launch { loadMore() } },
			)

			items.isEmpty() -> Box(Modifier.fillMaxSize()) {
				Text(
					text = if (applied.isEmpty) {
						"This source returned nothing at all."
					} else {
						"Nothing matches those filters."
					},
					modifier = Modifier.padding(24.dp),
					style = MaterialTheme.typography.bodyMedium,
				)
			}

			shown.isEmpty() -> Box(Modifier.fillMaxSize()) {
				// Rows came back and every one of them was held back, which is a different
				// thing to say than "nothing found": the toggle above is the way out.
				Text(
					text = "${items.size} ${if (items.size == 1) "title" else "titles"} came back " +
						"and none of them matches \"${applied.query}\".",
					modifier = Modifier.padding(24.dp),
					style = MaterialTheme.typography.bodyMedium,
				)
			}

			else -> Column(Modifier.fillMaxSize()) {
				if (error != null) {
					// Paging failed with results already on screen. Keep them.
					Row(
						modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text(
							text = "Could not load more: $error",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.error,
							modifier = Modifier.weight(1f),
						)
						TextButton(onClick = { uiScope.launch { loadMore() } }) { Text("Retry") }
					}
				}
				LazyVerticalGrid(
					columns = GridCells.Adaptive(minSize = 150.dp),
					state = gridState,
					contentPadding = PaddingValues(12.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					verticalArrangement = Arrangement.spacedBy(12.dp),
					modifier = Modifier.fillMaxSize(),
				) {
					itemsIndexed(shown, key = { i, m -> "${m.id}-$i" }) { _, manga ->
						CoverCard(
							manga = manga,
							client = session.client,
							images = context.images,
							onClick = { onOpen(manga) },
							modifier = Modifier.fillMaxWidth(),
						)
					}
				}
			}
		}
	}
}

/** How many filters are active, for the toolbar badge. */
internal fun countActive(selection: FilterSelection): Int = listOf(
	selection.tags.isNotEmpty(),
	selection.tagsExclude.isNotEmpty(),
	selection.states.isNotEmpty(),
	selection.contentRating.isNotEmpty(),
	selection.types.isNotEmpty(),
	selection.demographics.isNotEmpty(),
	selection.locale != null,
	selection.originalLocale != null,
	selection.year != 0,
	selection.yearFrom != 0 || selection.yearTo != 0,
	selection.author.isNotBlank(),
).count { it }
