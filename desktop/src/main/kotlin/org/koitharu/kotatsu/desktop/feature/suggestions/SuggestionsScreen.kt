package org.koitharu.kotatsu.desktop.feature.suggestions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.koitharu.kotatsu.desktop.ui.RemoteImage
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
		SuggestionsToolbar(
			phase = phase,
			shown = stored.size,
			onRefresh = { controller.refresh() },
			onStop = { controller.stop() },
		)
		HorizontalDivider()
		PhaseNote(phase)

		// The stored set is what the grid shows, even mid-refresh. A refresh replaces
		// the table only when it finishes, so the user keeps a usable screen instead of
		// watching cards appear and reshuffle under the cursor.
		if (stored.isEmpty()) {
			EmptyNote(phase)
			return@Column
		}
		LazyVerticalGrid(
			columns = GridCells.Adaptive(GRID_MIN_WIDTH),
			contentPadding = PaddingValues(16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp),
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

@Composable
private fun SuggestionsToolbar(
	phase: RefreshPhase,
	shown: Int,
	onRefresh: () -> Unit,
	onStop: () -> Unit,
) {
	val running = phase is RefreshPhase.Profiling || phase is RefreshPhase.Fetching
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = if (shown == 0) "For you" else "For you: $shown titles",
			style = MaterialTheme.typography.titleMedium,
		)
		Spacer(Modifier.weight(1f))
		if (running) {
			TextButton(onClick = onStop) { Text("Stop") }
		} else {
			TextButton(onClick = onRefresh) { Text("Refresh") }
		}
	}
	if (phase is RefreshPhase.Fetching) {
		ProgressBar(phase.state.fraction(), Modifier.fillMaxWidth())
	}
}

/** One line under the toolbar saying what the refresh is doing, or what it found. */
@Composable
private fun PhaseNote(phase: RefreshPhase) {
	val note = when (phase) {
		is RefreshPhase.Idle -> null

		is RefreshPhase.Profiling -> if (phase.total == 0) {
			"Reading your library"
		} else {
			"Reading your library: ${phase.done} of ${phase.total} titles"
		}

		is RefreshPhase.Fetching -> buildString {
			append("Asking ${phase.state.total} sources: ${phase.state.settled} answered")
			if (phase.state.failed > 0) append(", ${phase.state.failed} failed")
		}

		is RefreshPhase.Finished -> buildString {
			append("Saved ${phase.stored} suggestions from ${phase.state.answered} sources")
			if (phase.state.failed > 0) {
				append(". ")
				append(phase.state.failures.joinToString(", ", limit = MAX_FAILURES_SHOWN) {
					"${it.source.title} failed"
				})
			}
		}

		is RefreshPhase.Broken -> "The refresh could not run: ${phase.message}"
	}
	if (note != null) {
		Text(
			text = note,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
		)
	}
}

/**
 * What to say when the grid has nothing in it.
 *
 * Always a sentence, never a blank panel. Each of the cases the engine distinguishes is
 * a different thing for the reader to do next.
 */
@Composable
private fun EmptyNote(phase: RefreshPhase) {
	val text = when (phase) {
		is RefreshPhase.Idle ->
			"Nothing here yet. Press Refresh and this fills from what is already in your library."

		is RefreshPhase.Profiling -> "Working out what you like."
		is RefreshPhase.Fetching -> phase.state.emptyExplanation() ?: "Asking the sources."
		is RefreshPhase.Finished -> phase.state.emptyExplanation() ?: "Nothing to show."
		is RefreshPhase.Broken -> "The refresh could not run: ${phase.message}"
	}
	Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
		Text(
			text = text,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * One suggestion: cover, title, and the reason underneath.
 *
 * This area draws its own card rather than reusing the one in the discover area. The two
 * are built in parallel by different people and the reason line is not something that
 * card has; importing it would couple the areas over four lines of layout.
 *
 * [client] may be null when the stored source has left the catalogue. The card still
 * renders, without a cover, because the reason and the title are the useful part and a
 * missing row would look like data loss.
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
		verticalArrangement = Arrangement.spacedBy(4.dp),
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
					.clip(RoundedCornerShape(10.dp)),
			)
		} else {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(COVER_ASPECT)
					.clip(RoundedCornerShape(10.dp))
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
			style = MaterialTheme.typography.bodySmall,
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
 * A determinate progress bar, drawn from two boxes.
 *
 * `LinearProgressIndicator`'s overload set in this material3 build is ambiguous at the
 * call site once every optional parameter is omitted. Two boxes cannot be ambiguous.
 */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.height(4.dp)
			.clip(RoundedCornerShape(2.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant),
	) {
		Box(
			Modifier
				.fillMaxHeight()
				.fillMaxWidth(fraction.coerceIn(0f, 1f))
				.background(MaterialTheme.colorScheme.primary),
		)
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

/** Typical manga cover proportions; keeps the grid from jumping before covers load. */
private const val COVER_ASPECT = 0.7f

/** Wide enough for a two-line title and a three-line reason without crowding. */
private val GRID_MIN_WIDTH = 150.dp

/** Enough failed sources named to be useful, not so many that the line wraps twice. */
private const val MAX_FAILURES_SHOWN = 3
