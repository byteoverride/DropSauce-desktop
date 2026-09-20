package org.koitharu.kotatsu.shared.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XdgAppPathsTest {

	private fun paths(vararg env: Pair<String, String>) = XdgAppPaths(
		appDirName = "dropsauce",
		env = { name -> env.toMap()[name] },
		userHome = "/home/tester",
	)

	@Test
	fun `falls back to the specified home-relative defaults when unset`() {
		val p = paths()
		assertEquals("/home/tester/.local/share/dropsauce", p.data.toString())
		assertEquals("/home/tester/.cache/dropsauce", p.cache.toString())
		assertEquals("/home/tester/.config/dropsauce", p.config.toString())
	}

	@Test
	fun `honours absolute XDG overrides`() {
		val p = paths(
			"XDG_DATA_HOME" to "/srv/data",
			"XDG_CACHE_HOME" to "/var/tmp/cache",
			"XDG_CONFIG_HOME" to "/etc/xdg-user",
		)
		assertEquals("/srv/data/dropsauce", p.data.toString())
		assertEquals("/var/tmp/cache/dropsauce", p.cache.toString())
		assertEquals("/etc/xdg-user/dropsauce", p.config.toString())
	}

	@Test
	fun `ignores a relative XDG value, as the specification requires`() {
		val p = paths("XDG_DATA_HOME" to "relative/data")
		assertEquals("/home/tester/.local/share/dropsauce", p.data.toString())
	}

	@Test
	fun `ignores an empty XDG value`() {
		val p = paths("XDG_CONFIG_HOME" to "")
		assertEquals("/home/tester/.config/dropsauce", p.config.toString())
	}

	@Test
	fun `local library sits under the data directory`() {
		val p = paths("XDG_DATA_HOME" to "/srv/data")
		assertEquals("/srv/data/dropsauce/library", p.localLibrary.toString())
	}

	@Test
	fun `all paths are absolute`() {
		val p = paths()
		assertTrue(listOf(p.data, p.cache, p.config, p.localLibrary).all { it.isAbsolute })
	}
}
