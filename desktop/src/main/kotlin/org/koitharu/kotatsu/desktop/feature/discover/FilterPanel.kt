package org.koitharu.kotatsu.desktop.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import java.util.Locale

/**
 * The filter panel for one source.
 *
 * Every control here exists because [spec] says the source honours it. Changes edit a
 * draft and are sent only when Apply is pressed: each request is a live call to a
 * third-party site, and re-querying on every chip tap would be both slow and a good way
 * to get rate-limited.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterPanel(
	spec: FilterSpec,
	draft: FilterSelection,
	applied: FilterSelection,
	onDraftChange: (FilterSelection) -> Unit,
	onApply: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier
			.width(FILTER_PANEL_WIDTH)
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 12.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text("Filters", style = MaterialTheme.typography.titleMedium)
			if (!draft.isEmpty) {
				TextButton(onClick = { onDraftChange(FilterSelection(query = draft.query)) }) {
					Text("Reset")
				}
			}
		}

		if (!spec.hasFilters) {
			Text(
				text = "This source offers no filters. Everything it can do is on the toolbar above.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		if (spec.conflictsWithQuery(draft)) {
			// Not a warning we invented: the capability flag says the source has separate
			// search and browse endpoints, so one of the two sets of parameters would be
			// dropped without telling anyone.
			Text(
				text = "This source cannot search and filter at the same time. " +
					"Applying these will clear the search box.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}

		if (FilterControl.SORT in spec) {
			Section("Sort by") {
				FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for (order in spec.sortOrders) {
						val selected = spec.sortOrderFor(draft) == order
						FilterChip(
							selected = selected,
							onClick = {
								onDraftChange(draft.copy(sortOrder = if (selected) null else order))
							},
							label = { Text(order.label()) },
						)
					}
				}
			}
		}

		if (FilterControl.TAGS in spec) {
			TagSection(spec, draft, onDraftChange)
		}

		if (FilterControl.STATE in spec) {
			Section("Status") {
				ChipSet(
					items = spec.states,
					selected = draft.states,
					label = { it.label() },
					onToggle = { onDraftChange(draft.copy(states = draft.states.toggle(it))) },
				)
			}
		}

		if (FilterControl.CONTENT_RATING in spec) {
			Section("Content rating") {
				ChipSet(
					items = spec.contentRatings,
					selected = draft.contentRating,
					label = { it.label() },
					onToggle = { onDraftChange(draft.copy(contentRating = draft.contentRating.toggle(it))) },
				)
			}
		}

		if (FilterControl.CONTENT_TYPE in spec) {
			Section("Type") {
				ChipSet(
					items = spec.contentTypes,
					selected = draft.types,
					label = { it.label() },
					onToggle = { onDraftChange(draft.copy(types = draft.types.toggle(it))) },
				)
			}
		}

		if (FilterControl.DEMOGRAPHIC in spec) {
			Section("Demographic") {
				ChipSet(
					items = spec.demographics,
					selected = draft.demographics,
					label = { it.label() },
					onToggle = { onDraftChange(draft.copy(demographics = draft.demographics.toggle(it))) },
				)
			}
		}

		if (FilterControl.LOCALE in spec) {
			Section("Language") {
				SingleChoiceLocales(
					locales = spec.locales,
					selected = draft.locale,
					onPick = { onDraftChange(draft.copy(locale = it)) },
				)
			}
		}

		if (FilterControl.ORIGINAL_LOCALE in spec) {
			Section("Original language") {
				SingleChoiceLocales(
					locales = spec.locales,
					selected = draft.originalLocale,
					onPick = { onDraftChange(draft.copy(originalLocale = it)) },
				)
			}
		}

		if (FilterControl.YEAR in spec) {
			Section("Year") {
				YearField(
					value = draft.year,
					bounds = spec.years,
					placeholder = "any",
					onChange = { onDraftChange(draft.copy(year = it)) },
				)
			}
		}

		if (FilterControl.YEAR_RANGE in spec) {
			Section("Years") {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					YearField(
						value = draft.yearFrom,
						bounds = spec.years,
						placeholder = "from",
						onChange = { onDraftChange(draft.copy(yearFrom = it)) },
						modifier = Modifier.width(96.dp),
					)
					YearField(
						value = draft.yearTo,
						bounds = spec.years,
						placeholder = "to",
						onChange = { onDraftChange(draft.copy(yearTo = it)) },
						modifier = Modifier.width(96.dp),
					)
				}
			}
		}

		if (FilterControl.AUTHOR in spec) {
			Section("Author") {
				OutlinedTextField(
					value = draft.author,
					onValueChange = { onDraftChange(draft.copy(author = it)) },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}

		HorizontalDivider()
		Button(
			onClick = onApply,
			enabled = spec.sanitize(draft) != applied,
			modifier = Modifier.fillMaxWidth(),
		) {
			Text("Apply")
		}
	}
}

/**
 * Tags, with exclusion folded into the same chip where the source supports it.
 *
 * A chip cycles include, then exclude, then off. Two parallel lists of several hundred
 * tags would not fit in a side panel, and the cycle keeps the state of a tag visible in
 * one place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSection(
	spec: FilterSpec,
	draft: FilterSelection,
	onDraftChange: (FilterSelection) -> Unit,
) {
	var tagQuery by remember(spec) { mutableStateOf("") }
	val canExclude = FilterControl.TAGS_EXCLUDE in spec
	val chosen = draft.tags + draft.tagsExclude
	val matches = remember(spec, tagQuery, chosen) {
		val needle = tagQuery.trim().lowercase()
		val pool = if (needle.isEmpty()) spec.tags else spec.tags.filter { it.title.lowercase().contains(needle) }
		// Chosen tags stay visible even when the search box no longer matches them,
		// otherwise a selection can be active and invisible at the same time.
		(chosen.filter { it in spec.tags } + pool).distinct().take(MAX_TAG_CHIPS)
	}
	Section(
		title = if (canExclude) "Tags (tap again to exclude)" else "Tags",
	) {
		if (spec.tags.size > TAG_SEARCH_THRESHOLD) {
			OutlinedTextField(
				value = tagQuery,
				onValueChange = { tagQuery = it },
				placeholder = { Text("Find a tag") },
				singleLine = true,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		if (!spec.isMultipleTagsSupported) {
			Text(
				text = "This source takes one tag at a time.",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			for (tag in matches) {
				val included = tag in draft.tags
				val excluded = tag in draft.tagsExclude
				FilterChip(
					selected = included || excluded,
					onClick = { onDraftChange(draft.cycleTag(tag, canExclude, spec.isMultipleTagsSupported)) },
					label = {
						Text(
							text = if (excluded) "− ${tag.title}" else tag.title,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					},
				)
			}
		}
		if (spec.tags.size > matches.size) {
			Text(
				text = "${spec.tags.size - matches.size} more, use the box above",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/** include -> exclude -> off, skipping exclude where the source cannot express it. */
internal fun FilterSelection.cycleTag(
	tag: MangaTag,
	canExclude: Boolean,
	multiple: Boolean,
): FilterSelection = when {
	tag in tags && canExclude -> copy(tags = tags - tag, tagsExclude = tagsExclude + tag)
	tag in tags -> copy(tags = tags - tag)
	tag in tagsExclude -> copy(tagsExclude = tagsExclude - tag)
	multiple -> copy(tags = tags + tag)
	// One-tag sources replace rather than accumulate, so the chip the user just tapped
	// is the one that takes effect.
	else -> copy(tags = setOf(tag))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipSet(
	items: List<T>,
	selected: Set<T>,
	label: (T) -> String,
	onToggle: (T) -> Unit,
) {
	FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		for (item in items) {
			FilterChip(
				selected = item in selected,
				onClick = { onToggle(item) },
				label = { Text(label(item)) },
			)
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleChoiceLocales(
	locales: List<Locale>,
	selected: Locale?,
	onPick: (Locale?) -> Unit,
) {
	FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		for (locale in locales) {
			val isSelected = locale == selected
			FilterChip(
				selected = isSelected,
				onClick = { onPick(if (isSelected) null else locale) },
				label = { Text(locale.displayLanguageOrTag()) },
			)
		}
	}
}

/**
 * A year box that accepts only digits and only years the source will take.
 *
 * Out-of-range input is rejected at the keystroke rather than on Apply: the filter is
 * sanitized before every request anyway, and silently dropping a year the user typed
 * would look like the source ignoring it.
 */
@Composable
private fun YearField(
	value: Int,
	bounds: IntRange,
	placeholder: String,
	onChange: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	// The text is held here as well as in the selection because a half-typed year is not
	// a year: "202" has to stay on screen while meaning "no year filter yet".
	var text by remember(value) { mutableStateOf(if (value == YEAR_UNKNOWN) "" else value.toString()) }
	OutlinedTextField(
		value = text,
		onValueChange = { raw ->
			val digits = raw.filter { it.isDigit() }.take(4)
			text = digits
			val parsed = digits.toIntOrNull()
			onChange(if (parsed != null && parsed in bounds) parsed else YEAR_UNKNOWN)
		},
		placeholder = { Text(placeholder) },
		singleLine = true,
		modifier = modifier,
	)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(
			text = title,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		content()
	}
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

val FILTER_PANEL_WIDTH = 290.dp

/** Above this many tags the list is unusable without a search box. */
private const val TAG_SEARCH_THRESHOLD = 24

/** Some sources publish well over a thousand tags; a panel cannot draw them all. */
private const val MAX_TAG_CHIPS = 80
