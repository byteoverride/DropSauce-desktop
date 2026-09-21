package org.koitharu.kotatsu.desktop.feature.scrobbling

/** The outcome of linking a local title to a service entry. */
data class LinkResult(
	val entry: RemoteEntry,
	/**
	 * True when the service already had an entry for this title.
	 *
	 * The caller must not push local progress over an adopted entry: a chapter count, a
	 * rating or a status set on the website is the user's own and always wins. Only a
	 * freshly created entry is safe to fill in from local history.
	 */
	val wasAlreadyTracked: Boolean,
)

/**
 * What every tracking service can do, stated once.
 *
 * Narrower than the Android `ScrobblerRepository`, which also owns its own database
 * writes. Here the implementations are pure API clients and every row that reaches
 * `scrobbling` is written by [TrackingRepository]. That separation is what lets a service
 * be tested against a scripted [HttpExchange] with no database at all.
 */
interface TrackerService {

	val service: TrackingService

	/** Who the stored token belongs to. Also the cheapest call that proves a token works. */
	suspend fun loadAccount(): TrackerAccount

	suspend fun search(query: String, type: TrackedMediaType, offset: Int = 0): List<TrackerMatch>

	suspend fun titleInfo(targetId: Long): TrackerTitleInfo

	/** Creates the entry on the service, or adopts the one already there. */
	suspend fun linkTitle(targetId: Long): LinkResult

	/** Reads the entry back, for status and rating the user may have changed elsewhere. */
	suspend fun fetchEntry(remoteId: Long, targetId: Long): RemoteEntry

	suspend fun pushProgress(remoteId: Long, targetId: Long, chapter: Int): RemoteEntry

	suspend fun pushState(
		remoteId: Long,
		targetId: Long,
		/** 0..1. Each service scales it to its own range. */
		rating: Float,
		status: TrackingStatus?,
		comment: String?,
		/** Stamps today as the reading start date. Left alone otherwise, so a date set on the website survives. */
		setStartDate: Boolean = false,
	): RemoteEntry
}

/** Builds the implementation for [session]'s service. */
fun trackerFor(session: ServiceSession): TrackerService = when (session.service) {
	TrackingService.AniList -> AniListTracker(session)
	TrackingService.MyAnimeList -> MyAnimeListTracker(session)
}
