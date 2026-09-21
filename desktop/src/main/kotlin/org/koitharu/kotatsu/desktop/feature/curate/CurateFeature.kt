package org.koitharu.kotatsu.desktop.feature.curate

import androidx.compose.runtime.Composable
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator

/**
 * How the organise area plugs into the shell.
 *
 * One destination, not two. Duplicates are a view of the same library under the same
 * selection, and splitting them out would mean a user who merged a group had to navigate
 * back to act on the result.
 */
object CurateFeature : Feature {

	override val id: String = "curate"

	override val title: String = "Organise"

	override val glyph: String = "☰"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		CurateScreen(context, navigator)
	}
}
