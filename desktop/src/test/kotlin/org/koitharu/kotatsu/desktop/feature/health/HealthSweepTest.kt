package org.koitharu.kotatsu.desktop.feature.health

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.nio.file.Files

/**
 * The health check, and the two rules that stop it doing harm.
 *
 * The feature's whole job is to remove sources from the catalogue, so every test here is
 * really asking the same question: can this delete something a reader still wants? The
 * two answers that matter are the trust rule, which refuses to believe a sweep taken
 * while the connection was down, and the strike rule, which refuses to write a source off
 * for one bad afternoon.
 */
class HealthSweepTest {

	private fun report(source: String, verdict: SourceVerdict) = SourceReport(source, verdict)

	// --- classification, against the failures this catalogue actually produces ---

	@Test
	fun `a dead domain, a dead certificate and a timeout are three different things`() {
		assertEquals(SourceVerdict.Gone, classify(UnknownHostException("mgjinx.com")).first)
		assertEquals(SourceVerdict.Untrusted, classify(SSLHandshakeException("certificate_unknown")).first)
		assertEquals(SourceVerdict.Unreachable, classify(SocketTimeoutException("timeout")).first)
	}

	@Test
	fun `a source that answers an HTTP error is refused, with the code kept`() {
		// The shape the library actually throws, seen from NINEMANGA_EN: a jsoup status
		// exception whose message carries the code and nothing else useful.
		val (verdict, reason) = classify(IOException("org.jsoup.HttpStatusException . Status=530, URL=[https://x]"))
		assertEquals(SourceVerdict.Refused, verdict)
		assertTrue("the code belongs in the reason, was '$reason'", reason.contains("530"))
	}

	@Test
	fun `a number that is not a status is not read as one`() {
		// Otherwise "connection reset after 404 bytes" becomes a 404.
		val (verdict, _) = classify(IOException("connection reset after 404 bytes"))
		assertEquals(SourceVerdict.Unreachable, verdict)
	}

	@OptIn(InternalParsersApi::class)
	@Test
	fun `a site that answers but cannot be parsed has changed, not died`() {
		assertEquals(SourceVerdict.Changed, classify(ParseException("Can't fetch data", "https://x")).first)
	}

	@OptIn(InternalParsersApi::class)
	@Test
	fun `being rate limited or asked to log in is not ill health`() {
		assertTrue(classify(TooManyRequestExceptions("https://x", 0L)).first.isUsable)
		assertTrue(classify(AuthRequiredException(MangaParserSource.MANGADEX)).first.isUsable)
	}

	@Test
	fun `only a dead name is conclusive on its own`() {
		assertTrue(SourceVerdict.Gone.isConclusive)
		for (other in SourceVerdict.entries - SourceVerdict.Gone) {
			assertFalse("$other must not be conclusive", other.isConclusive)
		}
	}

	// --- the trust rule: is it them, or is it us? ---

	@Test
	fun `a sweep where half the catalogue lost its DNS is not believed`() {
		val reports = List(20) { report("s$it", if (it < 10) SourceVerdict.Gone else SourceVerdict.Ok) }

		val trust = trustOf(reports)

		assertFalse(trust.isTrusted)
		assertTrue("the note should say what was wrong, was '${trust.note}'", trust.note.contains("DNS"))
	}

	@Test
	fun `a sweep where nothing could be reached at all is not believed`() {
		val reports = List(20) {
			report("s$it", if (it % 2 == 0) SourceVerdict.Unreachable else SourceVerdict.Untrusted)
		}

		assertFalse(trustOf(reports).isTrusted)
	}

	@Test
	fun `a sweep where sources answered badly IS believed`() {
		// Every one of these failed, and every one of them proves the network works: the
		// packets arrived and something answered. This is the case that must NOT be
		// thrown away, or a catalogue full of genuinely broken sources is never cleaned.
		val reports = List(20) {
			report("s$it", if (it % 2 == 0) SourceVerdict.Refused else SourceVerdict.Changed)
		}

		assertTrue(trustOf(reports).isTrusted)
	}

	@Test
	fun `a handful of sources is too few to judge the network by`() {
		val reports = List(4) { report("s$it", SourceVerdict.Gone) }

		assertTrue("a tiny sweep must not be able to declare the network down", trustOf(reports).isTrusted)
	}

	// --- the strike rule: one failure is not death ---

	@Test
	fun `one failure does not hide a source, two in a row does`() = runTest {
		val store = newStore()
		val down = listOf(report("ATSUMARU", SourceVerdict.Unreachable))

		store.record(down, SweepTrust(true, ""))
		assertFalse("one timeout must not cost a source its place", store.isDead("ATSUMARU"))

		store.record(down, SweepTrust(true, ""))
		assertTrue("two checks agreeing is a pattern", store.isDead("ATSUMARU"))
	}

	@Test
	fun `a source that comes back is forgiven immediately`() = runTest {
		val store = newStore()
		store.record(listOf(report("ATSUMARU", SourceVerdict.Unreachable)), SweepTrust(true, ""))
		store.record(listOf(report("ATSUMARU", SourceVerdict.Unreachable)), SweepTrust(true, ""))
		assertTrue(store.isDead("ATSUMARU"))

		store.record(listOf(report("ATSUMARU", SourceVerdict.Ok)), SweepTrust(true, ""))

		assertFalse("a source that answers is working now", store.isDead("ATSUMARU"))
		assertEquals(0, store.stateOf("ATSUMARU")?.strikes)
	}

	@Test
	fun `a dead name is hidden on the first observation`() = runTest {
		val store = newStore()

		store.record(listOf(report("MANGAJINX", SourceVerdict.Gone)), SweepTrust(true, ""))

		assertTrue("a name that no longer resolves is not coming back", store.isDead("MANGAJINX"))
	}

	@Test
	fun `a sweep that is not trusted records nothing at all`() = runTest {
		val store = newStore()
		val everythingGone = List(20) { report("s$it", SourceVerdict.Gone) }

		store.record(everythingGone, trustOf(everythingGone))

		assertEquals("not one verdict may survive a sweep taken on a dead connection", 0, store.deadCount)
		assertFalse(store.isDead("s0"))
	}

	@Test
	fun `a reader can overrule a verdict`() = runTest {
		val store = newStore()
		store.record(listOf(report("MANGAJINX", SourceVerdict.Gone)), SweepTrust(true, ""))

		store.forget("MANGAJINX")

		assertFalse(store.isDead("MANGAJINX"))
	}

	// --- the engine ---

	@Test
	fun `every source is checked and the progress reaches the end`() = runTest {
		val targets = (1..9).map { n ->
			HealthTarget("S$n", "Source $n") { SourceReport("S$n", SourceVerdict.Ok, items = 1) }
		}

		val states = HealthSweep(concurrency = 3).run(targets).toList()

		val last = states.last()
		assertTrue(last.isFinished)
		assertEquals(9, last.done)
		assertEquals(9, last.reports.size)
		assertEquals(9, last.usable)
	}

	@Test
	fun `a probe that throws costs its own source and nothing else`() = runTest {
		val targets = listOf(
			HealthTarget("A", "A") { SourceReport("A", SourceVerdict.Ok, items = 1) },
			HealthTarget("B", "B") { error("the probe itself blew up") },
			HealthTarget("C", "C") { SourceReport("C", SourceVerdict.Ok, items = 1) },
		)

		val last = HealthSweep(concurrency = 2).run(targets).toList().last()

		assertEquals(3, last.reports.size)
		assertEquals(2, last.usable)
	}

	@Test
	fun `an empty catalogue finishes rather than hanging`() = runTest {
		val states = HealthSweep().run(emptyList()).toList()

		assertEquals(1, states.size)
		assertTrue(states.single().isFinished)
	}

	private fun newStore(): HealthStore {
		// A real temp directory, as DiscoverStoreTest uses: okio's fake filesystem is
		// not on this module's test classpath and is not worth a dependency for one file.
		val directory = Files.createTempDirectory("health-test").toString().toPath()
		return HealthStore(directory.resolve("health.json"), FileSystem.SYSTEM)
	}
}
