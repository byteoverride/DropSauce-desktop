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
class ImageCache(
	maxEntries: Int = 300,
	/**
	 * Injected so the failure window is testable without sleeping through it. The same
	 * shape `TrackerRepository` and `openLibraryDatabase` already use.
	 */
	private val now: () -> Long = System::currentTimeMillis,
) {

	/**
	 * Capacity, adjustable at runtime so the settings control takes effect immediately
	 * rather than at next launch, which the setting's description would otherwise have
	 * to admit to.
	 */
	@Volatile
	var maxEntries: Int = maxEntries
		set(value) {
			field = value.coerceAtLeast(1)
		}

	private val entries: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
		object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
			override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>): Boolean =
				size > maxEntries
		},
	)

	/**
	 * What has gone wrong for each url, and how often.
	 *
	 * A previous version blacklisted a url on its first failure. That was wrong and it
	 * produced blank pages in the reader: MangaDex@Home nodes legitimately 404 individual
	 * pages, and the expected client behaviour is to ask for a different node and retry.
	 * Permanently poisoning the url made a recoverable miss look like a broken chapter.
	 * Counting instead still stops a genuinely dead cover from being refetched on every
	 * recomposition.
	 *
	 * The reason is kept as well as the count. Every one of these failures was silent
	 * outside a debug build, so "the cover is sometimes missing" was unanswerable: a 403,
	 * a timeout and a format Skia cannot read are the same blank square, and they need
	 * three different fixes.
	 */
	private val failures = ConcurrentHashMap<String, Failure>()

	private data class Failure(
		val count: Int,
		val reason: String,
		/** A format Skia cannot read will not start working; a timeout might. */
		val permanent: Boolean,
		val atMillis: Long,
	)

	/**
	 * Whether this url has been given up on.
	 *
	 * A transient run of failures expires. Without that, a network blip while a grid of
	 * sixty covers loads at once blanks those covers for the rest of the session, with no
	 * retry on scrolling back and no way to ask again short of restarting, which is
	 * exactly what "sometimes the cover is missing" looks like from the outside.
	 */
	fun isExhausted(url: String): Boolean {
		val failure = failures[url] ?: return false
		if (failure.permanent) return true
		if (now() - failure.atMillis >= RETRY_AFTER_MS) {
			failures.remove(url)
			return false
		}
		return failure.count >= MAX_ATTEMPTS
	}

	/** Why [url] last failed, for a caller that would rather say so than show a blank. */
	fun failureReason(url: String): String? = failures[url]?.reason

	/** Forgets a url's failures, so a freshly re-resolved address starts clean. */
	fun forget(url: String) {
		failures.remove(url)
	}

	private fun record(url: String, reason: String, permanent: Boolean) {
		failures.compute(url) { _, previous ->
			Failure(
				count = (previous?.count ?: 0) + 1,
				reason = reason,
				permanent = permanent,
				atMillis = now(),
			)
		}
	}

	suspend fun load(url: String, client: OkHttpClient): ImageBitmap? {
		if (url.isEmpty() || isExhausted(url)) return null
		entries[url]?.let { return it }
		return withContext(Dispatchers.IO) {
			val bytes = try {
				client.newCall(Request.Builder().url(url).build()).execute().use { response ->
					if (response.isSuccessful) {
						response.body?.bytes()
					} else {
						record(url, "the source answered ${response.code}", permanent = false)
						return@withContext null
					}
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
				record(url, describe(e), permanent = false)
				return@withContext null
			}
			if (bytes == null || bytes.isEmpty()) {
				record(url, "the source sent an empty response", permanent = false)
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
				record(url, "this image format is not supported", permanent = true)
				return@withContext null
			}
			entries[url] = bitmap
			bitmap
		}
	}

	/**
	 * A failure the reader can act on, rather than a stack trace it will never see.
	 *
	 * Deliberately not the exception's own message, which for a socket timeout is a host
	 * and port and for an SSL problem is a paragraph about certificate paths.
	 */
	private fun describe(e: Exception): String = when (e) {
		is java.net.SocketTimeoutException -> "the source did not answer in time"
		is java.net.UnknownHostException -> "that source's address could not be resolved"
		is javax.net.ssl.SSLException -> "that source's certificate could not be verified"
		is java.io.IOException -> "the connection failed"
		else -> e::class.simpleName ?: "the request failed"
	}

	private companion object {

		const val MAX_ATTEMPTS = 3

		/**
		 * How long a run of transient failures is honoured before the url is worth
		 * another try. Long enough that a dead cover is not refetched on every scroll,
		 * short enough that a blip does not outlive the reader's patience.
		 */
		const val RETRY_AFTER_MS = 60_000L
	}
}
