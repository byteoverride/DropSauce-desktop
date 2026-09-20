package org.koitharu.kotatsu.core.util.ext

/**
 * Debug-only diagnostics, shared by every platform.
 *
 * This used to be two files, `src/debug/.../Debug.kt` and `src/release/.../Debug.kt`,
 * selected by Android build type. That works only for an Android application module: a
 * Kotlin Multiplatform target has no `debug`/`release` source sets, so all 206 call sites
 * across 87 files would have failed to resolve the moment any of them moved to `:shared`,
 * and no import scan would have predicted it because the name resolves by source set.
 *
 * The build-type switch is therefore replaced by [DebugFlags.isDebug], set once at
 * startup by each platform's entry point. Behaviour is unchanged: debug builds print,
 * release builds do not.
 *
 * The package is deliberately the same one the Android sources already use, so none of
 * the call sites needed editing.
 */
object DebugFlags {

	/**
	 * Whether this is a debug build. Android sets it from `BuildConfig.DEBUG`; the desktop
	 * app sets it from its own launch configuration.
	 *
	 * Volatile because it is written once on the main thread at startup and read from
	 * background threads thereafter.
	 */
	@Volatile
	@JvmStatic
	var isDebug: Boolean = false
}

/**
 * Prints this throwable's stack trace in debug builds, and does nothing otherwise.
 *
 * Used for exceptions that are genuinely expected and handled, where the stack trace is
 * useful while developing and only noise in a shipped build.
 */
fun Throwable.printStackTraceDebug() {
	if (DebugFlags.isDebug) {
		printStackTrace()
	}
}
