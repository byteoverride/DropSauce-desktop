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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.core.util.ext.DebugFlags
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
	onChapterChange: (chapterIndex: Int, page: Int) -> Unit,
	/**
	 * Go looking for this title elsewhere, when there is anywhere to look.
	 *
	 * Null for a local comic, which came off this disk and has no other source. Retry is
	 * the right answer to a page that timed out and no answer at all to a chapter the
	 * source has pulled, and those look identical from here.
	 */
	onFindAlternative: (() -> Unit)? = null,
) {
	// One continuous strip across chapter boundaries. The reader asked for chapter two
	// to flow out of chapter one without a visible break, so the unit of loading is no
	// longer "the chapter being viewed": pages from several chapters live in one list
	// and the chapter is a property of the page you happen to be looking at.
	val strip = remember(chapterIndex) { mutableStateListOf<StripPage>() }
	var loadedThrough by remember(chapterIndex) { mutableStateOf(chapterIndex - 1) }
	var loading by remember(chapterIndex) { mutableStateOf(true) }
	var appending by remember(chapterIndex) { mutableStateOf(false) }
	var error: String? by remember(chapterIndex) { mutableStateOf(null) }
	// A chapter that failed to join on the end is reported under the strip, not in place
	// of it. Replacing the screen with an error would throw away the reader's position in
	// the pages they were happily reading, for a failure in a chapter they cannot see yet.
	var appendError: String? by remember(chapterIndex) { mutableStateOf(null) }
	var appendAttempt by remember(chapterIndex) { mutableStateOf(0) }
	var attempt by remember(chapterIndex) { mutableStateOf(0) }

	// The scroll position IS the current page. It used to be a separate counter that
	// only next() and previous() moved, so scrolling never changed it and the readout,
	// the history and the resume point all stayed on page one.
	val listState = remember(chapterIndex) { LazyListState() }
	val index by remember(chapterIndex) { derivedStateOf { listState.firstVisibleItemIndex } }
	var appliedInitial by remember(chapterIndex) { mutableStateOf(false) }
	val zoom = remember { ZoomState() }
	val settings by state.settings.data.collectAsState()

	val current = strip.getOrNull(index.coerceIn(0, (strip.size - 1).coerceAtLeast(0)))
	val chapter = current?.chapter ?: chapters[chapterIndex]
	val currentChapterIndex = current?.chapterIndex ?: chapterIndex

	suspend fun append(at: Int): Boolean {
		val target = chapters.getOrNull(at) ?: return false
		appendError = null
		// Cancellation is not a load failure and must not be reported as one. It means
		// the reader closed or moved on, and catching it here put "the coroutine scope
		// left the composition" under the strip as though the source had refused.
		val loaded = try {
			pageSource.pages(target)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			val message = e.message ?: e::class.simpleName ?: "Request failed"
			if (strip.isEmpty()) error = message else appendError = message
			return false
		}
		strip.addAll(
			loaded.mapIndexed { i, page ->
				StripPage(at, target, page, i, loaded.size)
			},
		)
		loadedThrough = at
		if (DebugFlags.isDebug) {
			println("[strip] joined chapter $at (${target.name}): +${loaded.size} pages, strip=${strip.size}")
		}
		return true
	}

	LaunchedEffect(chapterIndex, attempt) {
		loading = true
		error = null
		strip.clear()
		loadedThrough = chapterIndex - 1
		append(chapterIndex)
		loading = false
	}

	// Pull the next chapter in before the reader reaches the end, so the join is not a
	// pause. Appending to the end of a LazyColumn does not disturb the scroll position,
	// which is what makes the transition invisible.
	//
	// The scroll position is watched through a snapshot flow instead of being an effect
	// key. As a key it restarted the effect on every page that went past, which cancelled
	// the fetch that was in flight: scrolling steadily towards the end of a chapter, the
	// exact thing this exists for, could never finish loading the next one. A flow
	// collector keeps one coroutine and simply sees the latest values when it comes back
	// round, so a fetch always runs to completion.
	LaunchedEffect(chapterIndex) {
		// The last attempt this served, so a bump can be told apart from a scroll.
		var servedAttempt = appendAttempt
		snapshotFlow { StripDemand(index, strip.size, loadedThrough, appendAttempt) }
			.collect { demand ->
				if (demand.loaded == 0) return@collect
				if (demand.through >= chapters.lastIndex) return@collect
				// Someone pressed the button. That is a request, not a prediction, so it
				// skips the distance check: the whole reason the button exists is that
				// the automatic join did not happen, and making it depend on the same
				// condition that already failed would make it do nothing too.
				val asked = demand.attempt != servedAttempt
				if (!asked && demand.index < demand.loaded - CHAPTER_PREFETCH_PAGES) return@collect
				// A chapter that failed stays failed until the reader asks again, rather
				// than being retried on every page that scrolls past.
				if (!asked && appendError != null) return@collect
				servedAttempt = demand.attempt
				appending = true
				append(demand.through + 1)
				appending = false
			}
	}

	// Restoring the reading position has to happen AFTER the list exists. scrollToItem
	// suspends until the list is laid out, so calling it while `loading` is still true
	// waits for a LazyColumn that is not composed yet and never will be: the load
	// effect deadlocks and the reader stays blank.
	LaunchedEffect(chapterIndex, strip.size) {
		if (strip.isEmpty() || appliedInitial) return@LaunchedEffect
		appliedInitial = true
		// LAST_PAGE means "wherever the end is", which the caller cannot know: it is
		// stepping back into a chapter whose length nobody has fetched yet.
		val target = if (initialPage == LAST_PAGE) strip.lastIndex else initialPage.coerceIn(0, strip.lastIndex)
		if (target > 0) listState.scrollToItem(target)
	}

	// Keyboard navigation scrolls the strip rather than moving a counter beside it,
	// so keys and the scroll wheel cannot disagree about where the reader is.
	val scroller = rememberCoroutineScope()

	fun next() {
		if (index < strip.lastIndex) scroller.launch { listState.animateScrollToItem(index + 1) }
	}

	fun previous() {
		when {
			index > 0 -> scroller.launch { listState.animateScrollToItem(index - 1) }
			// Nothing above the strip's first page, so stepping back before it is the
			// one case that still swaps the screen out for an earlier chapter. It opens
			// at that chapter's end rather than its start, so reading backwards over the
			// join lands where the text continues instead of jumping a chapter back.
			chapterIndex > 0 -> onChapterChange(chapterIndex - 1, LAST_PAGE)
		}
	}

	// Record progress against the chapter the reader is actually in, which after a
	// transition is not the one the screen was opened on.
	LaunchedEffect(current?.chapter?.id, current?.pageInChapter) {
		val position = current ?: return@LaunchedEffect
		// Settle before writing. The page comes from the scroll position, so this
		// effect restarts on every page that passes and a flick through twenty pages
		// would otherwise be twenty database writes.
		delay(PROGRESS_SETTLE_MS)
		val within = (position.pageInChapter + 1).toFloat() / position.chapterPageCount
		val percent = ((position.chapterIndex + within) / chapters.size).coerceIn(0f, 1f)
		state.scope.launch {
			// Incognito is checked here rather than inside recordProgress so the reader
			// is the thing that decides, and so a caller cannot forget by not asking.
			if (!state.incognito.shouldRecordHistory(manga.id)) return@launch
			state.library.recordProgress(
				manga = manga,
				chapterId = position.chapter.id,
				page = position.pageInChapter,
				chaptersCount = chapters.size,
				percent = percent,
			)
		}
	}

	// Tell external trackers once per chapter, not once per page: services treat
	// progress as an absolute chapter count, so a page-level push would be noise.
	// Incognito suppresses this too, since a tracker is a more public record than
	// local history.
	LaunchedEffect(currentChapterIndex) {
		if (!state.incognito.shouldRecordHistory(manga.id)) return@LaunchedEffect
		runCatching { state.tracking.pushProgress(manga.id, chapters, chapter.id) }
			.onFailure { it.printStackTraceDebug() }
	}

	val focus = remember { FocusRequester() }
	LaunchedEffect(chapterIndex) { focus.requestFocus() }

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
			index = current?.pageInChapter ?: 0,
			total = current?.chapterPageCount ?: 0,
			onBack = onBack,
			bookmark = {
				val position = current
				if (position != null) {
					BookmarkToggle(
						repository = state.bookmarks,
						manga = manga,
						chapterId = position.chapter.id,
						pageId = position.page.id,
						page = position.pageInChapter,
						imageUrl = position.page.preview ?: position.page.url,
						percent = if (chapters.isEmpty()) {
							0f
						} else {
							val within = (position.pageInChapter + 1f) /
								position.chapterPageCount.coerceAtLeast(1)
							(position.chapterIndex + within) / chapters.size
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
			error != null -> ErrorBox(
				message = "Could not load pages.\n$error",
				onRetry = { attempt++ },
			) { FindAlternativeButton(onFindAlternative) }

			strip.isEmpty() -> ErrorBox(
				message = "This chapter has no pages.",
				onRetry = { attempt++ },
			) { FindAlternativeButton(onFindAlternative) }
			else -> WebtoonStrip(
				pageSource = pageSource,
				pages = strip,
				zoom = zoom,
				widthPercent = settings.webtoonWidthPercent,
				pageAttempts = settings.pageAttempts,
				listState = listState,
				footer = {
					// Only ever visible at the true end of what is loaded. Mid-strip
					// chapter joins are deliberately unmarked: the request was to not
					// notice them.
					StripFooter(
						appending = appending,
						error = appendError,
						hasMore = loadedThrough < chapters.lastIndex,
						// Bumping the attempt is what re-triggers the watcher; clearing
						// the error alone changes nothing it looks at. The same lambda
						// serves the manual nudge, because the watcher's other conditions
						// are all satisfied by the time a reader is looking at the footer.
						onLoadNext = {
							appendError = null
							appendAttempt++
						},
					)
				},
			)
		}
		}
		ReaderStatusBar(
			index = current?.pageInChapter ?: 0,
			total = current?.chapterPageCount ?: 0,
			widthPercent = settings.webtoonWidthPercent,
			zoom = zoom,
			onWidthPercent = { value ->
				state.scope.launch { state.settings.update { it.copy(webtoonWidthPercent = value) } }
			},
			// Relative to the chapter the visible page belongs to, not the one the screen
			// was opened on. After the strip has run into the next chapter those differ,
			// and stepping from the wrong one would skip or repeat a chapter.
			onNextChapter = (currentChapterIndex + 1)
				.takeIf { it <= chapters.lastIndex }
				?.let { next -> { onChapterChange(next, 0) } },
			onPreviousChapter = (currentChapterIndex - 1)
				.takeIf { it >= 0 }
				?.let { previous -> { onChapterChange(previous, 0) } },
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
internal fun WebtoonStrip(
	pageSource: ReaderPageSource,
	pages: List<StripPage>,
	zoom: ZoomState,
	widthPercent: Int,
	listState: LazyListState,
	pageAttempts: Int = DEFAULT_PAGE_ATTEMPTS,
	footer: @Composable () -> Unit,
) {
	// How many pages may be fetched and decoded at once.
	//
	// Decoding is not the cheap lazy thing it looks like: ImageCache turns the encoded
	// bytes into a ComposeImageBitmap, which allocates the whole ARGB buffer and
	// rasterises into it there and then. A long page is 43 MB and 131 ms. Without a
	// ceiling, every page the list composes starts one of those at the same moment, and
	// on a small machine the native allocation fails with no Java stack trace to show
	// for it.
	//
	// Sized from the machine because that is the resource being rationed, and kept at
	// two on anything normal: one being looked at, one arriving.
	val permits = remember {
		Semaphore(Runtime.getRuntime().availableProcessors().minus(1).coerceIn(1, 2))
	}
	BoxWithConstraints(
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
		// One screen, so an unloaded page occupies the space it will need rather than
		// pretending the chapter is a few hundred pixels long.
		val placeholder = maxHeight
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
			// No custom key on purpose. The strip only ever grows at the end, so the
			// default index key is already stable for everything on screen, and a page
			// id is not: sources do reuse one image URL across chapters, and a repeated
			// key in a LazyColumn is a crash rather than a glitch.
			items(pages.size) { i ->
				PageImage(
					page = pages[i].page,
					pageSource = pageSource,
					contentScale = ContentScale.FillWidth,
					// A page that has not arrived stands as tall as the window.
					//
					// It used to reserve whatever the spinner needed, about 36dp, because
					// LoadingBox fills its constraints and a LazyColumn item has no
					// height constraint to fill. Fifteen pages then fitted in one screen
					// and all fifteen began loading at once. Android does the same thing
					// the other way round: WebtoonImageView falls back to the parent's
					// height until it knows the page's own.
					placeholderHeight = placeholder,
					permits = permits,
					pageAttempts = pageAttempts,
					modifier = Modifier.fillMaxWidth(),
				)
			}
			item(key = "footer") { footer() }
		}
	}
}

@Composable
private fun PageImage(
	page: MangaPage,
	pageSource: ReaderPageSource,
	contentScale: ContentScale,
	/** What an unloaded page stands in at, so the list does not compose the whole chapter. */
	placeholderHeight: Dp,
	/** Bounds how many pages decode at once. See the comment where it is created. */
	permits: Semaphore,
	/**
	 * From settings, rather than a constant beside it.
	 *
	 * The setting has always been presented as "Retries per page image" and has never
	 * been read by anything; the reader used its own number and the downloader used none.
	 */
	pageAttempts: Int,
	modifier: Modifier = Modifier,
) {
	var bitmap: ImageBitmap? by remember(page.id) { mutableStateOf(null) }
	var failed by remember(page.id) { mutableStateOf(false) }
	var reason: String? by remember(page.id) { mutableStateOf(null) }
	// Bumped by the retry button, which re-runs the effect for this page alone.
	var retry by remember(page.id) { mutableStateOf(0) }

	LaunchedEffect(page.id, retry) {
		failed = false
		reason = null
		bitmap = null
		for (attempt in 1..pageAttempts) {
			if (attempt > 1) delay(PAGE_RETRY_DELAY_MS * (attempt - 1))
			// Held across the fetch and the decode, because the decode is the expensive
			// half and releasing before it would let every page allocate at once again.
			val loaded = permits.withPermit { pageSource.image(page, attempt) }
			if (loaded != null) {
				bitmap = loaded
				return@LaunchedEffect
			}
		}
		reason = pageSource.failureReason(page)
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

		failed -> Box(
			modifier = modifier.height(placeholderHeight),
			contentAlignment = Alignment.Center,
		) {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					// Saying which failure it was turns an unanswerable report into one
					// somebody can act on, and tells the reader whether waiting will help.
					text = reason?.let { "This page could not be loaded: $it." }
						?: "This page could not be loaded.",
					style = MaterialTheme.typography.bodyMedium,
					color = Color.White.copy(alpha = 0.7f),
				)
				TextButton(onClick = { retry++ }) { Text("Try again") }
			}
		}

		// Reserves a screen rather than the spinner's 36dp. This is the line that stops
		// the list composing fifteen pages at once.
		else -> LoadingBox(modifier.height(placeholderHeight))
	}
}


/** Offered only when there is somewhere else to look, which a local comic has not. */
@Composable
private fun FindAlternativeButton(onFindAlternative: (() -> Unit)?) {
	if (onFindAlternative != null) {
		TextButton(onClick = onFindAlternative) { Text("Find it on another source") }
	}
}

/**
 * Fallback when nothing supplies the setting, which is only the tests.
 *
 * Matches SettingsData.pageAttempts so a test and the app agree by default.
 */
internal const val DEFAULT_PAGE_ATTEMPTS = 3

/** How long the reader must stay on a page before that position is saved. */
private const val PROGRESS_SETTLE_MS = 600L

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
	/** Null on the last chapter, and on a local comic with nothing after it. */
	onNextChapter: (() -> Unit)?,
	/** Null on the first chapter. */
	onPreviousChapter: (() -> Unit)?,
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

		// Chapter navigation, next to the page counter rather than hidden in the top bar.
		// The strip is supposed to run into the following chapter on its own and does not
		// always manage it, and a reader who has reached the end of one should never have
		// to go back out to the title to carry on.
		VerticalDivider(modifier = Modifier.height(20.dp))
		OutlinedButton(
			onClick = { onPreviousChapter?.invoke() },
			enabled = onPreviousChapter != null,
		) { Text("\u2039 Previous") }
		OutlinedButton(
			onClick = { onNextChapter?.invoke() },
			enabled = onNextChapter != null,
		) { Text("Next \u203A") }

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

/**
 * What the chapter watcher looks at: where the reader is, how much is loaded, and whether
 * a retry has been asked for.
 *
 * A value class rather than four separate flows so the collector wakes once per change
 * and sees a consistent set, and so equality drops the repeats.
 */
private data class StripDemand(
	val index: Int,
	val loaded: Int,
	val through: Int,
	val attempt: Int,
)

/**
 * A page in the continuous strip, carrying the chapter it came from.
 *
 * The chapter has to travel with the page because the strip spans several of them: the
 * reader's current chapter is whichever one the visible page belongs to, not the one
 * the screen was opened on.
 */
internal data class StripPage(
	val chapterIndex: Int,
	val chapter: MangaChapter,
	val page: MangaPage,
	val pageInChapter: Int,
	val chapterPageCount: Int,
)

/**
 * How close to the end of the loaded strip to get before pulling the next chapter in.
 *
 * Far enough ahead that the fetch finishes before the reader arrives, which is the
 * whole point: a join the reader waits at is a join they notice.
 */
private const val CHAPTER_PREFETCH_PAGES = 3

/** Passed as the initial page to mean "open at the last page", whatever its number is. */
const val LAST_PAGE = -1

/**
 * What sits under the last loaded page.
 *
 * Nothing is drawn between chapters, only after the last one that has been fetched, so in
 * normal reading this is off-screen ahead of the reader and never seen.
 */
@Composable
private fun StripFooter(
	appending: Boolean,
	error: String?,
	hasMore: Boolean,
	/** Retries a failed join, and nudges one that simply has not happened. */
	onLoadNext: () -> Unit,
) {
	when {
		error != null -> Column(
			modifier = Modifier.fillMaxWidth().padding(24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = "Could not load the next chapter.\n$error",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			TextButton(onClick = onLoadNext) { Text("Try again") }
		}

		appending -> Box(
			modifier = Modifier.fillMaxWidth().padding(24.dp),
			contentAlignment = Alignment.Center,
		) {
			CircularProgressIndicator(strokeWidth = 2.dp)
		}

		!hasMore -> Text(
			text = "You are at the end of this title.",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.fillMaxWidth().padding(24.dp),
			textAlign = TextAlign.Center,
		)

		// There is a next chapter and it has not arrived. This branch used to draw
		// nothing at all, which is the whole of the bug report: the strip is meant to run
		// into the following chapter by itself, and when it does not the reader is left
		// at a blank end with no indication that anything was supposed to happen and
		// nothing to press. Whatever stopped the automatic join, this is a way through.
		else -> Column(
			modifier = Modifier.fillMaxWidth().padding(24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = "End of this chapter.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			TextButton(onClick = onLoadNext) { Text("Load the next chapter") }
		}
	}
}
