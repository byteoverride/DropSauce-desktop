package org.koitharu.kotatsu.desktop.feature.download

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.shared.db.DownloadEntity

/**
 * The five values `downloads.state` may hold.
 *
 * Constants rather than an enum column: the schema has no type converters anywhere
 * (see the note on [DownloadEntity]), so the mapping stays in Kotlin where it is
 * visible. [of] is the only place a stored string is interpreted.
 */
object DownloadState {

	const val QUEUED = "QUEUED"
	const val RUNNING = "RUNNING"
	const val DONE = "DONE"
	const val FAILED = "FAILED"
	const val CANCELLED = "CANCELLED"

	/** True while the row still owes work, so the queue must keep or pick it up. */
	fun isActive(state: String): Boolean = state == QUEUED || state == RUNNING

	/** True when the row will not change again on its own. */
	fun isFinished(state: String): Boolean = !isActive(state)
}

/** One chapter's download, as the screen needs it. */
data class DownloadItem(
	val mangaId: Long,
	val chapterId: Long,
	val chapterTitle: String,
	val chapterNumber: Float,
	val state: String,
	val pagesTotal: Int,
	val pagesDone: Int,
	val error: String?,
	val updatedAt: Long,
) {

	/** 0f until the page count is known, so an empty bar never reads as "stalled at 100%". */
	val progress: Float
		get() = when {
			state == DownloadState.DONE -> 1f
			pagesTotal <= 0 -> 0f
			else -> (pagesDone.toFloat() / pagesTotal).coerceIn(0f, 1f)
		}
}

/** Every download belonging to one title. */
data class DownloadGroup(
	val manga: Manga,
	val sourceName: String,
	val items: List<DownloadItem>,
) {

	val doneCount: Int get() = items.count { it.state == DownloadState.DONE }

	val isBusy: Boolean get() = items.any { DownloadState.isActive(it.state) }
}

internal fun DownloadEntity.toItem() = DownloadItem(
	mangaId = mangaId,
	chapterId = chapterId,
	chapterTitle = chapterTitle,
	chapterNumber = chapterNumber,
	state = state,
	pagesTotal = pagesTotal,
	pagesDone = pagesDone,
	error = error,
	updatedAt = updatedAt,
)
