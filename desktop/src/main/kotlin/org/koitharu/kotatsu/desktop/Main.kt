package org.koitharu.kotatsu.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import org.koitharu.kotatsu.core.util.ext.DebugFlags
import org.koitharu.kotatsu.desktop.ui.AppState
import org.koitharu.kotatsu.desktop.ui.BrowseScreen
import org.koitharu.kotatsu.desktop.ui.CatalogScreen
import org.koitharu.kotatsu.desktop.ui.DetailsScreen
import org.koitharu.kotatsu.desktop.ui.HistoryScreen
import org.koitharu.kotatsu.desktop.ui.LibraryScreen
import org.koitharu.kotatsu.desktop.ui.createAppState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.desktop.ui.ReaderScreen
import org.koitharu.kotatsu.desktop.ui.Screen

fun main() {
	// Counterpart of BaseApp setting this from BuildConfig.DEBUG on Android.
	DebugFlags.isDebug = System.getProperty("dropsauce.debug").toBoolean()
	launchUi()
}

private fun launchUi() = application {
	// createAppState makes the XDG directories before opening the database, so a
	// permissions or disk problem surfaces at startup rather than at the first write.
	val state = remember { createAppState() }
	Window(
		onCloseRequest = ::exitApplication,
		title = "DropSauce",
		state = rememberWindowState(width = 1280.dp, height = 840.dp),
	) {
		// MaterialExpressiveTheme and MotionScheme.expressive() are the same Material 3
		// Expressive entry points :app uses in settings/compose/SettingsTheme.kt. They
		// resolve only because of the material3 pin in gradle/libs.versions.toml, so this
		// failing to compile is the signal that DECISIONS.md D6a has regressed.
		MaterialExpressiveTheme(motionScheme = MotionScheme.expressive()) {
			Surface(modifier = Modifier.fillMaxSize()) {
				Row(Modifier.fillMaxSize()) {
					NavRail(state)
					VerticalDivider()
					Box(Modifier.weight(1f)) { Router(state) }
				}
			}
		}
	}
}

@Composable
private fun NavRail(state: AppState) {
	NavigationRail {
		Spacer(Modifier.height(12.dp))
		for ((destination, item) in ROOTS) {
			val (glyph, label) = item
			NavigationRailItem(
				selected = state.root == destination,
				onClick = { state.selectRoot(destination) },
				// A glyph rather than an empty icon slot: NavigationRailItem sizes its
				// hit target around the icon, so an empty one leaves an item that is
				// awkward to click even though it renders fine.
				icon = { Text(glyph, style = MaterialTheme.typography.titleMedium) },
				label = { Text(label) },
			)
		}
	}
}

private val ROOTS: List<Pair<Screen.Root, Pair<String, String>>> = listOf(
	Screen.Library to ("\u2605" to "Library"),
	Screen.Catalog to ("\u25A6" to "Sources"),
	Screen.History to ("\u21BA" to "History"),
)

@Composable
private fun Router(state: AppState) {
	val openDetails: (MangaParserSource, Manga) -> Unit = { source, manga ->
		state.go(Screen.Details(source, manga))
	}
	when (val screen = state.current) {
		Screen.Library -> LibraryScreen(state = state, onOpen = openDetails)

		Screen.History -> HistoryScreen(state = state, onOpen = openDetails)

		Screen.Catalog -> CatalogScreen(onPick = { state.go(Screen.Browse(it)) })

		is Screen.Browse -> BrowseScreen(
			state = state,
			source = screen.source,
			onBack = state::back,
			onOpen = { openDetails(screen.source, it) },
		)

		is Screen.Details -> DetailsScreen(
			state = state,
			source = screen.source,
			seed = screen.manga,
			onBack = state::back,
			onRead = { chapters, index ->
				state.go(Screen.Reader(screen.source, screen.manga, chapters, index))
			},
		)

		is Screen.Reader -> ReaderScreen(
			state = state,
			source = screen.source,
			manga = screen.manga,
			chapters = screen.chapters,
			chapterIndex = screen.chapterIndex,
			onBack = state::back,
			// Replaces rather than pushes, so reading ten chapters does not leave ten
			// reader entries to back out through.
			onChapterChange = { index -> state.replace(screen.copy(chapterIndex = index)) },
		)
	}
}
