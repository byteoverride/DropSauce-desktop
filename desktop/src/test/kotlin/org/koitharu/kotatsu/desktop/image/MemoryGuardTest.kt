package org.koitharu.kotatsu.desktop.image

import org.koitharu.kotatsu.desktop.feature.local.LocalImages
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The check the Android app makes before every decode and the desktop port did not.
 *
 * Without it a page too large for the machine is a failed native allocation, which is not
 * an exception, cannot be caught, and takes the process with it.
 */
class MemoryGuardTest {

	@Test
	fun `a decode that fits is allowed`() {
		val guard = MemoryGuard(freeBytes = { 2_000L * 1024 * 1024 })
		assertTrue(guard.canDecode(43L * 1024 * 1024))
	}

	@Test
	fun `a decode that does not fit is refused`() {
		val guard = MemoryGuard(freeBytes = { 100L * 1024 * 1024 })
		assertFalse(guard.canDecode(43L * 1024 * 1024), "43 MB doubled plus headroom does not fit in 100")
	}

	// Doubling matters: a page that fits exactly once does not fit while it is being
	// produced, because the decoder needs its working space at the same time.
	@Test
	fun `room for exactly one copy is not room enough`() {
		val page = 43L * 1024 * 1024
		val guard = MemoryGuard(freeBytes = { page + MemoryGuard.HEADROOM })
		assertFalse(guard.canDecode(page))

		val roomier = MemoryGuard(freeBytes = { page * 2 + MemoryGuard.HEADROOM })
		assertTrue(roomier.canDecode(page))
	}

	// A guard that cannot measure must not become the thing that stops the reader.
	@Test
	fun `an unknown amount of free memory allows the decode`() {
		val guard = MemoryGuard(freeBytes = { null })
		assertTrue(guard.canDecode(Long.MAX_VALUE / 4))
	}

	@Test
	fun `this machine reports its free memory`() {
		// Not an assertion about the number, which is whatever the machine is doing. It
		// asserts the platform answers at all, because every other test here injects a
		// fake and would pass just as well if the real probe returned null always.
		val free = MemoryGuard.availableBytes()
		assertNotNull(free, "neither /proc/meminfo nor the JDK bean would say")
		assertTrue(free > 0, "reported $free bytes free")
	}

	@Test
	fun `a decode is priced from the header without decoding it`() {
		val tall = jpeg(900, 12000)
		assertEquals(900L * 12000 * 4, MemoryGuard.decodedBytesOrNull(tall))
	}

	// An unsupported payload is not a memory question, and pretending to price it would
	// turn "this format is not supported" into "not enough memory".
	@Test
	fun `something with no readable header has no price`() {
		assertNull(MemoryGuard.decodedBytesOrNull("<html>not an image</html>".toByteArray()))
		assertNull(MemoryGuard.decodedBytesOrNull(ByteArray(0)))
	}

	// The defect this replaced: the budget was a fraction of the heap, but the pixels it
	// bounds live outside the heap, so raising -Xmx raised the native allowance too and
	// the two added instead of trading off.
	@Test
	fun `a larger heap leaves less room for images, not more`() {
		val machine = 4L * GB
		val small = ImageCache.defaultBudget(physicalBytes = machine, heapBytes = 512 * MB)
		val large = ImageCache.defaultBudget(physicalBytes = machine, heapBytes = 3 * GB)

		assertTrue(large < small, "a 3 GB heap was given $large against $small for a 512 MB heap")
	}

	@Test
	fun `a bigger machine is allowed more, up to the ceiling`() {
		val heap = 512 * MB
		val tiny = ImageCache.defaultBudget(physicalBytes = 1 * GB, heapBytes = heap)
		val big = ImageCache.defaultBudget(physicalBytes = 8 * GB, heapBytes = heap)

		assertTrue(tiny < big, "1 GB machine got $tiny, 8 GB machine got $big")
		assertEquals(ImageCache.MAXIMUM_BUDGET, big, "a large machine should reach the ceiling")
	}

	// The shape reported on the Windows VM: a 1 GB heap on a small machine. The old
	// formula handed it 256 MB of native pixels on top of the heap.
	@Test
	fun `the reported windows shape is given less than it used to be`() {
		val old = (1 * GB) / 4
		val now = ImageCache.defaultBudget(physicalBytes = 2 * GB, heapBytes = 1 * GB)

		assertTrue(now < old, "still allowing $now where the old formula allowed $old")
	}

	// A heap configured larger than the machine leaves nothing over. The floor holds,
	// because a cache of zero would decode the same page for every frame.
	@Test
	fun `an oversized heap falls back to the floor rather than to nothing`() {
		val budget = ImageCache.defaultBudget(physicalBytes = 1 * GB, heapBytes = 2 * GB)
		assertEquals(ImageCache.MINIMUM_BUDGET, budget)
		assertNull(MemoryGuard.spareForImages(physicalBytes = null))
		assertEquals(0L, MemoryGuard.spareForImages(physicalBytes = 1 * GB, heapBytes = 2 * GB))
	}

	@Test
	fun `a machine that will not report its size still gets a usable budget`() {
		val budget = ImageCache.defaultBudget(physicalBytes = null, heapBytes = 2 * GB)
		assertTrue(budget in ImageCache.MINIMUM_BUDGET..ImageCache.MAXIMUM_BUDGET, "got $budget")
	}

	@Test
	fun `this machine reports its total size`() {
		val total = MemoryGuard.totalBytes()
		assertNotNull(total, "neither /proc/meminfo nor the JDK bean would say")
		val free = MemoryGuard.availableBytes() ?: 0L
		assertTrue(total >= free, "reported $total total against $free available")
	}

	// The whole point is the machines that were crashing, so the change must not hand
	// those more than they already had. A quarter of the remainder did exactly that and
	// was the reason for the eighth.
	@Test
	fun `a constrained machine is given no more than the old formula gave it`() {
		for ((physical, heap) in listOf(
			1 * GB to 256 * MB,
			2 * GB to 1 * GB,
			1 * GB to 2 * GB,
			8 * GB to 2 * GB,
			16 * GB to 4 * GB,
		)) {
			val old = (heap / 4).coerceIn(ImageCache.MINIMUM_BUDGET, 1024 * MB)
			val now = ImageCache.defaultBudget(physicalBytes = physical, heapBytes = heap)
			assertTrue(now <= old, "${physical / MB}MB machine with a ${heap / MB}MB heap went $old to $now")
		}
	}

	// The local-comics caches are a second claim on the same memory, so they move with
	// it. The fractions are chosen to land on the previous fixed numbers at the ceiling.
	@Test
	fun `local budgets track the remote one and match the old numbers at the ceiling`() {
		assertEquals(96 * MB, LocalImages.pageBudget(ImageCache.MAXIMUM_BUDGET))
		assertEquals(64 * MB, LocalImages.coverBudget(ImageCache.MAXIMUM_BUDGET))

		// On a machine where the remote budget is at its floor, the local caches must not
		// be the larger of the two.
		val floor = ImageCache.MINIMUM_BUDGET
		assertTrue(LocalImages.pageBudget(floor) + LocalImages.coverBudget(floor) <= floor)
	}

	private companion object {

		const val MB = 1024L * 1024

		const val GB = 1024L * 1024 * 1024
	}

	private fun jpeg(width: Int, height: Int): ByteArray {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
	}
}
