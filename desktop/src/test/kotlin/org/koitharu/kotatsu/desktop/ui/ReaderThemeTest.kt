package org.koitharu.kotatsu.desktop.ui

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * What the reader's nested theme keeps and what it replaces.
 *
 * The reader wraps its own subtree in a second [MaterialExpressiveTheme] so that `Slider`,
 * `DropdownMenu`, `IconButton` and the shared error and loading boxes come out in reader
 * colours with no colour code at the call sites. A nested theme replaces the whole bundle,
 * though, and the motion scheme is part of that bundle: if the reader does not carry
 * `MotionScheme.expressive()` through, it is lost in there with nothing on screen to show
 * for it, which is the regression DECISIONS.md D6a exists to catch.
 *
 * Measured rather than eyeballed, and measured with a control. The second test proves the
 * probe can tell a kept motion scheme from a replaced one, so the first test's pass means
 * something: without it, an assertion that reads the wrong thing passes either way.
 */
class ReaderThemeTest {

	@Test
	fun `the reader keeps the expressive motion scheme and takes the reader colours`() {
		var motion: MotionScheme? = null
		var surface: Color? = null
		var chrome: Color? = null
		render {
			ReaderTheme {
				motion = MaterialTheme.motionScheme
				surface = MaterialTheme.colorScheme.surface
				chrome = MaterialTheme.colorScheme.surfaceContainer
			}
		}

		assertSame(
			MotionScheme.expressive(),
			motion,
			"the reader is running on a different motion scheme from the rest of the app",
		)
		assertEquals(DropSauceReaderColors.ReaderPage, surface, "the page ground is not the reader's")
		assertEquals(DropSauceReaderColors.ReaderChrome, chrome, "the bar fill is not the reader's")
	}

	@Test
	fun `the probe can tell a replaced motion scheme from a carried one`() {
		// The positive control. A nested theme given a different scheme on purpose: if
		// this came back expressive as well, the assertion above would be reading
		// something other than what the nested theme actually provides.
		var motion: MotionScheme? = null
		render {
			MaterialExpressiveTheme(
				colorScheme = DropSauceDarkColors,
				motionScheme = MotionScheme.standard(),
			) {
				motion = MaterialTheme.motionScheme
			}
		}

		assertNotSame(
			MotionScheme.expressive(),
			motion,
			"the probe cannot see a nested theme's motion scheme at all, so it proves nothing",
		)
		assertSame(MotionScheme.standard(), motion)
	}

	/**
	 * Composes [content] under the app's own outer theme, once, on the CPU renderer.
	 *
	 * The outer theme is the point: the question is what the inner one inherits, and that
	 * is unanswerable without something real above it.
	 */
	private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
		val scene = ImageComposeScene(
			width = 64,
			height = 64,
			density = Density(1f),
			coroutineContext = Dispatchers.Unconfined,
		)
		try {
			scene.setContent {
				MaterialExpressiveTheme(
					colorScheme = DropSauceDarkColors,
					motionScheme = MotionScheme.expressive(),
				) {
					content()
				}
			}
			scene.render()
		} finally {
			scene.close()
		}
	}
}
