package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The loopback half of the desktop OAuth flow, against a real socket.
 *
 * The listener is not faked here. It is the piece with no Android equivalent, it is the
 * piece that can hang or leak a port, and a fake of it would only test the fake. The
 * token endpoint is scripted, because that part is an ordinary HTTP call.
 */
class LoopbackOAuthTest {

	@Test
	fun `a redirect carrying a code completes the token exchange`() = runTest {
		val http = ScriptedHttp { request ->
			assertEquals("https://example.test/token", request.url)
			HttpResponse(
				200,
				"""{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"token_type":"Bearer"}""",
			)
		}
		val browser = FakeBrowser { state -> mapOf("code" to "the-code", "state" to state.orEmpty()) }
		val flow = OAuthFlow(http = http, browser = browser)

		val grant = flow.authorize(testConfig(), timeoutMillis = 15_000)

		assertEquals("at-1", grant.accessToken)
		assertEquals("rt-1", grant.refreshToken)
		assertEquals(3600L, grant.expiresInSeconds)

		val exchange = http.requests.single()
		assertEquals("POST", exchange.method)
		val fields = exchange.formFields()
		assertEquals("authorization_code", fields["grant_type"])
		assertEquals("the-code", fields["code"])
		assertEquals("client-1", fields["client_id"])
		// PKCE: the verifier goes to the token endpoint, and the challenge went to the
		// browser. For MyAnimeList's `plain` mode they are the same string.
		val verifier = requireNotNull(fields["code_verifier"]) { "no code_verifier was sent" }
		val authorizeUrl = requireNotNull(browser.openedUrl).toHttpUrl()
		// MyAnimeList's `plain` mode sends the verifier itself as the challenge, so the
		// two must match; on a service using S256 they would not.
		assertEquals("plain", authorizeUrl.queryParameter("code_challenge_method"))
		assertEquals(verifier, authorizeUrl.queryParameter("code_challenge"))
		// The redirect the service was told to use is the loopback literal, not a name
		// the host could resolve to something else (RFC 8252 section 8.3).
		val redirect = requireNotNull(authorizeUrl.queryParameter("redirect_uri"))
		assertTrue(redirect, redirect.startsWith("http://127.0.0.1:"))
		assertTrue(redirect, redirect.endsWith("/oauth/callback"))
		assertEquals(fields["redirect_uri"], redirect)

		browser.awaitCallback()
		assertNull(browser.failure)
		val page = requireNotNull(browser.receivedPage)
		assertTrue(page, page.contains("close this tab"))
	}

	@Test
	fun `the state value is echoed back and checked`() = runTest {
		val http = ScriptedHttp { HttpResponse(200, """{"access_token":"at"}""") }
		// A code arriving with somebody else's state must not be exchanged.
		val browser = FakeBrowser { mapOf("code" to "the-code", "state" to "not-the-one") }
		val flow = OAuthFlow(http = http, browser = browser)

		val error = expectThrows<OAuthFailedException> {
			flow.authorize(testConfig(), timeoutMillis = 15_000)
		}

		assertTrue(error.message!!, error.message!!.contains("wrong state"))
		assertTrue("a code with a bad state was exchanged anyway", http.requests.isEmpty())
	}

	@Test
	fun `a redirect carrying access_denied fails with a readable message and no exchange`() = runTest {
		val http = ScriptedHttp { HttpResponse(200, """{"access_token":"never"}""") }
		val browser = FakeBrowser { mapOf("error" to "access_denied") }
		val flow = OAuthFlow(http = http, browser = browser)

		val error = expectThrows<OAuthFailedException> {
			flow.authorize(testConfig(), timeoutMillis = 15_000)
		}

		assertEquals("You declined access, so nothing was connected.", error.message)
		assertTrue("a token exchange was attempted after a refusal", http.requests.isEmpty())

		browser.awaitCallback()
		val page = requireNotNull(browser.receivedPage)
		assertTrue(page, page.contains("Sign-in failed"))
	}

	@Test
	fun `an error_description from the service is shown instead of the code`() = runTest {
		val http = ScriptedHttp { HttpResponse(200, "{}") }
		val browser = FakeBrowser {
			mapOf("error" to "invalid_scope", "error_description" to "The scope read:list is unknown")
		}
		val flow = OAuthFlow(http = http, browser = browser)

		val error = expectThrows<OAuthFailedException> {
			flow.authorize(testConfig(), timeoutMillis = 15_000)
		}

		assertEquals("The scope read:list is unknown", error.message)
	}

	@Test
	fun `the listener times out rather than hanging, and gives the port back`() = runTest {
		val receiver = LoopbackReceiver()
		val port = receiver.port
		val startedAt = System.currentTimeMillis()

		val outcome = receiver.awaitRedirect(timeoutMillis = 300L)

		assertEquals(RedirectOutcome.TimedOut, outcome)
		val elapsed = System.currentTimeMillis() - startedAt
		assertTrue("waited $elapsed ms for a 300 ms timeout", elapsed < 10_000L)
		receiver.close()
		assertPortIsFree(port)
	}

	@Test
	fun `a sign-in nobody completes times out and releases the port`() = runTest {
		val http = ScriptedHttp { HttpResponse(200, "{}") }
		var port = -1
		val flow = OAuthFlow(
			http = http,
			browser = SilentBrowser,
			receivers = { LoopbackReceiver().also { port = it.port } },
		)

		val error = expectThrows<OAuthFailedException> {
			flow.authorize(testConfig(), timeoutMillis = 400L)
		}

		assertTrue(error.message!!, error.message!!.contains("did not answer"))
		assertTrue(http.requests.isEmpty())
		assertTrue("the receiver was never built", port > 0)
		// The `finally` in OAuthFlow.authorize is the thing being checked: a timed-out
		// sign-in must not sit on a port for the rest of the session.
		assertPortIsFree(port)
	}

	@Test
	fun `a request to another path does not end the wait`() = runTest {
		val receiver = LoopbackReceiver()
		try {
			// Browsers routinely ask for /favicon.ico against whatever host they just
			// loaded. Treating that as the redirect would abort every sign-in.
			val noise = FakeBrowser { emptyMap() }
			val authorizeUrl = "https://example.test/authorize".toHttpUrl().newBuilder()
				.addQueryParameter("redirect_uri", "http://127.0.0.1:${receiver.port}/favicon.ico")
				.build()
				.toString()
			noise.open(authorizeUrl)
			val outcome = receiver.awaitRedirect(timeoutMillis = 1_200L)
			assertEquals(RedirectOutcome.TimedOut, outcome)
			noise.awaitCallback()
			assertTrue(requireNotNull(noise.receivedPage).contains("not part of the sign-in"))
		} finally {
			receiver.close()
		}
	}

	private fun assertPortIsFree(port: Int) {
		ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use {
			assertEquals(port, it.localPort)
		}
	}

}
