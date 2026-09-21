package org.koitharu.kotatsu.desktop.feature.scrobbling

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An [HttpExchange] that records every call and answers from a script.
 *
 * This is the whole reason [HttpExchange] exists as an interface. Nothing in these tests
 * reaches the network; the only real socket anywhere is the loopback listener, which is
 * the part that has to be exercised for real to be worth testing at all.
 */
class ScriptedHttp(
	var handler: (HttpRequest) -> HttpResponse = { HttpResponse(404, "{}") },
) : HttpExchange {

	private val recorded = mutableListOf<HttpRequest>()

	val requests: List<HttpRequest> get() = synchronized(recorded) { recorded.toList() }

	fun requestsTo(url: String): List<HttpRequest> = requests.filter { it.url == url }

	override suspend fun execute(request: HttpRequest): HttpResponse {
		synchronized(recorded) { recorded += request }
		return handler(request)
	}
}

/** The form fields of the one request that was sent, as a map. */
fun HttpRequest.formFields(): Map<String, String> =
	(body as? HttpBody.Form)?.fields?.toMap() ?: emptyMap()

/**
 * A [BrowserLauncher] that plays the part of the user's browser.
 *
 * It reads the `redirect_uri` out of the authorize URL and calls it back with whatever
 * [params] produces, on another thread. Another thread matters: the loopback listener has
 * not started accepting when [open] is called, so a redirect performed inline would block
 * on the backlog and deadlock the flow it is meant to complete.
 */
class FakeBrowser(
	private val params: (state: String?) -> Map<String, String>,
) : BrowserLauncher {

	private val done = CountDownLatch(1)

	@Volatile
	var openedUrl: String? = null
		private set

	/** The HTML the listener served back, so a test can check the user sees something useful. */
	@Volatile
	var receivedPage: String? = null
		private set

	@Volatile
	var failure: Throwable? = null
		private set

	override fun open(url: String) {
		openedUrl = url
		val parsed = url.toHttpUrl()
		val redirectUri = requireNotNull(parsed.queryParameter("redirect_uri")) {
			"The authorize URL carried no redirect_uri"
		}
		val state = parsed.queryParameter("state")
		thread(name = "fake-browser", isDaemon = true) {
			try {
				val target = redirectUri.toHttpUrl().newBuilder().apply {
					for ((name, value) in params(state)) {
						addQueryParameter(name, value)
					}
				}.build()
				val connection = URI(target.toString()).toURL().openConnection() as HttpURLConnection
				connection.requestMethod = "GET"
				connection.connectTimeout = 5_000
				connection.readTimeout = 5_000
				receivedPage = try {
					connection.inputStream.bufferedReader().use { it.readText() }
				} catch (e: IOException) {
					connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
				}
			} catch (e: Throwable) {
				failure = e
			} finally {
				done.countDown()
			}
		}
	}

	/** Waits for the fake browser thread, so an assertion about the page is not a race. */
	fun awaitCallback(timeoutMillis: Long = 5_000) {
		done.await(timeoutMillis, TimeUnit.MILLISECONDS)
	}
}

/** A browser that opens nothing, for the case where the user never completes the sign-in. */
object SilentBrowser : BrowserLauncher {

	override fun open(url: String) = Unit
}

/** A browser that must never be called. Fails loudly if it is. */
object ForbiddenBrowser : BrowserLauncher {

	override fun open(url: String): Unit = throw AssertionError("A browser was opened: $url")
}

fun credentials(
	vararg entries: Pair<TrackingService, ServiceCredentials?>,
): CredentialsSource {
	val map = entries.toMap()
	return CredentialsSource { service -> map[service] }
}

fun testCredentials(secret: String? = "cs") = ServiceCredentials(clientId = "ci", clientSecret = secret)

/** A session wired entirely from fakes, with a token file under [dir]. */
fun testSession(
	service: TrackingService,
	dir: Path,
	http: ScriptedHttp,
	credentialsSource: CredentialsSource,
	browser: BrowserLauncher = ForbiddenBrowser,
	now: () -> Long,
): ServiceSession {
	val tokens = TokenStore(dir.resolve(TokenStore.FILE_NAME))
	return ServiceSession(
		service = service,
		credentials = credentialsSource,
		tokens = tokens,
		http = http,
		oauth = OAuthFlow(http = http, browser = browser),
		now = now,
		unavailableText = { "${it.label} needs a client id." },
	)
}

fun testConfig(
	service: TrackingService = TrackingService.MyAnimeList,
	pkce: PkceMode = PkceMode.Plain,
	clientSecret: String? = null,
) = OAuthConfig(
	service = service,
	authorizeUrl = "https://example.test/authorize",
	tokenUrl = "https://example.test/token",
	credentials = ServiceCredentials(clientId = "client-1", clientSecret = clientSecret),
	pkce = pkce,
)

fun chapter(
	id: Long,
	number: Float,
	branch: String? = null,
	source: MangaParserSource = MangaParserSource.MANGADEX,
) = MangaChapter(
	id = id,
	title = "Chapter $number",
	number = number,
	volume = 0,
	url = "/chapter/$id",
	scanlator = null,
	uploadDate = 0L,
	branch = branch,
	source = source,
)

/**
 * A suspend-aware "this must throw".
 *
 * JUnit 4's `assertThrows` takes a plain runnable and cannot call a suspend function, and
 * `kotlin-test` is not on this module's test classpath. Adding a dependency for one
 * assertion is not worth it, so here it is.
 */
internal suspend inline fun <reified T : Throwable> expectThrows(block: suspend () -> Unit): T {
	try {
		block()
	} catch (e: Throwable) {
		if (e is T) {
			return e
		}
		throw AssertionError("Expected ${T::class.simpleName}, got ${e::class.simpleName}: ${e.message}", e)
	}
	throw AssertionError("Expected ${T::class.simpleName}, but nothing was thrown")
}

/** Asserts the runtime type and returns the value as it, so its own fields can be checked. */
internal inline fun <reified T> expectType(value: Any?): T {
	if (value !is T) {
		throw AssertionError("Expected ${T::class.simpleName}, got $value")
	}
	return value
}
