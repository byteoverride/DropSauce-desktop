package org.koitharu.kotatsu.desktop.feature.reading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.desktop.feature.FeatureContext
import org.koitharu.kotatsu.desktop.ui.EmptyBox
import org.koitharu.kotatsu.desktop.ui.LoadingBox
import org.koitharu.kotatsu.desktop.ui.META_SEPARATOR
import org.koitharu.kotatsu.desktop.ui.Space
import org.koitharu.kotatsu.desktop.ui.TopBar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * Reading statistics.
 *
 * The bar chart is drawn by hand with [Canvas]. There is no charting library on the
 * desktop classpath and adding one for a single bar chart is not worth a dependency, so
 * the drawing is thirty lines of rectangles.
 */
@Composable
fun StatsScreen(context: FeatureContext) {
	val repository = remember(context) { StatsRepository(context.db) }
	var window by remember { mutableStateOf(StatsWindow.WEEK) }
	val report by remember(repository, window) {
		repository.observeReport(window)
	}.collectAsState(null)

	Column(Modifier.fillMaxSize()) {
		TopBar(
			title = "Statistics",
			subtitle = window.label,
			trailing = {
				// Straight into the bar's own Row: it already spaces its children by
				// Space.sm, so the wrapper this used to carry was a second gap rule.
				for (option in StatsWindow.entries) {
					Button(onClick = { window = option }, enabled = option != window) {
						Text(option.label)
					}
				}
			},
		)
		val snapshot = report
		when {
			// Null is "the query has not answered yet", which is a different thing from
			// an empty table and must not be shown as "nothing read".
			snapshot == null -> SettlingBox()

			snapshot.isEmpty -> EmptyBox(
				headline = "Nothing read in this period.",
				detail = "Reading time is recorded as you read.",
			)

			else -> StatsBody(snapshot)
		}
	}
}

/**
 * Blank first, then the shared loading box.
 *
 * The report is a local query and usually answers within a frame or two, so a spinner
 * shown immediately would appear and vanish as a flicker: that is why this area was a
 * deliberately empty [Box]. An empty window is the wrong answer for the slow case
 * though, where it reads as "nothing read" — the one thing the null state must not say.
 * Blank for [SPINNER_DELAY_MS] and `LoadingBox` after it covers both.
 */
@Composable
private fun SettlingBox() {
	var settled by remember { mutableStateOf(false) }
	LaunchedEffect(Unit) {
		delay(SPINNER_DELAY_MS)
		settled = true
	}
	if (settled) {
		LoadingBox()
	} else {
		Box(Modifier.fillMaxSize())
	}
}

/** Long enough that a query answering normally never draws a spinner at all. */
private const val SPINNER_DELAY_MS = 300L

@Composable
private fun StatsBody(report: StatsReport) {
	Column(
		modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg),
		verticalArrangement = Arrangement.spacedBy(Space.lg),
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(Space.md), modifier = Modifier.fillMaxWidth()) {
			SummaryTile("Time read", formatDuration(report.totalDuration), Modifier.weight(1f))
			SummaryTile("Pages", report.totalPages.toString(), Modifier.weight(1f))
			SummaryTile("Sessions", report.sessions.toString(), Modifier.weight(1f))
			SummaryTile(
				label = "Streak",
				value = if (report.streakDays == 0) "\u2013" else "${report.streakDays}d",
				modifier = Modifier.weight(1f),
			)
		}

		Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
			Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
				Text("Per day", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
				Text(
					text = "peak ${formatDuration(report.busiestDay)}",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			DayChart(report)
			ChartAxis(report.days.map { it.date })
		}

		Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
			Text("Most read", style = MaterialTheme.typography.titleSmall)
			val busiest = report.topTitles.maxOfOrNull { it.duration } ?: 0L
			for (title in report.topTitles) {
				TitleRow(title, busiest)
			}
		}
	}
}

/**
 * One bar per day, oldest on the left.
 *
 * Days with no reading still get a bar, a faint one of minimum height, because a gap in
 * a streak is information and an absent bar reads as a rendering fault.
 */
@Composable
private fun DayChart(report: StatsReport) {
	val barColor = MaterialTheme.colorScheme.primary
	val emptyColor = MaterialTheme.colorScheme.surfaceVariant
	val baselineColor = MaterialTheme.colorScheme.outlineVariant
	val days = report.days
	val peak = report.busiestDay
	Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
		if (days.isEmpty()) return@Canvas
		val baseline = 1.dp.toPx()
		val gap = if (days.size > 14) 2.dp.toPx() else 4.dp.toPx()
		val available = size.width - gap * (days.size - 1)
		val barWidth = max(available / days.size, 1f)
		val plotHeight = size.height - baseline
		val minBar = 2.dp.toPx()
		days.forEachIndexed { index, day ->
			val fraction = if (peak <= 0L) 0f else day.duration.toFloat() / peak.toFloat()
			val barHeight = max(fraction * plotHeight, minBar)
			val x = index * (barWidth + gap)
			drawRect(
				color = if (day.duration > 0L) barColor else emptyColor,
				topLeft = Offset(x, plotHeight - barHeight),
				size = Size(barWidth, barHeight),
			)
		}
		drawRect(
			color = baselineColor,
			topLeft = Offset(0f, plotHeight),
			size = Size(size.width, baseline),
		)
	}
}

/**
 * Start, middle and end date under the chart.
 *
 * Text is placed as composables rather than drawn into the [Canvas]: drawing text needs
 * a text measurer and produces labels that do not follow the theme's typography, for no
 * gain on three labels.
 */
@Composable
private fun ChartAxis(dates: List<LocalDate>) {
	if (dates.isEmpty()) return
	Row(Modifier.fillMaxWidth()) {
		AxisLabel(dates.first().format(AXIS_FORMAT), TextAlign.Start, Modifier.weight(1f))
		AxisLabel(dates[dates.size / 2].format(AXIS_FORMAT), TextAlign.Center, Modifier.weight(1f))
		AxisLabel("Today", TextAlign.End, Modifier.weight(1f))
	}
}

@Composable
private fun AxisLabel(text: String, align: TextAlign, modifier: Modifier) {
	Text(
		text = text,
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		textAlign = align,
		modifier = modifier,
	)
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
	Card(modifier = modifier) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(Space.md),
			verticalArrangement = Arrangement.spacedBy(Space.hair),
		) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(text = value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
		}
	}
}

@Composable
private fun TitleRow(title: TitleTime, busiest: Long) {
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
			Text(
				text = title.title,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = "${formatDuration(title.duration)}$META_SEPARATOR${title.pages}p",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		LinearProgressIndicator(
			progress = {
				if (busiest <= 0L) 0f else (title.duration.toFloat() / busiest.toFloat()).coerceIn(0f, 1f)
			},
			color = MaterialTheme.colorScheme.primary,
			trackColor = Color.Transparent,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

private val CHART_HEIGHT = 140.dp

private val AXIS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
