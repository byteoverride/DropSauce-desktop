package org.koitharu.kotatsu.desktop.feature.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.settings.SettingsData
import org.koitharu.kotatsu.shared.settings.ThemeMode
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exercises backup and restore against a real SQLite file, not a mock.
 *
 * Two things every failure test asserts, and the reason they take a snapshot first: that
 * the call throws *and* that every row count is unchanged afterwards. Asserting only the
 * throw would pass for an implementation that wrote half the archive and then blew up,
 * which is the failure mode the two-phase design exists to prevent.
 */
class BackupRepositoryTest {

	private lateinit var dir: File
	private lateinit var source: LibraryDatabase
	private lateinit var target: LibraryDatabase
	private lateinit var sourceSettings: FakeSettingsStore
	private lateinit var targetSettings: FakeSettingsStore

	@Before
	fun setUp() {
		dir = Files.createTempDirectory("backup-test").toFile()
		source = openTestDatabase(dir, "source.db")
		target = openTestDatabase(dir, "target.db")
		sourceSettings = FakeSettingsStore()
		targetSettings = FakeSettingsStore()
	}

	@After
	fun tearDown() {
		source.close()
		target.close()
		dir.deleteRecursively()
	}

	private fun repositoryFor(db: LibraryDatabase, settings: FakeSettingsStore) = BackupRepository(
		db = db,
		settings = settings,
		backupDirectory = File(dir, "backups").toOkioPath(),
	)

	/** Two categories beyond the seeded "Read later", three favourites, two history rows. */
	private suspend fun seedSource() {
		source.putManga(1L, "First")
		source.putManga(2L, "Second")
		source.putManga(3L, "Third", chaptersCount = 42)
		val reading = source.putCategory("Reading", sortKey = 1)
		val done = source.putCategory("Finished", sortKey = 2)
		val readLater = source.favouriteCategoriesDao().getAll().first { it.title == "Read later" }
		source.putFavourite(1L, reading)
		source.putFavourite(2L, done)
		source.putFavourite(3L, readLater.categoryId)
		source.putHistory(1L, chapterId = 11L, page = 3, percent = 0.25f, updatedAt = 5_000L)
		source.putHistory(2L, chapterId = 22L, page = 7, percent = 0.75f, updatedAt = 6_000L)
		source.putBookmark(1L, pageId = 900L, page = 3)
	}

	private suspend fun backupSource(name: String = "backup.zip"): File {
		val file = File(dir, name)
		repositoryFor(source, sourceSettings).createBackup(file.toOkioPath())
		return file
	}

	@Test
	fun `backup then restore into an empty database reproduces categories favourites and history`() =
		runBlocking {
			seedSource()
			val file = backupSource()

			val summary = repositoryFor(target, targetSettings).restore(file.toOkioPath(), RestoreMode.Merge)

			val sourceCategories = source.favouriteCategoriesDao().getAll().map { it.title }.sorted()
			val targetCategories = target.favouriteCategoriesDao().getAll().map { it.title }.sorted()
			assertEquals(sourceCategories, targetCategories)
			assertEquals(listOf("Finished", "Read later", "Reading"), targetCategories)

			// Favourites compared as (title, category title) so the comparison survives the
			// category ids legitimately differing between the two databases.
			assertEquals(favouritePairs(source), favouritePairs(target))
			assertEquals(3, favouritePairs(target).size)

			assertEquals(historyRows(source), historyRows(target))
			assertEquals(2, historyRows(target).size)

			assertEquals(1, target.rowCount("bookmarks"))
			val bookmark = requireNotNull(target.bookmarksDao().find(1L, 900L)) { "the bookmark is missing" }
			assertEquals(3, bookmark.page)

			// The stored chapter count is desktop-only and must survive the trip.
			assertEquals(42, requireNotNull(target.mangaDao().find(3L)).chaptersCount)
			assertEquals(0, summary.favouritesSkipped)
		}

	@Test
	fun `restore does not create a second read later category`() = runBlocking {
		seedSource()
		val file = backupSource()

		// The target already has the seeded default, and the backup carries one too. Android
		// resolves this by deleting an empty category whose *localized* title matches; this
		// restore matches on title on the way in, so the duplicate is never created.
		assertEquals(1, target.favouriteCategoriesDao().getAll().count { it.title == "Read later" })
		repositoryFor(target, targetSettings).restore(file.toOkioPath(), RestoreMode.Merge)

		assertEquals(1, target.favouriteCategoriesDao().getAll().count { it.title == "Read later" })
		assertEquals(3, target.rowCount("favourite_categories"))
	}

	@Test
	fun `restoring the same file twice is idempotent`() = runBlocking {
		seedSource()
		val file = backupSource()
		val repository = repositoryFor(target, targetSettings)

		repository.restore(file.toOkioPath(), RestoreMode.Merge)
		val afterFirst = target.countSnapshot()
		repository.restore(file.toOkioPath(), RestoreMode.Merge)
		val afterSecond = target.countSnapshot()

		assertEquals(afterFirst, afterSecond)
		assertEquals(3, afterSecond["favourite_categories"])
		assertEquals(3, afterSecond["favourites"])
		assertEquals(2, afterSecond["history"])
		assertEquals(1, afterSecond["bookmarks"])
	}

	@Test
	fun `merge keeps rows that exist only locally`() = runBlocking {
		seedSource()
		val file = backupSource()

		val localOnly = target.putCategory("Local only", sortKey = 9)
		target.putManga(99L, "Local title")
		target.putFavourite(99L, localOnly)
		target.putHistory(99L, chapterId = 999L, page = 1, percent = 0.1f, updatedAt = 9_000L)

		repositoryFor(target, targetSettings).restore(file.toOkioPath(), RestoreMode.Merge)

		val categories = target.favouriteCategoriesDao().getAll().map { it.title }
		assertTrue("the local-only category survived merge", "Local only" in categories)
		assertEquals(4, categories.size)
		assertEquals(4, target.rowCount("favourites"))
		assertEquals(3, target.rowCount("history"))
		assertNotNull(target.historyDao().find(99L))
	}

	@Test
	fun `merge keeps a reading position that is newer here`() = runBlocking {
		seedSource()
		val file = backupSource()

		target.putManga(1L, "First")
		// The backup's row for manga 1 is at page 3, updatedAt 5000.
		target.putHistory(1L, chapterId = 11L, page = 40, percent = 0.9f, updatedAt = 50_000L)

		val summary = repositoryFor(target, targetSettings).restore(file.toOkioPath(), RestoreMode.Merge)

		assertEquals(40, requireNotNull(target.historyDao().find(1L)).page)
		assertEquals(1, summary.historySkipped)
	}

	@Test
	fun `replace mode drops rows the backup does not mention`() = runBlocking {
		seedSource()
		val file = backupSource()

		val localOnly = target.putCategory("Local only", sortKey = 9)
		target.putManga(99L, "Local title")
		target.putFavourite(99L, localOnly)

		repositoryFor(target, targetSettings).restore(file.toOkioPath(), RestoreMode.Replace)

		val categories = target.favouriteCategoriesDao().getAll().map { it.title }
		assertFalse("replace should have removed the local-only category", "Local only" in categories)
		assertEquals(3, categories.size)
		assertEquals(3, target.rowCount("favourites"))
		// The manga table is deliberately not emptied: tracks, downloads and stats point at it.
		assertNotNull(target.mangaDao().find(99L))
	}

	@Test
	fun `a corrupt zip fails with a clear message and leaves the database untouched`() = runBlocking {
		seedSource()
		target.putManga(5L, "Untouched")
		val before = target.countSnapshot()

		val garbage = File(dir, "garbage.zip")
		garbage.writeBytes(ByteArray(4096) { (it % 251).toByte() })

		val error = assertThrowsFormatError {
			repositoryFor(target, targetSettings).restore(garbage.toOkioPath(), RestoreMode.Replace)
		}
		assertTrue(
			"message should name the file and say it is not a readable zip: $error",
			error.contains("garbage.zip") && error.contains("zip"),
		)
		assertEquals(before, target.countSnapshot())
	}

	@Test
	fun `a zip with no index entry fails with a clear message and leaves the database untouched`() =
		runBlocking {
			seedSource()
			target.putManga(5L, "Untouched")
			val before = target.countSnapshot()

			// A real, readable zip that happens to carry a categories section. Without the
			// index check this would restore, which is exactly the "foreign file" case.
			val foreign = File(dir, "foreign.zip")
			ZipOutputStream(foreign.outputStream()).use { zip ->
				zip.putNextEntry(ZipEntry("categories"))
				zip.write("[]".toByteArray())
				zip.closeEntry()
				zip.putNextEntry(ZipEntry("something-else.txt"))
				zip.write("hello".toByteArray())
				zip.closeEntry()
			}

			val error = assertThrowsFormatError {
				repositoryFor(target, targetSettings).restore(foreign.toOkioPath(), RestoreMode.Replace)
			}
			assertTrue(
				"message should say the index entry is missing: $error",
				error.contains("index"),
			)
			assertEquals(before, target.countSnapshot())
		}

	@Test
	fun `a valid index with a damaged section fails and leaves the database untouched`() = runBlocking {
		seedSource()
		target.putManga(5L, "Untouched")
		val before = target.countSnapshot()

		// The sharp case: the file passes the identity check, so validation has to read every
		// section before writing anything, not merely look at the index and start restoring.
		val damaged = File(dir, "damaged.zip")
		ZipOutputStream(damaged.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("index"))
			zip.write("[${BackupIndex.current(1L).toJson().toJsonString()}]".toByteArray())
			zip.closeEntry()
			zip.putNextEntry(ZipEntry("categories"))
			zip.write("""[{"category_id":1,"title":"Fine"}]""".toByteArray())
			zip.closeEntry()
			zip.putNextEntry(ZipEntry("favourites"))
			zip.write("""[{"manga_id":1,"category_id":1,"manga":{ BROKEN""".toByteArray())
			zip.closeEntry()
		}

		val error = assertThrowsFormatError {
			repositoryFor(target, targetSettings).restore(damaged.toOkioPath(), RestoreMode.Merge)
		}
		assertTrue("message should name the damaged section: $error", error.contains("favourites"))
		assertEquals(before, target.countSnapshot())
	}

	@Test
	fun `a newer format version is refused`() = runBlocking {
		val future = File(dir, "future.zip")
		val index = BackupIndex(
			appId = BackupFormat.APP_ID,
			appVersion = 999,
			createdAt = 1L,
			formatVersion = BackupFormat.FORMAT_VERSION + 1,
		)
		ZipOutputStream(future.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("index"))
			zip.write("[${index.toJson().toJsonString()}]".toByteArray())
			zip.closeEntry()
		}
		val before = target.countSnapshot()

		val error = assertThrowsFormatError {
			repositoryFor(target, targetSettings).restore(future.toOkioPath(), RestoreMode.Merge)
		}
		assertTrue("message should mention the format version: $error", error.contains("format"))
		assertEquals(before, target.countSnapshot())
	}

	@Test
	fun `settings survive the round trip`() = runBlocking {
		sourceSettings.update {
			SettingsData(
				theme = ThemeMode.Light,
				webtoonWidthPercent = 35,
				hideAdultSources = false,
				hiddenSourceTerms = listOf("one", "two"),
				imageCacheEntries = 123,
				pageAttempts = 5,
				userAgent = "test-agent/1.0",
			)
		}
		val file = backupSource()

		repositoryFor(target, targetSettings).restore(file.toOkioPath(), RestoreMode.Merge)

		val restored = targetSettings.data.value
		assertEquals(ThemeMode.Light, restored.theme)
		assertEquals(35, restored.webtoonWidthPercent)
		assertFalse(restored.hideAdultSources)
		assertEquals(listOf("one", "two"), restored.hiddenSourceTerms)
		assertEquals(123, restored.imageCacheEntries)
		assertEquals(5, restored.pageAttempts)
		assertEquals("test-agent/1.0", restored.userAgent)
	}

	@Test
	fun `a manga id larger than a double can hold survives the round trip`() = runBlocking {
		// The reason the JSON codec keeps number text instead of parsing to Double. Manga ids
		// are 64-bit hashes; a trip through Double rounds anything past 2^53 and every foreign
		// key in the file is built on this value.
		val bigId = 9_007_199_254_740_993L // 2^53 + 1
		source.putManga(bigId, "Big id")
		val category = source.putCategory("Reading")
		source.putFavourite(bigId, category)
		val file = backupSource("big.zip")

		repositoryFor(target, targetSettings).restore(file.toOkioPath(), RestoreMode.Merge)

		assertNotNull("the id must come back bit for bit", target.mangaDao().find(bigId))
		assertEquals("Big id", target.mangaDao().find(bigId)?.title)
	}

	@Test
	fun `backing up twice records the newer time`() = runBlocking {
		seedSource()
		val repository = repositoryFor(source, sourceSettings)
		val first = repository.createBackup(File(dir, "a.zip").toOkioPath())
		assertEquals(first.createdAt, repository.lastBackupAt() ?: -1L)
		val second = repository.createBackup(File(dir, "b.zip").toOkioPath())
		assertEquals(second.createdAt, repository.lastBackupAt() ?: -1L)
		assertTrue(second.createdAt >= first.createdAt)
	}

	private suspend fun favouritePairs(db: LibraryDatabase): List<Pair<String, String>> {
		val categories = db.favouriteCategoriesDao().getAll().associateBy { it.categoryId }
		return categories.values.flatMap { category ->
			db.favouritesDao().observeByCategory(category.categoryId).first().map { row ->
				row.manga.title to category.title
			}
		}.sortedBy { "${it.first}|${it.second}" }
	}

	private suspend fun historyRows(db: LibraryDatabase): List<String> =
		db.historyDao().observeRecent(Int.MAX_VALUE).first().map { row ->
			"${row.manga.title}|${row.history.chapterId}|${row.history.page}|" +
				"${row.history.percent}|${row.history.updatedAt}"
		}.sorted()

	private inline fun assertThrowsFormatError(block: () -> Unit): String {
		try {
			block()
		} catch (e: BackupFormatException) {
			val message = e.message
			assertTrue("a BackupFormatException must carry a message", !message.isNullOrBlank())
			return message.orEmpty()
		}
		throw AssertionError("expected a BackupFormatException, nothing was thrown")
	}
}
