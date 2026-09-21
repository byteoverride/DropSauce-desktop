package org.koitharu.kotatsu.desktop.feature.suggestions

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path as JavaPath

/**
 * Dismissals and the tag cache, against real files.
 *
 * Real files rather than a fake filesystem: the properties under test are that a value
 * survives a restart and that the file cannot grow without bound, and neither of those
 * means anything against an in-memory map.
 */
class SuggestionStoreTest {

	private lateinit var dir: JavaPath

	@Before
	fun setUp() {
		dir = Files.createTempDirectory("suggestions")
	}

	@After
	fun tearDown() {
		dir.toFile().deleteRecursively()
	}

	private fun store(limit: Int = SuggestionStore.MAX_DISMISSED) =
		SuggestionStore(dir.resolve(SuggestionStore.FILE_NAME).toOkioPath(), limit = limit)

	private fun tagCache(limit: Int = TagCache.MAX_ENTRIES) =
		TagCache(dir.resolve(TagCache.FILE_NAME).toOkioPath(), limit = limit, now = { 42L })

	@Test
	fun `a dismissal survives a restart`() = runBlocking {
		store().dismiss(77L)
		// A second instance over the same path is exactly what a restart is.
		assertEquals(setOf(77L), store().dismissed)
	}

	@Test
	fun `an empty store reads as empty rather than failing`() {
		assertTrue(store().dismissed.isEmpty())
	}

	@Test
	fun `dismissing the same title twice does not duplicate it`() = runBlocking {
		val store = store()
		store.dismiss(77L)
		store.dismiss(77L)
		assertEquals(listOf(77L), store.data.value.dismissed)
	}

	@Test
	fun `dismissals are capped so the file cannot grow without bound`() = runBlocking {
		val store = store(limit = 10)
		for (id in 1L..50L) store.dismiss(id)
		val kept = store.data.value.dismissed
		assertEquals(10, kept.size)
		assertEquals("newest first, oldest evicted", (50L downTo 41L).toList(), kept)
		// And the cap holds across a restart, not only in memory.
		assertEquals(10, store(limit = 10).dismissed.size)
	}

	@Test
	fun `a cap lowered between runs is applied on read`() = runBlocking {
		val wide = store(limit = 50)
		for (id in 1L..40L) wide.dismiss(id)
		assertEquals(5, store(limit = 5).dismissed.size)
	}

	@Test
	fun `a dismissal keeps a title out of a later refresh`() = runBlocking {
		val store = store()
		store.dismiss(100L)

		val profile = buildProfile(
			listOf(
				TasteSignal(1L, "Read", "ALPHA", setOf("Action"), "MANGA", false, 0.9f),
			),
		)
		val candidates = listOf(100L, 101L).map { id ->
			Candidate(id, "Candidate $id", "ALPHA", "Alpha", listOf("Action"), "MANGA", 0f)
		}
		val last = SuggestionEngine().refresh(
			profile = profile,
			sources = listOf(CandidateSource("ALPHA", "Alpha") { candidates }),
			dismissed = store.dismissed,
		).toList().last()

		assertEquals(listOf(101L), last.suggestions.map { it.candidate.mangaId })
		assertFalse(
			"a dismissed title must not come back",
			last.suggestions.any { it.candidate.mangaId == 100L },
		)
	}

	@Test
	fun `restoring a dismissal lets the title through again`() = runBlocking {
		val store = store()
		store.dismiss(100L)
		store.restore(100L)
		assertTrue(store.dismissed.isEmpty())
		assertTrue(store().dismissed.isEmpty())
	}

	@Test
	fun `an unreadable file is discarded rather than fatal`() {
		val file = dir.resolve(SuggestionStore.FILE_NAME)
		Files.writeString(file, "{ this is not json")
		// The point is that constructing the store at all does not throw: a corrupt
		// preferences file must not stop the screen opening.
		assertTrue(store().dismissed.isEmpty())
	}

	@Test
	fun `a file from a later version keeps the keys it understands`() {
		val file = dir.resolve(SuggestionStore.FILE_NAME)
		Files.writeString(file, """{"version":99,"dismissed":[5,6],"somethingNew":true}""")
		assertEquals(setOf(5L, 6L), store().dismissed)
	}

	@Test
	fun `tags survive a restart`() = runBlocking {
		tagCache().put(1L, listOf("Action", "Fantasy"))
		assertEquals(listOf("Action", "Fantasy"), tagCache()[1L])
	}

	@Test
	fun `a title known to have no tags is remembered as such, not as unknown`() = runBlocking {
		tagCache().put(1L, emptyList())
		assertEquals(emptyList<String>(), tagCache()[1L])
	}

	@Test
	fun `the tag cache is capped and evicts the least recently written`() = runBlocking {
		val cache = tagCache(limit = 5)
		for (id in 1L..20L) cache.put(id, listOf("Tag$id"))
		assertEquals(null, cache[1L])
		assertEquals(listOf("Tag20"), cache[20L])
		val reopened = tagCache(limit = 5)
		assertEquals(5, (1L..20L).count { reopened[it] != null })
	}
}
