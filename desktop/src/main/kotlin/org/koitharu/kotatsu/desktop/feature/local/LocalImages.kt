package org.koitharu.kotatsu.desktop.feature.local

import androidx.compose.ui.graphics.ImageBitmap
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
) {

	private val covers = lru<ImageBitmap>(coverEntries)

	/**
	 * Full-size pages. Small on purpose: these are whole manga pages, and the reader only
	 * ever shows one plus whatever it is scrolling past.
	 */
	private val pages = lru<ImageBitmap>(pageEntries)

	/** Containers whose cover could not be decoded, so the grid stops retrying them. */
	private val deadCovers = Collections.synchronizedSet(HashSet<String>())

	/** The thumbnail for an imported comic, or null when it cannot be decoded. */
	suspend fun cover(container: String, entry: String?): ImageBitmap? {
		if (entry == null || container in deadCovers) return null
		covers[container]?.let { return it }
		return withContext(Dispatchers.IO) {
			val bytes = readOrNull(LocalPageRef(container, entry))
			val bitmap = bytes?.let { decode(it, coverWidth) }
			if (bitmap == null) {
				// A missing or undecodable first entry will not fix itself between
				// recompositions, and reopening the archive each time would make
				// scrolling the grid reopen every broken file on every frame.
				deadCovers += container
			} else {
				covers[container] = bitmap
			}
			bitmap
		}
	}

	/** A full-size page, addressed by the url carried on a local `MangaPage`. */
	suspend fun page(url: String): ImageBitmap? {
		val ref = LocalPageRef.decode(url) ?: return null
		pages[url]?.let { return it }
		return withContext(Dispatchers.IO) {
			val bitmap = readOrNull(ref)?.let { decode(it, targetWidth = 0) }
			if (bitmap != null) {
				pages[url] = bitmap
			}
			bitmap
		}
	}

	/** Drops everything remembered about [container], after a re-import or a removal. */
	fun forget(container: String) {
		covers.remove(container)
		deadCovers.remove(container)
		synchronized(pages) {
			pages.keys.removeAll { LocalPageRef.decode(it)?.container == container }
		}
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

	private fun <V> lru(maxEntries: Int): MutableMap<String, V> = Collections.synchronizedMap(
		object : LinkedHashMap<String, V>(32, 0.75f, true) {
			override fun removeEldestEntry(eldest: Map.Entry<String, V>): Boolean =
				size > maxEntries.coerceAtLeast(1)
		},
	)
}
