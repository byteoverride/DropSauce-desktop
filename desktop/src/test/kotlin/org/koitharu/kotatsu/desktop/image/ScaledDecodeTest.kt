package org.koitharu.kotatsu.desktop.image

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoding to a target width, across the formats the catalogue actually serves.
 *
 * Each of the three refuses differently and none of it is in the API: JPEG scales only by
 * the ratios its decoder supports, WebP accepts any size but produces the whole image
 * internally first, and PNG will not scale while decoding at all. A test per format,
 * because a single one would pass on whichever happened to be easiest.
 */
class ScaledDecodeTest {

	@Test
	fun `a jpeg cover is decoded at the width it will be drawn`() {
		val decoded = assertNotNull(ScaledDecode.decode(jpeg(1500, 2000), targetWidth = 360))
		assertEquals(360, decoded.width)
		assertEquals(480, decoded.height, "aspect ratio was not kept")
	}

	@Test
	fun `a webp cover is decoded at the width it will be drawn`() {
		val decoded = assertNotNull(ScaledDecode.decode(reencode(jpeg(1500, 2000), EncodedImageFormat.WEBP), 360))
		assertEquals(360, decoded.width)
		assertEquals(480, decoded.height)
	}

	// PNG refuses a scaled decode outright, so this exercises the last rung of the
	// ladder: decode whole, shrink, discard the original.
	@Test
	fun `a png cover still ends up at the target width`() {
		val decoded = assertNotNull(ScaledDecode.decode(reencode(jpeg(1500, 2000), EncodedImageFormat.PNG), 360))
		assertEquals(360, decoded.width)
		assertEquals(480, decoded.height)
	}

	// The measured case that cut reader pages from this work. A 948px page drawn in a
	// 1150px strip must stay 948px: decoding it at 1150 would allocate more memory to
	// show the same detail, and the GPU stretches it at draw time for nothing.
	@Test
	fun `a source narrower than the target is left alone`() {
		val decoded = assertNotNull(ScaledDecode.decode(jpeg(948, 3409), targetWidth = 1150))
		assertEquals(948, decoded.width)
		assertEquals(3409, decoded.height)
	}

	@Test
	fun `no target width means decode whole`() {
		val decoded = assertNotNull(ScaledDecode.decode(jpeg(800, 600), targetWidth = 0))
		assertEquals(800, decoded.width)
		assertEquals(600, decoded.height)
	}

	@Test
	fun `an exact match is not resized`() {
		val decoded = assertNotNull(ScaledDecode.decode(jpeg(360, 480), targetWidth = 360))
		assertEquals(360, decoded.width)
	}

	// A width that is no simple fraction of the source, which is the normal case: the
	// ladder has to fall through the exact ask and the halvings and still land on it.
	@Test
	fun `an awkward ratio still lands exactly on the target`() {
		for (target in listOf(37, 111, 359, 500, 777)) {
			val decoded = assertNotNull(ScaledDecode.decode(jpeg(1500, 2000), target), "target $target")
			assertEquals(target, decoded.width, "target $target came back ${decoded.width}")
		}
	}

	@Test
	fun `a very tall page keeps at least one pixel of height`() {
		val decoded = assertNotNull(ScaledDecode.decode(jpeg(4000, 3), targetWidth = 10))
		assertEquals(10, decoded.width)
		assertTrue(decoded.height >= 1, "height rounded away to ${decoded.height}")
	}

	@Test
	fun `something Skia cannot read is null rather than a guess`() {
		assertNull(ScaledDecode.decode("<html>not an image</html>".toByteArray(), 360))
		assertNull(ScaledDecode.decode(ByteArray(0), 360))
	}

	// The saving this whole phase exists for, asserted rather than asserted about.
	@Test
	fun `a cover at display width costs a fraction of the whole one`() {
		val bytes = jpeg(1500, 2000)
		val whole = assertNotNull(ScaledDecode.decode(bytes, targetWidth = 0))
		val sized = assertNotNull(ScaledDecode.decode(bytes, targetWidth = 360))

		val wholeBytes = whole.width.toLong() * whole.height * 4
		val sizedBytes = sized.width.toLong() * sized.height * 4
		assertTrue(
			sizedBytes * 15 < wholeBytes,
			"expected an order of magnitude: $wholeBytes became $sizedBytes",
		)
	}

	private fun jpeg(width: Int, height: Int): ByteArray {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val random = java.util.Random(7)
		// Real detail, so a decoder cannot take a shortcut a flat image would allow.
		for (y in 0 until height step 2) {
			for (x in 0 until width step 2) image.setRGB(x, y, random.nextInt())
		}
		return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
	}

	/** ImageIO cannot write WebP, so Skia re-encodes what Skia will read back. */
	private fun reencode(source: ByteArray, format: EncodedImageFormat): ByteArray =
		Image.makeFromEncoded(source).encodeToData(format, 80, 0)!!.bytes
}
