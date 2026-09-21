package org.koitharu.kotatsu.desktop.feature

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.image.ImageCache
import org.koitharu.kotatsu.desktop.library.LibraryRepository
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.io.AppPaths
import org.koitharu.kotatsu.shared.settings.SettingsStore

/**
 * Everything a feature area is allowed to depend on.
 *
 * Feature areas are built independently and must not reach into each other or into the
 * navigation shell. This is the whole surface they get: shared services, no screens, no
 * `AppState`, no router. Anything a feature needs that is not here is either something it
 * should own itself, or a contract change that belongs to whoever owns this file.
 */
interface FeatureContext {

	val db: LibraryDatabase

	val sources: SourceRegistry

	val images: ImageCache

	val settings: SettingsStore

	val paths: AppPaths

	val library: LibraryRepository

	/** Outlives any one screen. Use for work that must survive navigation. */
	val scope: CoroutineScope

	/** The HTTP client for [source], carrying that source's own request headers. */
	fun clientFor(source: MangaParserSource): OkHttpClient

	/**
	 * Full details for [manga], including its chapter list.
	 *
	 * Added because four separate areas needed a chapter list and each reached through
	 * `sources.session(source).parser`, which is `internal` and only resolves because
	 * every feature currently lives in one module. Routing it through the contract means
	 * that stops being load-bearing.
	 */
	suspend fun details(source: MangaParserSource, manga: Manga): Manga

	/**
	 * Sources the user has chosen to see, after the adult and hidden-term filters.
	 *
	 * On the contract because two areas re-derived this rule from `SettingsData`
	 * independently, which is how a filter ends up disagreeing with itself.
	 */
	fun visibleSources(): List<MangaParserSource>
}

/**
 * How a feature area plugs into the app.
 *
 * Each area provides one of these from a single object. The shell reads it to build
 * navigation; the area never edits the shell. That is what lets several areas be built
 * at the same time without fighting over one `when` block.
 */
interface Feature {

	/** Stable identifier, also used as the navigation key. Lowercase, no spaces. */
	val id: String

	/** Label for the navigation rail. */
	val title: String

    /** Single-character glyph for the navigation rail. */
	val glyph: String

	/** Whether this area appears as a top-level destination. */
	val isTopLevel: Boolean get() = true

	/** The area's root screen. */
	@Composable
	fun Content(context: FeatureContext, navigator: FeatureNavigator)
}

/**
 * What a feature may ask the shell to do.
 *
 * Deliberately small. A feature can send the user to a title or a chapter, and can go
 * back; it cannot reach arbitrary screens, because that would make every area depend on
 * every other area's navigation model.
 */
interface FeatureNavigator {

	fun openDetails(source: MangaParserSource, manga: Manga)

	fun openReader(
		source: MangaParserSource,
		manga: Manga,
		chapters: List<MangaChapter>,
		chapterIndex: Int,
		page: Int = 0,
	)

	/** Opens a chapter whose pages are already on disk. */
	fun openLocalReader(manga: Manga, chapters: List<MangaChapter>, chapterIndex: Int, page: Int = 0)

	fun back()
}
