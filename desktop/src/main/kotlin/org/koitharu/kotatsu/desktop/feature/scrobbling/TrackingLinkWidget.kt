package org.koitharu.kotatsu.desktop.feature.scrobbling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = service.label,
					style = MaterialTheme.typography.titleSmall,
					modifier = Modifier.weight(1f),
				)
				if (busy) {
					CircularProgressIndicator(modifier = Modifier.size(18.dp))
				}
			}

			when {
				state is ServiceState.Unavailable -> TrackingNote(state.reason)

				state is ServiceState.Disconnected -> TrackingNote(
					"Not connected. Connect ${service.label} on the Tracking screen first.",
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

			error?.let { TrackingNote(it, isError = true) }
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
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		TrackingNote(
			"Linked to entry ${link.targetId}. ${link.chapter} chapters read. " +
				"Updated ${formatSyncTime(link.updatedAt)}.",
		)

		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
		)
		Slider(
			value = rating,
			onValueChange = onRatingChange,
			onValueChangeFinished = { onRatingCommit(rating) },
			steps = 9,
			enabled = !busy,
		)

		TextButton(onClick = onUnlink, enabled = !busy) {
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
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		OutlinedTextField(
			value = query,
			onValueChange = onQueryChange,
			label = { Text("Title to look for") },
			singleLine = true,
			enabled = !busy,
			modifier = Modifier.fillMaxWidth(),
		)
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
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
			results.isEmpty() -> TrackingNote("No matches. Try a different spelling, or the other type.")
			else -> Column(
				modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(4.dp),
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

@Composable
private fun MatchRow(match: TrackerMatch, enabled: Boolean, onPick: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
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
