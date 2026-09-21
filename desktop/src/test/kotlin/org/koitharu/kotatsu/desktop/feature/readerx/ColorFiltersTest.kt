package org.koitharu.kotatsu.desktop.feature.readerx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorFiltersTest {

	private val tolerance = 1e-4f

	/** Applies a 4x5 matrix to a colour in 0..255, which is the range Compose uses. */
	private fun apply(m: FloatArray, r: Float, g: Float, b: Float, a: Float = 255f) = floatArrayOf(
		m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4],
		m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9],
		m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14],
		m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19],
	)

	private fun assertMatrixEquals(expected: FloatArray, actual: FloatArray) {
		assertEquals(MATRIX_SIZE, actual.size)
		assertArrayEquals(expected, actual, tolerance)
	}

	@Test
	fun `default parameters produce the identity matrix`() {
		val matrix = colorMatrixOf(ReaderColorParams.DEFAULT).values
		assertMatrixEquals(identityMatrix(), matrix)
	}

	@Test
	fun `default parameters produce no filter at all`() {
		// The reader skips the draw-time filter entirely when nothing is configured.
		assertNull(colorFilterOf(ReaderColorParams.DEFAULT))
		assertTrue(ReaderColorParams.DEFAULT.isIdentity)
	}

	@Test
	fun `any non-default parameter produces a filter`() {
		assertNotNull(colorFilterOf(ReaderColorParams(invert = true)))
		assertNotNull(colorFilterOf(ReaderColorParams(brightness = 0.2f)))
		assertNotNull(colorFilterOf(ReaderColorParams(sepia = 0.1f)))
	}

	@Test
	fun `full grayscale gives all three colour rows the same weights`() {
		val m = grayscaleMatrix(1f)
		val red = floatArrayOf(m[0], m[1], m[2])
		val green = floatArrayOf(m[5], m[6], m[7])
		val blue = floatArrayOf(m[10], m[11], m[12])
		assertArrayEquals(red, green, tolerance)
		assertArrayEquals(red, blue, tolerance)
		// And those weights sum to one, so a grey stays exactly the grey it was.
		assertEquals(1f, red.sum(), tolerance)
	}

	@Test
	fun `full grayscale maps any colour to a single grey level`() {
		val out = apply(grayscaleMatrix(1f), 200f, 50f, 10f)
		assertEquals(out[0], out[1], tolerance)
		assertEquals(out[1], out[2], tolerance)
		// Alpha is untouched, which is what keeps a page from going transparent.
		assertEquals(255f, out[3], tolerance)
	}

	@Test
	fun `zero grayscale is the identity`() {
		assertMatrixEquals(identityMatrix(), grayscaleMatrix(0f))
	}

	@Test
	fun `zero sepia is the identity`() {
		assertMatrixEquals(identityMatrix(), sepiaMatrix(0f))
	}

	@Test
	fun `sepia warms a neutral grey`() {
		val out = apply(sepiaMatrix(1f), 128f, 128f, 128f)
		assertTrue("red ${out[0]} should exceed green ${out[1]}", out[0] > out[1])
		assertTrue("green ${out[1]} should exceed blue ${out[2]}", out[1] > out[2])
	}

	@Test
	fun `inverting twice is the identity`() {
		assertMatrixEquals(identityMatrix(), concat(invertMatrix(), invertMatrix()))
	}

	@Test
	fun `invert maps black to white and back`() {
		val inverted = apply(invertMatrix(), 0f, 0f, 0f)
		assertArrayEquals(floatArrayOf(255f, 255f, 255f, 255f), inverted, tolerance)
		val restored = apply(invertMatrix(), inverted[0], inverted[1], inverted[2])
		assertArrayEquals(floatArrayOf(0f, 0f, 0f, 255f), restored, tolerance)
	}

	@Test
	fun `invert leaves mid grey where it is`() {
		val out = apply(invertMatrix(), 127.5f, 127.5f, 127.5f)
		assertEquals(127.5f, out[0], tolerance)
	}

	@Test
	fun `zero brightness and zero contrast are the identity`() {
		assertMatrixEquals(identityMatrix(), brightnessMatrix(0f))
		assertMatrixEquals(identityMatrix(), contrastMatrix(0f))
	}

	@Test
	fun `contrast pivots around mid grey rather than around black`() {
		// The point of the translate term: raising contrast must not also brighten the
		// whole page, so the mid point has to be a fixed point of the transform.
		val out = apply(contrastMatrix(0.5f), 127.5f, 127.5f, 127.5f)
		assertEquals(127.5f, out[0], 1e-2f)
		val dark = apply(contrastMatrix(0.5f), 60f, 60f, 60f)
		assertTrue("raising contrast should darken a dark tone", dark[0] < 60f)
	}

	@Test
	fun `brightness scales every channel`() {
		val out = apply(brightnessMatrix(0.5f), 100f, 40f, 20f)
		assertArrayEquals(floatArrayOf(150f, 60f, 30f, 255f), out, tolerance)
	}

	@Test
	fun `concat applies the right hand matrix first`() {
		// Order is load-bearing: brightness then invert is not invert then brightness,
		// and a chain composed the wrong way round looks plausible until both are on.
		val brightThenInvert = concat(invertMatrix(), brightnessMatrix(0.5f))
		val out = apply(brightThenInvert, 100f, 100f, 100f)
		assertEquals(255f - 150f, out[0], tolerance)
	}

	@Test
	fun `the composed filter applies every stage`() {
		val params = ReaderColorParams(grayscale = 1f, invert = true)
		val out = apply(colorMatrixOf(params).values, 200f, 50f, 10f)
		val grey = apply(grayscaleMatrix(1f), 200f, 50f, 10f)
		assertEquals(255f - grey[0], out[0], tolerance)
		assertEquals(out[0], out[1], tolerance)
	}

	@Test
	fun `out of range parameters are clamped rather than producing a wild matrix`() {
		val wild = ReaderColorParams(brightness = 9f, contrast = -9f, grayscale = 4f, sepia = -2f)
		assertEquals(ReaderColorParams(brightness = 1f, contrast = -1f, grayscale = 1f, sepia = 0f), wild.coerced())
		val clamped = colorMatrixOf(wild).values
		assertEquals(MATRIX_SIZE, clamped.size)
		assertTrue(clamped.all { it.isFinite() })
	}

	@Test
	fun `alpha is never touched by any stage`() {
		val params = ReaderColorParams(brightness = 0.7f, contrast = -0.4f, grayscale = 0.5f, sepia = 0.6f, invert = true)
		val m = colorMatrixOf(params).values
		// The alpha row must stay 0,0,0,1,0 or pages fade in and out as sliders move.
		assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), m.copyOfRange(15, 20), tolerance)
	}
}
