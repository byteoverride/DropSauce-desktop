package org.koitharu.kotatsu.desktop.feature.local

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import org.koitharu.kotatsu.desktop.ui.TopBar
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
	var message: String? by remember { mutableStateOf(null) }
	var pendingRemoval: LocalMangaEntity? by remember { mutableStateOf(null) }

	fun runImport(folders: Boolean) {
		if (busy) return
		busy = true
		message = null
		// The app scope, not the composition's: an import of a large folder must not be
		// cancelled by the user navigating away while it runs.
		scope.launch {
			try {
				val picked = pickPaths(folders)
				if (picked.isEmpty()) {
					return@launch
				}
				val report = library.importAll(picked)
				// A re-import can point at different bytes under the same path, so
				// anything remembered about it is now stale.
				report.imported.forEach { images.forget(it.path) }
				message = report.summary()
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
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
			LinearProgressIndicator(Modifier.fillMaxWidth())
		}
		message?.let { text ->
			ImportMessage(text = text, onDismiss = { message = null })
		}
		if (items.isEmpty()) {
			EmptyLocalLibrary()
		} else {
			LazyVerticalGrid(
				columns = GridCells.Adaptive(minSize = 150.dp),
				contentPadding = PaddingValues(12.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
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

@Composable
private fun ImportMessage(text: String, onDismiss: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 12.dp, vertical = 4.dp)
			.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
			.padding(12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				.weight(1f)
				// A bulk import can fail on many files at once; the list scrolls rather
				// than pushing the grid off the screen.
				.heightIn(max = 120.dp)
				.verticalScroll(rememberScrollState()),
		)
		TextButton(onClick = onDismiss) { Text("Dismiss") }
	}
}

@Composable
private fun EmptyLocalLibrary() {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.padding(32.dp),
		) {
			Text("No local comics.", style = MaterialTheme.typography.titleMedium)
			Text(
				text = "Add a .cbz or .zip, a folder of images, or a folder of archives.\n" +
					"Files stay where they are; this only indexes them.",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
		}
	}
}

@Composable
private fun LocalCard(
	entity: LocalMangaEntity,
	images: LocalImages,
	onOpen: () -> Unit,
	onRemove: () -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		LocalCover(
			entity = entity,
			images = images,
			modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)),
		)
		Text(
			text = entity.title,
			style = MaterialTheme.typography.bodySmall,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = "${entity.format} · ${humanSize(entity.sizeBytes)}",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			TextButton(onClick = onRemove) {
				Text("Remove", style = MaterialTheme.typography.labelSmall)
			}
		}
	}
}

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
				modifier = Modifier.align(Alignment.Center).padding(4.dp),
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
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
