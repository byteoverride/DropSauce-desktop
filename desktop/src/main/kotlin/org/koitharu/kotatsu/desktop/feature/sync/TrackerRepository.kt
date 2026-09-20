package org.koitharu.kotatsu.desktop.feature.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.MangaEntity
import org.koitharu.kotatsu.shared.db.TrackEntity
import org.koitharu.kotatsu.shared.db.TrackWithManga
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads a title's chapter list.
 *
 * An interface rather than a direct call into [org.koitharu.kotatsu.desktop.source.SourceRegistry]
 * so the tracker can be tested without a network: every test in this area substitutes a
 * fetcher that returns a fixed chapter list or throws. The production implementation is
 * [SourceRegistryFetcher].
 */
fun interface MangaDetailsFetcher {

	/** Returns [manga] with its chapters filled in. May throw; the tracker records that. */
	suspend fun fetch(source: MangaParserSource, manga: Manga): Manga
}

/** The outcome of checking one title. */
data class TrackCheck(
	val mangaId: Long,
	val title: String,
	/** Chapters found since the remembered one. Zero when nothing changed or on error. */
	val newChapters: Int,
	val error: String?,
)

/** The outcome of a whole run. */
data class TrackRunSummary(
	val checked: Int,
	val titlesWithUpdates: Int,
	val newChapters: Int,
	val failed: Int,
	val results: List<TrackCheck>,
)

/**
 * Remembers the last chapter seen for a title and reports what has appeared since.
 *
 * There is no scheduler behind this. DECISIONS.md D11 rules out OS-level scheduling for
 * desktop v1, so a check happens because the user asked for one (or because the app just
 * started, if the shell decides to call [checkAll] then). The updates screen says so
 * rather than implying the list refreshes itself.
 */
class TrackerRepository(
	private val db: LibraryDatabase,
	private val fetcher: MangaDetailsFetcher,
	private val now: () -> Long = System::currentTimeMillis,
) {

	fun observeAll(): Flow<List<TrackWithManga>> = db.tracksDao().observeAll()

	fun observeWithUpdates(): Flow<List<TrackWithManga>> = db.tracksDao().observeWithUpdates()

	suspend fun isTracked(mangaId: Long): Boolean = db.tracksDao().find(mangaId) != null

	/**
	 * Starts watching [manga].
	 *
	 * If the object already carries chapters, the newest is adopted straight away so the
	 * first check reports what has appeared *since now*, not the entire back catalogue.
	 */
	suspend fun track(manga: Manga) {
		val chapters = manga.chapters.orEmpty()
		val existingManga = db.mangaDao().find(manga.id)
		val count = maxOf(chapters.size, existingManga?.chaptersCount ?: 0)
		db.mangaDao().upsert(manga.toEntity(count))
		val existing = db.tracksDao().find(manga.id)
		if (existing != null) {
			return
		}
		val newest = chapters.lastOrNull()
		db.tracksDao().upsert(
			TrackEntity(
				mangaId = manga.id,
				lastChapterId = newest?.id ?: 0L,
				lastChapterDate = newest?.uploadDate?.takeIf { it > 0L } ?: now(),
				newChapters = 0,
				lastCheck = 0L,
				lastError = null,
			),
		)
	}

	suspend fun untrack(mangaId: Long) = db.tracksDao().delete(mangaId)

	/**
	 * Starts watching every favourite that is not already watched, and returns how many
	 * were added.
	 *
	 * This exists because nothing else in this feature area can create a track. The natural
	 * place to start tracking one title is its details screen, which belongs to another
	 * area and reaches this repository only if that area chooses to. Without a way in from
	 * here, the updates screen would be permanently empty and untestable by the user.
	 *
	 * Each new track starts with no remembered chapter, so the first check adopts whatever
	 * the source lists and reports nothing new. Claiming a whole back catalogue as "new" the
	 * moment tracking is switched on would make the badge meaningless.
	 */
	suspend fun trackAllFavourites(): Int {
		var added = 0
		for (category in db.favouriteCategoriesDao().getAll()) {
			for (row in db.favouritesDao().observeByCategory(category.categoryId).first()) {
				if (db.tracksDao().find(row.manga.mangaId) != null) {
					continue
				}
				db.tracksDao().upsert(
					TrackEntity(
						mangaId = row.manga.mangaId,
						lastChapterId = 0L,
						lastChapterDate = 0L,
						newChapters = 0,
						lastCheck = 0L,
						lastError = null,
					),
				)
				added++
			}
		}
		return added
	}

	suspend fun clearNew(mangaId: Long) = db.tracksDao().clearNew(mangaId)

	suspend fun clearAllNew() {
		// TracksDao has no bulk clear and the table is one row per watched title, so the
		// loop is bounded by the size of the user's watch list.
		for (track in db.tracksDao().getAll()) {
			if (track.newChapters > 0) {
				db.tracksDao().clearNew(track.mangaId)
			}
		}
	}

	/** The most recent successful or failed check, or null if nothing has ever been checked. */
	suspend fun lastCheckedAt(): Long? =
		db.tracksDao().getAll().mapNotNull { it.lastCheck.takeIf { value -> value > 0L } }.maxOrNull()

	/**
	 * Checks every watched title, [concurrency] at a time.
	 *
	 * Bounded because each check is a live request to a third-party site and firing the
	 * whole watch list at once is both slow and rude. One title failing never stops the
	 * run: [check] turns a throw into a recorded error on that row.
	 */
	suspend fun checkAll(
		concurrency: Int = DEFAULT_CONCURRENCY,
		onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
	): TrackRunSummary {
		val tracks = db.tracksDao().getAll()
		if (tracks.isEmpty()) {
			onProgress(0, 0)
			return TrackRunSummary(0, 0, 0, 0, emptyList())
		}
		val permits = Semaphore(concurrency.coerceAtLeast(1))
		val done = AtomicInteger(0)
		val results = coroutineScope {
			tracks.map { track ->
				async(Dispatchers.IO) {
					permits.withPermit { check(track.mangaId) }.also {
						onProgress(done.incrementAndGet(), tracks.size)
					}
				}
			}.awaitAll()
		}
		return TrackRunSummary(
			checked = results.size,
			titlesWithUpdates = results.count { it.newChapters > 0 },
			newChapters = results.sumOf { it.newChapters },
			failed = results.count { it.error != null },
			results = results,
		)
	}

	/**
	 * Refetches one title and records what changed.
	 *
	 * Never throws for a source failure. That is the whole point: a run over a watch list
	 * must survive one dead site, so the failure is written to `tracks.last_error` and
	 * returned, and the remembered chapter is left exactly as it was so the next successful
	 * check still measures from the right place.
	 */
	suspend fun check(mangaId: Long): TrackCheck = withContext(Dispatchers.IO) {
		val track = db.tracksDao().find(mangaId)
			?: return@withContext TrackCheck(mangaId, "", 0, "This title is not being tracked.")
		val row = db.mangaDao().find(mangaId)
			?: return@withContext fail(track, "", "This title is no longer in the library.")
		val source = MangaParserSource.entries.firstOrNull { it.name == row.source }
			?: return@withContext fail(
				track,
				row.title,
				"\"${row.source}\" is not a source this build has. " +
					"DECISIONS.md D1: desktop browses the Kotatsu catalogue, Android browses Mihon extensions.",
			)

		val details = try {
			fetcher.fetch(source, row.toManga(source))
		} catch (e: CancellationException) {
			// Cancellation is the caller going away, not a source failure. Recording it as
			// one would leave a misleading error on the row.
			throw e
		} catch (e: Throwable) {
			return@withContext fail(track, row.title, e.readableMessage())
		}

		val chapters = details.chapters.orEmpty()
		if (chapters.isEmpty()) {
			// The fetch worked, the source simply lists nothing. Clearing the remembered
			// chapter here would make every chapter look new once the list came back.
			db.tracksDao().upsert(track.copy(lastCheck = now(), lastError = null))
			return@withContext TrackCheck(mangaId, row.title, 0, null)
		}

		val knownIndex = chapters.indexOfLast { it.id == track.lastChapterId }
		val found = when {
			// A track created without a chapter list adopts the current newest and reports
			// nothing, otherwise a freshly watched title would claim its whole archive is new.
			track.lastChapterId == 0L -> 0
			knownIndex >= 0 -> chapters.size - knownIndex - 1
			// The remembered chapter is gone from the list (renumbered, pulled, re-scanlated).
			// The stored count is the only other measure of "how much there was".
			else -> (chapters.size - row.chaptersCount).coerceAtLeast(0)
		}
		val newest = chapters.last()
		db.tracksDao().upsert(
			track.copy(
				lastChapterId = newest.id,
				lastChapterDate = newest.uploadDate.takeIf { it > 0L } ?: now(),
				// Accumulated, not replaced: two checks between two reading sessions must not
				// make the first batch of new chapters disappear from the badge.
				newChapters = track.newChapters + found,
				lastCheck = now(),
				lastError = null,
			),
		)
		db.mangaDao().upsert(row.copy(chaptersCount = chapters.size))
		TrackCheck(mangaId, row.title, found, null)
	}

	private suspend fun fail(track: TrackEntity, title: String, message: String): TrackCheck {
		db.tracksDao().upsert(track.copy(lastCheck = now(), lastError = message))
		return TrackCheck(track.mangaId, title, 0, message)
	}

	companion object {

		/** Four at a time: enough to make a watch list of twenty feel quick, gentle enough to pass for a reader. */
		const val DEFAULT_CONCURRENCY = 4
	}
}

/** Exceptions from parsers are often message-less; a class name beats an empty string. */
private fun Throwable.readableMessage(): String =
	message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"

/**
 * Rebuilds a [Manga] from its stored row.
 *
 * Duplicated from `LibraryRepository`, whose copy is private and in another feature's
 * file. The stored row is a projection (no tags, no description, no chapters), which is
 * fine here: a parser's `getDetails` only needs the url and the source to do its work.
 */
internal fun MangaEntity.toManga(source: MangaParserSource) = Manga(
	id = mangaId,
	title = title,
	altTitles = setOfNotNull(altTitle),
	url = url,
	publicUrl = publicUrl,
	rating = rating,
	contentRating = contentRating?.let { name -> ContentRating.entries.firstOrNull { it.name == name } },
	coverUrl = coverUrl,
	largeCoverUrl = largeCoverUrl,
	tags = emptySet(),
	state = state?.let { name -> MangaState.entries.firstOrNull { it.name == name } },
	authors = setOfNotNull(author),
	description = null,
	chapters = null,
	source = source,
)

internal fun Manga.toEntity(chaptersCount: Int) = MangaEntity(
	mangaId = id,
	title = title,
	altTitle = altTitles.firstOrNull(),
	url = url,
	publicUrl = publicUrl,
	rating = rating,
	contentRating = contentRating?.name,
	coverUrl = coverUrl,
	largeCoverUrl = largeCoverUrl,
	state = state?.name,
	author = authors.firstOrNull(),
	source = source.name,
	chaptersCount = chaptersCount,
)
