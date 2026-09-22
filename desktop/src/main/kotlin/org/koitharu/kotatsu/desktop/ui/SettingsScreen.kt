package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.shared.settings.SettingsData
import org.koitharu.kotatsu.shared.settings.ThemeMode

/**
 * Settings.
 *
 * Only things the app actually reads appear here. The Android `AppSettings` is 205 keys
 * and grew that way partly by exposing options nothing consumed; DECISIONS.md D8 says
 * desktop implements the subset it uses, so a control appearing here is a commitment
 * that something honours it.
 */
@Composable
fun SettingsScreen(state: AppState) {
	val scope = rememberCoroutineScope()
	val settings by state.settings.data.collectAsState()

	fun edit(transform: (SettingsData) -> SettingsData) {
		scope.launch { state.settings.update(transform) }
	}

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Settings",
			trailing = {
				OutlinedButton(onClick = { scope.launch { state.settings.reset() } }) {
					Text("Reset all")
				}
			},
		)
		Column(
			modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
			verticalArrangement = Arrangement.spacedBy(20.dp),
		) {
			Section("Appearance") {
				ScaleRow(
					current = settings.uiScale,
					onSelect = { value -> edit { it.copy(uiScale = value) } },
				)
				ChoiceRow(
					label = "Theme",
					options = ThemeMode.entries,
					selected = settings.theme,
					name = { it.name },
					onSelect = { choice -> edit { it.copy(theme = choice) } },
				)
			}

			Section("Reader") {
				NumberRow(
					label = "Retries per page image",
					help = "How many times to re-request a page before giving up on it.",
					value = settings.pageAttempts,
					range = 1..10,
					onChange = { n -> edit { it.copy(pageAttempts = n) } },
				)
			}

			Section("Sources") {
				SwitchRow(
					label = "Hide adult sources",
					help = "A large share of the ${state.usableSourceCount} available " +
						"sources are adult. Hiding them does not delete anything already " +
						"in your library.",
					checked = settings.hideAdultSources,
					onChange = { on -> edit { it.copy(hideAdultSources = on) } },
				)
				TextRow(
					label = "User agent",
					help = "Sent with every request. Some sources serve a different page " +
						"layout to unfamiliar clients, which parsers then fail to read.",
					value = settings.userAgent,
					onChange = { text ->
						edit { it.copy(userAgent = text.ifBlank { SettingsData.DEFAULT_USER_AGENT }) }
					},
				)
			}

			Section("Storage") {
				NumberRow(
					label = "Images held in memory",
					help = "Covers and pages. Higher is smoother and uses more memory.",
					value = settings.imageCacheEntries,
					range = 50..2000,
					onChange = { n -> edit { it.copy(imageCacheEntries = n) } },
				)
				DirectoryRow(
					label = "Downloads folder",
					help = "Where downloaded chapters are written. Chapters already " +
						"downloaded stay where they are and keep working.",
					value = settings.downloadDir,
					fallback = (state.paths.data / "downloads").toString(),
					onChange = { chosen -> edit { it.copy(downloadDir = chosen) } },
				)
				PathRow("Library database", state.paths.data.toString())
				PathRow("Settings file", state.paths.config.toString())
				PathRow("Cache", state.paths.cache.toString())
			}

			// The tools that used to each own a slot in the navigation rail. They are
			// configuration and occasional maintenance, opened rarely, and eighteen
			// destinations made the ones people use every day harder to find.
			val tools = state.features.filter { !it.isTopLevel }
			if (tools.isNotEmpty()) {
				Section("Tools") {
					for (tool in tools) {
						ToolRow(
							feature = tool,
							context = state.featureContext,
							onOpen = { state.go(Screen.FeatureRoot(tool.id)) },
						)
					}
				}
			}

			Section("About") {
				PathRow("Sources available", state.usableSourceCount.toString())
				Text(
					text = "Source catalogue provided by kotatsu-parsers. " +
						"Sources the library reports as broken are not listed.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

/**
 * One tool, with its own dot when it has something to say.
 *
 * Pushed rather than selected as a root, so the shell keeps a way back to this screen and
 * the rail goes on showing Settings as where you are.
 */
@Composable
private fun ToolRow(
	feature: org.koitharu.kotatsu.desktop.feature.Feature,
	context: org.koitharu.kotatsu.desktop.feature.FeatureContext,
	onOpen: () -> Unit,
) {
	val badged by remember(feature) { feature.badge(context) }.collectAsState(false)
	Row(
		modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(feature.glyph, style = MaterialTheme.typography.titleMedium)
		Text(feature.settingsTitle, style = MaterialTheme.typography.bodyLarge)
		if (badged) {
			Badge()
		}
		Spacer(Modifier.weight(1f))
		Text("\u203A", style = MaterialTheme.typography.titleMedium)
	}
}


/**
 * A folder, chosen with the desktop's own picker.
 *
 * AWT's [java.awt.FileDialog] rather than Swing's JFileChooser: on Linux the AWT dialog
 * is the one the desktop environment themes, and a Swing dialog in a Compose window
 * looks like it belongs to a different application. It needs the directory property set,
 * which is the documented way to make it pick folders instead of files.
 */
@Composable
private fun DirectoryRow(
	label: String,
	help: String,
	value: String?,
	fallback: String,
	onChange: (String?) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(label, style = MaterialTheme.typography.bodyLarge)
		Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(
			text = value ?: "$fallback  (default)",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(onClick = { chooseDirectory(label)?.let(onChange) }) { Text("Choose") }
			if (value != null) {
				OutlinedButton(onClick = { onChange(null) }) { Text("Use default") }
			}
		}
	}
}

/** Returns the chosen folder, or null when the picker was dismissed. */
private fun chooseDirectory(title: String): String? {
	val previous = System.getProperty("apple.awt.fileDialogForDirectories")
	return try {
		// Documented switch that turns FileDialog into a directory picker. Set around the
		// call and put back, because it is a global property and leaving it on would make
		// every later file dialog in the process pick folders.
		System.setProperty("apple.awt.fileDialogForDirectories", "true")
		val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
		dialog.isMultipleMode = false
		dialog.isVisible = true
		val directory = dialog.directory ?: return null
        val file = dialog.file
		val chosen = if (file == null) java.io.File(directory) else java.io.File(directory, file)
		// A picker that would not give up a folder gives up the file's parent instead,
		// which is the folder the reader was looking at and what they meant.
		val asDirectory = if (chosen.isDirectory) chosen else chosen.parentFile
		asDirectory?.absolutePath
	} catch (e: Throwable) {
		// A headless or unusual session has no picker. The setting is still editable by
		// hand in the settings file, so this is a missing convenience, not a failure.
		null
	} finally {
		if (previous == null) {
			System.clearProperty("apple.awt.fileDialogForDirectories")
		} else {
			System.setProperty("apple.awt.fileDialogForDirectories", previous)
		}
	}
}

/**
 * Interface scale.
 *
 * Its own control rather than a [ChoiceRow] because the options are numbers with a
 * meaning, and because this is the one setting a user reaches for when they cannot
 * comfortably read the rest of the settings screen.
 */
@Composable
private fun ScaleRow(current: Float, onSelect: (Float) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text("Interface size", style = MaterialTheme.typography.bodyLarge)
		Text(
			"Scales text, icons and spacing together. Applies immediately.",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			for (value in UI_SCALES) {
				FilterChip(
					selected = kotlin.math.abs(value - current) < 0.01f,
					onClick = { onSelect(value) },
					label = { Text("${(value * 100).toInt()}%") },
				)
			}
		}
	}
}

private val UI_SCALES = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(title, style = MaterialTheme.typography.titleMedium)
		HorizontalDivider()
		content()
	}
}

@Composable
private fun <T> ChoiceRow(
	label: String,
	options: List<T>,
	selected: T,
	name: (T) -> String,
	onSelect: (T) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(label, style = MaterialTheme.typography.bodyLarge)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			for (option in options) {
				FilterChip(
					selected = option == selected,
					onClick = { onSelect(option) },
					label = { Text(name(option)) },
				)
			}
		}
	}
}

@Composable
private fun SwitchRow(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(16.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.bodyLarge)
			Text(
				help,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Switch(checked = checked, onCheckedChange = onChange)
	}
}

@Composable
private fun NumberRow(
	label: String,
	help: String,
	value: Int,
	range: IntRange,
	onChange: (Int) -> Unit,
) {
	var text by remember(value) { mutableStateOf(value.toString()) }
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(label, style = MaterialTheme.typography.bodyLarge)
		Text(
			help,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			OutlinedTextField(
				value = text,
				onValueChange = { text = it.filter(Char::isDigit).take(5) },
				singleLine = true,
				modifier = Modifier.widthIn(max = 140.dp),
			)
			Button(
				// Applied on demand rather than per keystroke: a partially typed number
				// like "5" on the way to "500" would otherwise be saved and clamped.
				onClick = { text.toIntOrNull()?.let { onChange(it.coerceIn(range)) } },
				enabled = text.toIntOrNull()?.let { it != value } == true,
			) { Text("Apply") }
			Text(
				"${range.first} to ${range.last}",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun TextRow(label: String, help: String, value: String, onChange: (String) -> Unit) {
	var text by remember(value) { mutableStateOf(value) }
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(label, style = MaterialTheme.typography.bodyLarge)
		Text(
			help,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			OutlinedTextField(
				value = text,
				onValueChange = { text = it },
				singleLine = true,
				modifier = Modifier.weight(1f).onEnter { onChange(text) },
			)
			Button(onClick = { onChange(text) }, enabled = text != value) { Text("Apply") }
		}
	}
}

@Composable
private fun PathRow(label: String, value: String) {
	Column {
		Text(label, style = MaterialTheme.typography.bodyMedium)
		Text(
			value,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
