package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.suggestions.RelatedTitlesStrip
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.HistoryEntity

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
	onRead: (List<MangaChapter>, Int, Int) -> Unit,
	onOpenRelated: (MangaParserSource, Manga) -> Unit = { _, _ -> },
) {
	val session = remember(source) { state.sources.session(source) }
	var manga by remember(seed.id) { mutableStateOf(seed) }
	var loading by remember(seed.id) { mutableStateOf(true) }
	var error: String? by remember(seed.id) { mutableStateOf(null) }
	var attempt by remember(seed.id) { mutableStateOf(0) }
	var choosingCategories by remember(seed.id) { mutableStateOf(false) }
	val savedIn by remember(seed.id) { state.library.observeCategoriesOf(seed) }
		.collectAsState(emptySet())
	var history: HistoryEntity? by remember(seed.id) { mutableStateOf(null) }

	LaunchedEffect(seed.id) { history = state.library.findHistory(seed) }

	LaunchedEffect(seed.id, attempt) {
		loading = true
		error = null
		runCatching { withContext(Dispatchers.IO) { session.parser.getDetails(seed) } }
			.onSuccess {
				manga = it
				// Keeps a library entry's stored chapter count current, which is what the
				// library's length filter reads.
				state.library.refreshStored(it)
			}
			.onFailure { error = it.message ?: it::class.simpleName ?: "Request failed" }
		loading = false
	}

	if (choosingCategories) {
		CategoryPickerDialog(
			state = state,
			manga = manga,
			savedIn = savedIn,
			onDismiss = { choosingCategories = false },
		)
	}

	val chapters = manga.chapters.orEmpty()
	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = manga.title,
			subtitle = source.title,
			onBack = onBack,
		)
		// Actions live on their own row rather than in the top bar. In the bar they
		// competed with the title for width, and at a larger interface scale in a
		// narrow window they lost and vanished entirely, which left no way to reach
		// Continue or Add to library at all.
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.horizontalScroll(rememberScrollState())
				.padding(horizontal = 20.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (chapters.isNotEmpty()) {
				// Resume only when the recorded chapter is still in the list; a source
				// can renumber or drop chapters between visits.
				val resumeIndex = history?.let { h ->
					chapters.indexOfFirst { it.id == h.chapterId }.takeIf { it >= 0 }
				}
				if (resumeIndex != null) {
					Button(onClick = { onRead(chapters, resumeIndex, history?.page ?: 0) }) {
						Text("Continue")
					}
					OutlinedButton(onClick = { onRead(chapters, 0, 0) }) { Text("Start over") }
				} else {
					Button(onClick = { onRead(chapters, 0, 0) }) { Text("Read") }
				}
			}
			OutlinedButton(onClick = { choosingCategories = true }) {
				Text(if (savedIn.isEmpty()) "Add to library" else "In library (${savedIn.size})")
			}
		}
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
					RelatedTitlesStrip(
						context = state.featureContext,
						source = source,
						manga = manga,
						onOpen = onOpenRelated,
					)
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
							isCurrent = chapter.id == history?.chapterId,
							onClick = { onRead(chapters, chapters.indexOf(chapter), 0) },
						)
						HorizontalDivider()
					}
				}
			}
		}
	}
}

/**
 * Category picker.
 *
 * A title can sit in several categories at once, matching the Android app, so this is a
 * set of toggles rather than a single choice. Creating a category from here matters:
 * a fresh install has only the seeded one, and being sent to another screen to make a
 * second would break the flow of filing something away.
 */
@Composable
private fun CategoryPickerDialog(
	state: AppState,
	manga: Manga,
	savedIn: Set<Long>,
	onDismiss: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	val categories by state.library.observeCategories().collectAsState(emptyList())
	var creating by remember { mutableStateOf(false) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Save to") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				if (categories.isEmpty()) {
					Text(
						"No categories yet.",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				for (category in categories) {
					val checked = category.categoryId in savedIn
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable {
								scope.launch {
									state.library.setFavourite(manga, category.categoryId, !checked)
								}
							}
							.padding(vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Checkbox(checked = checked, onCheckedChange = null)
						Text(category.title, style = MaterialTheme.typography.bodyLarge)
					}
				}
				TextButton(onClick = { creating = true }) { Text("New category") }
			}
		},
		confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
	)

	if (creating) {
		CategoryNameDialog(
			title = "New category",
			initial = "",
			confirm = "Create",
			onDismiss = { creating = false },
			onConfirm = { name ->
				scope.launch {
					// Put the title straight into the category that was just made for it.
					val id = state.library.createCategory(name)
					state.library.setFavourite(manga, id, true)
				}
				creating = false
			},
		)
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
private fun ChapterRow(chapter: MangaChapter, isCurrent: Boolean, onClick: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 20.dp, vertical = 12.dp),
	) {
		Text(
			text = chapter.title ?: "Chapter ${chapter.number}",
			style = MaterialTheme.typography.bodyLarge,
			color = if (isCurrent) {
				MaterialTheme.colorScheme.primary
			} else {
				MaterialTheme.colorScheme.onSurface
			},
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
