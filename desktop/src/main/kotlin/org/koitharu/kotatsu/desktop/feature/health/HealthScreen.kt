package org.koitharu.kotatsu.desktop.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.ui.AppIcon
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.AppListRow
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LoadingStrip
import org.koitharu.kotatsu.desktop.ui.NoticeBar
import org.koitharu.kotatsu.desktop.ui.NoticeTone
import org.koitharu.kotatsu.desktop.ui.SectionHeader
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.TopBar
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Holds a running sweep.
 *
 * Above the screen and on the feature's own scope, so leaving the tool does not abandon
 * a check that is several hundred requests in. The same reason the global search is held
 * this way.
 */
@Stable
class HealthController(
	private val scope: CoroutineScope,
	private val engine: HealthSweep = HealthSweep(),
) {

	var progress: SweepProgress? by mutableStateOf(null)
		private set

	var isRunning: Boolean by mutableStateOf(false)
		private set

	/** Set when a finished sweep was not believed. See [trustOf]. */
	var rejected: String? by mutableStateOf(null)
		private set

	private var job: Job? = null

	fun start(targets: List<HealthTarget>, store: HealthStore) {
		job?.cancel()
		rejected = null
		progress = null
		isRunning = targets.isNotEmpty()
		job = scope.launch {
			try {
				engine.run(targets).collect { progress = it }
			} finally {
				// Also on cancellation, and that is the point. A full check is several
				// hundred requests and takes minutes; throwing away everything measured
				// because the reader stopped it at source 600 would make stopping cost
				// more than waiting. NonCancellable because this runs during
				// cancellation, where a plain suspend call would be cancelled too.
				withContext(NonCancellable) {
					val reports = progress?.reports.orEmpty()
					// The decision about whether to believe any of it can only be made
					// on the shape of the whole sample, so it happens here rather than
					// per source as they arrive.
					val trust = trustOf(reports)
					store.record(reports, trust)
					rejected = if (trust.isTrusted) null else trust.note
				}
				isRunning = false
			}
		}
	}

	/** Stops, keeping and recording whatever has already answered. */
	fun stop() {
		job?.cancel()
		job = null
	}
}

/**
 * Which sources still answer, and what to do about the ones that do not.
 *
 * The catalogue ships 890 usable sources, which counts parsers that *exist*. This counts
 * the ones that answer, which on a measured sweep of the first sixty was sixteen.
 */
@Composable
fun HealthScreen(context: FeatureContext, navigator: FeatureNavigator) {
	val controller = remember(context) { HealthController(context.scope) }
	val health by context.health.data.collectAsState()
	val settings by context.settings.data.collectAsState()
	val uiScope = rememberCoroutineScope()
	val candidates = remember(health) { context.visibleSourcesForCheck() }
	val dead = remember(health) {
		MangaParserSource.entries
			.filter { context.health.isDead(it.name) }
			.sortedBy { it.title.lowercase() }
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Source health",
			subtitle = buildString {
				append(health.sources.size)
				append(" of ")
				append(candidates.size)
				append(" checked")
				if (dead.isNotEmpty()) {
					append("  ·  ")
					append(dead.size)
					append(if (dead.size == 1) " dead" else " dead")
				}
			},
			onBack = navigator::back,
			backLabel = "Back to settings",
			trailing = {
				if (controller.isRunning) {
					TextButton(onClick = controller::stop) { Text("Stop") }
				} else {
					Button(
						onClick = {
							controller.start(
								candidates.map { context.sources.healthTarget(it) },
								context.health,
							)
						},
					) {
						AppIcon(AppIcons.REFRESH, contentDescription = null)
						Text("Check now", modifier = Modifier.padding(start = Space.sm))
					}
				}
			},
		)

		val running = controller.progress
		if (controller.isRunning && running != null) {
			LoadingStrip(
				label = "${running.done} of ${running.total}  ·  ${running.usable} answering",
				progress = running.fraction(),
				onCancel = controller::stop,
			)
		}

		controller.rejected?.let { note ->
			ErrorBox(
				message = note,
				headline = "That check was not believed",
				compact = true,
			)
		}

		if (dead.isNotEmpty()) {
			Row(
				modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.sm),
			) {
				Column(Modifier.weight(1f)) {
					Text("Hide dead sources", style = MaterialTheme.typography.bodyLarge)
					Text(
						text = "Keeps the ${dead.size} below out of the catalogue, the source " +
							"picker and global search. Nothing in your library is touched.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				Switch(
					checked = settings.hideDeadSources,
					onCheckedChange = { on ->
						uiScope.launch { context.settings.update { it.copy(hideDeadSources = on) } }
					},
				)
			}
		}

		when {
			health.sources.isEmpty() && !controller.isRunning -> EmptyBox(
				headline = "No sources have been checked yet",
				detail = "Checking asks each of the ${candidates.size} sources in your catalogue " +
					"for its front page, one request each, four at a time. It takes a few " +
					"minutes and you can stop it at any point.",
				icon = AppIcons.REFRESH,
			)

			// Nothing is recorded until a run ends, so during the first check there is a
			// progress strip above and nothing to list yet. Drawing "Not answering 0"
			// over an empty space would read as a result rather than as a blank.
			dead.isEmpty() && controller.isRunning -> EmptyBox(
				headline = "Checking",
				detail = "Sources that do not answer will be listed here when the check " +
					"finishes. Stopping early keeps whatever has been measured so far.",
				icon = AppIcons.REFRESH,
			)

			dead.isEmpty() && !controller.isRunning -> EmptyBox(
				headline = "Every source that was checked answered",
				detail = "Nothing to hide. Run the check again whenever the catalogue starts " +
					"looking thin.",
				icon = AppIcons.CHECK,
			)

			else -> Column(Modifier.fillMaxSize()) {
				SectionHeader("Not answering", count = dead.size)
				LazyColumn(Modifier.fillMaxSize()) {
					items(dead, key = { it.name }) { source ->
						val state = context.health.stateOf(source.name)
						AppListRow(
							// Nothing to click, but every word is meant to be read: this
							// is the list explaining why each source was dropped.
							enabled = true,
							headline = source.title,
							supporting = state?.reason.orEmpty().ifEmpty { "did not answer" },
							meta = listOfNotNull(
								state?.verdict?.name?.lowercase(),
								state?.strikes?.takeIf { it > 1 }?.let { "$it checks in a row" },
							),
							trailing = {
								// A verdict the reader disagrees with has to be
								// overrulable, or a wrong call is permanent.
								OutlinedButton(
									onClick = { uiScope.launch { context.health.forget(source.name) } },
								) {
									Text("Keep")
								}
							},
						)
					}
				}
			}
		}

		if (health.sources.isNotEmpty() && !controller.isRunning) {
			NoticeBar(
				text = "A source is only hidden after it fails ${HealthStore.STRIKES_TO_HIDE} " +
					"checks in a row, or straight away if its address no longer resolves at all.",
				icon = AppIcons.WARNING,
				tone = NoticeTone.Info,
				action = {
					TextButton(onClick = { uiScope.launch { context.health.clear() } }) {
						Text("Forget all verdicts")
					}
				},
			)
		}
	}
}

/**
 * What to check.
 *
 * The visible catalogue *plus* whatever is currently hidden as dead, because otherwise
 * hiding a source would also stop it ever being rechecked, and a site that comes back
 * would stay buried forever.
 */
private fun FeatureContext.visibleSourcesForCheck(): List<MangaParserSource> {
	val visible = visibleSources()
	val hidden = MangaParserSource.entries.filter { health.isDead(it.name) }
	return (visible + hidden).distinct()
}
