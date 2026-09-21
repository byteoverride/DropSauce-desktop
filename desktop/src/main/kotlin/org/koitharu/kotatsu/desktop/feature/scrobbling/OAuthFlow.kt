package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.awt.Desktop
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Whether, and how, a service accepts a PKCE challenge. */
enum class PkceMode {

	/** AniList: a confidential client with a secret, and no `code_challenge` parameter. */
	None,

	/** MyAnimeList: implements only `plain`, where the challenge is the verifier verbatim. */
	Plain,

	/** The correct form, used where the service supports it. */
	S256,
}

/** Everything that differs between one service's authorization-code flow and another's. */
data class OAuthConfig(
	val service: TrackingService,
	val authorizeUrl: String,
	val tokenUrl: String,
	val credentials: ServiceCredentials,
	val pkce: PkceMode,
	val scopes: List<String> = emptyList(),
	/** Extra query parameters the authorize request needs beyond the standard ones. */
	val extraAuthorizeParams: Map<String, String> = emptyMap(),
)

/** What a token endpoint returned. */
data class TokenGrant(
	val accessToken: String,
	val refreshToken: String?,
	/** Seconds, as the service reported it. 0 when it did not say. */
	val expiresInSeconds: Long,
)

/** Opens a URL in whatever the user's desktop considers their browser. */
fun interface BrowserLauncher {

	/** Throws when no browser can be opened, which the flow reports rather than hanging. */
	fun open(url: String)
}

/**
 * The real launcher.
 *
 * `Desktop.browse` is the only portable route, and on Linux it is not always available:
 * a headless JVM or a session with no xdg-open has no browsing support. That is checked
 * up front and reported, because the alternative is a flow that binds a port, opens
 * nothing, and times out two minutes later with no explanation.
 */
object AwtBrowserLauncher : BrowserLauncher {

	override fun open(url: String) {
		val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
		if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) {
			throw UnsupportedOperationException(
				"This session cannot open a browser. Open this address by hand to continue: $url",
			)
		}
		desktop.browse(URI(url))
	}
}

@Serializable
private data class TokenResponse(
	@SerialName("access_token") val accessToken: String,
	@SerialName("refresh_token") val refreshToken: String? = null,
	@SerialName("expires_in") val expiresIn: Long = 0L,
	@SerialName("token_type") val tokenType: String? = null,
)

@Serializable
private data class TokenErrorResponse(
	@SerialName("error") val error: String? = null,
	@SerialName("error_description") val errorDescription: String? = null,
	@SerialName("message") val message: String? = null,
	@SerialName("hint") val hint: String? = null,
)

/** Raised when the browser leg of the flow did not produce a code. */
class OAuthFailedException(message: String) : RuntimeException(message)

/**
 * Drives one authorization-code sign-in, start to finish.
 *
 * The ordering here is the whole point and it is not negotiable: the loopback socket binds
 * first, because its port is part of the redirect URI that the authorize URL carries; only
 * then does the browser open. The listener is closed in a `finally` so a timeout, a
 * refusal or a cancellation all release the port.
 *
 * Every collaborator is injected. The tests run the real listener against a real local
 * socket, which is the part worth exercising, with a fake browser that performs the
 * redirect and a fake [HttpExchange] standing in for the token endpoint.
 */
class OAuthFlow(
	private val http: HttpExchange,
	private val browser: BrowserLauncher = AwtBrowserLauncher,
	private val receivers: () -> LoopbackReceiver = { LoopbackReceiver() },
	private val random: SecureRandom = SecureRandom(),
) {

	private val json = Json { ignoreUnknownKeys = true }

	suspend fun authorize(config: OAuthConfig, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): TokenGrant {
		val receiver = receivers()
		try {
			val state = randomUrlSafe(STATE_BYTES)
			val verifier = if (config.pkce == PkceMode.None) null else randomUrlSafe(VERIFIER_BYTES)
			val url = buildAuthorizeUrl(config, receiver.redirectUri, state, verifier)
			// Bound above, opened here. Reversing these two lines is the classic way to
			// end up advertising a port that nothing is listening on yet.
			browser.open(url)
			return when (val outcome = receiver.awaitRedirect(timeoutMillis)) {
				is RedirectOutcome.TimedOut -> throw OAuthFailedException(
					"${config.service.label} did not answer within ${timeoutMillis / 1000} seconds. " +
						"Nothing was connected.",
				)

				is RedirectOutcome.Failed -> throw OAuthFailedException(
					describeError(outcome.error, outcome.description),
				)

				is RedirectOutcome.Code -> {
					// A mismatched state means the code came from a request this process
					// did not start, so it must not be exchanged.
					if (outcome.state != state) {
						throw OAuthFailedException(
							"The sign-in came back with the wrong state value and was discarded.",
						)
					}
					exchangeCode(config, outcome.code, receiver.redirectUri, verifier)
				}
			}
		} finally {
			receiver.close()
		}
	}

	/** Trades a refresh token for a fresh access token. */
	suspend fun refresh(config: OAuthConfig, refreshToken: String): TokenGrant {
		val fields = mutableListOf(
			"grant_type" to "refresh_token",
			"refresh_token" to refreshToken,
			"client_id" to config.credentials.clientId,
		)
		config.credentials.clientSecret?.let { fields += "client_secret" to it }
		return postToken(config, fields)
	}

	private suspend fun exchangeCode(
		config: OAuthConfig,
		code: String,
		redirectUri: String,
		verifier: String?,
	): TokenGrant {
		val fields = mutableListOf(
			"grant_type" to "authorization_code",
			"code" to code,
			"redirect_uri" to redirectUri,
			"client_id" to config.credentials.clientId,
		)
		config.credentials.clientSecret?.let { fields += "client_secret" to it }
		verifier?.let { fields += "code_verifier" to it }
		return postToken(config, fields)
	}

	private suspend fun postToken(config: OAuthConfig, fields: List<Pair<String, String>>): TokenGrant {
		val response = http.execute(
			HttpRequest(
				method = "POST",
				url = config.tokenUrl,
				headers = mapOf("Accept" to "application/json"),
				body = HttpBody.Form(fields),
			),
		)
		if (!response.isSuccessful) {
			throw OAuthFailedException(
				"${config.service.label} rejected the sign-in (HTTP ${response.code}): " +
					describeTokenError(response.body),
			)
		}
		val parsed = try {
			json.decodeFromString(TokenResponse.serializer(), response.body)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			throw OAuthFailedException(
				"${config.service.label} returned a token response this build cannot read: ${e.message}",
			)
		}
		return TokenGrant(
			accessToken = parsed.accessToken,
			refreshToken = parsed.refreshToken,
			expiresInSeconds = parsed.expiresIn,
		)
	}

	private fun describeTokenError(body: String): String {
		val parsed = try {
			json.decodeFromString(TokenErrorResponse.serializer(), body)
		} catch (e: Exception) {
			null
		}
		val text = parsed?.errorDescription
			?: parsed?.message
			?: parsed?.error
			?: body.takeIf { it.isNotBlank() }
			?: "no reason given"
		return text.lineSequence().first().take(300)
	}

	private fun buildAuthorizeUrl(
		config: OAuthConfig,
		redirectUri: String,
		state: String,
		verifier: String?,
	): String {
		val builder = config.authorizeUrl.toHttpUrl().newBuilder()
			.addQueryParameter("response_type", "code")
			.addQueryParameter("client_id", config.credentials.clientId)
			.addQueryParameter("redirect_uri", redirectUri)
			.addQueryParameter("state", state)
		if (config.scopes.isNotEmpty()) {
			builder.addQueryParameter("scope", config.scopes.joinToString(" "))
		}
		if (verifier != null) {
			builder.addQueryParameter("code_challenge", challengeFor(config.pkce, verifier))
			builder.addQueryParameter("code_challenge_method", if (config.pkce == PkceMode.S256) "S256" else "plain")
		}
		for ((name, value) in config.extraAuthorizeParams) {
			builder.addQueryParameter(name, value)
		}
		return builder.build().toString()
	}

	private fun challengeFor(mode: PkceMode, verifier: String): String = when (mode) {
		PkceMode.S256 -> {
			val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
			Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
		}
		// Plain is weaker and is used only because MyAnimeList implements nothing else.
		PkceMode.Plain -> verifier
		PkceMode.None -> verifier
	}

	private fun randomUrlSafe(bytes: Int): String {
		val buffer = ByteArray(bytes)
		random.nextBytes(buffer)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
	}

	companion object {

		/**
		 * Two minutes. Long enough to sign in and approve, short enough that an abandoned
		 * attempt gives the port back while the user is still in the app.
		 */
		const val DEFAULT_TIMEOUT_MILLIS = 120_000L

		const val STATE_BYTES = 16

		/**
		 * 64 bytes, which base64url-encodes to 86 characters.
		 *
		 * RFC 7636 caps the verifier at 128 characters and MyAnimeList, which uses it
		 * verbatim as the challenge, rejects anything longer.
		 */
		const val VERIFIER_BYTES = 64
	}
}
