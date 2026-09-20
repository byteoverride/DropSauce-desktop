package org.koitharu.kotatsu.desktop.feature.reading

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.StatsEntity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** How far back the statistics screen is looking. */
enum class StatsWindow(val label: String, val days: Int?) {

	WEEK("7 days", 7),
	MONTH("30 days", 30),
	ALL("All time", null),
}

/** One calendar day's reading, in the user's own time zone. */
data class DayBucket(val date: LocalDate, val duration: Long, val pages: Int)

/** One title's share of the window. */
data class TitleTime(val mangaId: Long, val title: String, val duration: Long, val pages: Int)

/** Everything the statistics screen draws, from one snapshot of the table. */
data class StatsReport(
	val window: StatsWindow,
	val totalDuration: Long,
	val totalPages: Int,
	val sessions: Int,
	/** Oldest first, one entry per day in the charted span, including days with nothing. */
	val days: List<DayBucket>,
	val topTitles: List<TitleTime>,
	val streakDays: Int,
) {

	val isEmpty: Boolean get() = sessions == 0

	/** The tallest bar, used to scale the chart. Zero when nothing was read. */
	val busiestDay: Long get() = days.maxOfOrNull { it.duration } ?: 0L
}

/**
 * A reading session in progress.
 *
 * The reader opens one when a chapter is opened and commits it when reading stops. It is
 * a separate object rather than a pair of repository calls so the caller cannot lose the
 * start time, which is the part that cannot be reconstructed afterwards.
 */
class ReadingSession internal constructor(
	private val repository: StatsRepository,
	private val mangaId: Long,
	val startedAt: Long,
	private val clock: Clock,
) {

	// The reader records page turns from composition effects, which are not guaranteed to
	// be on one thread, so the counter is atomic.
	private val pageCount = AtomicInteger(0)

	private var committed = false

	val pages: Int get() = pageCount.get()

	fun pageRead() {
		pageCount.incrementAndGet()
	}

	/**
	 * Writes the session. Committing twice is a no-op rather than a second row, because
	 * the reader can be left both by navigating back and by the window closing.
	 */
	suspend fun commit() {
		if (committed) return
		committed = true
		repository.record(
			mangaId = mangaId,
			startedAt = startedAt,
			duration = (clock.millis() - startedAt).coerceAtLeast(0L),
			pages = pageCount.get(),
		)
	}
}

/**
 * Reading statistics.
 *
 * [clock] is injected rather than read from [System] so window arithmetic is testable.
 * Everything about "the last 7 days" depends on where the day boundary falls, which
 * depends on the zone, and [Clock] carries both the instant and the zone.
 */
class StatsRepository(
	private val db: LibraryDatabase,
	private val clock: Clock = Clock.systemDefaultZone(),
) {

	fun beginSession(mangaId: Long): ReadingSession =
		ReadingSession(this, mangaId, clock.millis(), clock)

	/**
	 * Stores one finished session.
	 *
	 * A session that read nothing and lasted no time is dropped: the reader commits on
	 * every exit, including one straight back out of a chapter that failed to load, and
	 * those rows would inflate the streak without representing any reading.
	 */
	suspend fun record(mangaId: Long, startedAt: Long, duration: Long, pages: Int) {
		if (duration <= 0L && pages <= 0) return
		db.statsDao().upsert(
			StatsEntity(
				id = 0,
				mangaId = mangaId,
				startedAt = startedAt,
				duration = duration.coerceAtLeast(0L),
				pages = pages.coerceAtLeast(0),
			),
		)
	}

	suspend fun clear() = db.statsDao().clear()

	/**
	 * The whole screen's data.
	 *
	 * Reads every row rather than the windowed query, because the streak is not bounded
	 * by the selected window: switching to "7 days" must not shorten a 40 day streak.
	 * One session per chapter read means this stays in the low thousands of rows for a
	 * heavy reader, which is cheaper than keeping four flows in step.
	 */
	fun observeReport(window: StatsWindow, topLimit: Int = TOP_LIMIT): Flow<StatsReport> =
		db.statsDao().observeSince(Long.MIN_VALUE).map { rows ->
			val report = StatsMath.report(rows, window, clock, topLimit)
			report.copy(topTitles = report.topTitles.map { it.copy(title = titleOf(it.mangaId)) })
		}

	private suspend fun titleOf(mangaId: Long): String =
		db.mangaDao().find(mangaId)?.title ?: UNKNOWN_TITLE

	private companion object {

		const val TOP_LIMIT = 5

		/** A title read then removed from the library still owns its reading time. */
		const val UNKNOWN_TITLE = "Removed title"
	}
}

/**
 * The window arithmetic, kept pure so it can be tested without a database.
 *
 * All bucketing is by local calendar day in the clock's zone, not by fixed 24 hour
 * blocks. "7 days" means the last seven calendar days including today, so a session at
 * 00:05 today and one at 23:55 six days ago are both inside it.
 */
internal object StatsMath {

	/** Chart span for [StatsWindow.ALL], which has no natural end. */
	const val ALL_TIME_CHART_DAYS = 30

	fun today(clock: Clock): LocalDate = LocalDate.now(clock)

	fun dateOf(epochMillis: Long, clock: Clock): LocalDate =
		Instant.ofEpochMilli(epochMillis).atZone(clock.zone).toLocalDate()

	/**
	 * First instant inside [window], inclusive.
	 *
	 * Midnight at the start of the window's first day, so a session recorded exactly on
	 * the boundary counts. [StatsWindow.ALL] has no lower bound.
	 */
	fun windowStart(window: StatsWindow, clock: Clock): Long {
		val days = window.days ?: return Long.MIN_VALUE
		return today(clock)
			.minusDays((days - 1).toLong())
			.atStartOfDay(clock.zone)
			.toInstant()
			.toEpochMilli()
	}

	fun report(
		rows: List<StatsEntity>,
		window: StatsWindow,
		clock: Clock,
		topLimit: Int,
	): StatsReport {
		val start = windowStart(window, clock)
		val inWindow = rows.filter { it.startedAt >= start }
		val today = today(clock)
		val span = window.days ?: ALL_TIME_CHART_DAYS
		val byDate = inWindow.groupBy { dateOf(it.startedAt, clock) }
		val days = (span - 1 downTo 0).map { back ->
			val date = today.minusDays(back.toLong())
			val forDay = byDate[date].orEmpty()
			DayBucket(
				date = date,
				duration = forDay.sumOf { it.duration },
				pages = forDay.sumOf { it.pages },
			)
		}
		val top = inWindow.groupBy { it.mangaId }
			.map { (id, sessions) ->
				TitleTime(
					mangaId = id,
					// Resolved by the repository; the pure layer has no database.
					title = "",
					duration = sessions.sumOf { it.duration },
					pages = sessions.sumOf { it.pages },
				)
			}
			.sortedWith(compareByDescending<TitleTime> { it.duration }.thenBy { it.mangaId })
			.take(topLimit)
		return StatsReport(
			window = window,
			totalDuration = inWindow.sumOf { it.duration },
			totalPages = inWindow.sumOf { it.pages },
			sessions = inWindow.size,
			days = days,
			topTitles = top,
			streakDays = streak(rows, clock),
		)
	}

	/**
	 * Consecutive days read, counting back from today.
	 *
	 * Yesterday is an acceptable anchor. Without that, someone who read at 23:00 and
	 * opens the app at 09:00 the next morning would be told their streak is zero while
	 * the day they could still save it is not over.
	 */
	fun streak(rows: List<StatsEntity>, clock: Clock): Int {
		if (rows.isEmpty()) return 0
		val dates = rows.mapTo(HashSet()) { dateOf(it.startedAt, clock) }
		val today = today(clock)
		var cursor = when {
			today in dates -> today
			today.minusDays(1) in dates -> today.minusDays(1)
			else -> return 0
		}
		var count = 0
		while (cursor in dates) {
			count++
			cursor = cursor.minusDays(1)
		}
		return count
	}
}

/** "2h 14m", "14m", "38s". Zero reads as a dash rather than "0m", which looks broken. */
internal fun formatDuration(millis: Long): String {
	if (millis <= 0L) return "–"
	val totalSeconds = millis / 1000
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60
	return when {
		hours > 0 -> "${hours}h ${minutes}m"
		minutes > 0 -> "${minutes}m"
		else -> "${seconds}s"
	}
}
