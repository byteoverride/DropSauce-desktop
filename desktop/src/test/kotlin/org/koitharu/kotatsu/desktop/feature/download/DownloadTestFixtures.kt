package org.koitharu.kotatsu.desktop.feature.download

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * A page source with no network behind it.
 *
 * This is why [PageSource] is an interface at all: there is no MockWebServer on this
 * classpath, so the only way to drive a whole download deterministically, including the
 * failure and cancellation paths, is to inject the fetch.
 */
internal class FakePageSource(
	private val chapters: List<MangaChapter>,
	private val pageCount: Int,
	private val onFetch: suspend (index: Int) -> PageBytes = { index -> bytesFor(index) },
) : PageSource {

	/** Every page index the downloader actually asked for, in order. */
	val fetched = mutableListOf<Int>()

	override suspend fun chapters(manga: Manga): List<MangaChapter> = chapters

	override suspend fun pages(manga: Manga, chapter: MangaChapter): List<MangaPage> =
		(0 until pageCount).map { index ->
			MangaPage(
				id = chapter.id * 1000 + index,
				// The index travels in the url because MangaPage has no position field
				// and the fake has to know which page it is being asked for.
				url = "https://example.test/${chapter.id}/page-$index",
				preview = null,
				source = TEST_SOURCE,
			)
		}

	override suspend fun fetch(manga: Manga, page: MangaPage): PageBytes {
		val index = page.url.substringAfterLast("page-").toInt()
		synchronized(fetched) { fetched.add(index) }
		return onFetch(index)
	}
}

/** A real catalogue source, because a stored row has to resolve back to one. */
internal val TEST_SOURCE = MangaParserSource.MANGADEX

internal fun bytesFor(index: Int) = PageBytes("page $index".encodeToByteArray(), "jpg")

internal fun testChapter(id: Long, number: Float = 1f) = MangaChapter(
	id = id,
	title = "Chapter $number",
	number = number,
	volume = 0,
	url = "/chapter/$id",
	scanlator = null,
	uploadDate = 0L,
	branch = null,
	source = TEST_SOURCE,
)

internal fun testManga(id: Long = 42L, chapters: List<MangaChapter>) = Manga(
	id = id,
	title = "A Title",
	altTitles = emptySet(),
	url = "/manga/$id",
	publicUrl = "https://example.test/manga/$id",
	rating = 0.5f,
	contentRating = null,
	coverUrl = null,
	tags = emptySet(),
	state = null,
	authors = emptySet(),
	largeCoverUrl = null,
	description = null,
	chapters = chapters,
	source = TEST_SOURCE,
)
