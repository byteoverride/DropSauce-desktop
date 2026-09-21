package org.koitharu.kotatsu.desktop.feature.scrobbling

/**
 * The external tracking services this area knows about.
 *
 * [key] is what goes in `scrobbling.service`, and it matches the values the shared entity
 * documents (ANILIST, MAL, SHIKIMORI, KITSU) so a row written here means the same thing to
 * the Android app's importer. Only the two services with an implementation are listed;
 * adding an unimplemented entry would put a dead row shape in the database.
 */
enum class TrackingService(val key: String, val label: String) {

	AniList("ANILIST", "AniList"),
	MyAnimeList("MAL", "MyAnimeList"),
	;

	companion object {

		fun byKey(key: String): TrackingService? = entries.firstOrNull { it.key == key }
	}
}

/**
 * The reading states every tracker agrees on, before each one's own spelling is applied.
 *
 * Kept as an enum rather than passing the service's raw string around, because the two
 * services disagree on nearly every word ("CURRENT" against "reading") and a raw string
 * crossing a service boundary is a silently wrong update rather than a compile error.
 */
enum class TrackingStatus(val label: String) {

	Planned("Planned"),
	Reading("Reading"),
	ReReading("Re-reading"),
	Completed("Completed"),
	OnHold("On hold"),
	Dropped("Dropped"),
}

/** Trackers file prose and comics in one catalogue, so a search has to say which it wants. */
enum class TrackedMediaType { Manga, Novel }

/** Who the stored token belongs to, as the service reports it. */
data class TrackerAccount(
	val id: Long,
	val nickname: String,
	val avatarUrl: String?,
)

/** One candidate returned by a service's search, offered to the user to pick from. */
data class TrackerMatch(
	val targetId: Long,
	val title: String,
	val altTitle: String?,
	val coverUrl: String?,
	val url: String,
	/** The title matched the local one exactly, so it can be surfaced first. */
	val isExactMatch: Boolean,
)

/** What a service publishes about a title, independent of the user's own entry. */
data class TrackerTitleInfo(
	val targetId: Long,
	val title: String,
	val coverUrl: String?,
	val url: String,
	val descriptionHtml: String,
	/** 0 when the service does not publish a chapter count for this title. */
	val totalChapters: Int,
)

/**
 * The user's own entry for a title on a service.
 *
 * [rating] is normalised to 0..1 here regardless of the scale the service uses, because
 * AniList alone has five different score formats per account and storing the raw number
 * would make the stored value uninterpretable without also storing the format.
 */
data class RemoteEntry(
	/** The service's id for the list entry itself, stored as `scrobbling.remote_id`. */
	val remoteId: Long,
	/** The service's id for the title, stored as `scrobbling.target_id`. */
	val targetId: Long,
	/** The service's own spelling, stored verbatim; [status] is the interpreted form. */
	val rawStatus: String?,
	val chapter: Int,
	val rating: Float,
	val comment: String?,
) {

	fun statusOrNull(service: TrackingService): TrackingStatus? =
		rawStatus?.let { statusFromRaw(service, it) }
}

/** A stored link plus whatever the service last told us about it. */
data class TrackedTitle(
	val service: TrackingService,
	val mangaId: Long,
	val remoteId: Long,
	val targetId: Long,
	val status: TrackingStatus?,
	val chapter: Int,
	val rating: Float,
	val comment: String?,
	val updatedAt: Long,
)

/** Why a service cannot be used right now, in words meant for the user. */
sealed interface ServiceState {

	/** No client id configured. The user has to supply one before anything can happen. */
	data class Unavailable(val reason: String) : ServiceState

	/** Credentials present, no token stored. */
	data object Disconnected : ServiceState

	data class Connected(
		val account: TrackerAccount?,
		val lastSyncAt: Long,
	) : ServiceState

	/** Connected once, but the last attempt failed. The token may or may not still be good. */
	data class Failing(val message: String, val lastSyncAt: Long) : ServiceState
}

/** Raised when a service is asked to do something it has no credentials for. */
class TrackingUnavailableException(message: String) : IllegalStateException(message)

/** Raised when the stored token is gone or could not be refreshed. */
class TrackingAuthException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Raised for a service reply that is an error, or is not shaped the way the API documents. */
class TrackingApiException(message: String) : RuntimeException(message)

/** The service's own word for [status]. */
internal fun statusToRaw(service: TrackingService, status: TrackingStatus): String? = when (service) {
	TrackingService.AniList -> when (status) {
		TrackingStatus.Planned -> "PLANNING"
		TrackingStatus.Reading -> "CURRENT"
		TrackingStatus.ReReading -> "REPEATING"
		TrackingStatus.Completed -> "COMPLETED"
		TrackingStatus.OnHold -> "PAUSED"
		TrackingStatus.Dropped -> "DROPPED"
	}
	// MAL has no re-reading status of its own; it is a boolean flag on a "reading" entry.
	// Returning null rather than inventing one keeps the caller from writing a state that
	// would silently become something else on the website.
	TrackingService.MyAnimeList -> when (status) {
		TrackingStatus.Planned -> "plan_to_read"
		TrackingStatus.Reading -> "reading"
		TrackingStatus.ReReading -> null
		TrackingStatus.Completed -> "completed"
		TrackingStatus.OnHold -> "on_hold"
		TrackingStatus.Dropped -> "dropped"
	}
}

/** The reverse of [statusToRaw]; null when the service sent something unrecognised. */
internal fun statusFromRaw(service: TrackingService, raw: String): TrackingStatus? =
	TrackingStatus.entries.firstOrNull { statusToRaw(service, it) == raw }
