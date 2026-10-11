package org.koitharu.kotatsu.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.util.ext.DebugFlags
import org.koitharu.kotatsu.desktop.image.ImageCache

/**
 * A remote image, fetched through [client] so the source's own headers apply.
 *
 * Shows nothing while loading and a muted placeholder if the image cannot be decoded,
 * rather than an error, because a missing cover is common and not worth shouting about.
 */
@Composable
fun RemoteImage(
	url: String?,
	client: OkHttpClient,
	cache: ImageCache,
	contentDescription: String?,
	modifier: Modifier = Modifier,
	contentScale: ContentScale = ContentScale.Crop,
	// The reader passes Transparent: a surfaceVariant letterbox around a manga page looks
	// like a rendering fault rather than a background.
	background: Color = Color.Unspecified,
) {
	val fill = if (background.isSpecified) background else MaterialTheme.colorScheme.surfaceVariant
	// Measures itself rather than making every caller pass a size. A cover is drawn at a
	// few hundred pixels in a grid and much larger on a details screen, and the right
	// decode size is whatever this particular box turned out to be.
	BoxWithConstraints(modifier = modifier.background(fill)) {
		val targetWidth = decodeWidthFor(constraints.maxWidth, constraints.maxHeight)
		var bitmap: ImageBitmap? by remember(url, targetWidth) { mutableStateOf(null) }
		var settled by remember(url, targetWidth) { mutableStateOf(false) }
		var reason: String? by remember(url, targetWidth) { mutableStateOf(null) }
		LaunchedEffect(url, targetWidth) {
			bitmap = url?.let { cache.load(it, client, targetWidth) }
			reason = if (bitmap == null) url?.let { cache.failureReason(it) } else null
			settled = true
		}
		val bmp = bitmap
		if (bmp != null) {
			Image(
				bitmap = bmp,
				contentDescription = contentDescription,
				contentScale = contentScale,
				modifier = Modifier.fillMaxSize(),
			)
		} else if (settled) {
			Text(
				// "no cover" was the same square whether the source had none, answered
				// 403, or served something Skia cannot read. Only the first is normal.
				text = reason ?: "no cover",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.align(Alignment.Center).padding(Space.xs),
			)
		}
	}
}

/**
 * What width to decode for a box this size, in pixels, or 0 for "decode whole".
 *
 * The height matters because the default [ContentScale.Crop] fills both axes: a portrait
 * cover in a cell taller than the cover's own ratio is scaled up until it covers, so
 * decoding at the box's *width* alone would leave it soft. Taking the larger of the two
 * gives Crop enough pixels in either direction for the portrait images covers actually
 * are. A wide image in a very tall box could still come up short, which costs sharpness
 * on something unusual rather than correctness on everything.
 *
 * Rounded up to a step so that dragging a window edge does not re-decode every cover on
 * screen for each pixel of movement. The step costs a little memory and saves a great
 * deal of work: without it every resize frame is a fresh decode of everything visible.
 *
 * An unbounded box means the layout has not decided yet, and guessing there would cache
 * an image at a size nothing is going to draw.
 */
private fun decodeWidthFor(maxWidth: Int, maxHeight: Int): Int {
	if (maxWidth <= 0 || maxWidth == Constraints.Infinity) return 0
	val needed = if (maxHeight <= 0 || maxHeight == Constraints.Infinity) maxWidth else maxOf(maxWidth, maxHeight)
	return ((needed + DECODE_WIDTH_STEP - 1) / DECODE_WIDTH_STEP) * DECODE_WIDTH_STEP
}

/** Fine enough to matter, coarse enough that a resize is not a decode storm. */
private const val DECODE_WIDTH_STEP = 128

/**
 * A vendored Material Symbols glyph, drawn at 24 dp in the current content colour.
 *
 * [name] is a value from [AppIcons]. [filled] asks for the FILL 1 variant, which only
 * the navigation rail uses and which only exists for some names; see
 * [AppIcons.withFilledVariant].
 *
 * [contentDescription] is deliberately **not** defaulted. A null default is how an
 * icon-only button ends up unlabelled, and this app has enough of those to make the
 * friction worth it. Decorative use passes an explicit null, which is then a visible
 * decision at the call site rather than an omission.
 *
 * Size is fixed at 24 dp and not exposed. The glyphs it replaces were sized by whichever
 * typography style they happened to be drawn at, which is why the rail's icons and the
 * back arrow did not match.
 *
 * If the file is missing this draws an outlined box of the same size and keeps the
 * description, rather than throwing. A missing SVG is a packaging bug and `AppIconsTest`
 * fails the build for it; crashing the reader at runtime because one file was dropped
 * from the jar is strictly worse than a visible box.
 */
@Composable
fun AppIcon(
	name: String,
	contentDescription: String?,
	modifier: Modifier = Modifier,
	filled: Boolean = false,
	tint: Color = LocalContentColor.current,
) {
	val path = AppIcons.filePath(name, filled)
	if (iconResourceExists(path)) {
		Icon(
			painter = painterResource(path),
			contentDescription = contentDescription,
			tint = tint,
			modifier = modifier.size(ICON_SIZE),
		)
	} else {
		Box(
			modifier = modifier
				.size(ICON_SIZE)
				.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(4.dp)),
		)
	}
}

private val ICON_SIZE = 24.dp

/**
 * Whether an icon file is on the classpath.
 *
 * Checked rather than caught: `painterResource` throws, and a composable call cannot sit
 * inside a try block. Resolved once per path and remembered, because this runs on every
 * recomposition of every icon on screen.
 */
private val iconPresence = HashMap<String, Boolean>()

private fun iconResourceExists(path: String): Boolean = iconPresence.getOrPut(path) {
	val found = AppIcons::class.java.classLoader.getResource(path) != null
	if (!found && DebugFlags.isDebug) {
		System.err.println("DropSauce: icon resource missing from the classpath: $path")
	}
	found
}

/**
 * A back arrow plus a title, used at the top of every screen below the catalogue.
 *
 * [backLabel] names the back control, which is icon-only and so has nothing else to
 * announce. It is a parameter rather than a constant because "Back" is not always right:
 * from a search result it is "Back to results".
 */
@Composable
fun TopBar(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	onBack: (() -> Unit)? = null,
	backLabel: String = "Back",
	trailing: @Composable RowScope.() -> Unit = {},
) {
	Row(
		modifier = modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
	) {
		if (onBack != null) {
			IconButton(onClick = onBack) {
				AppIcon(AppIcons.BACK, contentDescription = backLabel)
			}
		}
		Column(Modifier.weight(1f)) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (subtitle != null) {
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		trailing()
	}
}

/**
 * A heading inside a screen, above a group of things.
 *
 * One style, so that "a section header" stops being three. [count] sits beside the text
 * rather than inside it, because "Chapters 105" and "105 chapters" were both in use.
 */
@Composable
fun SectionHeader(
	text: String,
	modifier: Modifier = Modifier,
	count: Int? = null,
	trailing: @Composable (RowScope.() -> Unit)? = null,
) {
	Row(
		modifier = modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.titleSmall,
			// Weighted and ellipsised, because a header's text is often a title someone
			// else chose. Unweighted, a long one measures at its full width and squeezes
			// the count and any trailing control down to nothing.
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f, fill = false),
		)
		if (count != null) {
			Text(
				text = count.toString(),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		if (trailing != null) {
			Box(Modifier.weight(1f))
			trailing()
		}
	}
}

/**
 * One row in a list: a thumbnail or control, a headline, and some metadata.
 *
 * Replaces six separate row implementations whose thumbnails were 36x50, 44x62, 48x68,
 * 52x74 and 56x76 dp with corner radii of 4, 4, 6, 6 and 6. The thumbnail is a slot, so
 * this knows nothing about images or HTTP and the rows that carry a checkbox or an icon
 * instead can use the same component.
 *
 * [meta] is a list rather than a joined string because the middle dot was spelled two
 * different ways across nineteen files, and because joining here is what stops a row
 * with one item rendering a trailing separator.
 *
 * The minimum height is what lets dividers stop carrying structure. A 658-row list
 * separated only by hairlines at 1.99:1 is a list whose structure fails WCAG 1.4.11; one
 * separated by rhythm and by a hover state does not need the hairline to do that work.
 * It is a minimum rather than a fixed height so a row whose text wraps at UI scale 2.0
 * grows instead of clipping.
 */
@Composable
fun AppListRow(
	headline: String,
	modifier: Modifier = Modifier,
	onClick: (() -> Unit)? = null,
	supporting: String? = null,
	meta: List<String> = emptyList(),
	problem: String? = null,
	leading: @Composable (() -> Unit)? = null,
	trailing: @Composable (RowScope.() -> Unit)? = null,
	progress: Float? = null,
	selected: Boolean = false,
	/**
	 * Whether this row is something the reader can act on.
	 *
	 * Defaults to "it has somewhere to go", which is right for a list of things to open:
	 * a history entry whose source has left the catalogue reads as unavailable, which it
	 * is. It is a separate parameter because a row with no `onClick` is not always
	 * disabled — an informational list has nothing to click and every word in it is
	 * meant to be read, and 38% content would be the one thing that must not happen to
	 * the text explaining why a source failed.
	 */
	enabled: Boolean = onClick != null,
) {
	val interactions = remember { MutableInteractionSource() }
	val hovered by interactions.collectIsHoveredAsState()
	val focused by interactions.collectIsFocusedAsState()
	val pressed by interactions.collectIsPressedAsState()
	val scheme = MaterialTheme.colorScheme
	val fill = when {
		selected -> scheme.secondaryContainer
		pressed -> scheme.onSurface.copy(alpha = PRESSED_ALPHA)
		hovered -> scheme.onSurface.copy(alpha = HOVER_ALPHA)
		else -> Color.Transparent
	}
	// A row that cannot be acted on has to look it, or a row you cannot open looks
	// exactly like one you can. 38% is Material's disabled alpha; such a row is also
	// left out of the tab order, which the absent `clickable` below already achieves.
	val content = when {
		!enabled -> scheme.onSurface.copy(alpha = DISABLED_ALPHA)
		selected -> scheme.onSecondaryContainer
		else -> scheme.onSurface
	}
	val supportingColor = if (enabled) scheme.onSurfaceVariant else content
	Row(
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = LIST_ROW_MIN_HEIGHT)
			.then(if (onClick != null) Modifier.clickable(interactionSource = interactions, indication = null, onClick = onClick) else Modifier)
			.hoverable(interactions)
			.focusRing(focused)
			.background(fill)
			.padding(horizontal = Space.lg, vertical = Space.md),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.lg),
	) {
		if (leading != null) {
			leading()
		}
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.hair)) {
			Text(
				text = headline,
				style = MaterialTheme.typography.bodyLarge,
				color = content,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (supporting != null) {
				Text(
					text = supporting,
					style = MaterialTheme.typography.bodySmall,
					color = supportingColor,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			if (meta.isNotEmpty()) {
				Text(
					text = meta.joinToString(META_SEPARATOR),
					style = MaterialTheme.typography.labelSmall,
					color = supportingColor,
					maxLines = 1,
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
			if (progress != null) {
				LinearProgressIndicator(
					progress = { progress },
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
		if (trailing != null) {
			trailing()
		}
	}
}

/** Material's standard hover overlay alpha. */
private const val HOVER_ALPHA = 0.08f

/** Material's standard pressed overlay alpha. */
private const val PRESSED_ALPHA = 0.12f

/** Material's disabled-content alpha. An inactive control is exempt from 1.4.3. */
private const val DISABLED_ALPHA = 0.38f

/** Rhythm, not hairlines, is what separates rows. See [AppListRow]. */
private val LIST_ROW_MIN_HEIGHT = 64.dp

/**
 * One spelling of the separator, in one place.
 *
 * Not private: the middle dot was spelled two different ways across nineteen files, and
 * the Details facts line and the reader's chapter line both join their own metadata.
 */
const val META_SEPARATOR = "  ·  "

/**
 * A 2 dp focus ring in [androidx.compose.material3.ColorScheme.primary].
 *
 * Keyboard focus had no visible treatment anywhere in the app beyond whatever Material
 * draws inside its own controls, so a row, a card or a chip reached by Tab looked
 * identical to one that was not. 10.79:1 against the dark page and 6.17:1 against the
 * light one, so it clears the 3:1 for a non-text indicator with room to spare.
 *
 * [shape] has to be passed for anything that is not a rectangle. A chip is a pill, and a
 * rectangular ring around a pill reads as a rendering fault rather than as focus.
 */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape = RectangleShape): Modifier =
	if (focused) this.border(2.dp, MaterialTheme.colorScheme.primary, shape) else this

/**
 * Nothing here, said once.
 *
 * Replaces seven near-identical private helpers and nine inline one-offs. It takes the
 * best shape of the seven: a headline saying what is empty, a detail saying what to do
 * about it, and an action, which none of them had.
 *
 * It also fixes something five screens got wrong: their empty text sat in a
 * `Box(Modifier.fillMaxSize())` with no alignment, so it rendered against the top-left
 * corner of an otherwise blank window.
 */
@Composable
fun EmptyBox(
	headline: String,
	modifier: Modifier = Modifier,
	detail: String? = null,
	icon: String? = AppIcons.EMPTY,
	action: @Composable (() -> Unit)? = null,
) {
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Space.sm),
			modifier = Modifier.padding(Space.xxl),
		) {
			if (icon != null) {
				AppIcon(
					name = icon,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Text(
				text = headline,
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				textAlign = TextAlign.Center,
			)
			if (detail != null) {
				Text(
					text = detail,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
					modifier = Modifier.widthIn(max = ReadableWidth),
				)
			}
			if (action != null) {
				action()
			}
		}
	}
}

/**
 * A failure the user can act on.
 *
 * Sources break constantly, so this is a normal state rather than an exceptional one and
 * it always offers a retry.
 *
 * The message is no longer drawn in the error colour. An icon and a headline carry "this
 * is a problem"; a paragraph of explanation in red is a wall of red that leaves colour
 * doing all the signalling, which is also the WCAG 1.4.1 failure.
 *
 * [compact] is the inline form, for when content is already on screen and must not be
 * thrown away to show a failure. Five screens hand-rolled that case.
 */
@Composable
fun ErrorBox(
	message: String,
	onRetry: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
	headline: String = "That did not work",
	compact: Boolean = false,
	/**
	 * Anything else worth offering here.
	 *
	 * Retry is the right answer to a timeout and the wrong one to a 404: a title the
	 * source has dropped will not come back however many times it is asked, and a screen
	 * whose only button cannot work is a dead end.
	 */
	extra: @Composable () -> Unit = {},
) {
	val scheme = MaterialTheme.colorScheme
	if (compact) {
		Row(
			modifier = modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.sm),
		) {
			AppIcon(AppIcons.ERROR, contentDescription = null, tint = scheme.error)
			Text(
				text = message,
				style = MaterialTheme.typography.bodySmall,
				color = scheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (onRetry != null) {
				TextButton(onClick = onRetry) { Text("Try again") }
			}
			extra()
		}
		return
	}
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Space.sm),
			modifier = Modifier.padding(Space.xl),
		) {
			AppIcon(AppIcons.ERROR, contentDescription = null, tint = scheme.error)
			Text(
				text = headline,
				style = MaterialTheme.typography.titleMedium,
				color = scheme.onSurface,
				textAlign = TextAlign.Center,
			)
			Text(
				text = message,
				style = MaterialTheme.typography.bodyMedium,
				color = scheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
				modifier = Modifier.widthIn(max = ReadableWidth),
			)
			if (onRetry != null) {
				Button(onClick = onRetry) {
					AppIcon(AppIcons.REFRESH, contentDescription = null)
					Box(Modifier.size(Space.sm))
					Text("Try again")
				}
			}
			extra()
		}
	}
}

/**
 * An inline notice that is not a failure: a count that needs loading, a download that
 * has started, a hint that something is off.
 *
 * Gives those a single shape with a dismiss affordance, which several of them lacked
 * entirely.
 */
@Composable
fun NoticeBar(
	text: String,
	modifier: Modifier = Modifier,
	icon: String? = null,
	tone: NoticeTone = NoticeTone.Info,
	onDismiss: (() -> Unit)? = null,
	action: @Composable (RowScope.() -> Unit)? = null,
) {
	val scheme = MaterialTheme.colorScheme
	val fill = when (tone) {
		NoticeTone.Info -> scheme.surfaceContainer
		NoticeTone.Problem -> scheme.errorContainer
	}
	val content = when (tone) {
		NoticeTone.Info -> scheme.onSurfaceVariant
		NoticeTone.Problem -> scheme.onErrorContainer
	}
	Row(
		modifier = modifier
			.fillMaxWidth()
			.background(fill)
			.padding(horizontal = Space.lg, vertical = Space.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
	) {
		if (icon != null) {
			AppIcon(icon, contentDescription = null, tint = content)
		}
		Text(
			text = text,
			style = MaterialTheme.typography.bodySmall,
			color = content,
			modifier = Modifier.weight(1f),
		)
		if (action != null) {
			action()
		}
		if (onDismiss != null) {
			IconButton(onClick = onDismiss) {
				// "Dismiss" alone says nothing about what is being dismissed, and this is
				// an icon-only control with no label beside it.
				AppIcon(AppIcons.CLOSE, contentDescription = "Dismiss this message", tint = content)
			}
		}
	}
}

/**
 * Whether a notice is telling you something or telling you something is wrong.
 *
 * [Problem] exists because the alternative was a warning icon on the ordinary fill, which
 * leaves the icon as the only thing distinguishing a problem from a hint. The container
 * pair is 7.28:1 dark and 13.31:1 light, both asserted in `ThemeContrastTest`.
 */
enum class NoticeTone { Info, Problem }

/**
 * Centred spinner for a screen that has nothing to show yet.
 *
 * [label] is for the cases that otherwise say nothing at all for several seconds while a
 * third-party site decides whether to answer.
 */
@Composable
fun LoadingBox(
	modifier: Modifier = Modifier,
	label: String? = null,
) {
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Space.md),
		) {
			CircularProgressIndicator(modifier = Modifier.size(36.dp))
			if (label != null) {
				Text(
					text = label,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

/**
 * The inline form of loading: a bar, a label, and optionally a way to stop.
 *
 * Replaces four bare indeterminate indicators, four determinate ones, three
 * `CircularProgressIndicator`s at 14, 18 and 20 dp, and two verbatim copies of a
 * hand-drawn two-box progress bar.
 *
 * Those two copies exist because of a real problem, recorded in a comment at each site:
 * `LinearProgressIndicator`'s overload set in this material3 build is ambiguous at the
 * call site once every optional parameter is omitted. Passing `progress` explicitly as a
 * lambda is what resolves it, which three other call sites in the app already do.
 */
@Composable
fun LoadingStrip(
	label: String,
	modifier: Modifier = Modifier,
	progress: Float? = null,
	onCancel: (() -> Unit)? = null,
) {
	Row(
		modifier = modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.sm),
	) {
		if (progress != null) {
			LinearProgressIndicator(
				progress = { progress },
				modifier = Modifier.weight(1f),
			)
		} else {
			LinearProgressIndicator(modifier = Modifier.weight(1f))
		}
		Text(
			text = label,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (onCancel != null) {
			TextButton(onClick = onCancel) { Text("Stop") }
		}
	}
}

/** Thin divider-height spacer used between list rows. */
@Composable
fun RowGap() = Box(Modifier.height(1.dp))

/**
 * Runs [action] when Enter is pressed while this element has focus.
 *
 * Search submits on Enter rather than on every keystroke: each keystroke would be a live
 * request to a third-party site, which is both slow and rude.
 */
fun Modifier.onEnter(action: () -> Unit): Modifier = this.onPreviewKeyEvent { event ->
	if (event.type == KeyEventType.KeyDown &&
		(event.key == Key.Enter || event.key == Key.NumPadEnter)
	) {
		action()
		true
	} else {
		false
	}
}
