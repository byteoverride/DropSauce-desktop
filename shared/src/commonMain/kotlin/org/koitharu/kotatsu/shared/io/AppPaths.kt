package org.koitharu.kotatsu.shared.io

import okio.FileSystem
import okio.Path

/**
 * Where the app is allowed to put things on the host.
 *
 * This is the replacement for the `Context` that the Android code threads through
 * everything to reach `filesDir`, `cacheDir` and `externalCacheDir`. Shared code takes
 * this instead, so it never needs a platform type. See DECISIONS.md D9.
 *
 * Implementations must return absolute paths and must not create the directories as a
 * side effect of reading a property; call [ensureDirectories] for that, at a point where
 * failing is meaningful.
 */
interface AppPaths {

	/** Persistent user data that must survive restarts: the database, installed plugins. */
	val data: Path

	/** Discardable. Deleting any of this while the app is stopped must be harmless. */
	val cache: Path

	/** User settings. */
	val config: Path

	/** Default location for the local manga library (CBZ archives and extracted folders). */
	val localLibrary: Path

	/**
	 * Creates every directory above if it does not already exist.
	 *
	 * Separate from the properties so that path resolution stays cheap and total, and so
	 * that an I/O failure surfaces once, at startup, instead of at an arbitrary later read.
	 */
	fun ensureDirectories(fileSystem: FileSystem = FileSystem.SYSTEM) {
		for (dir in listOf(data, cache, config, localLibrary)) {
			fileSystem.createDirectories(dir, mustCreate = false)
		}
	}
}
