package org.koitharu.kotatsu.desktop.feature.curate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.AppListRow
import org.koitharu.kotatsu.desktop.ui.ChipRow
import org.koitharu.kotatsu.desktop.ui.CoverCard
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.META_SEPARATOR
import org.koitharu.kotatsu.desktop.ui.NoticeBar
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.ThumbSize
import org.koitharu.kotatsu.desktop.ui.TopBar
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity

/**
 * Organise: a library that has grown, and the tools to get it back under control.
 *
 * Deliberately a second screen rather than more buttons on the library grid. The library
 * is for reading from; this is for editing, where every click can change many rows and
 * the destructive ones have to be spelled out before they run.
 */
@Composable
fun CurateScreen(context: FeatureContext, navigator: FeatureNavigator) {
	val repository = remember(context) { CurateRepository(context.db) }
	// shared(), not the constructor: the reader consults the same global incognito flag
	// through its own IncognitoController, and two stores over one file would disagree.
	val config = remember(context) {
		CurateConfigStore.shared(context.paths.config / CurateConfigStore.FILE_NAME)
	}
	val incognito = remember(repository, config) { IncognitoController(repository, config) }
	val scope = rememberCoroutineScope()

	val prefs by config.data.collectAsState()
	val categories by remember(repository) { repository.observeCategories() }
		.collectAsState(emptyList())
	val allItems by remember(repository) { repository.observeItems() }.collectAsState(emptyList())

	var categoryFilter: Long? by remember { mutableStateOf(null) }
	var selection by remember { mutableStateOf(Selection.EMPTY) }
	var showDuplicates by remember { mutableStateOf(false) }
	var confirmation: Confirmation? by remember { mutableStateOf(null) }
	var categoryPrompt: CategoryPrompt? by remember { mutableStateOf(null) }
	var mergeTarget: DuplicateGroup? by remember { mutableStateOf(null) }
	var status: String? by remember { mutableStateOf(null) }

	// The list under the selection is a Flow. A title removed by a batch action, or by
	// another screen, must not stay ticked: the next action would target a row that is
	// no longer there. Filtering is not deletion, so this reconciles against everything
	// saved rather than against what the current chip shows.
	LaunchedEffect(allItems) {
		selection = selection.retaining(allItems.map { it.id })
	}

	val activeCategory = categoryFilter
	val visible = remember(allItems, activeCategory, prefs.sort, prefs.descending) {
		allItems
			.filter { activeCategory == null || activeCategory in it.categoryIds }
			.sortedByKey(prefs.sort, prefs.descending)
	}
	val duplicates = remember(allItems) { Duplicates.find(allItems) }

	fun runBatch(describe: (BatchResult) -> String, action: suspend () -> BatchResult) {
		scope.launch {
			val result = action()
			status = describe(result)
			selection = Selection.EMPTY
		}
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Organise",
			subtitle = subtitleFor(visible.size, allItems.size, selection.size, duplicates.size),
			trailing = {
				// Straight into the bar's own Row: it centres and spaces its children
				// already, so the wrapper this used to carry was a second gap rule.
				Text("Incognito", style = MaterialTheme.typography.labelLarge)
				Switch(
					checked = prefs.incognito,
					onCheckedChange = { enabled ->
						scope.launch {
							incognito.setGlobal(enabled)
							status = if (enabled) {
								"Incognito on. Nothing you read is recorded."
							} else {
								"Incognito off. Reading is recorded again."
							}
						}
					},
				)
			},
		)
		CategoryRow(
			categories = categories,
			selected = activeCategory,
			onSelect = { categoryFilter = it },
		)
		SortRow(
			prefs = prefs,
			onSort = { key -> scope.launch { config.setSort(key, prefs.descending) } },
			onDirection = { scope.launch { config.setSort(prefs.sort, it) } },
			onView = { scope.launch { config.setView(it) } },
			duplicateCount = duplicates.size,
			showDuplicates = showDuplicates,
			onToggleDuplicates = { showDuplicates = !showDuplicates },
		)
		SelectionBar(
			selection = selection,
			visible = visible,
			onSelectAll = { selection = selection.selectAll(visible.map { it.id }) },
			onInvert = { selection = selection.invert(visible.map { it.id }) },
			onClear = { selection = Selection.EMPTY },
		)
		if (selection.isNotEmpty) {
			ActionBar(
				count = selection.size,
				categoryFilter = activeCategory,
				categoryName = categories.firstOrNull { it.categoryId == activeCategory }?.title,
				hasCategories = categories.isNotEmpty(),
				onAdd = {
					categoryPrompt = CategoryPrompt(
						title = "Add ${selection.size} ${plural(selection.size, "title", "titles")} to",
						exclude = null,
						onPick = { category ->
							categoryPrompt = null
							runBatch({ it.describe("added to ${category.title}") }) {
								repository.addToCategory(selection.ids, category.categoryId)
							}
						},
					)
				},
				onMove = {
					val from = activeCategory ?: return@ActionBar
					categoryPrompt = CategoryPrompt(
						title = "Move ${selection.size} ${plural(selection.size, "title", "titles")} to",
						exclude = from,
						onPick = { category ->
							categoryPrompt = null
							runBatch({ it.describe("moved to ${category.title}") }) {
								repository.moveToCategory(selection.ids, from, category.categoryId)
							}
						},
					)
				},
				onRemoveFromCategory = {
					val from = activeCategory ?: return@ActionBar
					val name = categories.firstOrNull { it.categoryId == from }?.title ?: "this category"
					val count = selection.size
					confirmation = Confirmation(
						title = "Remove from $name?",
						message = "Remove $count ${plural(count, "title", "titles")} from $name? " +
							"They stay in your other categories and their history is kept.",
						confirmLabel = "Remove",
					) {
						repository.removeFromCategory(selection.ids, from).describe("removed from $name")
					}
				},
				onRemoveFromLibrary = {
					val count = selection.size
					confirmation = Confirmation(
						title = "Remove from library?",
						message = "Remove $count ${plural(count, "title", "titles")} from the library, " +
							"including every category? Their reading history, bookmarks and " +
							"downloaded chapters are kept.",
						confirmLabel = "Remove",
					) {
						repository.removeFromLibrary(selection.ids).describe("removed from the library")
					}
				},
				onMarkRead = {
					val count = selection.size
					confirmation = Confirmation(
						title = "Mark as read?",
						message = "Mark $count ${plural(count, "title", "titles")} as fully read? " +
							"This rewrites the saved reading position and cannot be undone. " +
							"Titles you have never opened are skipped.",
						confirmLabel = "Mark read",
					) {
						repository.markHistory(selection.ids, read = true).describe("marked read")
					}
				},
				onMarkUnread = {
					val count = selection.size
					confirmation = Confirmation(
						title = "Mark as unread?",
						message = "Reset the reading position of $count " +
							"${plural(count, "title", "titles")} to the beginning? " +
							"The old position is lost. Titles you have never opened are skipped.",
						confirmLabel = "Mark unread",
					) {
						repository.markHistory(selection.ids, read = false).describe("marked unread")
					}
				},
				onDeleteHistory = {
					val count = selection.size
					confirmation = Confirmation(
						title = "Delete history?",
						message = "Delete the reading history of $count " +
							"${plural(count, "title", "titles")}? The titles stay in the library, " +
							"and bookmarks are kept.",
						confirmLabel = "Delete history",
					) {
						repository.deleteHistory(selection.ids).describe("cleared")
					}
				},
				onIncognito = { enabled ->
					runBatch({ it.describe(if (enabled) "set to incognito" else "recording again") }) {
						repository.setIncognito(selection.ids, enabled)
					}
				},
			)
		}
		status?.let { message ->
			// The outcome of a batch is something to acknowledge, not a failure, so it is
			// a notice. It used to be a bare line of text with a "Dismiss" text button,
			// which gave the one container in the app that reports what just changed to
			// many rows no container at all.
			NoticeBar(text = message, onDismiss = { status = null })
		}
		HorizontalDivider()
		when {
			showDuplicates -> DuplicatesPanel(
				groups = duplicates,
				onMerge = { mergeTarget = it },
			)

			allItems.isEmpty() -> EmptyBox(
				headline = "Nothing saved yet.",
				detail = "Save titles from Sources and they show up here to sort, tidy and de-duplicate.",
			)

			visible.isEmpty() -> EmptyBox(
				headline = "Nothing in this category.",
				detail = "Pick another category, or add titles to this one from a selection.",
				// The library is not empty, the filter is: the same icon the library uses
				// when its own chapter filter matches nothing.
				icon = AppIcons.FILTER,
			)

			prefs.view == LibraryViewMode.LIST -> CurateList(
				items = visible,
				selection = selection,
				context = context,
				onToggle = { selection = selection.toggle(it.id) },
				onOpen = { item ->
					parserSourceOrNull(item.sourceName)?.let { navigator.openDetails(it, item.manga) }
				},
			)

			else -> CurateGrid(
				items = visible,
				selection = selection,
				context = context,
				onToggle = { selection = selection.toggle(it.id) },
				onOpen = { item ->
					parserSourceOrNull(item.sourceName)?.let { navigator.openDetails(it, item.manga) }
				},
			)
		}
	}

	confirmation?.let { pending ->
		AlertDialog(
			onDismissRequest = { confirmation = null },
			title = { Text(pending.title) },
			text = { Text(pending.message) },
			confirmButton = {
				TextButton(
					onClick = {
						confirmation = null
						scope.launch {
							status = pending.perform()
							selection = Selection.EMPTY
						}
					},
				) { Text(pending.confirmLabel, color = MaterialTheme.colorScheme.error) }
			},
			dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
		)
	}

	categoryPrompt?.let { prompt ->
		AlertDialog(
			onDismissRequest = { categoryPrompt = null },
			title = { Text(prompt.title) },
			text = {
				val choices = categories.filterNot { it.categoryId == prompt.exclude }
				if (choices.isEmpty()) {
					Text("There is no other category to use. Create one from the library first.")
				} else {
					Column(
						modifier = Modifier.heightIn(max = 320.dp),
						verticalArrangement = Arrangement.spacedBy(Space.xs),
					) {
						for (category in choices) {
							OutlinedButton(
								onClick = { prompt.onPick(category) },
								modifier = Modifier.fillMaxWidth(),
							) { Text(category.title) }
						}
					}
				}
			},
			confirmButton = {},
			dismissButton = { TextButton(onClick = { categoryPrompt = null }) { Text("Cancel") } },
		)
	}

	mergeTarget?.let { group ->
		MergeDialog(
			group = group,
			onDismiss = { mergeTarget = null },
			onConfirm = { survivorId ->
				mergeTarget = null
				scope.launch {
					val others = group.items.map { it.id }.filterNot { it == survivorId }
					status = repository.merge(survivorId, others).describe()
					selection = Selection.EMPTY
				}
			},
		)
	}
}

/** Everything a destructive action needs to state its case before it runs. */
private class Confirmation(
	val title: String,
	val message: String,
	val confirmLabel: String,
	val perform: suspend () -> String,
)

/** A pick-a-category dialog, reused by add and move. */
private class CategoryPrompt(
	val title: String,
	/** The category the action is moving out of, which must not be a target. */
	val exclude: Long?,
	val onPick: (FavouriteCategoryEntity) -> Unit,
)

private fun subtitleFor(visible: Int, total: Int, selected: Int, duplicateGroups: Int): String =
	buildList {
		add(if (visible == total) "$total ${plural(total, "title", "titles")}" else "$visible of $total")
		if (selected > 0) add("$selected selected")
		if (duplicateGroups > 0) {
			add("$duplicateGroups possible ${plural(duplicateGroups, "duplicate", "duplicates")}")
		}
	}.joinToString(META_SEPARATOR)

@Composable
private fun CategoryRow(
	categories: List<FavouriteCategoryEntity>,
	selected: Long?,
	onSelect: (Long?) -> Unit,
) {
	// Was a horizontalScroll Row. The category list is as long as the reader made it, so
	// the last categories were clipped with nothing to say they existed and no way to
	// reach them but a drag. ChipRow wraps and collapses instead.
	ChipRow {
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
	}
}

@Composable
private fun SortRow(
	prefs: CuratePrefs,
	onSort: (LibrarySortKey) -> Unit,
	onDirection: (Boolean) -> Unit,
	onView: (LibraryViewMode) -> Unit,
	duplicateCount: Int,
	showDuplicates: Boolean,
	onToggleDuplicates: () -> Unit,
) {
	// The 8 dp spacer that used to separate the sort chips from the view chips is gone
	// rather than ported: in a flow a spacer is one of the row's items, so the collapse
	// indicator would count it and offer "+N more" for a gap.
	ChipRow(label = "Sort") {
		for (key in LibrarySortKey.entries) {
			FilterChip(
				selected = key == prefs.sort,
				onClick = { onSort(key) },
				label = { Text(key.label) },
			)
		}
		AssistChip(
			onClick = { onDirection(!prefs.descending) },
			// The glyph shows the direction the list currently runs in, not the action.
			label = { Text(if (prefs.descending) "Reversed" else "Normal") },
		)
		for (mode in LibraryViewMode.entries) {
			FilterChip(
				selected = mode == prefs.view,
				onClick = { onView(mode) },
				label = { Text(mode.label) },
			)
		}
		if (duplicateCount > 0) {
			FilterChip(
				selected = showDuplicates,
				onClick = onToggleDuplicates,
				label = { Text("Duplicates ($duplicateCount)") },
			)
		}
	}
}

@Composable
private fun SelectionBar(
	selection: Selection,
	visible: List<CurateItem>,
	onSelectAll: () -> Unit,
	onInvert: () -> Unit,
	onClear: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
		verticalAlignment = Alignment.CenterVertically,
	) {
		OutlinedButton(onClick = onSelectAll, enabled = visible.isNotEmpty()) {
			Text("Select all (${visible.size})")
		}
		OutlinedButton(onClick = onInvert, enabled = visible.isNotEmpty()) { Text("Invert") }
		OutlinedButton(onClick = onClear, enabled = selection.isNotEmpty) { Text("Clear") }
	}
}

@Composable
private fun ActionBar(
	count: Int,
	categoryFilter: Long?,
	categoryName: String?,
	hasCategories: Boolean,
	onAdd: () -> Unit,
	onMove: () -> Unit,
	onRemoveFromCategory: () -> Unit,
	onRemoveFromLibrary: () -> Unit,
	onMarkRead: () -> Unit,
	onMarkUnread: () -> Unit,
	onDeleteHistory: () -> Unit,
	onIncognito: (Boolean) -> Unit,
) {
	Column(
		// No horizontal padding here any more: the label carries its own gutter and
		// ChipRow brings its own, so the two would otherwise stack to 32 dp.
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.padding(vertical = Space.sm),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Text(
			text = "$count ${plural(count, "title", "titles")} selected",
			style = MaterialTheme.typography.labelLarge,
			modifier = Modifier.padding(horizontal = Space.lg),
		)
		// Nine actions in a horizontalScroll put "Delete history" and the incognito pair
		// off the right edge with nothing to say they were there. Two rows before the
		// collapse, because unlike a filter row this is the panel the batch is performed
		// from, and an action you have to expand the row to find is an action missed.
		ChipRow(collapsedRows = 2) {
			OutlinedButton(onClick = onAdd, enabled = hasCategories) { Text("Add to category") }
			// Moving and removing from a category are only meaningful with one in view:
			// without it there is no "out of" to name, and a button that cannot say what
			// it affects has no business next to one that deletes rows.
			OutlinedButton(onClick = onMove, enabled = categoryFilter != null) {
				Text(if (categoryName != null) "Move out of $categoryName" else "Move")
			}
			OutlinedButton(onClick = onRemoveFromCategory, enabled = categoryFilter != null) {
				Text(if (categoryName != null) "Remove from $categoryName" else "Remove from category")
			}
			OutlinedButton(onClick = onRemoveFromLibrary) { Text("Remove from library") }
			OutlinedButton(onClick = onMarkRead) { Text("Mark read") }
			OutlinedButton(onClick = onMarkUnread) { Text("Mark unread") }
			OutlinedButton(onClick = onDeleteHistory) { Text("Delete history") }
			OutlinedButton(onClick = { onIncognito(true) }) { Text("Incognito on") }
			OutlinedButton(onClick = { onIncognito(false) }) { Text("Incognito off") }
		}
	}
}

@Composable
private fun CurateGrid(
	items: List<CurateItem>,
	selection: Selection,
	context: FeatureContext,
	onToggle: (CurateItem) -> Unit,
	onOpen: (CurateItem) -> Unit,
) {
	LazyVerticalGrid(
		columns = GridCells.Adaptive(minSize = 150.dp),
		contentPadding = PaddingValues(Space.md),
		horizontalArrangement = Arrangement.spacedBy(Space.md),
		verticalArrangement = Arrangement.spacedBy(Space.md),
		modifier = Modifier.fillMaxSize(),
	) {
		gridItems(items, key = { it.id }) { item ->
			val checked = item.id in selection
			CoverCard(
				title = item.title,
				coverUrl = item.manga.coverUrl,
				client = rememberCoverClient(item, context),
				images = context.images,
				// A click ticks the box rather than opening the title. This screen is
				// for editing, and the one thing worse than an extra click to read is
				// a mis-click that adds a title to a batch about to be deleted.
				onClick = { onToggle(item) },
				secondary = itemSummary(item),
				// The border and the ticked box together, never the border alone.
				selected = checked,
				overlay = {
					Checkbox(
						checked = checked,
						onCheckedChange = { onToggle(item) },
						modifier = Modifier.align(Alignment.TopStart),
					)
				},
				action = { TextButton(onClick = { onOpen(item) }) { Text("Open") } },
			)
		}
	}
}

@Composable
private fun CurateList(
	items: List<CurateItem>,
	selection: Selection,
	context: FeatureContext,
	onToggle: (CurateItem) -> Unit,
	onOpen: (CurateItem) -> Unit,
) {
	LazyColumn(modifier = Modifier.fillMaxSize()) {
		items(items, key = { it.id }) { item ->
			val checked = item.id in selection
			AppListRow(
				headline = item.title,
				// Ticks the box, as in the grid and for the same reason.
				onClick = { onToggle(item) },
				meta = itemFacts(item),
				selected = checked,
				leading = {
					Row(
						horizontalArrangement = Arrangement.spacedBy(Space.sm),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Checkbox(checked = checked, onCheckedChange = { onToggle(item) })
						Cover(
							item = item,
							context = context,
							modifier = Modifier.size(ThumbSize.list).clip(MaterialTheme.shapes.small),
						)
					}
				},
				trailing = { TextButton(onClick = { onOpen(item) }) { Text("Open") } },
			)
			// Decoration now, not structure: the row's own minimum height and hover
			// state carry the separation. See UI_SPEC 1.6.
			HorizontalDivider()
		}
	}
}

@Composable
private fun Cover(item: CurateItem, context: FeatureContext, modifier: Modifier) {
	RemoteImage(
		url = item.manga.coverUrl,
		client = rememberCoverClient(item, context),
		cache = context.images,
		contentDescription = item.title,
		modifier = modifier,
	)
}

/**
 * The client this item's covers have to be fetched with.
 *
 * A row whose source has left the build still has to be organisable, so it gets a plain
 * client rather than being hidden. The cover url is probably dead too; a missing cover is
 * not worth refusing to draw the row over.
 */
@Composable
private fun rememberCoverClient(item: CurateItem, context: FeatureContext): OkHttpClient {
	val source = remember(item.sourceName) { parserSourceOrNull(item.sourceName) }
	return remember(source) { source?.let { context.clientFor(it) } ?: OkHttpClient() }
}

/** The facts under a title, in order, unjoined so a row can space them itself. */
private fun itemFacts(item: CurateItem): List<String> = buildList {
	add(item.sourceName.lowercase())
	add(if (item.chaptersCount > 0) "${item.chaptersCount} ch" else "ch unknown")
	if (item.hasHistory) add("${(item.progress * 100).toInt()}% read")
	if (item.incognito) add("incognito")
}

private fun itemSummary(item: CurateItem): String = itemFacts(item).joinToString(META_SEPARATOR)

@Composable
private fun DuplicatesPanel(groups: List<DuplicateGroup>, onMerge: (DuplicateGroup) -> Unit) {
	if (groups.isEmpty()) {
		EmptyBox(
			headline = "No duplicates found.",
			detail = "Titles are grouped when their names match once case, spacing and punctuation " +
				"are ignored, or differ by about one character.",
			// Nothing to fix is an outcome, not an empty shelf.
			icon = AppIcons.CHECK,
		)
		return
	}
	LazyColumn(modifier = Modifier.fillMaxSize()) {
		items(groups, key = { it.key }) { group ->
			Column(
				modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
				verticalArrangement = Arrangement.spacedBy(Space.xs),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.sm),
				) {
					Column(Modifier.weight(1f)) {
						Text(group.key, style = MaterialTheme.typography.titleSmall)
						Text(
							text = "${group.size} entries" +
								if (group.crossSource) ", different sources" else ", same source",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					OutlinedButton(onClick = { onMerge(group) }) { Text("Merge") }
				}
				for (item in group.items) {
					Text(
						text = "• ${item.title} (${itemSummary(item)})",
						style = MaterialTheme.typography.bodySmall,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			HorizontalDivider()
		}
	}
}

@Composable
private fun MergeDialog(
	group: DuplicateGroup,
	onDismiss: () -> Unit,
	onConfirm: (Long) -> Unit,
) {
	var survivorId by remember(group.key) { mutableStateOf(group.suggestedSurvivorId) }
	val survivor = group.items.first { it.id == survivorId }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Merge ${group.size} entries?") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
				Text("Keep which entry?", style = MaterialTheme.typography.labelLarge)
				for (item in group.items) {
					Row(
						modifier = Modifier.fillMaxWidth().clickable { survivorId = item.id },
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.sm),
					) {
						Checkbox(checked = item.id == survivorId, onCheckedChange = { survivorId = item.id })
						Column(Modifier.weight(1f)) {
							Text(item.title, style = MaterialTheme.typography.bodyMedium)
							Text(
								text = itemSummary(item),
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
				Text(
					text = "Merging keeps “${survivor.title}” and removes the other " +
						"${group.size - 1} ${plural(group.size - 1, "entry", "entries")} from the " +
						"library. Their categories and the furthest reading position move to the " +
						"entry you keep. Bookmarks and downloads on the removed entries stay where " +
						"they are. This cannot be undone.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(survivorId) }) {
				Text("Merge", color = MaterialTheme.colorScheme.error)
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}
