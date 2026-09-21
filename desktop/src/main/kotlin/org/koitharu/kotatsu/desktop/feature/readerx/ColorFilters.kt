package org.koitharu.kotatsu.desktop.feature.readerx

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the reader does to a page image before drawing it.
 *
 * Every field defaults to the value that changes nothing, so an older config file missing
 * a key still loads and so [isIdentity] is a plain equality check against the default.
 *
 * Grayscale and sepia are amounts rather than toggles. A reader who wants a slightly warm
 * page has no way to ask for that with a checkbox, and the matrix interpolates for free.
 */
@Serializable
data class ReaderColorParams(
	/** -1 is black, 0 is unchanged, 1 is twice as bright. Multiplicative, like Android's. */
	@SerialName("brightness") val brightness: Float = 0f,
	/** -1 is flat grey, 0 is unchanged, 1 is double contrast. */
	@SerialName("contrast") val contrast: Float = 0f,
	/** 0 is untouched, 1 is fully desaturated. */
	@SerialName("grayscale") val grayscale: Float = 0f,
	/** 0 is untouched, 1 is fully toned. */
	@SerialName("sepia") val sepia: Float = 0f,
	@SerialName("invert") val invert: Boolean = false,
) {

	/** True when applying this would be a no-op, so the reader can skip the filter. */
	val isIdentity: Boolean get() = this == DEFAULT

	/** Clamps every field into the range the sliders offer. */
	fun coerced(): ReaderColorParams = copy(
		brightness = brightness.coerceIn(-1f, 1f),
		contrast = contrast.coerceIn(-1f, 1f),
		grayscale = grayscale.coerceIn(0f, 1f),
		sepia = sepia.coerceIn(0f, 1f),
	)

	companion object {

		val DEFAULT = ReaderColorParams()
	}
}

/**
 * The 4x5 colour matrix for [params], row-major, in the layout Compose expects.
 *
 * Compose's [ColorMatrix] operates on channels in the 0..255 range, not 0..1, which is
 * why the translate column carries values like 255 rather than 1. Getting that wrong
 * produces a filter that looks almost right at low settings and clips hard at high ones.
 *
 * The stages are composed in a fixed order: desaturate, tone, invert, brighten, then
 * contrast. Order matters. Toning after desaturating is what makes sepia look like sepia
 * rather than a colour cast, and contrast last is what lets the contrast control still do
 * something useful once brightness has already scaled everything.
 */
fun colorMatrixOf(params: ReaderColorParams): ColorMatrix {
	val p = params.coerced()
	var m = identityMatrix()
	if (p.grayscale > 0f) m = concat(grayscaleMatrix(p.grayscale), m)
	if (p.sepia > 0f) m = concat(sepiaMatrix(p.sepia), m)
	if (p.invert) m = concat(invertMatrix(), m)
	if (p.brightness != 0f) m = concat(brightnessMatrix(p.brightness), m)
	if (p.contrast != 0f) m = concat(contrastMatrix(p.contrast), m)
	return ColorMatrix(m)
}

/** The [ColorFilter] for [params], or null when it would change nothing. */
fun colorFilterOf(params: ReaderColorParams): ColorFilter? =
	if (params.coerced().isIdentity) null else ColorFilter.colorMatrix(colorMatrixOf(params))

/**
 * `a` applied after `b`, as one matrix.
 *
 * Hand-written rather than [ColorMatrix.timesAssign] because which operand ends up on the
 * left of that product is a detail of Compose's implementation, and a filter chain that
 * silently composes in the wrong order is the kind of bug that only shows up when two
 * non-commuting stages are both switched on.
 */
internal fun concat(a: FloatArray, b: FloatArray): FloatArray {
	val out = FloatArray(MATRIX_SIZE)
	for (row in 0 until 4) {
		for (column in 0 until 4) {
			var sum = 0f
			for (k in 0 until 4) {
				sum += a[row * 5 + k] * b[k * 5 + column]
			}
			out[row * 5 + column] = sum
		}
		// The fifth column is a translation, so it picks up a's translation plus a
		// applied to b's.
		var translate = a[row * 5 + 4]
		for (k in 0 until 4) {
			translate += a[row * 5 + k] * b[k * 5 + 4]
		}
		out[row * 5 + 4] = translate
	}
	return out
}

internal fun identityMatrix(): FloatArray = floatArrayOf(
	1f, 0f, 0f, 0f, 0f,
	0f, 1f, 0f, 0f, 0f,
	0f, 0f, 1f, 0f, 0f,
	0f, 0f, 0f, 1f, 0f,
)

/**
 * Desaturation by [amount], interpolated from the identity.
 *
 * BT.709 luminance weights, which is what Compose's own `setToSaturation` uses. At full
 * strength all three colour rows become the same weights, which is exactly what "this
 * pixel's grey level does not depend on which channel it came from" means.
 */
internal fun grayscaleMatrix(amount: Float): FloatArray {
	val t = amount.coerceIn(0f, 1f)
	val inv = 1f - t
	val r = LUMA_R * t
	val g = LUMA_G * t
	val b = LUMA_B * t
	return floatArrayOf(
		inv + r, g, b, 0f, 0f,
		r, inv + g, b, 0f, 0f,
		r, g, inv + b, 0f, 0f,
		0f, 0f, 0f, 1f, 0f,
	)
}

/** The usual sepia tone, interpolated from the identity by [amount]. */
internal fun sepiaMatrix(amount: Float): FloatArray {
	val t = amount.coerceIn(0f, 1f)
	val inv = 1f - t
	return floatArrayOf(
		inv + 0.393f * t, 0.769f * t, 0.189f * t, 0f, 0f,
		0.349f * t, inv + 0.686f * t, 0.168f * t, 0f, 0f,
		0.272f * t, 0.534f * t, inv + 0.131f * t, 0f, 0f,
		0f, 0f, 0f, 1f, 0f,
	)
}

/**
 * Photographic negative.
 *
 * Written as `-1 * c + 255` rather than the Android app's `-1 * c + alpha + 1`, which
 * lands on 256 for an opaque pixel and is therefore not an involution. This one is: apply
 * it twice and you are exactly back where you started, which the tests check.
 */
internal fun invertMatrix(): FloatArray = floatArrayOf(
	-1f, 0f, 0f, 0f, CHANNEL_MAX,
	0f, -1f, 0f, 0f, CHANNEL_MAX,
	0f, 0f, -1f, 0f, CHANNEL_MAX,
	0f, 0f, 0f, 1f, 0f,
)

internal fun brightnessMatrix(brightness: Float): FloatArray {
	val scale = brightness + 1f
	return floatArrayOf(
		scale, 0f, 0f, 0f, 0f,
		0f, scale, 0f, 0f, 0f,
		0f, 0f, scale, 0f, 0f,
		0f, 0f, 0f, 1f, 0f,
	)
}

/** Scales around mid grey, so raising contrast does not also brighten the whole page. */
internal fun contrastMatrix(contrast: Float): FloatArray {
	val scale = contrast + 1f
	val translate = (-0.5f * scale + 0.5f) * CHANNEL_MAX
	return floatArrayOf(
		scale, 0f, 0f, 0f, translate,
		0f, scale, 0f, 0f, translate,
		0f, 0f, scale, 0f, translate,
		0f, 0f, 0f, 1f, 0f,
	)
}

internal const val MATRIX_SIZE = 20

/** Compose colour matrices work in 0..255, not 0..1. */
private const val CHANNEL_MAX = 255f

private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f
