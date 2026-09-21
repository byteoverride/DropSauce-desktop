package org.koitharu.kotatsu.desktop.feature.readerx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.shared.settings.ReadingMode

/**
 * Reader behaviour that is configured outside the reader.
 *
 * One destination rather than three, because colour filters, tap zones and double-page
 * layout are all answers to "how should a page be presented", and a reader adjusting one
 * is usually adjusting the others in the same sitting. The per-title list lives here too
 * so there is one place that answers "what have I changed, and where".
 */
object ReaderExtrasFeature : Feature {

	override val id: String = "readerx"

	override val title: String = "Reader"

	override val glyph: String = "▣"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		// The navigator is not used: nothing here opens a title or a chapter. Taking it
		// and ignoring it is the interface's shape, not an omission.
		ReaderExtrasScreen(context)
	}
}

@Composable
private fun ReaderExtrasScreen(context: FeatureContext) {
	val store = remember(context) { ReaderExtrasStore(context.paths.config / ReaderExtrasStore.FILE_NAME) }
	val prefs by store.data.collectAsState()
	val repository = remember(context) { TitlePrefsRepository(context.db) }
	val settings by context.settings.data.collectAsState()
	// remember on the repository, not on nothing: recreating the flow every recomposition
	// would re-run the join on each frame the screen redraws.
	val overridesFlow = remember(repository) { repository.observeOverrides() }
	val overrides by overridesFlow.collectAsState(initial = emptyList())
	val scope = context.scope

	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 16.dp, vertical = 12.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text("Reader", style = MaterialTheme.typography.titleLarge)

		ToggleRow(
			title = "Double-page mode",
			subtitle = "Show two pages side by side in paged mode.",
			checked = prefs.doublePage,
			onChange = { scope.launch { store.setDoublePage(it) } },
		)
		ToggleRow(
			title = "First page stands alone",
			subtitle = "Turn this on for a book that opens with a cover. It shifts every " +
				"facing pair by one, so if the pairs look wrong throughout, this is the switch.",
			checked = prefs.coverFirst,
			enabled = prefs.doublePage,
			onChange = { scope.launch { store.setCoverFirst(it) } },
		)

		HorizontalDivider()

		ColorFilterEditor(
			params = prefs.colorFilter,
			onChange = { scope.launch { store.setColorFilter(it) } },
			onReset = { scope.launch { store.setColorFilter(ReaderColorParams.DEFAULT) } },
		)

		HorizontalDivider()

		TapZoneEditor(
			grid = prefs.tapZones,
			onChange = { scope.launch { store.setTapZones(it) } },
			isRtl = settings.readingMode.isRtl(),
			onReset = { scope.launch { store.setTapZones(TapZoneGrid.DEFAULT) } },
		)

		HorizontalDivider()

		OverridesSection(
			overrides = overrides,
			filteredTitles = prefs.titleFilters.keys,
			defaultReadingMode = settings.readingMode,
			onSave = { edited -> scope.launch { repository.saveExisting(edited) } },
			onClear = { mangaId ->
				scope.launch {
					repository.clear(mangaId)
					store.clearTitleFilter(mangaId)
				}
			},
		)
	}
}

@Composable
private fun OverridesSection(
	overrides: List<TitleOverride>,
	filteredTitles: Set<Long>,
	defaultReadingMode: ReadingMode,
	onSave: (TitlePrefs) -> Unit,
	onClear: (Long) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("Titles with their own settings", style = MaterialTheme.typography.titleSmall)
		if (overrides.isEmpty() && filteredTitles.isEmpty()) {
			Text(
				text = "Nothing yet. Settings changed for one title while reading it show up here.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			return@Column
		}
		for (entry in overrides) {
			// Keyed on the title, so expanding one row and then having the list re-emit
			// from an unrelated write does not collapse it or move the draft elsewhere.
			key(entry.prefs.mangaId) {
				OverrideRow(
					entry = entry,
					hasOwnFilter = entry.prefs.mangaId in filteredTitles,
					defaultReadingMode = defaultReadingMode,
					onSave = onSave,
					onClear = { onClear(entry.prefs.mangaId) },
				)
			}
		}
		// A title can have a colour filter and nothing in the database, because the
		// schema has no column for one. Those rows would otherwise be invisible and
		// impossible to undo from here.
		val filterOnly = filteredTitles - overrides.map { it.prefs.mangaId }.toSet()
		for (mangaId in filterOnly.sorted()) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("Title #$mangaId", style = MaterialTheme.typography.bodyMedium)
					Text(
						text = "Colour filter",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				TextButton(onClick = { onClear(mangaId) }) { Text("Clear") }
			}
		}
	}
}

@Composable
private fun OverrideRow(
	entry: TitleOverride,
	hasOwnFilter: Boolean,
	defaultReadingMode: ReadingMode,
	onSave: (TitlePrefs) -> Unit,
	onClear: () -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	// The draft is local and only written on Save. Persisting each keystroke would mean
	// a database write per character typed into the title override field.
	var draft by remember(entry.prefs) { mutableStateOf(entry.prefs) }
	val summary = buildList {
		entry.prefs.readingMode?.let { add(it.label()) }
		entry.prefs.branch?.let { add("branch: $it") }
		if (entry.prefs.titleOverride != null) add("renamed")
		if (entry.prefs.coverOverride != null) add("custom cover")
		if (entry.prefs.incognito) add("incognito")
		if (hasOwnFilter) add("colour filter")
	}.joinToString(", ")

	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text(
				text = entry.prefs.titleOverride ?: entry.title,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = "${entry.sourceName.lowercase()} · $summary",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Done" else "Edit") }
		TextButton(onClick = onClear) { Text("Clear") }
	}
	if (expanded) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			TitlePrefsEditor(
				prefs = draft,
				onChange = { draft = it },
				defaultReadingMode = defaultReadingMode,
				onClear = {
					draft = TitlePrefs(mangaId = entry.prefs.mangaId)
					onSave(draft)
				},
			)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				TextButton(onClick = { draft = entry.prefs }, enabled = draft != entry.prefs) {
					Text("Revert")
				}
				TextButton(onClick = { onSave(draft) }, enabled = draft != entry.prefs) {
					Text("Save")
				}
			}
		}
	}
}

@Composable
private fun ToggleRow(
	title: String,
	subtitle: String,
	checked: Boolean,
	onChange: (Boolean) -> Unit,
	enabled: Boolean = true,
) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text(title, style = MaterialTheme.typography.bodyMedium)
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
	}
}
