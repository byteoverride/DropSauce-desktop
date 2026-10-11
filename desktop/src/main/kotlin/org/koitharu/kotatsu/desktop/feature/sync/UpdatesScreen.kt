package org.koitharu.kotatsu.desktop.feature.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.ui.AppListRow
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LoadingStrip
import org.koitharu.kotatsu.desktop.ui.NoticeBar
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.desktop.ui.ThumbSize
import org.koitharu.kotatsu.desktop.ui.TopBar
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.TrackWithManga

/**
 * Titles with chapters you have not seen yet.
 *
 * Checks are manual. Desktop v1 has no scheduler (DECISIONS.md D11), and the header says
 * that in words rather than leaving a stale list looking like a live one.
 */
@Composable
fun UpdatesScreen(context: FeatureContext, navigator: FeatureNavigator) {
	val scope = rememberCoroutineScope()
	val repository = remember(context) {
		TrackerRepository(context.db, SourceRegistryFetcher(context))
	}
	val withUpdates by remember(repository) { repository.observeWithUpdates() }
		.collectAsState(emptyList())
	val everything by remember(repository) { repository.observeAll() }
		.collectAsState(emptyList())

	var showAll by remember { mutableStateOf(false) }
	var running by remember { mutableStateOf(false) }
	var done by remember { mutableStateOf(0) }
	var total by remember { mutableStateOf(0) }
	var lastChecked: Long? by remember { mutableStateOf(null) }
	var runResult: String? by remember { mutableStateOf(null) }
	// The outcome of a run is either a report or a failure, and the two read nothing alike:
	// one is a NoticeBar, the other an ErrorBox. One string cannot say which it is.
	var runFailed by remember { mutableStateOf(false) }

	// Re-read after every run, and whenever the rows change, so the header does not claim
	// a check time that a later run has already moved on from.
	LaunchedEffect(repository, everything.size, running) {
		if (!running) {
			lastChecked = repository.lastCheckedAt()
		}
	}

	val rows = if (showAll) everything else withUpdates

	// Hoisted because the empty state offers it too: a reader with nothing tracked is
	// exactly the reader who needs this button, and it was only in the header.
	val trackLibrary: () -> Unit = {
		scope.launch {
			val added = repository.trackEverythingKept()
			runFailed = false
			runResult = if (added == 0) {
				"Everything you keep is already being tracked."
			} else {
				"Now tracking $added more title${if (added == 1) "" else "s"}. " +
					"Press Check now to look for new chapters."
			}
		}
		Unit
	}

	// Hoisted for the same reason: a run that stopped needs a way to run again, and the
	// failure strip is where the reader is looking when it does.
	val checkNow: () -> Unit = {
		scope.launch {
			running = true
			runResult = null
			runFailed = false
			done = 0
			total = everything.size
			try {
				val summary = repository.checkAll { d, t ->
					done = d
					total = t
				}
				runResult = summary.describe()
				// Show the whole watch list once a run finishes. The interesting answer is
				// often "nothing changed", and filtering to new chapters renders that as an
				// empty screen that looks like the check never happened.
				showAll = true
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				runFailed = true
				runResult = "The run stopped: " +
					(e.message ?: e::class.simpleName ?: "unknown error")
			} finally {
				running = false
				lastChecked = repository.lastCheckedAt()
			}
		}
		Unit
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Updates",
			subtitle = "Checked ${formatTimestamp(lastChecked)} · checks are manual, " +
				"there is no background updater",
			trailing = {
				TextButton(enabled = !running, onClick = trackLibrary) {
					// Not "favourites": that is this app's whole library, and the old
					// label read as though it meant some subset of it.
					Text("Track my library")
				}
				TextButton(
					enabled = everything.isNotEmpty(),
					onClick = { showAll = !showAll },
				) {
					Text(if (showAll) "Only new" else "Show all")
				}
				TextButton(
					enabled = !running && withUpdates.isNotEmpty(),
					onClick = { scope.launch { repository.clearAllNew() } },
				) {
					Text("Mark all seen")
				}
				Button(
					enabled = !running && everything.isNotEmpty(),
					onClick = checkNow,
				) {
					Text("Check now")
				}
			},
		)

		if (running) {
			LoadingStrip(
				label = "Checking $done of $total",
				progress = if (total <= 0) 0f else done.toFloat() / total,
			)
		}

		val message = runResult
		if (message != null && !running) {
			if (runFailed) {
				ErrorBox(message = message, onRetry = checkNow, compact = true)
			} else {
				NoticeBar(text = message, onDismiss = { runResult = null })
			}
		}

		// This one separates the controls from the list, so it carries structure and has
		// to clear 3:1; outlineVariant is 1.99:1 and is for decoration (1.6).
		HorizontalDivider(color = MaterialTheme.colorScheme.outline)

		if (rows.isEmpty()) {
			if (everything.isEmpty()) {
				EmptyBox(
					headline = "Nothing is being tracked yet",
					detail = "Watch everything in your library, then press Check now to " +
						"look for new chapters.",
					action = {
						Button(enabled = !running, onClick = trackLibrary) {
							Text("Track my library")
						}
					},
				)
			} else {
				EmptyBox(
					headline = "No new chapters",
					detail = "Press Check now to look again.",
				)
			}
		} else {
			LazyColumn(Modifier.fillMaxSize()) {
				items(rows, key = { it.track.mangaId }) { row ->
					UpdateRow(
						row = row,
						context = context,
						onOpen = { source ->
							navigator.openDetails(source, row.manga.toManga(source))
						},
						onMarkSeen = { scope.launch { repository.clearNew(row.track.mangaId) } },
					)
					HorizontalDivider()
				}
			}
		}
	}
}

@Composable
private fun UpdateRow(
	row: TrackWithManga,
	context: FeatureContext,
	onOpen: (MangaParserSource) -> Unit,
	onMarkSeen: () -> Unit,
) {
	val source = remember(row.manga.source) {
		MangaParserSource.entries.firstOrNull { it.name == row.manga.source }
	}
	val client = remember(source) {
		// A title saved on the Android side may name a source this build does not have
		// (D1). A bare client still renders its cover; the row just cannot be opened.
		source?.let { context.clientFor(it) } ?: OkHttpClient()
	}
	// Both can be true at once: a source that left the build may also have failed its
	// last check, and dropping either line would lose the reason the row is stuck.
	val problem = listOfNotNull(
		if (source == null) {
			"\"${row.manga.source}\" is not a source this build has, so it cannot be " +
				"checked or opened."
		} else {
			null
		},
		row.track.lastError,
	).joinToString(" ").ifEmpty { null }
	AppListRow(
		headline = row.manga.title,
		// Null rather than a disabled click: a row that cannot be opened should not
		// react to the pointer at all, and it leaves the tab order with it.
		onClick = source?.let { known -> { onOpen(known) } },
		meta = buildList {
			add(row.manga.source)
			if (row.track.lastChapterDate > 0L) {
				add("latest ${formatTimestamp(row.track.lastChapterDate)}")
			}
			add("checked ${formatTimestamp(row.track.lastCheck)}")
		},
		problem = problem,
		leading = {
			RemoteImage(
				url = row.manga.coverUrl,
				client = client,
				cache = context.images,
				contentDescription = row.manga.title,
				modifier = Modifier.size(ThumbSize.list).clip(MaterialTheme.shapes.small),
			)
		},
		trailing = {
			if (row.track.newChapters > 0) {
				CountBadge(row.track.newChapters)
				TextButton(onClick = onMarkSeen) { Text("Mark seen") }
			}
		},
	)
}

private fun TrackRunSummary.describe(): String = buildString {
	append("Checked $checked title")
	if (checked != 1) append("s")
	append(": ")
	if (newChapters == 0) {
		append("no new chapters")
	} else {
		append("$newChapters new chapter")
		if (newChapters != 1) append("s")
		append(" across $titlesWithUpdates title")
		if (titlesWithUpdates != 1) append("s")
	}
	if (failed > 0) {
		append(", $failed failed")
	}
	append(".")
}
