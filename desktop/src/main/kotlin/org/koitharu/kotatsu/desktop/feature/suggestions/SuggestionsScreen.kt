package org.koitharu.kotatsu.desktop.feature.suggestions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.image.ImageCache
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.COVER_ASPECT
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LoadingBox
import org.koitharu.kotatsu.desktop.ui.LoadingStrip
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.TopBar
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * The suggestions grid.
 *
 * Every card carries its reason. That is the whole point of the screen: a
 * recommendation a reader cannot check is one they cannot disagree with, and the
 * `reason` column exists so they can.
 *
 * Takes the pieces it needs rather than building them, so the same screen can be driven
 * by a controller wired to fakes.
 *
 * A refresh is reported in one place, not two. With cards on screen it is a strip above
 * them, because the grid must not be thrown away to say "still working"; with nothing on
 * screen the centred box is the whole answer and a strip above it would say it twice.
 */
@Composable
fun SuggestionsScreen(
	context: FeatureContext,
	controller: SuggestionsController,
	cards: Flow<List<SuggestionCard>>,
	onOpen: (MangaParserSource, Manga) -> Unit,
	modifier: Modifier = Modifier,
) {
	val stored by cards.collectAsState(initial = emptyList())
	val scope = rememberCoroutineScope()
	val phase = controller.phase

	Column(modifier.fillMaxSize()) {
		TopBar(
			title = "For you",
			subtitle = if (stored.isEmpty()) null else "${stored.size} titles",
			trailing = {
				if (controller.isRunning) {
					TextButton(onClick = { controller.stop() }) { Text("Stop") }
				} else {
					TextButton(onClick = { controller.refresh() }) { Text("Refresh") }
				}
			},
		)
		// Separates the controls from the grid, so it carries structure and has to clear
		// 3:1; outlineVariant is 1.99:1 and is for decoration (1.6).
		HorizontalDivider(color = MaterialTheme.colorScheme.outline)

		// The stored set is what the grid shows, even mid-refresh. A refresh replaces
		// the table only when it finishes, so the user keeps a usable screen instead of
		// watching cards appear and reshuffle under the cursor.
		if (stored.isEmpty()) {
			PhaseBody(phase = phase, onRefresh = { controller.refresh() })
			return@Column
		}
		PhaseStrip(
			phase = phase,
			onStop = { controller.stop() },
			onRefresh = { controller.refresh() },
		)
		LazyVerticalGrid(
			columns = GridCells.Adaptive(GRID_MIN_WIDTH),
			contentPadding = PaddingValues(Space.md),
			horizontalArrangement = Arrangement.spacedBy(Space.md),
			verticalArrangement = Arrangement.spacedBy(Space.md),
			modifier = Modifier.fillMaxSize(),
		) {
			items(stored, key = { it.manga.id }) { card ->
				val source = remember(card.sourceName) { parserSourceOrNull(card.sourceName) }
				SuggestionCoverCard(
					title = card.manga.title,
					coverUrl = card.manga.coverUrl,
					reason = card.reason,
					client = source?.let { context.clientFor(it) },
					images = context.images,
					onClick = { source?.let { onOpen(it, card.manga) } },
					onDismiss = { scope.launch { controller.dismiss(card.manga.id) } },
				)
			}
		}
	}
}

/**
 * What the refresh is doing, drawn above a grid that already has cards in it.
 *
 * A failure here is `compact` on purpose: the cards below it are still usable and
 * throwing them away to show an error would lose more than it explains.
 */
@Composable
private fun PhaseStrip(phase: RefreshPhase, onStop: () -> Unit, onRefresh: () -> Unit) {
	when (phase) {
		is RefreshPhase.Idle -> Unit

		is RefreshPhase.Profiling -> LoadingStrip(
			label = if (phase.total == 0) {
				"Reading your library"
			} else {
				"Reading your library: ${phase.done} of ${phase.total} titles"
			},
			onCancel = onStop,
		)

		is RefreshPhase.Fetching -> LoadingStrip(
			label = buildString {
				append("Asking ${phase.state.total} sources: ${phase.state.settled} answered")
				if (phase.state.failed > 0) append(", ${phase.state.failed} failed")
			},
			progress = phase.state.fraction(),
			onCancel = onStop,
		)

		is RefreshPhase.Finished -> Text(
			text = buildString {
				append("Saved ${phase.stored} suggestions from ${phase.state.answered} sources")
				if (phase.state.failed > 0) {
					append(". ")
					append(
						phase.state.failures.joinToString(", ", limit = MAX_FAILURES_SHOWN) {
							"${it.source.title} failed"
						},
					)
				}
			},
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
		)

		is RefreshPhase.Broken -> ErrorBox(
			message = phase.message,
			onRetry = onRefresh,
			compact = true,
		)
	}
}

/**
 * What to show when the grid has nothing in it.
 *
 * Each of the cases the engine distinguishes is a different thing for the reader to do
 * next, and two of them are not empty at all: a refresh that is still reading the library
 * or still asking sources is **loading**, and saying "Working out what you like." in the
 * shape used for "there is nothing here" is how a minute of work reads as a dead end.
 */
@Composable
private fun PhaseBody(phase: RefreshPhase, onRefresh: () -> Unit) {
	when (phase) {
		is RefreshPhase.Idle -> EmptyBox(
			headline = "Nothing here yet",
			detail = "This fills from what is already in your library.",
			icon = AppIcons.SUGGESTIONS,
			action = { Button(onClick = onRefresh) { Text("Refresh") } },
		)

		is RefreshPhase.Profiling -> LoadingBox(label = "Working out what you like.")

		is RefreshPhase.Fetching -> LoadingBox(
			label = phase.state.emptyExplanation() ?: "Asking the sources.",
		)

		is RefreshPhase.Finished -> EmptyBox(
			headline = "Nothing to show",
			detail = phase.state.emptyExplanation(),
			icon = AppIcons.SUGGESTIONS,
			action = { Button(onClick = onRefresh) { Text("Refresh") } },
		)

		is RefreshPhase.Broken -> ErrorBox(message = phase.message, onRetry = onRefresh)
	}
}

/**
 * One suggestion: cover, title, and the reason underneath.
 *
 * This area draws its own card rather than reusing the shared `CoverCard`, which takes a
 * non-null client. A stored suggestion can outlive its source leaving the catalogue, and
 * the card still has to render then: the reason and the title are the useful part and a
 * missing cell would look like data loss.
 */
@Composable
internal fun SuggestionCoverCard(
	title: String,
	coverUrl: String?,
	reason: String,
	client: OkHttpClient?,
	images: ImageCache,
	onClick: () -> Unit,
	onDismiss: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.clickable(onClick = onClick),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		if (client != null) {
			RemoteImage(
				url = coverUrl,
				client = client,
				cache = images,
				contentDescription = title,
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(COVER_ASPECT)
					.clip(MaterialTheme.shapes.medium),
			)
		} else {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(COVER_ASPECT)
					.clip(MaterialTheme.shapes.medium)
					.background(MaterialTheme.colorScheme.surfaceVariant),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = "source gone",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = reason,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 3,
			overflow = TextOverflow.Ellipsis,
		)
		if (onDismiss != null) {
			TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
				Text("Not for me", style = MaterialTheme.typography.labelSmall)
			}
		}
	}
}

/**
 * The area's root, wired to the real database, the real store and the real sources.
 *
 * Everything is remembered against the context so switching tabs does not rebuild the
 * controller and lose a running refresh.
 */
@Composable
fun SuggestionsRoot(context: FeatureContext, navigator: FeatureNavigator) {
	val store = remember(context) { SuggestionStore(context.paths.config / SuggestionStore.FILE_NAME) }
	val tags = remember(context) { TagCache(context.paths.cache / TagCache.FILE_NAME) }
	val repository = remember(context) { SuggestionsRepository(context.db, store) }
	val profiler = remember(context) {
		SuggestionProfiler(
			db = context.db,
			tags = tags,
			// The only place tags exist. The contract's details call rather than the
			// parser directly, so this area does not depend on an internal type.
			resolver = { source, seed -> context.details(source, seed).tags.map { it.title } },
		)
	}
	val controller = remember(context) {
		SuggestionsController(
			context = context,
			repository = repository,
			profiler = profiler,
			store = store,
		)
	}
	val cards = remember(context) { repository.observe() }
	SuggestionsScreen(
		context = context,
		controller = controller,
		cards = cards,
		onOpen = { source, manga -> navigator.openDetails(source, manga) },
	)
}

/** Wide enough for a two-line title and a three-line reason without crowding. */
private val GRID_MIN_WIDTH = 150.dp

/** Enough failed sources named to be useful, not so many that the line wraps twice. */
private const val MAX_FAILURES_SHOWN = 3
