package org.koitharu.kotatsu.desktop.feature.localx

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.feature.FeatureNavigator
import org.koitharu.kotatsu.desktop.feature.local.ImportOutcome
import org.koitharu.kotatsu.desktop.feature.local.ImportReport
import org.koitharu.kotatsu.desktop.feature.local.LocalFeature
import org.koitharu.kotatsu.desktop.feature.local.LocalImportException
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.AppListRow
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.ErrorBox
import org.koitharu.kotatsu.desktop.ui.LoadingStrip
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.ThumbSize
import org.koitharu.kotatsu.desktop.ui.TopBar
import org.koitharu.kotatsu.shared.db.LocalLibraryDao
import org.koitharu.kotatsu.shared.db.LocalMangaEntity
import java.io.File
import java.io.IOException
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The importer: pick, see what was found, then commit.
 *
 * The older Local screen imports on the click that opens the file chooser. This one puts
 * a confirmation between the two, because it can detect chapters and chapter detection
 * can be wrong in ways the user notices and the program cannot.
 */
@Composable
fun LocalxScreen(
	dao: LocalLibraryDao,
	importer: LocalxImporter,
	scope: CoroutineScope,
	navigator: FeatureNavigator,
) {
	val rows by remember(dao) { dao.observeAll() }.collectAsState(emptyList())
	var busy by remember { mutableStateOf(false) }
	var previews: List<ImportPreview> by remember { mutableStateOf(emptyList()) }
	var chosen: Map<String, String> by remember { mutableStateOf(emptyMap()) }
	// The report rather than its one-line summary: what worked and what failed are drawn
	// by different components now, and a joined string cannot be taken back apart.
	var report: ImportReport? by remember { mutableStateOf(null) }
	var opened: LocalMangaEntity? by remember { mutableStateOf(null) }

	fun pick(folders: Boolean) {
		if (busy) return
		busy = true
		report = null
		// The app scope, not the composition's: inspecting a folder of a hundred archives
		// must not be cancelled by the user navigating away while it runs.
		scope.launch {
			try {
				val picked = pickLocalxPaths(folders)
				if (picked.isEmpty()) return@launch
				previews = importer.inspect(picked)
				chosen = emptyMap()
			} finally {
				busy = false
			}
		}
	}

	fun commit() {
		if (busy) return
		busy = true
		val ready = previews.filterIsInstance<ImportPreview.Ready>()
		scope.launch {
			try {
				val candidates = ready.flatMap { preview -> selectedOption(preview, chosen).candidates }
				val outcome = importer.commit(candidates)
				// A re-import can point at different bytes under the same path, so
				// anything remembered about it is now stale.
				outcome.imported.forEach { LocalFeature.images.forget(it.path) }
				report = outcome
				previews = emptyList()
			} finally {
				busy = false
			}
		}
	}

	val open = opened
	if (open != null) {
		TitleScreen(entity = open, navigator = navigator, onBack = { opened = null })
		return
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Import",
			subtitle = if (rows.isEmpty()) "Nothing imported yet" else "${rows.size} ${plural(rows.size, "title")}",
			trailing = {
				Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
					Button(onClick = { pick(folders = false) }, enabled = !busy) { Text("Pick files") }
					Button(onClick = { pick(folders = true) }, enabled = !busy) { Text("Pick folder") }
				}
			},
		)
		if (busy) {
			// Which of the two slow things is running is readable from the state: previews
			// are only on screen between the inspection finishing and the import starting.
			LoadingStrip(label = if (previews.isEmpty()) "Looking at what you picked" else "Importing")
		}
		report?.let { outcome ->
			ImportOutcome(report = outcome, onDismiss = { report = null })
		}
		if (previews.isNotEmpty()) {
			ConfirmPanel(
				previews = previews,
				chosen = chosen,
				onChoose = { source, key -> chosen = chosen + (source to key) },
				onCancel = { previews = emptyList() },
				onConfirm = ::commit,
				enabled = !busy,
				modifier = Modifier.weight(1f),
			)
		} else if (rows.isEmpty()) {
			EmptyBox(
				modifier = Modifier.weight(1f),
				headline = "Nothing imported yet.",
				detail = "Pick a .cbz, a .zip, an .epub, a folder of chapter folders, or a folder " +
					"of archives. You are shown what was found before anything is saved. Files " +
					"stay where they are.",
				icon = AppIcons.IMPORT,
			)
		} else {
			LazyColumn(
				// No gap and no gutter here: `AppListRow` carries its own, and its minimum
				// height is what separates one row from the next.
				contentPadding = PaddingValues(vertical = Space.sm),
				modifier = Modifier.weight(1f),
			) {
				items(rows, key = { it.id }) { row ->
					TitleRow(row = row, onOpen = { opened = row })
				}
			}
		}
	}
}

/** The option the user picked for [preview], or the first, which detection ranked highest. */
private fun selectedOption(preview: ImportPreview.Ready, chosen: Map<String, String>): ImportOption {
	val key = chosen[preview.source.absolutePath]
	return preview.options.firstOrNull { it.key == key } ?: preview.options.first()
}

@Composable
private fun ConfirmPanel(
	previews: List<ImportPreview>,
	chosen: Map<String, String>,
	onChoose: (String, String) -> Unit,
	onCancel: () -> Unit,
	onConfirm: () -> Unit,
	enabled: Boolean,
	modifier: Modifier = Modifier,
) {
	val ready = previews.filterIsInstance<ImportPreview.Ready>()
	Column(modifier) {
		LazyColumn(
			contentPadding = PaddingValues(vertical = Space.sm),
			verticalArrangement = Arrangement.spacedBy(Space.md),
			modifier = Modifier.weight(1f),
		) {
			items(previews, key = { it.source.absolutePath }) { preview ->
				when (preview) {
					// Not inside a `Panel`: the error strip explains itself in
					// `onSurfaceVariant`, which is a colour for `surface` and not for the
					// panel's `surfaceContainer` fill.
					is ImportPreview.Rejected -> ErrorBox(
						message = preview.source.name + ": " + preview.reason,
						compact = true,
					)

					is ImportPreview.Ready -> Panel {
						Text(preview.source.name, style = MaterialTheme.typography.titleSmall)
						Text(
							text = "Detected: ${preview.layout}",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						for (note in preview.notes) {
							Text(
								text = "· $note",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						val selected = selectedOption(preview, chosen)
						for (option in preview.options) {
							OptionRow(
								option = option,
								selected = option.key == selected.key,
								// One option is not a choice, it is a statement of what
								// will happen; a radio button there invites a pointless click.
								showRadio = preview.options.size > 1,
								onSelect = { onChoose(preview.source.absolutePath, option.key) },
							)
						}
						ChapterPreview(selected)
					}
				}
			}
		}
		Row(
			modifier = Modifier.fillMaxWidth().padding(Space.lg),
			horizontalArrangement = Arrangement.spacedBy(Space.sm),
		) {
			Button(onClick = onConfirm, enabled = enabled && ready.isNotEmpty()) {
				Text("Import ${ready.sumOf { selectedOption(it, chosen).candidates.size }}")
			}
			TextButton(onClick = onCancel, enabled = enabled) { Text("Cancel") }
		}
	}
}

@Composable
private fun OptionRow(
	option: ImportOption,
	selected: Boolean,
	showRadio: Boolean,
	onSelect: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().clickable(enabled = showRadio, onClick = onSelect),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
	) {
		if (showRadio) {
			RadioButton(selected = selected, onClick = onSelect)
		}
		Column {
			Text(option.label, style = MaterialTheme.typography.bodyMedium)
			Text(
				text = option.describe(),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/** The chapter list the selected option would produce, so nothing is a surprise. */
@Composable
private fun ChapterPreview(option: ImportOption) {
	val chapters = option.candidates.flatMap { candidate -> candidate.chapters.map { candidate.title to it } }
	if (chapters.isEmpty()) return
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(max = 180.dp)
			.verticalScroll(rememberScrollState()),
	) {
		for ((title, chapter) in chapters.take(MAX_PREVIEW_CHAPTERS)) {
			Text(
				text = "${chapter.number.toInt()}. ${chapter.title}  " +
					if (chapter.isText) "(text)" else "(${chapter.pageCount} ${plural(chapter.pageCount, "page")})",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (option.candidates.size > 1) {
				Text(
					text = "    in $title",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		if (chapters.size > MAX_PREVIEW_CHAPTERS) {
			Text(
				text = "and ${chapters.size - MAX_PREVIEW_CHAPTERS} more",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/** An imported title, with its chapters, and the two ways of reading them. */
@Composable
private fun TitleScreen(entity: LocalMangaEntity, navigator: FeatureNavigator, onBack: () -> Unit) {
	var chapters: List<LocalxChapter> by remember(entity.id) { mutableStateOf(emptyList()) }
	var error: String? by remember(entity.id) { mutableStateOf(null) }
	var reading: Int? by remember(entity.id) { mutableStateOf(null) }
	// Reading a container that is still being copied onto the disk fails and then stops
	// failing, so the error state is worth a retry rather than only a way back.
	var attempt by remember(entity.id) { mutableStateOf(0) }
	LaunchedEffect(entity.id, entity.path, entity.format, attempt) {
		try {
			chapters = readChapters(entity)
			error = null
		} catch (e: LocalImportException) {
			error = e.message
		} catch (e: IOException) {
			error = "${entity.title}: ${e.message ?: e::class.simpleName}"
		}
	}

	val index = reading
	if (index != null && index in chapters.indices) {
		val chapter = chapters[index]
		val onClose = { reading = null }
		val onMove = { step: Int -> reading = (index + step).coerceIn(chapters.indices) }
		if (chapter.isText) {
			TextReader(File(entity.path), chapter, index, chapters.size, onClose, onMove)
		} else {
			PagesReader(chapter, index, chapters.size, onClose, onMove)
		}
		return
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = entity.title,
			subtitle = "${entity.format} · ${chapters.size} ${plural(chapters.size, "chapter")}",
			onBack = onBack,
		)
		error?.let {
			ErrorBox(message = it, onRetry = { attempt++ }, compact = true)
		}
		LazyColumn(
			contentPadding = PaddingValues(vertical = Space.sm),
			modifier = Modifier.weight(1f),
		) {
			itemsIndexed(chapters, key = { _, it -> it.key + "#" + it.number }) { i, chapter ->
				AppListRow(
					headline = chapter.title,
					onClick = {
						// A chapter the shell's reader can open goes there, because that
						// reader has zoom, page modes and progress and this one does not.
						// The rest this area has to show itself.
						if (chapter.isShellReadable) {
							val manga = localxManga(entity, chapters)
							navigator.openLocalReader(
								manga = manga,
								chapters = manga.chapters.orEmpty(),
								chapterIndex = i,
							)
						} else {
							reading = i
						}
					},
					meta = listOf(
						if (chapter.isText) {
							"text"
						} else {
							"${chapter.pageCount} ${plural(chapter.pageCount, "page")}"
						},
					),
					leading = {
						Text(
							text = "${chapter.number.toInt()}.",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					},
				)
			}
		}
	}
}

/**
 * The novel reader: one chapter of an EPUB as scrollable text.
 *
 * Its own screen rather than the shell's reader, which draws bitmaps. Font size is
 * adjustable because a fixed one is the single most common complaint about every text
 * reader, and the range is deliberately wide.
 */
@Composable
private fun TextReader(
	file: File,
	chapter: LocalxChapter,
	index: Int,
	total: Int,
	onClose: () -> Unit,
	onMove: (Int) -> Unit,
) {
	var text by remember(chapter.key) { mutableStateOf("") }
	// The failure is held apart from the text. Assigned into `text` it was drawn as the
	// chapter's own prose, at the reader's font size and in the reader's colour, so
	// "This chapter could not be read." was indistinguishable from a chapter that says so.
	var failure: String? by remember(chapter.key) { mutableStateOf(null) }
	var attempt by remember(chapter.key) { mutableStateOf(0) }
	var fontSize by remember { mutableStateOf(16f) }
	LaunchedEffect(file.path, chapter.key, attempt) {
		try {
			text = withContext(Dispatchers.IO) { EpubReader.chapterText(file, chapter.key) }
			failure = null
		} catch (e: LocalImportException) {
			failure = e.message ?: "This chapter could not be read."
		} catch (e: IOException) {
			failure = "This chapter could not be read: ${e.message ?: e::class.simpleName}"
		}
	}
	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = chapter.title,
			subtitle = "${index + 1} of $total",
			onBack = onClose,
			trailing = {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text("A", style = MaterialTheme.typography.labelSmall)
					Slider(
						value = fontSize,
						onValueChange = { fontSize = it },
						valueRange = MIN_FONT_SIZE..MAX_FONT_SIZE,
						modifier = Modifier.widthIn(max = 160.dp),
					)
					Text("A", style = MaterialTheme.typography.titleMedium)
				}
			},
		)
		val problem = failure
		if (problem != null) {
			// Compact rather than the full box so the chapter navigation below stays on
			// screen: moving on to the next chapter is the recovery that usually works.
			ErrorBox(message = problem, onRetry = { attempt++ }, compact = true)
			Box(Modifier.weight(1f))
		} else {
			Column(
				modifier = Modifier
					.weight(1f)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = Space.xl),
			) {
				Text(
					text = text,
					fontSize = fontSize.sp,
					// Generous leading: the default is tuned for labels, and a wall of prose
					// at 1.0 is unreadable at any size.
					lineHeight = (fontSize * 1.6f).sp,
					modifier = Modifier.padding(vertical = Space.lg),
				)
			}
		}
		ChapterNav(index, total, onMove)
	}
}

/**
 * Pages of a chapter this area has to read itself.
 *
 * Only reached for a chapter that is a subset of its container, which the shell's reader
 * cannot address: it resolves pages by listing the whole container. Plain vertical scroll,
 * no page modes; anything richer belongs in the shell's reader, once it can be handed a
 * page list rather than a path.
 */
@Composable
private fun PagesReader(
	chapter: LocalxChapter,
	index: Int,
	total: Int,
	onClose: () -> Unit,
	onMove: (Int) -> Unit,
) {
	val urls = remember(chapter.container, chapter.key) { chapter.pageUrls() }
	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = chapter.title,
			subtitle = "${index + 1} of $total · ${chapter.pageCount} ${plural(chapter.pageCount, "page")}",
			onBack = onClose,
		)
		LazyColumn(Modifier.weight(1f)) {
			items(urls, key = { it }) { url ->
				LocalPage(url)
			}
		}
		ChapterNav(index, total, onMove)
	}
}

@Composable
private fun LocalPage(url: String) {
	var bitmap: ImageBitmap? by remember(url) { mutableStateOf(null) }
	var settled by remember(url) { mutableStateOf(false) }
	LaunchedEffect(url) {
		bitmap = LocalFeature.images.page(url)
		settled = true
	}
	val bmp = bitmap
	if (bmp != null) {
		Image(
			bitmap = bmp,
			contentDescription = null,
			contentScale = ContentScale.FillWidth,
			modifier = Modifier.fillMaxWidth(),
		)
	} else if (settled) {
		// On the page surface rather than inside the placeholder fill: a page that will
		// not decode is a failure, not a slow load, and `ErrorBox` explains itself in
		// `onSurfaceVariant`, which is a colour for `surface` and not for `surfaceVariant`.
		ErrorBox(message = "This page could not be decoded.", compact = true)
	} else {
		Box(Modifier.fillMaxWidth().heightIn(min = 240.dp).background(MaterialTheme.colorScheme.surfaceVariant))
	}
}

@Composable
private fun ChapterNav(index: Int, total: Int, onMove: (Int) -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(Space.lg),
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
	) {
		TextButton(onClick = { onMove(-1) }, enabled = index > 0) { Text("Previous") }
		TextButton(onClick = { onMove(1) }, enabled = index < total - 1) { Text("Next") }
	}
}

@Composable
private fun TitleRow(row: LocalMangaEntity, onOpen: () -> Unit) {
	AppListRow(
		headline = row.title,
		onClick = onOpen,
		meta = listOf(
			LocalxFormat.of(row.format)?.label ?: row.format,
			"${row.chaptersCount} ${plural(row.chaptersCount, "chapter")}",
		),
		leading = { Cover(row) },
	)
}

/**
 * The thumbnail for one imported title.
 *
 * Sized, which it was not: `widthIn(max = 48.dp)` with no height and no aspect ratio
 * under a `ContentScale.Crop` with no size modifier meant each row was as tall as its own
 * decoded cover, so a list of ten was ten different row heights.
 *
 * No `contentDescription`: the row's headline is this title, in the same row, so a
 * description here would have a screen reader read it twice.
 */
@Composable
private fun Cover(row: LocalMangaEntity) {
	var bitmap: ImageBitmap? by remember(row.path) { mutableStateOf(null) }
	LaunchedEffect(row.path, row.coverEntry, row.format) {
		val url = coverUrlOf(row)
		bitmap = url?.let { LocalFeature.images.page(it) }
	}
	Box(
		Modifier
			.size(ThumbSize.list)
			.clip(MaterialTheme.shapes.small)
			.background(MaterialTheme.colorScheme.surfaceVariant),
	) {
		bitmap?.let {
			Image(
				bitmap = it,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}

/**
 * The card one import candidate is described in.
 *
 * `surfaceContainer`, not `surfaceVariant`: the text inside is `onSurfaceVariant`, which
 * is the scheme's pairing for `surface` and its containers, and drawing it on
 * `surfaceVariant` put a mid tone on a mid tone.
 */
@Composable
private fun Panel(content: @Composable () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = Space.lg)
			.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
			.padding(Space.md),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		content()
	}
}

/**
 * Shows a Swing file chooser and returns what the user picked.
 *
 * Two modes rather than `FILES_AND_DIRECTORIES`: in that mode the chooser navigates into
 * a folder on double click instead of selecting it. The dialog is modal and blocks its
 * thread, so it runs on IO and is marshalled onto the AWT event thread.
 */
private suspend fun pickLocalxPaths(folders: Boolean): List<File> = withContext(Dispatchers.IO) {
	onSwingThread {
		val chooser = JFileChooser()
		chooser.dialogTitle = if (folders) "Import a folder" else "Import comics or books"
		chooser.fileSelectionMode = if (folders) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
		chooser.isMultiSelectionEnabled = true
		if (!folders) {
			chooser.fileFilter = FileNameExtensionFilter(
				"Comics and books (*.cbz, *.zip, *.epub)",
				"cbz",
				"zip",
				"epub",
			)
		}
		if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
			return@onSwingThread emptyList()
		}
		// selectedFiles is empty when a look-and-feel ignores multi-selection, and
		// selectedFile is null when only the multi-selection array was filled.
		chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) }
	}
}

private fun <T> onSwingThread(block: () -> T): T {
	if (SwingUtilities.isEventDispatchThread()) return block()
	var result: Result<T>? = null
	SwingUtilities.invokeAndWait { result = runCatching(block) }
	return checkNotNull(result) { "Swing dispatch returned without running" }.getOrThrow()
}

private const val MAX_PREVIEW_CHAPTERS = 40

private const val MIN_FONT_SIZE = 11f

private const val MAX_FONT_SIZE = 32f
