package org.koitharu.kotatsu.desktop.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Retry buttons that cannot retry.
 *
 * A browse screen shipped with `onRetry = { submitted = submitted }`. Assigning a
 * `mutableStateOf` its own value is not a change, so no `remember` key was invalidated
 * and no `LaunchedEffect` re-ran: the button was present, looked enabled, and did
 * nothing at all. Nothing else would have caught it, because it compiles, it renders,
 * and the only symptom is a button that silently does not work.
 *
 * Reading the source is a blunt instrument and the right one here: the defect is a shape,
 * it is invisible to a compiler, and driving Compose UI in this project's test setup
 * would cost far more than it is worth for one line.
 */
class RetryKeyTest {

	@Test
	fun `no retry handler assigns a state value to itself`() {
		val offenders = mutableListOf<String>()
		sourceFiles().forEach { file ->
			file.readLines().forEachIndexed { index, line ->
				val match = SELF_ASSIGNMENT.find(line) ?: return@forEachIndexed
				val name = match.groupValues[1]
				offenders += "${file.name}:${index + 1}  { $name = $name }"
			}
		}
		if (offenders.isNotEmpty()) {
			fail("a retry that cannot retry:\n" + offenders.joinToString("\n"))
		}
	}

	@Test
	fun `the sweep actually looks at the screens`() {
		// A positive control. A pattern that matches nothing because the glob is wrong
		// would pass the test above for entirely the wrong reason.
		val files = sourceFiles()
		assertTrue(files.size > 10, "only found ${files.size} source files to scan")
		assertTrue(
			files.any { it.name == "BrowseScreen.kt" },
			"the file the bug was found in is not being scanned",
		)
		assertTrue(
			SELF_ASSIGNMENT.containsMatchIn("onRetry = { submitted = submitted },"),
			"the pattern no longer matches the shape it was written for",
		)
		assertTrue(
			!SELF_ASSIGNMENT.containsMatchIn("DownloadEntity(total = total, done = done)"),
			"the pattern flags ordinary named arguments, which would make it useless noise",
		)
	}

	private fun sourceFiles(): List<File> =
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

	private companion object {

		/**
		 * A lambda whose entire body is `x = x`.
		 *
		 * Deliberately not a bare `x = x`, which also matches a named argument passed
		 * the variable of the same name, and those are everywhere and perfectly fine. A
		 * handler that does nothing but assign a value to itself is the actual defect
		 * and has no innocent reading.
		 */
		val SELF_ASSIGNMENT = Regex("""\{\s*(\w+)\s*=\s*\1\s*\}""")
	}
}
