package org.koitharu.kotatsu.desktop.image

import androidx.compose.ui.graphics.ImageBitmap

/**
 * An LRU of decoded images bounded by how much memory they occupy.
 *
 * Counting entries does not bound memory, because the entries are not comparable. A cover
 * decodes to about 4 MB and a webtoon page to as much as 43, so the same limit of eight
 * is 32 MB of one and 344 MB of the other. A machine with room for the first dies on the
 * second, and it dies without a Java stack trace, because these pixels are allocated
 * natively and a failed native allocation is not an exception.
 *
 * Shared by [ImageCache] and the local-comics cache so the rule is written once. The two
 * had the same bug and only one of them was fixed.
 */
class BytesBoundedCache(maxBytes: Long, maxEntries: Int = Int.MAX_VALUE) {

	@Volatile
	var maxBytes: Long = maxBytes
		set(value) {
			field = value.coerceAtLeast(1)
			synchronized(lock) { evictLocked() }
		}

	@Volatile
	var maxEntries: Int = maxEntries
		set(value) {
			field = value.coerceAtLeast(1)
			synchronized(lock) { evictLocked() }
		}

	/**
	 * Access ordered, so eviction drops what was looked at longest ago.
	 *
	 * Eviction is a loop rather than `removeEldestEntry`, which can only ever remove one
	 * entry per insertion. A single page can be larger than several it displaces, so the
	 * overshoot has to be walked off or the budget is only a suggestion.
	 */
	private val entries = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)

	/** Guards the map and the running total together: the two must not disagree. */
	private val lock = Any()

	private var bytesHeld = 0L

	fun get(key: String): ImageBitmap? = synchronized(lock) { entries[key] }

	fun put(key: String, value: ImageBitmap) {
		synchronized(lock) {
			entries.put(key, value)?.let { bytesHeld -= sizeOf(it) }
			bytesHeld += sizeOf(value)
			evictLocked()
		}
	}

	fun remove(key: String) {
		synchronized(lock) { entries.remove(key)?.let { bytesHeld -= sizeOf(it) } }
	}

	/** Drops every entry whose key matches, for a container that has gone away. */
	fun removeIf(predicate: (String) -> Boolean) {
		synchronized(lock) {
			val iterator = entries.entries.iterator()
			while (iterator.hasNext()) {
				val entry = iterator.next()
				if (predicate(entry.key)) {
					bytesHeld -= sizeOf(entry.value)
					iterator.remove()
				}
			}
		}
	}

	fun clear() {
		synchronized(lock) {
			entries.clear()
			bytesHeld = 0L
		}
	}

	fun heldBytes(): Long = synchronized(lock) { bytesHeld }

	fun heldCount(): Int = synchronized(lock) { entries.size }

	private fun evictLocked() {
		val iterator = entries.entries.iterator()
		// Never evicts down to nothing. Whatever was just added is about to be drawn, and
		// a budget smaller than one page is a reason to hold one page anyway, not a
		// reason to return nothing.
		while (iterator.hasNext() && entries.size > 1 && (bytesHeld > maxBytes || entries.size > maxEntries)) {
			val eldest = iterator.next()
			bytesHeld -= sizeOf(eldest.value)
			iterator.remove()
		}
	}

	private companion object {

		const val BYTES_PER_PIXEL = 4L

		/** Decoded size. The encoded bytes are long gone by the time anything is cached. */
		fun sizeOf(bitmap: ImageBitmap): Long =
			bitmap.width.toLong() * bitmap.height.toLong() * BYTES_PER_PIXEL
	}
}
