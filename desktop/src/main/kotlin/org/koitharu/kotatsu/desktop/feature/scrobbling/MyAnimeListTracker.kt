package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * MyAnimeList, over the v2 REST API.
 *
 * MAL has no list-entry id: an entry is addressed by the manga id, so `remote_id` and
 * `target_id` hold the same value for every MAL row. That is recorded here rather than
 * left to be rediscovered, because the two columns exist for AniList, where they differ.
 *
 * MAL also omits every empty field from `my_list_status`, so nothing read back from it can
 * be treated as required.
 */
class MyAnimeListTracker(private val session: ServiceSession) : TrackerService {

	override val service: TrackingService get() = TrackingService.MyAnimeList

	override suspend fun loadAccount(): TrackerAccount {
		val json = get("$API/users/@me")
		val account = TrackerAccount(
			id = json.requireLong("id", WHAT),
			nickname = json.string("name") ?: "MyAnimeList user",
			avatarUrl = json.string("picture"),
		)
		session.recordAccount(account)
		return account
	}

	/**
	 * MAL's search has no media-type parameter, so novels are filtered out of the page
	 * here. A page therefore yields fewer than [PAGE_SIZE] items, which offset paging
	 * absorbs by re-requesting the overlap.
	 */
	override suspend fun search(query: String, type: TrackedMediaType, offset: Int): List<TrackerMatch> {
		val url = "$API/manga".toHttpUrl().newBuilder()
			.addQueryParameter("offset", offset.toString())
			.addQueryParameter("limit", PAGE_SIZE.toString())
			.addQueryParameter("nsfw", "true")
			.addQueryParameter("fields", "media_type,main_picture")
			// MAL answers 400 to a query longer than 64 characters.
			.addQueryParameter("q", query.take(MAX_QUERY_LENGTH))
			.build()
			.toString()
		val data = get(url).array("data").orEmpty()
		return data.mapNotNull { element ->
			val node = element.asObject()?.child("node") ?: return@mapNotNull null
			val mediaType = node.string("media_type")
			// An unrecognised media_type counts as a comic, so nothing disappears from
			// both tabs at once.
			if ((mediaType in NOVEL_TYPES) != (type == TrackedMediaType.Novel)) {
				return@mapNotNull null
			}
			val id = node.long("id") ?: return@mapNotNull null
			val title = node.string("title") ?: "#$id"
			TrackerMatch(
				targetId = id,
				title = title,
				altTitle = null,
				coverUrl = node.child("main_picture")?.string("large"),
				url = "$WEB/manga/$id",
				isExactMatch = title.equals(query, ignoreCase = true),
			)
		}
	}

	override suspend fun titleInfo(targetId: Long): TrackerTitleInfo {
		val url = "$API/manga/$targetId".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "synopsis,num_chapters,main_picture")
			.build()
			.toString()
		val json = get(url)
		return TrackerTitleInfo(
			targetId = json.long("id") ?: targetId,
			title = json.string("title") ?: "#$targetId",
			coverUrl = json.child("main_picture")?.string("large"),
			url = "$WEB/manga/$targetId",
			descriptionHtml = json.string("synopsis").orEmpty(),
			totalChapters = json.int("num_chapters"),
		)
	}

	override suspend fun linkTitle(targetId: Long): LinkResult {
		// MAL has no create endpoint, only a PUT that would zero the score and force
		// "reading", so an entry already on the website has to be picked up first.
		val existing = statusOf(targetId)
		if (existing != null) {
			return LinkResult(existing.toEntry(targetId), wasAlreadyTracked = true)
		}
		val created = put(
			targetId,
			listOf("status" to "reading", "score" to "0"),
		)
		return LinkResult(created.toEntry(targetId), wasAlreadyTracked = false)
	}

	override suspend fun fetchEntry(remoteId: Long, targetId: Long): RemoteEntry {
		val status = statusOf(targetId)
			?: throw TrackingApiException("MyAnimeList no longer lists this title on your account.")
		return status.toEntry(targetId)
	}

	override suspend fun pushProgress(remoteId: Long, targetId: Long, chapter: Int): RemoteEntry =
		put(targetId, listOf("num_chapters_read" to chapter.toString())).toEntry(targetId)

	override suspend fun pushState(
		remoteId: Long,
		targetId: Long,
		rating: Float,
		status: TrackingStatus?,
		comment: String?,
		setStartDate: Boolean,
	): RemoteEntry {
		val fields = mutableListOf<Pair<String, String>>()
		// MAL rejects an unknown status outright, so an unmapped one (re-reading) is left
		// off the request rather than sent as something else.
		status?.let { statusToRaw(service, it) }?.let { fields += "status" to it }
		fields += "score" to (rating.coerceIn(0f, 1f) * SCORE_MAX).roundToInt().toString()
		comment?.let { fields += "comments" to it }
		if (setStartDate) {
			fields += "start_date" to LocalDate.now().toString()
		}
		return put(targetId, fields).toEntry(targetId)
	}

	/** Null when the title is not on the user's list, which MAL signals by omitting the object. */
	private suspend fun statusOf(targetId: Long): JsonObject? {
		val url = "$API/manga/$targetId".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "my_list_status")
			.build()
			.toString()
		return get(url).child("my_list_status")
	}

	private fun JsonObject.toEntry(targetId: Long) = RemoteEntry(
		// MAL addresses a list entry by its manga id, so both columns hold the same value.
		remoteId = targetId,
		targetId = targetId,
		rawStatus = string("status"),
		chapter = int("num_chapters_read"),
		rating = (float("score") / SCORE_MAX).coerceIn(0f, 1f),
		comment = string("comments"),
	)

	private suspend fun get(url: String): JsonObject = send(HttpRequest(method = "GET", url = url))

	private suspend fun put(targetId: Long, fields: List<Pair<String, String>>): JsonObject = send(
		HttpRequest(
			method = "PUT",
			url = "$API/manga/$targetId/my_list_status",
			body = HttpBody.Form(fields),
		),
	)

	private suspend fun send(request: HttpRequest): JsonObject {
		val response = session.request(request.copy(headers = request.headers + ("Accept" to "application/json")))
		if (!response.isSuccessful) {
			val reason = runCatching { parseJsonObject(response.body, WHAT).string("message") }
				.getOrNull()
				?: response.body.lineSequence().firstOrNull().orEmpty()
			throw TrackingApiException(
				"MyAnimeList returned HTTP ${response.code}${if (reason.isBlank()) "" else ": $reason"}",
			)
		}
		return parseJsonObject(response.body, WHAT)
	}

	private companion object {

		const val WEB = "https://myanimelist.net"
		const val API = "https://api.myanimelist.net/v2"
		const val PAGE_SIZE = 20
		const val MAX_QUERY_LENGTH = 64
		const val WHAT = "MyAnimeList"

		/** MAL scores are whole numbers 0..10. */
		const val SCORE_MAX = 10f

		val NOVEL_TYPES = setOf("novel", "light_novel")
	}
}
