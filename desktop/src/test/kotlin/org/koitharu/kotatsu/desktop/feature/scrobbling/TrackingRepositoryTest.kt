package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.io.File
import java.nio.file.Path

/**
 * A [TrackerService] that records what it was asked to do and answers from memory.
 *
 * The real clients are covered by [ServiceSessionTest] at the HTTP layer; what matters
 * here is that the repository sends the right chapter number, writes the right row, and
 * refuses to act for a service that is not connected.
 */
private class FakeTracker(
	override val service: TrackingService,
	var existing: RemoteEntry? = null,
) : TrackerService {

	val pushedChapters = mutableListOf<Int>()
	val pushedStates = mutableListOf<Triple<Float, TrackingStatus?, String?>>()
	var searchCalls = 0
	var current: RemoteEntry = RemoteEntry(
		remoteId = 500L,
		targetId = 900L,
		rawStatus = "reading",
		chapter = 0,
		rating = 0f,
		comment = null,
	)

	override suspend fun loadAccount() = TrackerAccount(1L, "tester", null)

	override suspend fun search(query: String, type: TrackedMediaType, offset: Int): List<TrackerMatch> {
		searchCalls++
		return listOf(TrackerMatch(900L, query, null, null, "https://example.test/900", true))
	}

	override suspend fun titleInfo(targetId: Long) =
		TrackerTitleInfo(targetId, "Title", null, "https://example.test/$targetId", "", 100)

	override suspend fun linkTitle(targetId: Long): LinkResult {
		val found = existing
		return if (found != null) {
			current = found
			LinkResult(found, wasAlreadyTracked = true)
		} else {
			current = current.copy(targetId = targetId)
			LinkResult(current, wasAlreadyTracked = false)
		}
	}

	override suspend fun fetchEntry(remoteId: Long, targetId: Long) = current

	override suspend fun pushProgress(remoteId: Long, targetId: Long, chapter: Int): RemoteEntry {
		pushedChapters += chapter
		current = current.copy(chapter = chapter)
		return current
	}

	override suspend fun pushState(
		remoteId: Long,
		targetId: Long,
		rating: Float,
		status: TrackingStatus?,
		comment: String?,
		setStartDate: Boolean,
	): RemoteEntry {
		pushedStates += Triple(rating, status, comment)
		current = current.copy(
			rating = rating,
			rawStatus = status?.let { statusToRaw(service, it) } ?: current.rawStatus,
			comment = comment,
		)
		return current
	}
}

class TrackingRepositoryTest {

	@get:Rule
	val temp = TemporaryFolder()

	private val service = TrackingService.MyAnimeList
	private val mangaId = 42L
	private val clock = 7_000L

	private lateinit var dir: Path
	private lateinit var db: LibraryDatabase
	private lateinit var tracker: FakeTracker

	@Before
	fun setUp() {
		dir = temp.newFolder("config").toPath()
		db = openLibraryDatabase(File(temp.newFolder("data"), "library.db").toOkioPath(), now = { clock })
		tracker = FakeTracker(service)
	}

	@After
	fun tearDown() {
		db.close()
	}

	private fun repository(
		connected: Boolean = true,
		configured: Boolean = true,
	): TrackingRepository {
		val tokens = TokenStore(dir.resolve(TokenStore.FILE_NAME))
		if (connected) {
			tokens.put(service, StoredTokens(accessToken = "at", refreshToken = "rt"))
		} else {
			tokens.remove(service)
		}
		val http = ScriptedHttp { throw AssertionError("the repository made a raw HTTP call") }
		val session = testSession(
			service = service,
			dir = dir,
			http = http,
			credentialsSource = credentials(service to if (configured) testCredentials(secret = null) else null),
			now = { clock },
		)
		return TrackingRepository(
			db = db,
			sessions = mapOf(service to session),
			trackers = mapOf(service to tracker),
			now = { clock },
		)
	}

	@Test
	fun `linking a fresh entry writes the row and fills in the starting status`() = runTest {
		val repository = repository()

		val link = repository.link(service, mangaId, targetId = 900L, fallbackStatus = TrackingStatus.Reading)

		assertEquals(900L, link.targetId)
		assertEquals(TrackingStatus.Reading, link.status)
		assertEquals(clock, link.updatedAt)
		// The new entry had nothing worth keeping, so a status was written for it.
		assertEquals(1, tracker.pushedStates.size)
		assertEquals(TrackingStatus.Reading, tracker.pushedStates.single().second)

		val stored = requireNotNull(db.scrobblingDao().find(mangaId, service.key))
		assertEquals("MAL", stored.service)
		assertEquals(900L, stored.targetId)
	}

	@Test
	fun `an entry already on the service keeps its own progress and rating`() = runTest {
		tracker.existing = RemoteEntry(
			remoteId = 500L,
			targetId = 900L,
			rawStatus = "completed",
			chapter = 140,
			rating = 0.9f,
			comment = "read years ago",
		)
		val repository = repository()

		val link = repository.link(service, mangaId, targetId = 900L, fallbackStatus = TrackingStatus.Reading)

		// Nothing was written back: a status, rating and chapter count set on the website
		// are the user's, and a local library that has never opened the title would
		// otherwise replace 140 chapters with zero.
		assertTrue("an adopted entry was overwritten", tracker.pushedStates.isEmpty())
		assertEquals(140, link.chapter)
		assertEquals(0.9f, link.rating, 0.0001f)
		assertEquals(TrackingStatus.Completed, link.status)
	}

	@Test
	fun `progress is pushed as a whole number, truncating a decimal chapter`() = runTest {
		val repository = repository()
		repository.link(service, mangaId, targetId = 900L)
		val chapters = listOf(chapter(1L, 10f), chapter(2L, 10.5f), chapter(3L, 11f))

		val outcomes = repository.pushProgress(mangaId, chapters, chapterId = 2L)

		assertEquals(listOf(10), tracker.pushedChapters)
		assertEquals(10, outcomes.single().chapter)
		assertTrue(outcomes.single().isPushed)
		assertEquals(10, db.scrobblingDao().find(mangaId, service.key)?.chapter)
	}

	@Test
	fun `an unnumbered chapter is pushed as its position in its branch`() = runTest {
		val repository = repository()
		repository.link(service, mangaId, targetId = 900L)
		val chapters = listOf(
			chapter(1L, 0f, branch = "A"),
			chapter(2L, 0f, branch = "B"),
			chapter(3L, 0f, branch = "A"),
		)

		repository.pushProgress(mangaId, chapters, chapterId = 3L)

		assertEquals(listOf(2), tracker.pushedChapters)
	}

	@Test
	fun `progress already recorded is not pushed again`() = runTest {
		val repository = repository()
		repository.link(service, mangaId, targetId = 900L)
		val chapters = listOf(chapter(1L, 1f), chapter(2L, 2f), chapter(3L, 40f))
		repository.pushProgress(mangaId, chapters, chapterId = 3L)
		tracker.pushedChapters.clear()

		// Re-reading chapter 2 of a title the tracker has at 40 must not report that two
		// chapters have been read. Trackers treat progress as absolute.
		val outcomes = repository.pushProgress(mangaId, chapters, chapterId = 2L)

		assertTrue(tracker.pushedChapters.isEmpty())
		assertNull(outcomes.single().chapter)
		assertNull(outcomes.single().error)
		assertEquals(40, db.scrobblingDao().find(mangaId, service.key)?.chapter)
	}

	@Test
	fun `a title that is not linked pushes nothing at all`() = runTest {
		val repository = repository()

		val outcomes = repository.pushProgress(mangaId, listOf(chapter(1L, 1f)), chapterId = 1L)

		assertTrue(outcomes.isEmpty())
		assertTrue(tracker.pushedChapters.isEmpty())
	}

	@Test
	fun `a disconnected service is skipped rather than attempted`() = runTest {
		repository().link(service, mangaId, targetId = 900L)
		val disconnected = repository(connected = false)

		val outcomes = disconnected.pushProgress(mangaId, listOf(chapter(1L, 5f)), chapterId = 1L)

		assertTrue(outcomes.isEmpty())
		assertTrue(tracker.pushedChapters.isEmpty())
		assertEquals(ServiceState.Disconnected, disconnected.stateOf(service))
	}

	@Test
	fun `a service with no client id is unavailable and is never asked to search`() = runTest {
		val repository = repository(configured = false)

		val state = expectType<ServiceState.Unavailable>(repository.stateOf(service))

		assertTrue(state.reason, state.reason.contains("client id"))
		assertTrue(repository.availableServices().isEmpty())
		assertTrue(repository.connectedServices().isEmpty())
		expectThrows<TrackingUnavailableException> { repository.connect(service, timeoutMillis = 100L) }
		assertEquals("the tracker was called for an unconfigured service", 0, tracker.searchCalls)
	}

	@Test
	fun `a tracker failure is reported against the service and does not throw at the reader`() = runTest {
		repository().link(service, mangaId, targetId = 900L)
		val failing = object : TrackerService by tracker {
			override suspend fun pushProgress(remoteId: Long, targetId: Long, chapter: Int): RemoteEntry =
				throw TrackingApiException("MyAnimeList returned HTTP 503")
		}
		val tokens = TokenStore(dir.resolve(TokenStore.FILE_NAME))
		tokens.put(service, StoredTokens(accessToken = "at", refreshToken = "rt"))
		val repository = TrackingRepository(
			db = db,
			sessions = mapOf(
				service to testSession(
					service = service,
					dir = dir,
					http = ScriptedHttp { HttpResponse(200, "{}") },
					credentialsSource = credentials(service to testCredentials(secret = null)),
					now = { clock },
				),
			),
			trackers = mapOf(service to failing),
			now = { clock },
		)

		val outcomes = repository.pushProgress(mangaId, listOf(chapter(1L, 5f)), chapterId = 1L)

		// Reading must not stop because a tracker is down.
		assertEquals("MyAnimeList returned HTTP 503", outcomes.single().error)
		assertTrue(!outcomes.single().isPushed)
		expectType<ServiceState.Failing>(repository.stateOf(service))
	}

	@Test
	fun `unlinking removes the row and links are observable`() = runTest {
		val repository = repository()
		repository.link(service, mangaId, targetId = 900L)

		assertEquals(1, repository.observeLinks(mangaId).first().size)

		repository.unlink(service, mangaId)

		assertTrue(repository.observeLinks(mangaId).first().isEmpty())
		assertNull(repository.linkFor(service, mangaId))
	}

	@Test
	fun `forgetting a service drops its token and every link it owns`() = runTest {
		val repository = repository()
		repository.link(service, mangaId, targetId = 900L)
		repository.link(service, mangaId = 43L, targetId = 901L)

		repository.forget(service)

		assertTrue(repository.observeLinks(mangaId).first().isEmpty())
		assertTrue(repository.observeLinks(43L).first().isEmpty())
		assertEquals(ServiceState.Disconnected, repository.stateOf(service))
	}

	@Test
	fun `refreshing reads the entry back from the service`() = runTest {
		val repository = repository()
		repository.link(service, mangaId, targetId = 900L)
		tracker.current = tracker.current.copy(rawStatus = "on_hold", chapter = 17, rating = 0.7f)

		val refreshed = requireNotNull(repository.refresh(service, mangaId))

		assertEquals(TrackingStatus.OnHold, refreshed.status)
		assertEquals(17, refreshed.chapter)
		assertEquals(0.7f, refreshed.rating, 0.0001f)
		assertEquals(17, db.scrobblingDao().find(mangaId, service.key)?.chapter)
	}

	@Test
	fun `refreshing a title that is not linked returns nothing`() = runTest {
		assertNull(repository().refresh(service, mangaId))
	}
}
