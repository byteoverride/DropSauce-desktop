package org.koitharu.kotatsu.desktop.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.onEnter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Search many sources at once.
 *
 * The search runs in [GlobalSearchController], which is held above this screen, so
 * switching to the browse tab and back does not restart it. Results appear group by
 * group as sources answer; the screen is never blocked on the slowest one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlobalSearchScreen(
	context: FeatureContext,
	controller: GlobalSearchController,
	store: DiscoverStore,
	selectableSources: List<MangaParserSource>,
	selected: Set<MangaParserSource>,
	onSelectedChange: (Set<MangaParserSource>) -> Unit,
	onOpen: (MangaParserSource, Manga) -> Unit,
	modifier: Modifier = Modifier,
) {
	val prefs by store.data.collectAsState()
	var queryText by remember { mutableStateOf(controller.state?.query.orEmpty()) }
	var showSources by remember { mutableStateOf(false) }
	val uiScope = rememberCoroutineScope()

	fun submit(raw: String) {
		val query = raw.trim()
		if (query.isEmpty() || selected.isEmpty()) return
		queryText = query
		uiScope.launch {
			store.recordQuery(query)
			store.rememberSources(selected.map { it.name })
		}
		controller.start(query, context.searchTargets(selectableSources.filter { it in selected }))
	}

	Column(modifier.fillMaxSize()) {
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			OutlinedTextField(
				value = queryText,
				onValueChange = { queryText = it },
				label = { Text("Search every selected source, then press Enter") },
				singleLine = true,
				modifier = Modifier.weight(1f).onEnter { submit(queryText) },
			)
			Button(
				onClick = { submit(queryText) },
				enabled = queryText.isNotBlank() && selected.isNotEmpty(),
			) {
				Text("Search")
			}
			if (controller.isRunning) {
				TextButton(onClick = { controller.stop() }) { Text("Stop") }
			}
			TextButton(onClick = { showSources = !showSources }) {
				Text(if (showSources) "Hide sources" else "Sources (${selected.size})")
			}
		}

		if (selected.isEmpty()) {
			Text(
				text = "No sources selected. Open the source list and tick a few.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
				modifier = Modifier.padding(horizontal = 16.dp),
			)
		}

		if (prefs.recentQueries.isNotEmpty()) {
			FlowRow(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = "Recent",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 10.dp),
				)
				for (recent in prefs.recentQueries) {
					FilterChip(
						selected = false,
						onClick = { submit(recent) },
						label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
					)
				}
				TextButton(onClick = { uiScope.launch { store.clearHistory() } }) { Text("Clear") }
			}
		}

		if (showSources) {
			SourcePicker(
				sources = selectableSources,
				selected = selected,
				onSelectedChange = onSelectedChange,
			)
			HorizontalDivider()
		}

		val state = controller.state
		if (state == null) {
			Box(Modifier.fillMaxSize()) {
				Text(
					text = "Type a title. Every selected source is asked at once and the " +
						"answers arrive as they come.",
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(24.dp),
				)
			}
		} else {
			SearchResults(
				context = context,
				state = state,
				onOpen = onOpen,
				onRetry = { submit(state.query) },
			)
		}
	}
}

@Composable
private fun SearchResults(
	context: FeatureContext,
	state: GlobalSearchState,
	onOpen: (MangaParserSource, Manga) -> Unit,
	onRetry: () -> Unit,
) {
	val collapsed = remember(state.query) { mutableStateMapOf<String, Boolean>() }
	var showFailures by remember(state.query) { mutableStateOf(false) }

	Column(Modifier.fillMaxSize()) {
		ProgressBar(
			fraction = state.progress(),
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
		)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			if (!state.isFinished) CircularProgressIndicator(modifier = Modifier.size(14.dp))
			Text(
				text = buildString {
					append(state.hitCount)
					append(if (state.hitCount == 1) " title from " else " titles from ")
					append(state.groupsWithHits.size)
					append(if (state.groupsWithHits.size == 1) " source" else " sources")
					append("  ·  ")
					append(state.settled)
					append(" of ")
					append(state.total)
					append(" answered")
					if (state.failed > 0) {
						append("  ·  ")
						append(state.failed)
						append(" failed")
					}
				},
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.weight(1f),
			)
			if (state.failed > 0) {
				TextButton(onClick = { showFailures = !showFailures }) {
					Text(if (showFailures) "Hide failures" else "Show failures")
				}
			}
		}

		if (showFailures && state.failures.isNotEmpty()) {
			// Above the result list rather than inside it. A lazy list anchors on its
			// first visible item, so rows prepended to it land off-screen and the button
			// looks like it did nothing.
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = FAILURE_LIST_HEIGHT)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp),
			) {
				for (result in state.failures) {
					val reason = (result.outcome as? SourceOutcome.Failed)?.message.orEmpty()
					Text(
						text = "${result.target.title}: $reason",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.padding(vertical = 2.dp),
					)
				}
			}
			HorizontalDivider(Modifier.padding(vertical = 6.dp))
		}

		when {
			state.isTotalFailure -> ErrorBox(
				message = "Every one of the ${state.total} sources failed for " +
					"\"${state.query}\". That usually means the network is down, not the sources.",
				onRetry = onRetry,
			)

			state.isEmptyResult -> Box(Modifier.fillMaxSize()) {
				Text(
					text = "All ${state.total} sources answered and none of them has " +
						"\"${state.query}\".",
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(24.dp),
				)
			}

			else -> LazyColumn(
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(bottom = 24.dp),
			) {
				items(state.groupsWithHits, key = { it.target.key }) { group ->
					SourceGroup(
						context = context,
						group = group,
						isCollapsed = collapsed[group.target.key] == true,
						onToggle = {
							collapsed[group.target.key] = collapsed[group.target.key] != true
						},
						onOpen = onOpen,
					)
				}
				if (!state.isFinished) {
					item {
						Text(
							text = "Still asking ${state.total - state.settled} more",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(16.dp),
						)
					}
				}
			}
		}
	}
}

/** One source's hits, as a collapsible strip. */
@Composable
private fun SourceGroup(
	context: FeatureContext,
	group: SourceResult,
	isCollapsed: Boolean,
	onToggle: () -> Unit,
	onOpen: (MangaParserSource, Manga) -> Unit,
) {
	val source = remember(group.target.key) { sourceOf(group.target.key) }
	Column(Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable(onClick = onToggle)
				.padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(if (isCollapsed) "▸" else "▾", style = MaterialTheme.typography.bodyMedium)
			Text(group.target.title, style = MaterialTheme.typography.titleSmall)
			Text(
				text = "${group.items.size}",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		if (!isCollapsed && source != null) {
			val client = remember(source) { context.clientFor(source) }
			LazyRow(
				modifier = Modifier.fillMaxWidth(),
				contentPadding = PaddingValues(horizontal = 16.dp),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				itemsIndexed(group.items, key = { i, m -> "${m.id}-$i" }) { _, manga ->
					CoverCard(
						manga = manga,
						client = client,
						images = context.images,
						onClick = { onOpen(source, manga) },
						modifier = Modifier.width(STRIP_ITEM_WIDTH),
					)
				}
			}
		}
		HorizontalDivider(Modifier.padding(top = 8.dp))
	}
}

/** Tick the sources a global search covers. */
@Composable
private fun SourcePicker(
	sources: List<MangaParserSource>,
	selected: Set<MangaParserSource>,
	onSelectedChange: (Set<MangaParserSource>) -> Unit,
) {
	var filter by remember { mutableStateOf("") }
	val shown = remember(sources, filter) {
		val needle = filter.trim().lowercase()
		if (needle.isEmpty()) {
			sources
		} else {
			sources.filter { it.title.lowercase().contains(needle) || it.locale.lowercase() == needle }
		}
	}
	Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			OutlinedTextField(
				value = filter,
				onValueChange = { filter = it },
				placeholder = { Text("Filter sources by name or language code") },
				singleLine = true,
				modifier = Modifier.weight(1f),
			)
			TextButton(
				onClick = { onSelectedChange(selected + shown.take(MAX_SELECTABLE - selected.size)) },
				enabled = selected.size < MAX_SELECTABLE,
			) {
				Text("Add shown")
			}
			TextButton(onClick = { onSelectedChange(emptySet()) }) { Text("None") }
		}
		if (selected.size >= MAX_SELECTABLE) {
			// Not a technical limit: the engine would happily queue 900. It is that
			// nobody reads 900 groups, and every extra source is a request to a site
			// that did not ask to be scraped.
			Text(
				text = "$MAX_SELECTABLE sources is the practical ceiling for one search.",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		LazyColumn(Modifier.fillMaxWidth().heightIn(max = SOURCE_PICKER_HEIGHT)) {
			items(shown, key = { it.name }) { source ->
				val isOn = source in selected
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clickable {
							onSelectedChange(
								if (isOn) {
									selected - source
								} else {
									if (selected.size >= MAX_SELECTABLE) selected else selected + source
								},
							)
						}
						.padding(vertical = 2.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Checkbox(checked = isOn, onCheckedChange = null)
					Text(source.title, style = MaterialTheme.typography.bodyMedium)
					Text(
						text = source.locale,
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

private fun sourceOf(key: String): MangaParserSource? =
	MangaParserSource.entries.firstOrNull { it.name == key }

private val SOURCE_PICKER_HEIGHT = 240.dp

/** Enough for a handful of failures without pushing the results off the screen. */
private val FAILURE_LIST_HEIGHT = 140.dp

/** See the comment at the call site: a readability ceiling, not a technical one. */
const val MAX_SELECTABLE = 40
