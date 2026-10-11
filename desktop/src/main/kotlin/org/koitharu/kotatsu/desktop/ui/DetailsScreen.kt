package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
 *
 * One primary action, one secondary, and the rest behind an overflow. Five buttons of
 * near-equal weight in one scrolling row told the reader that opening the title, filing
 * it, downloading it and repairing it were all the same size of decision, and the one
 * thing they came here to do was indistinguishable from the four they did not.
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
		// The only place the title appears. The header used to draw it again at
		// headlineSmall, 24 dp below this copy; the bar is outside the LazyColumn and so
		// survives scrolling a 105-chapter list, which makes the scrolling copy the one
		// worth less.
		TopBar(
			title = manga.title,
			subtitle = source.title,
			onBack = onBack,
		)
		// Actions live on their own row rather than in the top bar. In the bar they
		// competed with the title for width, and at a larger interface scale in a
		// narrow window they lost and vanished entirely, which left no way to reach
		// Continue or Add to library at all.
		//
		// The row no longer scrolls horizontally: two buttons and an icon need about
		// 330 dp against 555 dp even at interface scale 2.0, so the flexible gap in the
		// middle closes before anything could need to leave.
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = Space.lg, vertical = Space.xs),
			horizontalArrangement = Arrangement.spacedBy(Space.sm),
			verticalAlignment = Alignment.CenterVertically,
		) {
			// Resume only when the recorded chapter is still in the list; a source
			// can renumber or drop chapters between visits.
			val resumeIndex = if (chapters.isEmpty()) {
				null
			} else {
				history?.let { h -> chapters.indexOfFirst { it.id == h.chapterId }.takeIf { it >= 0 } }
			}
			if (chapters.isNotEmpty()) {
				// The single filled button on the screen. It is why the screen was opened.
				Button(
					onClick = {
						if (resumeIndex != null) {
							onRead(chapters, resumeIndex, history?.page ?: 0)
						} else {
							onRead(chapters, 0, 0)
						}
					},
				) {
					AppIcon(AppIcons.PLAY, contentDescription = null)
					Box(Modifier.size(Space.sm))
					Text(if (resumeIndex != null) "Continue" else "Read")
				}
			}
			// Second most used, and it carries state worth seeing without opening
			// anything, so it stays a control rather than moving into the menu.
			OutlinedButton(onClick = { choosingCategories = true }) {
				Text(if (savedIn.isEmpty()) "Add to library" else "In library (${savedIn.size})")
			}
			Spacer(Modifier.weight(1f))
			// Deliberately not gated on having chapters: the states this answers are the
			// ones with none. Hidden only when the failure below is already offering it,
			// which would otherwise put the same button twice on one screen.
			val offeredBelow = error != null && chapters.isEmpty()
			DetailsOverflow(
				// Rare, and destructive-adjacent: it discards the position you were at.
				onStartOver = if (resumeIndex != null) {
					{ onRead(chapters, 0, 0) }
				} else {
					null
				},
				// Deliberate, never urgent.
				downloadLabel = if (chapters.isEmpty()) {
					null
				} else {
					val done = queued.count { it.state == DownloadState.DONE }
					if (done > 0) "Download ($done saved)" else "Download"
				},
				onDownload = { choosingDownload = true },
				// D27 wants this offered whenever the screen is open, not only when the
				// chapter list is empty. A menu item that is always there satisfies that:
				// the action is one click deeper, it has not become conditional.
				onFindAlternative = if (offeredBelow) null else ({ onFindAlternative(manga) }),
			)
		}
		downloadNotice?.let { message ->
			NoticeBar(text = message, onDismiss = { downloadNotice = null })
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
					// A section break, so it is the thing carrying the structure and has
					// to clear 3:1. outlineVariant is 1.99:1 dark and is for decoration.
					HorizontalDivider(color = MaterialTheme.colorScheme.outline)
					// The strip draws its own "Related titles" header.
					RelatedTitlesStrip(
						context = state.featureContext,
						source = source,
						manga = manga,
						onOpen = onOpenRelated,
					)
					HorizontalDivider(color = MaterialTheme.colorScheme.outline)
				}
				if (chapters.isEmpty()) {
					item {
						Column(
							modifier = Modifier.padding(Space.lg),
							verticalArrangement = Arrangement.spacedBy(Space.md),
						) {
							Text(
								text = "This source returned no chapters for this title. " +
									"That usually means the source has dropped it or has not " +
									"published any yet, and another source may still have it.",
								style = MaterialTheme.typography.bodyMedium,
								modifier = Modifier.widthIn(max = ReadableWidth),
							)
							Button(onClick = { onFindAlternative(manga) }) {
								Text("Find it on another source")
							}
						}
					}
				} else {
					item {
						SectionHeader(text = "Chapters", count = chapters.size)
					}
					items(chapters, key = { it.id }) { chapter ->
						ChapterRow(
							chapter = chapter,
							isCurrent = chapter.id == history?.chapterId,
							download = queuedByChapter[chapter.id],
							onClick = { onRead(chapters, chapters.indexOf(chapter), 0) },
						)
						// No divider between rows. AppListRow's 64 dp minimum height and
						// its hover state separate them; a hairline at 1.99:1 as the only
						// thing dividing a 105-row list is structure carried by something
						// that fails WCAG 1.4.11.
					}
				}
			}
		}
	}
}

/**
 * The actions that are not the reason you opened this screen.
 *
 * Each item is drawn only when it applies, and the button itself disappears when none of
 * them do, which happens in exactly one state: the load failed with no chapters, where
 * "Other sources" is promoted to a first-class control in the error box instead.
 */
@Composable
private fun DetailsOverflow(
	onStartOver: (() -> Unit)?,
	downloadLabel: String?,
	onDownload: () -> Unit,
	onFindAlternative: (() -> Unit)?,
) {
	if (onStartOver == null && downloadLabel == null && onFindAlternative == null) return
	var open by remember { mutableStateOf(false) }
	Box {
		IconButton(onClick = { open = true }) {
			AppIcon(AppIcons.MORE, contentDescription = "More actions for this title")
		}
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			if (onStartOver != null) {
				DropdownMenuItem(
					text = { Text("Start over") },
					leadingIcon = { AppIcon(AppIcons.REFRESH, contentDescription = null) },
					onClick = {
						open = false
						onStartOver()
					},
				)
			}
			if (downloadLabel != null) {
				DropdownMenuItem(
					text = { Text(downloadLabel) },
					leadingIcon = { AppIcon(AppIcons.DOWNLOADS, contentDescription = null) },
					onClick = {
						open = false
						onDownload()
					},
				)
			}
			if (onFindAlternative != null) {
				DropdownMenuItem(
					text = { Text("Other sources") },
					leadingIcon = { AppIcon(AppIcons.SOURCES, contentDescription = null) },
					onClick = {
						open = false
						onFindAlternative()
					},
				)
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
			Column {
				// Capped and scrollable. The list is as long as the reader has categories,
				// and an unbounded Column of a dozen at interface scale 2.0 grew the dialog
				// past the bottom of the window, taking Done with it. "New category" sits
				// outside the scrolling region so it does not scroll out of reach.
				Column(
					modifier = Modifier
						.heightIn(max = DIALOG_LIST_MAX_HEIGHT)
						.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(Space.xs),
				) {
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
								.padding(vertical = Space.sm),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(Space.sm),
						) {
							Checkbox(checked = checked, onCheckedChange = null)
							Text(category.title, style = MaterialTheme.typography.bodyLarge)
						}
					}
				}
				TextButton(onClick = { creating = true }) {
					AppIcon(AppIcons.ADD, contentDescription = null)
					Box(Modifier.size(Space.xs))
					Text("New category")
				}
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

/**
 * A dialog body that loops over a caller-supplied list stops growing here.
 *
 * `CurateScreen` already capped its own at 320 dp; the dialogs that did not simply grew
 * past the bottom of the window and took their confirm button with them.
 */
private val DIALOG_LIST_MAX_HEIGHT = 400.dp

/** The cover's width here. At [COVER_ASPECT] that is 200x286. */
private val HEADER_COVER_WIDTH = 200.dp

/** How much of a description is shown before [Header] offers the rest. */
private const val DESCRIPTION_LINES = 6

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(manga: Manga, state: AppState, client: okhttp3.OkHttpClient) {
	var descriptionOpen by remember(manga.id) { mutableStateOf(false) }
	var descriptionClipped by remember(manga.id) { mutableStateOf(false) }
	Row(
		modifier = Modifier.fillMaxWidth().padding(Space.lg),
		horizontalArrangement = Arrangement.spacedBy(Space.lg),
	) {
		RemoteImage(
			url = manga.largeCoverUrl ?: manga.coverUrl,
			client = client,
			cache = state.images,
			contentDescription = manga.title,
			// An aspect ratio rather than a fixed height. 180x260 is 0.692, which is close
			// enough to COVER_ASPECT to look right and far enough from it that this was
			// the one cover in the app very slightly squeezed.
			modifier = Modifier
				.width(HEADER_COVER_WIDTH)
				.aspectRatio(COVER_ASPECT)
				.clip(MaterialTheme.shapes.medium),
		)
		Column(verticalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.weight(1f)) {
			// The block's opening line, in place of the repeated title. It also gains the
			// chapter count, so the size of the thing is readable without scrolling to the
			// section header below.
			val facts = buildList {
				manga.state?.let { add(it.name.lowercase().replace('_', ' ')) }
				if (manga.rating > 0f) add("rating ${"%.1f".format(manga.rating * 10)}")
				manga.contentRating?.let { add(it.name.lowercase()) }
				manga.chapters?.size?.takeIf { it > 0 }?.let {
					add("$it ${if (it == 1) "chapter" else "chapters"}")
				}
			}
			if (facts.isNotEmpty()) {
				Text(
					text = facts.joinToString(META_SEPARATOR),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			manga.authors.firstOrNull()?.let {
				Text(it, style = MaterialTheme.typography.bodyMedium)
			}
			if (manga.tags.isNotEmpty()) {
				// A wrapping, collapsing row rather than one comma-joined line. Tag lists
				// are long and caller-supplied, and two rows of them is as much of the
				// header as they are worth.
				// gutter = false: this sits in a column that is already padded, and
				// ChipRow carries Space.lg of its own, so the tags would otherwise start
				// 16 dp further in than the title above them.
				ChipRow(collapsedRows = 2, gutter = false) {
					for (tag in manga.tags) {
						TagLabel(tag.title)
					}
				}
			}
			manga.description?.takeIf { it.isNotBlank() }?.let { description ->
				Text(
					// Descriptions arrive as HTML from most sources; this is a plain-text
					// reduction, not a renderer. A real HTML view belongs with the novel
					// reader, which is deferred to v1.1 by D2.
					text = description.replace(HTML_TAG, " ").replace(WHITESPACE, " ").trim(),
					style = MaterialTheme.typography.bodyMedium,
					maxLines = if (descriptionOpen) Int.MAX_VALUE else DESCRIPTION_LINES,
					overflow = TextOverflow.Ellipsis,
					// At the full width of a 1270 px window this ran to 95 characters a
					// line, about 20 past what anybody reads comfortably.
					modifier = Modifier.widthIn(max = ReadableWidth),
					onTextLayout = { result ->
						// Only measured while collapsed: expanded text never overflows, and
						// reading it then would retract the control that expanded it.
						if (!descriptionOpen) descriptionClipped = result.hasVisualOverflow
					},
				)
				if (descriptionClipped) {
					TextButton(
						onClick = { descriptionOpen = !descriptionOpen },
						modifier = Modifier.align(Alignment.End),
					) {
						Text(if (descriptionOpen) "Show less" else "Show more")
					}
				}
			}
		}
	}
}

/**
 * One tag, as a pill.
 *
 * Not an `AssistChip`, and not disabled one either. A tag here is a fact about the title
 * and nothing in this build browses by it, so a chip that takes a click and does nothing
 * is a false affordance, and a chip drawn disabled puts its label at 38% `onSurface`,
 * which is 3.04:1 dark and 2.34:1 light and fails 1.4.3 for text you are meant to read.
 * This is the chip's shape and tone at full contrast with no click to offer: 9.48:1 dark,
 * 13.27:1 light.
 */
@Composable
private fun TagLabel(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurface,
		modifier = Modifier
			.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
			.padding(horizontal = Space.md, vertical = Space.sm),
	)
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
			// Capped and scrollable: the branch list is as long as the title has
			// translations, and an unbounded Column of them pushed Download off-screen.
			Column(
				modifier = Modifier
					.heightIn(max = DIALOG_LIST_MAX_HEIGHT)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(Space.hair),
			) {
				for ((label, selection) in options) {
					val newCount = remember(selection, alreadyQueued) {
						selection.select(chapters).count { it.id !in alreadyQueued }
					}
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable { selected = selection }
							.padding(vertical = Space.sm),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.sm),
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
			// Not metadata. A failure is routed to `problem` so it is coloured and
			// iconned as one instead of reading like another status.
			else -> Unit
		}
	}
	AppListRow(
		headline = chapter.title ?: "Chapter ${chapter.number}",
		onClick = onClick,
		meta = meta,
		problem = "download failed".takeIf { download?.state == DownloadState.FAILED },
		// Where you are was `colorScheme.primary` text and nothing else, which is invisible
		// to anyone who cannot separate cyan from white. A selected row is a fill plus this
		// tick, so the state survives without the colour.
		leading = if (isCurrent) {
			{ AppIcon(AppIcons.CHECK, contentDescription = "Where you left off") }
		} else {
			null
		},
		selected = isCurrent,
	)
}

private val HTML_TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
