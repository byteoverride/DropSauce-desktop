package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** The OAuth application registration for one service. */
data class ServiceCredentials(
	val clientId: String,
	/** Null for a public client. MAL is one; AniList is not and will reject a token exchange without it. */
	val clientSecret: String?,
)

/**
 * Where the OAuth client id and secret come from.
 *
 * The Android app compiles them in as string resources. That cannot be copied here: this
 * is a source build with no registered application behind it, and writing a plausible
 * looking id into the tree would produce a service that appears configured and fails at
 * the token exchange with an opaque error. So there is no default and no placeholder. A
 * service with nothing configured reports [ServiceState.Unavailable] and the screen says
 * which variable to set.
 *
 * Two sources, environment first so a shell can override a file without editing it:
 *
 *  - `DROPSAUCE_ANILIST_CLIENT_ID` / `DROPSAUCE_ANILIST_CLIENT_SECRET`
 *  - `DROPSAUCE_MAL_CLIENT_ID` / `DROPSAUCE_MAL_CLIENT_SECRET`
 *  - `<config>/tracking-credentials.json`
 */
fun interface CredentialsSource {

	/** Null when this service has no client id, which is the normal state of a fresh build. */
	fun credentialsFor(service: TrackingService): ServiceCredentials?
}

/** The environment variable holding [service]'s client id. */
fun clientIdVariable(service: TrackingService): String = when (service) {
	TrackingService.AniList -> "DROPSAUCE_ANILIST_CLIENT_ID"
	TrackingService.MyAnimeList -> "DROPSAUCE_MAL_CLIENT_ID"
}

/** The environment variable holding [service]'s client secret. */
fun clientSecretVariable(service: TrackingService): String = when (service) {
	TrackingService.AniList -> "DROPSAUCE_ANILIST_CLIENT_SECRET"
	TrackingService.MyAnimeList -> "DROPSAUCE_MAL_CLIENT_SECRET"
}

/** True when [service] cannot complete a token exchange without a secret as well as an id. */
fun requiresClientSecret(service: TrackingService): Boolean = when (service) {
	// AniList's token endpoint is a confidential-client exchange: no PKCE, secret required.
	TrackingService.AniList -> true
	// MAL is a public client using PKCE, and rejects nothing when the secret is absent.
	TrackingService.MyAnimeList -> false
}

/** The name of the optional file, relative to `FeatureContext.paths.config`. */
const val CREDENTIALS_FILE_NAME = "tracking-credentials.json"

@Serializable
private data class CredentialsFile(
	@SerialName("services") val services: Map<String, FileEntry> = emptyMap(),
) {

	@Serializable
	data class FileEntry(
		@SerialName("client_id") val clientId: String = "",
		@SerialName("client_secret") val clientSecret: String? = null,
	)
}

/**
 * Reads credentials from the environment, then from an optional JSON file.
 *
 * Re-read on every call rather than cached, so setting a variable or dropping the file in
 * and reopening the screen is enough; there is no restart to explain to the user. These
 * are two short lookups, not a hot path.
 */
class DefaultCredentialsSource(
	private val configDir: Path,
	private val env: (String) -> String? = System::getenv,
) : CredentialsSource {

	private val json = Json { ignoreUnknownKeys = true }

	override fun credentialsFor(service: TrackingService): ServiceCredentials? {
		val fromEnv = env(clientIdVariable(service))?.takeIf { it.isNotBlank() }
		if (fromEnv != null) {
			return ServiceCredentials(
				clientId = fromEnv.trim(),
				clientSecret = env(clientSecretVariable(service))?.takeIf { it.isNotBlank() }?.trim(),
			)
		}
		val entry = readFile()[service.key] ?: return null
		val clientId = entry.clientId.trim().takeIf { it.isNotEmpty() } ?: return null
		return ServiceCredentials(
			clientId = clientId,
			clientSecret = entry.clientSecret?.trim()?.takeIf { it.isNotEmpty() },
		)
	}

	private fun readFile(): Map<String, CredentialsFile.FileEntry> {
		val file = configDir.resolve(CREDENTIALS_FILE_NAME)
		if (!Files.isRegularFile(file)) {
			return emptyMap()
		}
		return try {
			json.decodeFromString(CredentialsFile.serializer(), Files.readString(file)).services
		} catch (e: Exception) {
			// A malformed file must not take the whole screen down, and it must not be
			// silently identical to no file. The reason is printed once, here, and the
			// service then reports itself unavailable with the same message as an
			// unconfigured one, which is the accurate outcome either way.
			System.err.println("Tracking: ignoring unreadable $file: ${e.message}")
			emptyMap()
		}
	}
}

/** The sentence shown when [service] has no client id, naming exactly what to do about it. */
fun unavailableReason(service: TrackingService, configDir: Path): String {
	val secretNote = if (requiresClientSecret(service)) {
		" and ${clientSecretVariable(service)}"
	} else {
		""
	}
	return "${service.label} needs an OAuth client id. Register an application with " +
		"${service.label}, then set ${clientIdVariable(service)}$secretNote in the " +
		"environment, or put it in ${configDir.resolve(CREDENTIALS_FILE_NAME)}."
}
