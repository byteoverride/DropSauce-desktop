package org.koitharu.kotatsu.desktop.feature.curate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What the organise screen remembers between runs.
 *
 * [incognito] is the *global* switch. The per-title flag lives in `manga_prefs.incognito`
 * because it belongs to the title and has to survive a config file being deleted, while
 * this one is a session preference for the whole app and has no title to hang off.
 */
@Serializable
data class CuratePrefs(
	val version: Int = FORMAT_VERSION,
	val sort: LibrarySortKey = LibrarySortKey.TITLE,
	val descending: Boolean = false,
	val view: LibraryViewMode = LibraryViewMode.GRID,
	val incognito: Boolean = false,
) {

	companion object {

		const val FORMAT_VERSION = 1
	}
}

/**
 * [CuratePrefs] in a JSON file of this area's own, under `AppPaths.config`.
 *
 * Not a database table: the schema belongs to the shared module and five fields do not
 * justify a migration. Written to a sibling temp file and moved into place, so an
 * interrupted write cannot leave half a file behind and lose the user's view settings.
 */
class CurateConfigStore(
	private val file: Path,
	private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {

	private val writeLock = Mutex()
	private val state = MutableStateFlow(read())

	val data: StateFlow<CuratePrefs> = state.asStateFlow()

	val current: CuratePrefs get() = state.value

	suspend fun setSort(key: LibrarySortKey, descending: Boolean) =
		update { it.copy(sort = key, descending = descending) }

	suspend fun setView(mode: LibraryViewMode) = update { it.copy(view = mode) }

	suspend fun setIncognito(enabled: Boolean) = update { it.copy(incognito = enabled) }

	suspend fun update(transform: (CuratePrefs) -> CuratePrefs) {
		writeLock.withLock {
			val updated = transform(state.value)
			if (updated == state.value) return
			state.value = updated
			withContext(Dispatchers.IO) { write(updated) }
		}
	}

	private fun read(): CuratePrefs {
		if (!fileSystem.exists(file)) return CuratePrefs()
		return try {
			JSON.decodeFromString(CuratePrefs.serializer(), fileSystem.read(file) { readUtf8() })
		} catch (e: SerializationException) {
			// View settings are a convenience. Refusing to open the screen because its
			// preferences file is corrupt would be the worse failure; the next write
			// replaces it.
			logDiscarded(e)
			CuratePrefs()
		} catch (e: IOException) {
			logDiscarded(e)
			CuratePrefs()
		}
	}

	private fun logDiscarded(cause: Exception) {
		System.err.println("curate: ignoring unreadable $file (${cause.message})")
	}

	private fun write(value: CuratePrefs) {
		val parent = file.parent ?: return
		fileSystem.createDirectories(parent, mustCreate = false)
		val temp = parent.resolve(file.name + ".tmp")
		fileSystem.write(temp) { writeUtf8(JSON.encodeToString(CuratePrefs.serializer(), value)) }
		fileSystem.atomicMove(temp, file)
	}

	companion object {

		const val FILE_NAME = "curate.json"

		private val instances = ConcurrentHashMap<String, CurateConfigStore>()

		/**
		 * The one store for [file] in this process.
		 *
		 * The global incognito switch is read by the reader and written by the organise
		 * screen. Each instance holds its own `StateFlow`, seeded from the file when it is
		 * constructed, so two instances over the same path would disagree the moment one
		 * of them wrote: the user would turn incognito on and the reader, holding the
		 * other instance, would carry on recording history. Everything in the app must
		 * come through here; the constructor stays public for tests, which want the
		 * cold-start behaviour of reading the file back.
		 */
		fun shared(file: Path, fileSystem: FileSystem = FileSystem.SYSTEM): CurateConfigStore =
			instances.computeIfAbsent(file.toString()) { CurateConfigStore(file, fileSystem) }

		/**
		 * Unknown keys are ignored so a file written by a later build still opens here,
		 * and defaults are encoded so the file is readable by a human deciding what to
		 * edit.
		 */
		private val JSON = Json {
			ignoreUnknownKeys = true
			encodeDefaults = true
			prettyPrint = true
		}
	}
}
