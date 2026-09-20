package org.koitharu.kotatsu.desktop.feature.discover

import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.YEAR_MAX
import org.koitharu.kotatsu.parsers.model.YEAR_MIN
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import java.util.Locale

/**
 * A control the filter panel can show.
 *
 * The panel renders exactly the controls in [FilterSpec.controls] and nothing else. A
 * dead control that the source silently ignores is worse than no control, because the
 * user cannot tell the difference between "this source has no shounen" and "this source
 * threw my choice away".
 */
enum class FilterControl {
	QUERY,
	AUTHOR,
	TAGS,
	TAGS_EXCLUDE,
	STATE,
	CONTENT_RATING,
	CONTENT_TYPE,
	DEMOGRAPHIC,
	LOCALE,
	ORIGINAL_LOCALE,
	YEAR,
	YEAR_RANGE,
	SORT,
}

/**
 * What one source actually lets the user filter by.
 *
 * Built from the two things the parser API exposes, which answer different questions and
 * are both needed: [MangaListFilterCapabilities] says which *kinds* of filtering the
 * source implements, and [MangaListFilterOptions] says which *values* exist for the ones
 * that are value-based. A source can report tag support and then hand back an empty tag
 * set, so a control is offered only when both agree.
 */
class FilterSpec(
	val controls: Set<FilterControl>,
	val tags: List<MangaTag>,
	val states: List<MangaState>,
	val contentRatings: List<ContentRating>,
	val contentTypes: List<ContentType>,
	val demographics: List<Demographic>,
	val locales: List<Locale>,
	val sortOrders: List<SortOrder>,
	/** False when the source takes one tag at a time, which several do. */
	val isMultipleTagsSupported: Boolean,
	/** False when a text query and any other option cannot be sent together. */
	val isSearchWithFiltersSupported: Boolean,
	val years: IntRange,
) {

	operator fun contains(control: FilterControl): Boolean = control in controls

	/**
	 * Whether there is anything to narrow a list by.
	 *
	 * A text box and a sort order are not filters, so a source with only those still
	 * gets the honest "no filters" message next to them.
	 */
	val hasFilters: Boolean
		get() = controls.any { it != FilterControl.QUERY && it != FilterControl.SORT }

	/** The order to use when nothing is chosen, or when the choice is not on offer. */
	fun defaultSort(isSearch: Boolean): SortOrder {
		val wanted = if (isSearch) SortOrder.RELEVANCE else SortOrder.POPULARITY
		return when {
			wanted in sortOrders -> wanted
			SortOrder.UPDATED in sortOrders -> SortOrder.UPDATED
			else -> sortOrders.firstOrNull() ?: SortOrder.ALPHABETICAL
		}
	}

	companion object {

		fun of(
			capabilities: MangaListFilterCapabilities,
			options: MangaListFilterOptions,
			availableSortOrders: Set<SortOrder>,
		): FilterSpec {
			val tags = options.availableTags.sortedBy { it.title.lowercase() }
			val states = options.availableStates.sortedBy { it.ordinal }
			val ratings = options.availableContentRating.sortedBy { it.ordinal }
			val types = options.availableContentTypes.sortedBy { it.ordinal }
			val demographics = options.availableDemographics.sortedBy { it.ordinal }
			val locales = options.availableLocales.sortedBy { it.displayLanguageOrTag() }
			val sortOrders = availableSortOrders.sortedBy { it.ordinal }
			val controls = buildSet {
				if (capabilities.isSearchSupported) add(FilterControl.QUERY)
				if (capabilities.isAuthorSearchSupported) add(FilterControl.AUTHOR)
				if (tags.isNotEmpty()) {
					add(FilterControl.TAGS)
					// Exclusion without inclusion is not a shape any source reports, and
					// a panel offering only "not this" would read as a bug.
					if (capabilities.isTagsExclusionSupported) add(FilterControl.TAGS_EXCLUDE)
				}
				if (states.isNotEmpty()) add(FilterControl.STATE)
				if (ratings.isNotEmpty()) add(FilterControl.CONTENT_RATING)
				if (types.isNotEmpty()) add(FilterControl.CONTENT_TYPE)
				if (demographics.isNotEmpty()) add(FilterControl.DEMOGRAPHIC)
				if (locales.isNotEmpty()) {
					add(FilterControl.LOCALE)
					if (capabilities.isOriginalLocaleSupported) add(FilterControl.ORIGINAL_LOCALE)
				}
				if (capabilities.isYearSupported) add(FilterControl.YEAR)
				if (capabilities.isYearRangeSupported) add(FilterControl.YEAR_RANGE)
				// One possible order is not a choice.
				if (sortOrders.size > 1) add(FilterControl.SORT)
			}
			return FilterSpec(
				controls = controls,
				tags = tags,
				states = states,
				contentRatings = ratings,
				contentTypes = types,
				demographics = demographics,
				locales = locales,
				sortOrders = sortOrders,
				isMultipleTagsSupported = capabilities.isMultipleTagsSupported,
				isSearchWithFiltersSupported = capabilities.isSearchWithFiltersSupported,
				years = YEAR_MIN..YEAR_MAX,
			)
		}
	}
}

/** What the user has chosen. Free of any judgement about what the source accepts. */
data class FilterSelection(
	val query: String = "",
	val author: String = "",
	val tags: Set<MangaTag> = emptySet(),
	val tagsExclude: Set<MangaTag> = emptySet(),
	val states: Set<MangaState> = emptySet(),
	val contentRating: Set<ContentRating> = emptySet(),
	val types: Set<ContentType> = emptySet(),
	val demographics: Set<Demographic> = emptySet(),
	val locale: Locale? = null,
	val originalLocale: Locale? = null,
	val year: Int = YEAR_UNKNOWN,
	val yearFrom: Int = YEAR_UNKNOWN,
	val yearTo: Int = YEAR_UNKNOWN,
	val sortOrder: SortOrder? = null,
) {

	/** Everything except the text query, which is what the mutual-exclusion rule is about. */
	val hasNonSearchOptions: Boolean
		get() = author.isNotBlank() ||
			tags.isNotEmpty() ||
			tagsExclude.isNotEmpty() ||
			states.isNotEmpty() ||
			contentRating.isNotEmpty() ||
			types.isNotEmpty() ||
			demographics.isNotEmpty() ||
			locale != null ||
			originalLocale != null ||
			year != YEAR_UNKNOWN ||
			yearFrom != YEAR_UNKNOWN ||
			yearTo != YEAR_UNKNOWN

	val isEmpty: Boolean
		get() = query.isBlank() && !hasNonSearchOptions
}

/**
 * Drops everything [this] source will not honour.
 *
 * Runs before every request rather than only when a control changes, because a selection
 * can outlive the source it was made against: switching source keeps the query the user
 * typed, and a tag from the old source would otherwise be sent to the new one.
 */
fun FilterSpec.sanitize(selection: FilterSelection): FilterSelection {
	val tagPool = tags.toSet()
	var result = FilterSelection(
		query = if (FilterControl.QUERY in this) selection.query.trim() else "",
		author = if (FilterControl.AUTHOR in this) selection.author.trim() else "",
		tags = if (FilterControl.TAGS in this) {
			val kept = selection.tags.filterTo(LinkedHashSet()) { it in tagPool }
			// A source that takes one tag would otherwise get several and pick for us.
			if (isMultipleTagsSupported) kept else kept.take(1).toSet()
		} else {
			emptySet()
		},
		tagsExclude = if (FilterControl.TAGS_EXCLUDE in this) {
			selection.tagsExclude.filterTo(LinkedHashSet()) { it in tagPool }
		} else {
			emptySet()
		},
		states = if (FilterControl.STATE in this) selection.states.intersect(states.toSet()) else emptySet(),
		contentRating = if (FilterControl.CONTENT_RATING in this) {
			selection.contentRating.intersect(contentRatings.toSet())
		} else {
			emptySet()
		},
		types = if (FilterControl.CONTENT_TYPE in this) {
			selection.types.intersect(contentTypes.toSet())
		} else {
			emptySet()
		},
		demographics = if (FilterControl.DEMOGRAPHIC in this) {
			selection.demographics.intersect(demographics.toSet())
		} else {
			emptySet()
		},
		locale = selection.locale?.takeIf { FilterControl.LOCALE in this && it in locales },
		originalLocale = selection.originalLocale
			?.takeIf { FilterControl.ORIGINAL_LOCALE in this && it in locales },
		year = selection.year.takeIf { FilterControl.YEAR in this && it in years } ?: YEAR_UNKNOWN,
		yearFrom = selection.yearFrom.takeIf { FilterControl.YEAR_RANGE in this && it in years } ?: YEAR_UNKNOWN,
		yearTo = selection.yearTo.takeIf { FilterControl.YEAR_RANGE in this && it in years } ?: YEAR_UNKNOWN,
		sortOrder = selection.sortOrder?.takeIf { it in sortOrders },
	)
	// A tag both included and excluded is a contradiction the source cannot satisfy;
	// the include wins, because that is the control the user reaches for first.
	if (result.tagsExclude.isNotEmpty()) {
		result = result.copy(tagsExclude = result.tagsExclude - result.tags)
	}
	if (result.query.isNotEmpty() && !isSearchWithFiltersSupported && result.hasNonSearchOptions) {
		// These sources have two separate endpoints, a search and a browse, and sending
		// both sets of parameters gets one of them silently dropped. Keeping the query is
		// the right call: it is the more specific thing the user asked for.
		result = FilterSelection(query = result.query, sortOrder = result.sortOrder)
	}
	return result
}

/** True when a query and the current filters cannot both be sent to this source. */
fun FilterSpec.conflictsWithQuery(selection: FilterSelection): Boolean =
	!isSearchWithFiltersSupported && selection.query.isNotBlank() && selection.hasNonSearchOptions

/** The request to make for [selection]. Sanitize first; this does not second-guess it. */
fun FilterSpec.toFilter(selection: FilterSelection): MangaListFilter = MangaListFilter(
	query = selection.query.takeIf { it.isNotBlank() },
	tags = selection.tags,
	tagsExclude = selection.tagsExclude,
	locale = selection.locale,
	originalLocale = selection.originalLocale,
	states = selection.states,
	contentRating = selection.contentRating,
	types = selection.types,
	demographics = selection.demographics,
	year = selection.year,
	yearFrom = selection.yearFrom,
	yearTo = selection.yearTo,
	author = selection.author.takeIf { it.isNotBlank() },
)

/** The order to request for [selection]: the user's choice, or a sensible default. */
fun FilterSpec.sortOrderFor(selection: FilterSelection): SortOrder =
	selection.sortOrder?.takeIf { it in sortOrders } ?: defaultSort(selection.query.isNotBlank())

/** A label for [this] that is readable when the JVM has no display name for it. */
fun Locale.displayLanguageOrTag(): String = displayLanguage.ifEmpty { toLanguageTag() }

/** "POPULARITY_TODAY" reads badly in a chip; "Popularity today" does not. */
fun SortOrder.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

fun MangaState.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

fun ContentRating.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

fun ContentType.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

fun Demographic.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
