package org.koitharu.kotatsu.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.FileSystem
import org.koitharu.kotatsu.desktop.image.ImageCache
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.appupdate.AppUpdateFeature
import org.koitharu.kotatsu.desktop.feature.curate.CurateConfigStore
import org.koitharu.kotatsu.desktop.feature.curate.CurateFeature
import org.koitharu.kotatsu.desktop.feature.curate.CurateRepository
import org.koitharu.kotatsu.desktop.feature.curate.IncognitoController
import org.koitharu.kotatsu.desktop.feature.discover.DiscoverFeature
import org.koitharu.kotatsu.desktop.feature.download.DownloadFeature
import org.koitharu.kotatsu.desktop.feature.local.LocalFeature
import org.koitharu.kotatsu.desktop.feature.localx.LocalExtrasFeature
import org.koitharu.kotatsu.desktop.feature.migration.MigrationFeature
import org.koitharu.kotatsu.desktop.feature.readerx.ReaderExtrasFeature
import org.koitharu.kotatsu.desktop.feature.readerx.TitlePrefsRepository
import org.koitharu.kotatsu.desktop.feature.reading.BookmarksFeature
import org.koitharu.kotatsu.desktop.feature.reading.BookmarksRepository
import org.koitharu.kotatsu.desktop.feature.reading.StatsFeature
import org.koitharu.kotatsu.desktop.feature.scrobbling.ScrobblingFeature
import org.koitharu.kotatsu.desktop.feature.scrobbling.TrackingRepository
import org.koitharu.kotatsu.desktop.feature.scrobbling.createTrackingRepository
import org.koitharu.kotatsu.desktop.feature.suggestions.SuggestionsFeature
import org.koitharu.kotatsu.desktop.feature.sync.BackupFeature
import org.koitharu.kotatsu.desktop.feature.sync.UpdatesFeature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.library.ChapterCountRefresher
import org.koitharu.kotatsu.desktop.library.LibraryRepository
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import org.koitharu.kotatsu.shared.io.AppPaths
import org.koitharu.kotatsu.shared.settings.JsonSettingsStore
import org.koitharu.kotatsu.shared.settings.SettingsStore
import org.koitharu.kotatsu.shared.io.defaultAppPaths
import java.util.concurrent.TimeUnit

/** Where the user is. A plain stack; there is no navigation library on desktop. */
sealed interface Screen {

	/** The three top-level destinations, each of which resets the stack. */
	sealed interface Root : Screen

	data object Library : Root

	data object Catalog : Root

	data object History : Root

	data object Settings : Root

	/**
	 * A screen contributed by a feature area, addressed by [Feature.id].
	 *
	 * Feature areas are built independently, so the shell cannot name them one by one in
	 * a `when` without becoming the thing every area has to edit.
	 */
	data class FeatureRoot(val id: String) : Root

	data class Browse(val source: MangaParserSource) : Screen

	/**
	 * Looking for the same title on a source that actually has it.
	 *
	 * Its own screen rather than a mode of the migrate area, because it is reached from a
	 * title rather than from a list of broken ones: a source whose parser works fine and
	 * simply returns no chapters is not broken by any test the library scan can apply,
	 * and it is the case a reader actually hits.
	 */
	data class FindAlternative(val source: MangaParserSource, val manga: Manga) : Screen

	data class Details(val source: MangaParserSource, val manga: Manga) : Screen

	/** A locally imported comic, whose pages come from an archive rather than a source. */
	data class LocalReader(
		val manga: Manga,
		val chapters: List<MangaChapter>,
		val chapterIndex: Int,
		val initialPage: Int = 0,
	) : Screen

	data class Reader(
		val source: MangaParserSource,
		val manga: Manga,
		val chapters: List<MangaChapter>,
		val chapterIndex: Int,
		/** Where to open. Non-zero when resuming from history. */
		val initialPage: Int = 0,
	) : Screen
}

/**
 * Application-wide state: navigation plus the long-lived services.
 *
 * Held for the process lifetime and passed down explicitly rather than through a
 * CompositionLocal, so each screen's dependencies stay visible in its signature.
 */
class AppState(val paths: AppPaths = defaultAppPaths()) {

	val settings: SettingsStore = JsonSettingsStore(paths.config / SETTINGS_FILE)

	/** The User-Agent is read per session, so changing it applies after [SourceRegistry.reset]. */
	val sources = SourceRegistry { settings.data.value.userAgent }

	val images = ImageCache(settings.data.value.imageCacheEntries)

	/** Sources the catalogue can offer, after the adult filter in settings. */
	val visibleSources: List<MangaParserSource>
		get() {
			val current = settings.data.value
			var list = SourceRegistry.usableSources
			if (current.hideAdultSources) {
				list = list.filterNot { with(SourceRegistry) { it.isAdult() } }
			}
			// hiddenSourceTerms was already in SettingsData but nothing honoured it, so
			// the setting existed and did nothing.
			val terms = current.hiddenSourceTerms.filter { it.isNotBlank() }
			if (terms.isNotEmpty()) {
				list = list.filterNot { source ->
					terms.any { source.title.contains(it, ignoreCase = true) }
				}
			}
			return list
		}

	val usableSourceCount: Int get() = SourceRegistry.usableSources.size

	/**
	 * Feature areas contributed to the shell.
	 *
	 * Empty until an area is integrated. Adding one is a single entry here, which is the
	 * point: no area edits navigation, and integrating a batch cannot produce a merge
	 * conflict in a shared `when` block.
	 */
	val features: List<Feature> = listOf(
		LocalFeature,
		LocalExtrasFeature,
		DiscoverFeature,
		SuggestionsFeature,
		DownloadFeature,
		BookmarksFeature,
		CurateFeature,
		UpdatesFeature,
		MigrationFeature,
		ScrobblingFeature,
		ReaderExtrasFeature,
		StatsFeature,
		BackupFeature,
		AppUpdateFeature,
	)

	/**
	 * Whether reading should be recorded.
	 *
	 * One instance, deliberately. `CurateConfigStore` seeds its own `StateFlow` from the
	 * file at construction, so two instances over the same path drift apart the moment
	 * one writes: the user turns incognito on in the Organise screen and the reader,
	 * holding the other instance, keeps recording. `shared()` exists for this and the
	 * curate area has a test pinning it.
	 */
	/**
	 * One tracking repository for the whole app.
	 *
	 * The services screen and the per-title link widget must agree about connection
	 * state, and each construction builds its own sessions and token store.
	 */
	val tracking: TrackingRepository by lazy { createTrackingRepository(featureContext) }

	/** Per-title reading overrides, read when the reader opens a title. */
	val titlePrefs: TitlePrefsRepository by lazy { TitlePrefsRepository(database) }

	/** Bookmarks, shared between the reader's toggle and the bookmarks screen. */
	val bookmarks: BookmarksRepository by lazy { BookmarksRepository(database) }

	/** Batch operations over saved titles, shared by the organise screen and the library. */
	val curate: CurateRepository by lazy { CurateRepository(database) }

	val incognito: IncognitoController by lazy {
		IncognitoController(
			curate,
			CurateConfigStore.shared(paths.config / CurateConfigStore.FILE_NAME),
		)
	}

	/**
	 * Fills in library chapter counts.
	 *
	 * Held here rather than on the library screen so a run keeps going while the user
	 * navigates away, which matters when a shelf is two hundred titles deep.
	 */
	val chapterCounts: ChapterCountRefresher by lazy {
		ChapterCountRefresher(
			db = database,
			fetcher = { source, manga -> featureContext.details(source, manga).chapters?.size ?: 0 },
			scope = scope,
		)
	}

	fun feature(id: String): Feature? = features.firstOrNull { it.id == id }

	/**
	 * How much the tools inside Settings have to report, added together.
	 *
	 * Those areas have no rail slot of their own any more, so without this their dot
	 * would only ever be seen by someone who had already gone looking for it.
	 *
	 * `combine` over an empty list never emits, which would leave the rail waiting on a
	 * flow that says nothing, so the empty case is its own answer.
	 */
	fun toolsBadge(): Flow<Int> {
		val tools = features.filter { !it.isTopLevel }
		if (tools.isEmpty()) return flowOf(0)
		return combine(tools.map { it.badge(featureContext) }) { counts -> counts.sum() }
	}

	/**
	 * For requests that are not to a manga source.
	 *
	 * Its own client rather than a source session's: those carry that source's headers
	 * and cookie jar, and sending them to an unrelated host would be wrong. Short
	 * timeouts because the only caller so far is a release check nobody is waiting on.
	 */
	val httpClient: OkHttpClient by lazy {
		OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS)
			.callTimeout(30, TimeUnit.SECONDS)
			.build()
	}

	/** Survives restarts; this is the app's only durable state. */
	private val database = openLibraryDatabase(paths.data / DATABASE_FILE)

	val library = LibraryRepository(database)

	/** For work that outlives a screen, such as recording reading progress. */
	val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	private val backStack = mutableStateListOf<Screen>(Screen.Library)

	var current: Screen by mutableStateOf(Screen.Library)
		private set

	val canGoBack: Boolean
		get() = backStack.size > 1

	/** The root the current stack was started from, for highlighting the nav rail. */
	val root: Screen.Root
		get() = backStack.first() as? Screen.Root ?: Screen.Library

	fun go(screen: Screen) {
		backStack.add(screen)
		current = screen
	}

	fun back() {
		if (backStack.size > 1) {
			backStack.removeAt(backStack.lastIndex)
			current = backStack.last()
		}
	}

	/** Replaces the top of the stack, for moving between chapters without growing it. */
	fun replace(screen: Screen) {
		backStack[backStack.lastIndex] = screen
		current = screen
	}

	/** Switches top-level destination, discarding the stack under it. */
	/** [FeatureContext] implementation handed to every feature area. */
	val featureContext: FeatureContext = object : FeatureContext {
		override val db get() = database
		override val sources get() = this@AppState.sources
		override val images get() = this@AppState.images
		override val settings get() = this@AppState.settings
		override val paths get() = this@AppState.paths
		override val library get() = this@AppState.library
		override val scope get() = this@AppState.scope
		override fun clientFor(source: MangaParserSource) = this@AppState.sources.session(source).client

		override val httpClient get() = this@AppState.httpClient

		override suspend fun details(source: MangaParserSource, manga: Manga): Manga =
			withContext(Dispatchers.IO) { sources.session(source).parser.getDetails(manga) }

		override fun visibleSources(): List<MangaParserSource> = this@AppState.visibleSources
	}

	fun selectRoot(destination: Screen.Root) {
		backStack.clear()
		backStack.add(destination)
		current = destination
	}

	init {
		// Chapter counts the app already knows but never stored on the title. Cheap,
		// idempotent and local, so it runs on every start rather than waiting for the
		// user to press anything.
		scope.launch { library.backfillChapterCounts() }
		// A track row whose title is gone crashes the updates tab when Room tries to
		// resolve its non-null relation. The read now filters them out so the screen is
		// safe either way, but leaving them in the table means the count on the tab and
		// the list under it could disagree.
		scope.launch { database.tracksDao().deleteOrphans() }
		// Settings that other components cache have to be pushed when they change.
		scope.launch {
			var previousUserAgent = settings.data.value.userAgent
			settings.data.collect { value ->
				images.maxEntries = value.imageCacheEntries
				value.imageCacheMegabytes?.let { images.maxBytes = it.toLong() * 1024 * 1024 }
				if (value.userAgent != previousUserAgent) {
					previousUserAgent = value.userAgent
					sources.reset()
				}
			}
		}
	}

	private companion object {

		const val DATABASE_FILE = "library.db"
		const val SETTINGS_FILE = "settings.json"
	}
}

/** Creates the app's directories, then its state. Fails loudly at startup, not later. */
fun createAppState(): AppState {
	val paths = defaultAppPaths()
	paths.ensureDirectories(FileSystem.SYSTEM)
	return AppState(paths)
}
