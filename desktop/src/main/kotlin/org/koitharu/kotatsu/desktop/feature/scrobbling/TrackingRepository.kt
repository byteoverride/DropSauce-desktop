package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.ScrobblingEntity

/** What happened when progress was pushed to one service. */
data class PushOutcome(
	val service: TrackingService,
	/** The chapter number sent, or null when nothing was sent. */
	val chapter: Int?,
	val error: String?,
) {

	val isPushed: Boolean get() = chapter != null && error == null
}

/**
 * Everything the rest of the app asks of external tracking.
 *
 * Owns the `scrobbling` table and the per-service state that the UI shows. The service
 * clients under it are pure API callers; nothing else in this area writes a row.
 *
 * Nothing here throws at the caller for an ordinary service failure. A tracker being
 * unreachable must never take down reading, so a push records an error against the
 * service and returns it. The calls a user explicitly asked for (connect, link, refresh)
 * do surface their failure, because there a silent no-op would be worse.
 */
class TrackingRepository(
	private val db: LibraryDatabase,
	private val sessions: Map<TrackingService, ServiceSession>,
	private val trackers: Map<TrackingService, TrackerService>,
	private val now: () -> Long = System::currentTimeMillis,
) {

	private val stateFlow = MutableStateFlow(computeStates())

	/** Per-service connection state, recomputed after anything that could change it. */
	val states: StateFlow<Map<TrackingService, ServiceState>> get() = stateFlow

	fun stateOf(service: TrackingService): ServiceState =
		stateFlow.value[service] ?: ServiceState.Unavailable("${service.label} is not configured.")

	/** Services with a client id configured, in a stable order. */
	fun availableServices(): List<TrackingService> =
		TrackingService.entries.filter { sessions[it]?.isAvailable() == true }

	/** Services with a client id and a stored token. Only these can be asked to do anything. */
	fun connectedServices(): List<TrackingService> =
		TrackingService.entries.filter { stateOf(it) is ServiceState.Connected }

	/**
	 * Runs the browser sign-in for [service] and then loads the account.
	 *
	 * Loading the account is not decoration: it is the only thing that proves the token
	 * works, and on AniList it is where the score format comes from, without which every
	 * rating read back afterwards is on an unknown scale.
	 */
	suspend fun connect(service: TrackingService, timeoutMillis: Long = OAuthFlow.DEFAULT_TIMEOUT_MILLIS): TrackerAccount {
		val session = sessionOf(service)
		val tracker = trackerOf(service)
		try {
			session.connect(timeoutMillis)
			val account = tracker.loadAccount()
			session.recordSync(now())
			return account
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			// A token stored by a sign-in whose account call then failed is worse than no
			// token: the screen would say "connected" for a service that cannot be used.
			session.disconnect()
			throw e
		} finally {
			republish(service)
		}
	}

	fun disconnect(service: TrackingService) {
		// The stored links stay. Signing back in should not mean linking every title
		// again, and a row with no token is inert: every path through here checks the
		// session first.
		sessionOf(service).disconnect()
		republish(service)
	}

	/** Forgets both the token and every link for [service]. */
	suspend fun forget(service: TrackingService) {
		sessionOf(service).disconnect()
		db.scrobblingDao().deleteService(service.key)
		republish(service)
	}

	fun observeLinks(mangaId: Long): Flow<List<TrackedTitle>> =
		db.scrobblingDao().observeFor(mangaId).map { rows -> rows.mapNotNull { it.toTrackedTitle() } }

	suspend fun linkFor(service: TrackingService, mangaId: Long): TrackedTitle? =
		db.scrobblingDao().find(mangaId, service.key)?.toTrackedTitle()

	suspend fun search(
		service: TrackingService,
		query: String,
		type: TrackedMediaType = TrackedMediaType.Manga,
		offset: Int = 0,
	): List<TrackerMatch> = guarded(service) {
		trackerOf(service).search(query, type, offset)
	}

	suspend fun titleInfo(service: TrackingService, targetId: Long): TrackerTitleInfo = guarded(service) {
		trackerOf(service).titleInfo(targetId)
	}

	/**
	 * Links [mangaId] to [targetId] on [service].
	 *
	 * [fallbackStatus] is written only when the service had no entry of its own. An entry
	 * already on the website keeps its status, rating and chapter count verbatim: those
	 * are the user's, and a local library that has never opened the title would otherwise
	 * overwrite real progress with zero.
	 */
	suspend fun link(
		service: TrackingService,
		mangaId: Long,
		targetId: Long,
		fallbackStatus: TrackingStatus = TrackingStatus.Reading,
	): TrackedTitle = guarded(service) {
		val tracker = trackerOf(service)
		val result = tracker.linkTitle(targetId)
		val entry = if (result.wasAlreadyTracked) {
			result.entry
		} else {
			// Brand new entry, so it has no start date of its own worth preserving.
			tracker.pushState(
				remoteId = result.entry.remoteId,
				targetId = targetId,
				rating = 0f,
				status = fallbackStatus,
				comment = null,
				setStartDate = true,
			)
		}
		store(service, mangaId, entry)
	}

	suspend fun unlink(service: TrackingService, mangaId: Long) {
		db.scrobblingDao().delete(mangaId, service.key)
	}

	/** Re-reads the entry from the service, so a status or rating changed there shows here. */
	suspend fun refresh(service: TrackingService, mangaId: Long): TrackedTitle? = guarded(service) {
		val existing = db.scrobblingDao().find(mangaId, service.key) ?: return@guarded null
		val entry = trackerOf(service).fetchEntry(existing.remoteId, existing.targetId)
		store(service, mangaId, entry)
	}

	suspend fun updateState(
		service: TrackingService,
		mangaId: Long,
		rating: Float,
		status: TrackingStatus?,
		comment: String?,
	): TrackedTitle = guarded(service) {
		val existing = db.scrobblingDao().find(mangaId, service.key)
			?: throw TrackingApiException("This title is not linked to ${service.label}.")
		val entry = trackerOf(service).pushState(
			remoteId = existing.remoteId,
			targetId = existing.targetId,
			rating = rating,
			status = status,
			comment = comment,
		)
		store(service, mangaId, entry)
	}

	/**
	 * Pushes the chapter just finished to every connected service linked to [mangaId].
	 *
	 * Called from reading, so it never throws: a tracker that is down must not interrupt
	 * the reader. Each service reports its own outcome and the caller can show them or
	 * ignore them.
	 *
	 * A number the tracker already has, or a lower one, is not sent. Trackers treat
	 * progress as absolute, so re-reading chapter 3 of a title already at 40 would tell
	 * the service the user has read three chapters.
	 */
	suspend fun pushProgress(
		mangaId: Long,
		chapters: List<MangaChapter>,
		chapterId: Long,
	): List<PushOutcome> {
		val number = trackerChapterNumber(chapters, chapterId)
		val results = mutableListOf<PushOutcome>()
		for (service in TrackingService.entries) {
			val row = db.scrobblingDao().find(mangaId, service.key) ?: continue
			if (stateOf(service) !is ServiceState.Connected) {
				continue
			}
			if (number == null) {
				results += PushOutcome(service, null, "That chapter is not in the list this build loaded.")
				continue
			}
			if (number <= row.chapter) {
				results += PushOutcome(service, null, null)
				continue
			}
			results += try {
				val entry = trackerOf(service).pushProgress(row.remoteId, row.targetId, number)
				val stored = store(service, mangaId, entry)
				sessionOf(service).recordSync(now())
				republish(service)
				PushOutcome(service, stored.chapter, null)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordFailure(service, e)
				PushOutcome(service, null, describe(e))
			}
		}
		return results
	}

	private suspend fun store(service: TrackingService, mangaId: Long, entry: RemoteEntry): TrackedTitle {
		val row = ScrobblingEntity(
			mangaId = mangaId,
			service = service.key,
			remoteId = entry.remoteId,
			targetId = entry.targetId,
			status = entry.rawStatus,
			chapter = entry.chapter,
			rating = entry.rating,
			comment = entry.comment,
			updatedAt = now(),
		)
		db.scrobblingDao().upsert(row)
		return row.toTrackedTitle() ?: error("Just wrote a row for ${service.key} that cannot be read back")
	}

	/** Runs [block], turning a failure into a visible service state on the way out. */
	private suspend fun <T> guarded(service: TrackingService, block: suspend () -> T): T = try {
		val result = block()
		sessionOf(service).recordSync(now())
		republish(service)
		result
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		recordFailure(service, e)
		throw e
	}

	private fun recordFailure(service: TrackingService, error: Throwable) {
		val lastSync = sessionOf(service).stored()?.lastSyncAt ?: 0L
		// A failed refresh has already cleared the token, so the session now reports
		// Disconnected and that is the honest state; only a still-connected service is
		// downgraded to Failing.
		val base = sessionOf(service).state()
		stateFlow.value = stateFlow.value + (
			service to if (base is ServiceState.Connected) {
				ServiceState.Failing(describe(error), lastSync)
			} else {
				base
			}
			)
	}

	private fun republish(service: TrackingService) {
		stateFlow.value = stateFlow.value + (service to sessionOf(service).state())
	}

	private fun computeStates(): Map<TrackingService, ServiceState> =
		TrackingService.entries.associateWith { service ->
			sessions[service]?.state()
				?: ServiceState.Unavailable("${service.label} is not built into this version.")
		}

	private fun sessionOf(service: TrackingService): ServiceSession = sessions[service]
		?: throw TrackingUnavailableException("${service.label} is not built into this version.")

	private fun trackerOf(service: TrackingService): TrackerService = trackers[service]
		?: throw TrackingUnavailableException("${service.label} is not built into this version.")

	private fun ScrobblingEntity.toTrackedTitle(): TrackedTitle? {
		val known = TrackingService.byKey(service) ?: return null
		return TrackedTitle(
			service = known,
			mangaId = mangaId,
			remoteId = remoteId,
			targetId = targetId,
			status = status?.let { statusFromRaw(known, it) },
			chapter = chapter,
			rating = rating,
			comment = comment,
			updatedAt = updatedAt,
		)
	}
}

/** The shortest true sentence about [error] that can go in front of a user. */
internal fun describe(error: Throwable): String =
	error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "Unknown error"
