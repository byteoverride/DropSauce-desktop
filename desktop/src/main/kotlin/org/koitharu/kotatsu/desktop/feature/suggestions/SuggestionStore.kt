package org.koitharu.kotatsu.desktop.feature.suggestions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path

/** What the suggestions area remembers between runs. */
@Serializable
data class SuggestionPrefs(
	@SerialName("version") val version: Int = FORMAT_VERSION,
	/**
	 * Titles the user threw away, newest first.
	 *
	 * Order is load-bearing: the cap drops the oldest, and the oldest dismissal is the
	 * one the user is least likely to still remember making.
	 */
	@SerialName("dismissed") val dismissed: List<Long> = emptyList(),
)

/**
 * Tags remembered for a title, so a profile does not need a details fetch per refresh.
 *
 * Keyed by manga id. A source's tag vocabulary changes slowly and a stale tag costs a
 * slightly worse recommendation, which is why this is a cache and not a table.
 */
@Serializable
data class TagCacheFile(
	@SerialName("version") val version: Int = FORMAT_VERSION,
	@SerialName("entries") val entries: Map<String, TagCacheEntry> = emptyMap(),
)

@Serializable
data class TagCacheEntry(
	@SerialName("tags") val tags: List<String>,
	@SerialName("at") val storedAt: Long,
)

/**
 * [SuggestionPrefs] in a JSON file of this area's own.
 *
 * Not a database table: the schema is shared with four other areas and a list of longs
 * does not justify a contract change. Written to a sibling temp file and moved into
 * place, so an interrupted write cannot leave a truncated file behind.
 *
 * Dismissals are capped. A user who taps dismiss on every refresh for a year would
 * otherwise grow this file without bound, and an unbounded file read at startup is a
 * slow start that nobody can explain later.
 */
class SuggestionStore(
	private val file: Path,
	private val fileSystem: FileSystem = FileSystem.SYSTEM,
	private val limit: Int = MAX_DISMISSED,
) {

	private val writeLock = Mutex()
	private val state = MutableStateFlow(read())

	val data: StateFlow<SuggestionPrefs> = state.asStateFlow()

	val dismissed: Set<Long> get() = state.value.dismissed.toSet()

	suspend fun dismiss(mangaId: Long) = update { prefs ->
		val kept = prefs.dismissed.filterNot { it == mangaId }
		prefs.copy(dismissed = (listOf(mangaId) + kept).take(limit))
	}

	suspend fun restore(mangaId: Long) = update { prefs ->
		prefs.copy(dismissed = prefs.dismissed.filterNot { it == mangaId })
	}

	suspend fun clearDismissed() = update { it.copy(dismissed = emptyList()) }

	private suspend fun update(transform: (SuggestionPrefs) -> SuggestionPrefs) {
		writeLock.withLock {
			val updated = transform(state.value)
			if (updated == state.value) return
			state.value = updated
			withContext(Dispatchers.IO) { write(updated) }
		}
	}

	private fun read(): SuggestionPrefs = readJson<SuggestionPrefs>(fileSystem, file)
		?.let { it.copy(dismissed = it.dismissed.distinct().take(limit)) }
		?: SuggestionPrefs()

	private fun write(value: SuggestionPrefs) = writeJson(fileSystem, file, value)

	companion object {

		/**
		 * Roughly a year of dismissing a few a week. Far beyond what anyone reaches, and
		 * still a fixed ceiling on the file.
		 */
		const val MAX_DISMISSED = 500

		const val FILE_NAME = "suggestions.json"
	}
}

/**
 * Tags for library titles, on disk.
 *
 * Lives in the cache directory rather than the config directory: losing it costs one
 * slow refresh, and the rule for the cache directory is that deleting it must be
 * harmless. Entries are capped for the same reason dismissals are.
 */
class TagCache(
	private val file: Path,
	private val fileSystem: FileSystem = FileSystem.SYSTEM,
	private val limit: Int = MAX_ENTRIES,
	private val now: () -> Long = System::currentTimeMillis,
) {

	private val writeLock = Mutex()

	@Volatile
	private var entries: Map<Long, TagCacheEntry> = read()

	operator fun get(mangaId: Long): List<String>? = entries[mangaId]?.tags

	/** Adds or replaces [tags] for [mangaId]. Absent from [tags] means "known to have none". */
	suspend fun put(mangaId: Long, tags: List<String>) = putAll(mapOf(mangaId to tags))

	suspend fun putAll(values: Map<Long, List<String>>) {
		if (values.isEmpty()) return
		writeLock.withLock {
			val timestamp = now()
			val merged = LinkedHashMap(entries)
			for ((id, tags) in values) {
				merged.remove(id)
				merged[id] = TagCacheEntry(tags, timestamp)
			}
			// Insertion order is oldest first, so dropping from the front evicts the
			// entries least recently written.
			val trimmed = if (merged.size <= limit) merged else {
				merged.entries.drop(merged.size - limit).associate { it.key to it.value }
			}
			entries = trimmed
			withContext(Dispatchers.IO) {
				writeJson(
					fileSystem,
					file,
					TagCacheFile(entries = trimmed.mapKeys { it.key.toString() }),
				)
			}
		}
	}

	private fun read(): Map<Long, TagCacheEntry> {
		val loaded = readJson<TagCacheFile>(fileSystem, file) ?: return emptyMap()
		return loaded.entries.mapNotNull { (key, value) ->
			key.toLongOrNull()?.let { it to value }
		}.toMap()
	}

	companion object {

		/** A very large library, with room to spare. */
		const val MAX_ENTRIES = 4000

		const val FILE_NAME = "suggestion-tags.json"
	}
}

/**
 * The one JSON configuration both files share.
 *
 * `ignoreUnknownKeys` so a file written by a later version still loads; `encodeDefaults`
 * so the version field is always present even when it equals the default, which is what
 * makes a future migration able to tell the formats apart.
 */
private val json = Json {
	ignoreUnknownKeys = true
	encodeDefaults = true
	prettyPrint = true
}

/**
 * Reads [T] from [file], or null when there is nothing usable there.
 *
 * A file that cannot be parsed is discarded rather than fatal. Both callers hold a
 * convenience cache, and refusing to open the screen over a corrupt preferences file
 * would be a worse failure than forgetting what it held. The next write replaces it.
 */
private inline fun <reified T> readJson(fileSystem: FileSystem, file: Path): T? {
	if (!fileSystem.exists(file)) return null
	return try {
		json.decodeFromString<T>(fileSystem.read(file) { readUtf8() })
	} catch (e: SerializationException) {
		logDiscarded(file, e)
		null
	} catch (e: IllegalArgumentException) {
		logDiscarded(file, e)
		null
	} catch (e: IOException) {
		logDiscarded(file, e)
		null
	}
}

private inline fun <reified T> writeJson(fileSystem: FileSystem, file: Path, value: T) {
	val parent = file.parent ?: return
	fileSystem.createDirectories(parent, mustCreate = false)
	val temp = parent.resolve(file.name + ".tmp")
	fileSystem.write(temp) { writeUtf8(json.encodeToString(value)) }
	fileSystem.atomicMove(temp, file)
}

private fun logDiscarded(file: Path, cause: Exception) {
	System.err.println("suggestions: ignoring unreadable $file (${cause.message})")
}

private const val FORMAT_VERSION = 1
