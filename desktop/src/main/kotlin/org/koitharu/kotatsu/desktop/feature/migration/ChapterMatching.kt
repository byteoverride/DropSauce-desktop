package org.koitharu.kotatsu.desktop.feature.migration

import org.koitharu.kotatsu.parsers.model.MangaChapter

/**
 * Chapter identity does not survive a source change.
 *
 * A chapter id is derived from the source's own url, so the same chapter on two sites has
 * two unrelated ids. Everything that hangs off a chapter id (a bookmark, a reading
 * position) therefore has to be re-pointed through a map built from the two chapter
 * lists, and anything that cannot be re-pointed has to be dropped: a bookmark silently
 * moved to a different chapter is worse than a bookmark that is gone, because the user
 * cannot tell it happened.
 */

/**
 * Old chapter id to new chapter id.
 *
 * Two rules, in order:
 *
 * 1. **Volume and chapter number.** The only identity that is actually shared between two
 *    sites. First new chapter with a given (volume, number) wins, so a source that lists a
 *    chapter twice does not shuffle the mapping.
 * 2. **Ordinal position, but only for chapters that carry no number.** Plenty of sources
 *    number nothing (`number == 0f`), and for those, position is the only signal there is.
 *
 * Deliberately *not* an ordinal fallback for numbered chapters. Old [1, 2, 3] against new
 * [1, 3] would map old 2 onto new index 1, which is chapter 3: exactly the silent
 * mis-point this map exists to prevent. A numbered chapter with no numbered counterpart is
 * dropped instead. Android's `mapChapterIds` drops it too; it has no ordinal fallback at
 * all, and the unnumbered case is the gap this closes.
 *
 * A new chapter is never the target of two old chapters, so a dropped entry cannot come
 * back as a duplicate of a kept one.
 */
internal fun mapChapterIds(
	oldChapters: List<MangaChapter>,
	newChapters: List<MangaChapter>,
): Map<Long, Long> {
	if (oldChapters.isEmpty() || newChapters.isEmpty()) {
		return emptyMap()
	}
	val byNumber = HashMap<ChapterNumber, Long>(newChapters.size)
	for (chapter in newChapters) {
		if (chapter.number > 0f) {
			byNumber.putIfAbsent(chapter.numberKey(), chapter.id)
		}
	}
	val result = HashMap<Long, Long>(oldChapters.size)
	val claimed = HashSet<Long>(oldChapters.size)
	for ((index, chapter) in oldChapters.withIndex()) {
		val target = if (chapter.number > 0f) {
			byNumber[chapter.numberKey()]
		} else {
			newChapters.getOrNull(index)?.id
		} ?: continue
		if (claimed.add(target)) {
			result[chapter.id] = target
		}
	}
	return result
}

/**
 * The new chapter a reading position should land on.
 *
 * Unlike a bookmark, a reading position is not dropped when there is no exact match.
 * Losing it means the user reopens the title at chapter one with no way to know where
 * they were, which is the single worst outcome of a migration; landing one chapter off is
 * recoverable in two clicks. So this falls back to the equivalent position in the new
 * list, and reports which of the two happened so the screen can say so.
 */
internal fun matchReadingPosition(
	oldChapters: List<MangaChapter>,
	newChapters: List<MangaChapter>,
	oldChapterId: Long,
	percent: Float,
): ChapterMatch? {
	if (newChapters.isEmpty()) {
		return null
	}
	// No old chapter list (the old source is dead and could not be loaded): position by
	// how far through the title the reader had got, which is all that survived.
	if (oldChapters.isEmpty()) {
		return ChapterMatch(newChapters[percentIndex(newChapters.lastIndex, percent)].id, byNumber = false)
	}
	var index = oldChapters.indexOfFirst { it.id == oldChapterId }
	if (index < 0) {
		// The remembered chapter is not in the list any more, which happens when the old
		// source reorganised. Percent is the only thing left to go on.
		index = percentIndex(oldChapters.lastIndex, percent)
	}
	val oldChapter = oldChapters[index]
	if (oldChapter.number > 0f) {
		val key = oldChapter.numberKey()
		newChapters.firstOrNull { it.number > 0f && it.numberKey() == key }?.let {
			return ChapterMatch(it.id, byNumber = true)
		}
	}
	val fallback = newChapters.getOrNull(index) ?: newChapters.last()
	return ChapterMatch(fallback.id, byNumber = false)
}

/** Where a reading position landed, and whether it was an exact chapter-number match. */
internal data class ChapterMatch(val chapterId: Long, val byNumber: Boolean)

/** Volume plus number. Volume matters: many sources restart numbering at each volume. */
private typealias ChapterNumber = Pair<Int, Float>

private fun MangaChapter.numberKey(): ChapterNumber = volume to number

private fun percentIndex(lastIndex: Int, percent: Float): Int =
	if (lastIndex <= 0 || percent !in 0f..1f) 0 else (lastIndex * percent).toInt()
