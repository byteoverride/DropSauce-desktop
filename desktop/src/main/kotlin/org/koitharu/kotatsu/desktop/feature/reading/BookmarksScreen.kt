package org.koitharu.kotatsu.desktop.feature.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.desktop.ui.TopBar
import org.koitharu.kotatsu.parsers.model.Manga
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Newest first, or gathered under each title. */
private enum class BookmarkGrouping { NEWEST, BY_TITLE }

/**
 * Saved pages.
 *
 * A bookmark stores a chapter id, not a chapter list, so opening one has to refetch the
 * title's details first. That is a network round trip on click, which is why the row
 * shows its own spinner rather than the screen freezing.
 */
@Composable
fun BookmarksScreen(context: FeatureContext, navigator: FeatureNavigator) {
	val repository = remember(context) { BookmarksRepository(context.db) }
	val scope = rememberCoroutineScope()
	val bookmarks by remember(repository) { repository.observeAll() }.collectAsState(emptyList())
	var grouping by remember { mutableStateOf(BookmarkGrouping.NEWEST) }
	var openingPage: Long? by remember { mutableStateOf(null) }
	var failure: String? by remember { mutableStateOf(null) }

	// One client for rows whose source no longer exists in the catalogue. Those page
	// urls are almost certainly dead too, but a plain client at least tries.
	val fallbackClient = remember { OkHttpClient() }

	fun open(bookmark: Bookmark) {
		val source = parserSourceOrNull(bookmark.sourceName)
		if (source == null) {
			failure = "${bookmark.sourceName} is no longer in the source catalogue."
			return
		}
		scope.launch {
			openingPage = bookmark.pageId
			failure = null
			val details = runCatching {
				withContext(Dispatchers.IO) {
					context.sources.session(source).parser.getDetails(bookmark.manga)
				}
			}
			openingPage = null
			details.onSuccess { full ->
				val chapters = full.chapters.orEmpty()
				val index = chapters.indexOfFirst { it.id == bookmark.chapterId }
				when {
					chapters.isEmpty() ->
						failure = "${full.title} no longer lists any chapters."
					// Deliberately not falling back to chapter 0: the saved page number
					// means nothing in a different chapter, so opening there would drop
					// the user somewhere arbitrary while looking like it worked.
					index < 0 ->
						failure = "That chapter is no longer listed by ${bookmark.sourceName}."

					else -> navigator.openReader(
						source = source,
						manga = full,
						chapters = chapters,
						chapterIndex = index,
						page = bookmark.page,
					)
				}
			}.onFailure {
				failure = it.message ?: it::class.simpleName ?: "Could not load this title."
			}
		}
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Bookmarks",
			subtitle = if (bookmarks.isEmpty()) null else bookmarks.size.plural("page", "pages"),
			trailing = {
				if (bookmarks.isNotEmpty()) {
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						for (option in BookmarkGrouping.entries) {
							Button(
								onClick = { grouping = option },
								enabled = option != grouping,
							) {
								Text(if (option == BookmarkGrouping.NEWEST) "Newest" else "By title")
							}
						}
					}
				}
			},
		)
		if (failure != null) {
			Text(
				text = failure.orEmpty(),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
			)
		}
		if (bookmarks.isEmpty()) {
			EmptyNote("No bookmarks yet.\nSave a page while reading and it shows up here.")
		} else {
			LazyColumn(Modifier.fillMaxSize()) {
				when (grouping) {
					BookmarkGrouping.NEWEST -> items(
						count = bookmarks.size,
						key = { i -> bookmarks[i].keyOf() },
					) { i ->
						val bookmark = bookmarks[i]
						BookmarkRow(
							bookmark = bookmark,
							context = context,
							fallbackClient = fallbackClient,
							busy = openingPage == bookmark.pageId,
							onOpen = { open(bookmark) },
							onDelete = {
								scope.launch { repository.remove(bookmark.manga.id, bookmark.pageId) }
							},
						)
						HorizontalDivider()
					}

					BookmarkGrouping.BY_TITLE -> {
						// Grouped by title but each group still newest first, and the
						// groups themselves ordered by their newest member, so the list
						// does not reshuffle into alphabetical order on a toggle.
						val groups = bookmarks.groupBy { it.manga.id }.values.toList()
						for (group in groups) {
							val head = group.first()
							item(key = "header-${head.manga.id}") {
								GroupHeader(
									title = head.manga.title,
									count = group.size,
									onDeleteAll = {
										scope.launch { repository.removeAllFor(head.manga.id) }
									},
								)
							}
							items(count = group.size, key = { i -> group[i].keyOf() }) { i ->
								val bookmark = group[i]
								BookmarkRow(
									bookmark = bookmark,
									context = context,
									fallbackClient = fallbackClient,
									busy = openingPage == bookmark.pageId,
									showTitle = false,
									onOpen = { open(bookmark) },
									onDelete = {
										scope.launch {
											repository.remove(bookmark.manga.id, bookmark.pageId)
										}
									},
								)
								HorizontalDivider()
							}
						}
					}
				}
			}
		}
	}
}

/**
 * The reader's bookmark button.
 *
 * Self-contained on purpose: it takes everything it needs as a parameter and owns its
 * own observation, so the reader can drop it in without the two files having to agree on
 * a state holder. It is not wired to the reader yet; the shell owns that file.
 */
@Composable
fun BookmarkToggle(
	repository: BookmarksRepository,
	manga: Manga,
	chapterId: Long,
	pageId: Long,
	page: Int,
	imageUrl: String,
	percent: Float,
	modifier: Modifier = Modifier,
) {
	val scope = rememberCoroutineScope()
	val saved by remember(repository, manga.id) {
		repository.observeFor(manga.id)
	}.collectAsState(emptyList())
	val isSaved = saved.any { it.pageId == pageId }
	// A star rather than an icon artifact, matching TopBar's back arrow: one glyph is
	// not worth pulling in material-icons.
	OutlinedButton(
		onClick = {
			scope.launch {
				repository.toggle(
					manga = manga,
					chapterId = chapterId,
					pageId = pageId,
					page = page,
					imageUrl = imageUrl,
					percent = percent,
				)
			}
		},
		modifier = modifier,
	) {
		Text(if (isSaved) "★ Saved" else "☆ Bookmark")
	}
}

@Composable
private fun GroupHeader(title: String, count: Int, onDeleteAll: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.padding(horizontal = 16.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleSmall,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = count.plural("page", "pages"),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		TextButton(onClick = onDeleteAll) { Text("Remove all") }
	}
}

@Composable
private fun BookmarkRow(
	bookmark: Bookmark,
	context: FeatureContext,
	fallbackClient: OkHttpClient,
	busy: Boolean,
	onOpen: () -> Unit,
	onDelete: () -> Unit,
	showTitle: Boolean = true,
) {
	val source = remember(bookmark.sourceName) { parserSourceOrNull(bookmark.sourceName) }
	val client = remember(source) { source?.let { context.clientFor(it) } ?: fallbackClient }
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = !busy) { onOpen() }
			.padding(horizontal = 16.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RemoteImage(
			url = bookmark.imageUrl,
			client = client,
			cache = context.images,
			contentDescription = "Page ${bookmark.page + 1}",
			modifier = Modifier.width(56.dp).height(76.dp).clip(RoundedCornerShape(6.dp)),
		)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
			if (showTitle) {
				Text(
					text = bookmark.manga.title,
					style = MaterialTheme.typography.bodyLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Text(
				text = "Page ${bookmark.page + 1}  ·  ${(bookmark.percent * 100).toInt()}% through",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(
				text = "${bookmark.sourceName}  ·  ${formatTimestamp(bookmark.createdAt)}",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		if (busy) {
			CircularProgressIndicator(Modifier.size(20.dp))
		} else {
			TextButton(onClick = onDelete) { Text("Remove") }
		}
	}
}

@Composable
private fun EmptyNote(message: String) {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Text(
			text = message,
			style = MaterialTheme.typography.bodyMedium,
			textAlign = TextAlign.Center,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/** Bookmarks are keyed by the pair, so neither half alone is unique in the list. */
private fun Bookmark.keyOf(): String = "${manga.id}:$pageId"

private fun Int.plural(one: String, many: String): String =
	if (this == 1) "$this $one" else "$this $many"

private val timestampFormat: DateTimeFormatter =
	DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatTimestamp(epochMillis: Long): String =
	Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timestampFormat)
