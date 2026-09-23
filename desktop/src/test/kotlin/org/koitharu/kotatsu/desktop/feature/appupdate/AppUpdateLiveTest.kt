package org.koitharu.kotatsu.desktop.feature.appupdate

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The check against the real releases feed.
 *
 * Gated behind `-Dlive=true` like the parser tests, so GitHub being down or rate limiting
 * this machine cannot fail an ordinary build. Worth having anyway: the fake in
 * [AppUpdateTest] asserts what this code does with the JSON it expects, and only this one
 * can catch the feed not looking like that any more.
 */
class AppUpdateLiveTest {

	@BeforeTest
	fun requireLive() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
	}

	@Test
	fun `the real feed yields a usable release`() = runBlocking {
		// Both platforms against the real feed, because the bug was that one of them was
		// never exercised: the check looked for a .deb whatever it was running on.
		for (host in HostPackage.entries) {
			val releases = AppUpdateRepository(OkHttpClient(), hostPackage = host).availableReleases()
			assertTrue(releases.isNotEmpty(), "no desktop release carrying a package was found")
			val newest = releases.first()
			val url = newest.downloadUrl
			assertNotNull(url, "the newest release has no ${host.suffix}")
			assertTrue(url.endsWith(host.suffix), "$host was given $url")
			assertTrue((newest.downloadSize ?: 0) > 1_000_000, "suspiciously small: ${newest.downloadSize}")
			// Sorted newest first, so nothing later may outrank the head.
			assertTrue(releases.all { it.versionId <= newest.versionId })
			println("LIVE $host newest=${newest.version} size=${newest.downloadSize} url=$url")
		}
	}

	@Test
	fun `an old build is told there is something newer`() = runBlocking {
		val found = AppUpdateRepository(OkHttpClient(), currentVersion = "0.0.1").fetchUpdate()
		assertNotNull(found, "a 0.0.1 build should be offered every published release")
		println("LIVE offered ${found.version} to a 0.0.1 build")
	}

	@Test
	fun `a build from the future is offered nothing`() = runBlocking {
		val found = AppUpdateRepository(OkHttpClient(), currentVersion = "999.0.0").fetchUpdate()
		assertTrue(found == null, "a future build was offered ${found?.version}")
	}
}
