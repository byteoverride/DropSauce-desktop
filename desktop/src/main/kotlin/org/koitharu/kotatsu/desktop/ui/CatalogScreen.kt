package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * The source catalogue.
 *
 * Only sources the library does not flag as broken are listed, per DECISIONS.md D18:
 * roughly 380 of 1270 are flagged and listing them would mean most of what a user clicks
 * simply fails.
 *
 * The 658 that remain used to be a single column of two-line rows, each one stretched
 * across the window with its right half empty, separated by a hairline at 1.99:1 — a
 * list whose only structural cue fails WCAG 1.4.11. Three things fix it without adding
 * any data: the rows are laid out in as many columns as the window has room for, each
 * one is an [AppListRow] with a 64 dp minimum height and a hover state so the hairline
 * is not carrying structure any more, and the initial letters become sticky headers so
 * a reader can aim instead of scroll.
 */
@Composable
fun CatalogScreen(
	sources: List<MangaParserSource>,
	totalUsable: Int,
	onPick: (MangaParserSource) -> Unit,
) {
	var query by remember { mutableStateOf("") }
	// Keyed on the list as well as the query. The key used to be the query alone, so a
	// change to the catalogue while this screen was alive would not have re-filtered. It
	// is unreachable today because selectRoot clears the stack and rebuilds the screen,
	// and it is a trap for whoever makes the catalogue a non-root destination later.
	val filtered = remember(sources, query) {
		if (query.isBlank()) {
			sources
		} else {
			val q = query.trim().lowercase()
			sources.filter { it.title.lowercase().contains(q) || it.locale.lowercase() == q }
		}
	}
	// One group per initial letter. The registry hands this list over already sorted by
	// title, so grouping preserves that order and the headers are the only thing added.
	val groups = remember(filtered) { filtered.groupBy(::initialOf).toList() }
	val filterField = remember { FocusRequester() }
	val gridState = rememberLazyGridState()
	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Sources",
			subtitle = if (sources.size < totalUsable) {
				// Say so, rather than letting the adult filter look like missing sources.
				// "in the catalogue" rather than "available": the larger number is the
				// usable count, and "available" read as though a setting were withholding
				// the difference. Discover already phrases the same number this way.
				"${filtered.size} of ${sources.size} shown, $totalUsable in the catalogue"
			} else {
				"${filtered.size} of ${sources.size} in the catalogue"
			},
		)
		OutlinedTextField(
			value = query,
			onValueChange = { query = it },
			label = { Text("Filter by name or language code") },
			singleLine = true,
			leadingIcon = { AppIcon(AppIcons.DISCOVER, contentDescription = null) },
			trailingIcon = {
				// Until now the only way to empty the field was select-all-delete.
				if (query.isNotEmpty()) {
					IconButton(onClick = { query = "" }) {
						AppIcon(AppIcons.CLOSE, contentDescription = "Clear the filter")
					}
				}
			},
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = Space.lg, vertical = Space.sm)
				.focusRequester(filterField),
		)
		if (filtered.isEmpty()) {
			// The filter matching nothing used to leave a blank window saying nothing at all.
			// The wording follows Discover's source picker, which filters the same list with
			// the same field and should not phrase the same outcome differently. An empty
			// catalogue is a different outcome and says so: the hidden-source settings have
			// taken everything out, and clearing the filter would not bring any of it back.
			val wanted = query.trim()
			if (wanted.isEmpty()) {
				EmptyBox(
					headline = "No sources to show",
					detail = "Settings is hiding every source in the catalogue.",
					icon = AppIcons.DISCOVER,
				)
			} else {
				EmptyBox(
					headline = "No source matches that",
					detail = "Nothing in the catalogue is called \"$wanted\", and it is not a " +
						"language code either.",
					icon = AppIcons.DISCOVER,
					action = { Button(onClick = { query = "" }) { Text("Clear the filter") } },
				)
			}
		} else {
			LazyVerticalGrid(
				columns = GridCells.Adaptive(minSize = SOURCE_CELL_MIN_WIDTH),
				state = gridState,
				// No horizontal content padding: every cell is an AppListRow and carries the
				// Space.lg screen gutter itself, so adding any here would double it. Cells
				// abut vertically the way list rows do — the 64 dp minimum height and the
				// hover fill are what separate them now, which is the point of the change.
				contentPadding = PaddingValues(bottom = Space.md),
				horizontalArrangement = Arrangement.spacedBy(Space.md),
				modifier = Modifier
					.fillMaxSize()
					.focusGroup()
					.onPreviewKeyEvent { event ->
						// Typing with the grid focused goes to the filter instead of nowhere,
						// which is what a list this long needs. Plain printable keys only:
						// Tab, the arrows, Enter, Space and anything with a modifier still
						// reach the grid and the row that has focus.
						val typed = event.utf16CodePoint.toChar()
						if (
							event.type == KeyEventType.KeyDown &&
							!event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&
							!typed.isISOControl() && typed != ' '
						) {
							query += typed
							filterField.requestFocus()
							true
						} else {
							false
						}
					},
			) {
				groups.forEach { (letter, inGroup) ->
					stickyHeader(key = "letter-$letter") {
						SectionHeader(
							text = letter.toString(),
							count = inGroup.size,
							// A sticky header sits over scrolled content, so it needs a fill of
							// its own or the rows run through it.
							modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
						)
					}
					items(inGroup, key = { it.name }) { source ->
						AppListRow(
							headline = source.title,
							onClick = { onPick(source) },
							// Joined by the row, which ends the double space this screen used
							// to put between the content type and the language code.
							meta = metaOf(source),
						)
					}
				}
			}
		}
	}
}

/**
 * The width below which the catalogue drops a column.
 *
 * 240 dp holds a source name and its two metadata items comfortably, gives three columns
 * in the default window, and resolves to one column at UI scale 2.0 — which degrades to
 * exactly the single-column list that existed before rather than breaking.
 */
private val SOURCE_CELL_MIN_WIDTH = 240.dp

/** The group a source falls in: its initial letter, or `#` for anything that is not one. */
private fun initialOf(source: MangaParserSource): Char {
	val first = source.title.trimStart().firstOrNull()?.uppercaseChar() ?: '#'
	return if (first.isLetter()) first else '#'
}

/** Content type, then the language code if the source declares one. */
private fun metaOf(source: MangaParserSource): List<String> = listOfNotNull(
	source.contentType.name.lowercase(),
	source.locale.takeIf { it.isNotEmpty() },
)
