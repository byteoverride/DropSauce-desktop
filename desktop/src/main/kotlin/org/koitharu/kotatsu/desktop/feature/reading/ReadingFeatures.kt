package org.koitharu.kotatsu.desktop.feature.reading

import androidx.compose.runtime.Composable
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator

/**
 * How the reading area plugs into the shell.
 *
 * Two objects rather than one, because bookmarks and statistics are two navigation
 * destinations even though they share a source directory and both read what the reader
 * writes.
 */
object BookmarksFeature : Feature {

	override val id: String = "bookmarks"

	override val title: String = "Bookmarks"

	override val glyph: String = "\u2605"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		BookmarksScreen(context, navigator)
	}
}

object StatsFeature : Feature {

	override val id: String = "stats"

	override val title: String = "Statistics"

	override val glyph: String = "\u25A6"

	override val isTopLevel: Boolean = false

	override val settingsTitle: String = "Reading statistics"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		StatsScreen(context)
	}
}
