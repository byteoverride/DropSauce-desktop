package org.koitharu.kotatsu.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.koitharu.kotatsu.desktop.image.ImageCache
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/** Where the user is. A plain stack; there is no navigation library on desktop. */
sealed interface Screen {

	data object Catalog : Screen

	data class Browse(val source: MangaParserSource) : Screen

	data class Details(val source: MangaParserSource, val manga: Manga) : Screen

	data class Reader(
		val source: MangaParserSource,
		val manga: Manga,
		val chapters: List<MangaChapter>,
		val chapterIndex: Int,
	) : Screen
}

/**
 * Application-wide state: the navigation stack and the two long-lived services.
 *
 * Held for the process lifetime and passed down explicitly rather than through a
 * CompositionLocal, so that what each screen depends on stays visible in its signature.
 */
class AppState {

	val sources = SourceRegistry()
	val images = ImageCache()

	private val backStack = mutableStateListOf<Screen>(Screen.Catalog)

	var current: Screen by mutableStateOf(Screen.Catalog)
		private set

	val canGoBack: Boolean
		get() = backStack.size > 1

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
}
