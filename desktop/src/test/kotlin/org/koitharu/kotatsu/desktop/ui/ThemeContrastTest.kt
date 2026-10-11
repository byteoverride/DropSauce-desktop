package org.koitharu.kotatsu.desktop.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Every colour pair the app puts text or a boundary on, measured.
 *
 * The scheme was generated rather than picked, so the thing worth asserting is not that
 * a hex is a particular value but that the pairs still clear WCAG AA after anyone edits
 * one. 4.5:1 for body text, 3:1 for large text and for non-text boundaries and states
 * (WCAG 1.4.11).
 *
 * The ratios are computed from the `ColorScheme` objects themselves, not from a copy of
 * the numbers, so a change to `Theme.kt` is what this reads.
 */
class ThemeContrastTest {

	private val dark = DropSauceDarkColors
	private val light = DropSauceLightColors

	/**
	 * The positive control.
	 *
	 * Without it every assertion below could be measuring nothing and still pass. This
	 * proves the formula separates a pair that fails from a pair that passes before any
	 * of the real pairs are trusted to it.
	 */
	@Test
	fun `the measurement detects a pair that fails, and agrees with a known value`() {
		val grey = Color(0xFF777777)
		val white = Color(0xFFFFFFFF)
		val black = Color(0xFF000000)

		assertTrue("mid grey on white must not reach 4.5", contrast(grey, white) < 4.5)
		assertTrue("mid grey on white must still clear 3.0", contrast(grey, white) >= 3.0)
		// Black on white is exactly 21:1 by definition, which pins the formula itself.
		assertEquals(21.0, contrast(black, white), 0.01)
	}

	@Test
	fun `dark scheme body and secondary text clear 4 point 5`() {
		assertPasses(dark, "body on surface", { onSurface }, { surface }, 4.5)
		assertPasses(dark, "body on card", { onSurface }, { surfaceContainer }, 4.5)
		assertPasses(dark, "body on container-high", { onSurface }, { surfaceContainerHigh }, 4.5)
		assertPasses(dark, "body on container-highest", { onSurface }, { surfaceContainerHighest }, 4.5)
		assertPasses(dark, "secondary on surface", { onSurfaceVariant }, { surface }, 4.5)
		assertPasses(dark, "secondary on card", { onSurfaceVariant }, { surfaceContainer }, 4.5)
		assertPasses(dark, "secondary on surfaceVariant", { onSurfaceVariant }, { surfaceVariant }, 4.5)
	}

	@Test
	fun `light scheme body and secondary text clear 4 point 5`() {
		assertPasses(light, "body on surface", { onSurface }, { surface }, 4.5)
		assertPasses(light, "body on card", { onSurface }, { surfaceContainer }, 4.5)
		assertPasses(light, "body on container-high", { onSurface }, { surfaceContainerHigh }, 4.5)
		assertPasses(light, "body on container-highest", { onSurface }, { surfaceContainerHighest }, 4.5)
		assertPasses(light, "secondary on surface", { onSurfaceVariant }, { surface }, 4.5)
		assertPasses(light, "secondary on card", { onSurfaceVariant }, { surfaceContainer }, 4.5)
		assertPasses(light, "secondary on surfaceVariant", { onSurfaceVariant }, { surfaceVariant }, 4.5)
	}

	@Test
	fun `accents, containers and filled controls carry their own labels`() {
		for ((name, scheme) in schemes()) {
			assertPasses(scheme, "$name accent text on surface", { primary }, { surface }, 4.5)
			assertPasses(scheme, "$name accent text on card", { primary }, { surfaceContainer }, 4.5)
			assertPasses(scheme, "$name filled button label", { onPrimary }, { primary }, 4.5)
			assertPasses(scheme, "$name tonal chip label", { onPrimaryContainer }, { primaryContainer }, 4.5)
			assertPasses(scheme, "$name selected chip label", { onSecondaryContainer }, { secondaryContainer }, 4.5)
			assertPasses(scheme, "$name tertiary badge label", { onTertiaryContainer }, { tertiaryContainer }, 4.5)
			assertPasses(scheme, "$name tertiary text", { tertiary }, { surface }, 4.5)
			assertPasses(scheme, "$name snackbar text", { inverseOnSurface }, { inverseSurface }, 4.5)
		}
	}

	@Test
	fun `error text and error controls clear 4 point 5`() {
		for ((name, scheme) in schemes()) {
			assertPasses(scheme, "$name error text on surface", { error }, { surface }, 4.5)
			assertPasses(scheme, "$name error text on card", { error }, { surfaceContainer }, 4.5)
			assertPasses(scheme, "$name error button label", { onError }, { error }, 4.5)
			assertPasses(scheme, "$name error container label", { onErrorContainer }, { errorContainer }, 4.5)
		}
	}

	@Test
	fun `boundaries and the focus ring clear 3 for a non-text indicator`() {
		for ((name, scheme) in schemes()) {
			assertPasses(scheme, "$name outlined border on surface", { outline }, { surface }, 3.0)
			assertPasses(scheme, "$name outlined border on card", { outline }, { surfaceContainer }, 3.0)
			assertPasses(scheme, "$name focus ring on surface", { primary }, { surface }, 3.0)
			assertPasses(scheme, "$name focus ring on card", { primary }, { surfaceContainer }, 3.0)
			assertPasses(scheme, "$name focus ring on container-high", { primary }, { surfaceContainerHigh }, 3.0)
			// The rail's selected pill. Its fill cannot reach 3:1 against the rail at any
			// tone Material defines, so the 1 dp stroke is what carries the state, and
			// this is the assertion that keeps it honest.
			assertPasses(scheme, "$name rail indicator stroke", { outline }, { surfaceContainer }, 3.0)
		}
	}

	@Test
	fun `the reader's own colours clear their thresholds against its black page`() {
		val c = DropSauceReaderColors
		assertAbove("chrome text", contrast(c.ReaderOnChrome, c.ReaderChrome), 4.5)
		assertAbove("muted chrome text", contrast(c.ReaderOnChromeVariant, c.ReaderChrome), 4.5)
		assertAbove("accent on chrome", contrast(c.ReaderAccent, c.ReaderChrome), 4.5)
		assertAbove("failure text on page", contrast(c.ReaderOnChromeVariant, c.ReaderPage), 4.5)
		assertAbove("control border on chrome", contrast(c.ReaderChromeOutline, c.ReaderChrome), 3.0)
		// The bar is only 1.71:1 against the page, so the rule between them is the thing
		// that makes the bar findable. It has to clear 3:1 on BOTH sides to do that.
		assertAbove("bar rule, page side", contrast(c.ReaderChromeOutline, c.ReaderPage), 3.0)
		assertAbove("bar rule, bar side", contrast(c.ReaderChromeOutline, c.ReaderChrome), 3.0)
	}

	@Test
	fun `dark primary is the brand hex from the app icon, not a near neighbour`() {
		// The generated tone 80 was #68D3FF. Shipping a colour that is almost the logo is
		// worse than shipping the logo's, so it is pinned, and this is what pins it.
		assertEquals(Color(0xFF64D2FF), dark.primary)
		assertEquals(Color(0xFF64D2FF), dark.surfaceTint)
		assertEquals(Color(0xFF64D2FF), DropSauceReaderColors.ReaderAccent)
	}

	@Test
	fun `tertiary is not another purple`() {
		// Material's TonalSpot would put tertiary at hue + 60, which at this seed is a
		// lavender — visually the same purple the scheme exists to replace. A warm
		// tertiary has more red than blue; the default would have had more blue.
		for ((name, scheme) in schemes()) {
			assertTrue(
				"$name tertiary should be warm, was ${scheme.tertiary}",
				scheme.tertiary.red > scheme.tertiary.blue,
			)
		}
	}

	private fun schemes() = listOf("dark" to dark, "light" to light)

	private fun assertPasses(
		scheme: ColorScheme,
		what: String,
		foreground: ColorScheme.() -> Color,
		background: ColorScheme.() -> Color,
		need: Double,
	) = assertAbove(what, contrast(scheme.foreground(), scheme.background()), need)

	private fun assertAbove(what: String, ratio: Double, need: Double) = assertTrue(
		"$what is ${"%.2f".format(ratio)}:1, needs $need:1",
		ratio >= need,
	)

	/** WCAG 2.x relative luminance. */
	private fun luminance(color: Color): Double {
		fun channel(v: Float): Double {
			val c = v.toDouble()
			return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
		}
		return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
	}

	private fun contrast(a: Color, b: Color): Double {
		val la = luminance(a)
		val lb = luminance(b)
		return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
	}
}
