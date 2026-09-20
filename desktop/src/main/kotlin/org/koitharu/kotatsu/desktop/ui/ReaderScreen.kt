package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/** Reading direction. Webtoon is one continuous vertical strip; the others are paged. */
enum class ReaderMode { PAGED_LTR, PAGED_RTL, WEBTOON }

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
	source: MangaParserSource,
	manga: Manga,
	chapters: List<MangaChapter>,
	chapterIndex: Int,
	initialPage: Int,
	onBack: () -> Unit,
	onChapterChange: (Int) -> Unit,
) {
	val session = remember(source) { state.sources.session(source) }
	val chapter = chapters[chapterIndex]
	val pages = remember(chapter.id) { mutableStateListOf<MangaPage>() }
	var loading by remember(chapter.id) { mutableStateOf(true) }
	var error: String? by remember(chapter.id) { mutableStateOf(null) }
	var attempt by remember(chapter.id) { mutableStateOf(0) }
	// Only the chapter opened from a resume starts mid-way; moving on to the next chapter
	// must start at its beginning, which is why this keys on the chapter.
	var index by remember(chapter.id) { mutableStateOf(0) }
	var appliedInitial by remember(chapter.id) { mutableStateOf(false) }
	var mode by remember { mutableStateOf(ReaderMode.PAGED_LTR) }

	LaunchedEffect(chapter.id, attempt) {
		loading = true
		error = null
		pages.clear()
		runCatching { withContext(Dispatchers.IO) { session.parser.getPages(chapter) } }
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
			state.library.recordProgress(
				manga = manga,
				chapterId = chapter.id,
				page = index,
				chaptersCount = chapters.size,
				percent = percent,
			)
		}
	}

	val focus = remember { FocusRequester() }
	LaunchedEffect(chapter.id) { focus.requestFocus() }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(Color.Black)
			.focusRequester(focus)
			.focusable()
			.onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				// Reading direction decides what "forward" means for the arrow keys.
				val forward = if (mode == ReaderMode.PAGED_RTL) Key.DirectionLeft else Key.DirectionRight
				val back = if (mode == ReaderMode.PAGED_RTL) Key.DirectionRight else Key.DirectionLeft
				when (event.key) {
					forward, Key.PageDown, Key.Spacebar -> { next(); true }
					back, Key.PageUp -> { previous(); true }
					Key.Escape -> { onBack(); true }
					else -> false
				}
			},
	) {
		ReaderBar(
			manga = manga,
			chapter = chapter,
			index = index,
			total = pages.size,
			mode = mode,
			onMode = { mode = it },
			onBack = onBack,
		)
		when {
			loading -> LoadingBox()
			error != null -> ErrorBox("Could not load pages.\n$error", onRetry = { attempt++ })
			pages.isEmpty() -> ErrorBox("This chapter has no pages.", onRetry = { attempt++ })
			mode == ReaderMode.WEBTOON -> WebtoonStrip(state, source, session.client, pages)
			else -> PagedView(state, source, session.client, pages, index)
		}
	}
}

@Composable
private fun ReaderBar(
	manga: Manga,
	chapter: MangaChapter,
	index: Int,
	total: Int,
	mode: ReaderMode,
	onMode: (ReaderMode) -> Unit,
	onBack: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Button(onClick = onBack) { Text("Back") }
		Column(Modifier.weight(1f)) {
			Text(
				text = chapter.title ?: "Chapter ${chapter.number}",
				style = MaterialTheme.typography.bodyMedium,
				color = Color.White,
				maxLines = 1,
			)
			Text(
				text = if (total == 0) manga.title else "${index + 1} / $total",
				style = MaterialTheme.typography.bodySmall,
				color = Color.White.copy(alpha = 0.7f),
			)
		}
		for (option in ReaderMode.entries) {
			Button(
				onClick = { onMode(option) },
				enabled = option != mode,
			) {
				Text(
					when (option) {
						ReaderMode.PAGED_LTR -> "LTR"
						ReaderMode.PAGED_RTL -> "RTL"
						ReaderMode.WEBTOON -> "Webtoon"
					},
				)
			}
		}
	}
}

/** One page at a time, with pinch/scroll zoom and drag to pan. */
@Composable
private fun PagedView(
	state: AppState,
	source: MangaParserSource,
	client: okhttp3.OkHttpClient,
	pages: List<MangaPage>,
	index: Int,
) {
	var scale by remember(index) { mutableStateOf(1f) }
	var offsetX by remember(index) { mutableStateOf(0f) }
	var offsetY by remember(index) { mutableStateOf(0f) }
	Box(
		modifier = Modifier
			.fillMaxSize()
			.pointerInput(index) {
				detectTransformGestures { _, pan, zoom, _ ->
					scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
					if (scale > 1f) {
						offsetX += pan.x
						offsetY += pan.y
					} else {
						offsetX = 0f
						offsetY = 0f
					}
				}
			},
		contentAlignment = Alignment.Center,
	) {
		PageImage(
			page = pages[index],
			state = state,
			source = source,
			client = client,
			contentScale = ContentScale.Fit,
			modifier = Modifier.fillMaxSize().graphicsLayer {
				scaleX = scale
				scaleY = scale
				translationX = offsetX
				translationY = offsetY
			},
		)
	}
}

/** Continuous vertical strip. Pages are decoded whole; see D10 on tiling. */
@Composable
private fun WebtoonStrip(
	state: AppState,
	source: MangaParserSource,
	client: okhttp3.OkHttpClient,
	pages: List<MangaPage>,
) {
	val listState = rememberLazyListState()
	LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
		items(pages.size) { i ->
			PageImage(
				page = pages[i],
				state = state,
				source = source,
				client = client,
				contentScale = ContentScale.FillWidth,
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}

@Composable
private fun PageImage(
	page: MangaPage,
	state: AppState,
	source: MangaParserSource,
	client: okhttp3.OkHttpClient,
	contentScale: ContentScale,
	modifier: Modifier = Modifier,
) {
	var bitmap: ImageBitmap? by remember(page.id) { mutableStateOf(null) }
	var failed by remember(page.id) { mutableStateOf(false) }

	LaunchedEffect(page.id) {
		failed = false
		bitmap = null
		// Retry with a pause, because the failure is transient rather than structural.
		// Measured with a live 2x2 control (DECISIONS.md D20): whichever client requests
		// a given page url FIRST gets a 404, and every later request for the same url
		// through the very same client gets a 200. So the node is cold on first touch,
		// not hostile to our client. An immediate retry is often still too early, which
		// is why this backs off rather than hammering.
		for (attempt in 1..PAGE_ATTEMPTS) {
			if (attempt > 1) delay(PAGE_RETRY_DELAY_MS * (attempt - 1))
			val url = runCatching {
				withContext(Dispatchers.IO) { state.sources.pageUrl(source, page) }
			}.getOrNull()
			if (url == null) continue
			state.images.forget(url)
			val loaded = state.images.load(url, client)
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
