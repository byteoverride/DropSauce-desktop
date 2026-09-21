package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/**
 * The two API clients, against scripted replies.
 *
 * These cover the shapes that are easy to get wrong and impossible to notice: AniList
 * reporting an error with HTTP 200, AniList scores being on a per-account scale, and
 * MyAnimeList omitting every field it has no value for.
 */
class TrackerClientsTest {

	@get:Rule
	val temp = TemporaryFolder()

	private lateinit var dir: Path
	private val clock = 5_000L

	@Before
	fun setUp() {
		dir = temp.newFolder("config").toPath()
	}

	private fun session(service: TrackingService, http: ScriptedHttp): ServiceSession {
		TokenStore(dir.resolve(TokenStore.FILE_NAME)).put(service, StoredTokens(accessToken = "at"))
		return testSession(
			service = service,
			dir = dir,
			http = http,
			credentialsSource = credentials(service to testCredentials()),
			now = { clock },
		)
	}

	private fun anilist(http: ScriptedHttp) = AniListTracker(session(TrackingService.AniList, http))

	private fun mal(http: ScriptedHttp) = MyAnimeListTracker(session(TrackingService.MyAnimeList, http))

	// ---- AniList ----

	@Test
	fun `AniList reports a GraphQL error even though the status was 200`() = runTest {
		val http = ScriptedHttp {
			HttpResponse(200, """{"errors":[{"message":"Invalid token"}],"data":null}""")
		}

		val error = expectThrows<TrackingApiException> { anilist(http).titleInfo(1L) }

		assertTrue(error.message!!, error.message!!.contains("Invalid token"))
	}

	@Test
	fun `AniList normalises a score against the account's own format`() = runTest {
		val http = ScriptedHttp { request ->
			val query = (request.body as HttpBody.Json).text
			when {
				query.contains("AniChartUser") -> HttpResponse(
					200,
					"""{"data":{"AniChartUser":{"user":{"id":9,"name":"reader","avatar":{"medium":"a.png"},
						"mediaListOptions":{"scoreFormat":"POINT_5"}}}}}""".trimIndent(),
				)

				else -> HttpResponse(
					200,
					"""{"data":{"MediaList":{"id":11,"mediaId":22,"status":"CURRENT",
						"notes":"note","score":4,"progress":7}}}""".trimIndent(),
				)
			}
		}
		val tracker = anilist(http)

		val account = tracker.loadAccount()
		val entry = tracker.fetchEntry(remoteId = 11L, targetId = 22L)

		assertEquals("reader", account.nickname)
		// 4 out of 5, not 4 out of 10 and not 4 out of 100. Reading the score without the
		// account's format is how a 4/5 becomes a 0.04.
		assertEquals(0.8f, entry.rating, 0.0001f)
		assertEquals(7, entry.chapter)
		assertEquals(TrackingStatus.Reading, entry.statusOrNull(TrackingService.AniList))
	}

	@Test
	fun `AniList adopts an entry that is already on the list instead of creating one`() = runTest {
		val http = ScriptedHttp {
			HttpResponse(
				200,
				"""{"data":{"Media":{"mediaListEntry":{"id":11,"mediaId":22,"status":"COMPLETED",
					"notes":null,"score":90,"progress":140}}}}""".trimIndent(),
			)
		}

		val result = anilist(http).linkTitle(22L)

		assertTrue(result.wasAlreadyTracked)
		assertEquals(140, result.entry.chapter)
		assertEquals(11L, result.entry.remoteId)
		// One call, and it was a read. Creating over an existing entry would reset the
		// score and force "reading" on a title the user finished years ago.
		assertEquals(1, http.requests.size)
		assertTrue(graphQlOf(http.requests.single()).startsWith("query "))
	}

	@Test
	fun `AniList creates an entry when the account has not listed the title`() = runTest {
		val http = ScriptedHttp { request ->
			val query = (request.body as HttpBody.Json).text
			if (query.contains("mediaListEntry")) {
				// AniList omits mediaListEntry entirely for a title the user has not listed.
				HttpResponse(200, """{"data":{"Media":{"mediaListEntry":null}}}""")
			} else {
				HttpResponse(
					200,
					"""{"data":{"SaveMediaListEntry":{"id":12,"mediaId":22,"status":"CURRENT",
						"notes":null,"score":0,"progress":0}}}""".trimIndent(),
				)
			}
		}

		val result = anilist(http).linkTitle(22L)

		assertTrue(!result.wasAlreadyTracked)
		assertEquals(12L, result.entry.remoteId)
		assertEquals(2, http.requests.size)
		assertTrue(graphQlOf(http.requests[0]).startsWith("query "))
		assertTrue(graphQlOf(http.requests[1]).startsWith("mutation "))
	}

	@Test
	fun `AniList pushes progress as a mutation on the entry id`() = runTest {
		val http = ScriptedHttp {
			HttpResponse(
				200,
				"""{"data":{"SaveMediaListEntry":{"id":11,"mediaId":22,"status":"CURRENT",
					"notes":null,"score":0,"progress":13}}}""".trimIndent(),
			)
		}

		val entry = anilist(http).pushProgress(remoteId = 11L, targetId = 22L, chapter = 13)

		val query = graphQlOf(http.requests.single())
		assertTrue(query, query.contains("SaveMediaListEntry(id: 11, progress: 13)"))
		assertEquals(13, entry.chapter)
	}

	@Test
	fun `AniList escapes a search term rather than splicing it into the query`() = runTest {
		val http = ScriptedHttp { HttpResponse(200, """{"data":{"Page":{"media":[]}}}""") }
		val term = "a \"quoted\" title"

		anilist(http).search(term, TrackedMediaType.Manga)

		// The term is a GraphQL string literal, not text spliced into the query. A title
		// containing a quote is not rare, and splicing one breaks the whole request.
		val query = graphQlOf(http.requests.single())
		assertTrue(query, query.contains("search: ${graphQlString(term)}"))
	}

	/** The GraphQL document inside a request body, with the JSON envelope removed. */
	private fun graphQlOf(request: HttpRequest): String {
		val body = parseJsonObject((request.body as HttpBody.Json).text, "test")
		return requireNotNull(body.string("query"))
	}

	// ---- MyAnimeList ----

	@Test
	fun `MyAnimeList keeps comics out of the novel tab and novels out of the comic tab`() = runTest {
		val body = """
			{"data":[
			{"node":{"id":1,"title":"A comic","media_type":"manga","main_picture":{"large":"1.png"}}},
			{"node":{"id":2,"title":"A novel","media_type":"light_novel"}},
			{"node":{"id":3,"title":"Something else","media_type":"unknown_thing"}}
			]}
		""".trimIndent()
		val http = ScriptedHttp { HttpResponse(200, body) }

		val comics = mal(http).search("x", TrackedMediaType.Manga)
		val novels = mal(http).search("x", TrackedMediaType.Novel)

		// An unrecognised media_type counts as a comic, so nothing vanishes from both tabs.
		assertEquals(listOf(1L, 3L), comics.map { it.targetId })
		assertEquals(listOf(2L), novels.map { it.targetId })
		assertEquals("1.png", comics.first().coverUrl)
	}

	@Test
	fun `MyAnimeList reads an entry that omits every empty field`() = runTest {
		// This is a real shape: MAL drops score, comments and num_chapters_read when
		// they are unset. A required-field parser would throw on a normal new entry.
		val http = ScriptedHttp { HttpResponse(200, """{"id":22,"my_list_status":{"status":"plan_to_read"}}""") }

		val entry = mal(http).fetchEntry(remoteId = 22L, targetId = 22L)

		assertEquals(0, entry.chapter)
		assertEquals(0f, entry.rating, 0.0001f)
		assertNull(entry.comment)
		assertEquals(TrackingStatus.Planned, entry.statusOrNull(TrackingService.MyAnimeList))
		// MAL addresses an entry by the manga id, so both columns carry the same value.
		assertEquals(entry.remoteId, entry.targetId)
	}

	@Test
	fun `MyAnimeList pushes progress as a form PUT on the entry`() = runTest {
		val http = ScriptedHttp { HttpResponse(200, """{"status":"reading","num_chapters_read":13,"score":8}""") }

		val entry = mal(http).pushProgress(remoteId = 22L, targetId = 22L, chapter = 13)

		val request = http.requests.single()
		assertEquals("PUT", request.method)
		assertEquals("https://api.myanimelist.net/v2/manga/22/my_list_status", request.url)
		assertEquals("13", request.formFields()["num_chapters_read"])
		assertEquals(13, entry.chapter)
		assertEquals(0.8f, entry.rating, 0.0001f)
	}

	@Test
	fun `MyAnimeList leaves out a status it has no word for`() = runTest {
		val http = ScriptedHttp { HttpResponse(200, """{"status":"reading","num_chapters_read":1}""") }

		mal(http).pushState(
			remoteId = 22L,
			targetId = 22L,
			rating = 0.5f,
			status = TrackingStatus.ReReading,
			comment = null,
		)

		// MAL has no re-reading status; sending an invented one would be rejected, and
		// sending "reading" instead would silently change what the user asked for.
		val fields = http.requests.single().formFields()
		assertTrue(fields.toString(), !fields.containsKey("status"))
		assertEquals("5", fields["score"])
	}

	@Test
	fun `MyAnimeList surfaces the reason for a rejected call`() = runTest {
		val http = ScriptedHttp { HttpResponse(403, """{"message":"invalid token","error":"forbidden"}""") }

		val error = expectThrows<TrackingApiException> { mal(http).titleInfo(22L) }

		assertTrue(error.message!!, error.message!!.contains("403"))
		assertTrue(error.message!!, error.message!!.contains("invalid token"))
	}

	@Test
	fun `MyAnimeList truncates a search term the API would reject`() = runTest {
		val http = ScriptedHttp { HttpResponse(200, """{"data":[]}""") }
		val longTitle = "x".repeat(200)

		mal(http).search(longTitle, TrackedMediaType.Manga)

		// MAL answers 400 to a query over 64 characters.
		val sent = http.requests.single().url
		assertTrue(sent, sent.contains("q=" + "x".repeat(64) + "&") || sent.endsWith("q=" + "x".repeat(64)))
	}
}
