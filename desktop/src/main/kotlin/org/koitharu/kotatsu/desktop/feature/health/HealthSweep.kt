package org.koitharu.kotatsu.desktop.feature.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One source to check, and how to check it. A function, so the engine needs no network. */
class HealthTarget(
	val source: String,
	val title: String,
	val probe: suspend () -> SourceReport,
)

/** How far a sweep has got, emitted as each source answers. */
data class SweepProgress(
	val done: Int,
	val total: Int,
	val running: String?,
	val reports: List<SourceReport>,
) {

	val isFinished: Boolean get() = done == total

	fun fraction(): Float = if (total == 0) 1f else done.toFloat() / total

	val usable: Int get() = reports.count { it.verdict.isUsable }

	val dead: Int get() = reports.count { !it.verdict.isUsable }
}

/**
 * The verdict on the sweep itself, before any verdict on a source.
 *
 * This exists because of the failure that would otherwise make the whole feature
 * dangerous: run a sweep while your own connection is down and every source on earth
 * answers "the address does not resolve", which, taken at face value, empties the
 * catalogue. The instrument has to be checked before its measurements are believed.
 *
 * No extra request is made to do it. The check is the shape of the results themselves:
 * sites fail independently, so a large majority failing *in the same network-level way*
 * at the same moment is a statement about this machine, not about the internet.
 */
data class SweepTrust(
	val isTrusted: Boolean,
	val note: String,
)

/**
 * Decides whether a finished sweep may be recorded.
 *
 * Two rules, both deliberately blunt:
 *
 * - **Half the catalogue cannot lose its DNS at once.** If it looks like it did, the
 *   resolver is the thing that is broken.
 * - **Nearly everything failing at the network level** — no DNS, no TLS, no connection —
 *   is a connection that is down or a captive portal, not hundreds of simultaneous
 *   outages.
 *
 * A source answering with an HTTP error or unreadable HTML is *not* counted towards
 * either rule, because those prove the network works: the packets got there and
 * something answered.
 */
fun trustOf(reports: List<SourceReport>): SweepTrust {
	if (reports.size < MIN_SAMPLE) {
		return SweepTrust(true, "")
	}
	val gone = reports.count { it.verdict == SourceVerdict.Gone }.toFloat() / reports.size
	if (gone >= GONE_LIMIT) {
		return SweepTrust(
			isTrusted = false,
			note = "${(gone * 100).toInt()}% of sources failed to resolve at all. That is this " +
				"machine's DNS, not the sources, so nothing was recorded.",
		)
	}
	val networkLevel = reports.count {
		it.verdict == SourceVerdict.Gone ||
			it.verdict == SourceVerdict.Unreachable ||
			it.verdict == SourceVerdict.Untrusted
	}.toFloat() / reports.size
	if (networkLevel >= NETWORK_LIMIT) {
		return SweepTrust(
			isTrusted = false,
			note = "${(networkLevel * 100).toInt()}% of sources could not be reached at the " +
				"network level, and not one of them answered. That is the connection, so " +
				"nothing was recorded.",
		)
	}
	return SweepTrust(true, "")
}

/** Too few to reason about. A sweep of three sources says nothing about the network. */
private const val MIN_SAMPLE = 10

/** Half the catalogue losing its name at once is a resolver, not an extinction. */
private const val GONE_LIMIT = 0.5f

/** Almost nothing reachable, and nothing answering at all. */
private const val NETWORK_LIMIT = 0.9f

/**
 * Checks a list of sources, a few at a time, and streams the progress.
 *
 * The concurrency cap is lower than the global search's six. A sweep touches every source
 * in the catalogue rather than the handful a reader picked, and the point of it is to be
 * run occasionally and politely rather than quickly: 890 sites being asked for their
 * front page as fast as the machine can manage is indistinguishable from something
 * unpleasant, from the far end.
 */
class HealthSweep(private val concurrency: Int = DEFAULT_CONCURRENCY) {

	init {
		require(concurrency > 0) { "concurrency must be positive, was $concurrency" }
	}

	fun run(targets: List<HealthTarget>): Flow<SweepProgress> = channelFlow {
		send(SweepProgress(done = 0, total = targets.size, running = null, reports = emptyList()))
		if (targets.isEmpty()) return@channelFlow
		val permits = Semaphore(concurrency)
		val collected = ArrayList<SourceReport>(targets.size)
		val lock = Any()
		coroutineScope {
			for (target in targets) {
				launch {
					permits.withPermit {
						val report = try {
							target.probe()
						} catch (e: CancellationException) {
							throw e
						} catch (e: Throwable) {
							// The probe is supposed to catch its own failures; this is
							// the net under it, so one unexpected throw cannot abandon
							// the other 889.
							val (verdict, reason) = classify(e)
							SourceReport(target.source, verdict, reason)
						}
						val snapshot = synchronized(lock) {
							collected += report
							collected.toList()
						}
						send(
							SweepProgress(
								done = snapshot.size,
								total = targets.size,
								running = target.title,
								reports = snapshot,
							),
						)
					}
				}
			}
		}
	}

	companion object {

		/** Four at a time. See the class comment: politeness, not throughput. */
		const val DEFAULT_CONCURRENCY = 4
	}
}
