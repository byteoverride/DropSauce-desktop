package org.koitharu.kotatsu.desktop.feature.discover

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.first
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.settings.SettingsData
import java.util.Locale
import org.koitharu.kotatsu.desktop.ui.AppIcon
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.AppListRow
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.TopBar

/**
 * Finding something to read: one source in depth, or every source at once.
 *
 * Two modes rather than two destinations, because they are the same job at different
 * breadths and a user moves between them constantly. Both keep their state while the
 * other is on screen.
 */
object DiscoverFeature : Feature {

	override val id: String = "discover"

	override val title: String = "Discover"

	override val icon: String = AppIcons.DISCOVER

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		DiscoverRoot(context, navigator)
	}
}

private enum class DiscoverMode(val label: String) {
	EVERYWHERE("Search everywhere"),
	ONE_SOURCE("Browse a source"),
}

@Composable
private fun DiscoverRoot(context: FeatureContext, navigator: FeatureNavigator) {
	val store = remember(context) { DiscoverStore(context.paths.config / DiscoverStore.FILE_NAME) }
	// Held here, not in the search screen, so switching modes does not cancel a search.
	val controller = remember(context) { GlobalSearchController(context.scope) }
	val settings by context.settings.data.collectAsState()
	val selectable = remember(settings.hideAdultSources, settings.hiddenSourceTerms) {
		visibleSources(settings)
	}
	var selected by remember(context) { mutableStateOf<Set<MangaParserSource>>(emptySet()) }
	var selectionRestored by remember(context) { mutableStateOf(false) }
	LaunchedEffect(context) {
		if (!selectionRestored) {
			selected = restoreSelection(context, store, selectable)
			selectionRestored = true
		}
	}
	var mode by remember(context) { mutableStateOf(DiscoverMode.EVERYWHERE) }
	var browsing: MangaParserSource? by remember(context) { mutableStateOf(null) }

	Column(Modifier.fillMaxSize()) {
		val current = browsing
		TopBar(
			title = DiscoverFeature.title,
			// The number that decides what a search will actually do was buried in the
			// label of a text button at the end of the search row. In browse mode the
			// source's own name is already the heading below, so this does not repeat it.
			subtitle = when {
				mode == DiscoverMode.EVERYWHERE ->
					"${selected.size} of ${selectable.size} sources selected"

				current == null -> "${selectable.size} sources to choose from"
				else -> null
			},
			trailing = {
				if (mode == DiscoverMode.ONE_SOURCE && current != null) {
					TextButton(onClick = { browsing = null }) { Text("Change source") }
				}
			},
		)
		ModeTabs(mode = mode, onMode = { mode = it })
		when (mode) {
			DiscoverMode.EVERYWHERE -> GlobalSearchScreen(
				context = context,
				controller = controller,
				store = store,
				selectableSources = selectable,
				selected = selected,
				onSelectedChange = { selected = it },
				onOpen = { source, manga -> navigator.openDetails(source, manga) },
			)

			DiscoverMode.ONE_SOURCE -> {
				if (current == null) {
					SourceList(
						sources = selectable,
						totalUsable = SourceRegistry.usableSources.size,
						onPick = { browsing = it },
					)
				} else {
					SourceBrowseScreen(
						context = context,
						source = current,
						onOpen = { manga -> navigator.openDetails(current, manga) },
					)
				}
			}
		}
	}
}

/**
 * The two modes, as a tab row.
 *
 * They were `FilterChip`s sitting directly above the recent-query chips, so two
 * completely different kinds of control were drawn identically: one picked a mode, the
 * other ran a search. Two mutually exclusive modes that each own the whole screen below
 * them are what a tab row is for, and it carries the mode's own heading weight rather
 * than borrowing a filter's.
 *
 * Left and right move the selection, which is the convention for a tab row and is what
 * chips did not give: as chips, every mode was a separate Tab stop on the way to the
 * search box.
 */
@Composable
private fun ModeTabs(mode: DiscoverMode, onMode: (DiscoverMode) -> Unit) {
	val entries = DiscoverMode.entries
	PrimaryTabRow(
		selectedTabIndex = mode.ordinal,
		modifier = Modifier
			.fillMaxWidth()
			.focusGroup()
			.onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				val step = when (event.key) {
					Key.DirectionLeft -> -1
					Key.DirectionRight -> 1
					else -> return@onPreviewKeyEvent false
				}
				val next = mode.ordinal + step
				if (next in entries.indices) {
					onMode(entries[next])
					true
				} else {
					false
				}
			},
	) {
		for (entry in entries) {
			Tab(
				selected = mode == entry,
				onClick = { onMode(entry) },
				text = { Text(entry.label) },
			)
		}
	}
}

/**
 * The sources this user has said they want to see.
 *
 * Mirrors the catalogue's own rules rather than reaching into the navigation shell for
 * them: the adult filter and the hidden-term list are settings, and a feature area reads
 * settings directly.
 */
internal fun visibleSources(settings: SettingsData): List<MangaParserSource> {
	var result = SourceRegistry.usableSources
	if (settings.hideAdultSources) {
		result = result.filterNot { with(SourceRegistry) { it.isAdult() } }
	}
	val terms = settings.hiddenSourceTerms.filter { it.isNotBlank() }
	if (terms.isNotEmpty()) {
		result = result.filterNot { source ->
			terms.any { source.title.contains(it, ignoreCase = true) }
		}
	}
	return result
}

/**
 * Which sources a global search should start with.
 *
 * In order: what the user last searched, then the sources their library and history
 * already come from, then a handful in their own language. The last of those is a guess,
 * but an empty selection on a fresh install would make the screen look broken, and every
 * one of them is one click away from being unticked.
 */
private suspend fun restoreSelection(
	context: FeatureContext,
	store: DiscoverStore,
	selectable: List<MangaParserSource>,
): Set<MangaParserSource> {
	val byName = selectable.associateBy { it.name }
	val remembered = store.data.value.globalSources.mapNotNull { byName[it] }
	if (remembered.isNotEmpty()) return remembered.take(MAX_SELECTABLE).toSet()

	val used = runCatching {
		val favourites = context.library.observeFavourites(null).first().map { it.sourceName }
		val history = context.library.observeHistory(HISTORY_SAMPLE).first().map { it.sourceName }
		(favourites + history).distinct()
	}.getOrDefault(emptyList())
	val seeded = used.mapNotNull { byName[it] }
	if (seeded.isNotEmpty()) return seeded.take(MAX_SELECTABLE).toSet()

	val language = Locale.getDefault().language
	val local = selectable.filter { it.locale.equals(language, ignoreCase = true) }
	return local.ifEmpty { selectable }.take(FIRST_RUN_SELECTION).toSet()
}

/** Pick a source to browse. Kept in this area so it does not depend on the catalogue screen. */
@Composable
private fun SourceList(
	sources: List<MangaParserSource>,
	totalUsable: Int,
	onPick: (MangaParserSource) -> Unit,
) {
	var query by remember { mutableStateOf("") }
	val shown = remember(sources, query) {
		val needle = query.trim().lowercase()
		if (needle.isEmpty()) {
			sources
		} else {
			sources.filter { it.title.lowercase().contains(needle) || it.locale.lowercase() == needle }
		}
	}
	Column(Modifier.fillMaxSize()) {
		OutlinedTextField(
			value = query,
			onValueChange = { query = it },
			label = { Text("Filter by name or language code") },
			leadingIcon = { AppIcon(AppIcons.DISCOVER, contentDescription = null) },
			trailingIcon = {
				if (query.isNotEmpty()) {
					IconButton(onClick = { query = "" }) {
						AppIcon(AppIcons.CLOSE, contentDescription = "Clear the filter")
					}
				}
			},
			singleLine = true,
			modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
		)
		Text(
			text = "${shown.size} of ${sources.size} shown, $totalUsable in the catalogue",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = Space.lg),
		)
		if (shown.isEmpty()) {
			EmptyBox(
				headline = "No source matches that",
				detail = "Nothing in the catalogue is called \"${query.trim()}\", and it is not a " +
					"language code either.",
				icon = AppIcons.DISCOVER,
				action = { Button(onClick = { query = "" }) { Text("Clear the filter") } },
			)
			return@Column
		}
		LazyColumn(Modifier.fillMaxSize()) {
			items(shown, key = { it.name }) { source ->
				// The divider that used to separate these was the only thing carrying the
				// structure of a 658-row list, at 1.99:1. AppListRow carries it with a
				// minimum height and a hover state instead.
				AppListRow(
					headline = source.title,
					onClick = { onPick(source) },
					meta = listOfNotNull(source.locale.takeIf { it.isNotBlank() }),
					trailing = { AppIcon(AppIcons.CHEVRON_RIGHT, contentDescription = null) },
				)
			}
		}
	}
}

/** How far back to look for sources the user already reads. */
private const val HISTORY_SAMPLE = 50

/** Enough to prove the screen works on a fresh install, few enough to be quick. */
private const val FIRST_RUN_SELECTION = 8
