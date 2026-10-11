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
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.ui.AppIcon
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.AppNavRail
import org.koitharu.kotatsu.desktop.ui.AppState
import org.koitharu.kotatsu.desktop.ui.DropSauceDarkColors
import org.koitharu.kotatsu.desktop.ui.DropSauceLightColors
import org.koitharu.kotatsu.desktop.ui.NavDestination
import org.koitharu.kotatsu.desktop.ui.NavGroup
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
import org.koitharu.kotatsu.desktop.feature.appupdate.AppBuild
import org.koitharu.kotatsu.desktop.ui.Screen
import org.koitharu.kotatsu.shared.io.defaultAppPaths

fun main() {
	// Counterpart of BaseApp setting this from BuildConfig.DEBUG on Android.
	DebugFlags.isDebug = System.getProperty("dropsauce.debug").toBoolean()
	// Before anything else, so a failure while opening the database is recorded too.
	// A packaged app has no console on Windows at all, so without this a crash leaves
	// the reader with nothing to report and nothing to read.
	CrashLog(defaultAppPaths().data / "dropsauce.log").install(AppBuild.version)
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
			colorScheme = if (dark) DropSauceDarkColors else DropSauceLightColors,
			motionScheme = MotionScheme.expressive(),
		) {
			Surface(modifier = Modifier.fillMaxSize()) {
				// The reader gets the whole window. The rail is how you get anywhere in
				// this app, so hiding it is not free, but a reading surface permanently
				// inset by 120 px of chrome in a reading app costs more. D26 governs
				// what belongs ON the rail, not whether it is drawn over a screen that
				// was pushed onto the stack; the reader has its own Back, and Escape
				// leaves. Recorded as a revision in DECISIONS.md.
				val immersive = state.current.let { it is Screen.Reader || it is Screen.LocalReader }
				Row(Modifier.fillMaxSize()) {
					if (!immersive) {
						AppShellRail(state)
						VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
					}
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
private fun AppShellRail(state: AppState) {
	// The tools live inside Settings, so their dot has to surface here or it is
	// invisible until someone happens to open Settings.
	val toolsBadged by remember(state) { state.toolsBadge() }.collectAsState(0)
	val features = state.features.filter { it.isTopLevel }
	val featureBadges = features.map { feature ->
		// Collected for as long as the window is open, which is also what lets an area
		// do the work that answers the question. Most areas never emit anything.
		feature.id to remember(feature) { feature.badge(state.featureContext) }.collectAsState(0).value
	}.toMap()
	val destinations = buildList {
		add(NavDestination(Screen.Library, "Library", AppIcons.LIBRARY, NavGroup.Read))
		for (feature in features) {
			if (feature.id in READ_GROUP) {
				add(featureDestination(feature, NavGroup.Read, featureBadges[feature.id] ?: 0))
			}
		}
		add(NavDestination(Screen.History, "History", AppIcons.HISTORY, NavGroup.Read))
		add(NavDestination(Screen.Catalog, "Sources", AppIcons.SOURCES, NavGroup.Find))
		for (feature in features) {
			if (feature.id in FIND_GROUP) {
				add(featureDestination(feature, NavGroup.Find, featureBadges[feature.id] ?: 0))
			}
		}
		for (feature in features) {
			if (feature.id !in READ_GROUP && feature.id !in FIND_GROUP) {
				add(featureDestination(feature, NavGroup.KeepUp, featureBadges[feature.id] ?: 0))
			}
		}
		add(
			NavDestination(
				key = Screen.Settings,
				label = "Settings",
				icon = AppIcons.SETTINGS,
				group = NavGroup.System,
				badge = toolsBadged,
				// A dot rather than a number: it stands in for several areas at once,
				// and a sum across them would be adding up unrelated things.
				badgeAsDot = true,
			),
		)
	}
	AppNavRail(
		destinations = destinations,
		selectedKey = state.root,
		onSelect = { state.selectRoot(it.key as Screen.Root) },
	)
}

private fun featureDestination(feature: Feature, group: NavGroup, badge: Int) = NavDestination(
	key = Screen.FeatureRoot(feature.id),
	label = feature.title,
	icon = feature.icon,
	group = group,
	badge = badge,
)

/** Areas that are things you already have. Anything unlisted is something that changed. */
private val READ_GROUP = setOf("bookmarks", "local", "downloads")

/** Areas that are things you do not have yet. */
private val FIND_GROUP = setOf("discover", "suggestions")

/** A slim way out of a tool that was opened from Settings. */
@Composable
private fun BackToSettings(onBack: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		AppIcon(AppIcons.CHEVRON_LEFT, contentDescription = null)
		Text("Settings", style = MaterialTheme.typography.labelLarge)
	}
}


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
