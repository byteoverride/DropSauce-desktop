package org.koitharu.kotatsu.shared.io

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * [AppPaths] following Windows convention.
 *
 * `%APPDATA%` for anything the user would expect to keep, `%LOCALAPPDATA%` for anything
 * they would not miss. That split is the whole point of having two: roaming data follows
 * a user between machines on a domain, a cache does not and should not.
 *
 * Data and config share one folder, unlike the XDG layout which separates them. That is
 * the Windows convention: one directory per application, holding its database and its
 * settings file together. They cannot collide, being different filenames.
 *
 * [env] and [userHome] are parameters rather than direct [System] reads so the rules can
 * be tested without a Windows machine or a mutated environment.
 */
class WindowsAppPaths(
	private val appDirName: String = DEFAULT_APP_NAME,
	private val env: (String) -> String? = System::getenv,
	private val userHome: String = System.getProperty("user.home")
		?: error("user.home is not set; cannot resolve Windows application directories"),
) : AppPaths {

	override val data: Path by lazy { roaming / appDirName }

	override val config: Path by lazy { roaming / appDirName }

	override val cache: Path by lazy { local / appDirName / "cache" }

	override val localLibrary: Path by lazy { data / "library" }

	/** `%APPDATA%`, or the path it conventionally points at when it is not set. */
	private val roaming: Path
		get() = absoluteEnv("APPDATA") ?: (userHome.toPath() / "AppData" / "Roaming")

	/**
	 * `%LOCALAPPDATA%`, falling back to roaming rather than to the home directory.
	 *
	 * A cache under `%APPDATA%` is untidy; a cache in the user's home is wrong. Given a
	 * machine odd enough to define one and not the other, untidy is the better failure.
	 */
	private val local: Path
		get() = absoluteEnv("LOCALAPPDATA") ?: absoluteEnv("APPDATA") ?: (userHome.toPath() / "AppData" / "Local")

	/**
	 * An environment variable, ignored unless it is an absolute path.
	 *
	 * The same rule the XDG specification imposes, for the same reason: a relative value
	 * would resolve against the working directory, which for a packaged app is wherever
	 * the shortcut happened to start it.
	 */
	private fun absoluteEnv(name: String): Path? =
		env(name)?.takeIf { it.isNotEmpty() }?.toPath()?.takeIf { it.isAbsolute }

	override fun toString(): String =
		"WindowsAppPaths(data=$data, cache=$cache, config=$config, localLibrary=$localLibrary)"

	private companion object {

		/**
		 * Capitalised, unlike the Linux directory name.
		 *
		 * `%APPDATA%` is full of vendor and product names as people write them, and a
		 * lowercase entry among them looks like something that got there by accident.
		 */
		const val DEFAULT_APP_NAME = "DropSauce"
	}
}

/**
 * The right [AppPaths] for the machine this is running on.
 *
 * Windows gets [WindowsAppPaths]; everything else gets [XdgAppPaths]. macOS would
 * properly want `~/Library/Application Support`, and gets the XDG layout instead because
 * nothing packages for it yet. When something does, that is the third branch, not a
 * reason to change these two.
 *
 * A build that already has data in the old place keeps using it. The desktop app shipped
 * for Windows before this existed and wrote to the XDG fallback under the user's home; a
 * reader who installed that would otherwise open the app to an empty library sitting next
 * to a perfectly good database. Nothing is moved, because moving a database while
 * deciding where it lives is the one operation that cannot be half done safely.
 */
fun defaultAppPaths(
	osName: String = System.getProperty("os.name").orEmpty(),
	fileSystem: FileSystem = FileSystem.SYSTEM,
	// Both layouts are parameters so the choice between them can be tested against a
	// temporary directory. Resolving the real ones and then probing the reader's actual
	// home for a database is not something a test should ever do.
	windows: AppPaths = WindowsAppPaths(),
	legacy: AppPaths = XdgAppPaths(),
): AppPaths {
	if (!osName.startsWith("Windows", ignoreCase = true)) {
		return legacy
	}
	if (fileSystem.exists(windows.data / DATABASE_FILE)) {
		return windows
	}
	return if (fileSystem.exists(legacy.data / DATABASE_FILE)) legacy else windows
}

/**
 * The file that decides whether a directory is an existing install.
 *
 * Named here rather than imported from the app: `:shared` cannot depend on `:desktop`,
 * and a directory that exists but holds no database is not somewhere a library lives.
 */
private const val DATABASE_FILE = "library.db"
