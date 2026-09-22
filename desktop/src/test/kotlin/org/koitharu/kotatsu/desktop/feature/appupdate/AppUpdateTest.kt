package org.koitharu.kotatsu.desktop.feature.appupdate

import kotlinx.coroutines.runBlocking
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The update check, against a fake GitHub.
 *
 * The fake is the JDK's own `com.sun.net.httpserver`, not MockWebServer, which is not on
 * this classpath and is not worth a dependency for one test. It still drives the real
 * OkHttp path, so the headers GitHub insists on are actually asserted rather than assumed.
 *
 * Every assertion here is about a way the check can quietly stop working: a version
 * ordering that looks right until a number reaches ten, an Android release offered to the
 * desktop app, a release with no package, and a network failure that must not escape.
 */
class AppUpdateTest {

	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	private val client = OkHttpClient()

	/** Bodies to answer with, in order. */
	private val bodies = ConcurrentLinkedQueue<Pair<Int, String>>()
	private val requests = ConcurrentLinkedQueue<Map<String, String>>()
	private val requestCount = AtomicInteger(0)

	init {
		server.createContext("/releases") { exchange: HttpExchange ->
			requestCount.incrementAndGet()
			requests += exchange.requestHeaders.entries.associate {
				it.key.lowercase() to it.value.first()
			}
			val (code, body) = bodies.poll() ?: (500 to "no response queued")
			val bytes = body.toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(code, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	@AfterTest
	fun tearDown() = server.stop(0)

	// The bug this whole type exists to prevent. As text, "0.9.10" < "0.9.9".
	@Test
	fun `ten is newer than nine, which string ordering gets wrong`() {
		assertTrue(VersionId("0.9.10") > VersionId("0.9.9"))
		assertTrue("0.9.10" < "0.9.9", "the naive comparison really is the wrong way round")
		assertTrue(VersionId("1.0.0") > VersionId("0.99.99"))
		assertTrue(VersionId("0.10.0") > VersionId("0.9.99"))
	}

	@Test
	fun `a release outranks its own pre-releases`() {
		assertTrue(VersionId("1.0.0") > VersionId("1.0.0-rc2"))
		assertTrue(VersionId("1.0.0-rc2") > VersionId("1.0.0-rc1"))
		assertTrue(VersionId("1.0.0-rc1") > VersionId("1.0.0-beta3"))
		assertTrue(VersionId("1.0.0-beta1") > VersionId("1.0.0-alpha9"))
	}

	@Test
	fun `tags parse whether or not they carry the desktop prefix`() {
		assertEquals(VersionId("0.9.9"), VersionId("desktop-v0.9.9"))
		assertEquals(VersionId("0.9.9"), VersionId("v0.9.9"))
		// Nothing usable must not blow up, and must not look newer than a real version.
		assertTrue(VersionId("not a version") < VersionId("0.0.1"))
	}

	@Test
	fun `a newer release is offered`() = runBlocking {
		enqueueJson(release("desktop-v0.9.9", "dropsauce_0.9.9_amd64.deb"))
		val found = repository(current = "0.9.8").fetchUpdate()
		assertEquals("0.9.9", found?.version)
		assertEquals(66_000_000L, found?.downloadSize)
	}

	@Test
	fun `the running version is not offered to itself`() = runBlocking {
		enqueueJson(release("desktop-v0.9.9", "dropsauce_0.9.9_amd64.deb"))
		assertNull(repository(current = "0.9.9").fetchUpdate())
	}

	@Test
	fun `an older release is not offered`() = runBlocking {
		enqueueJson(release("desktop-v0.9.1", "dropsauce_0.9.1_amd64.deb"))
		assertNull(repository(current = "0.9.9").fetchUpdate())
	}

	// The repository carries the Android project's tags too. Offering an APK release as
	// a desktop update would send someone to a file they cannot install.
	@Test
	fun `an Android release in the same repository is ignored`() = runBlocking {
		enqueueJson(
			release("v2.5.0", "DropSauce-v2.5.0.apk"),
			release("desktop-v0.9.9", "dropsauce_0.9.9_amd64.deb"),
		)
		assertEquals("0.9.9", repository(current = "0.9.8").fetchUpdate()?.version)
	}

	@Test
	fun `a release with no package is not an update`() = runBlocking {
		enqueueJson(release("desktop-v1.0.0", assetName = null))
		assertNull(repository(current = "0.9.8").fetchUpdate())
	}

	@Test
	fun `a draft is not published`() = runBlocking {
		enqueueJson(release("desktop-v1.0.0", "dropsauce_1.0.0_amd64.deb", draft = true))
		assertNull(repository(current = "0.9.8").fetchUpdate())
	}

	@Test
	fun `a stable build is not nudged onto a pre-release`() = runBlocking {
		enqueueJson(
			release("desktop-v1.0.0-rc1", "dropsauce_1.0.0_amd64.deb"),
			release("desktop-v0.9.9", "dropsauce_0.9.9_amd64.deb"),
		)
		assertEquals("0.9.9", repository(current = "0.9.8").fetchUpdate()?.version)
		// Someone already running an rc keeps seeing them.
		enqueueJson(release("desktop-v1.0.0-rc2", "dropsauce_1.0.0_amd64.deb"))
		assertEquals("1.0.0-rc2", repository(current = "1.0.0-rc1").fetchUpdate()?.version)
	}

	@Test
	fun `the newest of several is chosen, not the first listed`() = runBlocking {
		enqueueJson(
			release("desktop-v0.9.9", "dropsauce_0.9.9_amd64.deb"),
			release("desktop-v0.9.10", "dropsauce_0.9.10_amd64.deb"),
			release("desktop-v0.9.2", "dropsauce_0.9.2_amd64.deb"),
		)
		assertEquals("0.9.10", repository(current = "0.9.8").fetchUpdate()?.version)
	}

	// Offline, rate limited, GitHub down. A check nobody asked for must not throw into
	// a coroutine the navigation rail is collecting.
	@Test
	fun `a server error is swallowed rather than thrown`() = runBlocking {
		enqueue(403, "rate limited")
		assertNull(repository(current = "0.9.8").fetchUpdate())
	}

	@Test
	fun `nonsense json is swallowed rather than thrown`() = runBlocking {
		enqueue(200, "<html>not json</html>")
		assertNull(repository(current = "0.9.8").fetchUpdate())
	}

	@Test
	fun `the check runs once per launch however often it is asked`() = runBlocking {
		enqueueJson(release("desktop-v0.9.9", "dropsauce_0.9.9_amd64.deb"))
		val repository = repository(current = "0.9.8")
		repeat(4) { repository.refreshOnce() }
		assertEquals(1, requestCount.get())
	}

	@Test
	fun `github is sent the headers it requires`() = runBlocking {
		enqueueJson(release("desktop-v0.9.9", "dropsauce_0.9.9_amd64.deb"))
		repository(current = "0.9.8").fetchUpdate()
		val headers = requests.poll()!!
		assertEquals("application/vnd.github+json", headers["accept"])
		// GitHub answers 403 to a request with no User-Agent.
		assertTrue(headers["user-agent"].orEmpty().startsWith("DropSauce-desktop/"))
	}

	private fun repository(current: String) = AppUpdateRepository(
		client = client,
		currentVersion = current,
		releasesUrl = "http://127.0.0.1:${server.address.port}/releases",
	)

	private fun enqueue(code: Int, body: String) {
		bodies += code to body
	}

	private fun enqueueJson(vararg releases: String) =
		enqueue(200, releases.joinToString(",", "[", "]"))

	private fun release(tag: String, assetName: String? = null, draft: Boolean = false): String {
		val assets = if (assetName == null) {
			"[]"
		} else {
			"""[{"name":"$assetName","browser_download_url":"https://example.test/$assetName","size":66000000}]"""
		}
		return """
			{"tag_name":"$tag","name":"Desktop $tag","html_url":"https://example.test/$tag",
			 "body":"notes","draft":$draft,"assets":$assets}
		""".trimIndent()
	}
}
