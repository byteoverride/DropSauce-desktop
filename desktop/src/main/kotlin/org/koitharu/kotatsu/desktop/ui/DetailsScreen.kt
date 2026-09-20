package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Title details and the chapter list.
 *
 * The [Manga] handed in from a browse grid carries only list-level fields, so details are
 * always refetched; `getDetails` is what populates chapters and description.
 */
@Composable
fun DetailsScreen(
	state: AppState,
	source: MangaParserSource,
	seed: Manga,
	onBack: () -> Unit,
	onRead: (List<MangaChapter>, Int) -> Unit,
) {
	val session = remember(source) { state.sources.session(source) }
	var manga by remember(seed.id) { mutableStateOf(seed) }
	var loading by remember(seed.id) { mutableStateOf(true) }
	var error: String? by remember(seed.id) { mutableStateOf(null) }
	var attempt by remember(seed.id) { mutableStateOf(0) }

	LaunchedEffect(seed.id, attempt) {
		loading = true
		error = null
		runCatching { withContext(Dispatchers.IO) { session.parser.getDetails(seed) } }
			.onSuccess { manga = it }
			.onFailure { error = it.message ?: it::class.simpleName ?: "Request failed" }
		loading = false
	}

	val chapters = manga.chapters.orEmpty()
	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = manga.title,
			subtitle = source.title,
			onBack = onBack,
			trailing = {
				if (chapters.isNotEmpty()) {
					Button(onClick = { onRead(chapters, 0) }) { Text("Read") }
				}
			},
		)
		when {
			loading && chapters.isEmpty() -> LoadingBox()
			error != null && chapters.isEmpty() -> ErrorBox(
				message = "Could not load this title.\n$error",
				onRetry = { attempt++ },
			)

			else -> LazyColumn(Modifier.fillMaxSize()) {
				item {
					Header(manga = manga, state = state, client = session.client)
					HorizontalDivider()
				}
				if (chapters.isEmpty()) {
					item {
						Text(
							text = "This source returned no chapters for this title.",
							style = MaterialTheme.typography.bodyMedium,
							modifier = Modifier.padding(20.dp),
						)
					}
				} else {
					item {
						Text(
							text = "${chapters.size} chapters",
							style = MaterialTheme.typography.titleSmall,
							modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
						)
					}
					items(chapters, key = { it.id }) { chapter ->
						ChapterRow(
							chapter = chapter,
							onClick = { onRead(chapters, chapters.indexOf(chapter)) },
						)
						HorizontalDivider()
					}
				}
			}
		}
	}
}

@Composable
private fun Header(manga: Manga, state: AppState, client: okhttp3.OkHttpClient) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(20.dp),
		horizontalArrangement = Arrangement.spacedBy(16.dp),
	) {
		RemoteImage(
			url = manga.largeCoverUrl ?: manga.coverUrl,
			client = client,
			cache = state.images,
			contentDescription = manga.title,
			modifier = Modifier.width(180.dp).height(260.dp).clip(RoundedCornerShape(12.dp)),
		)
		Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
			Text(manga.title, style = MaterialTheme.typography.headlineSmall)
			manga.authors.firstOrNull()?.let {
				Text(it, style = MaterialTheme.typography.bodyMedium)
			}
			val facts = buildList {
				manga.state?.let { add(it.name.lowercase().replace('_', ' ')) }
				if (manga.rating > 0f) add("rating ${"%.1f".format(manga.rating * 10)}")
				manga.contentRating?.let { add(it.name.lowercase()) }
			}
			if (facts.isNotEmpty()) {
				Text(
					text = facts.joinToString("  ·  "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			if (manga.tags.isNotEmpty()) {
				Text(
					text = manga.tags.joinToString(", ") { it.title },
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			manga.description?.takeIf { it.isNotBlank() }?.let { description ->
				Text(
					// Descriptions arrive as HTML from most sources; this is a plain-text
					// reduction, not a renderer. A real HTML view belongs with the novel
					// reader, which is deferred to v1.1 by D2.
					text = description.replace(HTML_TAG, " ").replace(WHITESPACE, " ").trim(),
					style = MaterialTheme.typography.bodyMedium,
					maxLines = 10,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun ChapterRow(chapter: MangaChapter, onClick: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 20.dp, vertical = 12.dp),
	) {
		Text(
			text = chapter.title ?: "Chapter ${chapter.number}",
			style = MaterialTheme.typography.bodyLarge,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		val meta = buildList {
			chapter.scanlator?.takeIf { it.isNotBlank() }?.let { add(it) }
			if (chapter.volume > 0) add("vol ${chapter.volume}")
		}
		if (meta.isNotEmpty()) {
			Text(
				text = meta.joinToString("  ·  "),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

private val HTML_TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
