package org.koitharu.kotatsu.desktop.ui

/**
 * Every icon the app draws, by its Material Symbols name.
 *
 * Constants rather than string literals at call sites, so a typo is a compile error
 * instead of a crash the first time a screen is opened. `AppIconsTest` resolves every
 * name in [all] against the classpath, so a file dropped from the jar fails the build
 * rather than the app.
 *
 * What these replace: eighteen unicode glyphs drawn as `Text`, from four typographic
 * families, with three collisions. `U+2913` was the icon for Local, Import *and*
 * Backup; `U+2605` was Library, Bookmarks *and* the reader's saved state; `U+25A6` was
 * Sources *and* Statistics. One of them, `U+2B07`, sits in an emoji-presentation block
 * and so rendered as a colour emoji at a different metric from its neighbours.
 *
 * The files are vendored Material Symbols Rounded, weight 400, grade 0, optical size
 * 24, so they share a stroke weight. See `icons/NOTICE.md` for the licence and for why
 * they are vendored rather than depended on.
 */
object AppIcons {

	// Navigation rail.
	const val LIBRARY = "collections_bookmark"
	const val SOURCES = "public"
	const val HISTORY = "history"
	const val SETTINGS = "settings"
	const val LOCAL = "folder"
	const val DISCOVER = "search"
	const val SUGGESTIONS = "auto_awesome"
	const val DOWNLOADS = "download"
	const val BOOKMARKS = "bookmark"

	/**
	 * Updates is `new_releases`, not `refresh`.
	 *
	 * The destination is "titles with chapters you have not seen", which is a result.
	 * `refresh` is the action that produces it, and is needed separately below.
	 */
	const val UPDATES = "new_releases"
	const val APP_UPDATE = "upgrade"

	// Settings tool rows. Never selected, so no filled variant is vendored.
	const val BACKUP = "settings_backup_restore"
	const val ORGANISE = "category"
	const val IMPORT = "drive_folder_upload"
	const val FIX_SOURCES = "build"
	const val READER_PREFS = "tune"
	const val STATS = "bar_chart"
	const val TRACKING = "link"

	// Controls.
	const val BACK = "arrow_back"
	const val CHEVRON_LEFT = "chevron_left"
	const val CHEVRON_RIGHT = "chevron_right"
	const val EXPAND_MORE = "expand_more"
	const val FILTER = "filter_list"
	const val SORT = "sort"
	const val REFRESH = "refresh"
	const val CLOSE = "close"
	const val ADD = "add"
	const val EDIT = "edit"
	const val DELETE = "delete"
	const val PLAY = "play_arrow"
	const val MORE = "more_vert"
	const val CHECK = "check"

	/**
	 * These two are not decoration.
	 *
	 * The library's "cannot be opened" bar and the shared error box both signalled "this
	 * is a problem" with `colorScheme.error` and nothing else, which is a colour-only
	 * cue and fails WCAG 1.4.1. An icon beside the text is the cheapest fix there is.
	 */
	const val WARNING = "warning"
	const val ERROR = "error"

	const val EMPTY = "inbox"

	/** Every name above. Enumerated by `AppIconsTest`, which resolves each one. */
	val all: List<String> = listOf(
		LIBRARY, SOURCES, HISTORY, SETTINGS, LOCAL, DISCOVER, SUGGESTIONS, DOWNLOADS,
		BOOKMARKS, UPDATES, APP_UPDATE,
		BACKUP, ORGANISE, IMPORT, FIX_SOURCES, READER_PREFS, STATS, TRACKING,
		BACK, CHEVRON_LEFT, CHEVRON_RIGHT, EXPAND_MORE, FILTER, SORT, REFRESH, CLOSE,
		ADD, EDIT, DELETE, PLAY, MORE, CHECK, WARNING, ERROR, EMPTY,
	)

	/**
	 * The names that have a genuinely different FILL 1 variant vendored.
	 *
	 * Measured, not assumed: Material publishes a filled variant for every one of the
	 * eleven rail icons, but for `public`, `history`, `search`, `download` and
	 * `upgrade` the file is **byte-identical** to the outlined one, because those
	 * glyphs are all stroke and the FILL axis has nothing to fill. Vendoring those five
	 * would be five duplicate files backing a selected state that cannot render, so
	 * they are not vendored and [filePath] falls back to the outlined file.
	 *
	 * This is why the selected state in the rail does not rest on the icon swapping.
	 * The 1 dp `outline` stroke on the indicator pill is what carries it (5.17:1 dark,
	 * 3.84:1 light); the fill swap, where it exists, is reinforcement.
	 */
	val withFilledVariant: Set<String> = setOf(
		LIBRARY, SETTINGS, LOCAL, SUGGESTIONS, BOOKMARKS, UPDATES,
	)

	/** The classpath resource for [name], honouring [filled] only where one exists. */
	fun filePath(name: String, filled: Boolean = false): String =
		if (filled && name in withFilledVariant) "icons/ic_${name}_fill.svg" else "icons/ic_$name.svg"
}
