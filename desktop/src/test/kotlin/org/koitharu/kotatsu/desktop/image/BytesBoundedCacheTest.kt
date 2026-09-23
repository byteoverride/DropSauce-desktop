package org.koitharu.kotatsu.desktop.image

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule both image caches now share.
 *
 * It exists because the same defect was written twice: a limit counting entries, holding
 * things that differ in size by a factor of ten. `ImageCache` was fixed and
 * `LocalImages` was not, and eight local webtoon pages is 344 MB.
 */
class BytesBoundedCacheTest {

	@Test
	fun `a budget in bytes holds fewer large things than small ones`() {
		val big = BytesBoundedCache(maxBytes = 64L * 1024 * 1024)
		repeat(6) { big.put("big$it", bitmap(2000, 2000)) } // 16 MB each
		assertTrue(big.heldCount() <= 4, "kept ${big.heldCount()} of six 16 MB entries in 64 MB")

		val small = BytesBoundedCache(maxBytes = 64L * 1024 * 1024)
		repeat(20) { small.put("small$it", bitmap(100, 100)) } // 40 KB each
		assertEquals(20, small.heldCount())
	}

	@Test
	fun `the running total tracks what is actually held`() {
		val cache = BytesBoundedCache(maxBytes = 1024L * 1024 * 1024)
		cache.put("a", bitmap(1000, 1000))
		cache.put("b", bitmap(1000, 1000))
		assertEquals(8_000_000L, cache.heldBytes())

		cache.remove("a")
		assertEquals(4_000_000L, cache.heldBytes())

		// Replacing a key must not count the old value twice.
		cache.put("b", bitmap(500, 500))
		assertEquals(1_000_000L, cache.heldBytes())
	}

	// One entry can be bigger than several it displaces, so eviction has to walk the
	// overshoot off. removeEldestEntry drops exactly one per insert, which is what makes
	// a LinkedHashMap the wrong tool for this on its own.
	@Test
	fun `one large entry evicts as many small ones as it takes`() {
		val cache = BytesBoundedCache(maxBytes = 5_000_000)
		repeat(10) { cache.put("small$it", bitmap(500, 500)) } // 1 MB each
		assertEquals(5, cache.heldCount())

		cache.put("huge", bitmap(1000, 1000)) // 4 MB, needs four of them gone

		assertTrue(cache.heldBytes() <= 5_000_000, "held ${cache.heldBytes()} against 5,000,000")
		assertNotNull(cache.get("huge"))
	}

	@Test
	fun `something larger than the whole budget is still kept`() {
		val cache = BytesBoundedCache(maxBytes = 1)
		cache.put("only", bitmap(800, 800))
		assertNotNull(cache.get("only"), "the thing about to be drawn was thrown away")
		assertEquals(1, cache.heldCount())
	}

	@Test
	fun `eviction drops what was looked at longest ago`() {
		val cache = BytesBoundedCache(maxBytes = 3_000_000)
		cache.put("a", bitmap(500, 500))
		cache.put("b", bitmap(500, 500))
		cache.put("c", bitmap(500, 500))
		cache.get("a") // touching a makes b the eldest
		cache.put("d", bitmap(500, 500))

		assertNotNull(cache.get("a"))
		assertNull(cache.get("b"))
	}

	@Test
	fun `the entry count still caps how many are tracked`() {
		val cache = BytesBoundedCache(maxBytes = 1024L * 1024 * 1024, maxEntries = 3)
		repeat(8) { cache.put("k$it", bitmap(50, 50)) }
		assertEquals(3, cache.heldCount())
	}

	@Test
	fun `lowering the budget evicts down to it immediately`() {
		val cache = BytesBoundedCache(maxBytes = 64L * 1024 * 1024)
		repeat(6) { cache.put("k$it", bitmap(2000, 2000)) }
		val before = cache.heldCount()

		cache.maxBytes = 8L * 1024 * 1024

		assertTrue(cache.heldCount() < before, "still holding ${cache.heldCount()}")
		assertTrue(cache.heldBytes() <= 16L * 1024 * 1024)
	}

	// Forgetting a re-imported comic drops its pages without touching anything else.
	@Test
	fun `a subset can be dropped by key`() {
		val cache = BytesBoundedCache(maxBytes = 1024L * 1024 * 1024)
		cache.put("book1/page1", bitmap(100, 100))
		cache.put("book1/page2", bitmap(100, 100))
		cache.put("book2/page1", bitmap(100, 100))

		cache.removeIf { it.startsWith("book1/") }

		assertEquals(1, cache.heldCount())
		assertNotNull(cache.get("book2/page1"))
		assertEquals(40_000L, cache.heldBytes())
	}

	private fun bitmap(width: Int, height: Int): ImageBitmap = ImageBitmap(width, height)
}
