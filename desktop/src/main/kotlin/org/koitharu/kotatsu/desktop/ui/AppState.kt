package org.koitharu.kotatsu.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import org.koitharu.kotatsu.desktop.image.ImageCache
import org.koitharu.kotatsu.desktop.library.LibraryRepository
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import org.koitharu.kotatsu.shared.io.AppPaths
import org.koitharu.kotatsu.shared.io.XdgAppPaths

/** Where the user is. A plain stack; there is no navigation library on desktop. */
sealed interface Screen {

	/** The three top-level destinations, each of which resets the stack. */
	sealed interface Root : Screen

	data object Library : Root

	data object Catalog : Root

	data object History : Root

	data class Browse(val source: MangaParserSource) : Screen

	data class Details(val source: MangaParserSource, val manga: Manga) : Screen

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
class AppState(paths: AppPaths = XdgAppPaths()) {

	val sources = SourceRegistry()
	val images = ImageCache()

	/** Survives restarts; this is the app's only durable state. */
	val library = LibraryRepository(openLibraryDatabase(paths.data / DATABASE_FILE))

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
	fun selectRoot(destination: Screen.Root) {
		backStack.clear()
		backStack.add(destination)
		current = destination
	}

	private companion object {

		const val DATABASE_FILE = "library.db"
	}
}

/** Creates the app's directories, then its state. Fails loudly at startup, not later. */
fun createAppState(): AppState {
	val paths = XdgAppPaths()
	paths.ensureDirectories(FileSystem.SYSTEM)
	return AppState(paths)
}
