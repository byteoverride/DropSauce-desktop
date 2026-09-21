package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/**
 * The refresh and availability rules, which are the two ways this area fails in normal use.
 *
 * Both are checked by counting requests rather than by reading the code back: "refreshes
 * once" is only a real claim if something has counted.
 */
class ServiceSessionTest {

	@get:Rule
	val temp = TemporaryFolder()

	private lateinit var dir: Path
	private var clock = 1_000_000L

	private val service = TrackingService.MyAnimeList
	private val tokenUrl = "https://myanimelist.net/v1/oauth2/token"
	private val apiUrl = "https://api.myanimelist.net/v2/users/@me"

	@Before
	fun setUp() {
		dir = temp.newFolder("config").toPath()
	}

	private fun tokens() = TokenStore(dir.resolve(TokenStore.FILE_NAME))

	private fun session(
		http: ScriptedHttp,
		source: CredentialsSource = credentials(service to testCredentials(secret = null)),
	) = testSession(
		service = service,
		dir = dir,
		http = http,
		credentialsSource = source,
		now = { clock },
	)

	@Test
	fun `an expired token is renewed exactly once and the call goes out with the new one`() = runTest {
		tokens().put(
			service,
			StoredTokens(accessToken = "old", refreshToken = "rt-1", expiresAt = clock - 1L),
		)
		val http = ScriptedHttp { request ->
			when (request.url) {
				tokenUrl -> HttpResponse(200, """{"access_token":"fresh","refresh_token":"rt-2","expires_in":3600}""")
				apiUrl -> HttpResponse(200, """{"id":1,"name":"someone"}""")
				else -> HttpResponse(404, "{}")
			}
		}
		val session = session(http)

		val response = session.request(HttpRequest("GET", apiUrl))

		assertEquals(200, response.code)
		assertEquals("exactly one refresh", 1, http.requestsTo(tokenUrl).size)
		assertEquals("refresh_token", http.requestsTo(tokenUrl).single().formFields()["grant_type"])
		val call = http.requestsTo(apiUrl).single()
		assertEquals("Bearer fresh", call.headers["Authorization"])
		// The rotated refresh token replaced the spent one, and the new expiry is stored.
		val stored = requireNotNull(tokens().get(service))
		assertEquals("fresh", stored.accessToken)
		assertEquals("rt-2", stored.refreshToken)
		assertEquals(clock + 3600L * 1000L, stored.expiresAt)
	}

	@Test
	fun `a 401 after a renewal is not renewed a second time`() = runTest {
		tokens().put(
			service,
			StoredTokens(accessToken = "old", refreshToken = "rt-1", expiresAt = clock - 1L),
		)
		// The grant is gone on the service side: renewing succeeds but the API keeps
		// rejecting. Retrying the refresh here is an infinite loop against a live API.
		val http = ScriptedHttp { request ->
			when (request.url) {
				tokenUrl -> HttpResponse(200, """{"access_token":"fresh","expires_in":3600}""")
				else -> HttpResponse(401, """{"error":"invalid_token"}""")
			}
		}
		val session = session(http)

		val error = expectThrows<TrackingAuthException> {
			session.request(HttpRequest("GET", apiUrl))
		}

		assertTrue(error.message!!, error.message!!.contains("rejected the stored sign-in"))
		assertEquals("exactly one refresh", 1, http.requestsTo(tokenUrl).size)
		assertEquals(ServiceState.Disconnected, session.state())
		assertNull(tokens().get(service))
	}

	@Test
	fun `a valid token that is rejected is renewed once and the call retried`() = runTest {
		// expiresAt 0: the service never said, so staleness can only be discovered by a 401.
		tokens().put(service, StoredTokens(accessToken = "old", refreshToken = "rt-1", expiresAt = 0L))
		val http = ScriptedHttp { request ->
			when {
				request.url == tokenUrl -> HttpResponse(200, """{"access_token":"fresh","expires_in":3600}""")
				request.headers["Authorization"] == "Bearer fresh" -> HttpResponse(200, """{"ok":true}""")
				else -> HttpResponse(401, "{}")
			}
		}
		val session = session(http)

		val response = session.request(HttpRequest("GET", apiUrl))

		assertEquals(200, response.code)
		assertEquals(1, http.requestsTo(tokenUrl).size)
		assertEquals(2, http.requestsTo(apiUrl).size)
	}

	@Test
	fun `a failed renewal disconnects instead of crashing`() = runTest {
		tokens().put(
			service,
			StoredTokens(accessToken = "old", refreshToken = "rt-dead", expiresAt = clock - 1L),
		)
		val http = ScriptedHttp { request ->
			when (request.url) {
				tokenUrl -> HttpResponse(400, """{"error":"invalid_grant","message":"refresh token revoked"}""")
				else -> HttpResponse(200, "{}")
			}
		}
		val session = session(http)

		val error = expectThrows<TrackingAuthException> {
			session.request(HttpRequest("GET", apiUrl))
		}

		// Typed and readable, not a raw IOException or a NullPointerException.
		assertTrue(error.message!!, error.message!!.contains("could not renew"))
		assertTrue(error.message!!, error.message!!.contains("refresh token revoked"))
		assertEquals(1, http.requestsTo(tokenUrl).size)
		// The API was never called with a token known to be dead.
		assertTrue(http.requestsTo(apiUrl).isEmpty())
		assertEquals(ServiceState.Disconnected, session.state())
		assertNull(tokens().get(service))
	}

	@Test
	fun `a token with no refresh token disconnects without calling the token endpoint`() = runTest {
		tokens().put(service, StoredTokens(accessToken = "old", refreshToken = null, expiresAt = clock - 1L))
		val http = ScriptedHttp { HttpResponse(200, "{}") }
		val session = session(http)

		val error = expectThrows<TrackingAuthException> {
			session.request(HttpRequest("GET", apiUrl))
		}

		assertTrue(error.message!!, error.message!!.contains("did not issue a refresh token"))
		assertTrue(http.requests.isEmpty())
		assertEquals(ServiceState.Disconnected, session.state())
	}

	@Test
	fun `a service with no client id is unavailable and never puts a request on the wire`() = runTest {
		// A token is present, so the only thing making this service unusable is the
		// missing client id. Without it the session would look connected.
		tokens().put(service, StoredTokens(accessToken = "at", refreshToken = "rt"))
		val http = ScriptedHttp { throw AssertionError("an unconfigured service made a request") }
		val session = session(http, source = credentials(service to null))

		val state = expectType<ServiceState.Unavailable>(session.state())

		assertTrue(state.reason, state.reason.contains("client id"))
		assertTrue(!session.isAvailable())

		expectThrows<TrackingUnavailableException> { session.request(HttpRequest("GET", apiUrl)) }
		expectThrows<TrackingUnavailableException> { session.connect(timeoutMillis = 100L) }
		assertTrue(http.requests.isEmpty())
	}

	@Test
	fun `a service needing a secret is unavailable when only the id is configured`() = runTest {
		val anilist = TrackingService.AniList
		val http = ScriptedHttp { throw AssertionError("an unconfigured service made a request") }
		val session = testSession(
			service = anilist,
			dir = dir,
			http = http,
			credentialsSource = credentials(anilist to ServiceCredentials(clientId = "ci", clientSecret = null)),
			now = { clock },
		)

		// AniList's token endpoint is a confidential-client exchange. An id alone would
		// get through the sign-in and fail at the exchange with an opaque error, so the
		// service is reported unavailable up front instead.
		expectType<ServiceState.Unavailable>(session.state())
		assertTrue(requiresClientSecret(anilist))
		expectThrows<TrackingUnavailableException> { session.connect(timeoutMillis = 100L) }
		assertTrue(http.requests.isEmpty())
	}

	@Test
	fun `a configured service with no token is disconnected, not unavailable`() {
		val http = ScriptedHttp { HttpResponse(200, "{}") }

		assertEquals(ServiceState.Disconnected, session(http).state())
	}

	@Test
	fun `a stored account and sync time show up in the connected state`() {
		tokens().put(service, StoredTokens(accessToken = "at"))
		val session = session(ScriptedHttp { HttpResponse(200, "{}") })
		session.recordAccount(TrackerAccount(id = 7L, nickname = "reader", avatarUrl = null))
		session.recordSync(clock)

		val state = expectType<ServiceState.Connected>(session.state())

		assertEquals("reader", state.account?.nickname)
		assertEquals(clock, state.lastSyncAt)
	}
}
