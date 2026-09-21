package org.koitharu.kotatsu.shared.settings

import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSettingsStoreTest {

	private fun tempDir() = Files.createTempDirectory("settings").toFile().toOkioPath()

	@Test
	fun `defaults apply when no file exists`() {
		val store = JsonSettingsStore(tempDir() / "settings.json")
		assertEquals(ThemeMode.Dark, store.data.value.theme)
		assertEquals(60, store.data.value.webtoonWidthPercent)
		assertTrue(store.data.value.hideAdultSources)
	}

	@Test
	fun `changes persist across a restart`() = runBlocking {
		val path = tempDir() / "settings.json"
		val first = JsonSettingsStore(path)
		first.update { it.copy(theme = ThemeMode.Dark, webtoonWidthPercent = 35) }

		// A separate instance reading the same file is the restart.
		val second = JsonSettingsStore(path)
		assertEquals(ThemeMode.Dark, second.data.value.theme)
		assertEquals(35, second.data.value.webtoonWidthPercent)
	}

	@Test
	fun `a corrupt file falls back to defaults instead of failing to start`() {
		val path = tempDir() / "settings.json"
		FileSystem.SYSTEM.write(path) { writeUtf8("{ this is not json") }
		val store = JsonSettingsStore(path)
		assertEquals(SettingsData(), store.data.value)
	}

	@Test
	fun `an unknown key written by a newer build is ignored`() {
		val path = tempDir() / "settings.json"
		FileSystem.SYSTEM.write(path) {
			writeUtf8("""{"theme":"Dark","some_future_key":42}""")
		}
		val store = JsonSettingsStore(path)
		assertEquals(ThemeMode.Dark, store.data.value.theme)
	}

	@Test
	fun `a missing key keeps its default`() {
		val path = tempDir() / "settings.json"
		FileSystem.SYSTEM.write(path) { writeUtf8("""{"theme":"Light"}""") }
		val store = JsonSettingsStore(path)
		assertEquals(ThemeMode.Light, store.data.value.theme)
		assertEquals(SettingsData().userAgent, store.data.value.userAgent)
	}

	@Test
	fun `reset restores defaults`() = runBlocking {
		val path = tempDir() / "settings.json"
		val store = JsonSettingsStore(path)
		store.update { it.copy(theme = ThemeMode.Dark, imageCacheEntries = 12) }
		store.reset()
		assertEquals(SettingsData(), store.data.value)
		assertEquals(SettingsData(), JsonSettingsStore(path).data.value)
	}

	@Test
	fun `no temp file is left behind after a write`() = runBlocking {
		val dir = tempDir()
		val path = dir / "settings.json"
		JsonSettingsStore(path).update { it.copy(theme = ThemeMode.Dark) }
		val leftovers: List<okio.Path> = FileSystem.SYSTEM.list(dir)
			.filter { path -> path.name.endsWith(".tmp") }
		assertTrue(leftovers.isEmpty(), "temp files left: $leftovers")
		Unit
	}
}
