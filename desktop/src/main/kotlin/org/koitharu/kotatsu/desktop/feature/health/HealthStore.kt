package org.koitharu.kotatsu.desktop.feature.health

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * What the last health check found for one source.
 *
 * [strikes] is the thing that keeps this honest. A source is not written off for one bad
 * afternoon: the count rises on each consecutive failure and is cleared the moment the
 * source answers again, so a timeout during a check costs a source nothing permanent.
 */
@Serializable
data class SourceState(
	@SerialName("verdict") val verdict: SourceVerdict = SourceVerdict.Ok,
	@SerialName("reason") val reason: String = "",
	@SerialName("checked_at") val checkedAt: Long = 0,
	@SerialName("strikes") val strikes: Int = 0,
)

@Serializable
data class HealthData(
	@SerialName("version") val version: Int = 1,
	@SerialName("checked_at") val checkedAt: Long = 0,
	@SerialName("sources") val sources: Map<String, SourceState> = emptyMap(),
)

/**
 * The health of every source that has been checked, in a JSON file of this area's own.
 *
 * Not a database table, for the reason `DiscoverStore` gives: the schema belongs to
 * another area and a map of a few hundred short records does not need one. Written to a
 * sibling temp file and moved into place, so an interrupted write cannot leave half a
 * file behind.
 */
class HealthStore(
	private val file: Path,
	private val fileSystem: FileSystem = FileSystem.SYSTEM,
	private val now: () -> Long = { System.currentTimeMillis() },
) {

	private val writeLock = Mutex()
	private val state = MutableStateFlow(read())

	val data: StateFlow<HealthData> = state.asStateFlow()

	/**
	 * Whether this source should be kept out of the catalogue.
	 *
	 * Two ways in, and both are deliberately hard to reach. A name that no longer
	 * resolves is conclusive on its own, because no amount of waiting brings a DNS entry
	 * back. Everything else needs [STRIKES_TO_HIDE] consecutive failures, so a source has
	 * to fail two checks in a row, on two different occasions, before it disappears.
	 */
	fun isDead(source: String): Boolean {
		val entry = state.value.sources[source] ?: return false
		if (entry.verdict.isUsable) return false
		return entry.verdict.isConclusive || entry.strikes >= STRIKES_TO_HIDE
	}

	fun stateOf(source: String): SourceState? = state.value.sources[source]

	val deadCount: Int get() = state.value.sources.count { isDead(it.key) }

    /**
     * Folds a finished sweep into what is already known.
     *
     * [trust] decides whether anything is written at all. A sweep the engine does not
     * believe is discarded entirely rather than partially applied, because the failure it
     * guards against is exactly the one that would be worst to half-apply.
     */
	suspend fun record(reports: List<SourceReport>, trust: SweepTrust) {
		if (!trust.isTrusted || reports.isEmpty()) return
		writeLock.withLock {
			val stamp = now()
			val merged = state.value.sources.toMutableMap()
			for (report in reports) {
				val previous = merged[report.source]
				merged[report.source] = SourceState(
					verdict = report.verdict,
					reason = report.reason,
					checkedAt = stamp,
					// Cleared on success, not decremented: a source that answers is
					// working now, and carrying old failures forward would keep a
					// recovered source hidden for as many checks as it was down.
					strikes = if (report.verdict.isUsable) 0 else (previous?.strikes ?: 0) + 1,
				)
			}
			val next = HealthData(version = 1, checkedAt = stamp, sources = merged)
			state.value = next
			write(next)
		}
	}

	/** Forgets everything, so the catalogue is whole again until the next check. */
	suspend fun clear() {
		writeLock.withLock {
			val next = HealthData()
			state.value = next
			write(next)
		}
	}

	/** Forgets one source, for a reader who disagrees with the verdict. */
	suspend fun forget(source: String) {
		writeLock.withLock {
			val next = state.value.copy(sources = state.value.sources - source)
			state.value = next
			write(next)
		}
	}

	private fun read(): HealthData = try {
		if (!fileSystem.exists(file)) {
			HealthData()
		} else {
			fileSystem.read(file) { readUtf8() }.let { JSON.decodeFromString<HealthData>(it) }
		}
	} catch (e: IOException) {
		// A missing or unreadable file means "nothing has been checked yet", which is
		// the same as a fresh install and is recoverable by running a check.
		HealthData()
	} catch (e: SerializationFailure) {
		HealthData()
	}

	private suspend fun write(data: HealthData) = withContext(Dispatchers.IO) {
		val temp = file.parent?.resolve("${file.name}.tmp") ?: return@withContext
		file.parent?.let { fileSystem.createDirectories(it) }
		fileSystem.write(temp) { writeUtf8(JSON.encodeToString(data)) }
		fileSystem.atomicMove(temp, file)
	}

	companion object {

		/**
		 * Two consecutive failures before a source is hidden.
		 *
		 * One is a bad moment. Two separate checks agreeing is a pattern, and the reader
		 * has to have run the check twice to get there, which is its own confirmation.
		 */
		const val STRIKES_TO_HIDE = 2

		private val JSON = Json {
			ignoreUnknownKeys = true
			encodeDefaults = true
			prettyPrint = true
		}
	}
}

/** kotlinx.serialization's failure is not an IOException, and both mean the same here. */
private typealias SerializationFailure = kotlinx.serialization.SerializationException
