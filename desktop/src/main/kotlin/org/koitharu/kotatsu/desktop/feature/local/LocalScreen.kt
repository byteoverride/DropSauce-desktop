package org.koitharu.kotatsu.desktop.feature.local

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.COVER_ASPECT
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LoadingStrip
import org.koitharu.kotatsu.desktop.ui.META_SEPARATOR
import org.koitharu.kotatsu.desktop.ui.NoticeBar
import org.koitharu.kotatsu.desktop.ui.NoticeTone
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.TopBar
import org.koitharu.kotatsu.desktop.ui.focusRing
import org.koitharu.kotatsu.shared.db.LocalMangaEntity
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The local library: comics imported from disk.
 *
 * Deliberately separate from the Library screen. That one holds titles saved from remote
 * sources and is keyed by source; these have no source and no chapters to refresh, and
 * mixing them would make every action on that screen need a "unless it is local" branch.
 */
@Composable
fun LocalScreen(
	library: LocalLibrary,
	images: LocalImages,
	scope: CoroutineScope,
	navigator: FeatureNavigator,
) {
	val items by remember(library) { library.observeAll() }.collectAsState(emptyList())
	var busy by remember { mutableStateOf(false) }
	// The report rather than its one-line summary: what worked and what failed are drawn
	// by different components now, and a joined string cannot be taken back apart.
	var report: ImportReport? by remember { mutableStateOf(null) }
	var pendingRemoval: LocalMangaEntity? by remember { mutableStateOf(null) }

	fun runImport(folders: Boolean) {
		if (busy) return
		busy = true
		report = null
		// The app scope, not the composition's: an import of a large folder must not be
		// cancelled by the user navigating away while it runs.
		scope.launch {
			try {
				val picked = pickPaths(folders)
				if (picked.isEmpty()) {
					return@launch
				}
				val outcome = library.importAll(picked)
				// A re-import can point at different bytes under the same path, so
				// anything remembered about it is now stale.
				outcome.imported.forEach { images.forget(it.path) }
				report = outcome
			} finally {
				busy = false
			}
		}
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Local",
			subtitle = subtitleFor(items),
			trailing = {
				Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
					Button(onClick = { runImport(folders = false) }, enabled = !busy) {
						Text("Add files")
					}
					Button(onClick = { runImport(folders = true) }, enabled = !busy) {
						Text("Add folder")
					}
				}
			},
		)
		if (busy) {
			// A bare bar said something was happening without saying what; opening a folder
			// of archives is slow enough that the difference matters.
			LoadingStrip(label = "Importing")
		}
		report?.let { outcome ->
			ImportOutcome(report = outcome, onDismiss = { report = null })
		}
		if (items.isEmpty()) {
			EmptyBox(
				modifier = Modifier.weight(1f),
				headline = "No local comics.",
				detail = "Add a .cbz or .zip, a folder of images, or a folder of archives. " +
					"Files stay where they are; this only indexes them.",
				icon = AppIcons.LOCAL,
			)
		} else {
			LazyVerticalGrid(
				columns = GridCells.Adaptive(minSize = 150.dp),
				contentPadding = PaddingValues(Space.lg),
				horizontalArrangement = Arrangement.spacedBy(Space.md),
				verticalArrangement = Arrangement.spacedBy(Space.md),
				modifier = Modifier.fillMaxSize(),
			) {
				items(items, key = { it.id }) { entity ->
					LocalCard(
						entity = entity,
						images = images,
						onOpen = {
							navigator.openLocalReader(
								manga = mangaOf(entity),
								chapters = chaptersOf(entity),
								chapterIndex = 0,
								page = 0,
							)
						},
						onRemove = { pendingRemoval = entity },
					)
				}
			}
		}
	}

	pendingRemoval?.let { entity ->
		RemoveDialog(
			entity = entity,
			onDismiss = { pendingRemoval = null },
			onConfirm = {
				scope.launch {
					library.remove(entity)
					images.forget(entity.path)
				}
				pendingRemoval = null
			},
		)
	}
}

private fun subtitleFor(items: List<LocalMangaEntity>): String = when (items.size) {
	0 -> "Nothing imported yet"
	1 -> "1 comic"
	else -> "${items.size} comics"
}

/**
 * What one import gesture did.
 *
 * Two components, not one: every line used to be `onSurfaceVariant` on a `surfaceVariant`
 * card, so "Imported 28 comics." and "x.cbz: truncated archive" were the same colour on
 * the same ground and a failure read as a receipt.
 *
 * Internal, and shared with the Import screen next door. Both commit the same
 * [ImportReport] and had their own copy of this, which is how the two drifted apart.
 */
@Composable
internal fun ImportOutcome(report: ImportReport, onDismiss: () -> Unit) {
	val worked = if (report.isEmpty) "Nothing selected." else report.importedLine()
	if (worked != null) {
		NoticeBar(text = worked, icon = AppIcons.CHECK, onDismiss = onDismiss)
	} else {
		// Every file failed, so there is no success line to carry the dismiss control and
		// a list of failures with no way to close it would stay on screen for good.
		NoticeBar(
			text = "Nothing was imported.",
			icon = AppIcons.WARNING,
			tone = NoticeTone.Problem,
			onDismiss = onDismiss,
		)
	}
	if (report.failures.isEmpty()) {
		return
	}
	Column(
		// A bulk import can fail on many files at once; the list scrolls rather than
		// pushing the grid off the screen.
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(max = 120.dp)
			.verticalScroll(rememberScrollState()),
	) {
		for (failure in report.failures) {
			// No retry: the same file fails the same way until the user goes and looks at
			// it, and a button that cannot work is worse than no button.
			ErrorBox(message = failure.reason, compact = true)
		}
	}
}

/**
 * One imported comic in the grid.
 *
 * Deliberately *not* `CoverCard`, and that is a gap in the shared component rather than a
 * choice here: `CoverCard` draws through `RemoteImage`, which fetches over OkHttp, and a
 * cover decoded out of a local archive has no address to fetch. Everything else is
 * matched to it on purpose — geometry, corner, type scale, and the hover, pressed and
 * focus treatments it has and this did not — so the two read as one card and this can be
 * swapped for the real one the day it accepts a bitmap.
 */
@Composable
private fun LocalCard(
	entity: LocalMangaEntity,
	images: LocalImages,
	onOpen: () -> Unit,
	onRemove: () -> Unit,
) {
	val interactions = remember { MutableInteractionSource() }
	val hovered by interactions.collectIsHoveredAsState()
	val focused by interactions.collectIsFocusedAsState()
	val pressed by interactions.collectIsPressedAsState()
	val scheme = MaterialTheme.colorScheme
	val overlayFill = when {
		pressed -> scheme.onSurface.copy(alpha = PRESSED_ALPHA)
		hovered -> scheme.onSurface.copy(alpha = HOVER_ALPHA)
		else -> Color.Transparent
	}
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(MaterialTheme.shapes.medium)
			.clickable(interactionSource = interactions, indication = null, onClick = onOpen)
			.hoverable(interactions)
			.focusRing(focused, MaterialTheme.shapes.medium)
			.background(overlayFill),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		LocalCover(
			entity = entity,
			images = images,
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(COVER_ASPECT)
				.clip(MaterialTheme.shapes.medium),
		)
		Text(
			// bodyMedium, not bodySmall: it is the most important text on the card.
			text = entity.title,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = entity.format + META_SEPARATOR + humanSize(entity.sizeBytes),
			style = MaterialTheme.typography.bodySmall,
			color = scheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Box(Modifier.align(Alignment.End)) {
			TextButton(onClick = onRemove) { Text("Remove") }
		}
	}
}

/** Material's standard hover overlay alpha, as `CoverCard` uses it. */
private const val HOVER_ALPHA = 0.08f

/** Material's standard pressed overlay alpha, as `CoverCard` uses it. */
private const val PRESSED_ALPHA = 0.12f

@Composable
private fun LocalCover(entity: LocalMangaEntity, images: LocalImages, modifier: Modifier) {
	var bitmap: ImageBitmap? by remember(entity.path) { mutableStateOf(null) }
	var settled by remember(entity.path) { mutableStateOf(false) }
	LaunchedEffect(entity.path) {
		bitmap = images.cover(entity.path, entity.coverEntry)
		settled = true
	}
	Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
		val bmp = bitmap
		if (bmp != null) {
			Image(
				bitmap = bmp,
				contentDescription = entity.title,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		} else if (settled) {
			Text(
				text = "no cover",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
				modifier = Modifier.align(Alignment.Center).padding(Space.xs),
			)
		}
	}
}

@Composable
private fun RemoveDialog(
	entity: LocalMangaEntity,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Remove from local library") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
				Text(entity.title, style = MaterialTheme.typography.bodyMedium)
				Text(
					text = "This removes the entry only. The file stays at\n${entity.path}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onConfirm) {
				Text("Remove", color = MaterialTheme.colorScheme.error)
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}

private fun humanSize(bytes: Long): String = when {
	bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
	bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
	bytes >= 1024L -> "${bytes / 1024} KB"
	else -> "$bytes B"
}

/**
 * Shows a Swing file chooser and returns what the user picked.
 *
 * Two modes rather than `FILES_AND_DIRECTORIES`: in that mode the chooser navigates into
 * a folder on double click instead of selecting it, so picking a folder needs a trick the
 * user has to already know.
 *
 * The dialog is modal and blocks its thread, so it is opened from IO and marshalled onto
 * the AWT event thread. Calling `invokeAndWait` from the event thread itself throws,
 * hence the guard.
 */
private suspend fun pickPaths(folders: Boolean): List<File> = withContext(Dispatchers.IO) {
	onSwing {
		val chooser = JFileChooser()
		chooser.dialogTitle = if (folders) "Import a folder" else "Import comics"
		chooser.fileSelectionMode =
			if (folders) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
		chooser.isMultiSelectionEnabled = true
		if (!folders) {
			chooser.fileFilter = FileNameExtensionFilter("Comic archives (*.cbz, *.zip)", "cbz", "zip")
		}
		if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
			return@onSwing emptyList()
		}
		// selectedFiles is empty when a look-and-feel ignores multi-selection, and
		// selectedFile is null when only the multi-selection array was filled.
		chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) }
	}
}

private fun <T> onSwing(block: () -> T): T {
	if (SwingUtilities.isEventDispatchThread()) return block()
	var result: Result<T>? = null
	SwingUtilities.invokeAndWait { result = runCatching(block) }
	return checkNotNull(result) { "Swing dispatch returned without running" }.getOrThrow()
}
