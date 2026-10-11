package org.koitharu.kotatsu.desktop.ui

import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every icon the app can draw, resolved and parsed.
 *
 * `painterResource` throws on a missing classpath resource, so a dropped SVG is a crash
 * the first time a screen is opened. This is where that is caught instead: a packaging
 * mistake fails the build.
 */
class AppIconsTest {

	private val loader = AppIcons::class.java.classLoader

	/**
	 * The positive control.
	 *
	 * Every assertion below is of the form "this resource resolves". If the lookup
	 * silently returned something for any string, they would all pass while measuring
	 * nothing. This proves the lookup can fail.
	 */
	@Test
	fun `a name that was never vendored does not resolve`() {
		assertNull(loader.getResource(AppIcons.filePath("this_icon_does_not_exist")))
	}

	@Test
	fun `every icon name resolves to a file that parses as a 24dp painter`() {
		val missing = AppIcons.all.filter { loader.getResource(AppIcons.filePath(it)) == null }
		assertEquals("icons named but not vendored: $missing", emptyList<String>(), missing)

		for (name in AppIcons.all) {
			val stream = loader.getResourceAsStream(AppIcons.filePath(name))
			assertNotNull("no stream for $name", stream)
			val painter = stream!!.use { loadSvgPainter(it, Density(1f)) }
			assertEquals("$name is not 24dp wide", 24f, painter.intrinsicSize.width)
			assertEquals("$name is not 24dp tall", 24f, painter.intrinsicSize.height)
		}
	}

	@Test
	fun `every filled variant resolves and is actually different from its outline`() {
		for (name in AppIcons.withFilledVariant) {
			val outlined = loader.getResourceAsStream(AppIcons.filePath(name))!!.use { it.readBytes() }
			val filledPath = AppIcons.filePath(name, filled = true)
			assertTrue("$name claims a filled variant but the path is unchanged", filledPath.endsWith("_fill.svg"))
			val filled = loader.getResourceAsStream(filledPath)
			assertNotNull("no filled variant vendored for $name", filled)
			assertTrue(
				"$name's filled variant is byte-identical to its outline, so the selected " +
					"state cannot render; it should not be in withFilledVariant",
				!outlined.contentEquals(filled!!.use { it.readBytes() }),
			)
		}
	}

	/**
	 * Material publishes a FILL 1 variant for every symbol, but for glyphs that are all
	 * stroke it is the same drawing. Five of the eleven rail icons are like that, which
	 * is why the selected state rests on the indicator's stroke and not on the icon.
	 * Asking for a filled variant that was not vendored must fall back, not break.
	 */
	@Test
	fun `asking for a filled variant that does not exist falls back to the outline`() {
		val strokeOnly = AppIcons.SOURCES
		assertTrue("$strokeOnly should have no filled variant", strokeOnly !in AppIcons.withFilledVariant)
		assertEquals(AppIcons.filePath(strokeOnly), AppIcons.filePath(strokeOnly, filled = true))
		assertNotNull(loader.getResource(AppIcons.filePath(strokeOnly, filled = true)))
	}

	@Test
	fun `no two destinations share an icon, which is the collision being fixed`() {
		// Three unicode glyphs each did duty for two or three destinations: U+2913 was
		// Local, Import and Backup; U+2605 was Library, Bookmarks and the reader's saved
		// state; U+25A6 was Sources and Statistics.
		val rail = listOf(
			AppIcons.LIBRARY, AppIcons.SOURCES, AppIcons.HISTORY, AppIcons.SETTINGS,
			AppIcons.LOCAL, AppIcons.DISCOVER, AppIcons.SUGGESTIONS, AppIcons.DOWNLOADS,
			AppIcons.BOOKMARKS, AppIcons.UPDATES, AppIcons.APP_UPDATE,
		)
		val tools = listOf(
			AppIcons.BACKUP, AppIcons.ORGANISE, AppIcons.IMPORT, AppIcons.FIX_SOURCES,
			AppIcons.READER_PREFS, AppIcons.STATS, AppIcons.TRACKING,
		)
		val destinations = rail + tools
		assertEquals(
			"two destinations share an icon: " +
				destinations.groupBy { it }.filterValues { it.size > 1 }.keys,
			destinations.size,
			destinations.toSet().size,
		)
	}

	@Test
	fun `nothing is vendored that nothing names`() {
		// Keeps the directory from silently accumulating files nobody draws.
		val vendored = loader.getResource("icons")?.let {
			java.io.File(it.toURI()).list().orEmpty().toList()
		}.orEmpty()
		val svgs = vendored.filter { it.endsWith(".svg") }
		val expected = AppIcons.all.map { "ic_$it.svg" } +
			AppIcons.withFilledVariant.map { "ic_${it}_fill.svg" }
		assertEquals(
			"vendored but unnamed: ${svgs - expected.toSet()}",
			emptyList<String>(),
			svgs - expected.toSet(),
		)
	}
}
