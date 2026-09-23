package org.koitharu.kotatsu.desktop.image

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

	private fun jpeg(width: Int, height: Int): ByteArray {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
	}
}
