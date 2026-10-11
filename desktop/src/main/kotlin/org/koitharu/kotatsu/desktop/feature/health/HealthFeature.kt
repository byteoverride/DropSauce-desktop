package org.koitharu.kotatsu.desktop.feature.health

import androidx.compose.runtime.Composable
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.ui.AppIcons

/**
 * Checking which sources still answer, and keeping the dead ones out of the catalogue.
 *
 * A tool rather than a rail destination (D26): it is run occasionally, and the result is
 * visible everywhere else as a shorter, more honest source list rather than as a screen
 * anyone needs to keep open.
 */
object HealthFeature : Feature {

	override val id: String = "health"

	override val title: String = "Source health"

	override val icon: String = AppIcons.REFRESH

	override val isTopLevel: Boolean = false

	override val settingsTitle: String = "Check source health"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		HealthScreen(context, navigator)
	}
}
