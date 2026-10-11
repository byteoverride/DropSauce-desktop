package org.koitharu.kotatsu.desktop.feature.migration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.ui.AppIcon
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.AppListRow
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LoadingBox
import org.koitharu.kotatsu.desktop.ui.LoadingStrip
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.ThumbSize
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
			},
		)
		if (running) {
			// One component rather than a bar above a sentence: the bar on its own showed
			// motion without saying which entry was moving.
			LoadingStrip(
				label = "Entry ${currentIndex + 1} of ${rows?.size ?: 0} · " +
					"${outcomes.count { it.value.isSuccess }} fixed",
			)
		}
		// Separates the controls from the list, so it carries structure and has to clear
		// 3:1; outlineVariant is 1.99:1 and is for decoration (1.6).
		HorizontalDivider(color = MaterialTheme.colorScheme.outline)
		when {
			rows == null -> LoadingBox(Modifier.weight(1f), label = "Checking every library entry")
			rows.isEmpty() -> EmptyBox(
				headline = "Nothing to fix",
				detail = "Every entry in your library is on a source that still works.",
				// The spec asks for check_circle, which is not in the vendored set. CHECK
				// carries the same "all clear" without inventing a name AppIconsTest
				// would fail the build over.
				icon = AppIcons.CHECK,
				modifier = Modifier.weight(1f),
			)

			else -> LazyColumn(Modifier.weight(1f)) {
				items(rows, key = { it.manga.id }) { entry ->
					BrokenEntry(
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

/**
 * One broken entry, with whatever the last Fix all run made of it.
 *
 * The outcome sits under the row rather than inside it because the two results read
 * nothing alike: a repair is a result, a failure is a failure, and the failure gets the
 * same shape here as everywhere else in the app.
 */
@Composable
private fun BrokenEntry(
	context: FeatureContext,
	entry: LibraryEntry,
	outcome: AutoFixOutcome?,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	Column(Modifier.fillMaxWidth()) {
		AppListRow(
			headline = entry.manga.title,
			onClick = if (enabled) onClick else null,
			supporting = entry.reason(),
			leading = {
				Box(
					Modifier
						.size(ThumbSize.list)
						.clip(MaterialTheme.shapes.small)
						.background(MaterialTheme.colorScheme.surfaceVariant),
				) {
					// A broken source still has a client and its covers are often still
					// served by a CDN that outlived the site, so this is worth trying. A
					// missing source has no client at all, and the placeholder is the
					// honest answer.
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
			},
			trailing = {
				TextButton(enabled = enabled, onClick = onClick) { Text("Choose") }
			},
		)
		val result = outcome
		if (result == null) {
			return@Column
		}
		if (result.isSuccess) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = Space.lg, vertical = Space.xs),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.sm),
			) {
				// The tick is what keeps "this one worked" from being carried by the
				// primary colour alone (6.4).
				AppIcon(
					name = AppIcons.CHECK,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
				)
				Text(
					text = result.message,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.primary,
				)
			}
		} else {
			ErrorBox(message = result.message, compact = true)
		}
	}
}
