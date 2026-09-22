package org.koitharu.kotatsu.desktop.feature.migration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator

/**
 * How the migration area plugs into the shell.
 *
 * The broken-sources list is the entry point rather than the single-title screen, because
 * migrating is almost never something a reader sets out to do: they arrive because a
 * title stopped opening, and the useful first question is "which of mine are affected".
 * Picking one entry from that list opens the single-title screen.
 *
 * Navigation between the area's two screens is held here rather than handed to
 * [FeatureNavigator], which deliberately cannot address arbitrary screens.
 */
object MigrationFeature : Feature {

	override val id: String = "migration"

	override val title: String = "Migrate"

	/** Rightwards arrow with corners: moving a thing from one place to another. */
	override val glyph: String = "⇄"

	override val isTopLevel: Boolean = false

	override val settingsTitle: String = "Fix broken sources"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		var selected: LibraryEntry? by remember(context) { mutableStateOf(null) }
		// Bumped after a migration so the list behind re-scans; the entry that was just
		// repaired must not still be sitting there looking broken.
		var reloadKey by remember(context) { mutableStateOf(0) }
		val entry = selected
		if (entry == null) {
			BrokenSourcesScreen(
				context = context,
				onOpenEntry = { selected = it },
				reloadKey = reloadKey,
			)
		} else {
			MigrationScreen(
				context = context,
				entry = entry,
				onBack = { selected = null },
				onMigrated = {
					reloadKey++
					selected = null
				},
			)
		}
	}
}
