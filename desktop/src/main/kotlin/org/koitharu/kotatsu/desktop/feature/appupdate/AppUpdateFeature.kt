package org.koitharu.kotatsu.desktop.feature.appupdate

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import java.util.WeakHashMap

/**
 * Tells the reader when a newer build has been published.
 *
 * Called "App update" and not "Updates", which this app already uses for new chapters in
 * the library. Two things called updates in one navigation rail would be a worse problem
 * than an ungainly label.
 */
object AppUpdateFeature : Feature {

	override val id: String = "appupdate"

	override val title: String = "App update"

	/** Upwards arrow: the thing you do with a newer version. */
	override val glyph: String = "↑"

	/**
	 * On the rail, unlike the other tools.
	 *
	 * Its dot is the only one that means "go and do something you cannot discover any
	 * other way". Buried in Settings it would surface on the Settings dot along with
	 * everything else, which says something needs attention without saying what.
	 */
	override val isTopLevel: Boolean = true

	/**
	 * One repository per shell.
	 *
	 * The rail's dot and the screen have to observe the same state, and the contract
	 * hands out a [FeatureContext] rather than an instance, so the area keeps its own.
	 * Weak because the object outlives any one shell and must not pin a closed one; in
	 * practice there is exactly one for the life of the process.
	 */
	private val repositories = WeakHashMap<FeatureContext, AppUpdateRepository>()

	@Synchronized
	private fun repository(context: FeatureContext): AppUpdateRepository =
		repositories.getOrPut(context) { AppUpdateRepository(context.httpClient) }

	/**
	 * The dot, and the thing that starts the only check the app makes.
	 *
	 * The rail collects this for the life of the window, so [AppUpdateRepository.refreshOnce]
	 * running here means the check happens because the app opened. That matches the
	 * Android app, which checks once per launch, and it keeps desktop clear of the
	 * scheduling DECISIONS.md D11 rules out.
	 */
	override fun badge(context: FeatureContext): Flow<Int> {
		val repository = repository(context)
		return repository.observeAvailableUpdate()
			.onStart { repository.refreshOnce() }
			// One, or none. There is only ever a newest release, so a larger number here
			// would be inventing a quantity to fill a space.
			.map { if (it == null) 0 else 1 }
	}

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		AppUpdateScreen(repository(context))
	}
}
