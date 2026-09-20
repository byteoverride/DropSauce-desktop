package org.koitharu.kotatsu.desktop.feature.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * What the discover area remembers between runs.
 *
 * Queries are newest first. Sources are the ones the user last had ticked for a global
 * search, held by [org.koitharu.kotatsu.parsers.model.MangaParserSource.name] rather
 * than by title, because titles change with the catalogue and enum names do not.
 */
data class DiscoverPrefs(
	val recentQueries: List<String> = emptyList(),
	val globalSources: List<String> = emptyList(),
)

/**
 * [DiscoverPrefs] in a JSON file of this feature's own.
 *
 * Deliberately not a database table: the schema belongs to another area, and a list of
 * twenty strings does not need one. Written to a sibling temp file and moved into place,
 * so an interrupted write cannot leave a half-written file behind.
 */
class DiscoverStore(
	private val file: Path,
	private val fileSystem: FileSystem = FileSystem.SYSTEM,
	private val historyLimit: Int = MAX_HISTORY,
) {

	private val writeLock = Mutex()
	private val state = MutableStateFlow(read())

	val data: StateFlow<DiscoverPrefs> = state.asStateFlow()

	/**
	 * Puts [raw] at the front of the history, folding away any earlier spelling of it.
	 *
	 * Matching ignores case and surrounding space, so searching "one piece" after
	 * "One Piece" moves the entry rather than adding a second one; the newest spelling
	 * is the one kept, because that is what the user just typed.
	 */
	suspend fun recordQuery(raw: String) {
		val query = raw.trim()
		if (query.isEmpty()) return
		update { prefs ->
			val kept = prefs.recentQueries.filterNot { it.equals(query, ignoreCase = true) }
			prefs.copy(recentQueries = (listOf(query) + kept).take(historyLimit))
		}
	}

	suspend fun forgetQuery(query: String) = update { prefs ->
		prefs.copy(recentQueries = prefs.recentQueries.filterNot { it.equals(query, ignoreCase = true) })
	}

	suspend fun clearHistory() = update { it.copy(recentQueries = emptyList()) }

	/** Remembers which sources a global search should cover next time. */
	suspend fun rememberSources(names: Collection<String>) = update {
		it.copy(globalSources = names.distinct())
	}

	private suspend fun update(transform: (DiscoverPrefs) -> DiscoverPrefs) {
		writeLock.withLock {
			val updated = transform(state.value)
			if (updated == state.value) return
			state.value = updated
			withContext(Dispatchers.IO) { write(updated) }
		}
	}

	private fun read(): DiscoverPrefs {
		if (!fileSystem.exists(file)) return DiscoverPrefs()
		return try {
			val root = parseJson(fileSystem.read(file) { readUtf8() }).asObject() ?: return DiscoverPrefs()
			DiscoverPrefs(
				recentQueries = root[FIELD_QUERIES].asStringList().take(historyLimit),
				globalSources = root[FIELD_SOURCES].asStringList(),
			)
		} catch (e: JsonException) {
			// A file we cannot read is a file we throw away: this is a convenience cache,
			// and refusing to start the screen over it would be the worse failure. The
			// next write replaces it.
			logDiscarded(e)
			DiscoverPrefs()
		} catch (e: IOException) {
			logDiscarded(e)
			DiscoverPrefs()
		}
	}

	private fun logDiscarded(cause: Exception) {
		System.err.println("discover: ignoring unreadable $file (${cause.message})")
	}

	private fun write(value: DiscoverPrefs) {
		val parent = file.parent ?: return
		fileSystem.createDirectories(parent, mustCreate = false)
		val temp = parent.resolve(file.name + ".tmp")
		val json = writeJson(
			jsonObject(
				FIELD_VERSION to JsonValue.Num(FORMAT_VERSION.toDouble()),
				FIELD_QUERIES to jsonStrings(value.recentQueries),
				FIELD_SOURCES to jsonStrings(value.globalSources),
			),
		)
		fileSystem.write(temp) { writeUtf8(json) }
		fileSystem.atomicMove(temp, file)
	}

	companion object {

		/** Long enough to be useful as a shortcut, short enough to stay a shortcut. */
		const val MAX_HISTORY = 20

		const val FILE_NAME = "discover.json"

		private const val FORMAT_VERSION = 1
		private const val FIELD_VERSION = "version"
		private const val FIELD_QUERIES = "queries"
		private const val FIELD_SOURCES = "sources"
	}
}
