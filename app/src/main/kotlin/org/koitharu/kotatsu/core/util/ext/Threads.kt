package org.koitharu.kotatsu.core.util.ext

import android.os.Looper
import org.koitharu.kotatsu.BuildConfig

/**
 * Fails if called from the Android main thread.
 *
 * Android-only and deliberately left in `:app`: both call sites are in `NetworkModule`,
 * which cannot leave Android either. Previously this lived in the `debug`/`release`
 * source sets alongside [printStackTraceDebug]; those are gone, so the build-type switch
 * is now an explicit `BuildConfig.DEBUG` check. Behaviour is unchanged, the check is
 * debug-only.
 */
fun assertNotInMainThread() {
	if (BuildConfig.DEBUG) {
		check(Looper.myLooper() != Looper.getMainLooper()) {
			"Calling this from the main thread is prohibited"
		}
	}
}
