package org.koitharu.kotatsu.desktop.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.desktop.ui.Space
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Small pieces shared by the backup and updates screens.
 *
 * What is left here is what is genuinely local to this feature: the file chooser and the
 * timestamp format. The header and the empty note that used to live here were private
 * copies of `TopBar` and `EmptyBox`, kept separate while five areas were being built in
 * parallel against the `FeatureContext` contract. The areas are integrated now, so the
 * copies are gone and both screens draw the shared components from `desktop/ui/`.
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

/** The new-chapter count, as a filled pill next to a title. */
@Composable
internal fun CountBadge(count: Int, modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.primary)
			.padding(horizontal = Space.sm, vertical = Space.hair),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = if (count > 99) "99+" else count.toString(),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onPrimary,
		)
	}
}
