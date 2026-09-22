package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
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
			}
		}.onFailure { e ->
			error = e.message ?: e::class.simpleName ?: "Request failed"
		}
		loading = false
	}

	val gridState = rememberLazyGridState()
	LaunchedEffect(source, submitted, attempt) { loadMore() }
	LaunchedEffect(gridState, source, submitted, attempt) {
		snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
			.distinctUntilChanged()
			.collect { last ->
				if (last >= items.size - GRID_PREFETCH_DISTANCE) loadMore()
			}
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = source.title,
			subtitle = if (items.isEmpty()) null else "${items.size} titles",
			onBack = onBack,
		)
		OutlinedTextField(
			value = query,
			onValueChange = { query = it },
			label = { Text("Search this source, then press Enter") },
			singleLine = true,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 4.dp)
				.onEnter { submitted = query.trim() },
		)
		when {
			items.isEmpty() && loading -> LoadingBox()
			items.isEmpty() && error != null -> ErrorBox(
				message = "This source did not respond.\n$error",
				onRetry = { attempt++ },
			)

			items.isEmpty() -> Box(Modifier.fillMaxSize()) {
				Text(
					text = "Nothing found.",
					modifier = Modifier.padding(24.dp),
					style = MaterialTheme.typography.bodyMedium,
				)
			}

			else -> LazyVerticalGrid(
				columns = GridCells.Adaptive(minSize = 150.dp),
				state = gridState,
				contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				itemsIndexed(items, key = { i, m -> "${m.id}-$i" }) { _, manga ->
					MangaCard(
						manga = manga,
						state = state,
						client = session.client,
						onClick = { onOpen(manga) },
					)
				}
			}
		}
	}
}

@Composable
private fun MangaCard(
	manga: Manga,
	state: AppState,
	client: okhttp3.OkHttpClient,
	onClick: () -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		RemoteImage(
			url = manga.coverUrl,
			client = client,
			cache = state.images,
			contentDescription = manga.title,
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(COVER_ASPECT)
				.clip(RoundedCornerShape(10.dp)),
		)
		Text(
			text = manga.title,
			style = MaterialTheme.typography.bodySmall,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
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

/** Typical manga cover proportions; keeps the grid from jumping before covers load. */
private const val COVER_ASPECT = 0.7f

/** How close to the end of the grid to get before asking for the next page. */
private const val GRID_PREFETCH_DISTANCE = 8
