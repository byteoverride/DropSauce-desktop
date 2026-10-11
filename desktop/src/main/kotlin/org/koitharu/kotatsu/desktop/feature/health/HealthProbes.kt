package org.koitharu.kotatsu.desktop.feature.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Asks one source for its front page, and times it.
 *
 * The front page rather than a search, deliberately. It is the cheapest thing every
 * source supports, it is one request, and it is exactly what a reader sees first: if this
 * does not work, nothing else on that source will either. A search would also test the
 * query path, which is a different question and costs a second request.
 *
 * The timeout is the engine's, not the HTTP client's. `SourceRegistry`'s client allows 90
 * seconds for a whole call, which is right for a reader waiting on a page they asked for
 * and far too long when 890 of them are queued behind it.
 */
internal fun SourceRegistry.healthTarget(
	source: MangaParserSource,
	timeoutMs: Long = PROBE_TIMEOUT_MS,
): HealthTarget = HealthTarget(source = source.name, title = source.title) {
	val started = System.currentTimeMillis()
	val outcome = withContext(Dispatchers.IO) {
		try {
			withTimeoutOrNull(timeoutMs) {
				val parser = session(source).parser
				val order = parser.availableSortOrders.firstOrNull() ?: SortOrder.UPDATED
				val list = parser.getList(0, order, MangaListFilter())
				if (list.isEmpty()) {
					SourceVerdict.Empty to "answered with nothing"
				} else {
					SourceVerdict.Ok to ""
				}
			} ?: (SourceVerdict.Unreachable to "no answer in ${timeoutMs / 1000}s")
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			classify(e)
		}
	}
	SourceReport(
		source = source.name,
		verdict = outcome.first,
		reason = outcome.second,
		millis = System.currentTimeMillis() - started,
	)
}

/**
 * Long enough for a slow site on a slow connection, short enough that a whole catalogue
 * finishes. A source that cannot produce its front page in this is not one a reader is
 * going to enjoy either.
 */
private const val PROBE_TIMEOUT_MS = 15_000L
