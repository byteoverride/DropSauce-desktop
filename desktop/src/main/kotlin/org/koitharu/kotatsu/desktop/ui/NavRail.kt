package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One slot in the navigation rail.
 *
 * [key] is whatever the shell navigates by, compared with `==`, so this file needs to
 * know nothing about the screen graph.
 */
data class NavDestination(
	val key: Any,
	val label: String,
	/** A name from [AppIcons]. The filled variant is used when selected, where one exists. */
	val icon: String,
	val group: NavGroup,
	val badge: Int = 0,
	/** A dot instead of a number, for a badge that sums things that are not comparable. */
	val badgeAsDot: Boolean = false,
)

/**
 * Why a destination is on the rail.
 *
 * Eleven undifferentiated slots is a list to read rather than a place to aim. Grouping
 * moves nothing across the line D26 draws — content is still on the rail and tools are
 * still in Settings — it just gives the eye somewhere to land.
 */
enum class NavGroup(val label: String) {

	/** Things you already have. */
	Read("Your library"),

	/** Things you do not have yet. */
	Find("Find more"),

	/** Things that changed without you. */
	KeepUp("Keeping up"),

	/** Pinned to the bottom, outside the scrolling region. */
	System("App"),
}

/**
 * The navigation rail.
 *
 * Two structural fixes over the rail this replaces.
 *
 * **It scrolls, so it cannot clip.** The old rail laid eleven items out in a column with
 * no scroll container. At the shipped window height the last two were simply unreachable
 * — visible in a screen capture as "Bookmarks" cut in half at the bottom edge — and at
 * UI scale 2.0 five were. The items now sit in a weighted scrolling column, so the rail
 * gives up the space it has rather than the items it cannot fit.
 *
 * **Settings sits outside that scrolling region**, pinned to the bottom. It is the only
 * destination that is also the way to the seven tools, and a destination you have to
 * scroll to find is the problem being fixed.
 *
 * Group boundaries are drawn as a divider and a gap, with no visible headings: four
 * labels would cost more height than the grouping saves. The headings exist for screen
 * readers, as an empty node carrying the group name and a heading role, so the structure
 * is announced even though it is not drawn.
 */
@Composable
fun AppNavRail(
	destinations: List<NavDestination>,
	selectedKey: Any?,
	onSelect: (NavDestination) -> Unit,
	modifier: Modifier = Modifier,
) {
	val scrolling = destinations.filter { it.group != NavGroup.System }
	val pinned = destinations.filter { it.group == NavGroup.System }
	NavigationRail(
		modifier = modifier,
		containerColor = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Spacer(Modifier.height(Space.md))
		Column(
			modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
			horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Space.xs),
		) {
			var previous: NavGroup? = null
			for (destination in scrolling) {
				if (previous != null && destination.group != previous) {
					GroupRule()
				}
				if (destination.group != previous) {
					// Announced, not drawn. See the class comment.
					Spacer(Modifier.semantics { heading(); contentDescription = destination.group.label })
				}
				previous = destination.group
				NavRailItem(
					destination = destination,
					selected = destination.key == selectedKey,
					onClick = { onSelect(destination) },
				)
			}
		}
		if (pinned.isNotEmpty()) {
			GroupRule()
			for (destination in pinned) {
				NavRailItem(
					destination = destination,
					selected = destination.key == selectedKey,
					onClick = { onSelect(destination) },
				)
			}
			Spacer(Modifier.height(Space.md))
		}
	}
}

/**
 * One rail item.
 *
 * The selected state is carried by the indicator pill and its label weight, not by the
 * icon swapping to its filled variant: Material publishes a filled variant for all of
 * these, but for five of them it is the identical drawing, so the swap is reinforcement
 * where it exists and nothing where it does not. See [AppIcons.withFilledVariant].
 */
@Composable
private fun NavRailItem(
	destination: NavDestination,
	selected: Boolean,
	onClick: () -> Unit,
) {
	NavigationRailItem(
		selected = selected,
		onClick = onClick,
		icon = {
			BadgedBox(
				badge = {
					when {
						destination.badge <= 0 -> Unit
						// A plain dot where the number would be summing things that have
						// nothing to do with each other.
						destination.badgeAsDot -> Badge()
						else -> Badge {
							// Capped in the text, not the value, so a big number cannot
							// widen the rail.
							Text(if (destination.badge > BADGE_CAP) "$BADGE_CAP+" else destination.badge.toString())
						}
					}
				},
			) {
				AppIcon(
					name = destination.icon,
					// The label below says the same thing, and a screen reader
					// announcing it twice is worse than once.
					contentDescription = null,
					filled = selected,
				)
			}
		},
		label = {
			Text(
				text = destination.label,
				style = if (selected) {
					MaterialTheme.typography.labelLargeEmphasized
				} else {
					MaterialTheme.typography.labelMedium
				},
			)
		},
		colors = NavigationRailItemDefaults.colors(
			selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
			selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
			indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
			unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
			unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
		),
	)
}

/**
 * The rule between two groups.
 *
 * Explicitly narrow, and that is load bearing. `HorizontalDivider` fills its parent's
 * width by default, and the rail is wrap-content: a full-width divider inside it resolves
 * against the window's width instead, so the rail swallowed the entire window and the
 * content area was laid out at zero width. A fixed short rule keeps the rail sized by its
 * widest label, which is what lets it grow with the UI-scale setting.
 */
@Composable
private fun GroupRule() {
	HorizontalDivider(
		modifier = Modifier.width(GROUP_RULE_WIDTH).padding(vertical = Space.sm),
		color = MaterialTheme.colorScheme.outlineVariant,
	)
}

private val GROUP_RULE_WIDTH = 40.dp

private const val BADGE_CAP = 99
