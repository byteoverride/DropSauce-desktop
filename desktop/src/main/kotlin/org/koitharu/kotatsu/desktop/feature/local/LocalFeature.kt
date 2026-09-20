package org.koitharu.kotatsu.desktop.feature.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import org.koitharu.kotatsu.desktop.feature.Feature
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.shared.db.LocalMangaEntity

/**
 * Comics imported from disk.
 *
 * Everything the shell needs from this area goes through here: the navigation entry, and
 * the two calls a reader showing a local chapter has to make. Those two are on the
 * feature rather than on [LocalLibrary] because the shell has a [FeatureNavigator] and a
 * [FeatureContext], not a repository, and `FeatureNavigator.openLocalReader` hands it a
 * chapter with no way to ask where the pages live.
 */
object LocalFeature : Feature {

	override val id = "local"

	override val title = "Local"

	/** A downwards arrow to a bar: bringing something in from outside. */
	override val glyph = "⤓"

	/**
	 * Decoded covers and pages, held on the feature rather than per composition so
	 * scrolling the grid away and back does not reopen every archive.
	 */
	val images = LocalImages()

	@Composable
	override fun Content(context: FeatureContext, navigator: FeatureNavigator) {
		val library = remember(context) { LocalLibrary(context.db.localLibraryDao()) }
		LocalScreen(
			library = library,
			images = images,
			scope = context.scope,
			navigator = navigator,
		)
	}

	/** Whether a chapter or title came from disk rather than a source. */
	fun isLocal(source: MangaSource): Boolean = source.name == LocalMangaSource.name

	/**
	 * The pages of a local chapter.
	 *
	 * The local counterpart of `MangaParser.getPages`, which a local chapter has no
	 * parser to call.
	 */
	suspend fun pages(chapter: MangaChapter): List<MangaPage> = pagesOf(chapter)

	/**
	 * Decodes one local page.
	 *
	 * `ImageCache.load` cannot serve these: it fetches over OkHttp and a local page has
	 * no address to fetch.
	 */
	suspend fun page(page: MangaPage): ImageBitmap? = images.page(page.url)

	/** The title a stored row describes, for a shell rebuilding it from history. */
	fun manga(entity: LocalMangaEntity): Manga = mangaOf(entity)

	/** The chapters of a stored row. Always exactly one; see [chaptersOf]. */
	fun chapters(entity: LocalMangaEntity): List<MangaChapter> = chaptersOf(entity)

	/** A repository over [context]'s database, for a shell that needs one outside the screen. */
	fun library(context: FeatureContext): LocalLibrary = LocalLibrary(context.db.localLibraryDao())
}
