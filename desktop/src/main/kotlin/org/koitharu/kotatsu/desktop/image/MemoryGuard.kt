package org.koitharu.kotatsu.desktop.image

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Whether there is room to decode an image before the decode is attempted.
 *
 * The Android app asks this before every page (`Context.ensureRamAtLeast`, called from
 * `PageLoader`) and throws a catchable `IllegalStateException` when the answer is no. The
 * desktop port carried the decoder across and left the check behind, so the same
 * condition arrives as a failed native allocation instead. That is not a Java exception,
 * nothing catches it, and a packaged Windows app has no console to print it to: the
 * process disappears. A webtoon page is one indivisible allocation of around 43 MB, so
 * this is the ordinary case rather than an edge.
 *
 * Refusing is not a fix. It converts a silent death into a failure the reader can show
 * and retry, which is the difference between a bug report and a blank page.
 */
class MemoryGuard(
	/** Injected for tests, and null whenever the platform will not say. */
	private val freeBytes: () -> Long? = ::availableBytes,
) {

	/**
	 * Doubles [bytes] the way Android does: the decoder needs the destination buffer and
	 * working space beside it. [HEADROOM] on top, because a machine left with exactly
	 * enough for one page has nothing to spend on the rest of the application.
	 *
	 * Unknown free memory answers true. A guard that cannot measure must not be the thing
	 * that stops the reader working.
	 */
	fun canDecode(bytes: Long): Boolean {
		val free = freeBytes() ?: return true
		return free >= bytes * 2 + HEADROOM
	}

	companion object {

		/** Room for the window, the JVM and whatever else the machine is doing. */
		const val HEADROOM = 128L * 1024 * 1024

		private const val BYTES_PER_PIXEL = 4L

		/**
		 * What [bytes] will occupy once decoded, read from the header alone.
		 *
		 * `Codec` parses metadata without allocating pixels, which is the only way to
		 * price a decode before paying for it. Null when the header cannot be read, which
		 * covers a format Skia has no decoder for; the decode itself is about to report
		 * that properly, and guessing here would turn an unsupported format into a
		 * memory complaint.
		 */
		fun decodedBytesOrNull(bytes: ByteArray): Long? = try {
			Data.makeFromBytes(bytes).use { data ->
				Codec.makeFromData(data).use { codec ->
					codec.width.toLong() * codec.height.toLong() * BYTES_PER_PIXEL
				}
			}
		} catch (e: Throwable) {
			null
		}

		/**
		 * Free memory as the operating system reports it, or null if it will not say.
		 *
		 * `MemAvailable` on Linux rather than the JVM's figure, because the bean reports
		 * `MemFree`, which excludes reclaimable page cache. Reading a few large files is
		 * enough to drive `MemFree` near zero on an otherwise idle machine, and a guard
		 * built on it would refuse pages to a reader that had plenty of room. Windows
		 * draws no such distinction and the bean is the right answer there.
		 */
		fun availableBytes(): Long? = linuxMemAvailable() ?: beanFreeBytes()

		private fun linuxMemAvailable(): Long? = try {
			File("/proc/meminfo").takeIf { it.canRead() }?.useLines { lines ->
				lines.firstOrNull { it.startsWith("MemAvailable:") }
					?.filter(Char::isDigit)
					?.toLongOrNull()
					?.times(1024)
			}
		} catch (e: Exception) {
			null
		}

		private fun beanFreeBytes(): Long? = try {
			val bean = ManagementFactory.getOperatingSystemMXBean()
			(bean as? com.sun.management.OperatingSystemMXBean)?.freeMemorySize
		} catch (e: Throwable) {
			// jdk.management is an optional module and a trimmed runtime image need not
			// carry it. Unknown means do not stand in the way.
			null
		}
	}
}
