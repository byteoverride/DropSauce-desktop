package org.koitharu.kotatsu.desktop.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * Decodes encoded bytes to no more than a given width.
 *
 * A cover arrives at 1500x2000 and is drawn about 360px across. Decoded whole that is
 * 12 MB for something the grid shows at 0.7 MB's worth of pixels, and a library holds
 * hundreds of them. Measured on the real catalogue (D37), capping covers at the width
 * they are drawn removes 92% of what they occupy.
 *
 * skiko exposes no sample-size parameter. What works, found by experiment rather than
 * from the API, is to allocate the destination [Bitmap] at the size wanted and let Skia
 * either honour it or refuse, and the three formats in this catalogue refuse differently.
 * Hence the ladder in [decode]: ask for exactly what is wanted, then for a halving that
 * overshoots it, then give up and scale after the fact. Every rung produces the same
 * result; they differ only in what they cost.
 *
 * **Never upscales.** A page 948px wide drawn in a 1150px strip must stay 948px and be
 * stretched by the GPU at draw time, which is free. Decoding it at 1150 would allocate
 * more memory to display the same detail. This is not hypothetical: at the default strip
 * width most pages in the measured catalogue are *narrower* than the space they are drawn
 * in, which is why D37 cut reader pages from this work and kept covers.
 */
object ScaledDecode {

	/**
	 * [targetWidth] of zero, or anything at least as wide as the source, decodes whole.
	 *
	 * Returns null for a payload Skia cannot read at all, which the caller reports as an
	 * unsupported format rather than as a memory problem.
	 */
	fun decode(bytes: ByteArray, targetWidth: Int): ImageBitmap? {
		val source = dimensions(bytes) ?: return null
		val (sourceWidth, sourceHeight) = source
		if (targetWidth <= 0 || sourceWidth <= 0 || sourceHeight <= 0 || targetWidth >= sourceWidth) {
			return whole(bytes)
		}
		val targetHeight = heightFor(sourceWidth, sourceHeight, targetWidth)

		// Exactly what was asked for. WebP takes any size this way; JPEG takes only the
		// ratios its decoder supports, so this usually fails for JPEG and always for PNG.
		subsampled(bytes, targetWidth, targetHeight)?.let { return it }

		// A halving that is still at least as wide as the target. For JPEG this is a real
		// subsampled decode and the full image is never produced; the leftover difference
		// is taken off afterwards, by which point the image is already small.
		val stepped = steppedDown(bytes, sourceWidth, sourceHeight, targetWidth)
		if (stepped != null) {
			return shrink(stepped, targetWidth, targetHeight) ?: stepped.toComposeImageBitmap()
		}

		// PNG, and anything else that will not scale while decoding. The full size is
		// materialised once and thrown away; the cache keeps only the small result.
		val full = try {
			Image.makeFromEncoded(bytes)
		} catch (e: Exception) {
			return null
		}
		return full.use { shrink(it, targetWidth, targetHeight) ?: it.toComposeImageBitmap() }
	}

	/** Width and height from the header, allocating no pixels. */
	fun dimensions(bytes: ByteArray): Pair<Int, Int>? = try {
		Data.makeFromBytes(bytes).use { data ->
			Codec.makeFromData(data).use { codec -> codec.width to codec.height }
		}
	} catch (e: Throwable) {
		null
	}

	/** Preserves the aspect ratio, and never rounds a dimension away to nothing. */
	fun heightFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int): Int =
		(sourceHeight.toLong() * targetWidth / sourceWidth).toInt().coerceAtLeast(1)

	private fun whole(bytes: ByteArray): ImageBitmap? = try {
		Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
	} catch (e: Exception) {
		// Skia throws a plain IllegalArgumentException for a payload it cannot read,
		// which includes AVIF and an error page served with an image content type.
		null
	}

	/**
	 * Decodes straight into a destination of the given size.
	 *
	 * Skia throws `IllegalArgumentException: Invalid scale` when its decoder cannot
	 * produce that size, which is the signal to try something else rather than an error
	 * worth reporting.
	 */
	private fun subsampled(bytes: ByteArray, width: Int, height: Int): ImageBitmap? = try {
		Data.makeFromBytes(bytes).use { data ->
			Codec.makeFromData(data).use { codec ->
				val bitmap = Bitmap()
				bitmap.allocPixels(ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL))
				codec.readPixels(bitmap)
				bitmap.setImmutable()
				Image.makeFromBitmap(bitmap).use { it.toComposeImageBitmap() }
			}
		}
	} catch (e: Throwable) {
		null
	}

	/**
	 * The smallest halving of the source that is still at least [targetWidth] across.
	 *
	 * Halvings are what a JPEG decoder can actually do, so asking for one is the
	 * difference between a subsampled decode and a full one.
	 */
	private fun steppedDown(bytes: ByteArray, sourceWidth: Int, sourceHeight: Int, targetWidth: Int): Image? {
		var divisor = 2
		while (sourceWidth / divisor >= targetWidth && divisor <= MAX_DIVISOR) {
			divisor *= 2
		}
		divisor /= 2
		while (divisor >= 2) {
			val width = ceilDiv(sourceWidth, divisor)
			val height = ceilDiv(sourceHeight, divisor)
			val image = try {
				Data.makeFromBytes(bytes).use { data ->
					Codec.makeFromData(data).use { codec ->
						val bitmap = Bitmap()
						bitmap.allocPixels(ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL))
						codec.readPixels(bitmap)
						bitmap.setImmutable()
						Image.makeFromBitmap(bitmap)
					}
				}
			} catch (e: Throwable) {
				null
			}
			if (image != null) return image
			divisor /= 2
		}
		return null
	}

	/** Draws [image] into a raster surface of the target size. */
	private fun shrink(image: Image, width: Int, height: Int): ImageBitmap? = try {
		Surface.makeRaster(ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL)).use { surface ->
			surface.canvas.drawImageRect(
				image,
				Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
				Rect.makeWH(width.toFloat(), height.toFloat()),
				SamplingMode.LINEAR,
				null,
				true,
			)
			surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
		}
	} catch (e: Throwable) {
		null
	}

	/**
	 * Rounds up, because Skia computes its own supported size by rounding up and refuses
	 * a request that is one pixel short. An eighth of 900 is 113 to Skia and 112 to
	 * integer division, and asking for 112 throws.
	 */
	private fun ceilDiv(value: Int, divisor: Int): Int =
		((value + divisor - 1) / divisor).coerceAtLeast(1)

	/** JPEG decoders scale by eighths; past that there is nothing left to ask for. */
	private const val MAX_DIVISOR = 8
}
