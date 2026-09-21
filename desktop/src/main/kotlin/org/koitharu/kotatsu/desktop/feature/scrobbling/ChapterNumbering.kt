package org.koitharu.kotatsu.desktop.feature.scrobbling

import org.koitharu.kotatsu.parsers.model.MangaChapter
import kotlin.math.floor

/**
 * Turns "the user just finished this chapter" into the whole number a tracker expects.
 *
 * Trackers count chapters read as an integer. Sources do not: a chapter carries a float
 * number that may be 10.5 for a split release or an extra, and may be 0 for a prologue or
 * for a source that simply never numbers anything. So the number cannot be handed over as
 * it stands, and the two awkward cases need different answers.
 *
 * **A decimal number truncates down.** Finishing 10.5 means ten whole chapters have been
 * read, not eleven. Rounding up would mark a chapter the user has not opened as read, and
 * on a tracker that is a change they have to undo by hand.
 *
 * **A number below 1 falls back to position.** Zero carries no information at all, and so
 * does 0.5: truncating either gives 0, which the tracker reads as "nothing read" and which
 * would undo real progress on every push. The 1-based position within the chapter's own
 * branch is the best available answer, and it is correct for the common case of a source
 * that numbers nothing.
 *
 * The branch filter is why position is safe to use. A title with three scanlation groups
 * has their chapters interleaved in one list, so a raw list index would count all three
 * and report roughly triple the real progress.
 *
 * @return null when [chapterId] is not in [chapters], in which case there is nothing
 * truthful to push and the caller skips the update rather than guessing.
 */
fun trackerChapterNumber(chapters: List<MangaChapter>, chapterId: Long): Int? {
	val chapter = chapters.firstOrNull { it.id == chapterId } ?: return null
	if (chapter.number >= 1f) {
		return floor(chapter.number).toInt()
	}
	val branch = chapters.filter { it.branch == chapter.branch }
	val position = branch.indexOfFirst { it.id == chapterId }
	// indexOfFirst cannot be -1 here: the chapter came out of this same list and matches
	// its own branch. Guarded anyway, because returning 0 would silently zero progress.
	return if (position >= 0) position + 1 else null
}
