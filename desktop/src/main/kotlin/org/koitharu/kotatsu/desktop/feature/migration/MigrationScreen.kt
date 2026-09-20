package org.koitharu.kotatsu.desktop.feature.migration

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.desktop.ui.TopBar
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Move one title to a different source.
 *
 * Candidates stream in rather than appearing all at once: searching a thousand sources
 * takes minutes, and the source the user wants is usually among the first few to answer.
 * Nothing is written until Confirm.
 */
@Composable
fun MigrationScreen(
	context: FeatureContext,
	entry: LibraryEntry,
	onBack: () -> Unit,
	onMigrated: () -> Unit = {},
) {
	val scope = rememberCoroutineScope()
	val engine = remember(context) {
		AlternativesEngine(SourceRegistrySearcher(context), SourceRegistryDetailsLoader(context))
	}
	val service = remember(context) {
		MigrationService(MigrationRepository(context.db), SourceRegistryDetailsLoader(context))
	}

	val candidates = remember(entry.manga.id) { mutableStateListOf<Alternative>() }
	var searching by remember(entry.manga.id) { mutableStateOf(true) }
	var failed by remember(entry.manga.id) { mutableStateOf(0) }
	var total by remember(entry.manga.id) { mutableStateOf(0) }
	var selected: Alternative? by remember(entry.manga.id) { mutableStateOf(null) }
	var migrateProgress by remember(entry.manga.id) { mutableStateOf(true) }
	var running by remember(entry.manga.id) { mutableStateOf(false) }
	var outcome: String? by remember(entry.manga.id) { mutableStateOf(null) }
	var failure: String? by remember(entry.manga.id) { mutableStateOf(null) }

	LaunchedEffect(entry.manga.id) {
		val sources = engine.candidateSources(
			currentSourceName = entry.sourceName,
			isNovel = entry.source?.contentType == ContentType.NOVEL,
		)
		total = sources.size
		try {
			engine.alternatives(entry.manga, sources, onFailure = { failed++ }).collect { alternative ->
				// Inserted in rank order as it arrives, so the top of the list is always the
				// best answer so far and does not jump around as later sources report.
				val at = candidates.indexOfFirst { alternativeOrder.compare(alternative, it) < 0 }
				if (at < 0) candidates.add(alternative) else candidates.add(at, alternative)
			}
		} finally {
			searching = false
		}
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Migrate ${entry.manga.title}",
			subtitle = buildString {
				append("from ${entry.sourceName}")
				if (entry.needsFixing) append(" · ${entry.reason()}")
			},
			onBack = onBack,
		)
		if (searching) {
			LinearProgressIndicator(Modifier.fillMaxWidth())
		}
		Text(
			text = statusLine(searching, candidates.size, failed, total),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
		)
		HorizontalDivider()

		val shownFailure = failure
		if (shownFailure != null) {
			ErrorBox(shownFailure, modifier = Modifier.weight(1f))
		} else if (!searching && candidates.isEmpty()) {
			ErrorBox("No other source has this title.", modifier = Modifier.weight(1f))
		} else {
			LazyColumn(Modifier.weight(1f)) {
				// Keyed by source as well as id: two sources producing the same id would be
				// a duplicate key and LazyColumn throws on those.
				items(candidates, key = { "${it.manga.source.name}:${it.manga.id}" }) { alternative ->
					CandidateRow(
						context = context,
						alternative = alternative,
						isSelected = selected?.manga?.id == alternative.manga.id,
						onClick = { selected = alternative },
					)
					HorizontalDivider()
				}
			}
		}

		HorizontalDivider()
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Switch(checked = migrateProgress, onCheckedChange = { migrateProgress = it })
				Column(Modifier.padding(start = 12.dp)) {
					Text("Migrate progress", style = MaterialTheme.typography.bodyMedium)
					Text(
						text = if (migrateProgress) {
							"Reading position, bookmarks and tracking follow the title."
						} else {
							"Only favourites move. History, bookmarks and tracking are deleted."
						},
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			val done = outcome
			if (done != null) {
				Text(done, style = MaterialTheme.typography.bodyMedium)
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(
					enabled = selected != null && !running,
					onClick = {
						val target = selected ?: return@Button
						scope.launch {
							running = true
							failure = null
							try {
								val result = service.migrate(entry.manga, target.manga, migrateProgress)
								outcome = "Moved to ${target.manga.source.name}: ${result.describe()}"
								onMigrated()
							} catch (e: CancellationException) {
								throw e
							} catch (e: Throwable) {
								// Nothing was written: the migration is one transaction and it
								// rolled back. Say so, because "it failed" otherwise leaves the
								// user wondering whether half of it happened.
								failure = "Nothing was changed. ${e.describeFailure()}"
							} finally {
								running = false
							}
						}
					},
				) {
					Text(if (running) "Migrating…" else "Confirm")
				}
				TextButton(onClick = onBack) { Text("Cancel") }
			}
		}
	}
}

@Composable
private fun CandidateRow(
	context: FeatureContext,
	alternative: Alternative,
	isSelected: Boolean,
	onClick: () -> Unit,
) {
	val source = alternative.source
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 8.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			Modifier
				.width(44.dp)
				.height(62.dp)
				.clip(RoundedCornerShape(4.dp))
				.then(
					if (isSelected) {
						Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
					} else {
						Modifier
					},
				),
		) {
			if (source != null) {
				RemoteImage(
					url = alternative.manga.coverUrl,
					client = context.clientFor(source),
					cache = context.images,
					contentDescription = alternative.manga.title,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
		Column(Modifier.weight(1f)) {
			Text(
				text = alternative.manga.title,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = describeCandidate(alternative, source),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/** The two things that decide a candidate: can it be read, and is it the same comic. */
internal fun describeCandidate(alternative: Alternative, source: MangaParserSource?): String {
	val chapters = if (alternative.chaptersCount == 0) {
		"no chapters"
	} else {
		"${alternative.chaptersCount} chapters"
	}
	val match = when {
		alternative.similarity >= 1f -> "exact title match"
		alternative.similarity >= 0.5f -> "close title match"
		else -> "loose title match"
	}
	return "${source?.title ?: alternative.manga.source.name} · $chapters · $match"
}

private fun statusLine(searching: Boolean, found: Int, failed: Int, total: Int): String = buildString {
	append(if (searching) "Searching $total sources" else "Searched $total sources")
	append(" · $found with a result")
	if (failed > 0) append(" · $failed could not be searched")
}
