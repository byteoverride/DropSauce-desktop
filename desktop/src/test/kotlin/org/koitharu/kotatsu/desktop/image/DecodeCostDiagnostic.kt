package org.koitharu.kotatsu.desktop.image

import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import kotlin.system.measureTimeMillis
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What a webtoon page costs to decode, and what decoding it smaller would save.
 *
 * A reader on two virtual CPUs reports the app is slow. The app decodes every page at
 * full resolution through Skia and then draws it scaled into a strip that is typically
 * half the window wide, so most of that work is thrown away on the way to the screen.
 * These are the numbers for deciding whether that is worth fixing, measured rather than
 * assumed.
 */
class DecodeCostDiagnostic {

	@BeforeTest
	fun requireLive() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
	}

	@Test
	fun `measure full and subsampled decodes of a realistic page`() {
		for ((w, h) in listOf(800 to 5000, 900 to 12000)) {
			val jpeg = encode(w, h, "jpg")
			println("PAGE ${w}x$h  encoded ${jpeg.size / 1024} KB")
			println("  full decode:      %s".format(report(w, h) { decodeFull(jpeg) }))
			for (factor in listOf(2, 3, 4)) {
				println("  subsample 1/$factor:    %s".format(report(w / factor, h / factor) { decodeSub(jpeg, factor) }))
			}
		}
	}

	/** Skia's path, which is what the app does today. */
	private fun decodeFull(bytes: ByteArray): Pair<Int, Int> {
		val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
		return image.width to image.height
	}

	/**
	 * ImageIO reading at a fraction of the resolution.
	 *
	 * `setSourceSubsampling` is the same family as the `setSourceRegion` DECISIONS.md D10
	 * measured: it ships in the JDK, needs no dependency, and never materialises the full
	 * image, so the saving is in decode time as well as in memory.
	 */
	private fun decodeSub(bytes: ByteArray, factor: Int): Pair<Int, Int> {
		ImageIO.createImageInputStream(bytes.inputStream()).use { stream ->
			val reader = ImageIO.getImageReaders(stream).next()
			reader.input = stream
			val params: ImageReadParam = reader.defaultReadParam
			params.setSourceSubsampling(factor, factor, 0, 0)
			val image = reader.read(0, params)
			reader.dispose()
			return image.width to image.height
		}
	}

	private fun report(expectW: Int, expectH: Int, decode: () -> Pair<Int, Int>): String {
		decode() // warm the codec, so the first run's class loading is not the measurement
		var size = 0 to 0
		val runs = 3
		val ms = (1..runs).sumOf { measureTimeMillis { size = decode() } } / runs
		val mb = size.first.toLong() * size.second * 4 / 1_000_000
		return "${size.first}x${size.second}  ${ms}ms  ${mb}MB decoded"
	}

	private fun encode(width: Int, height: Int, format: String): ByteArray {
		// Noise rather than a flat colour: a solid image compresses to nothing and
		// decodes far faster than a real page, which would flatter every number here.
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val random = java.util.Random(1)
		for (y in 0 until height step 2) {
			for (x in 0 until width step 2) {
				image.setRGB(x, y, random.nextInt())
			}
		}
		val out = ByteArrayOutputStream()
		ImageIO.write(image, format, out)
		return out.toByteArray()
	}
}
