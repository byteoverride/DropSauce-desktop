package org.koitharu.kotatsu.desktop.feature.migration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.ui.LoadingBox
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.desktop.ui.TopBar

/**
 * Library entries sitting on a source that no longer works, and a way to repair them all.
 *
 * This is the screen that justifies the feature: 380 of the 1270 catalogue sources are
 * flagged broken (DECISIONS.md D18), so a library of any age accumulates entries that
 * simply will not open.
 */
@Composable
fun BrokenSourcesScreen(
	context: FeatureContext,
	onOpenEntry: (LibraryEntry) -> Unit,
	reloadKey: Int = 0,
) {
	val scanner = remember(context) { LibraryScanner(context.db) }
	val runner = remember(context) {
		AutoFixRunner(
			engine = AlternativesEngine(
				SourceRegistrySearcher(context),
				SourceRegistryDetailsLoader(context),
			),
			service = MigrationService(
				MigrationRepository(context.db),
				SourceRegistryDetailsLoader(context),
			),
		)
	}

	var entries: List<LibraryEntry>? by remember(context) { mutableStateOf(null) }
	var migrateProgress by remember(context) { mutableStateOf(true) }
	var running by remember(context) { mutableStateOf(false) }
	var currentIndex by remember(context) { mutableStateOf(0) }
	var scanGeneration by remember(context) { mutableStateOf(0) }
	val outcomes = remember(context) { mutableStateMapOf<Long, AutoFixOutcome>() }

	LaunchedEffect(context, reloadKey, scanGeneration) {
		entries = null
		entries = scanner.scanBroken()
	}

	val rows = entries
	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Broken sources",
			subtitle = when {
				rows == null -> "Checking the library"
				rows.isEmpty() -> "Every library entry is on a working source"
				else -> "${rows.size} ${if (rows.size == 1) "entry" else "entries"} cannot be opened"
			},
			trailing = {
				Row(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text("Migrate progress", style = MaterialTheme.typography.bodySmall)
					Switch(
						checked = migrateProgress,
						enabled = !running,
						onCheckedChange = { migrateProgress = it },
					)
					Button(
						enabled = !running && !rows.isNullOrEmpty(),
						onClick = {
							val targets = rows ?: return@Button
							outcomes.clear()
							running = true
							currentIndex = 0
							// Launched on the feature scope, not the composition's, so a run
							// that is already writing to the database finishes even if the
							// user navigates away. The report on screen is lost if they do;
							// a half-applied library would be worse.
							context.scope.launch {
								try {
									runner.run(targets, migrateProgress).collect { event ->
										when (event) {
											is AutoFixEvent.Started -> currentIndex = event.index
											else -> event.toOutcome()?.let {
												outcomes[event.entry.manga.id] = it
											}
										}
									}
								} catch (e: CancellationException) {
									throw e
								} catch (e: Throwable) {
									// Per-entry failures never reach here; this is the runner
									// itself giving up, which the user still has to be told.
									for (target in targets) {
										outcomes.getOrPut(target.manga.id) {
											AutoFixOutcome(
												entry = target,
												movedTo = null,
												result = null,
												message = "the run stopped: ${e.describeFailure()}",
												isSuccess = false,
											)
										}
									}
								} finally {
									running = false
									scanGeneration++
								}
							}
						},
					) {
						Text(if (running) "Fixing…" else "Fix all")
					}
				}
			},
		)
		if (running) {
			val total = rows?.size ?: 0
			LinearProgressIndicator(Modifier.fillMaxWidth())
			Text(
				text = "Entry ${currentIndex + 1} of $total · ${outcomes.count { it.value.isSuccess }} fixed",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
			)
		}
		HorizontalDivider()
		when {
			rows == null -> LoadingBox(Modifier.weight(1f))
			rows.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
				Text(
					text = "Nothing to fix.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			else -> LazyColumn(Modifier.weight(1f)) {
				items(rows, key = { it.manga.id }) { entry ->
					BrokenEntryRow(
						context = context,
						entry = entry,
						outcome = outcomes[entry.manga.id],
						enabled = !running,
						onClick = { onOpenEntry(entry) },
					)
					HorizontalDivider()
				}
			}
		}
	}
}

@Composable
private fun BrokenEntryRow(
	context: FeatureContext,
	entry: LibraryEntry,
	outcome: AutoFixOutcome?,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 8.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(44.dp).height(62.dp).clip(RoundedCornerShape(4.dp))) {
			// A broken source still has a client and its covers are often still served by
			// a CDN that outlived the site, so this is worth trying. A missing source has
			// no client at all, and the placeholder is the honest answer.
			val source = entry.source
			if (source != null) {
				RemoteImage(
					url = entry.manga.coverUrl,
					client = context.clientFor(source),
					cache = context.images,
					contentDescription = entry.manga.title,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
		Column(Modifier.weight(1f)) {
			Text(
				text = entry.manga.title,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = entry.reason(),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (outcome != null) {
				Text(
					text = outcome.message,
					style = MaterialTheme.typography.bodySmall,
					color = if (outcome.isSuccess) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.error
					},
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		TextButton(enabled = enabled, onClick = onClick) { Text("Choose") }
	}
}
