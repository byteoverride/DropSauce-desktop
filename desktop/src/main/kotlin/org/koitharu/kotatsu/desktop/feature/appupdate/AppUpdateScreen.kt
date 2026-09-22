package org.koitharu.kotatsu.desktop.feature.appupdate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.ui.TopBar
import java.awt.Desktop
import java.net.URI

/**
 * What version is running, and whether a newer one exists.
 *
 * Deliberately does not download or install. A `.deb` is installed with root, and an app
 * that fetched and ran an installer on its own would be doing something nobody asked for.
 * This hands over the release page and the direct link and stops there.
 */
@Composable
fun AppUpdateScreen(repository: AppUpdateRepository) {
	val scope = rememberCoroutineScope()
	val update by repository.observeAvailableUpdate().collectAsState()
	val checked by repository.observeChecked().collectAsState()
	var checking by remember { mutableStateOf(false) }

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "App update",
			subtitle = "Running ${AppBuild.version}",
			trailing = {
				Button(
					enabled = !checking,
					onClick = {
						checking = true
						scope.launch {
							repository.fetchUpdate()
							checking = false
						}
					},
				) { Text(if (checking) "Checking" else "Check now") }
			},
		)
		Column(
			modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			val release = update
			when {
				release != null -> UpdateCard(release)

				// "Nothing newer" and "nobody has looked" read the same on screen and are
				// not the same fact, so the second one says so.
				checking || !checked -> Text(
					"Checking for a newer version.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)

				else -> Text(
					"${AppBuild.version} is the newest release. " +
						"Checked once when the app started; Check now asks again.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Text(
				text = "Updates are looked up from this project's GitHub releases. Nothing is " +
					"downloaded or installed for you: a package needs root, so the install " +
					"stays yours to run.",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun UpdateCard(release: AppRelease) {
	Card(Modifier.fillMaxWidth()) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text(release.title, style = MaterialTheme.typography.titleLarge)
			Text(
				text = "Version ${release.version}, ${release.downloadSize / 1_000_000} MB, " +
					"replacing ${AppBuild.version}",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (release.notes.isNotBlank()) {
				Text(
					text = release.notes,
					style = MaterialTheme.typography.bodyMedium,
					maxLines = 40,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(onClick = { browse(release.downloadUrl) }) { Text("Download the package") }
				OutlinedButton(onClick = { browse(release.url) }) { Text("Release notes") }
			}
			Text(
				text = "Install it with: sudo dpkg -i ${release.downloadUrl.substringAfterLast('/')}",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * Hands a link to the desktop environment.
 *
 * Silent when there is no browse support, which is the case on a bare session. The link
 * is on screen either way, so a failure here costs the reader a click, not the update.
 */
private fun browse(url: String) {
	runCatching {
		if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			Desktop.getDesktop().browse(URI(url))
		}
	}
}
