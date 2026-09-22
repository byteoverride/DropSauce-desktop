package org.koitharu.kotatsu.desktop

import okio.Path
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A file the app writes its failures to.
 *
 * Exists because there is otherwise no way to find out why a packaged build misbehaved.
 * A jpackage app on Windows is a GUI subsystem executable with no console attached, so
 * anything printed to stderr goes nowhere at all; on Linux it goes to a terminal only if
 * somebody happened to start it from one. A reader saying "it crashes a lot" has nothing
 * they can send, and the developer has nothing to read.
 *
 * Deliberately plain: an appended text file next to the database, no rotation beyond a
 * size cap, no upload anywhere. It is written for a person to open and paste.
 */
class CrashLog(private val file: Path) {

	/**
	 * Records the uncaught exceptions of every thread, and what the app was running on.
	 *
	 * The environment lines are not padding. Most of what makes a desktop build fail on
	 * one machine and not another is in them: how much memory the JVM gave itself, which
	 * renderer Skia chose, and whether this is the platform the build was tested on.
	 */
	fun install(version: String) {
		append(
			buildString {
				appendLine("--- started ${timestamp()} ---")
				appendLine("version   $version")
				appendLine("os        ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
				appendLine("java      ${System.getProperty("java.version")}")
				appendLine("maxHeap   ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB")
				appendLine("cpus      ${Runtime.getRuntime().availableProcessors()}")
				// Empty unless someone has overridden it, which is itself worth knowing.
				appendLine("renderApi ${System.getProperty("skiko.renderApi") ?: "(default)"}")
			},
		)
		val existing = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			append("--- uncaught on ${thread.name} at ${timestamp()} ---\n" + stackTrace(error))
			// Chained rather than replaced: the JVM's own handler is what still prints to
			// stderr for anyone who did start this from a terminal.
			existing?.uncaughtException(thread, error)
		}
	}

	/** Records something the app handled but a reader might still be asking about. */
	fun note(message: String) = append("${timestamp()}  $message\n")

	private fun append(text: String) {
		try {
			val target = file.toFile()
			target.parentFile?.mkdirs()
			// A log that grows without limit is its own bug report. Truncating from the
			// front would be kinder and needs the whole file in memory; starting over is
			// enough for something only read after a failure.
			if (target.length() > MAX_BYTES) {
				target.writeText("--- truncated at ${timestamp()}, the log had passed ${MAX_BYTES / 1024} KB ---\n")
			}
			target.appendText(text)
		} catch (e: Throwable) {
			// Never let logging be the thing that brings the app down. If the disk is
			// full or the directory is not writable, that is already the larger problem.
		}
	}

	private fun stackTrace(error: Throwable): String = StringWriter()
		.also { writer -> PrintWriter(writer).use(error::printStackTrace) }
		.toString()

	private fun timestamp(): String =
		SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

	private companion object {

		const val MAX_BYTES = 512L * 1024
	}
}
