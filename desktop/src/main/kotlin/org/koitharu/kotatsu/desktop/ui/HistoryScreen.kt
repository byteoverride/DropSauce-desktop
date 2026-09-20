package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					"Nothing read yet.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		} else {
			LazyColumn(Modifier.fillMaxSize()) {
				items(items, key = { it.manga.id }) { item ->
					HistoryRow(item = item, state = state, onOpen = onOpen)
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
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = source != null) { source?.let { onOpen(it, item.manga) } }
			.padding(horizontal = 16.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RemoteImage(
			url = item.manga.coverUrl,
			client = remember(source) {
				source?.let { state.sources.session(it).client } ?: okhttp3.OkHttpClient()
			},
			cache = state.images,
			contentDescription = item.manga.title,
			modifier = Modifier.width(48.dp).height(68.dp).clip(RoundedCornerShape(6.dp)),
		)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Text(
				text = item.manga.title,
				style = MaterialTheme.typography.bodyLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = "${item.sourceName}  ·  page ${item.page + 1}",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (item.percent > 0f) {
				LinearProgressIndicator(
					progress = { item.percent.coerceIn(0f, 1f) },
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}
}
