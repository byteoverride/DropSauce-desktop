package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.migration.MigrationFeature
import org.koitharu.kotatsu.desktop.library.ChapterCountProgress
import org.koitharu.kotatsu.desktop.library.LibraryItem
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity

/**
 * The library: saved titles, grouped into categories.
 *
 * Categories are managed from here rather than a settings screen, because creating one
 * is part of filing a title away, not configuration.
 */
@Composable
fun LibraryScreen(state: AppState, onOpen: (MangaParserSource, org.koitharu.kotatsu.parsers.model.Manga) -> Unit) {
	val scope = rememberCoroutineScope()
	val categories by state.library.observeCategories().collectAsState(emptyList())
	// null means "everything", which is the only sensible view before any category exists.
	var selected: Long? by remember { mutableStateOf(null) }
	var manageTarget: FavouriteCategoryEntity? by remember { mutableStateOf(null) }
	var creating by remember { mutableStateOf(false) }

	var lengthFilter by remember { mutableStateOf(ChapterFilter.Any) }
	var refiling by remember { mutableStateOf(false) }
	var notice: String? by remember { mutableStateOf(null) }

	val all by remember(selected) { state.library.observeFavourites(selected) }
		.collectAsState(emptyList())
	val items = remember(all, lengthFilter) { all.filter(lengthFilter::matches) }
	val missingCounts by remember(selected) { state.chapterCounts.observeMissing(selected) }
		.collectAsState(0)
	val countProgress by state.chapterCounts.progress.collectAsState()
	// Counted from what is already on screen rather than by rescanning the library: the
	// cards each resolve their own source anyway, and this way the number always agrees
	// with the markers under them.
	val unopenable = remember(all) { all.count { sourceHealth(it.sourceName) != SourceHealth.Ok } }

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Library",
			subtitle = if (lengthFilter == ChapterFilter.Any) {
				"${items.size} titles"
			} else {
				"${items.size} of ${all.size} titles"
			},
			trailing = {
				Button(onClick = { creating = true }) { Text("New category") }
			},
		)
		CategoryChips(
			categories = categories,
			selected = selected,
			// The notice names a category and a count, so it stops being true the moment
			// either changes.
			onSelect = { selected = it; notice = null },
			onManage = { manageTarget = it },
		)
		ChapterFilterChips(selected = lengthFilter, onSelect = { lengthFilter = it; notice = null })
		if (unopenable > 0) {
			UnopenableBar(count = unopenable, onFix = { state.goToMigration() })
		}
		ChapterCountBar(
			missing = missingCounts,
			progress = countProgress,
			onLoad = { state.chapterCounts.start(selected) },
			onCancel = { state.chapterCounts.cancel() },
			onDismiss = { state.chapterCounts.dismiss() },
		)
		if (lengthFilter != ChapterFilter.Any && items.isNotEmpty()) {
			RefileBar(
				count = items.size,
				fromAll = selected == null,
				onRefile = { refiling = true },
			)
		}
		notice?.let { message ->
			Text(
				text = message,
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
			)
		}
		when {
			items.isEmpty() && all.isNotEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
				Text(
					// Naming the unknowns matters: "nothing matches" and "nothing has a
					// count yet" look identical on screen and mean opposite things.
					if (missingCounts > 0) {
						"No titles match ${lengthFilter.label.lowercase()}. " +
							"$missingCounts here have no chapter count yet."
					} else {
						"No titles match ${lengthFilter.label.lowercase()}."
					},
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			items.isEmpty() -> EmptyLibrary(hasCategories = categories.isNotEmpty())
			else -> LazyVerticalGrid(
				columns = GridCells.Adaptive(minSize = 150.dp),
				contentPadding = PaddingValues(12.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				items(items, key = { it.manga.id }) { item ->
					LibraryCard(
						item = item,
						state = state,
						onOpen = onOpen,
						onFixSource = { state.goToMigration() },
					)
				}
			}
		}
	}

	if (creating) {
		CategoryNameDialog(
			title = "New category",
			initial = "",
			confirm = "Create",
			onDismiss = { creating = false },
			onConfirm = { name ->
				scope.launch { state.library.createCategory(name) }
				creating = false
			},
		)
	}

	if (refiling) {
		RefileDialog(
			count = items.size,
			filterLabel = lengthFilter.label,
			categories = categories.filter { it.categoryId != selected },
			onDismiss = { refiling = false },
			onConfirm = { target ->
				val ids = items.map { it.manga.id }
				val from = selected
				scope.launch {
					val result = if (from == null) {
						// Nothing to move out of: "All" is a view over every category, so
						// the only well-defined action is filing the titles as well.
						state.curate.addToCategory(ids, target.categoryId)
					} else {
						state.curate.moveToCategory(ids, from = from, to = target.categoryId)
					}
					val verb = if (from == null) "added to" else "moved to"
					notice = "${result.describe("$verb ${target.title}")}."
				}
				refiling = false
			},
		)
	}

	manageTarget?.let { category ->
		ManageCategoryDialog(
			category = category,
			onDismiss = { manageTarget = null },
			onRename = { name ->
				scope.launch { state.library.renameCategory(category.categoryId, name) }
				manageTarget = null
			},
			onDelete = {
				scope.launch {
					state.library.deleteCategory(category.categoryId)
					if (selected == category.categoryId) selected = null
				}
				manageTarget = null
			},
		)
	}
}

/**
 * Filter the library by how long a title is.
 *
 * Counts come from the stored `chapters_count`. That column is filled in three ways: as a
 * side effect of opening, reading or tracking a title; by copying what `history` already
 * knows, which happens on every start; and by
 * [org.koitharu.kotatsu.desktop.library.ChapterCountRefresher], which fetches the rest on
 * request. Only the last covers a title that has never been opened, and a library
 * restored from an Android backup is entirely made of those, so the filter is only as
 * good as the counts and the screen says so rather than reporting every bucket empty.
 * [Unknown] makes the titles still missing a count findable.
 */
enum class ChapterFilter(val label: String, private val range: IntRange?) {

	Any("Any length", null),
	Unknown("Not loaded", 0..0),
	Short("1 to 25", 1..25),
	Medium("26 to 100", 26..100),
	Long("101 to 500", 101..500),
	VeryLong("Over 500", 501..Int.MAX_VALUE),

	/**
	 * Deliberately overlaps [Medium], [Long] and [VeryLong], and is not a mistake.
	 *
	 * The buckets above partition the library for browsing it. This one answers a
	 * question: a shelf held for titles that have not grown long enough yet is emptied at
	 * a threshold, and at 100 that threshold falls inside [Medium] and then spans two
	 * more buckets, so asking it as a partition costs three passes and gets the boundary
	 * title wrong. Inclusive of 100: "100 or more" is the rule, and 100 is more than 99.
	 */
	HundredPlus("100 or more", 100..Int.MAX_VALUE),
	;

	fun matches(item: LibraryItem): Boolean =
		range == null || item.chaptersCount in range
}

/**
 * Offers to fetch the chapter counts the filter has no answer for, and reports a run.
 *
 * Shown only when there is something to fetch or something to report. A library built up
 * in this app fills its counts in as titles are opened and never sees this bar; one that
 * arrived from a backup has no counts at all and cannot use the filter until it does.
 */
/**
 * Whether this build can actually open a title stored against [sourceName].
 *
 * Two ways to fail and they are not the same. The name may not be in the catalogue at
 * all, which is what a library restored from an Android backup is full of: it carries
 * Mihon and LNReader sources desktop has no parser for (DECISIONS.md D1). Or the source
 * is present and the catalogue flags it broken, which is 380 of the 1270 (D18) and looks
 * fine right up to the moment nothing loads.
 *
 * Mirrors [org.koitharu.kotatsu.desktop.feature.migration.EntryHealth], which the migrate
 * area computes over the whole library. Kept as its own small function rather than
 * scanning from here: the grid already resolves each card's source, and a second source
 * of truth that disagreed with the markers would be worse than a little duplication.
 */
internal enum class SourceHealth { Ok, Broken, Missing }

internal fun sourceHealth(sourceName: String): SourceHealth {
	val source = MangaParserSource.entries.firstOrNull { it.name == sourceName }
	return when {
		source == null -> SourceHealth.Missing
		source.isBroken -> SourceHealth.Broken
		else -> SourceHealth.Ok
	}
}

/** Sends the shell to the migrate area, which lists every entry that cannot be opened. */
private fun AppState.goToMigration() {
	feature(MigrationFeature.id)?.let { selectRoot(Screen.FeatureRoot(it.id)) }
}

/**
 * Says how many titles in view cannot be opened, and offers the screen that repairs them.
 *
 * The migrate area could already fix all of this and nothing pointed at it. A card whose
 * source is gone was inert: it said "source unavailable" and did nothing when clicked, so
 * the repair existed only for someone who already knew to go looking for it.
 */
@Composable
private fun UnopenableBar(count: Int, onFix: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = if (count == 1) {
				"1 title here is on a source that cannot be opened."
			} else {
				"$count titles here are on a source that cannot be opened."
			},
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.error,
		)
		AssistChip(onClick = onFix, label = { Text("Fix sources") })
	}
}

@Composable
private fun ChapterCountBar(
	missing: Int,
	progress: ChapterCountProgress?,
	onLoad: () -> Unit,
	onCancel: () -> Unit,
	onDismiss: () -> Unit,
) {
	val running = progress?.running == true
	if (!running && progress == null && missing == 0) return
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		when {
			running -> {
				LinearProgressIndicator(
					progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
					modifier = Modifier.width(120.dp),
				)
				Text(
					text = "Loading chapter counts, ${progress.done} of ${progress.total}",
					style = MaterialTheme.typography.bodyMedium,
				)
				TextButton(onClick = onCancel) { Text("Stop") }
			}

			progress != null -> {
				Text(
					text = if (progress.failed > 0) {
						"Loaded ${progress.filled} counts, ${progress.failed} could not be reached."
					} else {
						"Loaded ${progress.filled} counts."
					},
					style = MaterialTheme.typography.bodyMedium,
				)
				TextButton(onClick = onDismiss) { Text("Dismiss") }
			}

			else -> {
				Text(
					text = "$missing here have no chapter count yet.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				// One live request per title, so this is a button and not something the
				// screen does on its own when it opens.
				AssistChip(onClick = onLoad, label = { Text("Load counts") })
			}
		}
	}
}

/** Files everything the length filter is currently showing into another category. */
@Composable
private fun RefileBar(count: Int, fromAll: Boolean, onRefile: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		AssistChip(
			onClick = onRefile,
			label = { Text(if (fromAll) "Add these $count to…" else "Move these $count to…") },
		)
	}
}

@Composable
private fun RefileDialog(
	count: Int,
	filterLabel: String,
	categories: List<FavouriteCategoryEntity>,
	onDismiss: () -> Unit,
	onConfirm: (FavouriteCategoryEntity) -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("$count titles, ${filterLabel.lowercase()}") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				if (categories.isEmpty()) {
					Text(
						"No other category to file these into.",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				for (category in categories) {
					Text(
						text = category.title,
						style = MaterialTheme.typography.bodyLarge,
						modifier = Modifier
							.fillMaxWidth()
							.clickable { onConfirm(category) }
							.padding(vertical = 8.dp),
					)
				}
			}
		},
		confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}

@Composable
private fun ChapterFilterChips(selected: ChapterFilter, onSelect: (ChapterFilter) -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(horizontal = 16.dp, vertical = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = "Chapters",
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		for (filter in ChapterFilter.entries) {
			FilterChip(
				selected = filter == selected,
				onClick = { onSelect(filter) },
				label = { Text(filter.label) },
			)
		}
	}
}

@Composable
private fun CategoryChips(
	categories: List<FavouriteCategoryEntity>,
	selected: Long?,
	onSelect: (Long?) -> Unit,
	onManage: (FavouriteCategoryEntity) -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		FilterChip(
			selected = selected == null,
			onClick = { onSelect(null) },
			label = { Text("All") },
		)
		for (category in categories) {
			FilterChip(
				selected = selected == category.categoryId,
				onClick = { onSelect(category.categoryId) },
				label = { Text(category.title) },
			)
		}
		if (selected != null) {
			categories.firstOrNull { it.categoryId == selected }?.let { category ->
				AssistChip(onClick = { onManage(category) }, label = { Text("Edit") })
			}
		}
	}
}

@Composable
private fun EmptyLibrary(hasCategories: Boolean) {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.padding(32.dp),
		) {
			Text("Nothing saved yet.", style = MaterialTheme.typography.titleMedium)
			Text(
				text = if (hasCategories) {
					"Open a title from Sources and add it to a category."
				} else {
					"Create a category, then add titles from Sources."
				},
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun LibraryCard(
	item: LibraryItem,
	state: AppState,
	onOpen: (MangaParserSource, org.koitharu.kotatsu.parsers.model.Manga) -> Unit,
	onFixSource: () -> Unit,
) {
	val source = remember(item.sourceName) {
		MangaParserSource.entries.firstOrNull { it.name == item.sourceName }
	}
	val health = remember(item.sourceName) { sourceHealth(item.sourceName) }
	Column(
		modifier = Modifier
			.fillMaxWidth()
			// Always clickable. A card that cannot be read leads to the repair instead of
			// to the reader, which beats a card that swallows the click and does nothing.
			.clickable {
				if (health == SourceHealth.Ok && source != null) onOpen(source, item.manga) else onFixSource()
			},
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		RemoteImage(
			url = item.manga.coverUrl,
			// Covers need the source's own client; falling back to a plain one would
			// 403 on sources that check headers (see D1).
			client = remember(source) {
				source?.let { state.sources.session(it).client } ?: okhttp3.OkHttpClient()
			},
			cache = state.images,
			contentDescription = item.manga.title,
			modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)),
		)
		Text(
			text = item.manga.title,
			style = MaterialTheme.typography.bodySmall,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = if (item.chaptersCount > 0) {
				"${item.chaptersCount} chapters"
			} else {
				"chapters not loaded"
			},
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (health != SourceHealth.Ok) {
			// Name which of the two problems it is. A source flagged broken used to look
			// identical to a working one here and only failed once the reader was open.
			Text(
				text = when (health) {
					SourceHealth.Missing -> "source unavailable, tap to fix"
					SourceHealth.Broken -> "source is broken, tap to fix"
					SourceHealth.Ok -> ""
				},
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}

@Composable
fun CategoryNameDialog(
	title: String,
	initial: String,
	confirm: String,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit,
) {
	var name by remember { mutableStateOf(initial) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			OutlinedTextField(
				value = name,
				onValueChange = { name = it },
				label = { Text("Name") },
				singleLine = true,
				modifier = Modifier.onEnter { if (name.isNotBlank()) onConfirm(name.trim()) },
			)
		},
		confirmButton = {
			TextButton(
				onClick = { onConfirm(name.trim()) },
				enabled = name.isNotBlank(),
			) { Text(confirm) }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}

@Composable
private fun ManageCategoryDialog(
	category: FavouriteCategoryEntity,
	onDismiss: () -> Unit,
	onRename: (String) -> Unit,
	onDelete: () -> Unit,
) {
	var name by remember(category.categoryId) { mutableStateOf(category.title) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Edit category") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					label = { Text("Name") },
					singleLine = true,
				)
				Text(
					text = "Deleting a category removes it and its entries. " +
						"The titles themselves stay in your history.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = { onRename(name.trim()) },
				enabled = name.isNotBlank() && name.trim() != category.title,
			) { Text("Rename") }
		},
		dismissButton = {
			TextButton(onClick = onDelete) {
				Text("Delete", color = MaterialTheme.colorScheme.error)
			}
		},
	)
}
