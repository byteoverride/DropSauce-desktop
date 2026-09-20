package org.koitharu.kotatsu.desktop.feature.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.desktop.ui.TopBar

/**
 * The downloads screen: every queued, running and finished chapter, grouped by title.
 *
 * Grouped rather than one flat list because the unit a user thinks in is the title;
 * a hundred chapters of one series as a hundred top-level rows is unreadable.
 */
@Composable
fun DownloadsScreen(
	context: FeatureContext,
	navigator: FeatureNavigator,
	repository: DownloadRepository,
) {
	val scope = rememberCoroutineScope()
	val groupsFlow: Flow<List<DownloadGroup>> = remember(repository) { repository.observeGroups() }
	val groups by groupsFlow.collectAsState(initial = emptyList())
	val paused by repository.isPaused.collectAsState()

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Downloads",
			subtitle = summary(groups),
			trailing = {
				if (groups.any { it.isBusy }) {
					Button(onClick = { if (paused) repository.resume() else repository.pause() }) {
						Text(if (paused) "Resume" else "Pause")
					}
				}
			},
		)
		HorizontalDivider()
		if (groups.isEmpty()) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					text = "Nothing downloaded yet.\nOpen a title and download a chapter to read it offline.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			return@Column
		}
		LazyColumn(
			modifier = Modifier.fillMaxSize().padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			items(groups, key = { it.manga.id }) { group ->
				GroupCard(
					context = context,
					group = group,
					onOpen = { item ->
						scope.launch {
							val chapters = repository.offlineChapters(group.manga.id)
							val index = chapters.indexOfFirst { it.id == item.chapterId }
							if (index >= 0) {
								navigator.openLocalReader(group.manga, chapters, index)
							}
						}
					},
					onCancel = { item -> scope.launch { repository.cancel(item.mangaId, item.chapterId) } },
					onRetry = { item -> scope.launch { repository.retry(item.mangaId, item.chapterId) } },
					onDelete = { item -> scope.launch { repository.delete(item.mangaId, item.chapterId) } },
					onDeleteAll = { scope.launch { repository.deleteAll(group.manga.id) } },
				)
			}
		}
	}
}

@Composable
private fun GroupCard(
	context: FeatureContext,
	group: DownloadGroup,
	onOpen: (DownloadItem) -> Unit,
	onCancel: (DownloadItem) -> Unit,
	onRetry: (DownloadItem) -> Unit,
	onDelete: (DownloadItem) -> Unit,
	onDeleteAll: () -> Unit,
) {
	Card(Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			val source = remember(group.sourceName) { parserSource(group.sourceName) }
			if (source != null) {
				RemoteImage(
					url = group.manga.coverUrl,
					client = context.clientFor(source),
					cache = context.images,
					contentDescription = group.manga.title,
					modifier = Modifier.size(width = 52.dp, height = 74.dp).clip(RoundedCornerShape(6.dp)),
				)
			}
			Column(Modifier.weight(1f)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) {
						Text(
							text = group.manga.title,
							style = MaterialTheme.typography.titleMedium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							text = "${group.doneCount} of ${group.items.size} chapters on disk  ·  ${group.sourceName}",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					TextButton(onClick = onDeleteAll) { Text("Delete all") }
				}
				Box(Modifier.height(6.dp))
				for (item in group.items) {
					ChapterRow(
						item = item,
						onOpen = { onOpen(item) },
						onCancel = { onCancel(item) },
						onRetry = { onRetry(item) },
						onDelete = { onDelete(item) },
					)
				}
			}
		}
	}
}

@Composable
private fun ChapterRow(
	item: DownloadItem,
	onOpen: () -> Unit,
	onCancel: () -> Unit,
	onRetry: () -> Unit,
	onDelete: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Column(Modifier.weight(1f)) {
			Text(
				text = item.chapterTitle,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = statusLine(item),
				style = MaterialTheme.typography.bodySmall,
				color = if (item.state == DownloadState.FAILED) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			if (DownloadState.isActive(item.state)) {
				LinearProgressIndicator(
					progress = { item.progress },
					modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
				)
			}
		}
		Box(Modifier.width(4.dp))
		when {
			item.state == DownloadState.DONE -> TextButton(onClick = onOpen) { Text("Read") }
			DownloadState.isActive(item.state) -> TextButton(onClick = onCancel) { Text("Cancel") }
			else -> TextButton(onClick = onRetry) { Text("Retry") }
		}
		TextButton(onClick = onDelete) { Text("Delete") }
	}
}

private fun statusLine(item: DownloadItem): String = when (item.state) {
	DownloadState.QUEUED -> "Queued"
	DownloadState.RUNNING -> if (item.pagesTotal > 0) {
		"Downloading ${item.pagesDone} / ${item.pagesTotal} pages"
	} else {
		"Starting"
	}

	DownloadState.DONE -> "${item.pagesTotal} pages on disk"
	DownloadState.CANCELLED -> "Cancelled"
	// The message is the point of a failed row: "failed" alone sends the user to a log
	// they do not have.
	DownloadState.FAILED -> "Failed: ${item.error ?: "unknown error"}"
	else -> item.state
}

private fun summary(groups: List<DownloadGroup>): String {
	if (groups.isEmpty()) return "No downloads"
	val items = groups.flatMap { it.items }
	val active = items.count { DownloadState.isActive(it.state) }
	val failed = items.count { it.state == DownloadState.FAILED }
	val done = items.count { it.state == DownloadState.DONE }
	return buildString {
		append("$done ready")
		if (active > 0) append(", $active in queue")
		if (failed > 0) append(", $failed failed")
	}
}
