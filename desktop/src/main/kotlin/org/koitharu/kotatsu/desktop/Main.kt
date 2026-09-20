package org.koitharu.kotatsu.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import okio.FileSystem
import org.koitharu.kotatsu.core.util.ext.DebugFlags
import org.koitharu.kotatsu.shared.io.AppPaths
import org.koitharu.kotatsu.shared.io.XdgAppPaths

fun main() {
	// Counterpart of BaseApp setting this from BuildConfig.DEBUG on Android.
	DebugFlags.isDebug = System.getProperty("dropsauce.debug").toBoolean()
	launchUi()
}

private fun launchUi() = application {
	val paths = remember { XdgAppPaths() }
	Window(
		onCloseRequest = ::exitApplication,
		title = "DropSauce",
	) {
		// MaterialExpressiveTheme and MotionScheme.expressive() are the same Material 3
		// Expressive entry points :app uses in settings/compose/SettingsTheme.kt. They are
		// here deliberately: they only resolve because of the material3 pin in
		// gradle/libs.versions.toml, so this window failing to compile is the signal that
		// DECISIONS.md D6a has regressed.
		MaterialExpressiveTheme(motionScheme = MotionScheme.expressive()) {
			Surface(modifier = Modifier.fillMaxSize()) {
				StartupReport(paths)
			}
		}
	}
}

@Composable
private fun StartupReport(paths: AppPaths) {
	val rows = remember(paths) {
		val fs = FileSystem.SYSTEM
		listOf(
			"data" to paths.data,
			"cache" to paths.cache,
			"config" to paths.config,
			"localLibrary" to paths.localLibrary,
		).map { (label, path) -> Triple(label, path.toString(), fs.exists(path)) }
	}
	Column(
		modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text("DropSauce desktop", style = MaterialTheme.typography.headlineMedium)
		Text(
			"Resolved host directories (XDG Base Directory Specification).",
			style = MaterialTheme.typography.bodyMedium,
		)
		for ((label, path, exists) in rows) {
			Text(
				text = "$label: $path" + if (exists) "" else "  (not created yet)",
				style = MaterialTheme.typography.bodySmall,
			)
		}
	}
}
