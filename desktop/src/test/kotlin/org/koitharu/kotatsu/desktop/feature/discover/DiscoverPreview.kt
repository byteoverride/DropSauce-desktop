package org.koitharu.kotatsu.desktop.feature.discover

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.image.ImageCache
import org.koitharu.kotatsu.desktop.library.LibraryRepository
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import org.koitharu.kotatsu.shared.io.AppPaths
import org.koitharu.kotatsu.shared.settings.JsonSettingsStore
import org.koitharu.kotatsu.shared.settings.SettingsStore
import java.nio.file.Files

/**
 * A manual harness: it opens the discover area in a window on its own, without the app
 * shell, using a scratch database and config under a temp directory.
 *
 * It exists because nothing registers [DiscoverFeature] yet, so there is otherwise no
 * way to look at these screens. Run it as a plain main from the test source set. It hits
 * the real network as soon as a source is opened, which is the point: the unit tests
 * cover the models, and this covers "does it actually draw and does a real source work".
 */
private class PreviewPaths(root: Path) : AppPaths {
	override val data: Path = root / "data"
	override val cache: Path = root / "cache"
	override val config: Path = root / "config"
	override val localLibrary: Path = root / "library"
}

private class PreviewContext(root: Path) : FeatureContext {
	private val paths0 = PreviewPaths(root).also { it.ensureDirectories(FileSystem.SYSTEM) }
	override val paths: AppPaths = paths0
	override val db: LibraryDatabase = openLibraryDatabase(paths0.data / "library.db")
	override val sources = SourceRegistry()
	override val images = ImageCache(50)
	override val settings: SettingsStore = JsonSettingsStore(paths0.config / "settings.json")
	override val library = LibraryRepository(db)
	override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val client = OkHttpClient()
	override fun clientFor(source: MangaParserSource): OkHttpClient = client

	override val httpClient: OkHttpClient = client

	override suspend fun details(source: MangaParserSource, manga: Manga): Manga =
		withContext(Dispatchers.IO) { sources.session(source).parser.getDetails(manga) }

	override fun visibleSources(): List<MangaParserSource> = SourceRegistry.usableSources
}

private object PreviewNavigator : FeatureNavigator {
	override fun openDetails(source: MangaParserSource, manga: Manga) = Unit
	override fun openReader(
		source: MangaParserSource,
		manga: Manga,
		chapters: List<MangaChapter>,
		chapterIndex: Int,
		page: Int,
	) = Unit

	override fun openLocalReader(manga: Manga, chapters: List<MangaChapter>, chapterIndex: Int, page: Int) = Unit
	override fun back() = Unit
}

fun main() {
	val root = Files.createTempDirectory("discover-preview").toString().toPath()
	val context = PreviewContext(root)
	application {
		Window(onCloseRequest = ::exitApplication, title = "Discover preview") {
			MaterialTheme {
				Surface { DiscoverFeature.Content(context, PreviewNavigator) }
			}
		}
	}
}
