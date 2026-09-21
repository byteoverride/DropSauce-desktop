package org.koitharu.kotatsu.desktop.feature.scrobbling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.desktop.feature.FeatureContext

/**
 * Connect and disconnect the external tracking services.
 *
 * The screen's job is to make the three states distinguishable, because they need
 * different things from the user. A service with no client id is not broken and not
 * signed out, it is unconfigured, and the card says exactly which environment variable
 * or file would fix that. Nothing here pretends a service is usable when it is not.
 */
@Composable
fun TrackingServicesScreen(context: FeatureContext) {
	val repository = remember(context) { createTrackingRepository(context) }
	TrackingServicesContent(repository)
}

/**
 * The screen body, against the repository alone.
 *
 * Split from [TrackingServicesScreen] so the composable never has to build a repository
 * to be previewed or re-used, and so the wiring above it stays one line.
 */
@Composable
fun TrackingServicesContent(repository: TrackingRepository) {
	val scope = rememberCoroutineScope()
	val states by repository.states.collectAsState()
	var busy: TrackingService? by remember { mutableStateOf(null) }
	var lastError: String? by remember { mutableStateOf(null) }
	var lastMessage: String? by remember { mutableStateOf(null) }

	Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
		TrackingHeader(
			title = "Tracking",
			subtitle = "Push your reading progress to an external service.",
		)
		HorizontalDivider()

		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			Text(
				text = "Signing in opens your browser. DropSauce listens on a local port for " +
					"the reply, and nothing leaves this machine except the sign-in itself.",
				style = MaterialTheme.typography.bodyMedium,
			)

			for (service in TrackingService.entries) {
				val state = states[service] ?: ServiceState.Disconnected
				ServiceCard(
					service = service,
					state = state,
					isBusy = busy == service,
					// One sign-in at a time: each holds a loopback port and a browser
					// window, and two at once is a user interface for confusing yourself.
					isBlocked = busy != null && busy != service,
					onConnect = {
						scope.launch {
							busy = service
							lastError = null
							lastMessage = null
							try {
								val account = repository.connect(service)
								lastMessage = "Connected to ${service.label} as ${account.nickname}."
							} catch (e: CancellationException) {
								throw e
							} catch (e: Exception) {
								lastError = describe(e)
							} finally {
								busy = null
							}
						}
					},
					onDisconnect = {
						repository.disconnect(service)
						lastMessage = "Signed out of ${service.label}. Your links are kept."
						lastError = null
					},
					onForget = {
						scope.launch {
							busy = service
							try {
								repository.forget(service)
								lastMessage = "Signed out of ${service.label} and removed its links."
								lastError = null
							} catch (e: CancellationException) {
								throw e
							} catch (e: Exception) {
								lastError = describe(e)
							} finally {
								busy = null
							}
						}
					},
				)
			}

			lastMessage?.let { TrackingNote(it) }
			lastError?.let { TrackingNote(it, isError = true) }

			Text(
				text = "Shikimori and Kitsu are not implemented. Kitsu signs in with a username " +
					"and password rather than a browser redirect, so it shares none of this, and " +
					"Shikimori needs its own set of API calls.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun ServiceCard(
	service: TrackingService,
	state: ServiceState,
	isBusy: Boolean,
	isBlocked: Boolean,
	onConnect: () -> Unit,
	onDisconnect: () -> Unit,
	onForget: () -> Unit,
) {
	Card(modifier = Modifier.fillMaxWidth()) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = service.label,
					style = MaterialTheme.typography.titleMedium,
					modifier = Modifier.weight(1f),
				)
				if (isBusy) {
					CircularProgressIndicator(modifier = Modifier.size(20.dp))
				}
			}

			TrackingNote(
				text = describeState(state),
				isError = state is ServiceState.Failing,
			)

			when (state) {
				is ServiceState.Unavailable -> {
					// No button at all. A disabled Connect would read as "temporarily
					// unavailable"; the honest statement is that there is nothing to
					// press until a client id exists.
					Text(
						text = "Set the variable above, then reopen this screen.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				ServiceState.Disconnected -> Button(
					onClick = onConnect,
					enabled = !isBusy && !isBlocked,
				) {
					Text("Connect")
				}

				is ServiceState.Connected, is ServiceState.Failing -> Row(
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					OutlinedButton(onClick = onDisconnect, enabled = !isBusy) {
						Text("Disconnect")
					}
					TextButton(onClick = onForget, enabled = !isBusy) {
						Text("Disconnect and remove links")
					}
					if (state is ServiceState.Failing) {
						Button(onClick = onConnect, enabled = !isBusy && !isBlocked) {
							Text("Sign in again")
						}
					}
				}
			}
		}
	}
}
