package org.koitharu.kotatsu.desktop.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.koitharu.kotatsu.desktop.feature.FeatureContext

/**
 * Back up the library to a zip, and read one back.
 *
 * The two destructive things a user can do here are both explicit: restoring needs a file
 * chosen from a dialog, and the replace mode that empties tables first is never the
 * default and says what it does before it is picked.
 */
@Composable
fun BackupScreen(context: FeatureContext) {
	val scope = rememberCoroutineScope()
	val repository = remember(context) { rememberBackupRepository(context) }

	var mode by remember { mutableStateOf(RestoreMode.Merge) }
	var busy by remember { mutableStateOf(false) }
	var progress: BackupProgress? by remember { mutableStateOf(null) }
	var result: String? by remember { mutableStateOf(null) }
	var warnings: List<String> by remember { mutableStateOf(emptyList()) }
	var error: String? by remember { mutableStateOf(null) }
	var lastBackup: Long? by remember { mutableStateOf(null) }

	LaunchedEffect(repository) {
		lastBackup = repository.lastBackupAt()
	}

	Column(
		modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
	) {
		FeatureHeader(
			title = "Backup",
			subtitle = "Last backup: ${formatTimestamp(lastBackup)}",
		)
		HorizontalDivider()

		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			Text(
				text = "A backup holds your categories, favourites, reading history, bookmarks " +
					"and settings. It is the same zip format the Android app reads and writes, " +
					"so the two can exchange libraries.",
				style = MaterialTheme.typography.bodyMedium,
			)

			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Button(
					enabled = !busy,
					onClick = {
						scope.launch {
							busy = true
							result = null
							error = null
							warnings = emptyList()
							try {
								val suggested = repository.suggestedFile().toFile()
								val chosen = chooseBackupFile(save = true, initial = suggested)
								if (chosen != null) {
									val summary = repository.createBackup(chosen.toOkioPath()) { progress = it }
									lastBackup = summary.createdAt
									result = "Saved ${summary.categories} categories, " +
										"${summary.favourites} favourites, ${summary.history} history " +
										"entries and ${summary.bookmarks} bookmarks to ${chosen.name}."
								}
							} catch (e: CancellationException) {
								throw e
							} catch (e: Throwable) {
								error = e.message ?: e::class.simpleName ?: "Backup failed"
							} finally {
								progress = null
								busy = false
							}
						}
					},
				) {
					Text("Back up now")
				}

				OutlinedButton(
					enabled = !busy,
					onClick = {
						scope.launch {
							busy = true
							result = null
							error = null
							warnings = emptyList()
							try {
								val chosen = chooseBackupFile(save = false, initial = null)
								if (chosen != null) {
									val summary = repository.restore(chosen.toOkioPath(), mode) { progress = it }
									result = summary.describe()
									warnings = summary.warnings
								}
							} catch (e: CancellationException) {
								throw e
							} catch (e: Throwable) {
								// A BackupFormatException carries a message written for a
								// person; anything else falls back to its class name.
								error = e.message ?: e::class.simpleName ?: "Restore failed"
							} finally {
								progress = null
								busy = false
							}
						}
					},
				) {
					Text("Restore from file")
				}
			}

			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text("When restoring", style = MaterialTheme.typography.titleSmall)
				RestoreModeOption(
					selected = mode == RestoreMode.Merge,
					enabled = !busy,
					title = "Merge (recommended)",
					description = "Adds and updates. Nothing already on this machine is deleted, " +
						"and a reading position that is newer here is kept.",
					onSelect = { mode = RestoreMode.Merge },
				)
				RestoreModeOption(
					selected = mode == RestoreMode.Replace,
					enabled = !busy,
					title = "Replace",
					description = "Empties categories, favourites, history and bookmarks first, " +
						"so the library ends up exactly as the file describes it. " +
						"Downloads, local files and reading statistics are left alone.",
					onSelect = { mode = RestoreMode.Replace },
				)
			}

			val current = progress
			if (current != null) {
				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(current.step, style = MaterialTheme.typography.bodySmall)
					LinearProgressIndicator(
						progress = {
							if (current.total <= 0) 0f else current.done.toFloat() / current.total
						},
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}

			val message = result
			if (message != null) {
				Card(Modifier.fillMaxWidth()) {
					Text(
						text = message,
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.padding(12.dp),
					)
				}
			}

			if (warnings.isNotEmpty()) {
				Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text("Notes", style = MaterialTheme.typography.titleSmall)
					for (warning in warnings) {
						Text(
							text = "· $warning",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}

			val failure = error
			if (failure != null) {
				Card(Modifier.fillMaxWidth()) {
					Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Text(
							text = "That did not work",
							style = MaterialTheme.typography.titleSmall,
							color = MaterialTheme.colorScheme.error,
						)
						Text(text = failure, style = MaterialTheme.typography.bodyMedium)
						Text(
							text = "Nothing was changed.",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun RestoreModeOption(
	selected: Boolean,
	enabled: Boolean,
	title: String,
	description: String,
	onSelect: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.selectable(selected = selected, enabled = enabled, onClick = onSelect)
			.padding(vertical = 6.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Top,
	) {
		RadioButton(selected = selected, enabled = enabled, onClick = onSelect)
		Column {
			Text(text = title, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Start)
			Text(
				text = description,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

private fun RestoreSummary.describe(): String = buildString {
	append("Restored in ")
	append(if (mode == RestoreMode.Replace) "replace" else "merge")
	append(" mode: ")
	append("$manga titles, ")
	append("$categoriesCreated new categor${if (categoriesCreated == 1) "y" else "ies"}")
	if (categoriesMatched > 0) {
		append(" ($categoriesMatched matched an existing one)")
	}
	append(", $favourites favourites, $historyApplied history entries")
	if (historySkipped > 0) {
		append(" ($historySkipped already newer here)")
	}
	append(", $bookmarks bookmarks")
	if (settingsApplied) {
		append(", and settings")
	}
	append(".")
}

/** Built once per [FeatureContext]; the database and settings behind it live for the process. */
private fun rememberBackupRepository(context: FeatureContext) = BackupRepository(
	db = context.db,
	settings = context.settings,
	backupDirectory = context.paths.data / "backups",
)
