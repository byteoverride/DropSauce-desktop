package org.koitharu.kotatsu.desktop.feature.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.concurrent.ConcurrentHashMap

/** One page's bytes and the extension they should be stored under. */
class PageBytes(val bytes: ByteArray, val extension: String)

/**
 * Everything the downloader needs from the network, and the only part of it that talks
 * to one.
 *
 * Split out as an interface so a download can be driven end to end by a test without a
 * server: there is no MockWebServer on this classpath, and a downloader that can only
 * be exercised against a live source is a downloader that is never tested.
 *
 * Implementations must throw on failure. Returning an empty or partial result would let
 * the worker record a chapter as complete when it is not.
 */
interface PageSource {

	/** The chapter list of [manga], fresh enough to resolve a stored chapter id. */
	suspend fun chapters(manga: Manga): List<MangaChapter>

	suspend fun pages(manga: Manga, chapter: MangaChapter): List<MangaPage>

	suspend fun fetch(manga: Manga, page: MangaPage): PageBytes
}

/**
 * The real [PageSource]: the parser catalogue plus the per-source HTTP client.
 *
 * Images go through the source's own client, for the reason recorded in DECISIONS.md
 * D19: several sources 403 their image CDN for a client that does not carry the
 * parser's request headers.
 *
 * DECISIONS.md D20 is the opposite open failure, where a page 404s through that same
 * client while a bare client gets 200 on the identical url. It is not addressed here
 * and must not be assumed fixed. What this does is refuse to paper over it: a page that
 * cannot be fetched fails the chapter with the HTTP code in the message, so a D20 hit
 * shows up as "HTTP 404" on a FAILED row instead of a silently short chapter.
 */
class ParserPageSource(
	private val sources: SourceRegistry,
	private val clientFor: (MangaParserSource) -> OkHttpClient,
) : PageSource {

	/**
	 * Chapter lists already resolved in this process.
	 *
	 * A title download asks for the same list once per chapter, and each miss is a full
	 * details request to the source. Priming this from an enqueue that already holds the
	 * chapters (see [prime]) takes a whole-title download down to zero extra requests.
	 */
	private val chapterLists = ConcurrentHashMap<Long, List<MangaChapter>>()

	fun prime(manga: Manga) {
		manga.chapters?.takeIf { it.isNotEmpty() }?.let { chapterLists[manga.id] = it }
	}

	override suspend fun chapters(manga: Manga): List<MangaChapter> {
		chapterLists[manga.id]?.let { return it }
		manga.chapters?.takeIf { it.isNotEmpty() }?.let {
			chapterLists[manga.id] = it
			return it
		}
		val source = requireSource(manga)
		val loaded = withContext(Dispatchers.IO) {
			sources.session(source).parser.getDetails(manga).chapters
		}.orEmpty()
		if (loaded.isNotEmpty()) {
			chapterLists[manga.id] = loaded
		}
		return loaded
	}

	override suspend fun pages(manga: Manga, chapter: MangaChapter): List<MangaPage> {
		val source = requireSource(manga)
		return withContext(Dispatchers.IO) { sources.session(source).parser.getPages(chapter) }
	}

	override suspend fun fetch(manga: Manga, page: MangaPage): PageBytes {
		val source = requireSource(manga)
		// Two steps, not one: for many sources `page.url` is not an image address and
		// needs a second request to resolve, which is what getPageUrl performs.
		val url = withContext(Dispatchers.IO) { sources.pageUrl(source, page) }
		if (url.isEmpty()) throw IOException("The source returned no image address for this page")
		val client = clientFor(source)
		return withContext(Dispatchers.IO) {
			client.newCall(Request.Builder().url(url).build()).execute().use { response ->
				if (!response.isSuccessful) {
					throw IOException("HTTP ${response.code} for $url")
				}
				val body = response.body ?: throw IOException("Empty response for $url")
				val bytes = body.bytes()
				if (bytes.isEmpty()) throw IOException("Empty image body for $url")
				PageBytes(bytes, imageExtension(url, response.header("Content-Type")))
			}
		}
	}

	private fun requireSource(manga: Manga): MangaParserSource =
		parserSource(manga.source.name)
			?: throw IOException("Unknown source '${manga.source.name}' in this build")
}

/**
 * The extension a page's bytes should be stored under.
 *
 * Content type first, because a CDN url frequently carries no extension at all or a
 * misleading one (`.jpg` serving WebP). The url is the fallback, and jpg the last
 * resort: the extension only has to let a viewer pick a decoder, and every decoder used
 * here sniffs the bytes anyway.
 */
internal fun imageExtension(url: String, contentType: String?): String {
	val fromType = contentType?.substringBefore(';')?.trim()?.lowercase()?.let { type ->
		when (type) {
			"image/jpeg", "image/jpg" -> "jpg"
			"image/png" -> "png"
			"image/webp" -> "webp"
			"image/gif" -> "gif"
			"image/avif" -> "avif"
			"image/bmp" -> "bmp"
			else -> null
		}
	}
	if (fromType != null) return fromType
	val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
	val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
	return if (extension.length in 2..4 && extension.all { it.isLetterOrDigit() }) extension else "jpg"
}
