package org.koitharu.kotatsu.desktop.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The app's colour, spacing, shape and size tokens.
 *
 * Both schemes are generated from one seed, `#64D2FF`, which is measured from the app
 * icon rather than chosen: `dropsauce.png` is a cyan monogram on a near-black ground,
 * and the cyan samples as that hex over 9056 pixels. Until now the app shipped bare
 * `darkColorScheme()` / `lightColorScheme()`, so every accent on screen was Material's
 * default purple while the icon in the taskbar was cyan.
 *
 * The ramps were produced in CIELAB at the seed's own hue (L* 79.62, C* 36.54,
 * h 239.35 deg), with chroma clamped into the sRGB gamut per tone. CIELAB is the right
 * space for it because Material 3's HCT tone *is* CIELAB L*: a ramp built at target L*
 * values lands on Material's tone grid on the one axis that governs contrast. Maximum
 * in-gamut chroma at this hue is 41.8 at L*70 but only 17.8 at L*90, so a constant
 * chroma ramp would have clipped at the light end.
 *
 * Two departures from Material's TonalSpot defaults, both deliberate:
 *
 * - **Tertiary is a warm sand, not hue + 60.** Hue + 60 here is 299 deg, a lavender,
 *   which is visually the same purple this scheme exists to get away from; a reader
 *   would reasonably conclude the change had not worked. Hue 80 is unmistakably neither
 *   the primary nor the error red.
 * - **Dark `primary` is the brand hex itself.** The generated tone 80 is `#68D3FF`.
 *   Shipping a colour that is almost the logo is worse than shipping the logo's, so it
 *   is pinned and every contrast figure in `ThemeContrastTest` is computed against the
 *   pinned value.
 *
 * Every pair that carries text or a boundary is asserted in `ThemeContrastTest`, which
 * has a positive control so the assertions are proven to fire.
 */

/** Dark scheme. `surfaceContainerLow` is within 0.74 L* of the icon's own ground. */
internal val DropSauceDarkColors: ColorScheme = darkColorScheme(
	primary = Color(0xFF64D2FF),
	onPrimary = Color(0xFF003546),
	primaryContainer = Color(0xFF004D64),
	onPrimaryContainer = Color(0xFFBEE9FF),
	inversePrimary = Color(0xFF006684),
	secondary = Color(0xFFA6CCE0),
	onSecondary = Color(0xFF093544),
	secondaryContainer = Color(0xFF254B5C),
	onSecondaryContainer = Color(0xFFC2E8FC),
	tertiary = Color(0xFFDEC29B),
	onTertiary = Color(0xFF3F2D0C),
	tertiaryContainer = Color(0xFF584322),
	onTertiaryContainer = Color(0xFFFBDEB6),
	error = Color(0xFFFFB4A1),
	onError = Color(0xFF630F00),
	errorContainer = Color(0xFF8B190A),
	onErrorContainer = Color(0xFFFFDBD1),
	background = Color(0xFF0E1417),
	onBackground = Color(0xFFDDE3E7),
	surface = Color(0xFF0E1417),
	onSurface = Color(0xFFDDE3E7),
	surfaceVariant = Color(0xFF394951),
	onSurfaceVariant = Color(0xFFB7C9D3),
	surfaceTint = Color(0xFF64D2FF),
	inverseSurface = Color(0xFFDDE3E7),
	inverseOnSurface = Color(0xFF2C3134),
	outline = Color(0xFF82939C),
	outlineVariant = Color(0xFF394951),
	scrim = Color(0xFF000000),
	surfaceDim = Color(0xFF0E1417),
	surfaceBright = Color(0xFF353A3D),
	surfaceContainerLowest = Color(0xFF080F13),
	surfaceContainerLow = Color(0xFF171C1F),
	surfaceContainer = Color(0xFF1B2023),
	surfaceContainerHigh = Color(0xFF262B2D),
	surfaceContainerHighest = Color(0xFF303638),
)

/** Light scheme, same seed and same hue, tones mapped Material's way. */
internal val DropSauceLightColors: ColorScheme = lightColorScheme(
	primary = Color(0xFF006684),
	onPrimary = Color(0xFFFFFFFF),
	primaryContainer = Color(0xFFBEE9FF),
	onPrimaryContainer = Color(0xFF001F2A),
	inversePrimary = Color(0xFF68D3FF),
	secondary = Color(0xFF3E6374),
	onSecondary = Color(0xFFFFFFFF),
	secondaryContainer = Color(0xFFC2E8FC),
	onSecondaryContainer = Color(0xFF001F2A),
	tertiary = Color(0xFF715B38),
	onTertiary = Color(0xFFFFFFFF),
	tertiaryContainer = Color(0xFFFBDEB6),
	onTertiaryContainer = Color(0xFF261A00),
	error = Color(0xFFA93621),
	onError = Color(0xFFFFFFFF),
	errorContainer = Color(0xFFFFDBD1),
	onErrorContainer = Color(0xFF360F00),
	background = Color(0xFFF4FAFE),
	onBackground = Color(0xFF171C1F),
	surface = Color(0xFFF4FAFE),
	onSurface = Color(0xFF171C1F),
	surfaceVariant = Color(0xFFD3E5EF),
	onSurfaceVariant = Color(0xFF394951),
	surfaceTint = Color(0xFF006684),
	inverseSurface = Color(0xFF2C3134),
	inverseOnSurface = Color(0xFFEBF2F5),
	outline = Color(0xFF697A82),
	outlineVariant = Color(0xFFB7C9D3),
	scrim = Color(0xFF000000),
	surfaceDim = Color(0xFFD4DBDF),
	surfaceBright = Color(0xFFF4FAFE),
	surfaceContainerLowest = Color(0xFFFFFFFF),
	surfaceContainerLow = Color(0xFFEEF5F8),
	surfaceContainer = Color(0xFFE8EFF3),
	surfaceContainerHigh = Color(0xFFE2E9ED),
	surfaceContainerHighest = Color(0xFFDDE3E7),
)

/**
 * The reader's own colours, which do not follow the light/dark setting and must not.
 *
 * A manga page is ink on paper photographed; a pale surround throws light at the reader
 * from around the page and changes how the art reads. `ReaderScreen` already forced
 * black for that reason. What was wrong was not the decision but the spelling: nine
 * literal `Color.White` calls and one `Color(0xFF2A2732)`, which is also the last
 * surviving trace of the purple scheme.
 *
 * [ReaderChrome] is lighter than the old bar on purpose. The existing comment in the
 * reader says a bar that blends into the page is a bar nobody finds. At 1.71:1 against
 * the page the bar alone still does not carry that, so [ReaderChromeOutline] does: it is
 * 6.60:1 against the page and 3.86:1 against the bar, clearing the 3:1 non-text
 * threshold on *both* sides, which the old 18%-alpha white rule (about 2.2:1) did not.
 */
object DropSauceReaderColors {

	/** The ground the strip sits on. */
	val ReaderPage = Color(0xFF000000)

	/** Top and bottom bar fill. */
	val ReaderChrome = Color(0xFF303638)

	/** Primary chrome text. */
	val ReaderOnChrome = Color(0xFFEBF2F5)

	/** Secondary chrome text, and failure text drawn on the page. */
	val ReaderOnChromeVariant = Color(0xFFB7C9D3)

	/** The rule between bar and page, and outlined control borders on the bar. */
	val ReaderChromeOutline = Color(0xFF82939C)

	/** Focus ring, active slider track, the bookmarked state. The brand hex, both themes. */
	val ReaderAccent = Color(0xFF64D2FF)
}

/**
 * The spacing scale.
 *
 * Before this there were 10 distinct `Arrangement.spacedBy` steps across 118 call sites
 * and 28 distinct `padding(...)` signatures, with screen-edge padding variously 12, 16,
 * 20, 24 and 32. That is a habit, not a scale.
 *
 * Everything is `dp`, so the UI-scale multiplier in `Main.kt` carries it. Nothing here
 * is expressed in `sp`, in pixels, or as a fraction of the window.
 */
object Space {

	/** The gap inside a two-line label block. Nothing else. */
	val hair: Dp = 2.dp

	/** Icon to label inside a control; cover to title in a card. */
	val xs: Dp = 4.dp

	/** Between sibling controls in a row; chip gaps. */
	val sm: Dp = 8.dp

	/** Grid gutters; list-row vertical padding; card internal padding. */
	val md: Dp = 12.dp

	/** The screen's horizontal gutter, everywhere, with no exceptions. */
	val lg: Dp = 16.dp

	/** Between sections within a screen; empty- and error-state internal padding. */
	val xl: Dp = 24.dp

	/** The top inset of a centred empty state. */
	val xxl: Dp = 32.dp
}

/** The two cover sizes. Five existed; they differed by accident, not by intent. */
object ThumbSize {

	/** Every list row with a cover. Was 36x50, 44x62, 48x68, 52x74 and 56x76. */
	val list: DpSize = DpSize(48.dp, 68.dp)

	/** A horizontal strip item. Was declared twice with the same value. */
	val strip: Dp = 132.dp
}

/**
 * Cover proportions, the single declaration.
 *
 * Was spelled three times with the same value, in `DiscoverCommon.kt`, `BrowseScreen.kt`
 * and `SuggestionsScreen.kt`.
 */
const val COVER_ASPECT = 0.7f

/** Long-form text stops here. 640 dp at `bodyMedium` is roughly 72 to 80 characters. */
val ReadableWidth: Dp = 640.dp
