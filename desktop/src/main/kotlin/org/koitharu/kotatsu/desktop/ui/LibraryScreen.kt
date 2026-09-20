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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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

	val all by remember(selected) { state.library.observeFavourites(selected) }
		.collectAsState(emptyList())
	val items = remember(all, lengthFilter) { all.filter(lengthFilter::matches) }

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
			onSelect = { selected = it },
			onManage = { manageTarget = it },
		)
		ChapterFilterChips(selected = lengthFilter, onSelect = { lengthFilter = it })
		when {
			items.isEmpty() && all.isNotEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
				Text(
					"No titles match ${lengthFilter.label.lowercase()}.",
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
					LibraryCard(item = item, state = state, onOpen = onOpen)
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
 * Counts come from the stored `chapters_count`, written whenever a title's details are
 * loaded. A title saved but never opened has no count, so [Unknown] exists to make those
 * findable rather than quietly absent from every bucket.
 */
enum class ChapterFilter(val label: String, private val range: IntRange?) {

	Any("Any length", null),
	Unknown("Not loaded", 0..0),
	Short("1 to 25", 1..25),
	Medium("26 to 100", 26..100),
	Long("101 to 500", 101..500),
	VeryLong("Over 500", 501..Int.MAX_VALUE),
	;

	fun matches(item: LibraryItem): Boolean =
		range == null || item.chaptersCount in range
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
) {
	val source = remember(item.sourceName) {
		MangaParserSource.entries.firstOrNull { it.name == item.sourceName }
	}
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = source != null) { source?.let { onOpen(it, item.manga) } },
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
		if (source == null) {
			// The stored source is not in this build's catalogue. Say so rather than
			// silently doing nothing on click.
			Text(
				text = "source unavailable",
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
