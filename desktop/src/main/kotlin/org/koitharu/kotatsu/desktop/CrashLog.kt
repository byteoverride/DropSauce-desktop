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
				// What Skia actually chose, not the override property, which is empty
				// unless somebody set it and so says nothing about what is being used.
				// This is the line that separates "the machine has no GPU and is drawing
				// every frame on two cores" from every other reason to be slow.
				appendLine("renderApi ${renderApi()}")
				// Both, because either one works and reporting only the property said
				// "(none)" for a run that had in fact been forced to software through the
				// environment variable. That is the wrong answer to give somebody who is
				// trying to work out why their machine is slow.
				appendLine(
					"override  " + listOfNotNull(
						System.getProperty("skiko.renderApi")?.let { "-Dskiko.renderApi=$it" },
						System.getenv("SKIKO_RENDER_API")?.let { "SKIKO_RENDER_API=$it" },
					).joinToString(", ").ifEmpty { "(none)" },
				)
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

	/**
	 * The renderer Skia settled on.
	 *
	 * Reflective because reading it eagerly would initialise Skiko before the toolkit is
	 * ready, and because a Skiko version that renames this must degrade to an unknown
	 * line in a log rather than stopping the app from starting.
	 */
	private fun renderApi(): String = try {
		val properties = Class.forName("org.jetbrains.skiko.SkikoProperties")
			.getField("INSTANCE").get(null)
		properties.javaClass.getMethod("getRenderApi").invoke(properties).toString()
	} catch (e: Throwable) {
		"(could not be read: ${e::class.simpleName})"
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
