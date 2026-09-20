package org.koitharu.kotatsu.desktop.feature.sync

import androidx.compose.runtime.Composable
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/** Backup and restore, as a top-level destination. */
object BackupFeature : Feature {

	override val id: String = "backup"

	override val title: String = "Backup"

	override val glyph: String = "⤓"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		BackupScreen(context)
	}
}

/** New chapters for tracked titles, as a top-level destination. */
object UpdatesFeature : Feature {

	override val id: String = "updates"

	override val title: String = "Updates"

	override val glyph: String = "↻"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		UpdatesScreen(context, navigator)
	}
}

/**
 * The production [MangaDetailsFetcher]: a real request through the source's own parser.
 *
 * Goes through the registry rather than building a parser here, so the check uses the same
 * per-source client with the parser installed as an interceptor that everything else uses.
 * DECISIONS.md D1: a shared client sends no per-source headers and collects 403s, and it
 * still compiles and still works against undemanding sources, which is how that mistake
 * survives to production.
 */
class SourceRegistryFetcher(private val context: FeatureContext) : MangaDetailsFetcher {

	override suspend fun fetch(source: MangaParserSource, manga: Manga): Manga =
		context.sources.session(source).parser.getDetails(manga)
}
