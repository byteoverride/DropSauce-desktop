package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A row of chips that cannot run off the edge of the window.
 *
 * Fifteen chip containers existed across three incompatible strategies and this replaces
 * all of them:
 *
 * - **`horizontalScroll`**, at five sites, clips with no affordance at all. The Library's
 *   length filter ended mid-word at "100 o" with nothing to say that "100 or more"
 *   existed or that the row could be dragged.
 * - **Nothing at all**, at seven sites, clips *and* offers no way to reach what was
 *   clipped. The category row was a plain `Row`, so a reader with twelve categories
 *   could not select the last three by any means.
 * - **`FlowRow`**, at five sites, is correct, and is what this generalises. The addition
 *   is the collapse, so a source with forty tags does not take the whole screen.
 *
 * The overflow indicator is an `AssistChip` rather than a text button, so it is the same
 * height and shape as its neighbours and lands in the tab order with them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(
	modifier: Modifier = Modifier,
	/** A label before the chips, e.g. "Chapters". */
	label: String? = null,
	/** Rows shown before the rest collapse behind the indicator. */
	collapsedRows: Int = 1,
	/**
	 * Whether to apply the screen gutter.
	 *
	 * False when this sits inside a container that is already padded, which would
	 * otherwise set the chips 32 dp in while the text above them stays at 16. The same
	 * flag exists on the discover filter panel's `Section`, for the same reason.
	 */
	gutter: Boolean = true,
	content: @Composable FlowRowScope.() -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	FlowRow(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = if (gutter) Space.lg else 0.dp, vertical = Space.xs),
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
		verticalArrangement = Arrangement.spacedBy(Space.sm),
		maxLines = if (expanded) Int.MAX_VALUE else collapsedRows,
		overflow = FlowRowOverflow.expandOrCollapseIndicator(
			expandIndicator = {
				AssistChip(
					onClick = { expanded = true },
					label = { Text("+$totalItemCount more") },
				)
			},
			collapseIndicator = {
				AssistChip(
					onClick = { expanded = false },
					label = { Text("Show less") },
				)
			},
		),
	) {
		if (label != null) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
			)
		}
		content()
	}
}
