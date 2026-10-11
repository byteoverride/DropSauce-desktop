package org.koitharu.kotatsu.desktop.feature.scrobbling

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The small UI pieces this area needs that are specific to tracking.
 *
 * This used to carry a private header, empty-state and note helper, on the grounds that
 * reaching into another area's composables is how this one breaks without anyone touching
 * it. That reasoning no longer applies: `desktop/ui/Common.kt` is the app's shared
 * component contract rather than another feature's internals, so the header is now
 * `TopBar`, the empty state `EmptyBox` and every failure line `ErrorBox`.
 *
 * What is left is what is genuinely local: a timestamp format and the wording for a
 * service's state.
 */

private val timestampFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

internal fun formatSyncTime(value: Long?): String =
	if (value == null || value <= 0L) "never" else timestampFormat.format(Date(value))

/**
 * One line of secondary text, used for every ordinary status line in this area.
 *
 * No error variant any more. It had one, and a failure drawn only in `colorScheme.error`
 * is a failure announced by colour alone; those sites are `ErrorBox(compact = true)` now,
 * which carries an icon as well. Removing the parameter is what stops the colour-only
 * version coming back.
 */
@Composable
internal fun TrackingNote(text: String, modifier: Modifier = Modifier) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = modifier,
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
