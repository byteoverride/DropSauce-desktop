package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.image.ImageCache

/**
 * A short pill drawn on a cover's top end corner.
 *
 * [CoverBadgeTone.New] is `tertiaryContainer`, which is the warm sand the scheme defines
 * for exactly this: "something changed here" that is neither an accent nor an error.
 */
data class CoverBadge(val text: String, val tone: CoverBadgeTone = CoverBadgeTone.Neutral)

enum class CoverBadgeTone { New, Neutral }

/**
 * One title in a grid or a strip.
 *
 * This existed inside `feature/discover/` and four other areas had their own copy, all
 * of them an `Adaptive(150.dp)` grid of `aspectRatio(0.7f)` covers with a rounded corner
 * that was variously 4, 6, 10 or 12 dp. A shared component cannot live inside one
 * feature's directory, so it moves here and widens enough to cover all five uses.
 *
 * It takes [title] and [coverUrl] rather than a `Manga`, because two of the five callers
 * do not have one and reimplemented the card for that reason alone.
 *
 * The title is `bodyMedium`, raised from the `bodySmall` every copy used. It is the most
 * important text on the card, and setting it at the app's smallest body size is why a
 * library grid reads as a wall of pictures with captions.
 */
@Composable
fun CoverCard(
	title: String,
	coverUrl: String?,
	client: OkHttpClient,
	images: ImageCache,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	/** One line under the title: a chapter count, a file size, why this was suggested. */
	secondary: String? = null,
	secondaryMaxLines: Int = 1,
	badge: CoverBadge? = null,
	/** Something wrong with this item. Drawn under the text with a warning icon. */
	problem: String? = null,
	/** Over the cover's top start corner, for a selection checkbox. */
	overlay: @Composable (BoxScope.() -> Unit)? = null,
	/** Under the text, end aligned, for a per-card action. */
	action: @Composable (() -> Unit)? = null,
	selected: Boolean = false,
) {
	val interactions = remember { MutableInteractionSource() }
	val hovered by interactions.collectIsHoveredAsState()
	val focused by interactions.collectIsFocusedAsState()
	val pressed by interactions.collectIsPressedAsState()
	val scheme = MaterialTheme.colorScheme
	val overlayFill = when {
		pressed -> scheme.onSurface.copy(alpha = 0.12f)
		hovered -> scheme.onSurface.copy(alpha = 0.08f)
		else -> Color.Transparent
	}
	Column(
		modifier = modifier
			.fillMaxWidth()
			.clip(MaterialTheme.shapes.medium)
			.clickable(interactionSource = interactions, indication = null, onClick = onClick)
			.hoverable(interactions)
			.focusRing(focused, MaterialTheme.shapes.medium)
			.then(if (selected) Modifier.border(2.dp, scheme.primary, MaterialTheme.shapes.medium) else Modifier)
			.background(overlayFill),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Box {
			RemoteImage(
				url = coverUrl,
				client = client,
				cache = images,
				contentDescription = title,
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(COVER_ASPECT)
					.clip(MaterialTheme.shapes.medium),
			)
			if (overlay != null) {
				overlay()
			}
			if (badge != null) {
				val background = when (badge.tone) {
					CoverBadgeTone.New -> scheme.tertiaryContainer
					CoverBadgeTone.Neutral -> scheme.surfaceContainerHighest
				}
				val foreground = when (badge.tone) {
					CoverBadgeTone.New -> scheme.onTertiaryContainer
					CoverBadgeTone.Neutral -> scheme.onSurface
				}
				Text(
					text = badge.text,
					style = MaterialTheme.typography.labelSmall,
					color = foreground,
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(Space.sm)
						.background(background, CircleShape)
						.padding(horizontal = Space.sm, vertical = Space.xs),
				)
			}
		}
		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		if (secondary != null) {
			Text(
				text = secondary,
				style = MaterialTheme.typography.bodySmall,
				color = scheme.onSurfaceVariant,
				maxLines = secondaryMaxLines,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (problem != null) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.xs),
			) {
				AppIcon(AppIcons.WARNING, contentDescription = null, tint = scheme.error)
				Text(
					text = problem,
					style = MaterialTheme.typography.labelSmall,
					color = scheme.error,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		if (action != null) {
			Box(Modifier.align(Alignment.End)) { action() }
		}
	}
}
