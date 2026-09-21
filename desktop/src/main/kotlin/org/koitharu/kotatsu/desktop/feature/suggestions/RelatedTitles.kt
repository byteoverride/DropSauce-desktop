package org.koitharu.kotatsu.desktop.feature.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/** One related title, with the sentence explaining why it is here. */
data class RelatedTitle(
	val manga: Manga,
	val source: MangaParserSource,
	val reason: String,
	val relevance: Float,
)

/** How a related-titles lookup is going. */
sealed interface RelatedState {

	data object Loading : RelatedState

	data class Ready(val items: List<RelatedTitle>, val failedSources: Int) : RelatedState

	/** Nothing to show, with the reason spelled out rather than an empty strip. */
	data class Nothing(val explanation: String) : RelatedState
}

/**
 * Finds titles like [manga], from its own source first and then from others.
 *
 * The source's own `getRelatedManga` comes first because a site that keeps a related
 * list knows its catalogue better than a tag match ever will. Most do not implement it,
 * so the fallback is the same tag search the suggestions refresh uses, run against a few
 * other sources as well: a reader who has finished something wants the next one, not
 * another page of the same site.
 *
 * Scoring reuses [score] against a one-title profile. That is deliberate. A second
 * ranking rule would drift from the first, and the reason strings would stop agreeing
 * between this strip and the suggestions grid.
 *
 * Failures are counted, not thrown. One dead source out of five must not empty a strip
 * that four sources filled.
 */
suspend fun relatedTo(
	context: FeatureContext,
	source: MangaParserSource,
	manga: Manga,
	otherSources: Int = OTHER_SOURCES,
	limit: Int = MAX_RELATED,
): RelatedState {
	// A seed with no tags gives the scorer nothing to work with, and the source's own
	// related list is then the only honest answer available.
	val seed = if (manga.tags.isEmpty()) {
		runCatching { context.details(source, manga) }.getOrDefault(manga)
	} else {
		manga
	}
	val profile = buildProfile(
		listOf(
			TasteSignal(
				mangaId = seed.id,
				title = seed.title,
				sourceName = source.name,
				tags = seed.tags.map { it.title }.toSet(),
				contentType = source.contentType.name,
				isFavourite = false,
				// Treated as fully read: this is a profile of one title the user is
				// looking at right now, so there is nothing to weight it against.
				readFraction = 1f,
			),
		),
	)
	if (profile.tags.isEmpty()) {
		return RelatedState.Nothing("This title has no tags, so there is nothing to match it on.")
	}

	val wanted = profile.rankedTags.map { it.label }
	val visible = context.visibleSources()
	val others = visible.filter { it != source }.shuffled().take(otherSources)
	val targets = (if (source in visible) listOf(source) else emptyList()) + others
	if (targets.isEmpty()) {
		return RelatedState.Nothing("Every source is hidden by your catalogue settings.")
	}

	val permits = Semaphore(RELATED_CONCURRENCY)
	var failed = 0
	val lock = Any()
	val found = coroutineScope {
		targets.map { target ->
			async(Dispatchers.IO) {
				permits.withPermit {
					try {
						fetchRelated(context, target, seed, isOwnSource = target == source, wanted)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Throwable) {
						synchronized(lock) { failed++ }
						emptyList()
					}
				}
			}
		}.awaitAll()
	}.flatten()

	val byId = found.associateBy { it.first.id }
	val ranked = rank(
		profile = profile,
		candidates = found.map { (title, titleSource) -> title.toCandidate(titleSource) }
			.filterNot { it.mangaId == seed.id },
		limit = limit,
	)
	if (ranked.isEmpty()) {
		val explanation = when {
			failed == targets.size -> "None of the sources answered."
			found.isEmpty() -> "The sources had nothing under ${wanted.take(2).joinToString(", ")}."
			else -> "Nothing came back that shares a tag with this title."
		}
		return RelatedState.Nothing(explanation)
	}
	return RelatedState.Ready(
		items = ranked.mapNotNull { scored ->
			byId[scored.candidate.mangaId]?.let { (relatedManga, relatedSource) ->
				RelatedTitle(
					manga = relatedManga,
					source = relatedSource,
					reason = scored.reason,
					relevance = scored.relevance,
				)
			}
		},
		failedSources = failed,
	)
}

/**
 * One source's answer, as (title, source) pairs.
 *
 * The source is carried alongside because the strip opens each title against the source
 * it came from, and opening a title against the wrong source fetches a different comic.
 */
private suspend fun fetchRelated(
	context: FeatureContext,
	target: MangaParserSource,
	seed: Manga,
	isOwnSource: Boolean,
	wantedTags: List<String>,
): List<Pair<Manga, MangaParserSource>> = withContext(Dispatchers.IO) {
	val parser = context.sources.session(target).parser
	if (isOwnSource) {
		// Not every parser implements this, and the ones that do not throw rather than
		// return empty, so a failure here falls through to the tag search below.
		val own = runCatching { parser.getRelatedManga(seed) }.getOrNull().orEmpty()
		if (own.isNotEmpty()) return@withContext own.map { it to target }
	}
	val tag = matchingTag(parser, wantedTags) ?: return@withContext emptyList()
	parser.getList(0, preferredOrder(parser.availableSortOrders), MangaListFilter(tags = setOf(tag)))
		.take(PER_SOURCE)
		.map { it to target }
}

/**
 * A strip of related titles the details screen can host.
 *
 * Takes explicit parameters and no navigator: the shell wires [onOpen], so this area
 * does not need to know how the details screen navigates. It renders nothing at all
 * while loading and nothing at all when there is nothing to say, because a permanently
 * empty box under a chapter list is worse than no box.
 */
@Composable
fun RelatedTitlesStrip(
	context: FeatureContext,
	source: MangaParserSource,
	manga: Manga,
	onOpen: (MangaParserSource, Manga) -> Unit,
	modifier: Modifier = Modifier,
	showExplanationWhenEmpty: Boolean = false,
) {
	var state: RelatedState by remember(manga.id, source) { mutableStateOf(RelatedState.Loading) }
	LaunchedEffect(manga.id, source) {
		state = runCatching { relatedTo(context, source, manga) }
			.getOrElse { RelatedState.Nothing(it.describe()) }
	}
	when (val current = state) {
		is RelatedState.Loading -> Unit

		is RelatedState.Nothing -> if (showExplanationWhenEmpty) {
			Text(
				text = current.explanation,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
			)
		}

		is RelatedState.Ready -> Column(modifier.fillMaxWidth()) {
			Text(
				text = "Related titles",
				style = MaterialTheme.typography.titleSmall,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
			)
			if (current.failedSources > 0) {
				Text(
					text = "${current.failedSources} source(s) did not answer",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 16.dp),
				)
			}
			LazyRow(
				contentPadding = PaddingValues(horizontal = 16.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
			) {
				items(current.items, key = { it.manga.id }) { item ->
					SuggestionCoverCard(
						title = item.manga.title,
						coverUrl = item.manga.coverUrl,
						reason = item.reason,
						client = context.clientFor(item.source),
						images = context.images,
						onClick = { onOpen(item.source, item.manga) },
						onDismiss = null,
						modifier = Modifier.width(STRIP_ITEM_WIDTH),
					)
				}
			}
		}
	}
}

/** Sources besides the title's own to ask. Enough for variety, few enough to be quick. */
private const val OTHER_SOURCES = 3

/** Against the same sites as everything else in this area. */
private const val RELATED_CONCURRENCY = 4

/** One screenful of a horizontal strip. */
private const val MAX_RELATED = 20

/** Per source, before ranking. */
private const val PER_SOURCE = 20

/** Cover width in the horizontal strip. */
private val STRIP_ITEM_WIDTH = 132.dp
