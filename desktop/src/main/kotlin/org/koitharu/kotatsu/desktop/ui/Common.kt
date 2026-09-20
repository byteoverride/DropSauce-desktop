package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
	var bitmap: ImageBitmap? by remember(url) { mutableStateOf(null) }
	var settled by remember(url) { mutableStateOf(false) }
	LaunchedEffect(url) {
		bitmap = url?.let { cache.load(it, client) }
		settled = true
	}
	val fill = if (background.isSpecified) background else MaterialTheme.colorScheme.surfaceVariant
	Box(modifier = modifier.background(fill)) {
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
				text = "no cover",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
				modifier = Modifier.align(Alignment.Center).padding(4.dp),
			)
		}
	}
}

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
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
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
