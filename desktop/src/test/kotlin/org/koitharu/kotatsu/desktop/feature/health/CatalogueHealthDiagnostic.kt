package org.koitharu.kotatsu.desktop.feature.health

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koitharu.kotatsu.desktop.source.SourceRegistry
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * How much of the shipped catalogue is actually alive.
 *
 * A diagnostic, not a test: it asks several hundred third-party sites for their front
 * page, so it runs only with `-Dlive=true`, and it asserts nothing about the answer
 * because the answer is a property of the internet on the day it ran.
 *
 * It exists because "890 usable sources" is a count of parsers that *exist*, and the
 * number a reader cares about is how many *answer*. On this machine's own fifteen
 * selected sources, nine did not.
 *
 * `-Dsample=N` checks the first N instead of all of them.
 */
class CatalogueHealthDiagnostic {

	@Test
	fun `sweep the catalogue and report what is alive`() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
		val sample = System.getProperty("sample")?.toIntOrNull()
		val registry = SourceRegistry()
		val all = SourceRegistry.usableSources.let { if (sample != null) it.take(sample) else it }
		val targets = all.map { registry.healthTarget(it) }
		println("sweeping ${targets.size} sources of ${MangaParserSource.entries.size} in the catalogue")

		val started = System.currentTimeMillis()
		var last: SweepProgress? = null
		runBlocking {
			HealthSweep(concurrency = CONCURRENCY).run(targets).collect { progress ->
				last = progress
				if (progress.done > 0 && progress.done % 50 == 0) {
					println("  ${progress.done}/${progress.total}  alive so far: ${progress.usable}")
				}
			}
		}
		val finished = last ?: return
		val seconds = (System.currentTimeMillis() - started) / 1000

		val byVerdict = finished.reports.groupingBy { it.verdict }.eachCount()
		println()
		println("=== swept ${finished.reports.size} sources in ${seconds}s")
		for (verdict in SourceVerdict.entries) {
			val n = byVerdict[verdict] ?: 0
			if (n == 0) continue
			val share = n * 100 / finished.reports.size
			println("  ${verdict.name.padEnd(13)} $n  ($share%)  ${if (verdict.isUsable) "usable" else ""}")
		}
		println()
		println("  usable: ${finished.usable} of ${finished.reports.size}")
		val trust = trustOf(finished.reports)
		println("  sweep trusted: ${trust.isTrusted} ${trust.note}")

		println()
		println("=== the dead, by reason")
		finished.reports.filterNot { it.verdict.isUsable }
			.groupBy { it.verdict }
			.forEach { (verdict, group) ->
				println("  $verdict (${group.size}): " + group.take(12).joinToString(", ") { it.source })
			}
	}

	/** Higher than the app's four: a diagnostic run by hand, not a background sweep. */
	private companion object {
		const val CONCURRENCY = 8
	}
}
