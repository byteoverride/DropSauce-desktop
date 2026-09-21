package org.koitharu.kotatsu.desktop.feature.readerx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Sliders for [ReaderColorParams], with a live preview above them.
 *
 * A preview rather than only numbers because none of these values mean anything as a
 * number. "Contrast 0.35" is not a thing anyone can picture, and the reader would
 * otherwise have to leave the settings screen and open a chapter after every nudge.
 */
@Composable
fun ColorFilterEditor(
	params: ReaderColorParams,
	onChange: (ReaderColorParams) -> Unit,
	modifier: Modifier = Modifier,
	sample: ImageBitmap? = null,
	onReset: (() -> Unit)? = null,
) {
	Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text("Colour filter", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
			if (onReset != null) {
				TextButton(onClick = onReset, enabled = !params.isIdentity) { Text("Reset") }
			}
		}
		ColorFilterPreview(
			params = params,
			sample = sample,
			modifier = Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp),
		)
		FilterSlider(
			label = "Brightness",
			value = params.brightness,
			range = -1f..1f,
			onChange = { onChange(params.copy(brightness = it)) },
		)
		FilterSlider(
			label = "Contrast",
			value = params.contrast,
			range = -1f..1f,
			onChange = { onChange(params.copy(contrast = it)) },
		)
		FilterSlider(
			label = "Grayscale",
			value = params.grayscale,
			range = 0f..1f,
			onChange = { onChange(params.copy(grayscale = it)) },
		)
		FilterSlider(
			label = "Sepia",
			value = params.sepia,
			range = 0f..1f,
			onChange = { onChange(params.copy(sepia = it)) },
		)
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text("Invert", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
			Switch(checked = params.invert, onCheckedChange = { onChange(params.copy(invert = it)) })
		}
	}
}

/**
 * The filter applied to something, so the reader can see what the sliders do.
 *
 * Falls back to a drawn gradient when no page is supplied. The gradient is not decorative:
 * it runs black to white through a band of saturated colour, which is exactly the range
 * these five controls act on, so every slider visibly moves something.
 */
@Composable
fun ColorFilterPreview(
	params: ReaderColorParams,
	modifier: Modifier = Modifier,
	sample: ImageBitmap? = null,
) {
	val filter = colorFilterOf(params)
	Box(modifier = modifier, contentAlignment = Alignment.Center) {
		if (sample != null) {
			Image(
				bitmap = sample,
				contentDescription = null,
				contentScale = ContentScale.Fit,
				colorFilter = filter,
				modifier = Modifier.fillMaxWidth(),
			)
		} else {
			Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
				drawPreviewGradient(filter)
			}
		}
	}
}

private fun DrawScope.drawPreviewGradient(filter: androidx.compose.ui.graphics.ColorFilter?) {
	val brush = Brush.linearGradient(
		colors = listOf(
			Color.Black,
			Color(0xFF3355CC),
			Color(0xFFCC3322),
			Color(0xFFDDBB55),
			Color.White,
		),
		start = Offset.Zero,
		end = Offset(size.width, 0f),
	)
	drawRect(brush = brush, colorFilter = filter)
}

@Composable
private fun FilterSlider(
	label: String,
	value: Float,
	range: ClosedFloatingPointRange<Float>,
	onChange: (Float) -> Unit,
) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.width(96.dp),
		)
		Slider(
			value = value.coerceIn(range),
			onValueChange = onChange,
			valueRange = range,
			modifier = Modifier.weight(1f),
		)
		Text(
			// Two decimals would imply a precision the eye cannot resolve here.
			text = "${(value * 100).roundToInt()}",
			style = MaterialTheme.typography.labelSmall,
			modifier = Modifier.width(40.dp),
		)
	}
}
