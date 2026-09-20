package org.koitharu.kotatsu.shared.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug

/**
 * [SettingsStore] backed by a JSON file.
 *
 * `java.util.prefs` was the obvious alternative and is ruled out in DECISIONS.md D8: it
 * has no set or list type, and an 8 KB per-value cap that the Android app's own JSON-blob
 * preferences already exceed.
 *
 * Writes go to a sibling temp file and are then moved into place, so an interrupted write
 * cannot leave a half-written config that fails to parse on next launch.
 */
class JsonSettingsStore(
	private val file: Path,
	private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : SettingsStore {

	private val json = Json {
		prettyPrint = true
		// A config written by a newer build must not stop an older one from starting.
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val writeLock = Mutex()
	private val state = MutableStateFlow(read())

	override val data: StateFlow<SettingsData> = state.asStateFlow()

	override suspend fun update(transform: (SettingsData) -> SettingsData) {
		writeLock.withLock {
			val updated = transform(state.value)
			if (updated == state.value) return
			state.value = updated
			withContext(Dispatchers.IO) { write(updated) }
		}
	}

	private fun read(): SettingsData {
		if (!fileSystem.exists(file)) return SettingsData()
		return try {
			val text = fileSystem.read(file) { readUtf8() }
			json.decodeFromString(SettingsData.serializer(), text)
		} catch (e: Exception) {
			// A corrupt or truncated config must not stop the app starting. Defaults are
			// always a working configuration, so fall back rather than surfacing this.
			e.printStackTraceDebug()
			SettingsData()
		}
	}

	private fun write(value: SettingsData) {
		file.parent?.let { fileSystem.createDirectories(it, mustCreate = false) }
		val temp = file.parent?.resolve(file.name + ".tmp") ?: return
        fileSystem.write(temp) { writeUtf8(json.encodeToString(SettingsData.serializer(), value)) }
		fileSystem.atomicMove(temp, file)
	}
}
