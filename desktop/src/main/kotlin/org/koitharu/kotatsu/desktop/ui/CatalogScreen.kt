package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * The source catalogue.
 *
 * Only sources the library does not flag as broken are listed, per DECISIONS.md D18:
 * roughly 380 of 1270 are flagged and listing them would mean most of what a user clicks
 * simply fails.
 */
@Composable
fun CatalogScreen(onPick: (MangaParserSource) -> Unit) {
	var query by remember { mutableStateOf("") }
	val all = remember { SourceRegistry.usableSources }
	val filtered = remember(query) {
		if (query.isBlank()) {
			all
		} else {
			val q = query.trim().lowercase()
			all.filter { it.title.lowercase().contains(q) || it.locale.lowercase() == q }
		}
	}
	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Sources",
			subtitle = "${filtered.size} of ${all.size} available",
		)
		OutlinedTextField(
			value = query,
			onValueChange = { query = it },
			label = { Text("Filter by name or language code") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
		)
		val listState = rememberLazyListState()
		LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
			items(filtered, key = { it.name }) { source ->
				SourceRow(source = source, onClick = { onPick(source) })
				HorizontalDivider()
			}
		}
	}
}

@Composable
private fun SourceRow(source: MangaParserSource, onClick: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 20.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.weight(1f)) {
			Text(
				text = source.title,
				style = MaterialTheme.typography.bodyLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = buildString {
					append(source.contentType.name.lowercase())
					if (source.locale.isNotEmpty()) {
						append("  ")
						append(source.locale)
					}
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
