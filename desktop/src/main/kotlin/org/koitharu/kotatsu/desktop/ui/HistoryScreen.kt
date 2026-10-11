package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.library.HistoryItem
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/** Recently read titles, most recent first, with where you got to. */
@Composable
fun HistoryScreen(state: AppState, onOpen: (MangaParserSource, Manga) -> Unit) {
	val scope = rememberCoroutineScope()
	val items by remember { state.library.observeHistory() }.collectAsState(emptyList())
	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "History",
			subtitle = if (items.isEmpty()) null else "${items.size} titles",
			trailing = {
				if (items.isNotEmpty()) {
					Button(onClick = { scope.launch { state.library.clearHistory() } }) {
						Text("Clear")
					}
				}
			},
		)
		if (items.isEmpty()) {
			EmptyBox(
				headline = "Nothing read yet.",
				detail = "A title shows up here once you have opened a chapter, with the page you got to.",
			)
		} else {
			LazyColumn(Modifier.fillMaxSize()) {
				items(items, key = { it.manga.id }) { item ->
					HistoryRow(item = item, state = state, onOpen = onOpen)
					// Decoration now, not structure: the row's own height and hover state
					// carry the separation. See UI_SPEC 1.6.
					HorizontalDivider()
				}
			}
		}
	}
}

@Composable
private fun HistoryRow(
	item: HistoryItem,
	state: AppState,
	onOpen: (MangaParserSource, Manga) -> Unit,
) {
	val source = remember(item.sourceName) {
		MangaParserSource.entries.firstOrNull { it.name == item.sourceName }
	}
	AppListRow(
		headline = item.manga.title,
		// A title whose source is no longer in the catalogue cannot be opened, so the row
		// has no click rather than a click that does nothing.
		onClick = if (source != null) {
			{ onOpen(source, item.manga) }
		} else {
			null
		},
		meta = listOf(item.sourceName, "page ${item.page + 1}"),
		leading = {
			RemoteImage(
				url = item.manga.coverUrl,
				client = remember(source) {
					source?.let { state.sources.session(it).client } ?: okhttp3.OkHttpClient()
				},
				cache = state.images,
				contentDescription = item.manga.title,
				modifier = Modifier.size(ThumbSize.list).clip(MaterialTheme.shapes.small),
			)
		},
		progress = item.percent.takeIf { it > 0f }?.coerceIn(0f, 1f),
	)
}
