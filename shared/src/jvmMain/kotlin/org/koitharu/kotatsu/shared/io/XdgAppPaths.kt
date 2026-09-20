package org.koitharu.kotatsu.shared.io

import okio.Path
import okio.Path.Companion.toPath

/**
 * [AppPaths] following the XDG Base Directory Specification, which is what a Linux
 * desktop app is expected to do.
 *
 * `$XDG_DATA_HOME`, `$XDG_CONFIG_HOME` and `$XDG_CACHE_HOME` are honoured when set,
 * falling back to `~/.local/share`, `~/.config` and `~/.cache`. Per the specification a
 * value that is not an absolute path is invalid and must be ignored, so a relative
 * setting falls back rather than resolving against the working directory.
 *
 * [env] and [userHome] are parameters rather than direct [System] reads so the rules
 * above can be tested without mutating the real environment.
 */
class XdgAppPaths(
	private val appDirName: String = DEFAULT_APP_DIR_NAME,
	private val env: (String) -> String? = System::getenv,
	private val userHome: String = System.getProperty("user.home")
		?: error("user.home is not set; cannot resolve XDG base directories"),
) : AppPaths {

	override val data: Path by lazy { baseDir("XDG_DATA_HOME", ".local/share") / appDirName }

	override val cache: Path by lazy { baseDir("XDG_CACHE_HOME", ".cache") / appDirName }

	override val config: Path by lazy { baseDir("XDG_CONFIG_HOME", ".config") / appDirName }

	/**
	 * Library content is bulk user data, not app state, so it sits under the data dir
	 * rather than beside the database. A user who wants it elsewhere overrides the
	 * storage directory in settings; this is only the default.
	 */
	override val localLibrary: Path by lazy { data / "library" }

	private fun baseDir(variable: String, homeRelativeFallback: String): Path {
		val fromEnv = env(variable)?.takeIf { it.isNotEmpty() }?.toPath()
		// The specification requires absolute paths; anything else is to be ignored.
		return if (fromEnv != null && fromEnv.isAbsolute) {
			fromEnv
		} else {
			userHome.toPath() / homeRelativeFallback
		}
	}

	override fun toString(): String =
		"XdgAppPaths(data=$data, cache=$cache, config=$config, localLibrary=$localLibrary)"

	companion object {

		const val DEFAULT_APP_DIR_NAME = "dropsauce"
	}
}
