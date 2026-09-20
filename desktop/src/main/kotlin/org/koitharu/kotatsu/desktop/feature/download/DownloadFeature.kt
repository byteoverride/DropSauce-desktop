package org.koitharu.kotatsu.desktop.feature.download

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import java.util.IdentityHashMap

/**
 * Downloads and offline reading.
 *
 * The one object the shell needs. [repository] is public so that screens outside this
 * area (a details screen offering "download this chapter", a reader opening a local
 * chapter) can reach the same queue instead of starting a second one.
 */
object DownloadFeature : Feature {

	override val id: String = "downloads"

	override val title: String = "Downloads"

	override val glyph: String = "⬇"

	/**
	 * One repository per context, by identity.
	 *
	 * A download queue is process-wide state: two of them against one database would
	 * both claim the same QUEUED rows and write the same files. There is one context per
	 * process, so this map holds one entry and never needs eviction; identity rather
	 * than equality because a context is a service holder, not a value.
	 */
	private val repositories = IdentityHashMap<FeatureContext, DownloadRepository>()

	/**
	 * The queue for [context], started on first use.
	 *
	 * Starting here rather than in a separate init hook is deliberate: the contract has
	 * no startup callback, and the first thing that touches downloads (the nav rail
	 * opening this screen, or a details screen queueing a chapter) is exactly when
	 * resuming an interrupted queue becomes worth doing.
	 */
	@Synchronized
	fun repository(context: FeatureContext): DownloadRepository =
		repositories.getOrPut(context) {
			DownloadRepository.create(context).also { created ->
				context.scope.launch { created.start() }
			}
		}

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		val repository = remember(context) { repository(context) }
		DownloadsScreen(context = context, navigator = navigator, repository = repository)
	}
}
