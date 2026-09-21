package org.koitharu.kotatsu.desktop.feature.readerx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The nine tap regions, laid out as they appear on screen, each one a menu.
 *
 * Drawn to scale rather than listed as nine rows, because the thing being configured is
 * spatial. A list of "Centre left: previous page" makes the reader translate positions in
 * their head; a grid does not.
 */
@Composable
fun TapZoneEditor(
	grid: TapZoneGrid,
	onChange: (TapZoneGrid) -> Unit,
	modifier: Modifier = Modifier,
	isRtl: Boolean = false,
	onReset: (() -> Unit)? = null,
) {
	Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text("Tap zones", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
			if (onReset != null) {
				TextButton(onClick = onReset, enabled = grid != TapZoneGrid.DEFAULT) { Text("Reset") }
			}
		}
		Text(
			text = if (isRtl) {
				"Showing what each region does in a right-to-left title, where next and previous swap."
			} else {
				"Showing what each region does in a left-to-right title."
			},
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Column(
			modifier = Modifier
				.fillMaxWidth()
				// Roughly a window's shape, so the cells sit where they would in the reader.
				.aspectRatio(16f / 10f),
		) {
			for (row in 0 until GRID) {
				Row(Modifier.fillMaxWidth().weight(1f)) {
					for (column in 0 until GRID) {
						val zone = TapZone.at(row, column)
						ZoneCell(
							zone = zone,
							action = grid.actionFor(zone).let { if (isRtl) it.mirrored() else it },
							onPick = { onChange(grid.with(zone, if (isRtl) it.mirrored() else it)) },
							modifier = Modifier.weight(1f).fillMaxSize(),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun ZoneCell(
	zone: TapZone,
	action: TapAction,
	onPick: (TapAction) -> Unit,
	modifier: Modifier = Modifier,
) {
	var open by remember { mutableStateOf(false) }
	Box(
		modifier = modifier
			.padding(2.dp)
			.background(action.tint())
			.border(1.dp, MaterialTheme.colorScheme.outlineVariant)
			.clickable { open = true },
		contentAlignment = Alignment.Center,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			Text(
				text = action.label,
				style = MaterialTheme.typography.labelMedium,
				textAlign = TextAlign.Center,
			)
			Text(
				text = zone.label,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
		}
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			for (option in TapAction.entries) {
				DropdownMenuItem(
					text = { Text(option.label) },
					onClick = {
						open = false
						onPick(option)
					},
				)
			}
		}
	}
}

/**
 * A wash of colour per action, so the whole grid reads at a glance.
 *
 * Low alpha rather than solid colours: these sit behind text in both light and dark
 * themes, and a saturated fill would fail contrast in one of them.
 */
@Composable
private fun TapAction.tint(): Color = when (this) {
	TapAction.NONE -> Color.Transparent
	TapAction.NEXT_PAGE -> Color(0xFF4CAF50).copy(alpha = ZONE_TINT_ALPHA)
	TapAction.PREVIOUS_PAGE -> Color(0xFFFF7043).copy(alpha = ZONE_TINT_ALPHA)
	TapAction.TOGGLE_UI -> Color(0xFF3D69C5).copy(alpha = ZONE_TINT_ALPHA)
}

private const val ZONE_TINT_ALPHA = 0.22f
