package org.koitharu.kotatsu.desktop.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.settings.SettingsData
import java.util.Locale

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

	override val glyph: String = "⌕"

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
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			for (entry in DiscoverMode.entries) {
				FilterChip(
					selected = mode == entry,
					onClick = { mode = entry },
					label = { Text(entry.label) },
				)
			}
			Spacer(Modifier.weight(1f))
			val current = browsing
			if (mode == DiscoverMode.ONE_SOURCE && current != null) {
				TextButton(onClick = { browsing = null }) { Text("Change source") }
			}
		}
		HorizontalDivider()
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
				val current = browsing
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
			singleLine = true,
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
		)
		Text(
			text = "${shown.size} of ${sources.size} shown, $totalUsable in the catalogue",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 16.dp),
		)
		LazyColumn(Modifier.fillMaxSize()) {
			items(shown, key = { it.name }) { source ->
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clickable { onPick(source) }
						.padding(horizontal = 20.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					Text(
						text = source.title,
						style = MaterialTheme.typography.bodyLarge,
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
				HorizontalDivider()
			}
		}
	}
}

/** How far back to look for sources the user already reads. */
private const val HISTORY_SAMPLE = 50

/** Enough to prove the screen works on a fresh install, few enough to be quick. */
private const val FIRST_RUN_SELECTION = 8
