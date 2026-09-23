package org.koitharu.kotatsu.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.local.LocalFeature
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Where the reader gets its pages.
 *
 * Exists because a locally imported comic cannot travel the remote path at all: pages
 * live inside a zip rather than behind a url, and `Screen.Reader` was typed to
 * `MangaParserSource`, which is a closed enum that a local source can never be. The
 * reader therefore asks this instead of asking a parser directly.
 */
interface ReaderPageSource {

	suspend fun pages(chapter: MangaChapter): List<MangaPage>

	/**
	 * The decoded image for [page], or null if it could not be loaded.
	 *
	 * [attempt] starts at 1. Remote sources use it to back off; see DECISIONS.md D20,
	 * where a page 404s on first touch and succeeds once the node has it cached.
	 */
	suspend fun image(page: MangaPage, attempt: Int): ImageBitmap?

	/**
	 * Why the last [image] call for [page] came back null, if anything is known.
	 *
	 * Exists because "This page could not be loaded" is not a usable bug report. A 403
	 * from the source, a timeout and an image format Skia cannot read all produced that
	 * one sentence, and they have nothing to do with each other.
	 */
	fun failureReason(page: MangaPage): String? = null
}

/** Pages fetched from a remote source through its own parser and client. */
class RemotePageSource(
	private val state: AppState,
	private val source: MangaParserSource,
) : ReaderPageSource {

	override suspend fun pages(chapter: MangaChapter): List<MangaPage> =
		withContext(Dispatchers.IO) { state.sources.session(source).parser.getPages(chapter) }

	/** The url each page last resolved to, so a failure can be explained afterwards. */
	private val resolved = java.util.concurrent.ConcurrentHashMap<Long, String>()

	override suspend fun image(page: MangaPage, attempt: Int): ImageBitmap? {
		val url = runCatching {
			withContext(Dispatchers.IO) { state.sources.pageUrl(source, page) }
		}.getOrNull()
		if (url == null) {
			unresolvable += page.id
			return null
		}
		unresolvable -= page.id
		resolved[page.id] = url
		// Forget past *transient* failures for this url: a retry here is deliberate, and
		// the cache's own attempt counter would otherwise refuse the very retry that fixes
		// D20. A format Skia cannot decode survives the call, so an AVIF page is not
		// refetched and re-decoded on every composition.
		state.images.forget(url)
		return state.images.load(url, state.sources.session(source).client)
	}

	/** Pages whose address the source would not give up at all. */
	private val unresolvable = java.util.Collections.newSetFromMap(
		java.util.concurrent.ConcurrentHashMap<Long, Boolean>(),
	)

	override fun failureReason(page: MangaPage): String? = when {
		page.id in unresolvable -> "the source would not give an address for this page"
		else -> resolved[page.id]?.let { state.images.failureReason(it) }
	}
}

/** Pages read out of a local archive on disk. */
class LocalPageSource : ReaderPageSource {

	override suspend fun pages(chapter: MangaChapter): List<MangaPage> =
		LocalFeature.pages(chapter)

	override suspend fun image(page: MangaPage, attempt: Int): ImageBitmap? =
		LocalFeature.page(page)
}
