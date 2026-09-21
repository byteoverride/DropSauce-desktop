package org.koitharu.kotatsu.desktop.feature.curate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * The one place that decides whether reading a title is recorded.
 *
 * Two independent switches. The global one is a session preference and lives in this
 * area's JSON config; the per-title one is `manga_prefs.incognito` and belongs to the
 * title, so it survives the config file being deleted and travels with a backup.
 *
 * Either being on suppresses history. They are not layered and neither overrides the
 * other: a user who turns incognito on globally has said "do not record anything", and a
 * user who marked one title has said "not this one". Both statements have to hold, so
 * the rule is `OR`, and it is written once here rather than re-derived by every caller.
 *
 * Whoever records reading progress must ask [shouldRecordHistory] first. Nothing in this
 * class can stop a caller that does not ask, which is why it is a single function with
 * an unambiguous name rather than a flag on a settings object.
 */
class IncognitoController(
	private val repository: CurateRepository,
	private val config: CurateConfigStore,
) {

	/** The global switch, for a toggle in the UI. */
	val isGlobalEnabled: Flow<Boolean> = config.data.map { it.incognito }

	val prefs: StateFlow<CuratePrefs> get() = config.data

	val isGlobalEnabledNow: Boolean get() = config.current.incognito

	suspend fun setGlobal(enabled: Boolean) = config.setIncognito(enabled)

	suspend fun isIncognito(mangaId: Long): Boolean =
		config.current.incognito || repository.isIncognito(mangaId)

	suspend fun setForManga(mangaId: Long, incognito: Boolean): Boolean =
		repository.setIncognito(mangaId, incognito)

	/**
	 * Whether a reading position for [mangaId] may be written.
	 *
	 * Reads `manga_prefs` on every call rather than caching. It is a primary-key lookup,
	 * it happens on a page turn and not per frame, and a cache here would be wrong the
	 * moment another screen set the flag: the failure mode of a stale cache is recording
	 * history the user explicitly asked not to record, which is the exact thing this
	 * function exists to prevent.
	 */
	suspend fun shouldRecordHistory(mangaId: Long): Boolean = !isIncognito(mangaId)
}
