package org.koitharu.kotatsu.desktop.feature.scrobbling

import androidx.compose.runtime.Composable
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** External tracking services, as a top-level destination. */
object ScrobblingFeature : Feature {

	override val id: String = "tracking"

	override val title: String = "Tracking"

	override val glyph: String = "◉"

	override val isTopLevel: Boolean = false

	override val settingsTitle: String = "Tracking services"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		TrackingServicesScreen(context)
	}
}

/**
 * Builds the whole area from the feature contract.
 *
 * One function so the wiring is in one readable place and a test can build the same
 * object graph with its own [HttpExchange] and its own directory.
 *
 * The HTTP client is this area's own. `FeatureContext.clientFor` hands out a client
 * configured for a manga source, complete with that source's headers, cookie jar and
 * parser interceptor (DECISIONS.md D1); sending any of that to AniList would be wrong,
 * and the tracking APIs need none of it.
 */
fun createTrackingRepository(
	context: FeatureContext,
	configDir: Path = context.paths.config.toNioPath(),
	http: HttpExchange = OkHttpExchange(defaultTrackingClient()),
	browser: BrowserLauncher = AwtBrowserLauncher,
	now: () -> Long = System::currentTimeMillis,
): TrackingRepository {
	val credentials = DefaultCredentialsSource(configDir)
	val tokens = TokenStore(configDir.resolve(TokenStore.FILE_NAME))
	val oauth = OAuthFlow(http = http, browser = browser)
	val sessions = TrackingService.entries.associateWith { service ->
		ServiceSession(
			service = service,
			credentials = credentials,
			tokens = tokens,
			http = http,
			oauth = oauth,
			now = now,
			unavailableText = { unavailableReason(it, configDir) },
		)
	}
	return TrackingRepository(
		db = context.db,
		sessions = sessions,
		trackers = sessions.mapValues { (_, session) -> trackerFor(session) },
		now = now,
	)
}

/**
 * A plain client with timeouts.
 *
 * No retry and no authenticator: a retry would double a progress push, and the
 * refresh-on-401 rule lives in [ServiceSession] where it is testable without a socket.
 */
internal fun defaultTrackingClient(): OkHttpClient = OkHttpClient.Builder()
	.connectTimeout(20, TimeUnit.SECONDS)
	.readTimeout(30, TimeUnit.SECONDS)
	.retryOnConnectionFailure(false)
	.build()
