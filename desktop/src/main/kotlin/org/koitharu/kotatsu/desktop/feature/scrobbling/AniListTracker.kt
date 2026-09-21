package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * AniList's per-account score scale.
 *
 * AniList returns a bare number and the scale it is on is a property of the account, not
 * of the entry, so a score is meaningless without this. It is read once when the account
 * loads and kept with the token.
 */
enum class AniListScoreFormat(private val max: Float) {

	POINT_100(100f),
	POINT_10_DECIMAL(10f),
	POINT_10(10f),
	POINT_5(5f),
	POINT_3(3f),
	;

	fun normalize(score: Float): Float = (score / max).coerceIn(0f, 1f)

	companion object {

		/** AniList's own default when an account has never chosen one. */
		val Default = POINT_10_DECIMAL

		fun of(raw: String?): AniListScoreFormat =
			entries.firstOrNull { it.name == raw } ?: Default
	}
}

/**
 * AniList, over its GraphQL endpoint.
 *
 * Ported from the Android `AniListRepository`, with the same queries. Two differences,
 * both deliberate: the entry fields are requested through one shared fragment-shaped
 * constant instead of being retyped at each of the six call sites, and the score is
 * written with `scoreRaw` (always 0..100) while being read through the account's own
 * format, which is the only combination that survives an account whose format is
 * POINT_5 or POINT_3.
 */
class AniListTracker(private val session: ServiceSession) : TrackerService {

	override val service: TrackingService get() = TrackingService.AniList

	override suspend fun loadAccount(): TrackerAccount {
		val data = query(
			"""
			AniChartUser {
				user { id name avatar { medium } mediaListOptions { scoreFormat } }
			}
			""",
		)
		val user = data.requireChild("AniChartUser", WHAT).requireChild("user", WHAT)
		val account = TrackerAccount(
			id = user.requireLong("id", WHAT),
			nickname = user.string("name") ?: "AniList user",
			avatarUrl = user.child("avatar")?.string("medium"),
		)
		val format = user.child("mediaListOptions")?.string("scoreFormat")
		session.recordAccount(account, format)
		return account
	}

	override suspend fun search(query: String, type: TrackedMediaType, offset: Int): List<TrackerMatch> {
		val page = offset / PAGE_SIZE + 1
		// AniList files prose as the NOVEL format under the MANGA type, so the type is a
		// format filter rather than a different media type.
		val formatFilter = if (type == TrackedMediaType.Novel) "format: NOVEL" else "format_not: NOVEL"
		val data = query(
			"""
			Page(page: $page, perPage: $PAGE_SIZE) {
				media(type: MANGA, $formatFilter, sort: SEARCH_MATCH, search: ${graphQlString(query)}) {
					id title { userPreferred native english romaji } coverImage { medium } siteUrl
				}
			}
			""",
		)
		val media = data.requireChild("Page", WHAT).array("media").orEmpty()
		return media.mapNotNull { element ->
			val item = element.asObject() ?: return@mapNotNull null
			val titles = item.child("title")
			val id = item.long("id") ?: return@mapNotNull null
			TrackerMatch(
				targetId = id,
				title = titles?.string("userPreferred") ?: "#$id",
				altTitle = titles?.string("native"),
				coverUrl = item.child("coverImage")?.string("medium"),
				url = item.string("siteUrl") ?: "https://anilist.co/manga/$id",
				// Any of the four title spellings matching exactly is a strong enough
				// signal to put the entry first; the user still confirms.
				isExactMatch = titles != null && TITLE_KEYS.any { key ->
					titles.string(key)?.equals(query, ignoreCase = true) == true
				},
			)
		}
	}

	override suspend fun titleInfo(targetId: Long): TrackerTitleInfo {
		val data = query(
			"""
			Media(id: $targetId) {
				id title { userPreferred } coverImage { large } description siteUrl chapters
			}
			""",
		)
		val media = data.requireChild("Media", WHAT)
		return TrackerTitleInfo(
			targetId = media.long("id") ?: targetId,
			title = media.child("title")?.string("userPreferred") ?: "#$targetId",
			coverUrl = media.child("coverImage")?.string("large"),
			url = media.string("siteUrl") ?: "https://anilist.co/manga/$targetId",
			descriptionHtml = media.string("description").orEmpty(),
			totalChapters = media.int("chapters"),
		)
	}

	override suspend fun linkTitle(targetId: Long): LinkResult {
		// Creating an entry defaults it to CURRENT with a zero score, so an entry already
		// on the user's list has to be adopted before anything is written. mediaListEntry
		// is simply absent when the account has not listed this title.
		val existing = query("Media(id: $targetId) { mediaListEntry { $ENTRY_FIELDS } }")
			.requireChild("Media", WHAT)
			.child("mediaListEntry")
		if (existing != null) {
			return LinkResult(existing.toEntry(), wasAlreadyTracked = true)
		}
		val created = mutation("SaveMediaListEntry(mediaId: $targetId) { $ENTRY_FIELDS }")
			.requireChild("SaveMediaListEntry", WHAT)
		return LinkResult(created.toEntry(), wasAlreadyTracked = false)
	}

	override suspend fun fetchEntry(remoteId: Long, targetId: Long): RemoteEntry =
		query("MediaList(id: $remoteId) { $ENTRY_FIELDS }")
			.requireChild("MediaList", WHAT)
			.toEntry()

	override suspend fun pushProgress(remoteId: Long, targetId: Long, chapter: Int): RemoteEntry =
		mutation("SaveMediaListEntry(id: $remoteId, progress: $chapter) { $ENTRY_FIELDS }")
			.requireChild("SaveMediaListEntry", WHAT)
			.toEntry()

	override suspend fun pushState(
		remoteId: Long,
		targetId: Long,
		rating: Float,
		status: TrackingStatus?,
		comment: String?,
		setStartDate: Boolean,
	): RemoteEntry {
		// scoreRaw is always on the 0..100 scale whatever the account's display format is,
		// which is why writing goes through it and only reading consults the format.
		val scoreRaw = (rating.coerceIn(0f, 1f) * 100f).roundToInt()
		val statusPart = status?.let { statusToRaw(service, it) }?.let { ", status: $it" }.orEmpty()
		val notesPart = comment?.let { ", notes: ${graphQlString(it)}" }.orEmpty()
		val startPart = if (setStartDate) {
			val today = LocalDate.now()
			", startedAt: { year: ${today.year}, month: ${today.monthValue}, day: ${today.dayOfMonth} }"
		} else {
			""
		}
		return mutation(
			"SaveMediaListEntry(id: $remoteId, scoreRaw: $scoreRaw$statusPart$notesPart$startPart) { $ENTRY_FIELDS }",
		).requireChild("SaveMediaListEntry", WHAT).toEntry()
	}

	private fun JsonObject.toEntry(): RemoteEntry {
		val format = AniListScoreFormat.of(session.scoreFormat())
		return RemoteEntry(
			remoteId = requireLong("id", WHAT),
			targetId = long("mediaId") ?: 0L,
			rawStatus = string("status"),
			chapter = int("progress"),
			rating = format.normalize(float("score")),
			comment = string("notes"),
		)
	}

	private suspend fun query(payload: String): JsonObject = doRequest("query", payload)

	private suspend fun mutation(payload: String): JsonObject = doRequest("mutation", payload)

	private suspend fun doRequest(kind: String, payload: String): JsonObject {
		val body = buildJsonObject {
			put("query", JsonPrimitive("$kind { ${payload.replace(WHITESPACE, " ").trim()} }"))
		}
		val response = session.request(
			HttpRequest(
				method = "POST",
				url = ENDPOINT,
				headers = mapOf("Accept" to "application/json"),
				body = HttpBody.Json(body.toString()),
			),
		)
		val json = parseJsonObject(response.body, WHAT)
		// AniList answers a partly-failed query with HTTP 200 and an errors array, so the
		// status code alone never tells you whether the call did anything.
		json.array("errors")?.takeIf { it.isNotEmpty() }?.let { errors ->
			val message = errors.mapNotNull { it.asObject()?.string("message") }
				.joinToString("; ")
				.ifEmpty { errors.toString() }
			throw TrackingApiException("AniList refused the request: $message")
		}
		if (!response.isSuccessful) {
			throw TrackingApiException("AniList returned HTTP ${response.code}")
		}
		return json.requireChild("data", WHAT)
	}

	private companion object {

		const val ENDPOINT = "https://graphql.anilist.co"
		const val PAGE_SIZE = 10
		const val WHAT = "AniList"
		const val ENTRY_FIELDS = "id mediaId status notes score progress"

		val TITLE_KEYS = listOf("userPreferred", "native", "english", "romaji")
		val WHITESPACE = Regex("\\s+")
	}
}
