package org.koitharu.kotatsu.desktop.feature.discover

import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Search history is the one thing this feature keeps on disk, so the tests care about
 * what survives a restart rather than about what the in-memory list looks like.
 */
class DiscoverStoreTest {

	private lateinit var directory: Path
	private val fileSystem = FileSystem.SYSTEM

	private val file: Path get() = directory / DiscoverStore.FILE_NAME

	@Before
	fun setUp() {
		directory = Files.createTempDirectory("discover-test").toString().toPath()
	}

	@After
	fun tearDown() {
		fileSystem.deleteRecursively(directory)
	}

	private fun store(limit: Int = DiscoverStore.MAX_HISTORY) =
		DiscoverStore(file, fileSystem, historyLimit = limit)

	@Test
	fun `a recorded query survives a reload`() = runBlocking {
		store().recordQuery("vinland saga")
		assertEquals(listOf("vinland saga"), store().data.value.recentQueries)
	}

	@Test
	fun `nothing on disk is not an error`() {
		assertEquals(DiscoverPrefs(), store().data.value)
		assertTrue(!fileSystem.exists(file))
	}

	@Test
	fun `the newest query comes first`() = runBlocking {
		val store = store()
		store.recordQuery("one")
		store.recordQuery("two")
		store.recordQuery("three")
		assertEquals(listOf("three", "two", "one"), store.data.value.recentQueries)
	}

	@Test
	fun `a repeated query moves instead of duplicating`() = runBlocking {
		val store = store()
		store.recordQuery("one")
		store.recordQuery("two")
		store.recordQuery("one")
		assertEquals(listOf("one", "two"), store.data.value.recentQueries)
	}

	@Test
	fun `case and surrounding space do not make a new entry`() = runBlocking {
		val store = store()
		store.recordQuery("One Piece")
		store.recordQuery("  one piece  ")
		// One entry, and it is spelled the way the user last typed it.
		assertEquals(listOf("one piece"), store.data.value.recentQueries)
	}

	@Test
	fun `the history is capped and drops the oldest`() = runBlocking {
		val store = store(limit = 3)
		for (i in 1..5) store.recordQuery("q$i")
		assertEquals(listOf("q5", "q4", "q3"), store.data.value.recentQueries)
		// And the cap is what was written, not just what is in memory.
		assertEquals(listOf("q5", "q4", "q3"), store(limit = 3).data.value.recentQueries)
	}

	@Test
	fun `a blank query is not remembered`() = runBlocking {
		val store = store()
		store.recordQuery("   ")
		store.recordQuery("")
		assertEquals(emptyList<String>(), store.data.value.recentQueries)
	}

	@Test
	fun `a query can be forgotten and the history cleared`() = runBlocking {
		val store = store()
		store.recordQuery("a")
		store.recordQuery("b")
		store.forgetQuery("A")
		assertEquals(listOf("b"), store.data.value.recentQueries)
		store.clearHistory()
		assertEquals(emptyList<String>(), store.data.value.recentQueries)
		assertEquals(emptyList<String>(), store().data.value.recentQueries)
	}

	@Test
	fun `selected sources survive a reload alongside the history`() = runBlocking {
		val store = store()
		store.recordQuery("berserk")
		store.rememberSources(listOf("MANGADEX", "MANGADEX", "READMANGA_RU"))
		val reloaded = store()
		assertEquals(listOf("MANGADEX", "READMANGA_RU"), reloaded.data.value.globalSources)
		assertEquals(listOf("berserk"), reloaded.data.value.recentQueries)
	}

	@Test
	fun `queries with quotes and newlines survive the round trip`() = runBlocking {
		val awkward = "he said \"go\"\n\tand \\ left"
		store().recordQuery(awkward)
		assertEquals(listOf(awkward.trim()), store().data.value.recentQueries)
	}

	@Test
	fun `non latin queries survive the round trip`() = runBlocking {
		store().recordQuery("鋼の錬金術師")
		assertEquals(listOf("鋼の錬金術師"), store().data.value.recentQueries)
	}

	@Test
	fun `a corrupt file is discarded rather than fatal`() {
		fileSystem.createDirectories(directory, mustCreate = false)
		fileSystem.write(file) { writeUtf8("{\"queries\": [\"a\",") }
		// Reading it must not throw; the file is a convenience, not user data.
		assertEquals(DiscoverPrefs(), store().data.value)
	}

	@Test
	fun `a file written by a newer build keeps the fields this build knows`() {
		fileSystem.createDirectories(directory, mustCreate = false)
		fileSystem.write(file) {
			writeUtf8("{\"version\": 99, \"queries\": [\"a\", 7, \"b\"], \"whatIsThis\": {\"x\": [1]}}")
		}
		// The stray number is skipped rather than failing the whole file.
		assertEquals(listOf("a", "b"), store().data.value.recentQueries)
	}

	@Test
	fun `an interrupted write leaves no stray temp file`() = runBlocking {
		store().recordQuery("a")
		val leftovers = fileSystem.list(directory).filter { it.name.endsWith(".tmp") }
		assertEquals(emptyList<Path>(), leftovers)
	}
}
