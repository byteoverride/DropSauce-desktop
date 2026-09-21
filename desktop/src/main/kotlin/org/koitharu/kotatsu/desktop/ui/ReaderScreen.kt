package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.desktop.feature.reading.BookmarkToggle
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * The reader.
 *
 * Zoom and pan is a hand-written transform (DECISIONS.md D10) rather than a port of
 * subsampling-scale-image-view, which is Android-only. Tiled decoding is not wired in
 * yet; pages are decoded whole, which is correct for paged modes and is the known
 * weakness for very tall webtoon strips.
 */
@Composable
fun ReaderScreen(
	state: AppState,
	pageSource: ReaderPageSource,
	sourceLabel: String,
	manga: Manga,
	chapters: List<MangaChapter>,
	chapterIndex: Int,
	initialPage: Int,
	onBack: () -> Unit,
	onChapterChange: (Int) -> Unit,
) {
	val chapter = chapters[chapterIndex]
	val pages = remember(chapter.id) { mutableStateListOf<MangaPage>() }
	var loading by remember(chapter.id) { mutableStateOf(true) }
	var error: String? by remember(chapter.id) { mutableStateOf(null) }
	var attempt by remember(chapter.id) { mutableStateOf(0) }
	// Only the chapter opened from a resume starts mid-way; moving on to the next chapter
	// must start at its beginning, which is why this keys on the chapter.
	var index by remember(chapter.id) { mutableStateOf(0) }
	var appliedInitial by remember(chapter.id) { mutableStateOf(false) }
	val zoom = remember { ZoomState() }
	val settings by state.settings.data.collectAsState()

	LaunchedEffect(chapter.id, attempt) {
		loading = true
		error = null
		pages.clear()
		runCatching { pageSource.pages(chapter) }
			.onSuccess {
				pages.addAll(it)
				if (!appliedInitial) {
					// Clamped: a source can return fewer pages than when the position
					// was recorded, and opening past the end would show nothing.
					index = initialPage.coerceIn(0, (it.size - 1).coerceAtLeast(0))
					appliedInitial = true
				}
			}
			.onFailure { error = it.message ?: it::class.simpleName ?: "Request failed" }
		loading = false
	}

	fun next() {
		when {
			index < pages.lastIndex -> index++
			chapterIndex < chapters.lastIndex -> onChapterChange(chapterIndex + 1)
		}
	}

	fun previous() {
		when {
			index > 0 -> index--
			chapterIndex > 0 -> onChapterChange(chapterIndex - 1)
		}
	}

	// Record progress whenever the page or chapter changes. Runs on the app scope rather
	// than the composition's, so leaving the reader mid-write does not cancel it.
	LaunchedEffect(chapter.id, index, pages.size) {
		if (pages.isEmpty()) return@LaunchedEffect
		val chapterProgress = (index + 1).toFloat() / pages.size
		val percent = ((chapterIndex + chapterProgress) / chapters.size).coerceIn(0f, 1f)
		state.scope.launch {
			// Incognito is checked here rather than inside recordProgress so the reader
			// is the thing that decides, and so a caller cannot forget by not asking.
			if (!state.incognito.shouldRecordHistory(manga.id)) return@launch
			state.library.recordProgress(
				manga = manga,
				chapterId = chapter.id,
				page = index,
				chaptersCount = chapters.size,
				percent = percent,
			)
		}
	}

	// Tell external trackers once per chapter, not once per page: services treat
	// progress as an absolute chapter count, so a page-level push would be noise.
	// Incognito suppresses this too, since a tracker is a more public record than
	// local history.
	LaunchedEffect(chapter.id) {
		if (!state.incognito.shouldRecordHistory(manga.id)) return@LaunchedEffect
		runCatching { state.tracking.pushProgress(manga.id, chapters, chapter.id) }
			.onFailure { it.printStackTraceDebug() }
	}

	val focus = remember { FocusRequester() }
	LaunchedEffect(chapter.id) { focus.requestFocus() }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(Color.Black)
			.focusRequester(focus)
			.focusable()
			// Take focus back on any press inside the reader. The page area is a
			// scrollable list, so clicking it moves focus off this Column and silently
			// kills every keyboard shortcut: arrows, space, Escape and zoom all stop
			// working with nothing on screen to explain why. Runs on the Initial pass
			// and consumes nothing, so gestures below are unaffected.
			.pointerInput(Unit) {
				awaitEachGesture {
					awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
					focus.requestFocus()
				}
			}
			.onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				when (event.key) {
					Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar -> {
						next()
						true
					}

					Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> {
						previous()
						true
					}
					// Keyboard zoom, because a mouse without a wheel or a trackpad
					// without pinch would otherwise have no way to zoom at all.
					Key.Equals, Key.Plus, Key.NumPadAdd -> { zoom.zoomBy(KEY_STEP); true }
					Key.Minus, Key.NumPadSubtract -> { zoom.zoomBy(1f / KEY_STEP); true }
					Key.Zero, Key.NumPad0 -> { zoom.reset(); true }
					Key.Escape -> { onBack(); true }
					else -> false
				}
			},
	) {
		ReaderBar(
			manga = manga,
			sourceLabel = sourceLabel,
			chapter = chapter,
			index = index,
			total = pages.size,
			onBack = onBack,
			bookmark = {
				val current = pages.getOrNull(index)
				if (current != null) {
					BookmarkToggle(
						repository = state.bookmarks,
						manga = manga,
						chapterId = chapter.id,
						pageId = current.id,
						page = index,
						imageUrl = current.preview ?: current.url,
						percent = if (chapters.isEmpty()) {
							0f
						} else {
							(chapterIndex + (index + 1f) / pages.size.coerceAtLeast(1)) / chapters.size
						},
					)
				}
			},
		)
		// weight(1f), not the content's own fillMaxSize: inside a Column a child that
		// fills takes every remaining pixel, which left the status bar below it with
		// zero height and therefore invisible.
		Box(Modifier.weight(1f)) {
		when {
			loading -> LoadingBox()
			error != null -> ErrorBox("Could not load pages.\n$error", onRetry = { attempt++ })
			pages.isEmpty() -> ErrorBox("This chapter has no pages.", onRetry = { attempt++ })
			else -> WebtoonStrip(
				pageSource = pageSource,
				pages = pages,
				zoom = zoom,
				widthPercent = settings.webtoonWidthPercent,
			)
		}
		}
		ReaderStatusBar(
			index = index,
			total = pages.size,
			widthPercent = settings.webtoonWidthPercent,
			zoom = zoom,
			onWidthPercent = { value ->
				state.scope.launch { state.settings.update { it.copy(webtoonWidthPercent = value) } }
			},
		)
	}
}

@Composable
private fun ReaderBar(
	manga: Manga,
	sourceLabel: String,
	chapter: MangaChapter,
	index: Int,
	total: Int,
	onBack: () -> Unit,
	bookmark: @Composable () -> Unit = {},
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Button(onClick = onBack) { Text("Back") }
		bookmark()
		Column(Modifier.weight(1f)) {
			Text(
				text = chapter.title ?: "Chapter ${chapter.number}",
				style = MaterialTheme.typography.bodyMedium,
				color = Color.White,
				maxLines = 1,
			)
			if (sourceLabel.isNotEmpty() && total == 0) {
				Text(
					text = sourceLabel,
					style = MaterialTheme.typography.labelSmall,
					color = Color.White.copy(alpha = 0.6f),
					maxLines = 1,
				)
			}
			Text(
				text = if (total == 0) manga.title else "${index + 1} / $total",
				style = MaterialTheme.typography.bodySmall,
				color = Color.White.copy(alpha = 0.7f),
			)
		}
	}
}

/**
 * Zoom and pan shared by every reading mode.
 *
 * Hoisted out of the individual views because webtoon mode originally had no transform at
 * all: it was a plain LazyColumn, so a long strip could only ever be read at whatever
 * width the window happened to be. Keeping one state also means switching mode does not
 * silently throw away the zoom the reader had set.
 */
@Stable
class ZoomState {

	var scale by mutableStateOf(MIN_SCALE)
		private set

	var offsetX by mutableStateOf(0f)
		private set

	var offsetY by mutableStateOf(0f)
		private set

	val isZoomed: Boolean get() = scale > MIN_SCALE + 0.001f

	/** Absolute set, for a slider that reports a position rather than a delta. */
	fun zoomTo(value: Float) {
		val next = value.coerceIn(MIN_SCALE, MAX_SCALE)
		if (next == scale) return
		scale = next
		if (!isZoomed) {
			offsetX = 0f
			offsetY = 0f
		}
	}

	fun zoomBy(factor: Float) {
		val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
		if (next == scale) return
		scale = next
		if (!isZoomed) {
			// Snapping back to fit must also recentre, or the page stays nudged off to
			// one side with no visible way to correct it.
			offsetX = 0f
			offsetY = 0f
		}
	}

	fun panBy(dx: Float, dy: Float) {
		if (!isZoomed) return
		offsetX += dx
		offsetY += dy
	}

	fun reset() {
		scale = MIN_SCALE
		offsetX = 0f
		offsetY = 0f
	}
}

/**
 * Ctrl plus wheel to zoom, which is what a desktop user reaches for first.
 *
 * Plain wheel is deliberately left alone so it still scrolls the strip in webtoon mode.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.ctrlScrollZoom(zoom: ZoomState): Modifier =
	onPointerEvent(PointerEventType.Scroll) { event ->
		if (!event.keyboardModifiers.isCtrlPressed) return@onPointerEvent
		val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
		if (delta == 0f) return@onPointerEvent
		zoom.zoomBy(if (delta < 0f) WHEEL_STEP else 1f / WHEEL_STEP)
		event.changes.forEach { it.consume() }
	}

/** Pinch on a trackpad, and drag to pan once zoomed in. */
private fun Modifier.pinchAndPan(zoom: ZoomState, key: Any?): Modifier =
	pointerInput(key) {
		detectTransformGestures { _, pan, gestureZoom, _ ->
			if (gestureZoom != 1f) zoom.zoomBy(gestureZoom)
			zoom.panBy(pan.x, pan.y)
		}
	}

/** Continuous vertical strip. Pages are decoded whole; see D10 on tiling. */
@Composable
private fun WebtoonStrip(
	pageSource: ReaderPageSource,
	pages: List<MangaPage>,
	zoom: ZoomState,
	widthPercent: Int,
) {
	val listState = rememberLazyListState()
	Box(
		modifier = Modifier
			.fillMaxSize()
			.clipToBounds()
			.ctrlScrollZoom(zoom)
			// Horizontal drag pans a zoomed strip. Vertical drag is left to the list so
			// scrolling the chapter keeps working, which is the whole point of this mode.
			.pointerInput(Unit) {
				detectTransformGestures { _, pan, gestureZoom, _ ->
					if (gestureZoom != 1f) zoom.zoomBy(gestureZoom)
					zoom.panBy(pan.x, 0f)
				}
			},
	) {
		LazyColumn(
			state = listState,
			horizontalAlignment = Alignment.CenterHorizontally,
			// The strip is narrowed here rather than by zooming out, so scroll distance
			// shrinks with it. Zooming a full-width strip would leave the same very
			// long page to scroll through, which is the actual complaint.
			modifier = Modifier
				.fillMaxHeight()
				.fillMaxWidth(widthPercent.coerceIn(20, 100) / 100f)
				.align(Alignment.TopCenter)
				.graphicsLayer {
				scaleX = zoom.scale
				scaleY = zoom.scale
				translationX = zoom.offsetX
				// Scale from the top so zooming does not jump the reader's position down
				// the strip, and from the centre horizontally so it grows evenly.
				transformOrigin = TransformOrigin(0.5f, 0f)
				},
		) {
			items(pages.size) { i ->
				PageImage(
					page = pages[i],
					pageSource = pageSource,
					contentScale = ContentScale.FillWidth,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}
}

@Composable
private fun PageImage(
	page: MangaPage,
	pageSource: ReaderPageSource,
	contentScale: ContentScale,
	modifier: Modifier = Modifier,
) {
	var bitmap: ImageBitmap? by remember(page.id) { mutableStateOf(null) }
	var failed by remember(page.id) { mutableStateOf(false) }

	LaunchedEffect(page.id) {
		failed = false
		bitmap = null
		for (attempt in 1..PAGE_ATTEMPTS) {
			if (attempt > 1) delay(PAGE_RETRY_DELAY_MS * (attempt - 1))
			val loaded = pageSource.image(page, attempt)
			if (loaded != null) {
				bitmap = loaded
				return@LaunchedEffect
			}
		}
		failed = true
	}

	val bmp = bitmap
	when {
		bmp != null -> Image(
			bitmap = bmp,
			contentDescription = null,
			contentScale = contentScale,
			modifier = modifier,
		)

		failed -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
			Text(
				text = "This page could not be loaded.",
				style = MaterialTheme.typography.bodyMedium,
				color = Color.White.copy(alpha = 0.7f),
			)
		}

		else -> LoadingBox(modifier)
	}
}

/** How many times to re-resolve and refetch a page before giving up on it. */
private const val PAGE_ATTEMPTS = 4

/** Backoff between page attempts, multiplied by the attempt number. */
private const val PAGE_RETRY_DELAY_MS = 400L

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f

/** Per wheel notch. Small enough that a fast scroll is not a jarring jump. */
private const val WHEEL_STEP = 1.15f

/** Per key press. Larger than a wheel notch, since keys are pressed deliberately. */
private const val KEY_STEP = 1.25f

/**
 * The reader's status bar, modelled on the one in a PDF or document viewer.
 *
 * Zoom used to be reachable only by Ctrl plus wheel, which is invisible, and the
 * underlying complaint was not really about zoom: a full-width webtoon strip on a wide
 * display is simply enormous. So the strip width lives here, next to zoom, in the place
 * people already look. Width persists; the zoom percentage is per session, being the
 * ad-hoc adjustment on top.
 */
@Composable
private fun ReaderStatusBar(
	index: Int,
	total: Int,
	widthPercent: Int,
	zoom: ZoomState,
	onWidthPercent: (Int) -> Unit,
) {
	// Deliberately lighter than the reader's black and separated by a rule. A bar that
	// blends into the page is a bar nobody finds, which is what the first version did.
	HorizontalDivider(color = Color.White.copy(alpha = 0.18f))
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(Color(0xFF2A2732))
			// Scrolls rather than clipping: at a large interface scale in a narrow
			// window the controls are wider than the bar, and a zoom slider you cannot
			// reach is worse than one you have to nudge sideways to.
			.horizontalScroll(rememberScrollState())
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(
			text = if (total == 0) "" else "Page ${index + 1} of $total",
			style = MaterialTheme.typography.bodyMedium,
			color = Color.White,
		)
		Spacer(Modifier.width(16.dp))

		// Width rather than zoom on purpose: narrowing the strip also shortens how far
		// there is to scroll, which zooming out does not.
		Text("Strip width", style = MaterialTheme.typography.bodyMedium, color = Color.White)
		Slider(
			value = widthPercent.toFloat(),
			onValueChange = { onWidthPercent(it.toInt()) },
			valueRange = 20f..100f,
			modifier = Modifier.width(150.dp),
		)
		Text(
			"$widthPercent%",
			style = MaterialTheme.typography.bodyMedium,
			color = Color.White,
			modifier = Modifier.widthIn(min = 64.dp),
		)

		VerticalDivider(modifier = Modifier.height(20.dp))

		Text("Zoom", style = MaterialTheme.typography.bodyMedium, color = Color.White)
		OutlinedButton(onClick = { zoom.zoomBy(1f / KEY_STEP) }) { Text("\u2212") }
		Slider(
			value = zoom.scale,
			onValueChange = zoom::zoomTo,
			valueRange = MIN_SCALE..MAX_SCALE,
			modifier = Modifier.width(150.dp),
		)
		OutlinedButton(onClick = { zoom.zoomBy(KEY_STEP) }) { Text("+") }
		// Fixed width so the row does not shuffle sideways as the number changes.
		TextButton(onClick = { zoom.reset() }, modifier = Modifier.widthIn(min = 76.dp)) {
			Text("${(zoom.scale * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
		}
	}
}
