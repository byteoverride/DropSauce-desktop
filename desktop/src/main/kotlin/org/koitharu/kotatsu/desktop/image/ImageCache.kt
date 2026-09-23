package org.koitharu.kotatsu.desktop.image

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
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
	 * How much decoded image data to keep, in bytes.
	 *
	 * The real bound. Counting entries is meaningless here because the entries are not
	 * comparable: a cover decodes to about 4 MB and a webtoon page to anywhere up to 40,
	 * so three hundred of one is a gigabyte and three hundred of the other is thirteen.
	 * A machine with room for the first will die on the second, silently, because a
	 * failed native allocation produces no Java stack trace and a packaged Windows app
	 * has no console to print one to.
	 */
	maxBytes: Long = defaultBudget(),
	/**
	 * Injected so the failure window is testable without sleeping through it. The same
	 * shape `TrackerRepository` and `openLibraryDatabase` already use.
	 */
	private val now: () -> Long = System::currentTimeMillis,
	/**
	 * Asked before every decode, so that running out of memory is a message rather than
	 * a vanished process. Injected because a test cannot arrange for a machine to be
	 * nearly full.
	 */
	private val memory: MemoryGuard = MemoryGuard(),
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

	@Volatile
	var maxBytes: Long = maxBytes
		set(value) {
			field = value.coerceAtLeast(MINIMUM_BUDGET)
			evict()
		}

	/**
	 * Access ordered, so eviction drops what has been looked at least recently.
	 *
	 * Eviction is [evict] rather than `removeEldestEntry`, which can only ever remove one
	 * entry per insertion. One page can be larger than several it displaces, so the
	 * overshoot has to be walked off in a loop or the budget is a suggestion.
	 */
	private val entries: LinkedHashMap<String, ImageBitmap> =
		LinkedHashMap(64, 0.75f, true)

	/** Guards [entries] and [bytesHeld] together: the two must not disagree. */
	private val lock = Any()

	private var bytesHeld: Long = 0L

	/** Decoded size, which is what occupies memory. The encoded bytes are long gone. */
	private fun sizeOf(bitmap: ImageBitmap): Long =
		bitmap.width.toLong() * bitmap.height.toLong() * BYTES_PER_PIXEL

	private fun put(url: String, bitmap: ImageBitmap) {
		synchronized(lock) {
			entries.put(url, bitmap)?.let { bytesHeld -= sizeOf(it) }
			bytesHeld += sizeOf(bitmap)
			evictLocked()
		}
	}

	private fun get(url: String): ImageBitmap? = synchronized(lock) { entries[url] }

	private fun evict() = synchronized(lock) { evictLocked() }

	private fun evictLocked() {
		val iterator = entries.entries.iterator()
		// Never evicts the entry just added, however large it is: the caller is about to
		// draw it, and returning a bitmap that is not in the cache is better than
		// returning one that has been thrown away.
		while (iterator.hasNext() && (bytesHeld > maxBytes || entries.size > maxEntries) && entries.size > 1) {
			val eldest = iterator.next()
			bytesHeld -= sizeOf(eldest.value)
			iterator.remove()
		}
	}

	/**
	 * One url drawn at two sizes is two entries, so the grid's thumbnail is not served
	 * where the details screen's full-width cover was asked for.
	 */
	private fun keyFor(url: String, targetWidth: Int): String =
		if (targetWidth > 0) "$url@$targetWidth" else url

	/** Releases every held bitmap, for the moment the machine has no room left. */
	private fun dropAll() {
		synchronized(lock) {
			entries.clear()
			bytesHeld = 0L
		}
	}

	/** What the cache is holding, for a diagnostic that would otherwise be guesswork. */
	fun heldBytes(): Long = synchronized(lock) { bytesHeld }

	fun heldCount(): Int = synchronized(lock) { entries.size }

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

	/**
	 * Forgets a url's *transient* failures, so a freshly re-resolved address starts clean.
	 *
	 * A permanent failure survives this on purpose. The reader calls it before every load
	 * so that the deliberate D20 retry is not refused by the attempt counter, and for two
	 * releases it also wiped the record for formats Skia cannot decode. The effect was
	 * that an AVIF page was refetched and re-decoded on every composition, for the life of
	 * the session, on exactly the machines least able to afford it. Covers never hit this
	 * because `RemoteImage` calls [load] directly, so the two paths quietly disagreed
	 * about whether "unsupported format" meant anything.
	 */
	fun forget(url: String) {
		failures.computeIfPresent(url) { _, failure -> if (failure.permanent) failure else null }
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

	/**
	 * Fetches and decodes [url], at no more than [targetWidth] pixels across.
	 *
	 * Zero, the default, decodes whole. A caller that knows how large it will draw the
	 * image should say so: a cover arrives at 1500x2000 and is drawn around 360px across,
	 * which is 12 MB held to show 0.7 MB's worth (D37).
	 *
	 * The decoded cache is keyed by url *and* width, because the same cover is drawn small
	 * in a grid and large on a details screen and the two are different images. Failures
	 * are keyed by url alone: a 403 is a 403 at any size.
	 */
	suspend fun load(url: String, client: OkHttpClient, targetWidth: Int = 0): ImageBitmap? {
		if (url.isEmpty() || isExhausted(url)) return null
		val key = keyFor(url, targetWidth)
		get(key)?.let { return it }
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
			// The whole size even when a smaller one was asked for. Only JPEG genuinely
			// decodes at the reduced size; WebP produces the full image internally and
			// PNG has to be decoded whole and then shrunk, so the worst case is the
			// honest one to check against.
			val needed = MemoryGuard.decodedBytesOrNull(bytes)
			if (needed != null && !memory.canDecode(needed)) {
				// Our own cached pixels are the largest thing we can give back, and the
				// image being asked for matters more than the ones behind it. Skia frees
				// them natively only once the Java peers are collected, so the room does
				// not appear in time for this attempt; recording the failure as transient
				// hands recovery to the retry ladder, which backs off far enough for the
				// collector to have run by the next try.
				dropAll()
				record(url, "not enough free memory to decode this image", permanent = false)
				return@withContext null
			}
			val bitmap = ScaledDecode.decode(bytes, targetWidth)
			if (bitmap == null) {
				// A decode failure will not fix itself on retry, unlike a transport miss.
				record(url, "this image format is not supported", permanent = true)
				return@withContext null
			}
			put(key, bitmap)
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

	/** Internal rather than private so the local-comics caches can size against it. */
	internal companion object {

		const val BYTES_PER_PIXEL = 4L

		/** Below this the reader would be decoding the same page over and over. */
		const val MINIMUM_BUDGET = 48L * 1024 * 1024

		/**
		 * Never more than this, however large the machine.
		 *
		 * A deliberate ceiling rather than a consequence of the arithmetic. 256 MB is
		 * about six full-size webtoon pages, and the reader shows one at a time with a
		 * couple of neighbours; holding a gigabyte of decoded pages buys nothing and
		 * risks the whole process. The previous ceiling was 1 GB and a large desktop
		 * really did take it.
		 */
		const val MAXIMUM_BUDGET = 256L * 1024 * 1024

		/**
		 * An eighth of whatever the machine has left once the JVM has taken its share.
		 *
		 * This used to be a quarter of the *heap*, which had the relationship backwards.
		 * Skia keeps these pixels outside the heap, so a bigger `-Xmx` gave a bigger
		 * image budget as well and the two added rather than traded: the Windows report
		 * that started this work ran a 1 GB heap and was therefore also allowed 256 MB of
		 * native pixels on top. Sizing off physical memory and subtracting the heap makes
		 * a larger heap shrink the image budget, which is the true relationship.
		 *
		 * An eighth rather than a quarter because the remainder is not ours to spend: the
		 * operating system and everything else the person is running live in it too. A
		 * quarter was tried first and handed a 1 GB machine 117 MB where the old formula
		 * gave it 64, which is the wrong direction for a change whose point is to stop
		 * that machine dying. At an eighth every constrained shape gets the same or less
		 * than before, and only the large ones lose much, which they can afford to.
		 *
		 * Arguments are injectable so the arithmetic can be tested on machines it was not
		 * run on. Falls back to the old heap proxy only when the platform will not report
		 * its own size, and even then respects the new ceiling.
		 */
		fun defaultBudget(
			physicalBytes: Long? = MemoryGuard.totalBytes(),
			heapBytes: Long = Runtime.getRuntime().maxMemory(),
		): Long {
			val spare = MemoryGuard.spareForImages(physicalBytes, heapBytes)
				?: return (heapBytes / 4).coerceIn(MINIMUM_BUDGET, MAXIMUM_BUDGET)
			return (spare / 8).coerceIn(MINIMUM_BUDGET, MAXIMUM_BUDGET)
		}

		const val MAX_ATTEMPTS = 3

		/**
		 * How long a run of transient failures is honoured before the url is worth
		 * another try. Long enough that a dead cover is not refetched on every scroll,
		 * short enough that a blip does not outlive the reader's patience.
		 */
		const val RETRY_AFTER_MS = 60_000L
	}
}
