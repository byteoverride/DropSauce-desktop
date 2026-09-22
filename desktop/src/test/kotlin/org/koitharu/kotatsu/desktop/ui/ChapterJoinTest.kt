package org.koitharu.kotatsu.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When the strip decides to pull in the next chapter.
 *
 * The reported bug is that it sometimes does not, and reading the reader did not find a
 * cause, so this pins the decision itself: the rules that were only ever expressed as a
 * run of early returns inside a collector, where nothing could look at them.
 *
 * The rule that matters most is the last one. A reader who presses the button has already
 * watched the automatic join fail, so the button must not be gated on the same condition.
 */
class ChapterJoinTest {

	@Test
	fun `nothing is pulled in before the first chapter has arrived`() {
		assertTrue(!shouldJoin(demand(index = 0, loaded = 0, through = -1)))
	}

	@Test
	fun `nothing is pulled in on the last chapter`() {
		assertTrue(!shouldJoin(demand(index = 9, loaded = 10, through = 4), lastChapter = 4))
	}

	@Test
	fun `nothing is pulled in while there is still plenty ahead`() {
		assertTrue(!shouldJoin(demand(index = 2, loaded = 40, through = 0)))
	}

	@Test
	fun `the join starts a few pages before the end, not at it`() {
		// Three from the end, so the next chapter is there by the time it is reached.
		assertTrue(shouldJoin(demand(index = 7, loaded = 10, through = 0)))
		assertTrue(!shouldJoin(demand(index = 6, loaded = 10, through = 0)))
	}

	@Test
	fun `a short chapter joins immediately, having no room to wait`() {
		assertTrue(shouldJoin(demand(index = 0, loaded = 2, through = 0)))
	}

	@Test
	fun `a failed join is not retried on every page that scrolls past`() {
		assertTrue(!shouldJoin(demand(index = 9, loaded = 10, through = 0), failed = true))
	}

	// The button. It exists because the automatic join did not happen, so gating it on the
	// condition that already failed would make it do nothing, which is the bug again.
	@Test
	fun `an explicit request ignores where the reader is scrolled to`() {
		assertTrue(shouldJoin(demand(index = 0, loaded = 40, through = 0, attempt = 1), served = 0))
	}

	@Test
	fun `an explicit request clears a previous failure`() {
		assertTrue(shouldJoin(demand(index = 0, loaded = 40, through = 0, attempt = 1), served = 0, failed = true))
	}

	@Test
	fun `a request is served once, not on every later scroll`() {
		val after = demand(index = 1, loaded = 40, through = 0, attempt = 1)
		assertTrue(!shouldJoin(after, served = 1), "the same attempt was served twice")
	}

	@Test
	fun `even an explicit request stops at the last chapter`() {
		assertTrue(!shouldJoin(demand(index = 0, loaded = 10, through = 4, attempt = 1), served = 0, lastChapter = 4))
	}

	private fun demand(index: Int, loaded: Int, through: Int, attempt: Int = 0) =
		Demand(index, loaded, through, attempt)

	private data class Demand(val index: Int, val loaded: Int, val through: Int, val attempt: Int)

    /**
     * The collector's rules, in one place.
     *
     * Kept in step with `ReaderScreen` by hand, which is the trade for testing a decision
     * that otherwise only exists inside a Compose effect. If the two drift, the test is
     * the one that is wrong.
     */
	private fun shouldJoin(
		d: Demand,
		served: Int = 0,
		failed: Boolean = false,
		lastChapter: Int = 99,
		prefetch: Int = 3,
	): Boolean {
		if (d.loaded == 0) return false
		if (d.through >= lastChapter) return false
		val asked = d.attempt != served
		if (!asked && d.index < d.loaded - prefetch) return false
		if (!asked && failed) return false
		return true
	}

	@Test
	fun `the rules here match the ones the reader ships`() {
		// A control. These rules are a copy, so the copy has to be checked against the
		// original rather than merely existing.
		val source = java.io.File("src/main/kotlin/org/koitharu/kotatsu/desktop/ui/ReaderScreen.kt")
			.readText()
		listOf(
			"if (demand.loaded == 0) return@collect",
			"if (demand.through >= chapters.lastIndex) return@collect",
			"val asked = demand.attempt != servedAttempt",
			"if (!asked && demand.index < demand.loaded - CHAPTER_PREFETCH_PAGES) return@collect",
			"if (!asked && appendError != null) return@collect",
		).forEach { rule ->
			assertTrue(source.contains(rule), "the reader no longer contains: $rule")
		}
		assertEquals(3, Regex("""CHAPTER_PREFETCH_PAGES = (\d+)""").find(source)!!.groupValues[1].toInt())
	}
}
