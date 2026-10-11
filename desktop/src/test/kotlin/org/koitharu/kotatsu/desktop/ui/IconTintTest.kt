package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The icons are vendored Material Symbols SVGs with no fill of their own, drawn through
 * [Icon] so the theme colour applies. Loading one proves nothing about that: a painter
 * that renders solid black would load exactly as well and would be invisible on a dark
 * surface. This renders one and reads the pixels back.
 */
class IconTintTest {

	@Test
	fun `a vendored icon renders in the colour it is tinted with`() {
		val density = Density(1f)
		val painter = javaClass.classLoader.getResourceAsStream("icons/download.svg")!!
			.use { loadSvgPainter(it, density) }
		assertEquals(24f, painter.intrinsicSize.width)

		val scene = ImageComposeScene(width = 24, height = 24, density = density) {
			Icon(
				painter = painter,
				contentDescription = null,
				tint = Color.Red,
				modifier = Modifier.size(24.dp),
			)
		}
		val pixels = try {
			scene.render().toComposeImageBitmap().toPixelMap()
		} finally {
			scene.close()
		}

		var red = 0
		var other = 0
		for (x in 0 until 24) {
			for (y in 0 until 24) {
				val c = pixels[x, y]
				if (c.alpha < 0.5f) continue
				if (c.red > 0.5f && c.green < 0.5f && c.blue < 0.5f) red++ else other++
			}
		}
		assertTrue("the icon drew nothing at all", red + other > 20)
		assertTrue("expected red pixels, got $red red and $other other", red > other)
	}
}
