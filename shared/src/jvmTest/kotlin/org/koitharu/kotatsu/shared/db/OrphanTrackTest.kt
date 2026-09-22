package org.koitharu.kotatsu.shared.db

import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A tracked title whose manga row has gone.
 *
 * `TrackWithManga.manga` is declared non-null, so Room throws
 * `IllegalStateException: Relationship item 'manga' was expected to be NON-NULL` the
 * moment the updates screen reads. It is thrown inside the flow, on the AWT event thread,
 * and takes the window with it: the tab cannot be opened again until the row is gone.
 *
 * The rows should not exist, because `tracks.manga_id` has a foreign key onto `manga`
 * with ON DELETE CASCADE. These tests establish whether that constraint is actually
 * enforced, because a foreign key SQLite is not told to check is only a comment.
 */
class OrphanTrackTest {

	private val file = Files.createTempFile("orphan-track", ".db").toFile().also { it.delete() }
	private val db: LibraryDatabase = openLibraryDatabase(file.toOkioPath())

	@AfterTest
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `foreign keys are enforced at all`() = runBlocking {
		val enforced = db.useWriterConnection { connection ->
			connection.usePrepared("PRAGMA foreign_keys") { statement ->
				if (statement.step()) statement.getLong(0) else 0L
			}
		}
		println("PRAGMA foreign_keys = $enforced")
		assertEquals(1L, enforced, "foreign keys are off, so ON DELETE CASCADE never runs")
	}

	@Test
	fun `deleting a manga takes its track row with it`() = runBlocking {
		db.mangaDao().upsert(manga())
		db.tracksDao().upsert(track())
		assertEquals(1, db.tracksDao().getAll().size)

		db.useWriterConnection { connection ->
			connection.usePrepared("DELETE FROM manga WHERE manga_id = ?") { statement ->
				statement.bindLong(1, MANGA_ID)
				statement.step()
			}
		}

		assertEquals(0, db.tracksDao().getAll().size, "the track row outlived its manga row")
	}

	@Test
	fun `the updates screen survives a track row with no manga`() = runBlocking {
		db.mangaDao().upsert(manga())
		db.tracksDao().upsert(track())
		// Force the state the crash log shows, whatever let it happen.
		db.useWriterConnection { connection ->
			connection.usePrepared("PRAGMA foreign_keys = OFF") { it.step() }
			connection.usePrepared("DELETE FROM manga WHERE manga_id = ?") { statement ->
				statement.bindLong(1, MANGA_ID)
				statement.step()
			}
			connection.usePrepared("PRAGMA foreign_keys = ON") { it.step() }
		}

		// Reading must not throw. An orphan is not worth showing and is certainly not
		// worth taking the window down for.
		assertEquals(emptyList(), db.tracksDao().observeAll().first())
		assertEquals(emptyList(), db.tracksDao().observeWithUpdates().first())
		// And the count the navigation dot reads must agree with the empty list, or the
		// tab would advertise updates that its own screen cannot show.
		assertEquals(0, db.tracksDao().observeUpdatedCount().first())
	}

	@Test
	fun `orphans can be swept up once they exist`() = runBlocking {
		db.mangaDao().upsert(manga())
		db.tracksDao().upsert(track())
		db.useWriterConnection { connection ->
			connection.usePrepared("PRAGMA foreign_keys = OFF") { it.step() }
			connection.usePrepared("DELETE FROM manga WHERE manga_id = ?") { statement ->
				statement.bindLong(1, MANGA_ID)
				statement.step()
			}
			connection.usePrepared("PRAGMA foreign_keys = ON") { it.step() }
		}
		assertEquals(1, db.tracksDao().getAll().size)

		db.tracksDao().deleteOrphans()

		assertEquals(0, db.tracksDao().getAll().size)
	}

	@Test
	fun `a tracked title with new chapters is counted and named`() = runBlocking {
		db.mangaDao().upsert(manga())
		db.tracksDao().upsert(track())

		assertEquals(1, db.tracksDao().observeUpdatedCount().first())
		assertEquals(listOf(MANGA_ID), db.tracksDao().observeUpdatedIds().first())

		db.tracksDao().clearNew(MANGA_ID)

		assertEquals(0, db.tracksDao().observeUpdatedCount().first())
		assertEquals(emptyList(), db.tracksDao().observeUpdatedIds().first())
	}

	private fun manga() = MangaEntity(
		mangaId = MANGA_ID,
		title = "Tracked",
		altTitle = null,
		url = "/manga/1",
		publicUrl = "https://example.test/manga/1",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		state = null,
		author = null,
		source = "MANGADEX",
		chaptersCount = 3,
	)

	private fun track() = TrackEntity(
		mangaId = MANGA_ID,
		lastChapterId = 0L,
		lastChapterDate = 0L,
		newChapters = 2,
		lastCheck = 0L,
		lastError = null,
	)

	private companion object {

		const val MANGA_ID = 1L
	}
}
