package org.koitharu.kotatsu.desktop.feature.download

import org.koitharu.kotatsu.parsers.model.MangaChapter

/**
 * Which chapters a download covers.
 *
 * The same four choices the Android app offers in its download dialog, where they are
 * `ChaptersSelectMacro.WholeManga`, `WholeBranch`, `FirstChapters` and `UnreadChapters`.
 * Kept as plain logic over a chapter list rather than wired into a screen, because the
 * interesting part is the arithmetic and the interesting part is what goes wrong: a
 * branch filter that silently matches nothing, or "unread" quietly meaning "everything"
 * when the remembered chapter has been renumbered away.
 */
sealed interface ChapterSelection {

	/** The chapters this covers, in reading order, possibly none. */
	fun select(chapters: List<MangaChapter>): List<MangaChapter>

	/** Everything the source lists, every branch. */
	data object Everything : ChapterSelection {

		override fun select(chapters: List<MangaChapter>): List<MangaChapter> = chapters
	}

	/**
	 * One scanlation branch.
	 *
	 * Worth its own option because a title with three translations lists all of them
	 * interleaved, and downloading the lot means three copies of the same story.
	 */
	data class Branch(val branch: String?) : ChapterSelection {

		override fun select(chapters: List<MangaChapter>): List<MangaChapter> =
			chapters.filter { it.branch == branch }
	}

	/** The first [count] chapters of [branch], for sampling a long title. */
	data class First(val count: Int, val branch: String?) : ChapterSelection {

		override fun select(chapters: List<MangaChapter>): List<MangaChapter> =
			chapters.asSequence()
				.filter { branch == null || it.branch == branch }
				.take(count.coerceAtLeast(0))
				.toList()
	}

	/**
	 * Everything after the chapter last read.
	 *
	 * [lastReadChapterId] null, or naming a chapter the source no longer lists, means
	 * nothing has been read here that this list can place, so everything is unread. That
	 * is the safe direction: offering the whole title to someone who wanted the tail is a
	 * larger download they can see the size of before confirming, while the opposite
	 * silently skips chapters they wanted.
	 */
	data class Unread(val lastReadChapterId: Long?) : ChapterSelection {

		override fun select(chapters: List<MangaChapter>): List<MangaChapter> {
			val index = chapters.indexOfFirst { it.id == lastReadChapterId }
			return if (index < 0) chapters else chapters.drop(index + 1)
		}
	}
}

/** The branches a chapter list carries, in the order they first appear. */
fun branchesOf(chapters: List<MangaChapter>): List<String?> =
	chapters.map { it.branch }.distinct()
