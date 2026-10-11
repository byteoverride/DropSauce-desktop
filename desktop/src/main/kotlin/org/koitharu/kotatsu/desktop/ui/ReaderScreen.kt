package org.koitharu.kotatsu.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * The screen is immersive: the shell hides the navigation rail for it, so the strip has
 * the whole window and the chrome in here is the way out. The chrome overlays the strip
 * rather than sitting beside it, and auto-hides, for the reason spelled out at
 * [ReaderTopChrome].
 */
@OptIn(ExperimentalComposeUiApi::class)
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

	// Chrome state. Visible on open, and gone again a couple of seconds after the reader
	// stops doing anything, because the thing this screen is for is the page.
	var chromeShown by remember { mutableStateOf(true) }
	var chromeFocused by remember { mutableStateOf(false) }
	var displayOpen by remember { mutableStateOf(false) }
	val topHover = remember { MutableInteractionSource() }
	val bottomHover = remember { MutableInteractionSource() }
	val pointerOnTop by topHover.collectIsHoveredAsState()
	val pointerOnBottom by bottomHover.collectIsHoveredAsState()
	val idle = remember { IdleClock() }

	/**
	 * Whether the reader put the chrome away on purpose.
	 *
	 * Without this a click that hides the bars is undone by the next pixel of pointer
	 * movement, which is every click: the hand that clicked is still on the mouse. So a
	 * deliberate hide latches, and only another click, or Tab, takes it off again.
	 */
	var chromeHiddenByUser by remember { mutableStateOf(false) }

	fun reveal() {
		if (chromeHiddenByUser) return
		idle.sinceNanos = System.nanoTime()
		// A no-op when it is already true: assigning a mutableStateOf its current value
		// records no write, which is what keeps a pointer move off the recomposition path.
		chromeShown = true
	}

	/**
	 * Click the page to put the chrome away, click again to bring it back.
	 *
	 * The bars sit over the page, so on a long strip they cover art at the top and bottom
	 * of every screenful. The timer already hides them, but only once the reader stops
	 * moving, and reading a webtoon is continuous scrolling: in practice they were always
	 * on. This is the way to say "not now" and have it stick.
	 */
	fun toggleChrome() {
		if (chromeShown) {
			chromeShown = false
			chromeHiddenByUser = true
		} else {
			chromeHiddenByUser = false
			idle.sinceNanos = System.nanoTime()
			chromeShown = true
		}
	}

	// A control that vanishes under the cursor is worse than one that is always there, so
	// none of these lets the timer run: the pointer resting on either bar, a chrome
	// control holding focus, or the Display popup being open.
	val holdOpen = pointerOnTop || pointerOnBottom || chromeFocused || displayOpen
	LaunchedEffect(chromeShown, holdOpen) {
		if (!chromeShown || holdOpen) return@LaunchedEffect
		// Polled against a plain timestamp rather than keyed on the input itself. A
		// pointer move arrives dozens of times a second and every one of those as a
		// snapshot write would recompose the reader at mouse-move rate to feed a timer.
		while (true) {
			val waited = (System.nanoTime() - idle.sinceNanos) / NANOS_PER_MS
			if (waited >= CHROME_HIDE_MS) break
			delay(CHROME_HIDE_MS - waited)
		}
		chromeShown = false
	}

	ReaderTheme {
		// Alpha only, and fast. A slide would change the bars' bounds, and a bound change
		// over a scrolling strip is the reflow this layout was chosen to avoid: D23 wants
		// a chapter join to be invisible, and a strip that shifts under the reader is the
		// opposite of that.
		val chromeAlpha by animateFloatAsState(
			targetValue = if (chromeShown) 1f else 0f,
			animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
			label = "reader chrome",
		)
		// A Box, not a Column. The old Column needed the page area to take weight(1f) so
		// that the status bar below it was not starved of height; here `align` puts the
		// bars over the strip and neither can starve the other. The strip gets the whole
		// window, and chrome coming and going does not reflow it.
		Box(
			modifier = Modifier
				.fillMaxSize()
				// The page ground stays black, for the reason it always was: a manga page
				// is ink on paper photographed, and a pale surround throws light at the
				// reader from around the art. What changed is that it is spelled once, as
				// a token, instead of at eleven call sites.
				.background(DropSauceReaderColors.ReaderPage)
				.focusRequester(focus)
				.focusable()
				// Take focus back on any press inside the reader. The page area is a
				// scrollable list, so clicking it moves focus off this Box and silently
				// kills every keyboard shortcut: arrows, space, Escape and zoom all stop
				// working with nothing on screen to explain why. Runs on the Initial pass
				// and consumes nothing, so gestures below are unaffected.
				.pointerInput(Unit) {
					awaitEachGesture {
						awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
						focus.requestFocus()
					}
				}
				// Taps, not presses. detectTapGestures waits for the release and gives up
				// the moment the pointer travels far enough to be a drag, so scrolling the
				// strip and panning a zoomed page never read as a click. It runs on the
				// Main pass, after the list below has had its refusal, and the list only
				// consumes movement.
				.pointerInput(Unit) {
					detectTapGestures(onTap = { toggleChrome() })
				}
				.revealOnPointerInput { reveal() }
				.onPreviewKeyEvent { event ->
					if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
					// Read before revealing: the Tab branch below needs to know whether
					// the chrome was already there.
					val wasShown = chromeShown
					// Tab is keyboard access to the controls, so it overrides a hide the
					// pointer asked for; nothing else does, or a hidden bar would come
					// back on the first arrow key and never stay away while reading.
					if (event.key == Key.Tab) chromeHiddenByUser = false
					reveal()
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
						// Tab is the way into the chrome. Hidden chrome is not composed and
						// so is not in the tab order at all, which is the point: Tab must
						// never land on something invisible. So the first Tab spends itself
						// bringing the bars back and is consumed here; once they are there,
						// Tab is left alone and traverses into them, back first.
						Key.Tab -> !wasShown
						Key.Escape -> {
							// Escape in the chrome goes back to the page; Escape on the
							// page leaves the reader, which is what it always did.
							if (chromeFocused) focus.requestFocus() else onBack()
							true
						}
						else -> false
					}
				},
		) {
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
					// A page giving up is something to be told about, and the retry for it
					// is on the page rather than in the bar, but the bar is how you leave a
					// chapter that will not load at all.
					onPageFailed = { reveal() },
					footer = {
						// The footer composing means the reader has run out of loaded strip.
						// That is exactly when the chapter controls are wanted back.
						LaunchedEffect(Unit) { reveal() }
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
			// Composed only while it is drawn. At alpha 0 the bars are not in the
			// composition, so they are not in the tab order either, and Tab cannot land
			// on an invisible control.
			if (chromeAlpha > 0f) {
				ReaderTopChrome(
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
					modifier = Modifier
						.align(Alignment.TopCenter)
						.alpha(chromeAlpha)
						.hoverable(topHover)
						.onFocusChanged {
							if (it.hasFocus) reveal()
							chromeFocused = it.hasFocus
						},
				)
				ReaderBottomChrome(
					index = current?.pageInChapter ?: 0,
					total = current?.chapterPageCount ?: 0,
					widthPercent = settings.webtoonWidthPercent,
					zoom = zoom,
					onWidthPercent = { value ->
						state.scope.launch { state.settings.update { it.copy(webtoonWidthPercent = value) } }
					},
					// Relative to the chapter the visible page belongs to, not the one the
					// screen was opened on. After the strip has run into the next chapter
					// those differ, and stepping from the wrong one would skip or repeat a
					// chapter.
					onNextChapter = (currentChapterIndex + 1)
						.takeIf { it <= chapters.lastIndex }
						?.let { next -> { onChapterChange(next, 0) } },
					onPreviousChapter = (currentChapterIndex - 1)
						.takeIf { it >= 0 }
						?.let { previous -> { onChapterChange(previous, 0) } },
					displayOpen = displayOpen,
					onDisplayOpen = { displayOpen = it },
					modifier = Modifier
						.align(Alignment.BottomCenter)
						.alpha(chromeAlpha)
						.hoverable(bottomHover)
						.onFocusChanged {
							if (it.hasFocus) reveal()
							chromeFocused = it.hasFocus
						},
				)
			}
		}
	}
}

/**
 * The reader's own theme, nested inside the app's.
 *
 * Substituting a token at each call site was the alternative and it is the worse one: the
 * reader draws Material's own controls, and a `Slider` or a `DropdownMenu` or [ErrorBox]
 * has no colour parameter for a caller to override. Wrapping once means every one of them
 * is reader-coloured in both app themes with no colour code at the call site at all.
 *
 * [MaterialTheme.motionScheme] is passed **explicitly**. A nested theme replaces the whole
 * bundle, so a motion scheme the reader quietly fell back on would be `MotionScheme`
 * expressive silently lost in here with nothing on screen to show for it — which is the
 * class of regression DECISIONS.md D6a exists to catch. `ReaderThemeTest` pins it, with a
 * positive control proving the assertion can tell a kept scheme from a lost one.
 */
@Composable
internal fun ReaderTheme(content: @Composable () -> Unit) {
	MaterialExpressiveTheme(
		colorScheme = DropSauceReaderScheme,
		motionScheme = MaterialTheme.motionScheme,
		content = content,
	)
}

/**
 * The Display popup's own fill, one step above the bar it opens from.
 *
 * Not in [DropSauceReaderColors] because nothing outside this screen has a popup over a
 * black page. Measured against it: [DropSauceReaderColors.ReaderOnChrome] 8.21:1,
 * `ReaderOnChromeVariant` 5.45:1, `ReaderAccent` 5.40:1. `ReaderChromeOutline` is only
 * 2.92:1 here, which is why the slider's inactive track is drawn in the variant text
 * colour instead and the outline is kept for the popup's own border, where it is read
 * against the page at 6.60:1.
 */
private val ReaderPopupContainer = Color(0xFF42484B)

/**
 * The dark scheme with the reader's six tokens substituted into the roles its controls
 * actually read.
 *
 * Dark rather than light-or-dark on purpose: the page is black whichever theme the app is
 * set to, so everything drawn against it has to be the dark end of the ramp. See
 * [DropSauceReaderColors] for why the reader does not follow `ThemeMode`.
 */
private val DropSauceReaderScheme: ColorScheme = DropSauceDarkColors.copy(
	primary = DropSauceReaderColors.ReaderAccent,
	background = DropSauceReaderColors.ReaderPage,
	onBackground = DropSauceReaderColors.ReaderOnChrome,
	surface = DropSauceReaderColors.ReaderPage,
	onSurface = DropSauceReaderColors.ReaderOnChrome,
	onSurfaceVariant = DropSauceReaderColors.ReaderOnChromeVariant,
	surfaceContainer = DropSauceReaderColors.ReaderChrome,
	surfaceContainerHigh = ReaderPopupContainer,
	outline = DropSauceReaderColors.ReaderChromeOutline,
	// The reader has one rule and it is structural, so it is not allowed to be the
	// decorative hairline `outlineVariant` normally is.
	outlineVariant = DropSauceReaderColors.ReaderChromeOutline,
)

/**
 * The top bar: the way out, where you are, and the bookmark.
 *
 * Back is an [IconButton], not a filled `Button`. The filled pill made leaving the reader
 * the highest-contrast element on the reading screen, which is the wrong thing to shout
 * about on a screen whose primary action is reading. There is no filled button in the
 * reader chrome at all for the same reason.
 */
@Composable
private fun ReaderTopChrome(
	manga: Manga,
	sourceLabel: String,
	chapter: MangaChapter,
	index: Int,
	total: Int,
	onBack: () -> Unit,
	bookmark: @Composable () -> Unit = {},
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.sm),
		) {
			IconButton(onClick = onBack) {
				AppIcon(AppIcons.BACK, contentDescription = "Leave the reader")
			}
			Column(Modifier.weight(1f)) {
				Text(
					text = chapter.title ?: "Chapter ${chapter.number}",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				// Title, source and position on one line instead of three stacked Texts
				// each carrying its own condition. Only the parts that exist are joined,
				// so nothing can render a trailing separator.
				val detail = listOfNotNull(
					manga.title.takeIf { it.isNotEmpty() },
					sourceLabel.takeIf { it.isNotEmpty() },
					if (total > 0) "${index + 1} / $total" else null,
				)
				if (detail.isNotEmpty()) {
					Text(
						text = detail.joinToString(META_SEPARATOR),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			bookmark()
		}
		// The bar is 1.71:1 against the page, so the bar alone does not carry its own
		// edge. This rule does: 3.86:1 on the bar side and 6.60:1 on the page side, which
		// clears the 3:1 a non-text boundary needs on both. A bar that blends into the
		// page is a bar nobody finds, which is what the first version did.
		HorizontalDivider(color = MaterialTheme.colorScheme.outline)
	}
}

/**
 * The bottom bar: where you are, the chapter either side, and the way to Display.
 *
 * Four fixed controls and one flexible gap, which is the whole design. The row it replaces
 * laid a page counter, two labelled buttons, a width slider, a percentage, a zoom slider
 * and two stepper buttons in a line and needed about 1125 dp to do it, against 767 dp
 * available at a 1270 px window: it was cut mid-word at "Zoom" and the `horizontalScroll`
 * that was supposed to rescue it gave no hint that there was anything to scroll to. This
 * needs 332 dp, and because the only elastic element is a `Spacer(weight(1f))` the way it
 * degrades is that the gap closes — never that a control leaves the window.
 */
@Composable
private fun ReaderBottomChrome(
	index: Int,
	total: Int,
	widthPercent: Int,
	zoom: ZoomState,
	onWidthPercent: (Int) -> Unit,
	/** Null on the last chapter, and on a local comic with nothing after it. */
	onNextChapter: (() -> Unit)?,
	/** Null on the first chapter. */
	onPreviousChapter: (() -> Unit)?,
	displayOpen: Boolean,
	onDisplayOpen: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
		HorizontalDivider(color = MaterialTheme.colorScheme.outline)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.md),
		) {
			Text(
				text = if (total == 0) "" else "Page ${index + 1} of $total",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(min = PAGE_COUNTER_WIDTH),
			)
			// Chapter navigation stays next to the page counter rather than moving to the
			// top bar. The strip is supposed to run into the following chapter on its own
			// and does not always manage it, and a reader who has reached the end of one
			// should never have to go back out to the title to carry on.
			IconButton(
				onClick = { onPreviousChapter?.invoke() },
				enabled = onPreviousChapter != null,
			) { AppIcon(AppIcons.CHEVRON_LEFT, contentDescription = "Previous chapter") }
			IconButton(
				onClick = { onNextChapter?.invoke() },
				enabled = onNextChapter != null,
			) { AppIcon(AppIcons.CHEVRON_RIGHT, contentDescription = "Next chapter") }
			Spacer(Modifier.weight(1f))
			Box {
				IconButton(onClick = { onDisplayOpen(!displayOpen) }) {
					AppIcon(AppIcons.READER_PREFS, contentDescription = "Display options")
				}
				DisplayMenu(
					expanded = displayOpen,
					onDismiss = { onDisplayOpen(false) },
					widthPercent = widthPercent,
					onWidthPercent = onWidthPercent,
					zoom = zoom,
				)
			}
		}
	}
}

/**
 * Strip width and zoom, in a popup.
 *
 * They belong together and they belong here. Zoom used to be reachable only by Ctrl plus
 * wheel, which is invisible, and the underlying complaint was never really about zoom: a
 * full-width webtoon strip on a wide display is simply enormous, and only narrowing it
 * shortens how far there is to scroll. Width persists; zoom is per session, being the
 * ad-hoc adjustment on top.
 *
 * A popup rather than an inline row because a `Popup` is clamped to the window by its own
 * position provider, so it cannot be cut off by the window edge the way the bar was. The
 * `−` and `+` stepper buttons are gone: they existed to make zoom discoverable, which a
 * labelled slider in a panel called Display now does, and `+`/`-`/`0` are still wired on
 * the keyboard.
 */
@Composable
private fun DisplayMenu(
	expanded: Boolean,
	onDismiss: () -> Unit,
	widthPercent: Int,
	onWidthPercent: (Int) -> Unit,
	zoom: ZoomState,
) {
	val scheme = MaterialTheme.colorScheme
	DropdownMenu(
		expanded = expanded,
		onDismissRequest = onDismiss,
		modifier = Modifier.width(DISPLAY_MENU_WIDTH),
		shape = MaterialTheme.shapes.medium,
		containerColor = scheme.surfaceContainerHigh,
		border = BorderStroke(1.dp, scheme.outline),
	) {
		Column(
			modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
			verticalArrangement = Arrangement.spacedBy(Space.xs),
		) {
			SettingLabel("Strip width", "$widthPercent%")
			Slider(
				value = widthPercent.toFloat(),
				onValueChange = { onWidthPercent(it.toInt()) },
				valueRange = 20f..100f,
				colors = readerSliderColors(),
			)
			SettingLabel("Zoom", "${(zoom.scale * 100).toInt()}%")
			Slider(
				value = zoom.scale,
				onValueChange = zoom::zoomTo,
				valueRange = MIN_SCALE..MAX_SCALE,
				colors = readerSliderColors(),
			)
			TextButton(
				onClick = { zoom.reset() },
				modifier = Modifier.align(Alignment.End),
			) { Text("Reset") }
		}
	}
}

/** A setting's name and its current value, on one line above its slider. */
@Composable
private fun SettingLabel(name: String, value: String) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = name,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = value,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * Slider tracks on the Display popup.
 *
 * The inactive track is the variant text colour, not the chrome outline: the outline is
 * 2.92:1 against this popup's fill and would miss the 3:1 that a control's own boundary
 * needs, while the variant reads at 5.45:1.
 */
@Composable
private fun readerSliderColors() = SliderDefaults.colors(
	thumbColor = MaterialTheme.colorScheme.primary,
	activeTrackColor = MaterialTheme.colorScheme.primary,
	inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * Any pointer activity brings the chrome back.
 *
 * Watched on the Initial pass, which runs outermost first: the strip consumes scroll on
 * its way through, and chrome that only returned for input the page did not want would be
 * chrome that never returned while reading.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.revealOnPointerInput(onInput: () -> Unit): Modifier = this
	.onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { onInput() }
	.onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { onInput() }

/**
 * When the reader last did anything.
 *
 * A plain field rather than a `mutableStateOf`, deliberately. A pointer move arrives
 * dozens of times a second, and each one as a snapshot write would recompose the reader
 * at mouse-move rate in order to feed a timer that nothing draws.
 */
private class IdleClock {
	var sinceNanos: Long = System.nanoTime()
}

/** How long after the last input the chrome gets out of the way. */
private const val CHROME_HIDE_MS = 2500L

private const val NANOS_PER_MS = 1_000_000L

/** Wide enough for "Page 100 of 150" without the controls beside it moving. */
private val PAGE_COUNTER_WIDTH = 120.dp

private val DISPLAY_MENU_WIDTH = 280.dp

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
	/** A page has given up. Defaulted so a test can drive the strip without caring. */
	onPageFailed: () -> Unit = {},
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
			//
			// Nothing in here animates, and nothing in here may. D23 wants a chapter join
			// to be invisible, and an item-placement animation or a crossfade on page
			// content is precisely what would make one visible.
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
					onFailed = onPageFailed,
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
	/** Called once, when this page has run out of attempts. */
	onFailed: () -> Unit,
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
		onFailed()
	}

	val bmp = bitmap
	when {
		bmp != null -> Image(
			bitmap = bmp,
			contentDescription = null,
			contentScale = contentScale,
			modifier = modifier,
		)

		// The shared failure shape, under the reader's own theme, instead of a fourth one.
		// This was centred body text plus a bare "Try again" link, which made the app's
		// error treatments four: it gains the error icon, a headline separate from the
		// reason, and a button that looks like one. Saying which failure it was turns an
		// unanswerable report into one somebody can act on, and tells the reader whether
		// waiting will help.
		//
		// placeholderHeight is kept. Reserving a screen's worth of height is what stops
		// the list composing fifteen pages at once, and a failed page is still a page the
		// list has to give room to.
		failed -> ErrorBox(
			message = reason ?: "The source did not say why.",
			onRetry = { retry++ },
			modifier = modifier.height(placeholderHeight),
			headline = "This page could not be loaded",
		)

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
 *
 * The four states are unchanged; only how they are drawn is. D23's rule — a chapter that
 * fails to load is reported under the strip, not in place of it — is why this stays a
 * footer and why the failure takes the compact form of the shared error box rather than
 * the full-height one, which would stand a screen tall under the page.
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
		error != null -> ErrorBox(
			message = "Could not load the next chapter. $error",
			onRetry = onLoadNext,
			modifier = Modifier.padding(vertical = Space.md),
			compact = true,
		)

		appending -> LoadingStrip(
			label = "Loading the next chapter",
			modifier = Modifier.padding(vertical = Space.md),
		)

		!hasMore -> Text(
			text = "You are at the end of this title.",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.fillMaxWidth().padding(Space.xl),
			textAlign = TextAlign.Center,
		)

		// There is a next chapter and it has not arrived. This branch used to draw
		// nothing at all, which is the whole of the bug report: the strip is meant to run
		// into the following chapter by itself, and when it does not the reader is left
		// at a blank end with no indication that anything was supposed to happen and
		// nothing to press. Whatever stopped the automatic join, this is a way through.
		else -> Column(
			modifier = Modifier.fillMaxWidth().padding(Space.xl),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Space.sm),
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
