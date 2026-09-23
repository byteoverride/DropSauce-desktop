package org.koitharu.kotatsu.desktop.feature.local

import androidx.compose.ui.graphics.ImageBitmap
import org.koitharu.kotatsu.desktop.image.BytesBoundedCache
import org.koitharu.kotatsu.desktop.image.MemoryGuard
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import javax.imageio.ImageIO

/**
 * Decoded pages and covers from local archives.
 *
 * Separate from `ImageCache` on purpose: that one is keyed by url and fetches over
 * OkHttp, and a local page has no url to fetch. What is shared is the decoder, Skia via
 * `Image.makeFromEncoded`, which is the only decoder in the desktop app that handles the
 * formats a scanner actually emits.
 *
 * Covers are downscaled before they are cached. A grid of fifty full-resolution manga
 * pages is roughly 600 MB of ARGB, and the grid only ever draws them a few hundred
 * pixels wide.
 */
class LocalImages(
	coverEntries: Int = 200,
	pageEntries: Int = 8,
	private val coverWidth: Int = 400,
	/**
	 * The same check `ImageCache` makes. A page out of a local archive is decoded by the
	 * same Skia call and is exactly as able to be 43 MB, so leaving it out here would
	 * have fixed half of a problem.
	 */
	private val memory: MemoryGuard = MemoryGuard(),
) {

	// Covers are downscaled to coverWidth before caching, so the entry count is already a
	// fair proxy for memory here; the byte bound is a backstop for an unusually tall one.
	private val covers = BytesBoundedCache(maxBytes = COVER_BUDGET, maxEntries = coverEntries)

	/**
	 * Full-size pages, bounded by bytes rather than by count.
	 *
	 * Eight entries was the whole limit, and eight local webtoon pages is 344 MB. That is
	 * the same defect `ImageCache` had: a count says nothing about memory when one entry
	 * can be ten times another. The reader only shows one page plus whatever it is
	 * scrolling past, so the count stays as a secondary cap.
	 */
	private val pages = BytesBoundedCache(maxBytes = PAGE_BUDGET, maxEntries = pageEntries)

	/** Containers whose cover could not be decoded, so the grid stops retrying them. */
	private val deadCovers = Collections.synchronizedSet(HashSet<String>())

	/** The thumbnail for an imported comic, or null when it cannot be decoded. */
	suspend fun cover(container: String, entry: String?): ImageBitmap? {
		if (entry == null || container in deadCovers) return null
		covers.get(container)?.let { return it }
		return withContext(Dispatchers.IO) {
			val bytes = readOrNull(LocalPageRef(container, entry))
			val bitmap = bytes?.let { decode(it, coverWidth) }
			if (bitmap == null) {
				// A missing or undecodable first entry will not fix itself between
				// recompositions, and reopening the archive each time would make
				// scrolling the grid reopen every broken file on every frame.
				deadCovers += container
			} else {
				covers.put(container, bitmap)
			}
			bitmap
		}
	}

	/** A full-size page, addressed by the url carried on a local `MangaPage`. */
	suspend fun page(url: String): ImageBitmap? {
		val ref = LocalPageRef.decode(url) ?: return null
		pages.get(url)?.let { return it }
		return withContext(Dispatchers.IO) {
			val bitmap = readOrNull(ref)?.let { decode(it, targetWidth = 0) }
			if (bitmap != null) {
				pages.put(url, bitmap)
			}
			bitmap
		}
	}

	/** Drops everything remembered about [container], after a re-import or a removal. */
	fun forget(container: String) {
		covers.remove(container)
		deadCovers.remove(container)
		pages.removeIf { LocalPageRef.decode(it)?.container == container }
	}

	private fun readOrNull(ref: LocalPageRef): ByteArray? = try {
		LocalArchives.readPage(ref).takeIf { it.isNotEmpty() }
	} catch (e: IOException) {
		e.printStackTraceDebug()
		null
	}

	/**
	 * Decodes [bytes], optionally shrinking to [targetWidth] first.
	 *
	 * ImageIO does the shrinking because Skia's decoder has no downscale entry point
	 * here, but ImageIO has no plugin for webp or avif, so a null from it is a reason to
	 * hand the original bytes to Skia rather than to give up.
	 */
	private fun decode(bytes: ByteArray, targetWidth: Int): ImageBitmap? {
		val encoded = if (targetWidth > 0) shrink(bytes, targetWidth) ?: bytes else bytes
		val needed = MemoryGuard.decodedBytesOrNull(encoded)
		if (needed != null && !memory.canDecode(needed)) {
			// Returning null shows the page as unavailable, which is recoverable. The
			// alternative is a native allocation failure, which is not.
			return null
		}
		return try {
			Image.makeFromEncoded(encoded).toComposeImageBitmap()
		} catch (e: Exception) {
			// Skia throws a plain IllegalArgumentException for a payload it cannot read,
			// which includes a text file renamed to .jpg inside an otherwise valid zip.
			e.printStackTraceDebug()
			null
		}
	}

	private fun shrink(bytes: ByteArray, targetWidth: Int): ByteArray? {
		val source = try {
			ImageIO.read(ByteArrayInputStream(bytes))
		} catch (e: IOException) {
			e.printStackTraceDebug()
			null
		} ?: return null
		if (source.width <= targetWidth) return null
		val height = (source.height.toLong() * targetWidth / source.width).toInt().coerceAtLeast(1)
		val scaled = BufferedImage(targetWidth, height, BufferedImage.TYPE_INT_RGB)
		val graphics = scaled.createGraphics()
		try {
			graphics.setRenderingHint(
				RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR,
			)
			graphics.drawImage(source, 0, 0, targetWidth, height, null)
		} finally {
			graphics.dispose()
		}
		val out = ByteArrayOutputStream()
		return if (ImageIO.write(scaled, "png", out)) out.toByteArray() else null
	}

	private companion object {

		/**
		 * Budgets for locally imported comics, fixed rather than scaled to the heap.
		 *
		 * A quarter of the heap is already spoken for by [ImageCache], which holds remote
		 * covers and reader pages. These are a second cache over the same memory, so they
		 * take a modest fixed share instead of another proportional bite.
		 */
		const val COVER_BUDGET = 64L * 1024 * 1024

		const val PAGE_BUDGET = 96L * 1024 * 1024
	}
}
