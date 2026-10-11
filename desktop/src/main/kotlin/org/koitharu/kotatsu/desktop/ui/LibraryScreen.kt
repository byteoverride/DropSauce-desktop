package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.migration.MigrationFeature
import org.koitharu.kotatsu.desktop.library.LibraryItem
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity

/**
 * The library: saved titles, grouped into categories.
 *
 * Categories are managed from here rather than a settings screen, because creating one
 * is part of filing a title away, not configuration.
 *
 * The chrome above the grid used to be able to stack six bars and in practice stacked
 * three, which put the first cover 315 px down an 810 px window. Two things fixed that:
 * the categories and the length filter now share one row, and the four possible notice
 * bars collapse into two slots with a stated order. Nothing was removed to get there.
 *
 * There is deliberately **no filled button on this screen**. Filing a title away is not
 * what you came to the library to do; the primary action is clicking a cover, so nothing
 * else is allowed to outweigh one.
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
	// Which titles have chapters the reader has not seen. Knowing that on the shelf is
	// the point: the updates screen already knew and the library, where you actually
	// choose what to read, did not.
	val updatedIds by remember { state.library.observeTitlesWithNewChapters() }
		.collectAsState(emptySet())
	val selectedCategory = remember(categories, selected) {
		categories.firstOrNull { it.categoryId == selected }
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Library",
			subtitle = if (lengthFilter == ChapterFilter.Any) {
				"${items.size} titles"
			} else {
				"${items.size} of ${all.size} titles"
			},
			trailing = {
				// A TextButton, not a filled one. As a filled Button this was the single
				// highest-contrast element in the window, which told the reader that
				// making a category was the thing to do here.
				TextButton(onClick = { creating = true }) {
					AppIcon(AppIcons.ADD, contentDescription = null)
					Box(Modifier.size(Space.xs))
					Text("New category")
				}
			},
		)
		LibraryFilterRow(
			categories = categories,
			selectedCategory = selectedCategory,
			selected = selected,
			// The notice names a category and a count, so it stops being true the moment
			// either changes.
			onSelect = { selected = it; notice = null },
			onManage = { manageTarget = it },
			lengthFilter = lengthFilter,
			onLengthFilter = { lengthFilter = it; notice = null },
			// Only offered when the filter is narrowing something down, because the offer
			// is "file everything you are looking at", and with no filter that is the
			// whole library.
			refileCount = items.size.takeIf { lengthFilter != ChapterFilter.Any && it > 0 },
			refileFromAll = selected == null,
			onRefile = { refiling = true },
		)
		// At most two notice bars, with a stated order. Cases 1 (a run in flight), 2 (its
		// result) and 4 (the offer to start one) are three states of the same thing, so
		// they share one slot and 1 or 2 replaces 4. Case 3, a problem, has its own slot.
		val countRun = countProgress
		if (countRun != null) {
			if (countRun.running) {
				LoadingStrip(
					label = "Loading chapter counts, ${countRun.done} of ${countRun.total}",
					progress = countRun.done.toFloat() / countRun.total.coerceAtLeast(1),
					onCancel = { state.chapterCounts.cancel() },
				)
			} else {
				NoticeBar(
					text = if (countRun.failed > 0) {
						"Loaded ${countRun.filled} counts, ${countRun.failed} could not be reached."
					} else {
						"Loaded ${countRun.filled} counts."
					},
					onDismiss = { state.chapterCounts.dismiss() },
				)
			}
		}
		// A refile result is also something to acknowledge, so it sits in the same place
		// and now has the container and the dismiss it never had as a bare line of text.
		notice?.let { message ->
			NoticeBar(text = message, onDismiss = { notice = null })
		}
		if (unopenable > 0) {
			// The warning icon is not decoration. This bar signalled "something is wrong"
			// with colorScheme.error and nothing else, which is a colour-only cue. The
			// icon and the tone now carry it together, so neither is doing it alone.
			NoticeBar(
				text = if (unopenable == 1) {
					"1 title here is on a source that cannot be opened."
				} else {
					"$unopenable titles here are on a source that cannot be opened."
				},
				icon = AppIcons.WARNING,
				tone = NoticeTone.Problem,
				action = {
					AssistChip(onClick = { state.goToMigration() }, label = { Text("Fix sources") })
				},
			)
		}
		if (countRun == null && missingCounts > 0) {
			NoticeBar(
				text = "$missingCounts here have no chapter count yet.",
				action = {
					// One live request per title, so this is a control and not something
					// the screen does on its own when it opens.
					AssistChip(
						onClick = { state.chapterCounts.start(selected) },
						label = { Text("Load counts") },
					)
				},
			)
		}
		when {
			items.isEmpty() && all.isNotEmpty() -> EmptyBox(
				// Naming the unknowns matters: "nothing matches" and "nothing has a count
				// yet" look identical on screen and mean opposite things. Nothing failed
				// here, so this is an empty state and not an error.
				headline = "No titles match ${lengthFilter.label.lowercase()}.",
				detail = "$missingCounts here have no chapter count yet.".takeIf { missingCounts > 0 },
				icon = AppIcons.FILTER,
			)

			items.isEmpty() -> EmptyBox(
				headline = "Nothing saved yet.",
				detail = if (categories.isNotEmpty()) {
					"Open a title from Sources and add it to a category."
				} else {
					"Create a category, then add titles from Sources."
				},
			)

			// Adaptive(150.dp) is deliberate and measured, not a default. RemoteImage
			// rounds its decode target up to a 128 px step, and D37 measured the cover
			// budget against the bucket a 150 dp minimum lands in. Moving the minimum
			// moves the bucket and invalidates that measurement.
			else -> LazyVerticalGrid(
				columns = GridCells.Adaptive(minSize = 150.dp),
				contentPadding = PaddingValues(Space.md),
				horizontalArrangement = Arrangement.spacedBy(Space.md),
				verticalArrangement = Arrangement.spacedBy(Space.md),
				modifier = Modifier.fillMaxSize(),
			) {
				items(items, key = { it.manga.id }) { item ->
					LibraryCard(
						item = item,
						state = state,
						hasNewChapters = item.manga.id in updatedIds,
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
				// The app scope, not the composition's. moveToCategory runs in one
				// immediate transaction, so cancelling it halfway rolls the whole move
				// back: leaving the library while 130 titles were being refiled undid all
				// of it and said nothing, because the coroutine that would have reported
				// it died with the screen.
				state.scope.launch {
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
			onSetTracked = { tracked ->
				// The app scope: this can untrack a few hundred titles and must not be
				// abandoned halfway by closing the dialog.
				state.scope.launch { state.library.setCategoryTracked(category.categoryId, tracked) }
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

/**
 * The catalogue indexed by name, built once.
 *
 * [sourceHealth] is asked once per card and once per title behind the banner, and a
 * linear scan of 1270 entries each time is around 450 thousand string comparisons for a
 * library this size, on the frame thread, every time the list changes. A library four
 * times larger would drop frames on every scroll. The map makes it a hash lookup.
 */
private val catalogueByName: Map<String, MangaParserSource> by lazy {
	MangaParserSource.entries.associateBy { it.name }
}

internal fun sourceHealth(sourceName: String): SourceHealth {
	val source = catalogueByName[sourceName]
	return when {
		source == null -> SourceHealth.Missing
		source.isBroken -> SourceHealth.Broken
		else -> SourceHealth.Ok
	}
}

/**
 * Sends the shell to the migrate area, which lists every entry that cannot be opened.
 *
 * Pushed, not selected as a root. Migrate has no slot in the navigation rail any more, so
 * making it the root would highlight nothing and clear the only stack entry that could
 * have brought the reader back: they would arrive at the repair screen with no way out
 * but to pick some other destination and lose their place in the library.
 */
private fun AppState.goToMigration() {
	feature(MigrationFeature.id)?.let { go(Screen.FeatureRoot(it.id)) }
}

/**
 * The categories and the length filter, on one row.
 *
 * Two rows of chips cost 112 dp above the grid and each clipped in its own way: the
 * length filter scrolled horizontally with no affordance and ended mid-word at "100 o",
 * and the categories were a plain `Row` with no scroll at all, so a reader with twelve
 * categories could not select the last three by any means.
 *
 * The outer container is itself a [FlowRow] with two children rather than a `Row`, so at
 * a large interface scale the pinned controls drop to their own line instead of squeezing
 * the chip flow to nothing. No breakpoint and no window measurement.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryFilterRow(
	categories: List<FavouriteCategoryEntity>,
	selectedCategory: FavouriteCategoryEntity?,
	selected: Long?,
	onSelect: (Long?) -> Unit,
	onManage: (FavouriteCategoryEntity) -> Unit,
	lengthFilter: ChapterFilter,
	onLengthFilter: (ChapterFilter) -> Unit,
	refileCount: Int?,
	refileFromAll: Boolean,
	onRefile: () -> Unit,
) {
	FlowRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
		verticalArrangement = Arrangement.spacedBy(Space.sm),
	) {
		CategoryChips(
			categories = categories,
			selected = selected,
			onSelect = onSelect,
			onManage = onManage,
			modifier = Modifier.weight(1f),
		)
		Row(
			// Only an end gutter: ChipRow brings its own Space.lg on the right, and the
			// resulting gap is what separates the categories from the pinned controls.
			modifier = Modifier
				.align(Alignment.CenterVertically)
				.padding(end = Space.lg),
			horizontalArrangement = Arrangement.spacedBy(Space.sm),
			verticalAlignment = Alignment.CenterVertically,
		) {
			ChapterFilterMenu(selected = lengthFilter, onSelect = onLengthFilter)
			if (refileCount != null) {
				// The offer to file everything the filter is showing is a property of the
				// filter, not a notice, so it sits beside the filter instead of on a bar
				// of its own above the grid.
				AssistChip(
					onClick = onRefile,
					label = {
						Text(
							if (refileFromAll) {
								"Add these $refileCount to…"
							} else {
								"Move these $refileCount to…"
							},
						)
					},
				)
			}
			if (selectedCategory != null) {
				LibraryOverflow(category = selectedCategory, onEdit = { onManage(selectedCategory) })
			}
		}
	}
}

/**
 * The length filter, as a menu rather than seven chips.
 *
 * [ChapterFilter] is single-select, and a single-select of seven is a menu. The button
 * shows the active value, which seven chips make you scan for, and takes about 170 dp
 * instead of a whole row. A `DropdownMenu` clamps itself to the window, so unlike the
 * scrolling chip row it cannot cut an item off: "100 or more" gets its full label back.
 *
 * The active value is marked with a tick as well as being named on the button, so it is
 * not carried by the menu's selection colour alone.
 */
@Composable
private fun ChapterFilterMenu(selected: ChapterFilter, onSelect: (ChapterFilter) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box {
		OutlinedButton(onClick = { open = true }) {
			AppIcon(AppIcons.FILTER, contentDescription = null)
			Box(Modifier.size(Space.xs))
			Text("Chapters: ${selected.label}")
			Box(Modifier.size(Space.xs))
			AppIcon(AppIcons.EXPAND_MORE, contentDescription = null)
		}
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			for (filter in ChapterFilter.entries) {
				DropdownMenuItem(
					text = { Text(filter.label) },
					leadingIcon = {
						if (filter == selected) {
							AppIcon(AppIcons.CHECK, contentDescription = null)
						}
					},
					onClick = {
						open = false
						onSelect(filter)
					},
				)
			}
		}
	}
}

/**
 * Rename, retrack and delete for the category currently in view.
 *
 * Drawn only when a category is selected, because editing the "All" view means nothing
 * and an icon-only button that opens an empty menu is worse than no button.
 */
@Composable
private fun LibraryOverflow(category: FavouriteCategoryEntity, onEdit: () -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box {
		IconButton(onClick = { open = true }) {
			AppIcon(AppIcons.MORE, contentDescription = "More library options")
		}
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			DropdownMenuItem(
				text = { Text("Edit ${category.title}") },
				leadingIcon = { AppIcon(AppIcons.EDIT, contentDescription = null) },
				onClick = {
					open = false
					onEdit()
				},
			)
		}
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
			// Capped and scrollable. The list is as long as the reader has categories, and
			// an unbounded Column of a dozen of them at interface scale 2.0 made the
			// dialog taller than the window and put Cancel off-screen.
			Column(
				modifier = Modifier
					.heightIn(max = DIALOG_LIST_MAX_HEIGHT)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(Space.xs),
			) {
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
							.padding(vertical = Space.sm),
					)
				}
			}
		},
		confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}

/**
 * A dialog body that loops over a caller-supplied list stops growing here.
 *
 * `CurateScreen` already capped its own at 320 dp; the three dialogs that did not simply
 * grew past the bottom of the window and took their confirm button with them.
 */
private val DIALOG_LIST_MAX_HEIGHT = 400.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryChips(
	categories: List<FavouriteCategoryEntity>,
	selected: Long?,
	onSelect: (Long?) -> Unit,
	onManage: (FavouriteCategoryEntity) -> Unit,
	modifier: Modifier = Modifier,
) {
	ChipRow(modifier = modifier) {
		FilterChip(
			selected = selected == null,
			onClick = { onSelect(null) },
			label = { Text("All") },
		)
		for (category in categories) {
			val isSelected = selected == category.categoryId
			FilterChip(
				selected = isSelected,
				onClick = { onSelect(category.categoryId) },
				label = { Text(category.title) },
				// Right-click edits the category in view. Editing used to be an "Edit"
				// chip appended to this row, which made the row's length depend on the
				// selection and put a verb in a row of nouns. The same action is in the
				// overflow menu beside the filter, which is where anyone who does not try
				// the secondary mouse button will find it.
				modifier = if (isSelected) {
					Modifier.onClick(
						matcher = PointerMatcher.mouse(PointerButton.Secondary),
						onClick = { onManage(category) },
					)
				} else {
					Modifier
				},
			)
		}
	}
}

@Composable
private fun LibraryCard(
	item: LibraryItem,
	state: AppState,
	hasNewChapters: Boolean,
	onOpen: (MangaParserSource, org.koitharu.kotatsu.parsers.model.Manga) -> Unit,
	onFixSource: () -> Unit,
) {
	val source = remember(item.sourceName) { catalogueByName[item.sourceName] }
	val health = remember(item.sourceName) { sourceHealth(item.sourceName) }
	CoverCard(
		title = item.manga.title,
		coverUrl = item.manga.coverUrl,
		// Covers need the source's own client; falling back to a plain one would
		// 403 on sources that check headers (see D1).
		client = remember(source) {
			source?.let { state.sources.session(it).client } ?: okhttp3.OkHttpClient()
		},
		images = state.images,
		// Always clickable. A card that cannot be read leads to the repair instead of
		// to the reader, which beats a card that swallows the click and does nothing.
		onClick = {
			if (health == SourceHealth.Ok && source != null) onOpen(source, item.manga) else onFixSource()
		},
		secondary = if (item.chaptersCount > 0) {
			"${item.chaptersCount} chapters"
		} else {
			"chapters not loaded"
		},
		// On the cover rather than a fourth line of text under it. A card could stack
		// title over two lines, a chapter count, "new chapters" and a source problem,
		// which is five lines of caption per cover and is why the rows read as ragged.
		badge = if (hasNewChapters) CoverBadge("New", CoverBadgeTone.New) else null,
		// Name which of the two problems it is. A source flagged broken used to look
		// identical to a working one here and only failed once the reader was open.
		problem = when (health) {
			SourceHealth.Missing -> "source unavailable, tap to fix"
			SourceHealth.Broken -> "source is broken, tap to fix"
			SourceHealth.Ok -> null
		},
	)
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
	onSetTracked: (Boolean) -> Unit,
	onDelete: () -> Unit,
) {
	var name by remember(category.categoryId) { mutableStateOf(category.title) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Edit category") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					label = { Text("Name") },
					singleLine = true,
				)
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.md),
				) {
					Switch(
						checked = category.track,
						onCheckedChange = { onSetTracked(it) },
					)
					Column {
						Text("Check for new chapters", style = MaterialTheme.typography.bodyLarge)
						Text(
							// Saying what it costs, because it is one live request per
							// title every time a check runs.
							text = "Titles here are included when Updates checks. " +
								"Turn it off for a shelf you have finished with.",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
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
