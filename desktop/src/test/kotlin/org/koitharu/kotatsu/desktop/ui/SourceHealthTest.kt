package org.koitharu.kotatsu.desktop.ui

import org.koitharu.kotatsu.parsers.model.MangaParserSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A stored source name is not the same thing as a source you can read.
 *
 * Both failures were invisible in the library before: a name with no parser behind it
 * showed a marker but the card did nothing when clicked, and a source the catalogue flags
 * broken looked completely healthy until the reader came up empty.
 */
class SourceHealthTest {

	@Test
	fun `a working catalogue source is fine`() {
		val working = MangaParserSource.entries.first { !it.isBroken }
		assertEquals(SourceHealth.Ok, sourceHealth(working.name))
	}

	@Test
	fun `a source the catalogue flags broken is not fine`() {
		// Found rather than named: which sources are flagged changes with every
		// kotatsu-parsers bump, and a hardcoded name would rot into a false pass.
		val broken = MangaParserSource.entries.firstOrNull { it.isBroken }
		assertNotNull(broken, "the catalogue is expected to flag some sources broken")
		assertEquals(SourceHealth.Broken, sourceHealth(broken.name))
	}

	// What an Android backup actually leaves behind. These are Mihon extension ids, and
	// no desktop build will ever have a parser for them.
	@Test
	fun `a Mihon source from a restored backup is missing`() {
		assertEquals(SourceHealth.Missing, sourceHealth("MIHON_4972933717624256217"))
	}

	@Test
	fun `an unrecognised name is missing rather than crashing`() {
		assertEquals(SourceHealth.Missing, sourceHealth("UNKNOWN"))
		assertEquals(SourceHealth.Missing, sourceHealth(""))
	}

	@Test
	fun `health is decided by name only, so the grid and the migrate area agree`() {
		// The migrate area reaches the same verdict from the database side. If these two
		// ever disagree the library offers a repair for something that is not broken, or
		// stays silent about something that is.
		val counted = MangaParserSource.entries.count { sourceHealth(it.name) == SourceHealth.Ok }
		assertEquals(MangaParserSource.entries.count { !it.isBroken }, counted)
	}
}
