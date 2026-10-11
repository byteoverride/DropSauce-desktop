package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.search.MAX_FRUITLESS_PAGES
import org.koitharu.kotatsu.desktop.search.rankForQuery
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Browse and search one source.
 *
 * Paging is offset-based, the shape the parser API exposes. The grid asks for the next
 * page when it gets near the end; a page that comes back empty ends the list, because
 * that is the only end-of-data signal the API gives.
 */
@Composable
fun BrowseScreen(
	state: AppState,
	source: MangaParserSource,
	onBack: () -> Unit,
	onOpen: (Manga) -> Unit,
) {
	val session = remember(source) { state.sources.session(source) }
	var query by remember(source) { mutableStateOf("") }
	var submitted by remember(source) { mutableStateOf("") }
	/**
	 * Bumped to ask again for the same query.
	 *
	 * Retry used to be `submitted = submitted`, which does nothing: a `mutableStateOf`
	 * assigned its own value is not a change, so no key was invalidated and no effect
	 * re-ran. The button was there, looked enabled, and could not work. A counter is a
	 * real change every time.
	 */
	var attempt by remember(source) { mutableStateOf(0) }
	val items = remember(source, submitted, attempt) { mutableStateListOf<Manga>() }
	var loading by remember(source, submitted, attempt) { mutableStateOf(false) }
	var exhausted by remember(source, submitted, attempt) { mutableStateOf(false) }
	var error: String? by remember(source, submitted, attempt) { mutableStateOf(null) }
	var showLoose by remember(source, submitted, attempt) { mutableStateOf(false) }
	/** Consecutive pages holding no match for [submitted]. See [MAX_FRUITLESS_PAGES]. */
	var fruitlessPages by remember(source, submitted, attempt) { mutableStateOf(0) }

	// A site search is loose, and several of these sources answer a title they do not
	// have with their front page, so what came back is ranked against the query and the
	// rows that answer nothing are held behind a count. See rankForQuery.
	//
	// derivedStateOf rather than a plain val: the paging collector below runs in a
	// coroutine that outlives the composition it started in, and a plain val would be
	// captured once, at a point where the list is still empty. These read live there.
	val ranked by remember(source, submitted, attempt) {
		derivedStateOf { rankForQuery(submitted, items.toList()) }
	}
	val shown by remember(source, submitted, attempt) {
		derivedStateOf { if (showLoose) ranked.all else ranked.shown }
	}
	val uiScope = rememberCoroutineScope()

	suspend fun loadMore() {
		if (loading || exhausted) return
		loading = true
		error = null
		val offset = items.size
		val result = runCatching {
			withContext(Dispatchers.IO) {
				session.parser.getList(
					offset,
					// RELEVANCE is the order a search expects; POPULARITY is a sensible
					// default for plain browsing. Not every source supports either, so
					// fall back to whatever the parser lists first.
					preferredOrder(session.parser.availableSortOrders, submitted.isNotBlank()),
					MangaListFilter(query = submitted.takeIf { it.isNotBlank() }),
				)
			}
		}
		result.onSuccess { page ->
			if (page.isEmpty()) {
				exhausted = true
			} else {
				items.addAll(page)
				fruitlessPages = if (rankForQuery(submitted, page).shown.isEmpty()) {
					fruitlessPages + 1
				} else {
					0
				}
			}
		}.onFailure { e ->
			error = e.message ?: e::class.simpleName ?: "Request failed"
		}
		loading = false
	}

	val stalled by remember(source, submitted, attempt) {
		derivedStateOf { fruitlessPages >= MAX_FRUITLESS_PAGES }
	}

	val gridState = rememberLazyGridState()
	LaunchedEffect(source, submitted, attempt) { loadMore() }
	LaunchedEffect(gridState, source, submitted, attempt) {
		snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
			.distinctUntilChanged()
			.collect { last ->
				// Against what is on screen, not what was fetched: with the unrelated rows
				// held back those differ, and the fetched count would stop paging while the
				// grid still had room.
				if (!stalled && last >= shown.size - GRID_PREFETCH_DISTANCE) loadMore()
			}
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = source.title,
			subtitle = when {
				items.isEmpty() -> null
				ranked.hiddenCount > 0 -> "${shown.size} of ${items.size} titles"
				else -> "${items.size} titles"
			},
			onBack = onBack,
		)
		OutlinedTextField(
			value = query,
			onValueChange = { query = it },
			label = { Text("Search this source, then press Enter") },
			singleLine = true,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = Space.lg, vertical = Space.sm)
				.onEnter { submitted = query.trim() },
		)
		if (ranked.hiddenCount > 0) {
			// Neither of these two is a failure, so they are notices rather than errors.
			NoticeBar(
				text = "${ranked.hiddenCount} of ${items.size} returned " +
					"${if (ranked.hiddenCount == 1) "title has" else "titles have"} nothing to do " +
					"with \"$submitted\"",
				action = {
					TextButton(onClick = { showLoose = !showLoose }) {
						Text(if (showLoose) "Hide them" else "Show them")
					}
				},
			)
		}
		if (stalled && !exhausted) {
			NoticeBar(
				text = "Stopped paging: the last $MAX_FRUITLESS_PAGES pages held no match for " +
					"\"$submitted\".",
				action = {
					TextButton(
						onClick = {
							fruitlessPages = 0
							uiScope.launch { loadMore() }
						},
					) {
						Text("Keep looking")
					}
				},
			)
		}
		when {
			items.isEmpty() && loading -> LoadingBox()
			items.isEmpty() && error != null -> ErrorBox(
				message = "This source did not respond.\n$error",
				onRetry = { attempt++ },
			)

			items.isEmpty() -> EmptyBox(
				headline = "Nothing found.",
				detail = if (submitted.isNotBlank()) {
					"This source answered the search for \"$submitted\" with no titles at all."
				} else {
					null
				},
				icon = AppIcons.DISCOVER,
			)

			shown.isEmpty() -> EmptyBox(
				// Rows came back and all of them were held back. That is a different thing to
				// say than "nothing found", and the toggle above is the way out of it.
				headline = "Nothing matches \"$submitted\".",
				detail = "${items.size} ${if (items.size == 1) "title" else "titles"} came back " +
					"and none of them has anything to do with it. Show them above lists them anyway.",
				icon = AppIcons.DISCOVER,
			)

			else -> LazyVerticalGrid(
				columns = GridCells.Adaptive(minSize = COVER_CELL_MIN_WIDTH),
				state = gridState,
				contentPadding = PaddingValues(Space.md),
				horizontalArrangement = Arrangement.spacedBy(Space.md),
				verticalArrangement = Arrangement.spacedBy(Space.md),
				modifier = Modifier.fillMaxSize(),
			) {
				itemsIndexed(shown, key = { i, m -> "${m.id}-$i" }) { _, manga ->
					CoverCard(
						title = manga.title,
						coverUrl = manga.coverUrl,
						client = session.client,
						images = state.images,
						onClick = { onOpen(manga) },
					)
				}
			}
		}
	}
}

private fun preferredOrder(available: Set<SortOrder>, isSearch: Boolean): SortOrder {
	val wanted = if (isSearch) SortOrder.RELEVANCE else SortOrder.POPULARITY
	return when {
		wanted in available -> wanted
		SortOrder.UPDATED in available -> SortOrder.UPDATED
		else -> available.firstOrNull() ?: SortOrder.ALPHABETICAL
	}
}

/** How close to the end of the grid to get before asking for the next page. */
private const val GRID_PREFETCH_DISTANCE = 8

/**
 * The cover grid's smallest cell.
 *
 * Unchanged at 150 dp on purpose: with the cover drawn at [COVER_ASPECT] this is what
 * sets the decode bucket `RemoteImage` rounds up to, and the memory budget in DECISIONS
 * D37 was measured against it.
 */
private val COVER_CELL_MIN_WIDTH = 150.dp
