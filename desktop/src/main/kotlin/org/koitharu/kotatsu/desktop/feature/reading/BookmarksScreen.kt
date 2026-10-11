package org.koitharu.kotatsu.desktop.feature.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.ui.AppIcon
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.AppListRow
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.ThumbSize
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
	// Keyed by the composite bookmark key: a page id alone is only unique within a title.
	var openingKey: String? by remember { mutableStateOf(null) }
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
			openingKey = bookmark.keyOf()
			failure = null
			val details = runCatching {
				withContext(Dispatchers.IO) {
					context.sources.session(source).parser.getDetails(bookmark.manga)
				}
			}
			openingKey = null
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
					// Straight into the bar's own Row: it already spaces its children by
					// Space.sm, so the wrapper this used to carry was a second gap rule.
					for (option in BookmarkGrouping.entries) {
						Button(
							onClick = { grouping = option },
							enabled = option != grouping,
						) {
							Text(if (option == BookmarkGrouping.NEWEST) "Newest" else "By title")
						}
					}
				}
			},
		)
		failure?.let { message ->
			// Compact, because the list underneath is still usable: one dead chapter is
			// not a reason to throw away every other bookmark on screen. The icon and
			// the headline carry "this failed", so the text is not red on its own.
			ErrorBox(message = message, compact = true)
		}
		if (bookmarks.isEmpty()) {
			EmptyBox(
				headline = "No bookmarks yet.",
				detail = "Save a page while reading and it shows up here.",
				icon = AppIcons.BOOKMARKS,
			)
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
							busy = openingKey == bookmark.keyOf(),
							onOpen = { open(bookmark) },
							onDelete = {
								scope.launch { repository.remove(bookmark.manga.id, bookmark.pageId) }
							},
						)
						// Decoration now, not structure: the row's own minimum height and
						// hover state carry the separation. See UI_SPEC 1.6.
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
									busy = openingKey == bookmark.keyOf(),
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
	// An icon toggle rather than an outlined button with a text label: the reader's top
	// bar has room for a 48 dp control and the two unicode stars it used to draw differ
	// only in fill at that size.
	//
	// Nothing here sets a colour. This renders inside the reader, which wraps its own
	// subtree in a nested theme, so the checked tint and the content colour both arrive
	// from that theme. The state is carried by the filled glyph *and* by the description,
	// so neither the fill nor the tint is signalling it alone.
	IconToggleButton(
		checked = isSaved,
		onCheckedChange = {
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
		AppIcon(
			name = AppIcons.BOOKMARKS,
			// A toggle's meaning is its state, and this control has no visible label to
			// read it off, so the state goes in the description rather than in the glyph.
			contentDescription = if (isSaved) "Remove this bookmark" else "Bookmark this page",
			filled = isSaved,
		)
	}
}

/**
 * The title a group of bookmarks sits under.
 *
 * Deliberately not `SectionHeader`: a manga title is long and arbitrary, and
 * `SectionHeader` gives its text node no `weight` and no `maxLines`, so a long one
 * squeezes the count and the button beside it instead of ellipsising. The style, the
 * gutter and the vertical rhythm here are the same as that component's.
 */
@Composable
private fun GroupHeader(title: String, count: Int, onDeleteAll: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.padding(horizontal = Space.lg, vertical = Space.md),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
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
	val pageLabel = "Page ${bookmark.page + 1}"
	AppListRow(
		// Under a group header the title is already on screen, so the page is what
		// identifies the row; a blank headline would leave it with no first line.
		headline = if (showTitle) bookmark.manga.title else pageLabel,
		// No click while the details fetch is in flight: a second click would start a
		// second round trip to the same source for the same row.
		onClick = if (busy) null else onOpen,
		supporting = pageLabel.takeIf { showTitle },
		meta = listOf(
			"${(bookmark.percent * 100).toInt()}% through",
			bookmark.sourceName,
			formatTimestamp(bookmark.createdAt),
		),
		leading = {
			RemoteImage(
				url = bookmark.imageUrl,
				client = client,
				cache = context.images,
				contentDescription = pageLabel,
				modifier = Modifier.size(ThumbSize.list).clip(MaterialTheme.shapes.small),
			)
		},
		trailing = {
			if (busy) {
				// Stays a 20 dp indicator rather than becoming a LoadingStrip: it
				// replaces one button in place, and a strip would relabel the whole row
				// as loading when only its own open is in flight (UI_SPEC 5.3).
				CircularProgressIndicator(Modifier.size(20.dp))
			} else {
				TextButton(onClick = onDelete) { Text("Remove") }
			}
		},
	)
}

/** Bookmarks are keyed by the pair, so neither half alone is unique in the list. */
private fun Bookmark.keyOf(): String = "${manga.id}:$pageId"

private fun Int.plural(one: String, many: String): String =
	if (this == 1) "$this $one" else "$this $many"

private val timestampFormat: DateTimeFormatter =
	DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatTimestamp(epochMillis: Long): String =
	Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timestampFormat)
