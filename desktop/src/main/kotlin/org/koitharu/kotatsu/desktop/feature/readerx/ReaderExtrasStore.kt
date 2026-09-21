package org.koitharu.kotatsu.desktop.feature.readerx

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

/**
 * The reader settings this feature owns.
 *
 * Not in `SettingsData`: that record is shared with several other areas and adding four
 * reader-only keys to it is how the Android app's 205-key settings object grew
 * (DECISIONS.md D8). Not in the database either, because the schema file belongs to a
 * different owner and none of this is relational.
 *
 * [titleFilters] is keyed by manga id. The `manga_prefs` table has no column for a colour
 * filter and this area cannot add one, so per-title filters live here alongside the
 * defaults rather than being dropped. Everything else per-title goes through
 * [TitlePrefsRepository].
 */
@Serializable
data class ReaderExtrasPrefs(
	@SerialName("color") val colorFilter: ReaderColorParams = ReaderColorParams.DEFAULT,
	@SerialName("tap_zones") val tapZones: TapZoneGrid = TapZoneGrid.DEFAULT,
	@SerialName("double_page") val doublePage: Boolean = false,
	/** Whether page one stands alone. See [pairPages] for why this shifts the whole book. */
	@SerialName("cover_first") val coverFirst: Boolean = true,
	@SerialName("title_filters") val titleFilters: Map<Long, ReaderColorParams> = emptyMap(),
) {

	/** The filter for [mangaId], falling back to the default when it has no override. */
	fun filterFor(mangaId: Long): ReaderColorParams = titleFilters[mangaId] ?: colorFilter

	/** True when [mangaId] has its own filter rather than following the default. */
	fun hasOwnFilter(mangaId: Long): Boolean = mangaId in titleFilters
}

/**
 * [ReaderExtrasPrefs] in a JSON file of this feature's own.
 *
 * Written to a sibling temp file and moved into place, so an interrupted write cannot
 * leave a half-written file that fails to parse on next launch. Same shape as the
 * discover area's store, which is the established pattern here, but using
 * kotlinx-serialization rather than a hand-written codec now that one is on the module.
 */
class ReaderExtrasStore(
	private val file: Path,
	private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {

	private val json = Json {
		prettyPrint = true
		// A file written by a newer build must not stop an older one from starting.
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val writeLock = Mutex()
	private val state = MutableStateFlow(read())

	val data: StateFlow<ReaderExtrasPrefs> = state.asStateFlow()

	suspend fun setColorFilter(params: ReaderColorParams) =
		update { it.copy(colorFilter = params.coerced()) }

	suspend fun setTapZones(grid: TapZoneGrid) = update { it.copy(tapZones = grid) }

	suspend fun setTapAction(zone: TapZone, action: TapAction) =
		update { it.copy(tapZones = it.tapZones.with(zone, action)) }

	suspend fun setDoublePage(enabled: Boolean) = update { it.copy(doublePage = enabled) }

	suspend fun setCoverFirst(enabled: Boolean) = update { it.copy(coverFirst = enabled) }

	suspend fun setTitleFilter(mangaId: Long, params: ReaderColorParams) =
		update { it.copy(titleFilters = it.titleFilters + (mangaId to params.coerced())) }

	/** Drops [mangaId]'s own filter so it follows the default again. */
	suspend fun clearTitleFilter(mangaId: Long) =
		update { it.copy(titleFilters = it.titleFilters - mangaId) }

	suspend fun resetToDefaults() = update { ReaderExtrasPrefs() }

	private suspend fun update(transform: (ReaderExtrasPrefs) -> ReaderExtrasPrefs) {
		writeLock.withLock {
			val updated = transform(state.value)
			if (updated == state.value) return
			state.value = updated
			withContext(Dispatchers.IO) { write(updated) }
		}
	}

	private fun read(): ReaderExtrasPrefs {
		if (!fileSystem.exists(file)) return ReaderExtrasPrefs()
		return try {
			json.decodeFromString(
				ReaderExtrasPrefs.serializer(),
				fileSystem.read(file) { readUtf8() },
			)
		} catch (e: SerializationException) {
			// Defaults are always a working configuration, so a file we cannot parse is
			// a file we throw away rather than a reason to refuse to open the reader.
			logDiscarded(e)
			ReaderExtrasPrefs()
		} catch (e: IOException) {
			logDiscarded(e)
			ReaderExtrasPrefs()
		}
	}

	private fun logDiscarded(cause: Exception) {
		System.err.println("readerx: ignoring unreadable $file (${cause.message})")
	}

	private fun write(value: ReaderExtrasPrefs) {
		val parent = file.parent ?: return
		fileSystem.createDirectories(parent, mustCreate = false)
		val temp = parent.resolve(file.name + ".tmp")
		fileSystem.write(temp) { writeUtf8(json.encodeToString(ReaderExtrasPrefs.serializer(), value)) }
		fileSystem.atomicMove(temp, file)
	}

	companion object {

		const val FILE_NAME = "reader-extras.json"
	}
}
