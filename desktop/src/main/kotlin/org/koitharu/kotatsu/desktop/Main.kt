package org.koitharu.kotatsu.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.rememberWindowState
import org.koitharu.kotatsu.core.util.ext.DebugFlags
import org.koitharu.kotatsu.desktop.ui.AppState
import org.koitharu.kotatsu.desktop.ui.BrowseScreen
import org.koitharu.kotatsu.desktop.ui.CatalogScreen
import org.koitharu.kotatsu.desktop.ui.DetailsScreen
import org.koitharu.kotatsu.desktop.ui.HistoryScreen
import org.koitharu.kotatsu.desktop.ui.LibraryScreen
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.feature.migration.EntryHealth
import org.koitharu.kotatsu.desktop.feature.migration.LibraryEntry
import org.koitharu.kotatsu.desktop.feature.migration.MigrationScreen
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LocalPageSource
import org.koitharu.kotatsu.desktop.ui.RemotePageSource
import org.koitharu.kotatsu.desktop.ui.SettingsScreen
import org.koitharu.kotatsu.desktop.ui.createAppState
import org.koitharu.kotatsu.shared.settings.ThemeMode
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
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
		// The .desktop entry's icon only covers the launcher. A window carries its own,
		// and without one the running app shows the toolkit default in the taskbar and
		// the alt-tab switcher. Same file the package is built from.
		icon = painterResource("dropsauce.png"),
		state = rememberWindowState(width = 1280.dp, height = 840.dp),
	) {
		// MaterialExpressiveTheme and MotionScheme.expressive() are the same Material 3
		// Expressive entry points :app uses in settings/compose/SettingsTheme.kt. They
		// resolve only because of the material3 pin in gradle/libs.versions.toml, so this
		// failing to compile is the signal that DECISIONS.md D6a has regressed.
		val settings by state.settings.data.collectAsState()
		val dark = when (settings.theme) {
			ThemeMode.System -> isSystemInDarkTheme()
			ThemeMode.Light -> false
			ThemeMode.Dark -> true
		}
		// Scaling density rather than font size alone, so icons, padding and touch
		// targets grow with the text instead of text outgrowing its containers.
		val base = LocalDensity.current
		val scaled = Density(base.density * settings.uiScale, base.fontScale)
		CompositionLocalProvider(LocalDensity provides scaled) {
		MaterialExpressiveTheme(
			colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
			motionScheme = MotionScheme.expressive(),
		) {
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
}

/**
 * Bridges a feature area's navigation requests onto the shell's stack.
 *
 * Features get this rather than [AppState] so they cannot reach screens that are not
 * theirs, which is what keeps areas independent of each other.
 */
@Composable
private fun rememberNavigator(state: AppState): FeatureNavigator = remember(state) {
	object : FeatureNavigator {
		override fun openDetails(source: MangaParserSource, manga: Manga) {
			state.go(Screen.Details(source, manga))
		}

		override fun openReader(
			source: MangaParserSource,
			manga: Manga,
			chapters: List<MangaChapter>,
			chapterIndex: Int,
			page: Int,
		) {
			state.go(Screen.Reader(source, manga, chapters, chapterIndex, page))
		}

		override fun openLocalReader(
			manga: Manga,
			chapters: List<MangaChapter>,
			chapterIndex: Int,
			page: Int,
		) {
			// A local comic's source can never be a MangaParserSource, which is a closed
			// enum, so the earlier cast here always failed and clicking a local comic
			// silently did nothing. Local reading has its own screen for that reason.
			state.go(Screen.LocalReader(manga, chapters, chapterIndex, page))
		}

		override fun back() = state.back()
	}
}

@Composable
private fun NavRail(state: AppState) {
	NavigationRail {
		Spacer(Modifier.height(12.dp))
		// The tools live inside Settings now, so their dot has to surface here or it is
		// invisible until someone happens to open Settings.
		val toolsBadged by remember(state) { state.toolsBadge() }.collectAsState(0)
		for ((destination, item) in ROOTS) {
			val (glyph, label) = item
			NavigationRailItem(
				selected = state.root == destination,
				onClick = { state.selectRoot(destination) },
				// A glyph rather than an empty icon slot: NavigationRailItem sizes its
				// hit target around the icon, so an empty one leaves an item that is
				// awkward to click even though it renders fine.
				icon = {
					BadgedBox(
						badge = {
							// A plain dot for Settings: it stands in for several areas at
							// once, and a number summed across them would be adding up
							// things that have nothing to do with each other.
							if (destination == Screen.Settings && toolsBadged > 0) Badge()
						},
					) {
						Text(glyph, style = MaterialTheme.typography.titleMedium)
					}
				},
				label = { Text(label) },
			)
		}
		// Feature areas append themselves, so integrating one is a list entry rather
		// than an edit to this rail.
		for (feature in state.features.filter { it.isTopLevel }) {
			val destination = Screen.FeatureRoot(feature.id)
			// Collected for as long as the window is open, which is also what lets an
			// area do the work that answers the question. Most areas never emit true.
			val badged by remember(feature) { feature.badge(state.featureContext) }
				.collectAsState(0)
			NavigationRailItem(
				selected = state.root == destination,
				onClick = { state.selectRoot(destination) },
				icon = {
					BadgedBox(
						badge = {
							// The number, not a dot. "Something is new" and "twelve things
							// are new" decide different things. Capped in the text rather
							// than the value, so a big number cannot widen the rail.
							if (badged > 0) {
								Badge { Text(if (badged > 99) "99+" else badged.toString()) }
							}
						},
					) {
						Text(feature.glyph, style = MaterialTheme.typography.titleMedium)
					}
				},
				label = { Text(feature.title) },
			)
		}
	}
}

/** A slim way out of a tool that was opened from Settings. */
@Composable
private fun BackToSettings(onBack: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text("\u2039  Settings", style = MaterialTheme.typography.labelLarge)
	}
}

private val ROOTS: List<Pair<Screen.Root, Pair<String, String>>> = listOf(
	Screen.Library to ("\u2605" to "Library"),
	Screen.Catalog to ("\u25A6" to "Sources"),
	Screen.History to ("\u21BA" to "History"),
	Screen.Settings to ("\u2699" to "Settings"),
)

@Composable
private fun Router(state: AppState) {
	val openDetails: (MangaParserSource, Manga) -> Unit = { source, manga ->
		state.go(Screen.Details(source, manga))
	}
	when (val screen = state.current) {
		Screen.Library -> LibraryScreen(state = state, onOpen = openDetails)

		Screen.History -> HistoryScreen(state = state, onOpen = openDetails)

		Screen.Catalog -> CatalogScreen(
			sources = state.visibleSources,
			totalUsable = state.usableSourceCount,
			onPick = { state.go(Screen.Browse(it)) },
		)

		Screen.Settings -> SettingsScreen(state)

		is Screen.FeatureRoot -> {
			val feature = state.feature(screen.id)
			if (feature == null) {
				// Only reachable if a feature is removed while its screen is open.
				ErrorBox("This section is not available.", onRetry = { state.selectRoot(Screen.Library) })
			} else {
				Column(Modifier.fillMaxSize()) {
					// A tool opened from Settings is pushed onto the stack rather than
					// made the root, so that Settings stays highlighted and there is
					// somewhere to go back to. The shell provides the way back because an
					// area cannot know whether it was reached from the rail or from
					// inside Settings, and the ones in Settings have no rail slot to
					// click their way out of.
					if (state.canGoBack) {
						BackToSettings(onBack = state::back)
					}
					feature.Content(state.featureContext, rememberNavigator(state))
				}
			}
		}

		is Screen.FindAlternative -> MigrationScreen(
			context = state.featureContext,
			entry = LibraryEntry(
				manga = screen.manga,
				sourceName = screen.source.name,
				source = screen.source,
				// The source resolves and is not flagged: its only fault is having
				// nothing to read, which no scan of the library can detect.
				health = EntryHealth.OK,
				isFavourite = false,
				hasHistory = false,
			),
			onBack = state::back,
			onMigrated = {
				// Twice: the details screen underneath is showing the title that just
				// moved, so returning to it would show an entry whose favourites and
				// history now belong to something else.
				state.back()
				state.back()
			},
		)

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
			onRead = { chapters, index, page ->
				state.go(Screen.Reader(screen.source, screen.manga, chapters, index, page))
			},
			onOpenRelated = { source, manga -> state.go(Screen.Details(source, manga)) },
			onFindAlternative = { loaded -> state.go(Screen.FindAlternative(screen.source, loaded)) },
		)

		is Screen.LocalReader -> ReaderScreen(
			state = state,
			pageSource = remember { LocalPageSource() },
			sourceLabel = "Local",
			manga = screen.manga,
			chapters = screen.chapters,
			chapterIndex = screen.chapterIndex,
			initialPage = screen.initialPage,
			onBack = state::back,
			onChapterChange = { index, page ->
				state.replace(screen.copy(chapterIndex = index, initialPage = page))
			},
		)

		is Screen.Reader -> ReaderScreen(
			state = state,
			pageSource = remember(screen.source) { RemotePageSource(state, screen.source) },
			sourceLabel = screen.source.title,
			manga = screen.manga,
			chapters = screen.chapters,
			chapterIndex = screen.chapterIndex,
			initialPage = screen.initialPage,
			onBack = state::back,
			// Replaces rather than pushes, so reading ten chapters does not leave ten
			// reader entries to back out through.
			onChapterChange = { index, page ->
				state.replace(screen.copy(chapterIndex = index, initialPage = page))
			},
			onFindAlternative = { state.go(Screen.FindAlternative(screen.source, screen.manga)) },
		)
	}
}
