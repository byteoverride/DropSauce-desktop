package org.koitharu.kotatsu.desktop.feature.sync

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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
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

	// Re-read after every run, and whenever the rows change, so the header does not claim
	// a check time that a later run has already moved on from.
	LaunchedEffect(repository, everything.size, running) {
		if (!running) {
			lastChecked = repository.lastCheckedAt()
		}
	}

	val rows = if (showAll) everything else withUpdates

	Column(Modifier.fillMaxSize()) {
		FeatureHeader(
			title = "Updates",
			subtitle = "Checked ${formatTimestamp(lastChecked)} · checks are manual, " +
				"there is no background updater",
			trailing = {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					TextButton(
						enabled = !running,
						onClick = {
							scope.launch {
								val added = repository.trackAllFavourites()
								runResult = if (added == 0) {
									"Everything in your library is already being tracked."
								} else {
									"Now tracking $added more title${if (added == 1) "" else "s"}. " +
										"Press Check now to look for new chapters."
								}
							}
						},
					) {
						Text("Track favourites")
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
						onClick = {
							scope.launch {
								running = true
								runResult = null
								done = 0
								total = everything.size
								try {
									val summary = repository.checkAll { d, t ->
										done = d
										total = t
									}
									runResult = summary.describe()
								} catch (e: CancellationException) {
									throw e
								} catch (e: Throwable) {
									runResult = "The run stopped: " +
										(e.message ?: e::class.simpleName ?: "unknown error")
								} finally {
									running = false
									lastChecked = repository.lastCheckedAt()
								}
							}
						},
					) {
						Text("Check now")
					}
				}
			},
		)

		if (running) {
			LinearProgressIndicator(
				progress = { if (total <= 0) 0f else done.toFloat() / total },
				modifier = Modifier.fillMaxWidth(),
			)
			Text(
				text = "Checking $done of $total",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
			)
		}

		val message = runResult
		if (message != null && !running) {
			Text(
				text = message,
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
			)
		}

		HorizontalDivider()

		if (rows.isEmpty()) {
			EmptyNote(
				text = when {
					everything.isEmpty() ->
						"Nothing is being tracked yet. Press \"Track favourites\" to watch " +
							"everything in your library, then \"Check now\" to look for new chapters."

					else -> "No new chapters. Press Check now to look again."
				},
			)
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
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = source != null) { source?.let(onOpen) }
			.padding(horizontal = 16.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		CoverThumb(
			url = row.manga.coverUrl,
			client = client,
			cache = context.images,
			title = row.manga.title,
			modifier = Modifier.width(48.dp).height(68.dp).clip(RoundedCornerShape(6.dp)),
		)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = row.manga.title,
					style = MaterialTheme.typography.bodyLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f, fill = false),
				)
				if (row.track.newChapters > 0) {
					CountBadge(row.track.newChapters)
				}
			}
			Text(
				text = buildString {
					append(row.manga.source)
					if (row.track.lastChapterDate > 0L) {
						append("  ·  latest ")
						append(formatTimestamp(row.track.lastChapterDate))
					}
					append("  ·  checked ")
					append(formatTimestamp(row.track.lastCheck))
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			val error = row.track.lastError
			if (error != null) {
				Text(
					text = error,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
			if (source == null) {
				Text(
					text = "\"${row.manga.source}\" is not a source this build has, so it " +
						"cannot be checked or opened.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
				)
			}
		}
		if (row.track.newChapters > 0) {
			TextButton(onClick = onMarkSeen) { Text("Mark seen") }
		}
	}
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
