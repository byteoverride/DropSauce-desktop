package org.koitharu.kotatsu.shared.io

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a Windows install keeps its things.
 *
 * Written without a Windows machine, which is the point of taking the environment as a
 * parameter. The bug these exist for is silent: `XdgAppPaths` does not fail on Windows,
 * it cheerfully falls back to `~/.local/share` and puts the library somewhere no Windows
 * user would look for it or think to back up.
 */
class WindowsAppPathsTest {

	private fun paths(vararg env: Pair<String, String>) = WindowsAppPaths(
		env = env.toMap()::get,
		userHome = HOME,
	)

	@Test
	fun `data and config sit in roaming, cache in local`() {
		val p = paths("APPDATA" to ROAMING, "LOCALAPPDATA" to LOCAL)
		assertEquals("$ROAMING/DropSauce".toPath(), p.data)
		assertEquals("$ROAMING/DropSauce".toPath(), p.config)
		assertEquals("$LOCAL/DropSauce/cache".toPath(), p.cache)
		assertEquals("$ROAMING/DropSauce/library".toPath(), p.localLibrary)
	}

	// Roaming follows a user between machines on a domain and a cache should not, which
	// is the entire reason Windows has two of these.
	@Test
	fun `a cache is never put somewhere that roams`() {
		val p = paths("APPDATA" to ROAMING, "LOCALAPPDATA" to LOCAL)
		assertTrue(p.cache.toString().startsWith(LOCAL))
		assertTrue(!p.cache.toString().startsWith(ROAMING))
	}

	@Test
	fun `an unset APPDATA falls back to where it conventionally points`() {
		val p = paths()
		assertEquals("$HOME/AppData/Roaming/DropSauce".toPath(), p.data)
		assertEquals("$HOME/AppData/Local/DropSauce/cache".toPath(), p.cache)
	}

	// The same rule XDG imposes, for the same reason: a relative value would resolve
	// against the working directory, which for a packaged app is wherever the shortcut
	// started it.
	@Test
	fun `a relative value is ignored rather than resolved`() {
		val p = paths("APPDATA" to "AppData\\Roaming", "LOCALAPPDATA" to "")
		assertEquals("$HOME/AppData/Roaming/DropSauce".toPath(), p.data)
	}

	@Test
	fun `a machine with roaming but no local keeps the cache out of the home directory`() {
		val p = paths("APPDATA" to ROAMING)
		assertTrue(
			p.cache.toString().startsWith(ROAMING),
			"untidy is the right failure here; the home directory is the wrong one",
		)
	}

	@Test
	fun `nothing collides inside the shared roaming folder`() {
		val p = paths("APPDATA" to ROAMING, "LOCALAPPDATA" to LOCAL)
		val files = listOf(p.data / "library.db", p.config / "settings.json", p.localLibrary)
		assertEquals(files.size, files.toSet().size)
	}

	private companion object {

		const val HOME = "/C:/Users/reader"
		const val ROAMING = "/C:/Users/reader/AppData/Roaming"
		const val LOCAL = "/C:/Users/reader/AppData/Local"
	}
}

/**
 * Picking an implementation for the machine, and not stranding a library that already
 * exists somewhere else.
 */
class DefaultAppPathsTest {

	private val root = Files.createTempDirectory("app-paths").toFile().also { it.deleteOnExit() }

	/** A layout rooted in a temp directory, so nothing here can see a real library. */
	private fun at(name: String): AppPaths = object : AppPaths {
		override val data: Path = root.toOkioPath() / name
		override val cache: Path = root.toOkioPath() / name / "cache"
		override val config: Path = root.toOkioPath() / name
		override val localLibrary: Path = root.toOkioPath() / name / "library"
	}

	private fun withDatabase(paths: AppPaths): AppPaths = paths.also {
		FileSystem.SYSTEM.createDirectories(it.data)
		FileSystem.SYSTEM.write(it.data / "library.db") { writeUtf8("not really a database") }
	}

	@Test
	fun `anything that is not Windows keeps the XDG layout`() {
		val legacy = at("legacy")
		assertEquals(legacy, defaultAppPaths(osName = "Linux", windows = at("win"), legacy = legacy))
		assertEquals(legacy, defaultAppPaths(osName = "Mac OS X", windows = at("win"), legacy = legacy))
		assertEquals(legacy, defaultAppPaths(osName = "", windows = at("win"), legacy = legacy))
	}

	@Test
	fun `the real thing picks by the real os name`() {
		// A control: the defaults have to resolve, or every test above proves only that
		// the parameters work.
		assertTrue(defaultAppPaths(osName = "Linux") is XdgAppPaths)
		assertTrue(defaultAppPaths(osName = "Windows 11", legacy = at("nothing-here")) is WindowsAppPaths)
	}

	@Test
	fun `a fresh Windows machine gets the Windows layout`() {
		val windows = at("win")
		assertEquals(windows, defaultAppPaths(osName = "Windows 11", windows = windows, legacy = at("legacy")))
	}

	// The Windows build shipped before this existed and wrote to the XDG fallback. Someone
	// who installed that must not open the app to an empty library sitting next to a
	// perfectly good database.
	@Test
	fun `an existing library in the old place is still used`() {
		val legacy = withDatabase(at("legacy"))
		val chosen = defaultAppPaths(osName = "Windows 11", windows = at("win"), legacy = legacy)
		assertEquals(legacy.data, chosen.data, "a library already on disk was abandoned")
	}

	@Test
	fun `the proper place wins once there is a library in it`() {
		val windows = withDatabase(at("win"))
		val legacy = withDatabase(at("legacy"))
		val chosen = defaultAppPaths(osName = "Windows 11", windows = windows, legacy = legacy)
		assertEquals(windows.data, chosen.data)
	}
}
