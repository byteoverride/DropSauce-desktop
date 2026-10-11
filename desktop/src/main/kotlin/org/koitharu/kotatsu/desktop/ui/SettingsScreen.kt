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
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
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
		// Vertical padding only. Every row below carries the screen gutter itself,
		// because two of them hand their chips to ChipRow, which has Space.lg built
		// in; padding here as well would set the chips 16 dp inside their own label.
		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(vertical = Space.lg),
			verticalArrangement = Arrangement.spacedBy(Space.xl),
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
					modifier = Modifier.padding(horizontal = Space.lg),
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
	val badged by remember(feature) { feature.badge(context) }.collectAsState(0)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onOpen)
			.padding(horizontal = Space.lg, vertical = Space.md),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.md),
	) {
		AppIcon(feature.icon, contentDescription = null)
		Text(feature.settingsTitle, style = MaterialTheme.typography.bodyLarge)
		if (badged > 0) {
			Badge { Text(if (badged > 99) "99+" else badged.toString()) }
		}
		Spacer(Modifier.weight(1f))
		Text("\u203A", style = MaterialTheme.typography.titleMedium)
	}
}


/** A folder setting: what it is now, and a way to change it. */
@Composable
private fun DirectoryRow(
	label: String,
	help: String,
	value: String?,
	fallback: String,
	onChange: (String?) -> Unit,
) {
	Column(
		modifier = Modifier.padding(horizontal = Space.lg),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Text(label, style = MaterialTheme.typography.bodyLarge)
		Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(
			text = value ?: "$fallback  (default)",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
			Button(onClick = { chooseDirectory(label, value ?: fallback)?.let(onChange) }) { Text("Choose") }
			if (value != null) {
				OutlinedButton(onClick = { onChange(null) }) { Text("Use default") }
			}
		}
	}
}

/**
 * A folder, chosen with a real directory picker.
 *
 * `JFileChooser` in `DIRECTORIES_ONLY` mode, not `java.awt.FileDialog`. The AWT dialog
 * only selects directories on macOS, through the `apple.awt.fileDialogForDirectories`
 * property, which is exactly what this used to set: on Windows it cannot pick a folder at
 * all, and on Linux the property means nothing. Swing's chooser is the only thing in the
 * JDK that does this on every platform.
 *
 * Returns the chosen folder, or null when the dialog was dismissed or cannot be shown.
 */
private fun chooseDirectory(title: String, startIn: String?): String? = try {
	// So it does not look like a 1998 Java applet next to the rest of the window. Failing
	// to set it is not a reason to refuse the dialog, only to draw it plainly.
	runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
	val chooser = JFileChooser(startIn?.let(::File)?.takeIf { it.isDirectory })
	chooser.dialogTitle = title
	chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
	chooser.isMultiSelectionEnabled = false
	// Nothing to create a file with, so the filter would only be a confusing empty menu.
	chooser.isAcceptAllFileFilterUsed = false
	if (chooser.showDialog(null, "Use this folder") == JFileChooser.APPROVE_OPTION) {
		chooser.selectedFile?.absolutePath
	} else {
		null
	}
} catch (e: java.awt.HeadlessException) {
	// No display. The setting is still editable by hand in the settings file, so this is
	// a missing convenience rather than a failure.
	null
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
	Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
		Text(
			text = "Interface size",
			style = MaterialTheme.typography.bodyLarge,
			modifier = Modifier.padding(horizontal = Space.lg),
		)
		Text(
			text = "Scales text, icons and spacing together. Applies immediately.",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = Space.lg),
		)
		// A plain Row before, with no overflow handling of any kind. Six chips reading
		// "100%" to "250%" want about 400 dp, and every one of those dp is itself scaled
		// by this very setting, so at 250% the row wants roughly 1000 dp. The control
		// that gets you back out of an unreadable scale was therefore the first control
		// to leave the window, with no scroll and no wrap to reach it by: the only way
		// back to 100% was editing the settings file. ChipRow wraps and collapses.
		//
		// Three rows rather than the usual two, because this is the control someone
		// reaches for to undo a scale they cannot read. The larger the scale, the more
		// rows these six chips need, so collapsing them late is what keeps the way back
		// visible without a second click at exactly the scale that made it necessary.
		ChipRow(collapsedRows = 3) {
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

/**
 * A group of settings under one heading.
 *
 * The heading and the rule carry the gutter; the content does not, so a row can choose
 * between padding itself and handing a [ChipRow] the whole width. A padded wrapper here
 * would put every chip row 16 dp further in than the label above it.
 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.padding(horizontal = Space.lg),
		)
		HorizontalDivider()
		content()
	}
}

/**
 * One setting, chosen from a fixed set of named options.
 *
 * The option list is a type parameter, so this file cannot know how many chips a caller
 * will produce or how long their names will be. That is why the row wraps rather than
 * being sized for today's two enum entries.
 */
@Composable
private fun <T> ChoiceRow(
	label: String,
	options: List<T>,
	selected: T,
	name: (T) -> String,
	onSelect: (T) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodyLarge,
			modifier = Modifier.padding(horizontal = Space.lg),
		)
		ChipRow(collapsedRows = 2) {
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
		modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
		horizontalArrangement = Arrangement.spacedBy(Space.lg),
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
	Column(
		modifier = Modifier.padding(horizontal = Space.lg),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Text(label, style = MaterialTheme.typography.bodyLarge)
		Text(
			help,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(
			horizontalArrangement = Arrangement.spacedBy(Space.sm),
			verticalAlignment = Alignment.CenterVertically,
		) {
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
	Column(
		modifier = Modifier.padding(horizontal = Space.lg),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Text(label, style = MaterialTheme.typography.bodyLarge)
		Text(
			help,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(
			horizontalArrangement = Arrangement.spacedBy(Space.sm),
			verticalAlignment = Alignment.CenterVertically,
		) {
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
	Column(Modifier.padding(horizontal = Space.lg)) {
		Text(label, style = MaterialTheme.typography.bodyMedium)
		Text(
			value,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
