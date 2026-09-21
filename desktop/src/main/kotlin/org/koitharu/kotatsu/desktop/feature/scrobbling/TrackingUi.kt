package org.koitharu.kotatsu.desktop.feature.scrobbling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The small UI pieces this area needs.
 *
 * Deliberately a private copy rather than a reach into `desktop/ui/Common.kt` or another
 * feature's helpers: neither is part of `FeatureContext`, and depending on a signature
 * another agent owns is how this area breaks without anyone touching it.
 */

private val timestampFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

internal fun formatSyncTime(value: Long?): String =
	if (value == null || value <= 0L) "never" else timestampFormat.format(Date(value))

/** A title plus optional subtitle and trailing controls, at the top of a screen. */
@Composable
internal fun TrackingHeader(
	title: String,
	subtitle: String? = null,
	trailing: @Composable () -> Unit = {},
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.weight(1f)) {
			Text(text = title, style = MaterialTheme.typography.titleLarge)
			if (subtitle != null) {
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		trailing()
	}
}

@Composable
internal fun TrackingEmptyNote(text: String, modifier: Modifier = Modifier) {
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Text(
			text = text,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
			modifier = Modifier.padding(32.dp),
		)
	}
}

/** One line of secondary text, used for every status and error line in this area. */
@Composable
internal fun TrackingNote(text: String, isError: Boolean = false) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodySmall,
		color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

/** The one-line summary of a service's state, for the card subtitle. */
internal fun describeState(state: ServiceState): String = when (state) {
	is ServiceState.Unavailable -> state.reason
	ServiceState.Disconnected -> "Not connected."
	is ServiceState.Connected -> buildString {
		append(state.account?.nickname?.let { "Connected as $it" } ?: "Connected")
		append(". Last sync: ")
		append(formatSyncTime(state.lastSyncAt))
		append('.')
	}

	is ServiceState.Failing -> "Last attempt failed: ${state.message} " +
		"(last good sync: ${formatSyncTime(state.lastSyncAt)})"
}
