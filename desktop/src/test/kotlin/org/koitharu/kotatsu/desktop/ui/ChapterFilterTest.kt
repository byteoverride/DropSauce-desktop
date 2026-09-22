package org.koitharu.kotatsu.desktop.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.koitharu.kotatsu.desktop.feature.curate.CurateRepository
import org.koitharu.kotatsu.desktop.library.ChapterCountFetcher
import org.koitharu.kotatsu.desktop.library.ChapterCountRefresher
import org.koitharu.kotatsu.desktop.library.LibraryItem
import org.koitharu.kotatsu.desktop.library.LibraryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.LibraryDatabase
import org.koitharu.kotatsu.shared.db.openLibraryDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterFilterTest {

	private val file = Files.createTempFile("chapter-filter", ".db").toFile().also { it.delete() }
	private val db: LibraryDatabase = openLibraryDatabase(file.toOkioPath())
	private val repo = LibraryRepository(db)

	@AfterTest
	fun tearDown() {
		db.close()
		file.delete()
	}

	// Positive control: the predicate itself, fed a count directly.
	@Test
	fun `predicate buckets a known count`() {
		val item = LibraryItem(manga(1L, "X", chapters = 0), "MANGADEX", 30)
		assertEquals(true, ChapterFilter.Any.matches(item))
		assertEquals(true, ChapterFilter.Medium.matches(item))
		assertEquals(false, ChapterFilter.Short.matches(item))
		assertEquals(false, ChapterFilter.Unknown.matches(item))
	}

	@Test
	fun `saving a loaded title stores its chapter count`() = runBlocking {
		val category = repo.createCategory("Test")
		repo.setFavourite(manga(1L, "Loaded", chapters = 30), category, true)

		val items = repo.observeFavourites(null).first()
		assertEquals(1, items.size)
		assertEquals(30, items.single().chaptersCount)
	}

	@Test
	fun `opening details backfills the count for a title saved from a list`() = runBlocking {
		val category = repo.createCategory("Test")
		// Saved from a list screen, where the parser gives no chapters.
		repo.setFavourite(manga(1L, "From list", chapters = 0), category, true)
		assertEquals(0, repo.observeFavourites(null).first().single().chaptersCount)

		repo.refreshStored(manga(1L, "From list", chapters = 30))

		assertEquals(30, repo.observeFavourites(null).first().single().chaptersCount)
	}

	@Test
	fun `filtering a mixed library returns each bucket`() = runBlocking {
		val category = repo.createCategory("Test")
		repo.setFavourite(manga(1L, "Short", chapters = 10), category, true)
		repo.setFavourite(manga(2L, "Medium", chapters = 60), category, true)
		repo.setFavourite(manga(3L, "Long", chapters = 200), category, true)
		repo.setFavourite(manga(4L, "Unknown", chapters = 0), category, true)

		val all = repo.observeFavourites(null).first()
		assertEquals(4, all.size)
		assertEquals(listOf("Short"), all.filter(ChapterFilter.Short::matches).map { it.manga.title })
		assertEquals(listOf("Medium"), all.filter(ChapterFilter.Medium::matches).map { it.manga.title })
		assertEquals(listOf("Long"), all.filter(ChapterFilter.Long::matches).map { it.manga.title })
		assertEquals(listOf("Unknown"), all.filter(ChapterFilter.Unknown::matches).map { it.manga.title })
	}

	// The reported bug: a library restored from an Android backup carries no counts,
	// so every bucket but "Not loaded" is empty however much of it has been read.
	@Test
	fun `a restored library filters nothing until its counts are filled`() = runBlocking {
		val category = repo.createCategory("Marinate")
		repo.setFavourite(manga(1L, "Read, count lost", chapters = 0), category, true)
		repo.setFavourite(manga(2L, "Never opened", chapters = 0), category, true)
		// What a restore leaves behind: history knows the length, the title does not.
		putHistory(1L, chapters = 140)

		val before = repo.observeFavourites(null).first()
		assertEquals(emptyList(), before.filter(ChapterFilter.Long::matches).map { it.manga.title })

		assertEquals(1, repo.backfillChapterCounts())

		val after = repo.observeFavourites(null).first()
		assertEquals(listOf("Read, count lost"), after.filter(ChapterFilter.Long::matches).map { it.manga.title })
	}

	@Test
	fun `the refresher fetches only the counts nothing local knows`() = runBlocking {
		val category = repo.createCategory("Marinate")
		repo.setFavourite(manga(1L, "Read", chapters = 0), category, true)
		repo.setFavourite(manga(2L, "Never opened", chapters = 0), category, true)
		repo.setFavourite(manga(3L, "Already counted", chapters = 40), category, true)
		putHistory(1L, chapters = 140)

		val asked = mutableListOf<Long>()
		val refresher = refresher { manga ->
			asked += manga.id
			260
		}
		val result = refresher.run(categoryId = null)

		// Only the one title nothing local could answer for.
		assertEquals(listOf(2L), asked)
		assertEquals(ChapterCountProgressShape(done = 1, total = 1, failed = 0), result.shape())

		val items = repo.observeFavourites(null).first().associate { it.manga.title to it.chaptersCount }
		assertEquals(mapOf("Read" to 140, "Never opened" to 260, "Already counted" to 40), items)
	}

	@Test
	fun `a source that fails is counted, not fatal`() = runBlocking {
		val category = repo.createCategory("Marinate")
		repo.setFavourite(manga(1L, "Dead source", chapters = 0), category, true)
		repo.setFavourite(manga(2L, "Live source", chapters = 0), category, true)

		val refresher = refresher { manga ->
			if (manga.id == 1L) throw java.io.IOException("503") else 120
		}
		val result = refresher.run(categoryId = null)

		assertEquals(ChapterCountProgressShape(done = 2, total = 2, failed = 1), result.shape())
		val items = repo.observeFavourites(null).first().associate { it.manga.title to it.chaptersCount }
		assertEquals(mapOf("Dead source" to 0, "Live source" to 120), items)
	}

	@Test
	fun `a refresh is scoped to one category`() = runBlocking {
		val marinate = repo.createCategory("Marinate")
		val other = repo.createCategory("Long manwhas")
		repo.setFavourite(manga(1L, "In marinate", chapters = 0), marinate, true)
		repo.setFavourite(manga(2L, "Elsewhere", chapters = 0), other, true)

		val asked = mutableListOf<Long>()
		refresher { manga -> asked += manga.id; 300 }.run(categoryId = marinate)

		assertEquals(listOf(1L), asked)
	}

	@Test
	fun `a source reporting nothing leaves the count unknown rather than storing zero`() = runBlocking {
		val category = repo.createCategory("Marinate")
		repo.setFavourite(manga(1L, "Empty listing", chapters = 0), category, true)

		val result = refresher { 0 }.run(categoryId = null)

		assertEquals(ChapterCountProgressShape(done = 1, total = 1, failed = 1), result.shape())
		// Still offered next time, instead of being filed as a zero-chapter title.
		assertEquals(1, refresher { 0 }.observeMissing(null).first())
	}

	@Test
	fun `filtering then refiling moves exactly what the filter showed`() = runBlocking {
		val marinate = repo.createCategory("Marinate")
		val readLater = repo.createCategory("Read later")
		repo.setFavourite(manga(1L, "Ready", chapters = 140), marinate, true)
		repo.setFavourite(manga(2L, "Still short", chapters = 40), marinate, true)

		val shown = repo.observeFavourites(marinate).first().filter(ChapterFilter.Long::matches)
		assertEquals(listOf("Ready"), shown.map { it.manga.title })

		val moved = CurateRepository(db).moveToCategory(shown.map { it.manga.id }, from = marinate, to = readLater)

		assertEquals(1, moved.affected)
		assertEquals(listOf("Still short"), repo.observeFavourites(marinate).first().map { it.manga.title })
		assertEquals(listOf("Ready"), repo.observeFavourites(readLater).first().map { it.manga.title })
	}

	private data class ChapterCountProgressShape(val done: Int, val total: Int, val failed: Int)

	private fun org.koitharu.kotatsu.desktop.library.ChapterCountProgress.shape() =
		ChapterCountProgressShape(done, total, failed).also { assertTrue(!running) }

	private fun refresher(count: (Manga) -> Int) = ChapterCountRefresher(
		db = db,
		fetcher = ChapterCountFetcher { _, manga -> count(manga) },
		scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
	)

	private suspend fun putHistory(mangaId: Long, chapters: Int) {
		db.historyDao().upsert(
			HistoryEntity(
				mangaId = mangaId,
				createdAt = 1L,
				updatedAt = 1L,
				chapterId = 1L,
				page = 0,
				scroll = 0f,
				percent = 0.5f,
				chaptersCount = chapters,
				deletedAt = 0L,
			),
		)
	}

	private fun manga(id: Long, title: String, chapters: Int): Manga = Manga(
		id = id,
		title = title,
		altTitles = emptySet(),
		url = "/manga/$id",
		publicUrl = "https://example.test/manga/$id",
		rating = 0.8f,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = if (chapters == 0) null else List(chapters) { index ->
			MangaChapter(
				id = id * 1000 + index,
				title = "Chapter ${index + 1}",
				number = (index + 1).toFloat(),
				volume = 0,
				url = "/chapter/$id/$index",
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = MangaParserSource.MANGADEX,
			)
		},
		source = MangaParserSource.MANGADEX,
	)
}
