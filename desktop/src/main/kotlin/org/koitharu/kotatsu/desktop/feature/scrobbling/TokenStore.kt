package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** One service's stored OAuth state. */
@Serializable
data class StoredTokens(
	@SerialName("access_token") val accessToken: String,
	@SerialName("refresh_token") val refreshToken: String? = null,
	/** Epoch millis. 0 means the service did not say, so the token is only found stale by a 401. */
	@SerialName("expires_at") val expiresAt: Long = 0L,
	@SerialName("account_id") val accountId: Long = 0L,
	@SerialName("account_name") val accountName: String? = null,
	@SerialName("account_avatar") val accountAvatar: String? = null,
	/**
	 * AniList's per-account score format (POINT_100, POINT_10, ...).
	 *
	 * Stored with the token rather than derived on use because a score arrives as a bare
	 * number and is uninterpretable without it, and the only call that reports the format
	 * is the one that loads the account.
	 */
	@SerialName("score_format") val scoreFormat: String? = null,
	@SerialName("last_sync_at") val lastSyncAt: Long = 0L,
) {

	fun accountOrNull(): TrackerAccount? = accountName?.let {
		TrackerAccount(id = accountId, nickname = it, avatarUrl = accountAvatar)
	}

	/**
	 * True when the access token is past its stated lifetime.
	 *
	 * [SKEW_MILLIS] of slack, because a token that expires while the request is in flight
	 * comes back as a 401 that looks like a revoked grant rather than an expiry.
	 */
	fun isExpired(now: Long): Boolean = expiresAt > 0L && now >= expiresAt - SKEW_MILLIS

	companion object {

		const val SKEW_MILLIS = 60_000L
	}
}

@Serializable
private data class TokenFileContent(
	@SerialName("version") val version: Int = 1,
	@SerialName("services") val services: Map<String, StoredTokens> = emptyMap(),
)

/**
 * Persists OAuth tokens for every service in one file.
 *
 * The file holds live credentials. It is created with POSIX mode 0600 so that another
 * account on the same machine cannot read it, and every rewrite goes through a temporary
 * file created with the same mode and then moved into place, so the window where a
 * world-readable version exists never opens. This is **not encryption**: anything running
 * as this user, including any other program the user starts, can still read it. It only
 * removes the casual exposure of a file that is readable by everyone on the host, which is
 * what an ordinary `Files.writeString` would leave behind under a typical 022 umask.
 *
 * Encrypting it would need a key, and the only place to keep the key is the same
 * directory, which buys nothing. A real secret store (kwallet, gnome-keyring, the Secret
 * Service API) is the correct answer and is a new dependency, so it is out of scope here.
 */
class TokenStore(private val file: Path) {

	private val json = Json {
		ignoreUnknownKeys = true
		prettyPrint = true
		encodeDefaults = true
	}
	private val lock = Any()

	@Volatile
	private var cache: Map<String, StoredTokens>? = null

	fun get(service: TrackingService): StoredTokens? = load()[service.key]

	fun put(service: TrackingService, tokens: StoredTokens) {
		mutate { it + (service.key to tokens) }
	}

	fun remove(service: TrackingService) {
		mutate { it - service.key }
	}

	/** Applies [transform] to the current entry, doing nothing when there is none. */
	fun update(service: TrackingService, transform: (StoredTokens) -> StoredTokens) {
		mutate { current ->
			val existing = current[service.key] ?: return@mutate current
			current + (service.key to transform(existing))
		}
	}

	private fun load(): Map<String, StoredTokens> {
		cache?.let { return it }
		synchronized(lock) {
			cache?.let { return it }
			val loaded = readFile()
			cache = loaded
			return loaded
		}
	}

	private fun mutate(transform: (Map<String, StoredTokens>) -> Map<String, StoredTokens>) {
		synchronized(lock) {
			val current = cache ?: readFile()
			val next = transform(current)
			writeFile(next)
			cache = next
		}
	}

	private fun readFile(): Map<String, StoredTokens> {
		if (!Files.isRegularFile(file)) {
			return emptyMap()
		}
		return try {
			json.decodeFromString(TokenFileContent.serializer(), Files.readString(file)).services
		} catch (e: Exception) {
			// A corrupt token file means the user has to sign in again, which is
			// recoverable. Refusing to start the screen is not. The file is left in place
			// rather than deleted so the failure is still diagnosable.
			System.err.println("Tracking: ignoring unreadable $file: ${e.message}")
			emptyMap()
		}
	}

	private fun writeFile(services: Map<String, StoredTokens>) {
		// Absolute first: a bare file name has a null parent, and the temp file has to
		// land in the same directory as the target or the move stops being atomic.
		val parent = file.toAbsolutePath().parent
		Files.createDirectories(parent)
		val text = json.encodeToString(TokenFileContent.serializer(), TokenFileContent(services = services))
		val temp = Files.createTempFile(parent, ".tracking-tokens", ".tmp", *ownerOnlyAttribute())
		try {
			// createTempFile already honours the attribute on POSIX, but an explicit set
			// covers the case where the file existed from an interrupted earlier write.
			restrictToOwner(temp)
			Files.writeString(temp, text)
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
			} catch (e: java.nio.file.AtomicMoveNotSupportedException) {
				// Some filesystems cannot move atomically. A plain replace is still
				// preferable to writing over the live file in place.
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
			}
			restrictToOwner(file)
		} finally {
			Files.deleteIfExists(temp)
		}
	}

	private fun ownerOnlyAttribute(): Array<java.nio.file.attribute.FileAttribute<*>> =
		if (isPosix()) {
			arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY))
		} else {
			emptyArray()
		}

	private fun restrictToOwner(target: Path) {
		if (!isPosix()) {
			return
		}
		try {
			Files.setPosixFilePermissions(target, OWNER_ONLY)
		} catch (e: IOException) {
			// Better to hold a token with wider permissions than to lose the sign-in, but
			// the user should know it happened.
			System.err.println("Tracking: could not restrict permissions on $target: ${e.message}")
		}
	}

	private fun isPosix(): Boolean = file.fileSystem.supportedFileAttributeViews().contains("posix")

	companion object {

		/** `rw-------`. */
		val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

		const val FILE_NAME = "tracking-tokens.json"
	}
}
