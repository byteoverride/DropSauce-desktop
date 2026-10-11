package org.koitharu.kotatsu.desktop.feature.scrobbling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.ChipRow
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LoadingStrip
import org.koitharu.kotatsu.desktop.ui.Space
import kotlin.math.roundToInt

/**
 * The per-title tracking panel for one service.
 *
 * Every input is an explicit parameter and every action is a lambda, so it depends on no
 * repository, no `FeatureContext` and no navigation. The details screen belongs to
 * another area; this is what that area is handed to place wherever it likes, and it can
 * be driven from a preview or a test with plain values.
 *
 * The state machine is small and the widget shows exactly one leg of it: unavailable,
 * signed out, searchable, or linked.
 */
@Composable
fun TrackingLinkWidget(
	service: TrackingService,
	state: ServiceState,
	link: TrackedTitle?,
	/** The local title, used as the first search term so the common case is one click. */
	suggestedQuery: String,
	onSearch: suspend (query: String, type: TrackedMediaType) -> List<TrackerMatch>,
	onLink: suspend (TrackerMatch) -> Unit,
	onUnlink: suspend () -> Unit,
	onSetStatus: suspend (TrackingStatus) -> Unit,
	onSetRating: suspend (Float) -> Unit,
	modifier: Modifier = Modifier,
) {
	val scope = rememberCoroutineScope()
	var query by remember(suggestedQuery) { mutableStateOf(suggestedQuery) }
	var type by remember { mutableStateOf(TrackedMediaType.Manga) }
	var results: List<TrackerMatch>? by remember { mutableStateOf(null) }
	var busy by remember { mutableStateOf(false) }
	var error: String? by remember { mutableStateOf(null) }

	// Held locally while the slider is dragged so it moves smoothly, and pushed on
	// release. Sending a request per frame would rate-limit the account in seconds.
	var pendingRating: Float? by remember(link?.targetId) { mutableStateOf(null) }

	fun run(block: suspend () -> Unit) {
		scope.launch {
			busy = true
			error = null
			try {
				block()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				error = describe(e)
			} finally {
				busy = false
			}
		}
	}

	Card(modifier = modifier.fillMaxWidth()) {
		// Vertical padding only. The chip rows, the loading strip and the error line each
		// carry Space.lg of their own, so the gutter lives on the leaves and every edge
		// in the card lines up on 16.
		Column(
			modifier = Modifier.padding(vertical = Space.md),
			verticalArrangement = Arrangement.spacedBy(Space.sm),
		) {
			Text(
				text = service.label,
				style = MaterialTheme.typography.titleSmall,
				modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
			)
			if (busy) {
				// Was an 18 dp spinner beside the service name, which showed motion and
				// said nothing about what was moving. Searching a tracker and writing a
				// rating to it both land here and take seconds.
				LoadingStrip(label = "Talking to ${service.label}")
			}

			when {
				state is ServiceState.Unavailable -> TrackingNote(
					text = state.reason,
					modifier = Modifier.padding(horizontal = Space.lg),
				)

				state is ServiceState.Disconnected -> TrackingNote(
					text = "Not connected. Connect ${service.label} on the Tracking screen first.",
					modifier = Modifier.padding(horizontal = Space.lg),
				)

				link != null -> LinkedPanel(
					link = link,
					pendingRating = pendingRating,
					busy = busy,
					onStatus = { status -> run { onSetStatus(status) } },
					onRatingChange = { pendingRating = it },
					onRatingCommit = { value ->
						pendingRating = null
						run { onSetRating(value) }
					},
					onUnlink = { run { onUnlink() } },
				)

				else -> SearchPanel(
					query = query,
					onQueryChange = { query = it },
					type = type,
					onTypeChange = {
						type = it
						results = null
					},
					results = results,
					busy = busy,
					onSearch = {
						run {
							results = onSearch(query.trim(), type)
						}
					},
					onPick = { match ->
						run {
							onLink(match)
							results = null
						}
					},
				)
			}

			// Red secondary text before, which is the colour-only cue 6.4 lists. The
			// compact error carries the icon and the headline instead.
			error?.let { ErrorBox(message = it, compact = true) }
		}
	}
}

@Composable
private fun LinkedPanel(
	link: TrackedTitle,
	pendingRating: Float?,
	busy: Boolean,
	onStatus: (TrackingStatus) -> Unit,
	onRatingChange: (Float) -> Unit,
	onRatingCommit: (Float) -> Unit,
	onUnlink: () -> Unit,
) {
	val rating = pendingRating ?: link.rating
	Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
		TrackingNote(
			text = "Linked to entry ${link.targetId}. ${link.chapter} chapters read. " +
				"Updated ${formatSyncTime(link.updatedAt)}.",
			modifier = Modifier.padding(horizontal = Space.lg),
		)

		// A wrapping row rather than a fixed one: how many statuses appear depends on the
		// service, so the width of this row is not something this file knows.
		ChipRow {
			for (status in TrackingStatus.entries) {
				// A status the service has no word for is not offered, rather than
				// offered and silently dropped by the request builder.
				if (statusToRaw(link.service, status) == null) {
					continue
				}
				FilterChip(
					selected = link.status == status,
					onClick = { onStatus(status) },
					enabled = !busy,
					label = { Text(status.label) },
				)
			}
		}

		Text(
			text = "Rating: ${(rating * 10f).roundToInt()} / 10",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = Space.lg),
		)
		Slider(
			value = rating,
			onValueChange = onRatingChange,
			onValueChangeFinished = { onRatingCommit(rating) },
			steps = 9,
			enabled = !busy,
			modifier = Modifier.padding(horizontal = Space.lg),
		)

		TextButton(
			onClick = onUnlink,
			enabled = !busy,
			modifier = Modifier.padding(horizontal = Space.lg),
		) {
			Text("Unlink")
		}
	}
}

@Composable
private fun SearchPanel(
	query: String,
	onQueryChange: (String) -> Unit,
	type: TrackedMediaType,
	onTypeChange: (TrackedMediaType) -> Unit,
	results: List<TrackerMatch>?,
	busy: Boolean,
	onSearch: () -> Unit,
	onPick: (TrackerMatch) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
		OutlinedTextField(
			value = query,
			onValueChange = onQueryChange,
			label = { Text("Title to look for") },
			singleLine = true,
			enabled = !busy,
			modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
		)
		// The Search button shares the row with the type chips, so it wraps with them
		// rather than being the thing that pushes them off the edge of a narrow card.
		ChipRow {
			for (option in TrackedMediaType.entries) {
				FilterChip(
					selected = type == option,
					onClick = { onTypeChange(option) },
					enabled = !busy,
					label = { Text(option.name) },
				)
			}
			Button(onClick = onSearch, enabled = !busy && query.isNotBlank()) {
				Text("Search")
			}
		}

		when {
			results == null -> Unit
			// Bounded, because EmptyBox centres itself in whatever height it is given and
			// the card around it has none of its own.
			results.isEmpty() -> EmptyBox(
				headline = "No matches",
				detail = "Try a different spelling, or the other type.",
				icon = AppIcons.DISCOVER,
				modifier = Modifier.heightIn(max = EMPTY_RESULTS_HEIGHT),
			)

			else -> Column(
				modifier = Modifier
					.heightIn(max = RESULTS_HEIGHT)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(Space.xs),
			) {
				// Exact matches first, so the usual case is the top row and nobody has to
				// read a list of near-identical titles.
				for (match in results.sortedByDescending { it.isExactMatch }) {
					MatchRow(match = match, enabled = !busy, onPick = { onPick(match) })
				}
			}
		}
	}
}

/** How much of the card the result list may take before it scrolls. */
private val RESULTS_HEIGHT = 260.dp

/**
 * The bound on the in-card empty state.
 *
 * EmptyBox centres itself in the height it is given and the card has none of its own, so
 * it needs one. 200 rather than 160: the box spends 64 on its own vertical padding before
 * the icon, headline and two lines of detail, which 160 clips. In dp, so the UI-scale
 * multiplier moves the bound and the text inside it together.
 */
private val EMPTY_RESULTS_HEIGHT = 200.dp

@Composable
private fun MatchRow(match: TrackerMatch, enabled: Boolean, onPick: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
	) {
		Column(Modifier.weight(1f)) {
			Text(
				text = if (match.isExactMatch) "${match.title}  (exact match)" else match.title,
				style = MaterialTheme.typography.bodyMedium,
			)
			match.altTitle?.let {
				Text(
					text = it,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		OutlinedButton(onClick = onPick, enabled = enabled) {
			Text("Link")
		}
	}
}
