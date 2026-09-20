package org.koitharu.kotatsu.desktop.feature.reading

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import org.koitharu.kotatsu.shared.db.StatsEntity
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Statistics against a real database and a frozen clock.
 *
 * Every window here is a calendar window, so "correct" depends on where midnight falls.
 * The clock carries the zone as well as the instant, which is why the repository takes a
 * [Clock] rather than a `() -> Long`.
 */
class StatsRepositoryTest {

	private lateinit var db: LibraryDatabase
	private lateinit var file: java.io.File

	/** 15 March 2026, 10:00 UTC. A Sunday, mid-month, nowhere near a DST change. */
	private val nowUtc: Instant = Instant.parse("2026-03-15T10:00:00Z")

	private val utcClock: Clock = Clock.fixed(nowUtc, ZoneOffset.UTC)

	@Before
	fun setUp() {
		file = Files.createTempFile("stats", ".db").toFile()
		file.delete()
		db = openLibraryDatabase(file.toPath().toOkioPath(), now = { 1_000L })
	}

	@After
	fun tearDown() {
		db.close()
		file.delete()
	}

	private fun repository(clock: Clock = utcClock) = StatsRepository(db, clock)

	private suspend fun insert(mangaId: Long, at: Instant, duration: Long, pages: Int) {
		db.statsDao().upsert(
			StatsEntity(
				id = 0,
				mangaId = mangaId,
				startedAt = at.toEpochMilli(),
				duration = duration,
				pages = pages,
			),
		)
	}

	private suspend fun insertManga(id: Long, title: String) {
		db.mangaDao().upsert(
			MangaEntity(
				mangaId = id, title = title, altTitle = null, url = "/m/$id",
				publicUrl = "https://example.test/m/$id", rating = 0f, contentRating = null,
				coverUrl = null, largeCoverUrl = null, state = null, author = null,
				source = "MANGADEX", chaptersCount = 0,
			),
		)
	}

	private fun entityOn(date: LocalDate, zone: ZoneId = ZoneOffset.UTC, hour: Int = 12) =
		StatsEntity(
			id = 0,
			mangaId = 1L,
			startedAt = date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli(),
			duration = 60_000L,
			pages = 5,
		)

	@Test
	fun `an empty table aggregates to zero rather than throwing`() = runBlocking {
		val report = repository().observeReport(StatsWindow.ALL).first()
		assertTrue(report.isEmpty)
		assertEquals(0L, report.totalDuration)
		assertEquals(0, report.totalPages)
		assertEquals(0, report.sessions)
		assertEquals(0, report.streakDays)
		assertEquals(0L, report.busiestDay)
		assertTrue(report.topTitles.isEmpty())
		// The chart still has its full span, so an empty screen is not a blank rectangle.
		assertEquals(StatsMath.ALL_TIME_CHART_DAYS, report.days.size)
		assertTrue(report.days.all { it.duration == 0L && it.pages == 0 })
	}

	@Test
	fun `the seven day window starts at midnight and includes a session on the boundary`() =
		runBlocking {
			val boundary = Instant.parse("2026-03-09T00:00:00Z")
			assertEquals(
				boundary.toEpochMilli(),
				StatsMath.windowStart(StatsWindow.WEEK, utcClock),
			)
			insert(1L, boundary, duration = 100L, pages = 1)
			insert(1L, boundary.minusMillis(1), duration = 200L, pages = 2)
			insert(1L, nowUtc, duration = 400L, pages = 4)

			val week = repository().observeReport(StatsWindow.WEEK).first()
			// The boundary session counts, the one a millisecond earlier does not.
			assertEquals(500L, week.totalDuration)
			assertEquals(5, week.totalPages)
			assertEquals(2, week.sessions)

			val month = repository().observeReport(StatsWindow.MONTH).first()
			assertEquals(700L, month.totalDuration)
			assertEquals(7, month.totalPages)
			assertEquals(3, month.sessions)
		}

	@Test
	fun `the thirty day window starts at midnight thirty days back`() = runBlocking {
		val boundary = Instant.parse("2026-02-14T00:00:00Z")
		assertEquals(boundary.toEpochMilli(), StatsMath.windowStart(StatsWindow.MONTH, utcClock))
		insert(1L, boundary, duration = 10L, pages = 1)
		insert(1L, boundary.minusMillis(1), duration = 20L, pages = 2)

		val month = repository().observeReport(StatsWindow.MONTH).first()
		assertEquals(10L, month.totalDuration)
		assertEquals(1, month.sessions)

		val all = repository().observeReport(StatsWindow.ALL).first()
		assertEquals(30L, all.totalDuration)
		assertEquals(2, all.sessions)
	}

	@Test
	fun `the window is a calendar window in the clock's own zone`() = runBlocking {
		// 22:00 UTC on the 8th is 01:00 on the 9th in Kampala (UTC+3). It is therefore
		// inside the last seven calendar days there and outside them in UTC. A window
		// computed from a plain millisecond subtraction would get both wrong.
		val session = Instant.parse("2026-03-08T22:00:00Z")
		insert(1L, session, duration = 900L, pages = 9)

		val kampala = Clock.fixed(nowUtc, ZoneId.of("Africa/Kampala"))
		assertEquals(0, repository(utcClock).observeReport(StatsWindow.WEEK).first().sessions)
		assertEquals(1, repository(kampala).observeReport(StatsWindow.WEEK).first().sessions)
	}

	@Test
	fun `the chart has one bucket per day, oldest first, ending today`() = runBlocking {
		insert(1L, Instant.parse("2026-03-13T08:00:00Z"), duration = 300L, pages = 3)
		insert(1L, Instant.parse("2026-03-13T20:00:00Z"), duration = 200L, pages = 2)
		val report = repository().observeReport(StatsWindow.WEEK).first()
		assertEquals(7, report.days.size)
		assertEquals(LocalDate.of(2026, 3, 9), report.days.first().date)
		assertEquals(LocalDate.of(2026, 3, 15), report.days.last().date)
		// Two sessions on one day land in one bucket.
		val thirteenth = report.days.single { it.date == LocalDate.of(2026, 3, 13) }
		assertEquals(500L, thirteenth.duration)
		assertEquals(5, thirteenth.pages)
		assertEquals(500L, report.busiestDay)
		assertEquals(0L, report.days.single { it.date == LocalDate.of(2026, 3, 14) }.duration)
	}

	@Test
	fun `most read titles are ranked by time and carry their names`() = runBlocking {
		insertManga(1L, "Alpha")
		insertManga(2L, "Beta")
		insert(1L, nowUtc, duration = 1_000L, pages = 10)
		insert(2L, nowUtc, duration = 5_000L, pages = 3)
		insert(2L, nowUtc, duration = 1_000L, pages = 2)
		// Never stored in `manga`, so its name cannot be resolved.
		insert(3L, nowUtc, duration = 100L, pages = 1)

		val report = repository().observeReport(StatsWindow.WEEK).first()
		assertEquals(listOf("Beta", "Alpha", "Removed title"), report.topTitles.map { it.title })
		assertEquals(6_000L, report.topTitles.first().duration)
		assertEquals(5, report.topTitles.first().pages)
	}

	@Test
	fun `consecutive days count as a streak`() {
		val rows = listOf(
			entityOn(LocalDate.of(2026, 3, 15)),
			entityOn(LocalDate.of(2026, 3, 14)),
			entityOn(LocalDate.of(2026, 3, 13)),
		)
		assertEquals(3, StatsMath.streak(rows, utcClock))
	}

	@Test
	fun `a missed day resets the streak`() {
		val rows = listOf(
			entityOn(LocalDate.of(2026, 3, 15)),
			entityOn(LocalDate.of(2026, 3, 13)),
			entityOn(LocalDate.of(2026, 3, 12)),
		)
		assertEquals(1, StatsMath.streak(rows, utcClock))
	}

	@Test
	fun `reading twice in one day is still one day of streak`() {
		val rows = listOf(
			entityOn(LocalDate.of(2026, 3, 15), hour = 1),
			entityOn(LocalDate.of(2026, 3, 15), hour = 23),
			entityOn(LocalDate.of(2026, 3, 14), hour = 9),
		)
		assertEquals(2, StatsMath.streak(rows, utcClock))
	}

	@Test
	fun `yesterday still anchors a streak but the day before does not`() {
		val yesterdayOnly = listOf(
			entityOn(LocalDate.of(2026, 3, 14)),
			entityOn(LocalDate.of(2026, 3, 13)),
		)
		// Today is not over, so a streak that reached yesterday is still alive.
		assertEquals(2, StatsMath.streak(yesterdayOnly, utcClock))

		val staleOnly = listOf(entityOn(LocalDate.of(2026, 3, 13)))
		assertEquals(0, StatsMath.streak(staleOnly, utcClock))
		assertEquals(0, StatsMath.streak(emptyList(), utcClock))
	}

	@Test
	fun `the streak ignores the selected window`() = runBlocking {
		// Forty consecutive days, ending today. Looking at the last seven must not
		// shorten the streak to seven.
		for (back in 0L until 40L) {
			insert(
				mangaId = 1L,
				at = LocalDate.of(2026, 3, 15).minusDays(back)
					.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant(),
				duration = 60_000L,
				pages = 5,
			)
		}
		assertEquals(40, repository().observeReport(StatsWindow.WEEK).first().streakDays)
		assertEquals(7, repository().observeReport(StatsWindow.WEEK).first().sessions)
	}

	@Test
	fun `a recorded session is stored with its start time and page count`() = runBlocking {
		val repository = repository()
		repository.record(mangaId = 4L, startedAt = 5_000L, duration = 120_000L, pages = 14)
		val rows = db.statsDao().observeSince(0L).first()
		assertEquals(1, rows.size)
		assertEquals(4L, rows.single().mangaId)
		assertEquals(5_000L, rows.single().startedAt)
		assertEquals(120_000L, rows.single().duration)
		assertEquals(14, rows.single().pages)
	}

	@Test
	fun `a session that read nothing in no time is not stored`() = runBlocking {
		repository().record(mangaId = 4L, startedAt = 5_000L, duration = 0L, pages = 0)
		assertTrue(db.statsDao().observeSince(0L).first().isEmpty())
	}

	@Test
	fun `a reading session measures itself against the clock`() = runBlocking {
		// A clock that answers a fixed instant makes the duration zero, so this uses a
		// mutable one: the session must read the clock twice, not remember one value.
		var current = Instant.parse("2026-03-15T10:00:00Z")
		val moving = object : Clock() {
			override fun getZone(): ZoneId = ZoneOffset.UTC
			override fun withZone(zone: ZoneId): Clock = this
			override fun instant(): Instant = current
		}
		val repository = StatsRepository(db, moving)
		val session = repository.beginSession(mangaId = 7L)
		session.pageRead()
		session.pageRead()
		current = current.plusSeconds(90)
		session.commit()
		// Committing again must not double the row; the reader can exit twice.
		session.commit()

		val rows = db.statsDao().observeSince(0L).first()
		assertEquals(1, rows.size)
		assertEquals(7L, rows.single().mangaId)
		assertEquals(90_000L, rows.single().duration)
		assertEquals(2, rows.single().pages)
	}

	@Test
	fun `durations are formatted for reading, not for precision`() {
		assertEquals("–", formatDuration(0L))
		assertEquals("38s", formatDuration(38_000L))
		assertEquals("14m", formatDuration(14 * 60_000L))
		assertEquals("2h 14m", formatDuration(2 * 3_600_000L + 14 * 60_000L))
	}

	@Test
	fun `clear empties the table`() = runBlocking {
		insert(1L, nowUtc, duration = 100L, pages = 1)
		repository().clear()
		assertTrue(repository().observeReport(StatsWindow.ALL).first().isEmpty)
	}
}
