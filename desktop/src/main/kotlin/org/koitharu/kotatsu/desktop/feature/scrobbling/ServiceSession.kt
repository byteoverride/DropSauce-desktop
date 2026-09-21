package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The fixed half of a service's OAuth setup, the half that does not come from the user. */
internal fun oauthConfigFor(service: TrackingService, credentials: ServiceCredentials): OAuthConfig =
	when (service) {
		TrackingService.AniList -> OAuthConfig(
			service = service,
			authorizeUrl = "https://anilist.co/api/v2/oauth/authorize",
			tokenUrl = "https://anilist.co/api/v2/oauth/token",
			credentials = credentials,
			// AniList's token endpoint takes a client secret and ignores code_challenge.
			pkce = PkceMode.None,
		)

		TrackingService.MyAnimeList -> OAuthConfig(
			service = service,
			authorizeUrl = "https://myanimelist.net/v1/oauth2/authorize",
			tokenUrl = "https://myanimelist.net/v1/oauth2/token",
			credentials = credentials,
			// MyAnimeList documents code_challenge_method=plain and rejects S256.
			pkce = PkceMode.Plain,
		)
	}

/**
 * One service's credentials, tokens and authenticated request path.
 *
 * Everything that can make a service unusable is decided here and nowhere else, so there
 * is one place to read for "why is this service not working": no client id, no token, a
 * token that would not renew.
 *
 * The refresh rule is the part worth being precise about. A single [request] performs at
 * most one refresh. A token known to be expired is renewed before the call; a token that
 * turns out to be rejected is renewed once and the call retried. It is never both, and a
 * second 401 after a refresh means the grant is gone, not stale, so the session
 * disconnects rather than looping.
 */
class ServiceSession(
	val service: TrackingService,
	private val credentials: CredentialsSource,
	private val tokens: TokenStore,
	private val http: HttpExchange,
	private val oauth: OAuthFlow,
	private val now: () -> Long = System::currentTimeMillis,
	private val unavailableText: (TrackingService) -> String,
) {

	private val refreshLock = Mutex()

	/** Null when no client id is configured, which is the only reason a service is unavailable. */
	fun configOrNull(): OAuthConfig? {
		val found = credentials.credentialsFor(service) ?: return null
		if (requiresClientSecret(service) && found.clientSecret.isNullOrEmpty()) {
			return null
		}
		return oauthConfigFor(service, found)
	}

	fun isAvailable(): Boolean = configOrNull() != null

	fun stored(): StoredTokens? = tokens.get(service)

	fun state(): ServiceState {
		if (configOrNull() == null) {
			return ServiceState.Unavailable(unavailableText(service))
		}
		val current = tokens.get(service) ?: return ServiceState.Disconnected
		return ServiceState.Connected(current.accountOrNull(), current.lastSyncAt)
	}

	/**
	 * Runs the browser sign-in and stores what comes back.
	 *
	 * Does not load the account: that is a service API call and belongs to the tracker,
	 * which is built on top of this. Splitting them keeps this class free of any knowledge
	 * of what the services' endpoints look like.
	 */
	suspend fun connect(timeoutMillis: Long = OAuthFlow.DEFAULT_TIMEOUT_MILLIS) {
		val config = requireConfig()
		val grant = oauth.authorize(config, timeoutMillis)
		tokens.put(
			service,
			StoredTokens(
				accessToken = grant.accessToken,
				refreshToken = grant.refreshToken,
				expiresAt = expiryFrom(grant.expiresInSeconds),
			),
		)
	}

	fun disconnect() {
		// The token is dropped locally only. Neither service publishes a revocation
		// endpoint that a public client can call, so claiming the grant is gone from the
		// account would be a lie; the services screen says "connect again" instead.
		tokens.remove(service)
	}

	fun recordSync(at: Long = now()) {
		tokens.update(service) { it.copy(lastSyncAt = at) }
	}

	fun recordAccount(account: TrackerAccount, scoreFormat: String? = null) {
		tokens.update(service) {
			it.copy(
				accountId = account.id,
				accountName = account.nickname,
				accountAvatar = account.avatarUrl,
				scoreFormat = scoreFormat ?: it.scoreFormat,
			)
		}
	}

	fun scoreFormat(): String? = tokens.get(service)?.scoreFormat

	/**
	 * Sends [request] with the stored bearer token.
	 *
	 * Throws [TrackingUnavailableException] before touching [http] when there is no client
	 * id. That order matters: an unconfigured service must never put a request on the wire,
	 * because a request with no credentials is an error the user cannot interpret and, on
	 * MyAnimeList, a rate-limited one.
	 */
	suspend fun request(request: HttpRequest): HttpResponse {
		val config = requireConfig()
		var current = tokens.get(service)
			?: throw TrackingAuthException("${service.label} is not connected.")
		var refreshed = false
		if (current.isExpired(now())) {
			current = renew(config, current)
			refreshed = true
		}
		var response = http.execute(request.withBearer(current.accessToken))
		if (response.code == UNAUTHORIZED && !refreshed) {
			current = renew(config, current)
			response = http.execute(request.withBearer(current.accessToken))
		}
		if (response.code == UNAUTHORIZED) {
			disconnect()
			throw TrackingAuthException(
				"${service.label} rejected the stored sign-in. Connect the service again.",
			)
		}
		return response
	}

	private fun requireConfig(): OAuthConfig = configOrNull()
		?: throw TrackingUnavailableException(unavailableText(service))

	/**
	 * Renews the access token, or disconnects.
	 *
	 * A failed renewal is a terminal state for the stored grant: the refresh token is
	 * either revoked or expired and no amount of retrying changes that. Clearing it here
	 * is what turns a failure into "Disconnected" on the services screen instead of a
	 * service that keeps failing every call with the same message.
	 */
	private suspend fun renew(config: OAuthConfig, current: StoredTokens): StoredTokens = refreshLock.withLock {
		// Re-read inside the lock: another call may have renewed while this one waited,
		// and spending a refresh token twice invalidates the one that just succeeded.
		val latest = tokens.get(service) ?: throw TrackingAuthException("${service.label} is not connected.")
		if (latest.accessToken != current.accessToken) {
			return@withLock latest
		}
		val refreshToken = latest.refreshToken
		if (refreshToken.isNullOrEmpty()) {
			disconnect()
			throw TrackingAuthException(
				"${service.label} did not issue a refresh token, so the sign-in cannot be renewed. " +
					"Connect the service again.",
			)
		}
		val grant = try {
			oauth.refresh(config, refreshToken)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			disconnect()
			throw TrackingAuthException(
				"${service.label} could not renew its sign-in: ${e.message ?: e::class.simpleName}",
				e,
			)
		}
		val updated = latest.copy(
			accessToken = grant.accessToken,
			refreshToken = grant.refreshToken ?: latest.refreshToken,
			expiresAt = expiryFrom(grant.expiresInSeconds),
		)
		tokens.put(service, updated)
		updated
	}

	private fun expiryFrom(seconds: Long): Long = if (seconds > 0L) now() + seconds * 1000L else 0L

	private companion object {

		const val UNAUTHORIZED = 401
	}
}

internal fun HttpRequest.withBearer(accessToken: String): HttpRequest =
	copy(headers = headers + ("Authorization" to "Bearer $accessToken"))
