package org.koitharu.kotatsu.desktop.feature.suggestions

import androidx.compose.runtime.Composable
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator

/**
 * Recommendations built from the library, each one carrying its own reasoning.
 *
 * Manual refresh only. There is no scheduler and no background work, per DECISIONS.md
 * D11, so nothing here runs unless the reader asks for it.
 */
object SuggestionsFeature : Feature {

	override val id: String = "suggestions"

	override val title: String = "For you"

	override val glyph: String = "✦"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		SuggestionsRoot(context, navigator)
	}
}
