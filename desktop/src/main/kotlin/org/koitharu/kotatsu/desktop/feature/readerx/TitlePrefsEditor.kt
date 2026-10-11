package org.koitharu.kotatsu.desktop.feature.readerx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.koitharu.kotatsu.desktop.ui.ChipRow
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.shared.settings.ReadingMode

/**
 * Editor for one title's overrides.
 *
 * Stateless: it renders [prefs] and reports every change, so the caller decides when to
 * write. That matters here because a write touches two tables and has to create the
 * parent row, and doing that on every keystroke of a title override would be wasteful
 * and would race with itself.
 *
 * Each control has an explicit "follow the app default" position, shown as a chip or as
 * an empty field, and choosing it clears the override rather than storing today's default
 * value. Without that the user has no way to undo an override once set.
 */
@Composable
fun TitlePrefsEditor(
	prefs: TitlePrefs,
	onChange: (TitlePrefs) -> Unit,
	modifier: Modifier = Modifier,
	/** Branches or scanlators this title actually offers, if they have been fetched. */
	branches: List<String> = emptyList(),
	onClear: (() -> Unit)? = null,
) {
	// No horizontal padding here: the chip row carries the gutter itself, and every other
	// child adds it, so a long branch list and a text field start on the same edge.
	Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
		Row(
			modifier = Modifier.padding(horizontal = Space.lg),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "This title only",
				style = MaterialTheme.typography.titleSmall,
				modifier = Modifier.weight(1f),
			)
			if (onClear != null) {
				TextButton(onClick = onClear, enabled = !prefs.isEmpty) { Text("Clear all") }
			}
		}

		if (branches.isNotEmpty()) {
			// Was a plain Row with no overflow handling at all, and the list is whatever
			// the caller fetched: a title on a big aggregator can carry a dozen scanlator
			// branches with long names, and the ones past the window edge could not be
			// reached by scrolling, dragging or any other means.
			ChipRow(label = "Branch or scanlator", collapsedRows = 2) {
				FilterChip(
					selected = prefs.branch == null,
					onClick = { onChange(prefs.copy(branch = null)) },
					label = { Text("No preference") },
				)
				for (branch in branches) {
					FilterChip(
						selected = prefs.branch == branch,
						onClick = { onChange(prefs.copy(branch = branch)) },
						label = { Text(branch) },
					)
				}
			}
		} else {
			OutlinedTextField(
				value = prefs.branch.orEmpty(),
				onValueChange = { onChange(prefs.copy(branch = it.ifBlank { null })) },
				label = { Text("Branch or scanlator") },
				placeholder = { Text("No preference") },
				singleLine = true,
				modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
			)
		}

		OutlinedTextField(
			value = prefs.titleOverride.orEmpty(),
			// Blank clears the override. A title override of "" would hide the title
			// everywhere it is shown, which nobody wants and nothing else would explain.
			onValueChange = { onChange(prefs.copy(titleOverride = it.ifBlank { null })) },
			label = { Text("Show this title as") },
			placeholder = { Text("The name the source gives it") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
		)

		OutlinedTextField(
			value = prefs.coverOverride.orEmpty(),
			onValueChange = { onChange(prefs.copy(coverOverride = it.ifBlank { null })) },
			label = { Text("Cover image address") },
			placeholder = { Text("The cover the source gives it") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
		)

		Row(
			modifier = Modifier.padding(horizontal = Space.lg),
			horizontalArrangement = Arrangement.spacedBy(Space.lg),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text("Incognito", style = MaterialTheme.typography.bodyMedium)
				Text(
					text = "Do not record history or statistics for this title.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Switch(
				checked = prefs.incognito,
				onCheckedChange = { onChange(prefs.copy(incognito = it)) },
			)
		}
	}
}

/** Human wording for a mode. The enum names are storage keys and are not shown as-is. */
internal fun ReadingMode.label(): String = when (this) {
	ReadingMode.PagedLtr -> "Paged, left to right"
	ReadingMode.PagedRtl -> "Paged, right to left"
	ReadingMode.Webtoon -> "Webtoon"
}

/** True when this mode reads right to left, which mirrors pairing and tap zones. */
fun ReadingMode.isRtl(): Boolean = this == ReadingMode.PagedRtl
