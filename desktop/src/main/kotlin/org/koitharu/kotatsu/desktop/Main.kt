package org.koitharu.kotatsu.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import okio.FileSystem
import org.koitharu.kotatsu.core.util.ext.DebugFlags
import org.koitharu.kotatsu.desktop.ui.AppState
import org.koitharu.kotatsu.desktop.ui.BrowseScreen
import org.koitharu.kotatsu.desktop.ui.CatalogScreen
import org.koitharu.kotatsu.desktop.ui.DetailsScreen
import org.koitharu.kotatsu.desktop.ui.ReaderScreen
import org.koitharu.kotatsu.desktop.ui.Screen
import org.koitharu.kotatsu.shared.io.XdgAppPaths

fun main() {
	// Counterpart of BaseApp setting this from BuildConfig.DEBUG on Android.
	DebugFlags.isDebug = System.getProperty("dropsauce.debug").toBoolean()
	// Created up front so a permissions or disk problem surfaces at startup rather than
	// at the first write.
	XdgAppPaths().ensureDirectories(FileSystem.SYSTEM)
	launchUi()
}

private fun launchUi() = application {
	val state = remember { AppState() }
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
				Router(state)
			}
		}
	}
}

@Composable
private fun Router(state: AppState) {
	when (val screen = state.current) {
		Screen.Catalog -> CatalogScreen(
			onPick = { state.go(Screen.Browse(it)) },
		)

		is Screen.Browse -> BrowseScreen(
			state = state,
			source = screen.source,
			onBack = state::back,
			onOpen = { state.go(Screen.Details(screen.source, it)) },
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
