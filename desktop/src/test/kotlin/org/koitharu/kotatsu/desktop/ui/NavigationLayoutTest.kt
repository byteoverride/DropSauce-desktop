package org.koitharu.kotatsu.desktop.ui

import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.appupdate.AppUpdateFeature
import org.koitharu.kotatsu.desktop.feature.curate.CurateFeature
import org.koitharu.kotatsu.desktop.feature.discover.DiscoverFeature
import org.koitharu.kotatsu.desktop.feature.download.DownloadFeature
import org.koitharu.kotatsu.desktop.feature.local.LocalFeature
import org.koitharu.kotatsu.desktop.feature.localx.LocalExtrasFeature
import org.koitharu.kotatsu.desktop.feature.migration.MigrationFeature
import org.koitharu.kotatsu.desktop.feature.readerx.ReaderExtrasFeature
import org.koitharu.kotatsu.desktop.feature.reading.BookmarksFeature
import org.koitharu.kotatsu.desktop.feature.reading.StatsFeature
import org.koitharu.kotatsu.desktop.feature.scrobbling.ScrobblingFeature
import org.koitharu.kotatsu.desktop.feature.suggestions.SuggestionsFeature
import org.koitharu.kotatsu.desktop.feature.sync.BackupFeature
import org.koitharu.kotatsu.desktop.feature.sync.UpdatesFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where each area lives, and how many are in the rail.
 *
 * The rail reached eighteen destinations because every new area defaulted into it and
 * nobody counted. The split is a judgement, but it is one worth failing a build over:
 * the whole point is that adding an area is a deliberate decision about where it goes,
 * not something that happens by default.
 */
class NavigationLayoutTest {

	private val all: List<Feature> = listOf(
		LocalFeature, LocalExtrasFeature, DiscoverFeature, SuggestionsFeature,
		DownloadFeature, BookmarksFeature, CurateFeature, UpdatesFeature,
		MigrationFeature, ScrobblingFeature, ReaderExtrasFeature, StatsFeature,
		BackupFeature, AppUpdateFeature,
	)

	/** Four destinations are the shell's own and are not features. */
	private val shellRoots = 4

	@Test
	fun `the rail stays short enough to read`() {
		val railed = all.count { it.isTopLevel } + shellRoots
		assertTrue(railed <= 10, "the navigation rail is back up to $railed destinations")
	}

	// Content on the rail, configuration and maintenance in Settings. Bookmarks and local
	// comics are things you read, so they are not tools however rarely they are opened.
	@Test
	fun `the tools are the ones in Settings`() {
		assertEquals(
			listOf("appupdate", "backup", "curate", "localx", "migration", "readerx", "stats", "tracking"),
			all.filterNot { it.isTopLevel }.map { it.id }.sorted(),
		)
	}

	@Test
	fun `what is left on the rail is content`() {
		assertEquals(
			listOf("bookmarks", "discover", "downloads", "local", "suggestions", "updates"),
			all.filter { it.isTopLevel }.map { it.id }.sorted(),
		)
	}

	// A rail slot has a glyph beside it; a Settings row is read as a phrase. "Migrate"
	// works for the first and says nothing in the second.
	@Test
	fun `every tool reads as a phrase in the Settings list`() {
		all.filterNot { it.isTopLevel }.forEach { tool ->
			assertTrue(
				tool.settingsTitle.isNotBlank(),
				"${tool.id} has no Settings label",
			)
		}
		assertEquals("Fix broken sources", MigrationFeature.settingsTitle)
		assertEquals("Backup and restore", BackupFeature.settingsTitle)
		assertEquals("Reader preferences", ReaderExtrasFeature.settingsTitle)
	}

	@Test
	fun `ids are unique, since they are the navigation key`() {
		assertEquals(all.size, all.map { it.id }.toSet().size)
	}
}
