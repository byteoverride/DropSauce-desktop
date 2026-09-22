package org.koitharu.kotatsu.shared.settings

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the app picks between light and dark. */
enum class ThemeMode { System, Light, Dark }

/**
 * Reading direction.
 *
 * The desktop reader is webtoon-only, so nothing here selects between these at runtime.
 * The type survives because `manga_prefs.reading_mode` and the backup format both carry
 * it, and the Android app does have paged modes: dropping the value would silently lose
 * an Android user's per-title choice on a backup round trip.
 */
enum class ReadingMode { PagedLtr, PagedRtl, Webtoon }


/**
 * Everything the desktop app remembers between runs, other than the library.
 *
 * Deliberately a small, flat, explicitly-defaulted record rather than a port of the
 * Android `AppSettings`, which is 1501 lines and 205 keys (DECISIONS.md D8). Every field
 * here is one the desktop UI actually reads; adding a key that nothing consumes is how
 * that god object grew.
 *
 * Defaults are on the properties so an older config file missing a key still loads.
 */
@Serializable
data class SettingsData(
	/** Dark by default: this is a reader, and it is what the user asked for. */
	@SerialName("theme") val theme: ThemeMode = ThemeMode.Dark,
	/**
	 * Multiplier applied to the whole interface: text, icons, spacing, everything.
	 *
	 * Defaults above 1 because Compose for Desktop takes its density from the toolkit,
	 * and on a high resolution Linux display that commonly reports 1.0 regardless of the
	 * physical pixel density. The result is a correct layout rendered far too small. This
	 * is a user-facing control rather than a detected value because no reliable signal
	 * distinguishes "large display" from "high density display" on X11.
	 */
	@SerialName("ui_scale") val uiScale: Float = 1.5f,
	/**
	 * How wide a webtoon strip is drawn, as a percentage of the window.
	 *
	 * Webtoon pages are tall and narrow, so filling the window width on a wide display
	 * makes every panel enormous and forces the reader to scroll far more than they
	 * should. 60 is a readable default on a full-width window; a narrow window can be
	 * put back to 100.
	 */
	@SerialName("webtoon_width_percent") val webtoonWidthPercent: Int = 60,
	/**
	 * Hide adult sources from the catalogue.
	 *
	 * On by default. A large share of the 890 usable sources are adult, and a reader
	 * opened for the first time should not lead with them.
	 */
	@SerialName("hide_adult_sources") val hideAdultSources: Boolean = true,
	/** Sources whose names contain this are also hidden, for anything else unwanted. */
	@SerialName("hidden_source_terms") val hiddenSourceTerms: List<String> = emptyList(),
	/** Covers and pages held in memory. Higher is smoother and uses more RAM. */
	@SerialName("image_cache_entries") val imageCacheEntries: Int = 300,
	/**
	 * How much decoded image data to keep, in megabytes, or null to size it from the
	 * machine.
	 *
	 * The count above is not a memory bound and never was: a cover decodes to about 4 MB
	 * and a webtoon page to as much as 40, so the same three hundred entries is one
	 * gigabyte of one and thirteen of the other. Kept because it still caps how many
	 * separate images are tracked; this is the bound that matters.
	 */
	@SerialName("image_cache_mb") val imageCacheMegabytes: Int? = null,
	/** How many times to re-request a page image before giving up on it. */
	@SerialName("page_attempts") val pageAttempts: Int = 3,
	@SerialName("user_agent") val userAgent: String = DEFAULT_USER_AGENT,
	/**
	 * Where downloaded chapters are written, or null for the default under the app's own
	 * data directory.
	 *
	 * A reader with a large library wants this on the drive that has room for it, which
	 * is rarely the one holding a dotfile directory. Stored as the folder itself rather
	 * than a parent to create "downloads" inside: the reader picked a folder and that is
	 * where their files should be.
	 *
	 * Changing it never moves or forgets anything already downloaded. Each download
	 * records where its pages went, and reads follow that, so old chapters keep working
	 * from the old place and new ones go to the new one.
	 */
	@SerialName("download_dir") val downloadDir: String? = null,
) {

	companion object {

		/**
		 * A current desktop Firefox string. Sources fingerprint the UA, and the parsers
		 * library's own defaults lean mobile, which some sources answer with a different
		 * layout than the parser expects.
		 */
		const val DEFAULT_USER_AGENT =
			"Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
	}
}

/**
 * Reads and writes [SettingsData].
 *
 * An interface so shared code never touches a file path or a platform preference API
 * (DECISIONS.md D9). [data] is hot: the UI collects it and updates apply immediately.
 */
interface SettingsStore {

	val data: StateFlow<SettingsData>

	/** Applies [transform] to the current value and persists the result. */
	suspend fun update(transform: (SettingsData) -> SettingsData)

	/** Restores every field to its default. */
	suspend fun reset() = update { SettingsData() }
}
