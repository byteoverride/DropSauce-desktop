package org.koitharu.kotatsu.desktop.feature.appupdate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.ui.AppIcons
import org.koitharu.kotatsu.desktop.ui.LoadingStrip
import org.koitharu.kotatsu.desktop.ui.NoticeBar
import org.koitharu.kotatsu.desktop.ui.NoticeTone
import org.koitharu.kotatsu.desktop.ui.Space
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
		// Vertical padding only: LoadingStrip carries the screen gutter itself, so the
		// bar lines up with the paragraph below it instead of starting 16 dp further in.
		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(vertical = Space.lg),
			verticalArrangement = Arrangement.spacedBy(Space.md),
		) {
			val release = update
			when {
				release != null -> UpdateCard(
					release = release,
					modifier = Modifier.padding(horizontal = Space.lg),
				)

				// "Nothing newer" and "nobody has looked" read the same on screen and are
				// not the same fact, so the second one says so. It is also a wait, not a
				// statement, and a sentence sitting still for several seconds does not
				// say that a request is in flight.
				checking || !checked -> LoadingStrip(label = "Checking for a newer version")

				else -> Text(
					"${AppBuild.version} is the newest release. " +
						"Checked once when the app started; Check now asks again.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = Space.lg),
				)
			}
			Text(
				text = "Updates are looked up from this project's GitHub releases. Nothing is " +
					"downloaded or installed for you: a package needs root, so the install " +
					"stays yours to run.",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = Space.lg),
			)
		}
	}
}

@Composable
private fun UpdateCard(
	release: AppRelease,
	modifier: Modifier = Modifier,
	/** A parameter so the Windows wording can be rendered and asserted from Linux. */
	hostPackage: HostPackage = HostPackage.forHost(),
) {
	// The link that could not be handed over, if one could not. Both buttons write here,
	// because either of them can be the one that finds no browser.
	var unopened: String? by remember(release.url) { mutableStateOf(null) }
	Card(modifier.fillMaxWidth()) {
		// Vertical padding only, so the notice below can supply the gutter itself and
		// still line up with the title above it.
		Column(
			modifier = Modifier.padding(vertical = Space.md),
			verticalArrangement = Arrangement.spacedBy(Space.sm),
		) {
			Text(
				text = release.title,
				style = MaterialTheme.typography.titleLarge,
				modifier = Modifier.padding(horizontal = Space.lg),
			)
			Text(
				text = buildString {
					append("Version ${release.version}")
					// Absent when this release has no package for this platform, which is
					// the one case where there is no size to report.
					release.downloadSize?.let { append(", ${it / 1_000_000} MB") }
					append(", replacing ${AppBuild.version}")
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = Space.lg),
			)
			if (release.notes.isNotBlank()) {
				Text(
					text = release.notes,
					style = MaterialTheme.typography.bodyMedium,
					maxLines = 40,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(horizontal = Space.lg),
				)
			}
			val download = release.downloadUrl
			Row(
				modifier = Modifier.padding(horizontal = Space.lg),
				horizontalArrangement = Arrangement.spacedBy(Space.sm),
			) {
				if (download != null) {
					Button(
						onClick = { unopened = if (browse(download)) null else download },
					) { Text("Download the package") }
				}
				OutlinedButton(
					onClick = { unopened = if (browse(release.url)) null else release.url },
				) { Text("Release notes") }
			}
			unopened?.let { url ->
				// The old comment here claimed a failure cost the reader a click because
				// "the link is on screen either way". It was not: nothing on this screen
				// ever printed a URL, so pressing the button on a session with no browse
				// handler did nothing at all and said nothing. Now it says so, and the
				// address is selectable so the click it costs is a copy and paste.
				SelectionContainer {
					NoticeBar(
						text = "Could not open a browser for this. Copy the address instead: $url",
						icon = AppIcons.ERROR,
						tone = NoticeTone.Problem,
						onDismiss = { unopened = null },
					)
				}
			}
			Text(
				text = if (download != null) {
					hostPackage.installHint(download.substringAfterLast('/'))
				} else {
					// Better than hiding the release. It exists, it is newer, and the page
					// says what happened to the missing build.
					"This release has no ${hostPackage.suffix} yet. The release page has the rest."
				},
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = Space.lg),
			)
		}
	}
}

/**
 * Hands a link to the desktop environment, and says whether that worked.
 *
 * It used to swallow both the unsupported case and the thrown one and return nothing, so
 * a bare session with no browse handler gave the reader a button that did nothing. The
 * caller now has something to show.
 */
private fun browse(url: String): Boolean = runCatching {
	// getDesktop() itself throws on a headless JVM, so the whole lookup is inside the
	// catch rather than only the browse call.
	val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
	if (desktop?.isSupported(Desktop.Action.BROWSE) != true) {
		false
	} else {
		desktop.browse(URI(url))
		true
	}
}.getOrDefault(false)
