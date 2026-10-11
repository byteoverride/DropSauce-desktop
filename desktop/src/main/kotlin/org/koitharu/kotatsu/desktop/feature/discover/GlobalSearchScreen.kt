package org.koitharu.kotatsu.desktop.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.ui.AppIcon
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.ChipRow
import org.koitharu.kotatsu.desktop.ui.CoverCard
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LoadingStrip
import org.koitharu.kotatsu.desktop.ui.NoticeBar
import org.koitharu.kotatsu.desktop.ui.SectionHeader
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.ThumbSize
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
			modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.sm),
		) {
			OutlinedTextField(
				value = queryText,
				onValueChange = { queryText = it },
				label = { Text("Search every selected source, then press Enter") },
				leadingIcon = { AppIcon(AppIcons.DISCOVER, contentDescription = null) },
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
			// Which sources are searched is the most consequential setting on this screen,
			// so it is a control that names its own count rather than a link that reads
			// like one more piece of chrome.
			OutlinedButton(onClick = { showSources = true }) {
				AppIcon(AppIcons.FILTER, contentDescription = null)
				Spacer(Modifier.width(Space.xs))
				Text(if (selected.size == 1) "1 source" else "${selected.size} sources")
			}
		}

		if (selected.isEmpty()) {
			// Was `bodySmall` in the error colour with no icon and no container: a
			// colour-only cue for the one state in which the screen cannot work at all,
			// and with no way out of it.
			NoticeBar(
				text = "No sources are selected, so there is nothing to search.",
				icon = AppIcons.WARNING,
				action = {
					TextButton(onClick = { showSources = true }) { Text("Choose sources") }
				},
			)
		}

		if (prefs.recentQueries.isNotEmpty()) {
			// Clear sits outside the chip flow rather than at the end of it. Twenty
			// queries are kept, so inside the flow it would be the first thing the
			// overflow swallowed, which is the one control that should never be behind
			// the overflow of the list it clears.
			Row(
				modifier = Modifier.fillMaxWidth().padding(end = Space.lg),
				verticalAlignment = Alignment.CenterVertically,
			) {
				ChipRow(label = "Recent", collapsedRows = 2, modifier = Modifier.weight(1f)) {
					for (recent in prefs.recentQueries) {
						FilterChip(
							selected = false,
							onClick = { submit(recent) },
							label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
						)
					}
				}
				TextButton(onClick = { uiScope.launch { store.clearHistory() } }) { Text("Clear") }
			}
		}

		val state = controller.state
		if (state == null) {
			EmptyBox(
				headline = "Search every source at once",
				detail = "Type a title and press Enter. " +
					(if (selected.size == 1) "1 source is" else "${selected.size} sources are") +
					" selected; each is asked at once and the answers arrive as they come.",
				icon = AppIcons.DISCOVER,
				action = { Button(onClick = { showSources = true }) { Text("Choose sources") } },
			)
		} else {
			SearchResults(
				context = context,
				state = state,
				onOpen = onOpen,
				onRetry = { submit(state.query) },
			)
		}
	}

	if (showSources) {
		// A modal rather than an inline panel: opening it used to push the results it was
		// about down the screen, so the two things the user was comparing could not be on
		// screen together.
		SourcePickerDialog(
			sources = selectableSources,
			selected = selected,
			onSelectedChange = onSelectedChange,
			onClose = { showSources = false },
		)
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
	var showLoose by remember(state.query) { mutableStateOf(false) }

	// Sources answer a query loosely, so what came back is ranked against it and the rows
	// that answer nothing are held back behind a count. See rankForQuery.
	val groups = remember(state) { state.rankGroups() }
	val matched = groups.sumOf { it.ranked.shown.size }
	val loose = groups.sumOf { it.ranked.hiddenCount }
	val visibleGroups = if (showLoose) groups else groups.filter { it.ranked.shown.isNotEmpty() }
	val summary = buildString {
		val sources = groups.count { it.ranked.shown.isNotEmpty() }
		append(matched)
		append(if (matched == 1) " match from " else " matches from ")
		append(sources)
		append(if (sources == 1) " source" else " sources")
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
	}

	Column(Modifier.fillMaxSize()) {
		// A hand-drawn progress bar, a 14 dp spinner and the summary text were three
		// stacked things reporting one fact. While sources are still answering that is a
		// LoadingStrip; once they have all answered there is no progress left to report,
		// so the summary stands on its own.
		if (state.isFinished) {
			Text(
				text = summary,
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
			)
		} else {
			LoadingStrip(label = summary, progress = state.progress())
		}
		if (loose > 0 || state.failed > 0) {
			// On their own line rather than squeezed in beside the bar: this copy is
			// precise about what is being withheld and must not be the thing that
			// ellipsises when the window is narrow.
			Row(
				modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.sm),
			) {
				if (loose > 0) {
					// Named and counted rather than silently dropped: a source that lists
					// the title under a romanisation this cannot match is exactly the case
					// where the user needs to see what was held back.
					TextButton(onClick = { showLoose = !showLoose }) {
						Text(if (showLoose) "Hide $loose unrelated" else "Show $loose unrelated")
					}
				}
				if (state.failed > 0) {
					TextButton(onClick = { showFailures = !showFailures }) {
						Text(if (showFailures) "Hide failures" else "Show failures")
					}
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
					.verticalScroll(rememberScrollState()),
			) {
				for (result in state.failures) {
					val reason = (result.outcome as? SourceOutcome.Failed)?.message.orEmpty()
					// The same shape a failure has everywhere else in the app, instead of
					// a line of red text with nothing to say it is a failure but red.
					ErrorBox(
						message = "${result.target.title}: $reason",
						compact = true,
					)
				}
			}
			HorizontalDivider(Modifier.padding(vertical = Space.sm))
		}

		when {
			state.isTotalFailure -> ErrorBox(
				message = "Every one of the ${state.total} sources failed for " +
					"\"${state.query}\". That usually means the network is down, not the sources.",
				onRetry = onRetry,
			)

			state.isEmptyResult -> EmptyBox(
				headline = "Nothing found",
				detail = "All ${state.total} sources answered and none of them has " +
					"\"${state.query}\".",
				icon = AppIcons.DISCOVER,
			)

			// The sources answered, but with rows that have no word in common with the
			// query. Saying so beats both a wall of unrelated covers and a bare "nothing
			// found" that contradicts the count above it.
			matched == 0 && loose > 0 && !showLoose -> EmptyBox(
				headline = "Nothing that matches \"${state.query}\"",
				detail = "$loose ${if (loose == 1) "title came" else "titles came"} back, " +
					"but none of them matches it. Sources often widen a query that would " +
					"otherwise return little.",
				icon = AppIcons.DISCOVER,
				action = { Button(onClick = { showLoose = true }) { Text("Show them anyway") } },
			)

			else -> LazyColumn(
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(bottom = Space.xl),
			) {
				items(visibleGroups, key = { it.key }) { group ->
					SourceGroup(
						context = context,
						group = group,
						items = if (showLoose) group.ranked.all else group.ranked.shown,
						isCollapsed = collapsed[group.key] == true,
						onToggle = {
							collapsed[group.key] = collapsed[group.key] != true
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
							modifier = Modifier.padding(Space.lg),
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
	group: RankedGroup,
	items: List<Manga>,
	isCollapsed: Boolean,
	onToggle: () -> Unit,
	onOpen: (MangaParserSource, Manga) -> Unit,
) {
	val source = remember(group.key) { sourceOf(group.key) }
	Column(Modifier.fillMaxWidth()) {
		// The whole header is the toggle, so the chevron carries no description of its
		// own and the row says what tapping it will do. `heading()` is what lets a screen
		// reader move group to group instead of row by row.
		val toggleLabel = if (isCollapsed) "Expand ${group.title}" else "Collapse ${group.title}"
		SectionHeader(
			text = group.title,
			count = items.size,
			modifier = Modifier
				.clickable(onClick = onToggle)
				.semantics(mergeDescendants = true) {
					heading()
					contentDescription = toggleLabel
				},
			trailing = {
				if (group.ranked.hiddenCount > 0 && items.size == group.ranked.shown.size) {
					// The count alone would read as the whole answer. This source returned
					// more and the rest is one toggle away.
					Text(
						text = "of ${group.result.items.size}",
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				AppIcon(
					name = if (isCollapsed) AppIcons.CHEVRON_RIGHT else AppIcons.EXPAND_MORE,
					contentDescription = null,
				)
			},
		)
		if (!isCollapsed && source != null) {
			val client = remember(source) { context.clientFor(source) }
			LazyRow(
				modifier = Modifier.fillMaxWidth(),
				contentPadding = PaddingValues(horizontal = Space.lg),
				horizontalArrangement = Arrangement.spacedBy(Space.md),
			) {
				itemsIndexed(items, key = { i, m -> "${m.id}-$i" }) { _, manga ->
					CoverCard(
						title = manga.title,
						coverUrl = manga.coverUrl,
						client = client,
						images = context.images,
						onClick = { onOpen(source, manga) },
						modifier = Modifier.width(ThumbSize.strip),
					)
				}
			}
		}
		HorizontalDivider(Modifier.padding(top = Space.sm))
	}
}

/** Tick the sources a global search covers. */
@Composable
private fun SourcePickerDialog(
	sources: List<MangaParserSource>,
	selected: Set<MangaParserSource>,
	onSelectedChange: (Set<MangaParserSource>) -> Unit,
	onClose: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onClose,
		title = { Text("Sources to search") },
		text = {
			SourcePicker(
				sources = sources,
				selected = selected,
				onSelectedChange = onSelectedChange,
			)
		},
		confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
	)
}

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
	Column(
		modifier = Modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(Space.sm),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.sm),
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
		// Not AppListRow: this is a checkbox list, and the row height that makes a
		// navigable list readable would leave three sources visible out of six hundred.
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
						.padding(vertical = Space.xs),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.sm),
				) {
					Checkbox(checked = isOn, onCheckedChange = null)
					Text(
						text = source.title,
						style = MaterialTheme.typography.bodyMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f),
					)
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
