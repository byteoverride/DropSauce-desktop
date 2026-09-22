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
import androidx.compose.material3.RadioButton
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
import org.koitharu.kotatsu.desktop.feature.download.DownloadFeature
import org.koitharu.kotatsu.desktop.feature.download.DownloadItem
import org.koitharu.kotatsu.desktop.feature.download.DownloadState
import org.koitharu.kotatsu.desktop.feature.download.ChapterSelection
import org.koitharu.kotatsu.desktop.feature.download.branchesOf
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
	/**
	 * Takes the loaded title, not the seed the screen was opened with. Migration matches
	 * the reading position against the old chapter list, and the seed carries none, so
	 * handing over the seed would make it refetch what this screen already has.
	 */
	onFindAlternative: (Manga) -> Unit = {},
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
	var choosingDownload by remember(seed.id) { mutableStateOf(false) }
	var downloadNotice: String? by remember(seed.id) { mutableStateOf(null) }
	// The same queue the Downloads screen shows. DownloadFeature.repository is public
	// precisely so a second one is never started against the same database.
	val downloads = remember(state) { DownloadFeature.repository(state.featureContext) }
	val queued by remember(seed.id) { downloads.observeFor(seed.id) }.collectAsState(emptyList())
	val queuedByChapter = remember(queued) { queued.associateBy { it.chapterId } }

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

	if (choosingDownload) {
		DownloadChaptersDialog(
			chapters = chapters,
			lastReadChapterId = history?.chapterId,
			alreadyQueued = queuedByChapter.keys,
			onDismiss = { choosingDownload = false },
			onConfirm = { selection ->
				choosingDownload = false
				val wanted = selection.select(chapters).filterNot { it.id in queuedByChapter.keys }
				// The app scope: queueing writes rows and a title's chapter list can be
				// long, so leaving the screen must not abandon it half written.
				state.scope.launch {
					val added = downloads.download(manga, wanted)
					downloadNotice = when {
						added > 0 -> "Queued $added ${if (added == 1) "chapter" else "chapters"}."
						else -> "Nothing to queue; those chapters are already downloaded."
					}
				}
			},
		)
	}

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
			if (chapters.isNotEmpty()) {
				val done = queued.count { it.state == DownloadState.DONE }
				OutlinedButton(onClick = { choosingDownload = true }) {
					Text(if (done > 0) "Download ($done saved)" else "Download")
				}
			}
			// Deliberately not gated on having chapters: the states this answers are the
			// ones with none. Hidden only when the failure below is already offering it,
			// which would otherwise put the same button twice on one screen.
			val offeredBelow = error != null && chapters.isEmpty()
			if (!offeredBelow) {
				OutlinedButton(onClick = { onFindAlternative(manga) }) { Text("Other sources") }
			}
		}
		downloadNotice?.let { message ->
			Text(
				text = message,
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
			)
		}
		when {
			loading && chapters.isEmpty() -> LoadingBox()
			error != null && chapters.isEmpty() -> ErrorBox(
				// A 404, 403 or 502 here means this source cannot serve the title, not
				// that the title does not exist. Retry is the right answer to a timeout
				// and no answer at all to a page that has been taken down, so the way out
				// sits next to it rather than somewhere the reader has to go looking.
				message = "Could not load this title from ${source.title}.\n$error",
				onRetry = { attempt++ },
			) {
				// The seed, because the load is what failed: it carries the title, which
				// is all the search needs.
				OutlinedButton(onClick = { onFindAlternative(manga) }) {
					Text("Find it on another source")
				}
			}

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
						Column(
							modifier = Modifier.padding(20.dp),
							verticalArrangement = Arrangement.spacedBy(10.dp),
						) {
							Text(
								text = "This source returned no chapters for this title. " +
									"That usually means the source has dropped it or has not " +
									"published any yet, and another source may still have it.",
								style = MaterialTheme.typography.bodyMedium,
							)
							Button(onClick = { onFindAlternative(manga) }) {
								Text("Find it on another source")
							}
						}
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
							download = queuedByChapter[chapter.id],
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

/**
 * Which chapters to queue.
 *
 * The four choices the Android app offers, in the same order, because someone who uses
 * both should not have to work out that they are the same feature. Each one shows how
 * many chapters it would actually add, counting out what is already downloaded, so the
 * size of the thing is visible before it starts rather than after.
 */
@Composable
private fun DownloadChaptersDialog(
	chapters: List<MangaChapter>,
	lastReadChapterId: Long?,
	alreadyQueued: Set<Long>,
	onDismiss: () -> Unit,
	onConfirm: (ChapterSelection) -> Unit,
) {
	val branches = remember(chapters) { branchesOf(chapters) }
	val options = remember(chapters, lastReadChapterId, branches) {
		buildList {
			add("Everything" to ChapterSelection.Everything)
			// Only worth offering when there is more than one translation to choose
			// between; on a single-branch title it is the same list under another name.
			if (branches.size > 1) {
				for (branch in branches) {
					add("Only ${branch ?: "the unnamed branch"}" to ChapterSelection.Branch(branch))
				}
			}
			if (lastReadChapterId != null) {
				add("Everything after what I have read" to ChapterSelection.Unread(lastReadChapterId))
			}
			for (n in listOf(5, 10, 25)) {
				if (chapters.size > n) add("The next $n" to ChapterSelection.First(n, null))
			}
		}
	}
	var selected by remember(options) { mutableStateOf(options.first().second) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Download chapters") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				for ((label, selection) in options) {
					val newCount = remember(selection, alreadyQueued) {
						selection.select(chapters).count { it.id !in alreadyQueued }
					}
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable { selected = selection }
							.padding(vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(10.dp),
					) {
						RadioButton(selected = selected == selection, onClick = { selected = selection })
						Column {
							Text(label, style = MaterialTheme.typography.bodyLarge)
							Text(
								text = if (newCount == 0) {
									"nothing new"
								} else {
									"$newCount ${if (newCount == 1) "chapter" else "chapters"}"
								},
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}
		},
		confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Download") } },
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}

@Composable
private fun ChapterRow(
	chapter: MangaChapter,
	isCurrent: Boolean,
	download: DownloadItem?,
	onClick: () -> Unit,
) {
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
			// Whether this chapter is already on disk belongs next to the chapter, not
			// only on a separate downloads screen: it is the thing you want to know while
			// deciding what to queue.
			when (download?.state) {
				DownloadState.DONE -> add("saved")
				DownloadState.RUNNING -> add("downloading ${(download.progress * 100).toInt()}%")
				DownloadState.QUEUED -> add("queued")
				DownloadState.FAILED -> add("download failed")
				else -> Unit
			}
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
