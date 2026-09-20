package org.koitharu.kotatsu.desktop.feature.sync

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.image.ImageCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Small pieces shared by the backup and updates screens.
 *
 * Kept inside this feature rather than reaching into `desktop/ui/Common.kt`. Five areas are
 * being built at once against the `FeatureContext` contract, and that file is not part of
 * it; depending on its exact signatures would make this area break when another area edits
 * a shared screen helper.
 */

/**
 * Opens a Swing file chooser and returns what the user picked, or null if they cancelled.
 *
 * Run on [Dispatchers.IO], off the Compose thread, deliberately. Compose for Desktop renders
 * from the AWT event thread, and `showOpenDialog` blocks its caller until the dialog closes;
 * calling it from a composable's coroutine on the main dispatcher freezes the window behind
 * the dialog for as long as it is open, including its own repaint.
 */
internal suspend fun chooseBackupFile(save: Boolean, initial: File?): File? = withContext(Dispatchers.IO) {
	val chooser = JFileChooser().apply {
		dialogTitle = if (save) "Save a backup" else "Choose a backup to restore"
		fileSelectionMode = JFileChooser.FILES_ONLY
		isAcceptAllFileFilterUsed = true
		fileFilter = FileNameExtensionFilter("Backup archive (*.zip)", "zip")
		if (initial != null) {
			initial.parentFile?.takeIf { it.isDirectory }?.let { currentDirectory = it }
			selectedFile = initial
		}
	}
	val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
	if (result != JFileChooser.APPROVE_OPTION) {
		return@withContext null
	}
	val picked = chooser.selectedFile ?: return@withContext null
	// A save dialog lets the user type a bare name; the restore side matches on the zip
	// contents rather than the extension, but a file called "library" is unhelpful later.
	if (save && !picked.name.endsWith(".zip", ignoreCase = true)) {
		File(picked.parentFile, picked.name + ".zip")
	} else {
		picked
	}
}

private val timestampFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

internal fun formatTimestamp(value: Long?): String =
	if (value == null || value <= 0L) "never" else timestampFormat.format(Date(value))

/** A cover thumbnail, fetched through the originating source's client (D19). */
@Composable
internal fun CoverThumb(
	url: String?,
	client: OkHttpClient,
	cache: ImageCache,
	title: String,
	modifier: Modifier = Modifier,
) {
	var bitmap: ImageBitmap? by remember(url) { mutableStateOf(null) }
	LaunchedEffect(url) {
		bitmap = url?.let { cache.load(it, client) }
	}
	Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
		val bmp = bitmap
		if (bmp != null) {
			Image(
				bitmap = bmp,
				contentDescription = title,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}

/** The new-chapter count, as a filled pill next to a title. */
@Composable
internal fun CountBadge(count: Int, modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.primary)
			.padding(horizontal = 8.dp, vertical = 2.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = if (count > 99) "99+" else count.toString(),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onPrimary,
		)
	}
}

/** A title plus optional subtitle and trailing controls, at the top of a feature screen. */
@Composable
internal fun FeatureHeader(
	title: String,
	subtitle: String? = null,
	trailing: @Composable () -> Unit = {},
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.weight(1f)) {
			Text(text = title, style = MaterialTheme.typography.titleLarge)
			if (subtitle != null) {
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		trailing()
	}
}

/** Centred text for a screen with nothing in it yet. */
@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Text(
			text = text,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
			modifier = Modifier.padding(32.dp),
		)
	}
}
