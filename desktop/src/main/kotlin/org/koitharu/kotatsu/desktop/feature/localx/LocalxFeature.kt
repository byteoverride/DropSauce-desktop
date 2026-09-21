package org.koitharu.kotatsu.desktop.feature.localx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.shared.db.LocalMangaEntity

/**
 * The richer importer for files on disk.
 *
 * Separate from `LocalFeature` rather than folded into it. That area imports on one
 * click and models every file as one chapter, which is right for the flat .cbz that most
 * of a library is and wrong for the three layouts this one adds. Both write the same
 * `local_index` table and derive the same ids, so a title is the same title whichever
 * screen put it there.
 */
object LocalExtrasFeature : Feature {

	override val id = "localx"

	override val title = "Import"

	/** A box with an arrow into it: bringing structure in, not just a file. */
	override val glyph = "⤓"

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		val dao = remember(context) { context.db.localLibraryDao() }
		val importer = remember(dao) { LocalxImporter(dao) }
		LocalxScreen(dao = dao, importer = importer, scope = context.scope, navigator = navigator)
	}

	/** An importer over [context]'s database, for a shell that needs one outside the screen. */
	fun importer(context: FeatureContext): LocalxImporter = LocalxImporter(context.db.localLibraryDao())

	/**
	 * The chapters of a stored row, however it is laid out.
	 *
	 * The multi-chapter counterpart of `LocalFeature.chapters`, which returns exactly one
	 * by design. Re-derived from disk, so it is a suspending call and not a pure one.
	 */
	suspend fun chapters(entity: LocalMangaEntity): List<LocalxChapter> = readChapters(entity)
}
