package org.koitharu.kotatsu.desktop.feature.appupdate

/**
 * Which package file this machine can actually install.
 *
 * The update check looked for a `.deb` whatever it was running on, so a Windows reader was
 * shown the Linux package: a file Windows has no idea what to do with, behind a button
 * saying Download, followed by an instruction to run `sudo dpkg -i`. One hardcoded suffix,
 * three wrong things on screen.
 */
enum class HostPackage(val suffix: String) {

	DEB(".deb"),
	MSI(".msi"),
	;

	/** What to tell somebody who has just downloaded [fileName]. */
	fun installHint(fileName: String): String = when (this) {
		DEB -> "Install it with: sudo dpkg -i $fileName"
		MSI -> "Run $fileName and follow the installer."
	}

	companion object {

		/**
		 * Windows gets the installer, everything else the Debian package.
		 *
		 * Decided the same way `defaultAppPaths` decides it, that being the only other
		 * place in the app that cares what it is running on. There is no macOS packaging,
		 * so a Mac is offered the `.deb`; that is not a good answer but it is an honest
		 * one, because there is no other package to offer.
		 */
		fun forHost(osName: String = System.getProperty("os.name").orEmpty()): HostPackage =
			if (osName.startsWith("Windows", ignoreCase = true)) MSI else DEB

		/** Whether [fileName] is a package for any platform, rather than for this one. */
		fun isPackage(fileName: String): Boolean = entries.any { fileName.endsWith(it.suffix) }
	}
}
