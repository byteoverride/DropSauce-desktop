package org.koitharu.kotatsu.desktop.feature.appupdate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.parsers.util.await
import java.util.concurrent.atomic.AtomicBoolean

/** A published desktop release, reduced to what the screen needs. */
data class AppRelease(
	/** The version as written in the tag, with the `desktop-v` stripped. */
	val version: String,
	val title: String,
	/** The release page, for someone who would rather read it on the web. */
	val url: String,
	/**
	 * Direct link to the package for *this* platform, or null when the release carries
	 * one for some other platform but not this one.
	 *
	 * Nullable rather than absent, so that a release whose Windows job failed still shows
	 * up with its notes and a link, instead of Windows readers being told there is no
	 * update at all. A silent "you are up to date" is the worst of the three answers.
	 */
	val downloadUrl: String?,
	val downloadSize: Long?,
	val notes: String,
) {

	val versionId: VersionId get() = VersionId(version)
}

/**
 * Whether a newer desktop release has been published.
 *
 * Mirrors the Android app's `core/github/AppUpdateRepository`: the same releases endpoint,
 * the same "collect candidates, keep the newest, offer it only if it beats what is
 * running" shape, and the same rule that a stable build is never offered a pre-release.
 * Kept as its own copy rather than shared because the Android one is bound to `Context`
 * and to APK assets, and the two differ in exactly those two places.
 *
 * Nothing here downloads or installs anything. A desktop package is installed with root,
 * and an app that silently fetched and ran an installer would be doing something the user
 * did not ask for. The screen hands over a link.
 */
class AppUpdateRepository(
	private val client: OkHttpClient,
	private val currentVersion: String = AppBuild.version,
	private val releasesUrl: String = DEFAULT_RELEASES_URL,
	/** Injected so the Windows behaviour can be tested from Linux, which is where it broke. */
	private val hostPackage: HostPackage = HostPackage.forHost(),
) {

	private val available = MutableStateFlow<AppRelease?>(null)

	/** Set once a check has run, so the answer can distinguish "none" from "not yet". */
	private val checked = MutableStateFlow(false)

	private val checkedThisLaunch = AtomicBoolean(false)

	fun observeAvailableUpdate(): StateFlow<AppRelease?> = available.asStateFlow()

	fun observeChecked(): StateFlow<Boolean> = checked.asStateFlow()

	/**
	 * Checks once per launch, which is what the Android app does.
	 *
	 * Called when the navigation rail first observes the badge, so the check happens
	 * because the window opened rather than on a timer. DECISIONS.md D11 rules out
	 * scheduling on desktop, and one request per launch is the honest reading of that.
	 */
	suspend fun refreshOnce(): AppRelease? {
		if (!checkedThisLaunch.compareAndSet(false, true)) {
			return available.value
		}
		return fetchUpdate()
	}

    /**
     * Asks GitHub now, whatever has happened before. This is the refresh button.
     *
     * Never throws for a network failure. An update check is not something the user
     * asked for at startup, so it must not be able to take the window down with it; the
     * screen reports the failure from [observeChecked] and the stored result.
     */
	suspend fun fetchUpdate(): AppRelease? = withContext(Dispatchers.IO) {
		val newest = try {
			val current = VersionId(currentVersion)
			val candidates = availableReleases()
			candidates
				// A stable build is never nudged onto a pre-release. Someone running an rc
				// already opted in and keeps seeing them.
				.filter { !current.isStable || it.versionId.isStable }
				.maxByOrNull { it.versionId }
				?.takeIf { it.versionId > current }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			// Offline, rate limited, GitHub down. All the same from here: say nothing.
			null
		}
		available.value = newest
		checked.value = true
		newest
	}

	/** Every published desktop release that carries a package, newest first. */
	suspend fun availableReleases(): List<AppRelease> = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.get()
			.url(releasesUrl)
			// GitHub asks for both. Without the User-Agent it answers 403.
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "DropSauce-desktop/${AppBuild.version}")
			.build()
		val body = client.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				throw IllegalStateException("GitHub answered ${response.code}")
			}
			response.body.string()
		}
		JSON.decodeFromString<List<ReleaseJson>>(body)
			.asSequence()
			.filter { !it.draft && it.tagName.startsWith(DESKTOP_TAG_PREFIX) }
			.mapNotNull { release ->
				// A release with no package at all is an announcement, not something to
				// install. One that has packages but not ours is still worth showing.
				if (release.assets.none { HostPackage.isPackage(it.name) }) return@mapNotNull null
				val asset = release.assets.firstOrNull { it.name.endsWith(hostPackage.suffix) }
				AppRelease(
					version = release.tagName.removePrefix(DESKTOP_TAG_PREFIX),
					title = release.name?.takeIf { it.isNotBlank() } ?: release.tagName,
					url = release.htmlUrl,
					downloadUrl = asset?.browserDownloadUrl,
					downloadSize = asset?.size,
					notes = release.body.orEmpty().trim(),
				)
			}
			.sortedByDescending { it.versionId }
			.toList()
	}

	@Serializable
	private data class ReleaseJson(
		@SerialName("tag_name") val tagName: String = "",
		@SerialName("name") val name: String? = null,
		@SerialName("html_url") val htmlUrl: String = "",
		@SerialName("body") val body: String? = null,
		@SerialName("draft") val draft: Boolean = false,
		@SerialName("assets") val assets: List<AssetJson> = emptyList(),
	)

	@Serializable
	private data class AssetJson(
		@SerialName("name") val name: String = "",
		@SerialName("browser_download_url") val browserDownloadUrl: String = "",
		@SerialName("size") val size: Long = 0L,
	)

	companion object {

		/**
		 * Ten is plenty: the newest desktop release is near the top, and asking for more
		 * pages to find an older one would be work for an answer nobody wants.
		 */
		const val DEFAULT_RELEASES_URL =
			"https://api.github.com/repos/byteoverride/DropSauce-desktop/releases?page=1&per_page=10"

		private val JSON = Json { ignoreUnknownKeys = true }
	}
}
