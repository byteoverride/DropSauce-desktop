package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.image.ImageCache

/**
 * A remote image, fetched through [client] so the source's own headers apply.
 *
 * Shows nothing while loading and a muted placeholder if the image cannot be decoded,
 * rather than an error, because a missing cover is common and not worth shouting about.
 */
@Composable
fun RemoteImage(
	url: String?,
	client: OkHttpClient,
	cache: ImageCache,
	contentDescription: String?,
	modifier: Modifier = Modifier,
	contentScale: ContentScale = ContentScale.Crop,
	// The reader passes Transparent: a surfaceVariant letterbox around a manga page looks
	// like a rendering fault rather than a background.
	background: Color = Color.Unspecified,
) {
	val fill = if (background.isSpecified) background else MaterialTheme.colorScheme.surfaceVariant
	// Measures itself rather than making every caller pass a size. A cover is drawn at a
	// few hundred pixels in a grid and much larger on a details screen, and the right
	// decode size is whatever this particular box turned out to be.
	BoxWithConstraints(modifier = modifier.background(fill)) {
		val targetWidth = decodeWidthFor(constraints.maxWidth, constraints.maxHeight)
		var bitmap: ImageBitmap? by remember(url, targetWidth) { mutableStateOf(null) }
		var settled by remember(url, targetWidth) { mutableStateOf(false) }
		var reason: String? by remember(url, targetWidth) { mutableStateOf(null) }
		LaunchedEffect(url, targetWidth) {
			bitmap = url?.let { cache.load(it, client, targetWidth) }
			reason = if (bitmap == null) url?.let { cache.failureReason(it) } else null
			settled = true
		}
		val bmp = bitmap
		if (bmp != null) {
			Image(
				bitmap = bmp,
				contentDescription = contentDescription,
				contentScale = contentScale,
				modifier = Modifier.fillMaxSize(),
			)
		} else if (settled) {
			Text(
				// "no cover" was the same square whether the source had none, answered
				// 403, or served something Skia cannot read. Only the first is normal.
				text = reason ?: "no cover",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.align(Alignment.Center).padding(4.dp),
			)
		}
	}
}

/**
 * What width to decode for a box this size, in pixels, or 0 for "decode whole".
 *
 * The height matters because the default [ContentScale.Crop] fills both axes: a portrait
 * cover in a cell taller than the cover's own ratio is scaled up until it covers, so
 * decoding at the box's *width* alone would leave it soft. Taking the larger of the two
 * gives Crop enough pixels in either direction for the portrait images covers actually
 * are. A wide image in a very tall box could still come up short, which costs sharpness
 * on something unusual rather than correctness on everything.
 *
 * Rounded up to a step so that dragging a window edge does not re-decode every cover on
 * screen for each pixel of movement. The step costs a little memory and saves a great
 * deal of work: without it every resize frame is a fresh decode of everything visible.
 *
 * An unbounded box means the layout has not decided yet, and guessing there would cache
 * an image at a size nothing is going to draw.
 */
private fun decodeWidthFor(maxWidth: Int, maxHeight: Int): Int {
	if (maxWidth <= 0 || maxWidth == Constraints.Infinity) return 0
	val needed = if (maxHeight <= 0 || maxHeight == Constraints.Infinity) maxWidth else maxOf(maxWidth, maxHeight)
	return ((needed + DECODE_WIDTH_STEP - 1) / DECODE_WIDTH_STEP) * DECODE_WIDTH_STEP
}

/** Fine enough to matter, coarse enough that a resize is not a decode storm. */
private const val DECODE_WIDTH_STEP = 128

/** A back arrow plus a title, used at the top of every screen below the catalogue. */
@Composable
fun TopBar(
	title: String,
	subtitle: String? = null,
	onBack: (() -> Unit)? = null,
	trailing: @Composable () -> Unit = {},
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (onBack != null) {
			// A glyph rather than Icons.AutoMirrored.Filled.ArrowBack, which would mean
			// adding the material-icons artifact for a single arrow.
			IconButton(onClick = onBack) {
				Text(text = "\u2190", style = MaterialTheme.typography.titleLarge)
			}
		}
		Box(Modifier.weight(1f)) {
			androidx.compose.foundation.layout.Column {
				Text(
					text = title,
					style = MaterialTheme.typography.titleLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (subtitle != null) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
		trailing()
	}
}

/** Centred spinner for a screen that has nothing to show yet. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		CircularProgressIndicator(modifier = Modifier.size(36.dp))
	}
}

/**
 * A failure the user can act on.
 *
 * Sources break constantly, so this is a normal state rather than an exceptional one and
 * it always offers a retry.
 */
@Composable
fun ErrorBox(
	message: String,
	onRetry: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
	/**
	 * Anything else worth offering here.
	 *
	 * Retry is the right answer to a timeout and the wrong one to a 404: a title the
	 * source has dropped will not come back however many times it is asked, and a screen
	 * whose only button cannot work is a dead end.
	 */
	extra: @Composable () -> Unit = {},
) {
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		androidx.compose.foundation.layout.Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier.padding(24.dp),
		) {
			Text(
				text = message,
				style = MaterialTheme.typography.bodyMedium,
				textAlign = TextAlign.Center,
				color = MaterialTheme.colorScheme.error,
			)
			if (onRetry != null) {
				androidx.compose.material3.Button(onClick = onRetry) { Text("Retry") }
			}
			extra()
		}
	}
}

/** Thin divider-height spacer used between list rows. */
@Composable
fun RowGap() = Box(Modifier.height(1.dp))

/**
 * Runs [action] when Enter is pressed while this element has focus.
 *
 * Search submits on Enter rather than on every keystroke: each keystroke would be a live
 * request to a third-party site, which is both slow and rude.
 */
fun Modifier.onEnter(action: () -> Unit): Modifier = this.onPreviewKeyEvent { event ->
	if (event.type == KeyEventType.KeyDown &&
		(event.key == Key.Enter || event.key == Key.NumPadEnter)
	) {
		action()
		true
	} else {
		false
	}
}
