package org.koitharu.kotatsu.desktop.feature.appupdate

import java.util.Locale
import java.util.Properties

/**
 * The version this build is, read from the classpath.
 *
 * Written by the `generateVersionResource` task from the single `appVersion` in
 * `desktop/build.gradle.kts`, so the number the update check compares against is the same
 * one the package carries. Falls back to [UNKNOWN] rather than throwing: a missing
 * resource should make the app decline to claim an update, not fail to start.
 */
object AppBuild {

	const val UNKNOWN = "0.0.0"

	val version: String by lazy {
		runCatching {
			AppBuild::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
				Properties().apply { load(stream) }.getProperty("version")
			}
		}.getOrNull()?.takeIf { it.isNotBlank() } ?: UNKNOWN
	}

	private const val RESOURCE = "/dropsauce-version.properties"
}

/**
 * A comparable version.
 *
 * A deliberate copy of the Android app's `core/github/VersionId`, including the ordering
 * of pre-release variants, so the two front ends of the same project cannot disagree
 * about which of two releases is newer. Comparing version strings directly is the bug
 * this exists to avoid: "0.9.10" sorts before "0.9.9" as text and after it as a version,
 * which is exactly the point at which an update check quietly stops offering updates.
 */
data class VersionId(
	val major: Int,
	val minor: Int,
	val build: Int,
	val variantType: String,
	val variantNumber: Int,
) : Comparable<VersionId> {

	override fun compareTo(other: VersionId): Int {
		var diff = major.compareTo(other.major)
		if (diff != 0) return diff
		diff = minor.compareTo(other.minor)
		if (diff != 0) return diff
		diff = build.compareTo(other.build)
		if (diff != 0) return diff
		diff = variantWeight(variantType).compareTo(variantWeight(other.variantType))
		if (diff != 0) return diff
		return variantNumber.compareTo(other.variantNumber)
	}

	/** A release with no suffix outranks any pre-release of the same number. */
	private fun variantWeight(variantType: String) = when (variantType.lowercase(Locale.ROOT)) {
		"a", "alpha" -> 1
		"b", "beta" -> 2
		"rc" -> 4
		"" -> 8
		else -> 0
	}
}

val VersionId.isStable: Boolean get() = variantType.isEmpty()

/** Parses `1.2.3`, `1.2.3-rc2`, and the `desktop-v` prefixed tags releases are cut with. */
fun VersionId(versionName: String): VersionId {
	val cleaned = versionName.trim()
		.removePrefix(DESKTOP_TAG_PREFIX)
		.removePrefix("v")
	val parts = cleaned.substringBeforeLast('-').split('.')
	val variant = cleaned.substringAfterLast('-', "")
	return VersionId(
		major = parts.getOrNull(0)?.toIntOrNull() ?: 0,
		minor = parts.getOrNull(1)?.toIntOrNull() ?: 0,
		build = parts.getOrNull(2)?.toIntOrNull() ?: 0,
		variantType = variant.filter(Char::isLetter),
		variantNumber = variant.filter(Char::isDigit).toIntOrNull() ?: 0,
	)
}

/**
 * Tags for the desktop build.
 *
 * The repository also carries the Android project's tags, so a release is only ours if it
 * is named this way. Without the check the desktop app would offer an Android APK release
 * as its own update.
 */
const val DESKTOP_TAG_PREFIX = "desktop-v"
