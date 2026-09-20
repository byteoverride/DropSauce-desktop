package org.koitharu.kotatsu.desktop.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads remote images through a source's own OkHttp client and decodes them with Skia.
 *
 * Coil was the obvious choice and is deliberately not used. Coil wants one shared
 * `ImageLoader` with one client, but covers and pages must go through the *originating
 * parser's* client so its `requestHeaders` (User-Agent, Referer) apply. That is the same
 * trap DECISIONS.md D1 describes for page requests, and it shows up on covers first:
 * several sources serve reading fine while 403ing their cover CDN for a client that did
 * not send the source's headers. Recorded as D19.
 */
class ImageCache(private val maxEntries: Int = 300) {

	private val entries: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
		object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
			override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>): Boolean =
				size > maxEntries
		},
	)

	/**
	 * How many times each url has failed.
	 *
	 * A previous version blacklisted a url on its first failure. That was wrong and it
	 * produced blank pages in the reader: MangaDex@Home nodes legitimately 404 individual
	 * pages, and the expected client behaviour is to ask for a different node and retry.
	 * Permanently poisoning the url made a recoverable miss look like a broken chapter.
	 * Counting instead still stops a genuinely dead cover from being refetched on every
	 * recomposition.
	 */
	private val failures = ConcurrentHashMap<String, Int>()

	fun isExhausted(url: String): Boolean = (failures[url] ?: 0) >= MAX_ATTEMPTS

	/** Forgets a url's failures, so a freshly re-resolved address starts clean. */
	fun forget(url: String) {
		failures.remove(url)
	}

	suspend fun load(url: String, client: OkHttpClient): ImageBitmap? {
		if (url.isEmpty() || isExhausted(url)) return null
		entries[url]?.let { return it }
		return withContext(Dispatchers.IO) {
			val bytes = try {
				client.newCall(Request.Builder().url(url).build()).execute().use { response ->
					if (response.isSuccessful) response.body?.bytes() else null
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
				null
			}
			if (bytes == null || bytes.isEmpty()) {
				failures.merge(url, 1, Int::plus)
				return@withContext null
			}
			val bitmap = try {
				Image.makeFromEncoded(bytes).toComposeImageBitmap()
			} catch (e: Exception) {
				// Skia has no decoder for this payload: AVIF, or an error page served
				// with an image content type.
				e.printStackTraceDebug()
				null
			}
			if (bitmap == null) {
				// A decode failure will not fix itself on retry, unlike a transport miss.
				failures[url] = MAX_ATTEMPTS
				return@withContext null
			}
			entries[url] = bitmap
			bitmap
		}
	}

	private companion object {

		const val MAX_ATTEMPTS = 3
	}
}
