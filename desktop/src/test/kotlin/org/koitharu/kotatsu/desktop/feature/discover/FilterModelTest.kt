package org.koitharu.kotatsu.desktop.feature.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.Locale

/**
 * The panel is driven entirely by these two objects, so this is where the rule "never
 * show a control the source ignores" is actually enforced and tested. The composables
 * only render what comes out of here.
 */
@OptIn(InternalParsersApi::class)
class FilterModelTest {

	private val source = MangaParserSource.entries.first()

	private fun tag(name: String) = MangaTag(title = name, key = name.lowercase(), source = source)

	private fun capabilities(
		multipleTags: Boolean = false,
		tagsExclusion: Boolean = false,
		search: Boolean = false,
		searchWithFilters: Boolean = false,
		year: Boolean = false,
		yearRange: Boolean = false,
		originalLocale: Boolean = false,
		authorSearch: Boolean = false,
	) = MangaListFilterCapabilities(
		isMultipleTagsSupported = multipleTags,
		isTagsExclusionSupported = tagsExclusion,
		isSearchSupported = search,
		isSearchWithFiltersSupported = searchWithFilters,
		isYearSupported = year,
		isYearRangeSupported = yearRange,
		isOriginalLocaleSupported = originalLocale,
		isAuthorSearchSupported = authorSearch,
	)

	private fun options(
		tags: Set<MangaTag> = emptySet(),
		states: Set<MangaState> = emptySet(),
		ratings: Set<ContentRating> = emptySet(),
		types: Set<ContentType> = emptySet(),
		demographics: Set<Demographic> = emptySet(),
		locales: Set<Locale> = emptySet(),
	) = MangaListFilterOptions(
		availableTags = tags,
		availableStates = states,
		availableContentRating = ratings,
		availableContentTypes = types,
		availableDemographics = demographics,
		availableLocales = locales,
	)

	@Test
	fun `a source that supports nothing offers no controls at all`() {
		val spec = FilterSpec.of(capabilities(), options(), setOf(SortOrder.POPULARITY))
		assertEquals(emptySet<FilterControl>(), spec.controls)
		assertFalse(spec.hasFilters)
	}

	@Test
	fun `only the flags that are on become controls`() {
		val spec = FilterSpec.of(
			capabilities(search = true, year = true, authorSearch = true),
			options(states = setOf(MangaState.ONGOING)),
			setOf(SortOrder.POPULARITY, SortOrder.UPDATED),
		)
		assertEquals(
			setOf(
				FilterControl.QUERY,
				FilterControl.AUTHOR,
				FilterControl.YEAR,
				FilterControl.STATE,
				FilterControl.SORT,
			),
			spec.controls,
		)
		// Everything not listed above is absent, which is the whole point.
		assertFalse(FilterControl.TAGS in spec)
		assertFalse(FilterControl.YEAR_RANGE in spec)
		assertFalse(FilterControl.LOCALE in spec)
		assertFalse(FilterControl.DEMOGRAPHIC in spec)
		assertTrue(spec.hasFilters)
	}

	@Test
	fun `a capability with no values behind it is not a control`() {
		// The flag says tags can be excluded, but the source served no tags at all, so
		// there is nothing to tick and both tag controls stay off.
		val spec = FilterSpec.of(capabilities(tagsExclusion = true), options(), emptySet())
		assertFalse(FilterControl.TAGS in spec)
		assertFalse(FilterControl.TAGS_EXCLUDE in spec)
	}

	@Test
	fun `tag exclusion needs the flag as well as the tags`() {
		val withTags = options(tags = setOf(tag("Action")))
		val without = FilterSpec.of(capabilities(), withTags, emptySet())
		assertTrue(FilterControl.TAGS in without)
		assertFalse(FilterControl.TAGS_EXCLUDE in without)

		val with = FilterSpec.of(capabilities(tagsExclusion = true), withTags, emptySet())
		assertTrue(FilterControl.TAGS_EXCLUDE in with)
	}

	@Test
	fun `original locale needs the flag as well as the locale list`() {
		val locales = options(locales = setOf(Locale.ENGLISH, Locale.JAPANESE))
		assertFalse(FilterControl.ORIGINAL_LOCALE in FilterSpec.of(capabilities(), locales, emptySet()))
		assertTrue(
			FilterControl.ORIGINAL_LOCALE in
				FilterSpec.of(capabilities(originalLocale = true), locales, emptySet()),
		)
	}

	@Test
	fun `a single sort order is not offered as a choice`() {
		val one = FilterSpec.of(capabilities(), options(), setOf(SortOrder.POPULARITY))
		assertFalse(FilterControl.SORT in one)
		val two = FilterSpec.of(capabilities(), options(), setOf(SortOrder.POPULARITY, SortOrder.NEWEST))
		assertTrue(FilterControl.SORT in two)
	}

	@Test
	fun `null options mean no value based controls`() {
		val spec = FilterSpec.of(capabilities(search = true, year = true), null, setOf(SortOrder.NEWEST))
		assertEquals(setOf(FilterControl.QUERY, FilterControl.YEAR), spec.controls)
		assertTrue(spec.tags.isEmpty())
	}

	@Test
	fun `sanitize drops everything the source does not honour`() {
		val spec = FilterSpec.of(
			capabilities(search = true, searchWithFilters = true),
			options(states = setOf(MangaState.ONGOING)),
			setOf(SortOrder.POPULARITY, SortOrder.NEWEST),
		)
		val sanitized = spec.sanitize(
			FilterSelection(
				query = " naruto ",
				author = "someone",
				tags = setOf(tag("Action")),
				states = setOf(MangaState.ONGOING, MangaState.FINISHED),
				demographics = setOf(Demographic.SEINEN),
				locale = Locale.ENGLISH,
				year = 2020,
				sortOrder = SortOrder.RATING,
			),
		)
		assertEquals("naruto", sanitized.query)
		assertEquals("", sanitized.author)
		assertEquals(emptySet<MangaTag>(), sanitized.tags)
		// FINISHED is not in the source's own list, so it goes too.
		assertEquals(setOf(MangaState.ONGOING), sanitized.states)
		assertEquals(emptySet<Demographic>(), sanitized.demographics)
		assertNull(sanitized.locale)
		assertEquals(0, sanitized.year)
		assertNull(sanitized.sortOrder)
	}

	@Test
	fun `a source that takes one tag gets one tag`() {
		val a = tag("Action")
		val b = tag("Comedy")
		val spec = FilterSpec.of(capabilities(), options(tags = setOf(a, b)), emptySet())
		assertEquals(1, spec.sanitize(FilterSelection(tags = setOf(a, b))).tags.size)

		val multiple = FilterSpec.of(
			capabilities(multipleTags = true),
			options(tags = setOf(a, b)),
			emptySet(),
		)
		assertEquals(setOf(a, b), multiple.sanitize(FilterSelection(tags = setOf(a, b))).tags)
	}

	@Test
	fun `a tag cannot be included and excluded at once`() {
		val a = tag("Action")
		val spec = FilterSpec.of(
			capabilities(multipleTags = true, tagsExclusion = true),
			options(tags = setOf(a)),
			emptySet(),
		)
		val sanitized = spec.sanitize(FilterSelection(tags = setOf(a), tagsExclude = setOf(a)))
		assertEquals(setOf(a), sanitized.tags)
		assertEquals(emptySet<MangaTag>(), sanitized.tagsExclude)
	}

	@Test
	fun `a query clears the filters on a source that cannot combine them`() {
		val spec = FilterSpec.of(
			capabilities(search = true, searchWithFilters = false),
			options(states = setOf(MangaState.ONGOING)),
			emptySet(),
		)
		val selection = FilterSelection(query = "bleach", states = setOf(MangaState.ONGOING))
		assertTrue(spec.conflictsWithQuery(selection))
		val sanitized = spec.sanitize(selection)
		assertEquals("bleach", sanitized.query)
		assertEquals(emptySet<MangaState>(), sanitized.states)
	}

	@Test
	fun `filters survive a query on a source that can combine them`() {
		val spec = FilterSpec.of(
			capabilities(search = true, searchWithFilters = true),
			options(states = setOf(MangaState.ONGOING)),
			emptySet(),
		)
		val selection = FilterSelection(query = "bleach", states = setOf(MangaState.ONGOING))
		assertFalse(spec.conflictsWithQuery(selection))
		assertEquals(setOf(MangaState.ONGOING), spec.sanitize(selection).states)
	}

	@Test
	fun `the sort order falls back to one the source actually has`() {
		val spec = FilterSpec.of(capabilities(), options(), setOf(SortOrder.UPDATED, SortOrder.ALPHABETICAL))
		// RELEVANCE would be right for a search and POPULARITY for browsing, but this
		// source has neither.
		assertEquals(SortOrder.UPDATED, spec.sortOrderFor(FilterSelection(query = "x")))
		assertEquals(SortOrder.UPDATED, spec.sortOrderFor(FilterSelection()))
		assertEquals(
			SortOrder.ALPHABETICAL,
			spec.sortOrderFor(FilterSelection(sortOrder = SortOrder.ALPHABETICAL)),
		)
		// A choice the source dropped is not honoured just because it is in the selection.
		assertEquals(SortOrder.UPDATED, spec.sortOrderFor(FilterSelection(sortOrder = SortOrder.RATING)))
	}

	@Test
	fun `the request carries exactly what was sanitized`() {
		val a = tag("Action")
		val spec = FilterSpec.of(
			capabilities(search = true, searchWithFilters = true, multipleTags = true, year = true),
			options(tags = setOf(a), locales = setOf(Locale.ENGLISH)),
			setOf(SortOrder.POPULARITY),
		)
		val sanitized = spec.sanitize(
			FilterSelection(query = "x", tags = setOf(a), locale = Locale.ENGLISH, year = 2011),
		)
		val filter = spec.toFilter(sanitized)
		assertEquals("x", filter.query)
		assertEquals(setOf(a), filter.tags)
		assertEquals(Locale.ENGLISH, filter.locale)
		assertEquals(2011, filter.year)
		assertNull(filter.author)
	}

	@Test
	fun `a year outside the range the parsers accept is dropped`() {
		val spec = FilterSpec.of(capabilities(year = true), options(), emptySet())
		assertEquals(0, spec.sanitize(FilterSelection(year = 1200)).year)
		assertEquals(1995, spec.sanitize(FilterSelection(year = 1995)).year)
	}

	@Test
	fun `counting active filters ignores the query`() {
		assertEquals(0, countActive(FilterSelection(query = "x")))
		assertEquals(2, countActive(FilterSelection(tags = setOf(tag("a")), year = 2001)))
	}
}
