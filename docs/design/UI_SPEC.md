# DropSauce desktop — UI specification

Status: design specification, not implemented. No Kotlin was changed to produce this.
Scope: `desktop/` only. Nothing here touches `app/`. No new dependency is proposed.
Branch: `desktop-port`. Written against the tree at commit `fab43429`.

Read `DECISIONS.md` first. Where this document proposes something that contradicts a
recorded decision or an in-code comment, the quote and the argument are given inline and
flagged **PROPOSED REVISION**. Everything else fits inside what is already decided.

---

## Summary

Five things change.

1. **The accent stops being Material's default purple.** `Main.kt:103` ships bare
   `darkColorScheme()` / `lightColorScheme()`, so every selected chip, every filled
   button and every "new chapters" marker on screen is the stock purple seed while the
   app icon is cyan. Section 1 replaces both with schemes generated from the brand seed
   `#64D2FF`, every role named and every contrast pair computed.
2. **Glyph characters become a real icon set.** Twelve destinations are drawn with
   unicode characters picked one at a time (`★`, `▦`, `⌕`, `✦`, `⬇`, `⤓`, `↻`,
   `↑`, `⇄`, `▣`, `◉`, `☰`) from four different typographic families, with three outright
   collisions. Section 2 maps every one to a Material Symbols Rounded SVG.
3. **The reader becomes the reader.** Today the navigation rail is pinned beside the
   page on every page of every chapter, "Back" is the loudest control on the screen, and
   the bottom bar's zoom group is cut off the right edge of a 1270px window. Section 4.4
   hides the rail while reading, demotes Back to an icon, and replaces the overflowing
   bar with one that cannot overflow at any UI scale.
4. **Four screens stop overflowing or clipping.** The navigation rail loses its last two
   destinations with no way to reach them; Library's chapter-filter row scrolls off-screen
   with no visible affordance and its category row cannot scroll at all; Details prints
   the title twice and gives five buttons equal weight; Sources is a 658-row list with
   half the window empty.
5. **One empty state, one error state, one loading state.** There are at least four
   distinct error treatments and three distinct empty treatments in the tree. Section 3
   collapses them into `EmptyBox`, `ErrorBox` and `LoadingBox`, keeping the existing names
   and signatures where they already exist so the diff stays small.

Cut from scope, with reasons, in section 8.

## User goal

Someone opens DropSauce to do one of four things: resume what they were reading, find
the next thing to read, file what they have found, or read. The first and the last are
daily; the middle two are weekly. The current layout gives all four the same visual
weight, and gives the thing you do most — read — the least room, because the rail, a top
bar and a status bar are all permanently subtracted from the page.

---

## 0. What this is designed against

Every claim below is from a file read or a capture looked at.

| Evidence | What it shows |
|---|---|
| `desktop/src/main/kotlin/org/koitharu/kotatsu/desktop/Main.kt:103` | `colorScheme = if (dark) darkColorScheme() else lightColorScheme()` — stock Material purple, no seed |
| `Main.kt:229-234` | `ROOTS`: four shell destinations, each a unicode glyph in a `Text` |
| `Main.kt:181`, `Main.kt:209` | glyphs rendered as `Text(glyph, style = typography.titleMedium)`, not as icons |
| `Main.kt:107-111` | `Row { NavRail(state); VerticalDivider(); Box(weight(1f)) { Router(state) } }` — the rail is drawn for every screen including the reader |
| `Main.kt:159` | `NavigationRail { ... }` with no scroll container |
| `ui/Common.kt:126-165` | `TopBar`, the only shared chrome; back arrow is `Text("←")` |
| `ui/Common.kt:138-139` | *"A glyph rather than `Icons.AutoMirrored.Filled.ArrowBack`, which would mean adding the material-icons artifact for a single arrow."* |
| `ui/LibraryScreen.kt:461-483` | `ChapterFilterChips` — `horizontalScroll`, no overflow indicator |
| `ui/LibraryScreen.kt:492-495` | `CategoryChips` — a plain `Row`, **no scroll at all**; categories past the window edge are unreachable |
| `ui/LibraryScreen.kt:148` | `GridCells.Adaptive(minSize = 150.dp)` with `contentPadding(12.dp)`, `spacedBy(12.dp)` |
| `ui/LibraryScreen.kt:568` | cover `aspectRatio(0.7f)`, `RoundedCornerShape(10.dp)` |
| `ui/DetailsScreen.kt:142` + `ui/DetailsScreen.kt:353` | the title is printed twice: once in `TopBar`, once in `Header` |
| `ui/DetailsScreen.kt:150-189` | five actions in one `horizontalScroll` row; `Continue` is `Button`, the other four are `OutlinedButton` |
| `ui/CatalogScreen.kt:69-74` | a flat `LazyColumn` of 658 two-line rows, full width |
| `ui/ReaderScreen.kt:284` | `.background(Color.Black)` |
| `ui/ReaderScreen.kt:433,440,447,689,755,771,792,802,808` | nine hardcoded `Color.White` derivatives |
| `ui/ReaderScreen.kt:759` | `.background(Color(0xFF2A2732))` — a hardcoded purple-grey bar |
| `ui/ReaderScreen.kt:429` | `Button(onClick = onBack) { Text("Back") }` — filled primary, the loudest control on the reading screen |
| `ui/ReaderScreen.kt:756-765` | the status bar's answer to overflow is `horizontalScroll` |
| `ui/ReaderScreen.kt:316` | `Key.Escape -> { onBack(); true }` — a keyboard way out of the reader already exists |
| `feature/discover/DiscoverCommon.kt:32-60` | `CoverCard`: `aspectRatio(0.7f)`, `RoundedCornerShape(10.dp)`, 2-line title, no secondary line |
| `feature/discover/GlobalSearchScreen.kt:345` | collapse chevrons are the literal characters `"▸"` / `"▾"` |
| `feature/discover/SourceBrowseScreen.kt:407` | the browse grid is `GridCells.Adaptive(minSize = 150.dp)`, matching Library |
| capture `shot-library-small.png` | chapter-filter row cut at "100 o…"; rail cut at "Bookmarks" |
| capture `shot-sources-s.png` | 658 rows, full-width, right half of the window empty |
| capture `shot-discover-s.png` | mode chips, search field, "Recent", then an empty 350px-tall region |
| capture `shot-details-s.png`, `r1s.png` | title twice; five buttons of near-equal weight; a 95-character measure on the description |
| capture `r2s.png` | the rail is still drawn in the reader; `Back` is a filled pill; the bottom bar is cut mid-word at "Zoom"; a page failure renders as centred body text plus a "Try again" text link |

### Three numbers measured off the captures

**The rail cannot fit its own contents.** `shot-library-small.png` is a 1270×810 window
at UI scale 1.5, saved 1100 px wide, so image distances are multiplied by 1270/1100 =
1.1545 to recover physical pixels. The rail's label baselines are evenly pitched — 79
image px between every consecutive pair, seven times — which is **91 physical px, or 61
dp per destination**. Eleven destinations is 11 × 91 = 1001 px plus the 12 dp top spacer
(`Main.kt:160`, 18 px) = **1019 px against an 810 px window**. Eight and a half fit,
which is exactly what the capture shows: nine labels with "Bookmarks" cut in half and
Updates and App update absent. At UI scale 2.0 the same eleven need about 1366 px and six
fit. **Scrolling the rail is not a polish item, it is the difference between App update
being reachable and not existing.**

The rail itself measures ~120 physical px wide in both the 1100 px and the 900 px
captures, which is 80 dp at UI scale 1.5 — Material's standard `NavigationRail` width.
That is 9.4% of a 1270 px window.

**The reader's bottom bar needs about 1125 dp and has about 767 dp.** In `r2s.png` the
bar's content area runs from the rail edge to the window edge, 767 dp at UI scale 1.5,
and "Zoom" is cut mid-label. The controls still to come after that cut — a minus button,
a 150 dp slider (`ReaderScreen.kt:815`), a plus button and a reset button with
`widthIn(min = 76.dp)` (`ReaderScreen.kt:819`) plus four 12 dp gaps — are roughly
358 dp more. At UI scale 2.0 on a 1920 px display the bar would have about 920 dp. The
inline layout does not fit at the *default* scale on the *default* window size, so a
responsive breakpoint is the wrong fix; the collapsed form has to be the only form.

**The cover grid's minimum cell is 150 dp and the cover is drawn at aspect 0.7.** So the
smallest cover is 150 × 214 dp. At UI scale 1.5 that is 225 × 321 physical px, and
`RemoteImage` rounds its decode target up to the next 128 px step
(`Common.kt:115-122`), giving a 384 px decode. This matters for section 4.1: changing
the grid's minimum cell size changes the decode bucket and therefore the memory
behaviour D37 measured. Any change to `minSize` has to be checked against D37's budget.

---

## 1. Design tokens

### 1.1 Colour: the seed and how the ramps were produced

Seed: **`#64D2FF`**, taken from `desktop/src/main/resources/dropsauce.png`.

Measured, not assumed:

```
#64D2FF   CIELAB  L* = 79.62   C* = 36.54   h = 239.35 deg
#1A1A1A   CIELAB  L* =  9.26   (the icon's near-black ground)
```

Tonal ramps were generated in CIELAB at the seed's own hue angle. This is the right
space for the job because **Material 3's HCT "tone" is CIELAB L\* exactly** — H and C
come from CAM16, but T is L\*. So a ramp built at target L\* values lands on Material's
tone grid on the one dimension that governs contrast, and every hex below was produced
by a script rather than picked by eye. Chroma is clamped per tone to the sRGB gamut by
bisection, which matters at this hue: maximum in-gamut chroma at the seed's hue is 41.8
at L\*70 but only 17.8 at L\*90, so a naive constant-chroma ramp would clip.

Six palettes:

| palette | hue (Lab) | target chroma | why |
|---|---|---|---|
| Primary | 239.35 | 36 | the seed's own hue and chroma |
| Secondary | 239.35 | 16 | same hue, desaturated — supporting surfaces |
| Tertiary | **80.0** | 24 | a warm sand, see below |
| Neutral | 239.35 | 3 | faintly brand-tinted greys, so surfaces are not dead |
| Neutral variant | 239.35 | 8 | outlines and variant surfaces |
| Error | **40.0** | 60 | the measured Lab hue of sRGB pure red |

**Tertiary is warm, not purple.** Material's default TonalSpot puts tertiary at
hue + 60, which here is 299 deg — a lavender, generating `#CAC1ED` at tone 80. That is
visually the same lavender-purple the app is trying to get away from, so a reader would
reasonably conclude the fix had not worked. Hue 80 instead gives a sand/amber
(`#DEC29B` dark, `#715B38` light) that is unmistakably not the primary, not the error
red, and carries "new / in progress" better than a second blue would. This is a
deliberate departure from the TonalSpot default and the reason is above.

**The brand's near-black appears in the scheme.** `#1A1A1A` is L\* 9.26. The neutral
ramp's tone 10 is `#171C1F` at L\* 10.0, a difference of 0.74 L\*. That tone is
`surfaceContainerLow` in dark. The dark `surface` itself is tone 6 (`#0E1417`), one step
below it, following Material's dark mapping. So the icon's ground and the app's card
surface are the same darkness to within a rounding step, which is the tie that matters.

**Dark `primary` is the brand hex itself.** The generated tone 80 is `#68D3FF`; the
brand is `#64D2FF`. Rather than ship a colour that is almost the logo, dark `primary`
and `surfaceTint` are pinned to `#64D2FF` exactly and every contrast figure below is
computed against that pinned value, not the generated neighbour.

### 1.2 Dark colour scheme

Every argument to `darkColorScheme(...)`. All 48 roles exist in this build; the 20 not
listed (`primaryFixed`, `onPrimaryFixedVariant`, and the rest of the Fixed family) are
left at their defaults because nothing in `desktop/` reads them.

```kotlin
darkColorScheme(
    primary                 = Color(0xFF64D2FF),
    onPrimary               = Color(0xFF003546),
    primaryContainer        = Color(0xFF004D64),
    onPrimaryContainer      = Color(0xFFBEE9FF),
    inversePrimary          = Color(0xFF006684),
    secondary               = Color(0xFFA6CCE0),
    onSecondary             = Color(0xFF093544),
    secondaryContainer      = Color(0xFF254B5C),
    onSecondaryContainer    = Color(0xFFC2E8FC),
    tertiary                = Color(0xFFDEC29B),
    onTertiary              = Color(0xFF3F2D0C),
    tertiaryContainer       = Color(0xFF584322),
    onTertiaryContainer     = Color(0xFFFBDEB6),
    error                   = Color(0xFFFFB4A1),
    onError                 = Color(0xFF630F00),
    errorContainer          = Color(0xFF8B190A),
    onErrorContainer        = Color(0xFFFFDBD1),
    background              = Color(0xFF0E1417),
    onBackground            = Color(0xFFDDE3E7),
    surface                 = Color(0xFF0E1417),
    onSurface               = Color(0xFFDDE3E7),
    surfaceVariant          = Color(0xFF394951),
    onSurfaceVariant        = Color(0xFFB7C9D3),
    surfaceTint             = Color(0xFF64D2FF),
    inverseSurface          = Color(0xFFDDE3E7),
    inverseOnSurface        = Color(0xFF2C3134),
    outline                 = Color(0xFF82939C),
    outlineVariant          = Color(0xFF394951),
    scrim                   = Color(0xFF000000),
    surfaceDim              = Color(0xFF0E1417),
    surfaceBright           = Color(0xFF353A3D),
    surfaceContainerLowest  = Color(0xFF080F13),
    surfaceContainerLow     = Color(0xFF171C1F),
    surfaceContainer        = Color(0xFF1B2023),
    surfaceContainerHigh    = Color(0xFF262B2D),
    surfaceContainerHighest = Color(0xFF303638),
)
```

### 1.3 Light colour scheme

```kotlin
lightColorScheme(
    primary                 = Color(0xFF006684),
    onPrimary               = Color(0xFFFFFFFF),
    primaryContainer        = Color(0xFFBEE9FF),
    onPrimaryContainer      = Color(0xFF001F2A),
    inversePrimary          = Color(0xFF68D3FF),
    secondary               = Color(0xFF3E6374),
    onSecondary             = Color(0xFFFFFFFF),
    secondaryContainer      = Color(0xFFC2E8FC),
    onSecondaryContainer    = Color(0xFF001F2A),
    tertiary                = Color(0xFF715B38),
    onTertiary              = Color(0xFFFFFFFF),
    tertiaryContainer       = Color(0xFFFBDEB6),
    onTertiaryContainer     = Color(0xFF261A00),
    error                   = Color(0xFFA93621),
    onError                 = Color(0xFFFFFFFF),
    errorContainer          = Color(0xFFFFDBD1),
    onErrorContainer        = Color(0xFF360F00),
    background              = Color(0xFFF4FAFE),
    onBackground            = Color(0xFF171C1F),
    surface                 = Color(0xFFF4FAFE),
    onSurface               = Color(0xFF171C1F),
    surfaceVariant          = Color(0xFFD3E5EF),
    onSurfaceVariant        = Color(0xFF394951),
    surfaceTint             = Color(0xFF006684),
    inverseSurface          = Color(0xFF2C3134),
    inverseOnSurface        = Color(0xFFEBF2F5),
    outline                 = Color(0xFF697A82),
    outlineVariant          = Color(0xFFB7C9D3),
    scrim                   = Color(0xFF000000),
    surfaceDim              = Color(0xFFD4DBDF),
    surfaceBright           = Color(0xFFF4FAFE),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFEEF5F8),
    surfaceContainer        = Color(0xFFE8EFF3),
    surfaceContainerHigh    = Color(0xFFE2E9ED),
    surfaceContainerHighest = Color(0xFFDDE3E7),
)
```

### 1.4 Reader chrome: a sixth, theme-independent set

The reader does not follow the light/dark scheme and must not. A manga page is a
photograph of ink on paper; putting it on a pale grey ground throws light at the reader
from around the page and changes how the art reads. `ReaderScreen.kt:284` already forces
`Color.Black` for exactly this reason. The problem is not the decision, it is that the
decision is spelled with nine literal `Color.White` calls and one `Color(0xFF2A2732)`.

So: a named object, `DropSauceReaderColors`, in the token file. Not in the colour scheme,
because these values must not change with `ThemeMode`.

| token | hex | what it is |
|---|---|---|
| `ReaderPage` | `#000000` | the ground the strip sits on. Unchanged behaviour, named. |
| `ReaderChrome` | `#303638` | the top and bottom bar fill. Replaces `Color(0xFF2A2732)`. |
| `ReaderOnChrome` | `#EBF2F5` | primary chrome text. Replaces `Color.White`. |
| `ReaderOnChromeVariant` | `#B7C9D3` | secondary chrome text and on-page failure text. Replaces the three `Color.White.copy(alpha = 0.6f/0.7f)` values. |
| `ReaderChromeOutline` | `#82939C` | the rule between bar and page, and outlined-control borders on the bar. Replaces `Color.White.copy(alpha = 0.18f)`. |
| `ReaderAccent` | `#64D2FF` | focus ring, the active slider track, the "bookmarked" state. The brand hex, identical in both themes. |

`ReaderChrome` is tone 22 rather than the current `#2A2732`. The existing comment at
`ReaderScreen.kt:753-754` says *"Deliberately lighter than the reader's black and
separated by a rule. A bar that blends into the page is a bar nobody finds, which is what
the first version did."* That reasoning is kept and strengthened: `#2A2732` against pure
black is a weak separation, and the `0.18f`-alpha white rule that was supposed to carry
it composites to about 2.2:1 against the bar. The replacement bar is 1.71:1 against the
page and the rule is 6.60:1 against the page and 3.86:1 against the bar, so the rule
itself clears the 3:1 non-text threshold **on both sides**, which is what actually makes
the bar findable. `#2A2732` is also slightly violet (it is the one surviving trace of the
purple scheme in the reader) and is dropped for that reason too.

### 1.5 Contrast audit

Computed with the WCAG 2.x relative-luminance formula on the exact hexes above. "Need"
is 4.5:1 for body text, 3.0:1 for large text and for non-text UI boundaries and states
(WCAG 1.4.11), 1.0 for pairs listed only for reference.

**Dark**

| pair | fg / bg | ratio | need |
|---|---|---|---|
| body text | `#DDE3E7` / `#0E1417` | **14.34** | 4.5 |
| body on a card | `#DDE3E7` / `#1B2023` | **12.69** | 4.5 |
| body on container-high | `#DDE3E7` / `#262B2D` | **11.06** | 4.5 |
| body on container-highest | `#DDE3E7` / `#303638` | **9.48** | 4.5 |
| secondary text | `#B7C9D3` / `#0E1417` | **10.88** | 4.5 |
| secondary text on a card | `#B7C9D3` / `#1B2023` | **9.64** | 4.5 |
| secondary on surfaceVariant | `#B7C9D3` / `#394951` | **5.48** | 4.5 |
| accent text / link | `#64D2FF` / `#0E1417` | **10.79** | 4.5 |
| accent text on a card | `#64D2FF` / `#1B2023` | **9.55** | 4.5 |
| filled button label | `#003546` / `#64D2FF` | **7.64** | 4.5 |
| tonal chip label | `#BEE9FF` / `#004D64` | **7.25** | 4.5 |
| rail indicator label | `#C2E8FC` / `#254B5C` | **7.25** | 4.5 |
| selected filter chip | `#C2E8FC` / `#254B5C` | **7.25** | 4.5 |
| tertiary badge label | `#FBDEB6` / `#584322` | **7.24** | 4.5 |
| tertiary text | `#DEC29B` / `#0E1417` | **10.87** | 4.5 |
| error text | `#FFB4A1` / `#0E1417` | **10.87** | 4.5 |
| error text on a card | `#FFB4A1` / `#1B2023` | **9.62** | 4.5 |
| error button label | `#630F00` / `#FFB4A1` | **7.69** | 4.5 |
| error container label | `#FFDBD1` / `#8B190A` | **7.28** | 4.5 |
| snackbar text | `#2C3134` / `#DDE3E7` | **10.16** | 4.5 |
| outlined control border | `#82939C` / `#0E1417` | **5.84** | 3.0 |
| outlined border on a card | `#82939C` / `#1B2023` | **5.17** | 3.0 |
| focus ring on surface | `#64D2FF` / `#0E1417` | **10.79** | 3.0 |
| focus ring on a card | `#64D2FF` / `#1B2023` | **9.55** | 3.0 |
| focus ring on container-high | `#64D2FF` / `#262B2D` | **8.33** | 3.0 |
| card surface vs page | `#1B2023` / `#0E1417` | 1.13 | ref |
| divider `outlineVariant` | `#394951` / `#0E1417` | 1.99 | ref, see 1.6 |

**Light**

| pair | fg / bg | ratio | need |
|---|---|---|---|
| body text | `#171C1F` / `#F4FAFE` | **16.32** | 4.5 |
| body on a card | `#171C1F` / `#E8EFF3` | **14.78** | 4.5 |
| body on container-high | `#171C1F` / `#E2E9ED` | **14.00** | 4.5 |
| body on container-highest | `#171C1F` / `#DDE3E7` | **13.27** | 4.5 |
| secondary text | `#394951` / `#F4FAFE` | **8.88** | 4.5 |
| secondary text on a card | `#394951` / `#E8EFF3` | **8.05** | 4.5 |
| secondary on surfaceVariant | `#394951` / `#D3E5EF` | **7.22** | 4.5 |
| accent text / link | `#006684` / `#F4FAFE` | **6.17** | 4.5 |
| accent text on a card | `#006684` / `#E8EFF3` | **5.59** | 4.5 |
| filled button label | `#FFFFFF` / `#006684` | **6.49** | 4.5 |
| tonal chip label | `#001F2A` / `#BEE9FF` | **13.25** | 4.5 |
| rail indicator label | `#001F2A` / `#C2E8FC` | **13.21** | 4.5 |
| selected filter chip | `#001F2A` / `#C2E8FC` | **13.21** | 4.5 |
| tertiary badge label | `#261A00` / `#FBDEB6` | **13.19** | 4.5 |
| tertiary text | `#715B38` / `#F4FAFE` | **6.13** | 4.5 |
| error text | `#A93621` / `#F4FAFE` | **6.16** | 4.5 |
| error text on a card | `#A93621` / `#E8EFF3` | **5.58** | 4.5 |
| error button label | `#FFFFFF` / `#A93621` | **6.49** | 4.5 |
| error container label | `#360F00` / `#FFDBD1` | **13.31** | 4.5 |
| snackbar text | `#EBF2F5` / `#2C3134` | **11.62** | 4.5 |
| outlined control border | `#697A82` / `#F4FAFE` | **4.24** | 3.0 |
| outlined border on a card | `#697A82` / `#E8EFF3` | **3.84** | 3.0 |
| focus ring on surface | `#006684` / `#F4FAFE` | **6.17** | 3.0 |
| focus ring on a card | `#006684` / `#E8EFF3` | **5.59** | 3.0 |
| focus ring on container-high | `#006684` / `#E2E9ED` | **5.29** | 3.0 |
| card surface vs page | `#E8EFF3` / `#F4FAFE` | 1.10 | ref |
| divider `outlineVariant` | `#B7C9D3` / `#F4FAFE` | 1.62 | ref, see 1.6 |

**Reader chrome**

| pair | fg / bg | ratio | need |
|---|---|---|---|
| page counter, control labels | `#EBF2F5` / `#303638` | **10.85** | 4.5 |
| muted chrome text | `#B7C9D3` / `#303638` | **7.20** | 4.5 |
| accent on chrome | `#64D2FF` / `#303638` | **7.14** | 4.5 |
| page-failure text, on the page | `#B7C9D3` / `#000000` | **12.31** | 4.5 |
| chapter title, on the page | `#EBF2F5` / `#000000` | **18.55** | 4.5 |
| control border on chrome | `#82939C` / `#303638` | **3.86** | 3.0 |
| bar/page rule, page side | `#82939C` / `#000000` | **6.60** | 3.0 |
| focus ring on chrome | `#64D2FF` / `#303638` | **7.14** | 3.0 |
| chrome bar vs page | `#303638` / `#000000` | 1.71 | ref, carried by the rule above |

**Interaction overlays**, Material's standard alphas of `onSurface` composited over the
base, with the resulting hex and the contrast of body text over the overlaid state.
Disabled content is at 38% and is exempt from 1.4.3 as an inactive control, but the
figure is given so nobody has to guess.

| state | dark on `surface` | dark on `surfaceContainer` | light on `surface` | light on `surfaceContainer` |
|---|---|---|---|---|
| hover, 8% | `#1F2528`, text 11.98 | `#2B3033`, text 10.31 | `#E2E8EC`, text 13.90 | `#D7DEE2`, text 12.63 |
| pressed, 12% | `#272D30`, text 10.78 | `#32373B`, text 9.29 | `#D9DFE3`, text 12.77 | `#CFD6DA`, text 11.69 |
| disabled content, 38% | `#5D6366`, 3.04 | `#656A6D`, 3.00 | `#A0A6A9`, 2.34 | `#999FA2`, 2.31 |

In the reader: hover `#3F4547` (text 8.61), pressed `#464D4F` (text 7.62), disabled
content `#777D80` (2.94).

### 1.6 Two pairs that do not clear 3:1 by fill alone, and what carries them

Being explicit about this rather than quietly listing the ratio and moving on.

**(a) The navigation rail's selected indicator.** No Material tonal fill reaches 3:1
against the rail's own background — it is not a property of this palette, it is how the
indicator is defined. Measured against a `surfaceContainer` rail:

```
dark   secondaryContainer #254B5C on #1B2023 = 1.75:1
dark   primaryContainer   #004D64 on #1B2023 = 1.76:1
light  secondaryContainer #C2E8FC on #E8EFF3 = 1.11:1
```

Selected-ness is a UI state, so WCAG 1.4.11 applies and the fill alone fails. Three
things carry it instead, and any one of them would do:

1. **A 1 dp stroke on the indicator pill in `outline`.** Dark `#82939C` on `#1B2023` =
   **5.17:1**; light `#697A82` on `#E8EFF3` = **3.84:1**. This is the boundary that
   clears 1.4.11.
2. **The icon swaps between the outlined and the filled Material Symbols variant.** Both
   variants are vendored (section 2). This is reinforcement, not the carrier: the FILL
   axis is a strong shape change on `bookmark`, `folder`, `collections_bookmark`,
   `public`, `download` and `settings`, and a weak one on `search` and `history`, which
   are mostly stroke. It is worth having because it costs one extra file per icon, but
   the stroke in (1) is what the conformance claim rests on.
3. The label moves from `onSurfaceVariant` to `onSecondaryContainer` and from the default
   weight to `MaterialTheme.typography.labelLargeEmphasized`.

**(b) `outlineVariant` as a divider.** 1.99:1 dark, 1.62:1 light. This is correct for
Material's intent — `outlineVariant` is a decorative separator — but the app currently
leans on `HorizontalDivider` as the *only* thing separating list rows in the Sources
catalogue (`CatalogScreen.kt:72`), in Discover's source list
(`DiscoverFeature.kt:333`), and in History (`HistoryScreen.kt:173`). A separator that is
the sole carrier of structure has to clear 3:1.

The answer is not a louder divider, which would turn a 658-row list into a ladder. The
answer is to stop depending on it: section 3's `AppListRow` gives every row a minimum
height, 12 dp of internal vertical padding and a hover/focus background, so the rows are
separated by rhythm and by state rather than by a hairline. Dividers stay, as decoration,
at `outlineVariant`. Where a divider genuinely has to carry a *section* break (the one
between the Details header and the chapter list, `DetailsScreen.kt:217`), it uses
`outline` instead: 5.84:1 dark, 4.24:1 light.

### 1.7 Spacing scale

Today there are **10 distinct `Arrangement.spacedBy` steps across 118 call sites** and
**28 distinct `.padding(...)` signatures**, with seven different vertical values paired
with `horizontal = 16.dp` alone, and screen-edge padding variously 12, 16, 20, 24 and 32.
That is not a scale, it is a habit.

Seven steps, all multiples of 4 except the hairline:

| token | value | used for |
|---|---|---|
| `Space.hair` | 2.dp | the gap inside a two-line label block; nothing else |
| `Space.xs` | 4.dp | icon-to-label inside a control; cover-to-title in a card |
| `Space.sm` | 8.dp | between sibling controls in a row; chip gaps |
| `Space.md` | 12.dp | grid gutters; list-row vertical padding; card internal padding |
| `Space.lg` | 16.dp | **the screen's horizontal gutter, everywhere, with no exceptions**; between a row's thumbnail and its text |
| `Space.xl` | 24.dp | between sections within a screen; empty- and error-state internal padding |
| `Space.xxl` | 32.dp | the top inset of a centred empty state |

Rules that follow from it:

- The screen gutter is `Space.lg` (16) on every screen. `DetailsScreen.kt` uses 20,
  `CatalogScreen.kt`'s rows use 20, `DiscoverFeature.kt:316` uses 20. All become 16.
- `contentPadding` on a grid is `PaddingValues(Space.md)` and both arrangements are
  `spacedBy(Space.md)`. This is already what Library and browse do; the outlier is
  `SuggestionsScreen.kt:88`'s 14 dp vertical, which becomes 12.
- A list row's vertical padding is `Space.md` (12) and its horizontal padding is
  `Space.lg` (16). The six different combinations in the tree collapse to one.
- `6.dp` is removed. Its 8 call sites go to 4 or 8. `10.dp` is removed; its 4 go to 8 or
  12. `3.dp`, `14.dp`, `20.dp` as gaps are removed.
- Chip gap is `Space.sm` (8) in every chip container. `FilterPanel.kt` and
  `GlobalSearchScreen.kt:125` use 6; those become 8 so a chip row looks the same
  wherever it appears.

Everything is in `dp`, so the UI-scale multiplier at `Main.kt:100` carries it. Nothing in
this scale is expressed in `sp`, px, or a fraction of the window.

### 1.8 Shape scale

`MaterialTheme.shapes` defaults are kept. The job here is to stop screens reaching past
them for one-off radii. There are currently four different corner radii on cover art
alone: 4, 6, 10 and 12 dp.

| role | value | applied to |
|---|---|---|
| `shapes.extraSmall` | 4.dp | nothing; reserved |
| `shapes.small` | 8.dp | list-row thumbnails (replaces 4 and 6 dp); the status/progress bar track |
| `shapes.medium` | 12.dp | **every cover image, in every grid, strip and header** (replaces 10 and 12 dp); cards; the reader's Display popup |
| `shapes.large` | 16.dp | nothing currently; reserved for panels |
| `shapes.extraLarge` | 28.dp | dialogs (Material's own default) |
| `CircleShape` | — | chips, buttons, the rail indicator pill, icon buttons, the progress-bar fill |

One cover radius, one thumbnail radius. The five grid implementations listed in the
inventory all become `shapes.medium`; the five list-row thumbnail sizes (36×50, 44×62,
48×68, 52×74, 56×76) all become `shapes.small`.

Thumbnail sizes also collapse. Two sizes, both at the cover aspect:

- `ThumbSize.list` = 48 × 68 dp — every list row with a cover.
- `ThumbSize.strip` = 132 dp wide — the existing `STRIP_ITEM_WIDTH`, which is already
  declared twice (`DiscoverCommon.kt:93` and `RelatedTitles.kt:269`) and becomes one
  declaration.

### 1.9 Type scale

No custom typography. `MaterialTheme.typography` is used as shipped; what is specified is
which role means what, so that "a section header" is one style rather than three.

| use | role | notes |
|---|---|---|
| Screen title in `TopBar` | `titleLarge` | unchanged from `Common.kt:148` |
| Screen subtitle in `TopBar` | `bodySmall` + `onSurfaceVariant` | unchanged from `Common.kt:155-156` |
| Section header inside a screen | `titleSmall` | the three `readerx` editors and `RelatedTitles.kt:222` already use this; `DetailsScreen.kt:247`'s "N chapters" joins them |
| Dialog title | `headlineSmall` | Material's own default for `AlertDialog` |
| A title in a list row | `bodyLarge`, `maxLines = 1`, `Ellipsis` | |
| A title in a grid cell | `bodyMedium`, `maxLines = 2`, `Ellipsis` | **raised from `bodySmall`.** Every one of the five grid implementations uses `bodySmall` for the single most important text on the card, which is why the Library grid reads as a wall of pictures with captions |
| Secondary line under either | `bodySmall` + `onSurfaceVariant`, `maxLines = 1` | |
| Metadata / counts / status | `labelSmall` + `onSurfaceVariant` | |
| Button and chip labels | `labelLarge` | Material's default; not overridden |
| Selected rail label | `labelLargeEmphasized` | the Expressive emphasised variant, which exists in this build |
| Long-form body (a description) | `bodyMedium` | |
| Empty-state headline | `titleMedium` | |
| Empty-state explanation | `bodyMedium` + `onSurfaceVariant` | |

**Measure.** The description on Details currently runs the full width of the window — 95
characters in `r1s.png` at 1270 px. Long-form text is capped at
`Modifier.widthIn(max = 640.dp)`. 640 dp at `bodyMedium` is roughly 72–80 characters,
which is the readable range. This applies to exactly three places: the Details
description, the empty-state explanation, and the error-state message.

### 1.10 Elevation

No shadow elevation anywhere except where Material draws it itself for menus and dialogs.
Depth is tone, which is how the dark scheme is built and how the light scheme stays flat.

| layer | dark | light |
|---|---|---|
| page background | `surface` `#0E1417` | `surface` `#F4FAFE` |
| navigation rail | `surfaceContainer` `#1B2023` | `surfaceContainer` `#E8EFF3` |
| a card, a panel, a filter sidebar | `surfaceContainer` | `surfaceContainer` |
| a row under hover | +8% `onSurface` overlay | +8% `onSurface` overlay |
| a sticky header over scrolled content | `surfaceContainerHigh` | `surfaceContainerHigh` |
| menu / popup / dialog | `surfaceContainerHigh` + Material's own shadow | same |

The rail moving from `surface` to `surfaceContainer` is a change: today it is the same
colour as the content beside it and is separated only by `VerticalDivider()`
(`Main.kt:109`). Giving it a container tone means the content area reads as the page and
the rail as chrome, at 1.13:1 dark and 1.10:1 light — subtle by design, with the divider
staying to carry the edge.

### 1.11 Motion

`MotionScheme.expressive()` is already provided at `Main.kt:104` and is reachable as
`MaterialTheme.motionScheme`. Nothing new is introduced. What is specified is which spec
goes where, so animations do not get hand-tuned durations:

| change | spec |
|---|---|
| a chip, button or row changing colour on hover, focus or press | `motionScheme.fastEffectsSpec()` |
| the rail's selected indicator sliding | `motionScheme.defaultSpatialSpec()` |
| the filter sidebar opening or closing | `motionScheme.defaultSpatialSpec()` |
| the reader chrome auto-hiding and returning | `motionScheme.fastEffectsSpec()` on alpha |
| a loading placeholder giving way to content | `motionScheme.defaultEffectsSpec()` on a crossfade |
| anything in the webtoon strip | **nothing.** No animation touches the strip. D23 is explicit that a chapter join must be invisible, and the strip only grows at the end; an item-placement animation there would make the join visible, which is the opposite of the requirement |

No motion is decorative, no motion loops, and nothing animates position in a scrolling
list. There is no reduced-motion setting on this platform to read, and the set above is
short enough and fast enough not to need one; if one is added later it switches every
spec above to a snap.

### 1.12 Where the tokens live

One new file, `desktop/src/main/kotlin/org/koitharu/kotatsu/desktop/ui/Theme.kt`,
exporting:

```kotlin
internal val DropSauceDarkColors: ColorScheme      // section 1.2
internal val DropSauceLightColors: ColorScheme     // section 1.3
object DropSauceReaderColors { /* section 1.4 */ } // theme-independent
object Space { val hair; val xs; val sm; val md; val lg; val xl; val xxl }
object ThumbSize { val list: DpSize; val strip: Dp }
const val COVER_ASPECT = 0.7f                      // the single declaration
```

`COVER_ASPECT` is declared three times today (`DiscoverCommon.kt:87`,
`BrowseScreen.kt:296` area, `SuggestionsScreen.kt:336`) with the same value. It moves
here and the others import it.

`Main.kt:103` becomes
`colorScheme = if (dark) DropSauceDarkColors else DropSauceLightColors`.

**A test pins the scheme.** `ThemeContrastTest` asserts, in plain Kotlin with no Compose
runtime, that every pair in the tables in 1.5 meets its stated threshold, computing the
ratio from the `ColorScheme` object itself. The positive control is a deliberately
failing pair in the test's own fixture, so the assertion is proven to fire before it is
trusted on the real scheme. Without that control the test passes whether or not it is
measuring anything.

---

## 2. The icon set

### 2.1 What is being replaced

Every icon in the app today is a unicode character in a `Text`. There is no icon
artifact on the classpath — `desktop/build.gradle.kts:54-70` pulls
`compose.desktop.currentOs`, `compose.foundation` and `libs.compose.material3.cmp` and
nothing else — and `Icons.` appears zero times in `desktop/src/main`. The only
`painterResource` call in the whole source set is the window icon at `Main.kt:84`.

The fourteen `Feature.glyph` values and the four `ROOTS` glyphs come from four different
typographic families and collide three ways:

| glyph | codepoint | used by |
|---|---|---|
| `⤓` | U+2913 | **three** features: `local`, `localx`, `backup` |
| `★` | U+2605 | the `bookmarks` rail slot *and* the reader's "saved" state *and* the Library root (`Main.kt:230`) |
| `▦` | U+25A6 | the Sources root (`Main.kt:231`) *and* the `stats` tool |
| `⬇` | U+2B07 | `downloads` — the only glyph in an emoji-presentation block, so it can render as a colour emoji at a different metric from its neighbours |
| `▣` `◉` `☰` `⇄` `⌕` `✦` `↻` `↑` | — | shapes carrying no evident meaning for Reader, Tracking, Organise, Migrate |

Two are written as `\u` escapes, twelve as literal characters.

### 2.2 How they are loaded

As decided: Material Symbols **Rounded** SVGs vendored into
`desktop/src/main/resources/icons/`, loaded with
`androidx.compose.ui.res.painterResource("icons/ic_name.svg")` — the same API already
used for the window icon, proven to parse an SVG on the classpath to a painter at its
intrinsic 24×24. Apache-2.0; attribution recorded in `README-DESKTOP.md` and in a
`LICENSE` note beside the vendored files.

Naming: `ic_<material_symbols_name>.svg` for the outlined variant (FILL 0) and
`ic_<name>_fill.svg` for the filled variant (FILL 1). Weight 400, grade 0, optical size
24 for every file, so they share a stroke weight — which is the thing the current glyph
set gets most visibly wrong.

Budget: 43 files at roughly 1 KB each is about 45 KB, against 36.9 MB for
`material-icons-extended`.

### 2.3 Navigation rail — 11 destinations, outlined and filled

The filled variant is drawn when the destination is selected.

| Material Symbols name | files | replaces | used at |
|---|---|---|---|
| `collections_bookmark` | `ic_collections_bookmark.svg`, `_fill` | `★` | Library (`Main.kt:230`) |
| `public` | `ic_public.svg`, `_fill` | `▦` | Sources (`Main.kt:231`) |
| `history` | `ic_history.svg`, `_fill` | `↺` | History (`Main.kt:232`) |
| `settings` | `ic_settings.svg`, `_fill` | `⚙` | Settings (`Main.kt:233`) |
| `folder` | `ic_folder.svg`, `_fill` | `⤓` | Local (`LocalFeature.kt:31`) |
| `search` | `ic_search.svg`, `_fill` | `⌕` | Discover (`DiscoverFeature.kt:52`) |
| `auto_awesome` | `ic_auto_awesome.svg`, `_fill` | `✦` | For you (`SuggestionsFeature.kt:20`) |
| `download` | `ic_download.svg`, `_fill` | `⬇` | Downloads (`DownloadFeature.kt:24`) |
| `bookmark` | `ic_bookmark.svg`, `_fill` | `★` | Bookmarks (`ReadingFeatures.kt:21`); **also** the reader's bookmark toggle, replacing `☆`/`★` at `BookmarksScreen.kt:247` |
| `new_releases` | `ic_new_releases.svg`, `_fill` | `↻` | Updates (`Features.kt:47`) |
| `upgrade` | `ic_upgrade.svg`, `_fill` | `↑` | App update (`AppUpdateFeature.kt:26`) |

`↻` → `new_releases` rather than `refresh`: the Updates destination is *"titles with
chapters you have not seen"*, which is a result, and `refresh` is the action that
produces it. Those are different things and `refresh` is needed separately (2.5).

### 2.4 Settings tool rows — 7, outlined only

Rendered at `SettingsScreen.kt:182`, which currently draws `feature.glyph` at
`titleMedium`. These are never in a selected state, so no filled variant is vendored.

| Material Symbols name | file | replaces | tool (`settingsTitle`) |
|---|---|---|---|
| `settings_backup_restore` | `ic_settings_backup_restore.svg` | `⤓` | Backup and restore |
| `category` | `ic_category.svg` | `☰` | Organise library |
| `drive_folder_upload` | `ic_drive_folder_upload.svg` | `⤓` | Import comics |
| `build` | `ic_build.svg` | `⇄` | Fix broken sources |
| `tune` | `ic_tune.svg` | `▣` | Reader preferences |
| `bar_chart` | `ic_bar_chart.svg` | `▦` | Reading statistics |
| `link` | `ic_link.svg` | `◉` | Tracking services |

That resolves all three `⤓` collisions: Local is a `folder`, Import is
`drive_folder_upload`, Backup is `settings_backup_restore`.

### 2.5 Control icons — 15, outlined only

| Material Symbols name | file | replaces | used at |
|---|---|---|---|
| `arrow_back` | `ic_arrow_back.svg` | `Text("←")` | `TopBar`'s back button (`Common.kt:141`) — 19 screens; the reader's Back (`ReaderScreen.kt:429`) |
| `chevron_left` | `ic_chevron_left.svg` | `"‹"` | `BackToSettings` (`Main.kt:225`); reader previous chapter (`ReaderScreen.kt:783`) |
| `chevron_right` | `ic_chevron_right.svg` | `"›"`, `"▸"` | Settings tool-row drill-in (`SettingsScreen.kt:188`); reader next chapter (`ReaderScreen.kt:787`); a collapsed source group (`GlobalSearchScreen.kt:345`) |
| `expand_more` | `ic_expand_more.svg` | `"▾"` | an expanded source group (`GlobalSearchScreen.kt:345`) |
| `filter_list` | `ic_filter_list.svg` | — | the browse Filters toggle (`SourceBrowseScreen.kt:201`); the leading affordance on Library's filter row (4.1) |
| `sort` | `ic_sort.svg` | — | Library's sort control (4.1) |
| `refresh` | `ic_refresh.svg` | — | `ErrorBox`'s Retry (`Common.kt:208`); "Check now"; the reader footer's "Try again" (`ReaderScreen.kt:881`) |
| `close` | `ic_close.svg` | — | dismissing an inline notice: `ChapterCountBar`'s Dismiss (`LibraryScreen.kt:392`), the `downloadNotice` at `DetailsScreen.kt:190` which today cannot be dismissed at all |
| `add` | `ic_add.svg` | — | "New category" (`LibraryScreen.kt:94`, `DetailsScreen.kt:315`) |
| `edit` | `ic_edit.svg` | — | the category Edit chip (`LibraryScreen.kt:511`) |
| `delete` | `ic_delete.svg` | — | "Delete" (`LibraryScreen.kt:694`) |
| `play_arrow` | `ic_play_arrow.svg` | — | Details' single primary action, Continue / Read (4.3) |
| `more_vert` | `ic_more_vert.svg` | — | Details' overflow menu (4.3); the reader's Display popup is `tune`, reused from 2.4 |
| `warning` | `ic_warning.svg` | — | `UnopenableBar` (`LibraryScreen.kt:336`), which today signals a problem with the error *colour* alone |
| `inbox` | `ic_inbox.svg` | — | the shared `EmptyBox` (3.5) |
| `error` | `ic_error.svg` | — | the shared `ErrorBox` (3.6), which today also signals with colour alone |

`warning` and `error` are not decoration. `LibraryScreen.kt:349` and `Common.kt:205`
convey "this is a problem" purely by `colorScheme.error`, which is a colour-only cue and
fails WCAG 1.4.1. An icon beside the text is the cheapest fix.

### 2.6 The `AppIcon` component and what happens when a file is missing

```kotlin
/**
 * A vendored Material Symbols glyph.
 *
 * [name] is the Material Symbols name without the ic_ prefix or the extension, e.g.
 * "arrow_back". [filled] picks the FILL 1 variant, which only the navigation rail uses.
 */
@Composable
fun AppIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    tint: Color = LocalContentColor.current,
)
```

`contentDescription` is **not** defaulted to null. A default of null is how an icon-only
button ends up unlabelled, and there are enough of those here to make it worth the
friction. Decorative use passes an explicit `null`, which is then a visible decision at
the call site.

Size is always 24 dp, set inside `AppIcon` and not exposed. Every icon in the app is
24 dp; the places that currently differ do so by accident of which typography style the
glyph was drawn at (`titleMedium` for rail glyphs, `titleLarge` for the back arrow).

**If the file is missing.** `painterResource` throws on a missing classpath resource, so
the honest answer has two halves:

1. **Build time is where this is caught.** `AppIconsTest` enumerates the names in the
   `AppIcons` object, resolves each through the classloader, and fails if any is absent
   or does not parse. Its positive control is a deliberately fabricated name in the
   test's own fixture, asserted to fail, so the test is proven to detect the condition
   before it is trusted. A missing SVG is a packaging bug and should fail the build.
2. **Runtime does not crash.** `AppIcon` resolves the painter inside a `runCatching` and,
   on failure, draws a 24 dp rounded-square outline in `onSurfaceVariant` and keeps the
   `contentDescription`. This is not an empty catch and not a stub: the failure is
   recorded once per name through the existing `printStackTraceDebug` path, the control
   stays operable, and a screen reader still announces it correctly. Crashing the reader
   because one SVG was dropped from the jar is strictly worse than a visible box, and
   (1) is what stops the box ever shipping.

---

## 3. Shared components

### 3.0 What exists today, and the size of the duplication

Measured across the whole of `desktop/src/main`:

| thing | shared implementation | call sites using it | independent reimplementations |
|---|---|---|---|
| top bar | `TopBar` (`Common.kt:126`) | 19 | **2** private clones: `FeatureHeader` (`SyncUi.kt:132`), `TrackingHeader` (`TrackingUi.kt:36`) — byte-for-byte the same `Row(padding(horizontal = 16.dp, vertical = 12.dp), spacedBy(12.dp))` with `titleLarge` + `bodySmall`/`onSurfaceVariant`; plus 4 ad-hoc headers |
| empty state | none | — | **7**: `EmptyState` (`CurateScreen.kt:773`), `Empty` (`LocalxScreen.kt:608`), `EmptyLocalLibrary` (`LocalScreen.kt:195`), `EmptyNote` (`BookmarksScreen.kt:333`), `EmptyNote` (`SyncUi.kt:158`), `EmptyNote` (`SuggestionsScreen.kt:182`), `TrackingEmptyNote` (`TrackingUi.kt:61`, **zero call sites — dead**); plus 9 inline one-offs |
| error state | `ErrorBox` (`Common.kt:182`) | 6 | **11** inline treatments, including a bare `Text` + `TextButton` on the reader's page failure (`ReaderScreen.kt:680-695`) and a whole `Card` in `BackupScreen.kt:203-220` |
| loading | `LoadingBox` (`Common.kt:169`) | 5 | 4 bare `LinearProgressIndicator`, 4 determinate ones, `CircularProgressIndicator` at 5 hand-picked sizes (14, 18, 20, 36 dp and a 2 dp stroke), and **2 verbatim copies** of a hand-drawn `ProgressBar` (`DiscoverCommon.kt:70`, `SuggestionsScreen.kt:282`) carrying the same justifying comment |
| cover card | `CoverCard` (`DiscoverCommon.kt:32`) | 2 | **4**: `BrowseScreen.kt:256`, `LocalScreen.kt:215`, `CurateScreen.kt:551`, `SuggestionsScreen.kt:213` — all `Adaptive(150.dp)` + `aspectRatio(0.7f)` + `RoundedCornerShape(10.dp)`, with 3 separate `COVER_ASPECT` constants |
| list row with a cover | none | — | **6**, with thumbnails at 36×50, 44×62 (twice), 48×68, 52×74 and 56×76, and corner radii of 4, 4, 6, 6 and 6 dp; `LocalxScreen.kt:543` has `widthIn(max = 48.dp)` with **no height and no aspect ratio at all** |
| chip row | none | — | **15** containers across 3 incompatible overflow strategies: `horizontalScroll` (3 sites), `FlowRow` (5 sites), **nothing at all** (7 sites) |

Nine components below. Four already exist and keep their names. Every signature change is
called out.

---

### 3.1 `TopBar` — **existing, signature changed**

```kotlin
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,          // NEW
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",             // NEW
    trailing: @Composable RowScope.() -> Unit = {},   // CHANGED: was `@Composable () -> Unit`
)
```

Three changes:

- **`modifier` added as the second parameter**, per the Compose convention. All 19 call
  sites use named arguments, so this is source-compatible — but that has to be confirmed
  by compiling, not asserted.
- **`backLabel`**: the back control becomes an icon-only `IconButton`, and an icon-only
  button needs a name. It is a parameter rather than a constant because "Back" is wrong
  on the Details screen reached from a search result, where it is "Back to results".
- **`trailing` becomes `RowScope`-scoped**, so a trailing slot can use `weight` and so
  two trailing controls get the standard gap without each caller re-stating it.

Internals: back arrow is `AppIcon("arrow_back", contentDescription = backLabel)` instead
of `Text("←", style = titleLarge)` (`Common.kt:141`). Gutter becomes `Space.lg`
horizontal, `Space.sm` vertical. Title stays `titleLarge`, `maxLines = 1`, ellipsised;
subtitle stays `bodySmall` + `onSurfaceVariant`.

States: `default`; `hover`/`focus`/`pressed` apply only to the back button and to
`trailing` and are the standard Material `IconButton` states, which read the overlay
alphas in 1.5. There is no loading, empty, error or disabled state — the bar is always
drawn, even over an error, so the way out is never lost.

`FeatureHeader` and `TrackingHeader` are deleted and their 3 call sites use `TopBar`.
The comment at `SyncUi.kt:40-46` justifies the duplication as keeping the feature
independent of `desktop/ui/Common.kt`. **PROPOSED REVISION, small:** that independence
was worth having while five agents were building five areas in parallel; the areas are
integrated now, `FeatureContext` is already the contract and already lives outside the
feature directories, and the cost of the duplication is that a change to the app's
header is a change in three files that will drift. The two clones go.

---

### 3.2 `RemoteImage` — **existing, unchanged**

Signature is left exactly as it is (`Common.kt:49-58`). Its `BoxWithConstraints`
self-measurement, its 128 px decode step and its `background` escape hatch are all load
bearing for D37 and must not be touched by this pass. Callers keep applying
`.clip(...)` themselves; the only change is that they all apply `shapes.medium` or
`shapes.small` instead of five different literal radii.

One behavioural note that is already correct and should stay: on failure it draws
`reason ?: "no cover"` rather than an error (`Common.kt:83-93`), because a missing cover
is normal. This is deliberately *not* folded into `ErrorBox`.

---

### 3.3 `CoverCard` — **existing, moved and widened**

Moves from `feature/discover/DiscoverCommon.kt` to `ui/Covers.kt`, because four other
areas have their own copy and a shared component cannot live inside one feature's
directory.

```kotlin
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
    /** A short pill on the cover's top end corner. */
    badge: CoverBadge? = null,
    /** Something wrong with this item. Drawn under the text with a warning icon. */
    problem: String? = null,
    /** Over the cover's top start corner, for a selection checkbox. */
    overlay: @Composable (BoxScope.() -> Unit)? = null,
    /** Under the text, end aligned, for a per-card action. */
    action: @Composable (() -> Unit)? = null,
)

data class CoverBadge(val text: String, val tone: CoverBadgeTone)
enum class CoverBadgeTone { New, Neutral }
```

It takes `title`/`coverUrl` rather than a `Manga`, because `LocalScreen` and
`CurateScreen` do not have one and currently reimplement the card for that reason alone.

Geometry: cover `fillMaxWidth().aspectRatio(COVER_ASPECT).clip(shapes.medium)`; title
`bodyMedium` (**raised from `bodySmall`**), `maxLines = 2`, ellipsised; `Column`
arrangement `spacedBy(Space.xs)`.

Badge: `CoverBadgeTone.New` → `tertiaryContainer` / `onTertiaryContainer`, 7.24:1 dark
and 13.19:1 light. `Neutral` → `surfaceContainerHighest` / `onSurface`, 9.48:1 dark and
13.27:1 light. `labelSmall`, `CircleShape`, `Space.xs` padding, inset `Space.sm` from
the cover's top-end corner.

| state | treatment |
|---|---|
| default | as above |
| hover | 8% `onSurface` overlay on the whole card, `fastEffectsSpec` |
| focus | 2 dp `primary` ring on the card bounds at `shapes.medium`; 10.79:1 dark, 6.17:1 light |
| pressed | 12% overlay |
| selected (`overlay` carries a checkbox) | 2 dp `primary` border **and** the checkbox checked — never the border alone |
| loading | the cover area is `surfaceVariant` with nothing in it; `RemoteImage` already does this and deliberately shows no spinner |
| error (cover) | `RemoteImage`'s own `reason` text; the card is still clickable |
| problem (item) | `problem` text in `error` + an `ic_warning`; the card stays clickable, because `LibraryScreen.kt:552-556` is right that a card leading to the repair beats a card that swallows the click |
| empty | not applicable; a card is never empty |

---

### 3.4 `AppListRow` — **new**

Replaces six row implementations. No okhttp or `ImageCache` in the signature; the
thumbnail is a slot, so the row has no idea what an image is.

```kotlin
@Composable
fun AppListRow(
    headline: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    /** Counts, timestamps, status. Joined with a middle dot by the row, not the caller. */
    meta: List<String> = emptyList(),
    /** Something wrong with this row. Drawn in `error` with a warning icon. */
    problem: String? = null,
    /** Usually `RemoteImage(... ThumbSize.list ... shapes.small)`, or a checkbox, or an icon. */
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    /** 0f..1f draws a progress track under the text. Null draws none. */
    progress: Float? = null,
    selected: Boolean = false,
)
```

`meta` is a list, not a pre-joined string, because the middle-dot separator is currently
spelled two different ways — `"·"` in three files and the literal `·` in sixteen —
and joining it in one place ends that. It also means a row with one piece of metadata
does not end up with a trailing separator, which is the bug that produces `"MangaBat  ·  "`.

Geometry: `fillMaxWidth()`, `heightIn(min = 64.dp)`, padding `Space.lg` horizontal and
`Space.md` vertical, `spacedBy(Space.lg)` between leading, text and trailing. Headline
`bodyLarge`, `maxLines = 1`, ellipsised. Supporting `bodySmall` + `onSurfaceVariant`,
`maxLines = 1`. Meta `labelSmall` + `onSurfaceVariant`. Inner column `spacedBy(Space.hair)`.

The `heightIn(min = 64.dp)` is what lets dividers stop carrying structure (see 1.6), and
it is the minimum rather than a fixed height so that a row whose text wraps at UI scale
2.0 grows instead of clipping.

| state | treatment |
|---|---|
| default | `surface`, no fill |
| hover | 8% `onSurface` overlay across the full row width |
| focus | 2 dp `primary` inset ring; the row scrolls itself into view |
| pressed | 12% overlay |
| disabled (`onClick == null`) | content at 38% `onSurface`; no overlay reacts; not in the tab order |
| selected | `secondaryContainer` fill **and** a `check` icon in `leading`; 7.25:1 dark, 13.21:1 light for the text on it |
| problem | `problem` line in `error` with `ic_warning`; the row stays clickable |

---

### 3.5 `SectionHeader` — **new**

```kotlin
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
)
```

`text` at `titleSmall`, `count` at `labelMedium` + `onSurfaceVariant` beside it. Padding
`Space.lg` horizontal, `Space.md` vertical. Replaces the three near-identical header
rows in `readerx`, the strip header at `RelatedTitles.kt:221`, the group header at
`GlobalSearchScreen.kt:346`, `DetailsScreen.kt:245`'s "N chapters" and the bare
`titleSmall` texts in `BackupScreen.kt`.

States: `default`, plus whatever `trailing` provides. A section header is never
disabled, loading or empty — if a section has nothing in it, the section is not drawn
and an `EmptyBox` takes the whole area.

---

### 3.6 `EmptyBox` — **new**

```kotlin
@Composable
fun EmptyBox(
    headline: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    /** A Material Symbols name. Null draws no icon. */
    icon: String? = "inbox",
    action: @Composable (() -> Unit)? = null,
)
```

Centred `Box(fillMaxSize)`, `Column` with `spacedBy(Space.sm)`, `padding(Space.xxl)`,
`CenterHorizontally`. Icon at 24 dp in `onSurfaceVariant`. Headline `titleMedium` in
`onSurface`. Detail `bodyMedium` + `onSurfaceVariant`, centred, `widthIn(max = 640.dp)`.

This is one component, not seven. It takes the *best* of the seven: the
headline-plus-detail shape from `CurateScreen.kt:773` (the only one that separates "what
is empty" from "what to do about it"), centring from `LocalScreen.kt:195`, and an
action slot that none of them has.

Two existing behaviours are explicitly **fixed** by adopting it:

- `BrowseScreen.kt:216` and `:224` put their empty text in a `Box(Modifier.fillMaxSize())`
  with **no `contentAlignment`**, so it renders top-start in a full-height box. So do
  `GlobalSearchScreen.kt:270`, `:279` and `SourceBrowseScreen.kt:366`, `:378`. Five
  screens show their empty state stuck to the top-left corner of an otherwise blank
  window, which is visible in `shot-discover-s.png`. `EmptyBox` centres.
- `MigrationScreen.kt:120-121` renders *"No other source has this title."* through
  `ErrorBox`. Nothing failed; the search completed and found nothing. It becomes an
  `EmptyBox` with `icon = "search"`, and keeps the Cancel sibling the D28 sweep
  deliberately preserved.

`TrackingEmptyNote` (`TrackingUi.kt:61`) has zero call sites and is deleted rather than
migrated.

| state | treatment |
|---|---|
| default | as above |
| with `action` | one button, `Space.sm` below the detail; this is the only button on screen, so it is a filled `Button` |
| hover / focus / pressed | only on `action` |
| no `detail` | headline alone, still centred |

---

### 3.7 `ErrorBox` — **existing, signature extended**

```kotlin
@Composable
fun ErrorBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    headline: String = "That did not work",   // NEW
    compact: Boolean = false,                 // NEW
    extra: @Composable () -> Unit = {},
)
```

The two new parameters go **before** `extra`, so the two call sites that pass `extra` as
a trailing lambda (`DetailsScreen.kt:206`, `ReaderScreen.kt:325`) keep working unchanged
— a trailing lambda binds to the last parameter. The `extra` slot and the comment that
justifies it (`Common.kt:186-192`, D28: *"Retry is the right answer to a timeout and the
wrong one to a 404"*) are kept exactly as they are.

`compact = false` is today's behaviour: a centred full-height box. `compact = true` is a
single inline strip — icon, message, retry — at `Space.lg`/`Space.sm` padding, for the
case where content is already on screen and must not be thrown away. That case exists in
five places today and is hand-rolled in all five: `SourceBrowseScreen.kt:390-405`
("Could not load more"), `BookmarksScreen.kt:135`, `UpdatesScreen.kt:165`,
`BackupScreen.kt:203`, `DownloadsScreen.kt:184`.

Internals: `ic_error` at 24 dp in `error`, then `headline` at `titleMedium` in
`onSurface`, then `message` at `bodyMedium` in `onSurfaceVariant`,
`widthIn(max = 640.dp)`, then the Retry button, then `extra`.

**The message stops being `colorScheme.error`.** Today the whole message is drawn in
`error` (`Common.kt:205`), which is a wall of red for a paragraph of explanation, and
leaves the colour doing all the signalling. The icon and the headline carry "this is a
problem"; the explanation reads in `onSurfaceVariant` at 10.88:1 dark and 8.88:1 light.

This also absorbs the reader's page-failure treatment, which `r2s.png` shows as centred
body text plus a bare "Try again" link (`ReaderScreen.kt:680-695`) — the fourth distinct
error shape in the app. It becomes `ErrorBox(message = reason, onRetry = { retry++ })`
rendered over the page with `DropSauceReaderColors` substituted for the scheme roles
(see 4.4).

Three places that currently render a *failure* in a non-failure colour are fixed by
adopting it: `SuggestionsScreen.kt:163` (*"The refresh could not run"* in
`onSurfaceVariant`), `UpdatesScreen.kt:165-169` (*"The run stopped"* with no colour role
at all), and `LocalScreen.kt:169-192` (import failures in `onSurfaceVariant` on a
`surfaceVariant` background).

| state | treatment |
|---|---|
| default | icon + headline + message |
| retryable | plus a filled `Button` with `ic_refresh` and the label "Try again" |
| not retryable | `onRetry = null`; the only control is whatever `extra` provides |
| retry in flight | the button shows a 16 dp indeterminate indicator and is disabled; without this, D28's "a button that silently does not work" failure mode is invisible again |
| compact | one row, no icon-above-text stack, message `maxLines = 2` |

---

### 3.8 `LoadingBox` and `LoadingStrip`

```kotlin
// EXISTING, extended
@Composable
fun LoadingBox(
    modifier: Modifier = Modifier,
    label: String? = null,          // NEW
)

// NEW
@Composable
fun LoadingStrip(
    label: String,
    modifier: Modifier = Modifier,
    /** Null is indeterminate. */
    progress: Float? = null,
    onCancel: (() -> Unit)? = null,
)
```

`LoadingBox` keeps its 36 dp spinner and its `modifier`-first signature, so
`ReaderScreen.kt:698`'s `LoadingBox(modifier.height(placeholderHeight))` — which is load
bearing, see the comment at `ReaderScreen.kt:696-697` — is unaffected. The optional
`label` is for the cases that currently say nothing for several seconds, such as
`SourceBrowseScreen.kt:359`.

`LoadingStrip` is the inline form: a `LinearProgressIndicator` (determinate when
`progress` is given), the label at `bodySmall` + `onSurfaceVariant`, and an optional
Stop. It replaces the four bare `LinearProgressIndicator(fillMaxWidth())` calls, the
four determinate ones, the three small `CircularProgressIndicator`s at 14, 18 and 20 dp,
and both copies of the hand-drawn `ProgressBar`.

On the hand-drawn `ProgressBar`: the comment at `DiscoverCommon.kt:64-67` says
*"Drawn from two boxes rather than with `LinearProgressIndicator`, whose overload set in
this material3 build is ambiguous at the call site once every optional parameter is
omitted."* That is true and it is why the duplication exists. The ambiguity is avoided by
passing `progress` explicitly as a lambda, which is what `LibraryScreen.kt:372-375`,
`UpdatesScreen.kt:151` and `DownloadsScreen.kt:196` already do successfully. So
`LoadingStrip` uses the real indicator, calls it exactly once, and the two hand-drawn
copies go. If it turns out the ambiguity is not avoidable that way, the two-box drawing
moves inside `LoadingStrip` and stays there once instead of twice — **this must be
confirmed by compiling before the hand-drawn copies are deleted.**

| state | treatment |
|---|---|
| indeterminate | animated track, no percentage |
| determinate | `progress` lambda, plus "N of M" in the label as `LibraryScreen.kt:377` already does |
| cancellable | a `TextButton` "Stop" at the end |
| complete | the strip is removed by the caller; it has no success state of its own |

---

### 3.9 `ChipRow` — **new, and it cannot overflow off-screen**

This is the component the brief asks for by name. Fifteen chip containers exist across
three incompatible strategies, including seven with no overflow handling at all.

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    /** A label before the chips, e.g. "Chapters". */
    label: String? = null,
    /** Rows shown before collapsing behind the indicator. */
    collapsedRows: Int = 1,
    content: @Composable FlowRowScope.() -> Unit,
)
```

Implemented with `FlowRow(maxLines = ..., overflow = FlowRowOverflow.expandOrCollapseIndicator(...))`.
Both are present in the foundation this build resolves
(`foundation-layout-desktop-1.9.3`, `FlowLayoutKt.FlowRow` with the `maxLines` and
`FlowRowOverflow` parameters, and `FlowRowOverflow.Companion.expandOrCollapseIndicator`),
and `ExperimentalLayoutApi` is already opted into at `GlobalSearchScreen.kt:58` and in
`FilterPanel.kt`, so no compiler flag and no dependency is added.

Why this and not the two strategies already in the tree:

- **`horizontalScroll` (`LibraryScreen.kt:465`, `DetailsScreen.kt:153`,
  `CurateScreen.kt:405`, `:438`, `:526`)** clips with no affordance. In
  `shot-library-small.png` the Chapters row ends mid-word at "100 o" with nothing to
  indicate that "100 or more" exists or that the row can be dragged. Chips are a filter
  set; a filter you cannot see is a filter you do not use.
- **Nothing at all (`LibraryScreen.kt:492`, `SettingsScreen.kt:267`, `:300`,
  `TitlePrefsEditor.kt:54`, `TrackingLinkWidget.kt:173`, `:228`)** clips with no
  affordance *and* no way to reach the clipped chips. `CategoryChips` is the worst of
  these: it is a plain `Row`, so a user with twelve categories cannot select the last
  three by any means. `TitlePrefsEditor.kt:61` is next, because scanlator branch lists
  are long and caller-supplied.
- **`FlowRow` (5 sites)** is correct and is what this generalises, adding the collapse so
  a 40-item chip set does not eat the screen.

Geometry: `spacedBy(Space.sm)` on both axes — the `FlowRow` sites currently use 6 dp and
the `Row` sites 8 dp, and they become one value. `label` at `labelLarge` +
`onSurfaceVariant`, aligned to the first row's centre. Padding `Space.lg` horizontal,
`Space.xs` vertical.

The expand indicator is an `AssistChip` reading "+N more" / "Show less", so it is the
same height and shape as its neighbours and lands in the tab order with them.

| state | treatment |
|---|---|
| collapsed | `collapsedRows` rows plus the "+N more" chip |
| expanded | every chip, plus "Show less" |
| fits in `collapsedRows` | no indicator chip at all |
| chip selected | Material's `FilterChip` selected colours: `secondaryContainer` / `onSecondaryContainer`, 7.25:1 dark, 13.21:1 light |
| chip hover / pressed | Material's own `FilterChip` states |
| chip focus | 2 dp `primary` ring on the chip |
| chip disabled | standard 38% `onSurface`; still focusable so a keyboard user can discover why |

---

### 3.10 `AppNavRail` and `NavRailItem` — **new, replacing `NavRail` in `Main.kt`**

```kotlin
data class NavDestination(
    val key: Any,                  // Screen.Root or Screen.FeatureRoot
    val label: String,
    /** Material Symbols name; the filled variant is used when selected. */
    val icon: String,
    val group: NavGroup,
    val badge: Int = 0,
    /** A dot instead of a number, for Settings' summed tools badge. */
    val badgeAsDot: Boolean = false,
)

enum class NavGroup { Read, Find, KeepUp, System }

@Composable
fun AppNavRail(
    destinations: List<NavDestination>,
    selectedKey: Any,
    onSelect: (NavDestination) -> Unit,
    modifier: Modifier = Modifier,
)
```

Two structural fixes over `Main.kt:157-216`.

**It can scroll, so it cannot clip.** Today `NavigationRail { ... }` lays its children
out in a `Column` with no scroll container, and section 0 measures the result: eleven
destinations at a 61 dp pitch need 1019 px against an 810 px window, so two are
unreachable, and at UI scale 2.0 five are. The fix is one line inside the rail's content:
the items go in a `Column(Modifier.weight(1f).verticalScroll(rememberScrollState()))`.
`NavigationRail`'s content lambda is `ColumnScope`-receivered, so `weight(1f)` is
available and the scrolling region takes whatever height is left.

**It is grouped.** Eleven undifferentiated slots is a list to read, which is the exact
failure D26 was written about at eighteen. Grouping does not move anything across the
D26 line — it is still content on the rail and tools in Settings — it just gives the eye
somewhere to land:

| group | destinations | why |
|---|---|---|
| Read | Library, History, Bookmarks, Local, Downloads | things you already have |
| Find | Sources, Discover, For you | things you do not have yet |
| Keep up | Updates, App update | things that changed without you |
| System | Settings | pinned to the bottom, below the scrolling region, so it is always on screen |

Groups are separated by a `HorizontalDivider` in `outlineVariant` and 8 dp of space, with
no group headings: four labels would cost more height than the grouping saves, and the
division is legible from the gap alone. **The group labels still exist for screen
readers**, as a `Modifier.semantics { heading() }` on an invisible node before each
group, so the structure is announced even though it is not drawn.

Settings outside the scrolling region is deliberate. It is the only destination that is
also the way to the seven tools, and a destination you have to scroll to find is the
problem being fixed.

Rail container: `surfaceContainer` (see 1.10), `VerticalDivider` at `outlineVariant` on
its trailing edge, kept from `Main.kt:109`.

`NavRailItem` states:

| state | treatment |
|---|---|
| default | outlined icon in `onSurfaceVariant`, label `labelMedium` in `onSurfaceVariant` |
| selected | filled icon in `onSecondaryContainer`; `secondaryContainer` pill; **1 dp `outline` stroke on the pill** (5.17:1 dark, 3.84:1 light — see 1.6); label `labelLargeEmphasized` in `onSecondaryContainer` |
| hover | 8% `onSurface` overlay on the pill area |
| focus | 2 dp `primary` ring around the whole item, label included, not just the icon |
| pressed | 12% overlay |
| badged (count) | Material `Badge` with the number, capped in text at "99+" exactly as `Main.kt:205` already does so the value cannot widen the rail |
| badged (dot) | bare `Badge()`, for Settings, exactly as `Main.kt:178` |
| disabled | does not occur; a destination is never disabled |

`Main.kt`'s `ROOTS` list and the two `for` loops are replaced by one list of
`NavDestination`, built from the four shell roots plus
`state.features.filter { it.isTopLevel }`. `Feature` gains **one** new member:

```kotlin
/** Material Symbols name for the navigation rail or the Settings tool row. */
val icon: String
```

`glyph: String` is **removed** from `Feature`, not deprecated alongside `icon`. Leaving
both means a new area can still ship a glyph and nobody notices, which is precisely how
the rail reached eighteen destinations in the first place. This is a contract change and
belongs to whoever owns `FeatureContext.kt` — see the work packages in section 7.

`NavigationLayoutTest` keeps passing unchanged: nothing moves between the rail and
Settings. It gains one case — every `Feature.icon` resolves to a vendored file — which is
the same build-time guard described in 2.6.

---

## 4. Per-screen layout

Five screens get a layout rework. Everything else adopts the tokens and the components
in section 3 and keeps its layout.

API note for all five: `PrimaryTabRow`, `ButtonGroup`, `DropdownMenuItem`,
`IconToggleButton` and `FilledIconButton` were each confirmed present in
`material3-desktop-1.12.0-alpha03`, and `FlowRow(maxLines, overflow)` plus
`FlowRowOverflow.expandOrCollapseIndicator` in `foundation-layout-desktop-1.9.3`, by
reading the jars this build actually resolves. Nothing below needs a dependency.

---

### 4.1 Library

#### Today

```
┌──────────────────────────────────────────────────────────────┐
│ Library                                     [ New category ] │  TopBar, filled Button
│ 359 titles                                                   │
├──────────────────────────────────────────────────────────────┤
│ [All][Read later][Long manwhas][Marinate][Chapter (wait)]... │  plain Row — CANNOT SCROLL
├──────────────────────────────────────────────────────────────┤
│ Chapters [Any length][Not loaded][1 to 25]...[100 o▌         │  horizontalScroll — CLIPPED
├──────────────────────────────────────────────────────────────┤
│ ⚠ 12 titles here are on a source that cannot be opened  Fix  │  optional bar 1
├──────────────────────────────────────────────────────────────┤
│ 82 here have no chapter count yet.          [ Load counts ]  │  optional bar 2
├──────────────────────────────────────────────────────────────┤
│ Move these 23 to…                                            │  optional bar 3
├──────────────────────────────────────────────────────────────┤
│ <notice text>                                                │  optional bar 4
├──────────────────────────────────────────────────────────────┤
│ grid, 4 columns                                              │
```

Measured in `shot-library-small.png`: the grid starts 315 physical px down a 810 px
window, with only three of the six possible bars present. That is 210 dp of chrome, 26%
of the window, before one cover.

#### Proposed

```
┌──────────────────────────────────────────────────────────────┐
│ ←nothing   Library                             [+ New]       │  TopBar, 64 dp
│            359 titles                           TextButton   │
├──────────────────────────────────────────────────────────────┤
│ [All][Read later][Long manwhas][Marinate][+2 more] │ ⌸ Chapters: Any ▾ │ ⇅ │  56 dp
│  ChipRow, weight(1f), wraps and collapses          │  pinned, never wraps │
├──────────────────────────────────────────────────────────────┤
│ ⚠ 12 titles are on a source that cannot be opened   Fix   ✕  │  NoticeBar, 0..2, 48 dp each
├──────────────────────────────────────────────────────────────┤
│ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐                      │
│ │ cover │ │ cover │ │ cover │ │ cover │  ← 4 columns          │
│ │  NEW  │ │       │ │       │ │       │     badge on cover    │
│ └───────┘ └───────┘ └───────┘ └───────┘                      │
│  Title on  Title     Title     Title                         │
│  two lines                                                   │
│  8 chapters                                                  │
```

#### What moves, what is cut, and why

**The category row and the length filter share one row.** This is the single biggest
height win: 56 dp instead of 112 dp, so the grid starts at 120 dp typical instead of
210 dp. 90 dp recovered is 42% of a 214 dp cover row.

**The seven length chips become one menu button.** `ChapterFilter` is single-select
(`LibraryScreen.kt:65`, `selected: ChapterFilter`), and a single-select of seven is a
menu, not a chip set. The button reads `Chapters: Any length ▾` — it shows the active
value, which seven chips make you scan for, and it takes about 170 dp instead of a whole
row. The menu is a `DropdownMenu`, so it clamps to the window and cannot clip the way
the chip row does today. D24's deliberately-overlapping `HundredPlus` bucket survives and
is better off: `"100 or more"` has room for its full label in a menu, where today it is
the item cut mid-word at the window edge.

**The categories stay chips.** They are user-created, multi-valued in the sense that
identity matters, and you switch between them constantly. They go in a `ChipRow`, which
wraps to a second row and then collapses behind `+N more`. `CategoryChips`
(`LibraryScreen.kt:492`) is a plain `Row` today with no scroll at all, so a user with
twelve categories cannot reach the last three by any means. That is the most clear-cut
defect in the screen.

**The Edit affordance moves.** `LibraryScreen.kt:509-513` appends an `AssistChip`
labelled "Edit" to the chip row when a category is selected, which makes the row's
length depend on selection and puts a verb in a row of nouns. It becomes a long-press /
right-click on the selected category chip and an item in the `⇅` overflow, with
`ic_edit`.

**The refile offer moves into the filter row.** `RefileBar` (`LibraryScreen.kt:411`) is
shown only when a length filter is active, which makes it a property of the filter, not
a notice. It becomes a trailing `AssistChip` in the same row: `Chapters: 101 to 500 ▾
[Move these 23 to…]`. One fewer stacked bar.

**At most two notice bars, with a stated order.** `NoticeBar(tone, text, action,
onDismiss)` — a single component with `Info` and `Problem` tones (`surfaceContainer` /
`errorContainer`), 48 dp min height, `ic_warning` or `ic_info`, and a `ic_close`. Order:

1. a run in flight (chapter counts loading) — `LoadingStrip`, outranks everything
2. a result to acknowledge (`Loaded 37 counts, 4 could not be reached`)
3. a problem (`12 titles are on a source that cannot be opened`)
4. an offer (`82 here have no chapter count yet` + `Load counts`)

3 and 4 can coexist; 1 and 2 are mutually exclusive and replace 4. So the worst case is
two bars, not four, and the `notice` free-text at `LibraryScreen.kt:123-129` — which has
no dismiss, no icon and no container — becomes case 2 of this component.

**`⚠` is drawn, not implied.** `UnopenableBar` (`LibraryScreen.kt:342-350`) signals a
problem with `colorScheme.error` alone, which is a colour-only cue. `ic_warning` goes
beside it.

**"New category" stops being the loudest control on the screen.** It is a filled
`Button` at `LibraryScreen.kt:94`, which in `shot-library-small.png` is the single
highest-contrast element in the window. Filing is not what you came to the Library to
do. It becomes a `TextButton` with `ic_add`. There is then **no filled button on the
Library at all**, which is correct: the primary action is clicking a cover.

#### Grid, exactly

- `GridCells.Adaptive(minSize = 150.dp)` — **unchanged**. `RemoteImage` rounds its
  decode target up to a 128 px step (`Common.kt:115-122`) and D37 measured the cover
  budget against that bucketing. At 150 dp minimum and a 767 dp content width the grid
  resolves to 4 columns of 175 dp, which at UI scale 1.5 is a 384 px decode. Changing
  `minSize` moves the bucket and invalidates a measurement that was expensive to get, for
  no gain the chrome reclamation does not already give.
- `contentPadding = PaddingValues(Space.md)`; both arrangements `spacedBy(Space.md)`.
- Cover `aspectRatio(COVER_ASPECT)`, `clip(shapes.medium)` — 12 dp, up from the current
  literal 10 dp.
- Title `bodyMedium`, 2 lines, ellipsised — up from `bodySmall`.
- Secondary: `"8 chapters"` or `"chapters not loaded"`, `labelSmall` + `onSurfaceVariant`.
- Badge: `CoverBadge("New", New)` on the cover's top-end corner, replacing the third text
  line at `LibraryScreen.kt:585-591`. A card can currently stack title (2 lines) +
  chapters + "new chapters" + a source problem = five lines under a cover, which is why
  the rows look ragged in the capture. Two of those five move onto the cover.
- Problem: the source-health line, `error` + `ic_warning`, via `CoverCard(problem = ...)`.
  The "always clickable" behaviour and its comment (`LibraryScreen.kt:552-556`) are kept.

**The single primary action is a cover.** Nothing else on the screen is a filled button.

---

### 4.2 Sources (catalogue)

#### Today

```
┌──────────────────────────────────────────────────────────────┐
│ Sources                                                      │
│ 658 of 658 shown, 890 available                              │
├──────────────────────────────────────────────────────────────┤
│ [ Filter by name or language code ..........................]│
├──────────────────────────────────────────────────────────────┤
│ 3Asq                                                         │  ← 658 of these,
│ manga  ar                                                    │    one per row,
├──────────────────────────────────────────────────────────────┤    full width,
│ 4uScans                                                      │    right 60% empty
│ manga  ar                                                    │
```

`shot-sources-s.png`: a two-line row carrying a name and seven characters of metadata,
stretched across 767 dp. 658 rows at roughly 78 dp each is about 51,000 dp of scrolling.

#### Proposed

```
┌──────────────────────────────────────────────────────────────┐
│ Sources                                                      │
│ 658 of 658 shown, 890 in the catalogue                       │
├──────────────────────────────────────────────────────────────┤
│ [ ⌕ Filter by name or language code .......................] │
├──────────────────────────────────────────────────────────────┤
│  A                                                     (12)  │  ← sticky SectionHeader,
│  ┌──────────────┬──────────────┬──────────────┐               │    full-span grid item
│  │ 3Asq         │ 4uScans      │ AdonisFansub │               │
│  │ manga · ar   │ manga · ar   │ manga · tr   │  ← 3 columns  │
│  ├──────────────┼──────────────┼──────────────┤               │
│  │ AduManga     │ AinzScans    │ AloneScanlat…│               │
│  │ manga · tr   │ manga · id   │ manga · pt   │               │
│  └──────────────┴──────────────┴──────────────┘               │
│  B                                                     (31)  │
```

#### What changes and why

**One column becomes three.** `LazyColumn` → `LazyVerticalGrid(GridCells.Adaptive(minSize
= 240.dp))`. At 767 dp content width that is 3 columns; at UI scale 2.0 in the same
window it is 1 and the layout degrades to exactly what exists today rather than breaking.
658 rows become about 220, cutting the scroll distance by two thirds. No new data, no
new feature — the same two lines of text, laid out to use the window.

**Alphabetical sticky headers.** `item(span = { GridItemSpan(maxLineSpan) })` carrying a
`SectionHeader(letter, count = n)`. The key is `source.title.first().uppercaseChar()`,
which is data the screen already has. In an alphabetically sorted list of 658 this is the
difference between scrolling and aiming.

**The filter field gets a leading `ic_search` and a trailing `ic_close` when non-empty.**
Today there is no way to clear it but select-all-delete.

**The subtitle wording.** `CatalogScreen.kt:54-59` says `"658 of 658 shown, 890
available"`, which reads as though 232 sources are being withheld by a setting. Per D18
the 890 is the *usable* count and the hidden 380 are `isBroken`. It becomes
`"N of M shown, M in the catalogue"` when nothing is filtered, matching the phrasing
`DiscoverFeature.kt:305` already uses for the same number, so the two screens stop
disagreeing.

**Each cell is an `AppListRow`** with no `leading` (sources have no image), headline
`bodyLarge`, `meta = listOf(contentType, locale)`. The middle dot is joined by the
component, which ends the `"manga  ar"` double-space at `CatalogScreen.kt:99-101`.

#### Not proposed, deliberately

A content-type or language chip filter would help a 658-item list a lot, and the data for
it exists on `MangaParserSource`. It is **out of scope as a new feature** and is not
specified here. Flagged in section 8 as a question rather than smuggled in.

A "sources you already read" section at the top is the same situation: the computation
already exists at `DiscoverFeature.kt:267-271`, but wiring it into the catalogue is new
behaviour. Also in section 8.

#### One observation for whoever implements this

`CatalogScreen.kt:43` is `remember(query) { ... all ... }` where `all` is the `sources`
parameter. The key does not include `all`, so a change to the source list while this
composable is alive would not re-filter. It is not reachable today because `selectRoot`
clears the stack and recreates the screen, but it is a trap for anyone who later makes
the catalogue a non-root destination. Not a design item; noted because the file is being
touched anyway.

---

### 4.3 Details

#### Today

```
┌──────────────────────────────────────────────────────────────┐
│ [←] Crimson Yaksha                                           │  ← title, 1st time
│     MangaBat                                                 │
├──────────────────────────────────────────────────────────────┤
│ ( Read ) ( In library (1) ) ( Download ) ( Other sources )   │  ← 4-5 near-equal buttons
├──────────────────────────────────────────────────────────────┤
│ ┌────────┐ Crimson Yaksha                                    │  ← title, 2nd time
│ │        │ ongoing · rating 10.0                             │
│ │ cover  │ Manhwa, Action, Revenge, Fantasy                  │
│ │180x260 │ Read Crimson Yaksha The school-bullying perpet…   │  ← 95-char measure
│ └────────┘                                                   │
├──────────────────────────────────────────────────────────────┤
│ Related titles                                               │
├──────────────────────────────────────────────────────────────┤
│ 6 chapters                                                   │
│ Chapter 1                                                    │
```

#### Proposed

```
┌──────────────────────────────────────────────────────────────┐
│ [←] Crimson Yaksha                                           │  TopBar, sticky, the only
│     MangaBat                                                 │  place the title appears
├──────────────────────────────────────────────────────────────┤
│ [ ▶ Continue ]   ( In library (1) )                    [ ⋮ ] │  sticky action row:
│   filled          outlined                              menu │  one primary, one
├──────────────────────────────────────────────────────────────┤  secondary, rest in ⋮
│ ┌─────────┐  ongoing · rating 10.0 · 6 chapters              │
│ │         │  Manhwa, Action, Revenge, Fantasy                │  ← scrolls
│ │  cover  │                                                  │
│ │ 200×286 │  Read Crimson Yaksha The school-bullying         │
│ │         │  perpetrators died before he could even          │
│ └─────────┘  avenge his dead younger sister. Trash…          │  ← widthIn(max = 640.dp)
│                                               [ Show more ]  │
├──────────────────────────────────────────────────────────────┤
│ Related titles                                          (8)  │  SectionHeader
│ [strip]                                                      │
├──────────────────────────────────────────────────────────────┤
│ 6 chapters                                                   │  SectionHeader
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ ✓ Chapter 1                                              │ │  AppListRow, selected
│ │   Lupi Team · saved                                      │ │
│ ├──────────────────────────────────────────────────────────┤ │
│ │   Chapter 2                                              │ │
```

#### What is cut and why

**The second title.** `DetailsScreen.kt:353` draws `manga.title` at `headlineSmall`
inside `Header`, 24 dp below the same string at `titleLarge` in the `TopBar`
(`DetailsScreen.kt:142`). The **header** copy goes, not the bar copy, because the bar is
outside the `LazyColumn` (it is in the outer `Column` at `:140-145`, the list starts at
`:214`) and therefore stays on screen while you scroll a 105-chapter list. The copy that
scrolls away is the one worth less. The header's first line becomes the facts row, so the
block still has a strong opening and loses a whole line of duplication.

**Three of the five buttons.** Today Continue, Start over, In library, Download and Other
sources are all in one `horizontalScroll` row and four of the five are `OutlinedButton`,
so the row reads as five equal options.

| action | after | why |
|---|---|---|
| Continue / Read | filled `Button` + `ic_play_arrow` | **the single primary action.** It is why the screen was opened |
| In library (N) | `OutlinedButton` | the second most used, and it carries state you want visible without opening anything |
| Start over | `⋮` menu | rare, and destructive-adjacent — it discards your position |
| Download | `⋮` menu | deliberate, never urgent |
| Other sources | `⋮` menu | a repair path |

**On D27 and "Other sources".** D27 says: *"Offered whenever the details screen is open,
not only when the list is empty. A source that returns a stale or truncated chapter list
looks perfectly healthy and is the same problem."* The requirement is *offered whenever
the screen is open*, and an overflow menu that is always present satisfies it — the
action moves one click deeper, it does not become conditional. The two places where it
is **promoted out of the menu** are unchanged: the `ErrorBox` `extra` slot at
`DetailsScreen.kt:209` when the title will not load, and the filled `Button` at
`DetailsScreen.kt:238` when the source returns no chapters. Those are the states D27 and
D28 were actually written about, and in both of them it stays a first-class control. The
hiding logic at `DetailsScreen.kt:185-188` that avoids showing it twice on one screen
stays exactly as it is.

**The description's measure.** `widthIn(max = 640.dp)` and `maxLines = 6` with a
`Show more` `TextButton`, down from the current `maxLines = 10` at full window width
(`DetailsScreen.kt:383-384`). At 1270 px that is 95 characters per line, about 20 past
comfortable. The HTML-stripping reduction and its comment (`:378-380`, deferring a real
renderer to D2) are untouched.

#### Geometry

- Action row: fixed, under the `TopBar`, outside the `LazyColumn`. It stays where
  `DetailsScreen.kt:146-149` put it and for the reason recorded there — *"In the bar they
  competed with the title for width, and at a larger interface scale in a narrow window
  they lost and vanished entirely"*. Two buttons plus an icon need about 330 dp, so the
  `horizontalScroll` is no longer load bearing and is removed; the row shrinks the gap
  instead of scrolling.
- Header: `Row(spacedBy(Space.lg))`, `padding(Space.lg)`.
- Cover: `Modifier.width(200.dp).aspectRatio(COVER_ASPECT).clip(shapes.medium)` = 200×286.
  Today it is `width(180.dp).height(260.dp)` (`DetailsScreen.kt:350`), aspect 0.692 — close
  to `COVER_ASPECT` but not equal, so a cover is very slightly squeezed here and nowhere
  else.
- Facts row: `labelLarge` + `onSurfaceVariant`, joined by the component's middle dot.
  Gains the chapter count, so "6 chapters" is visible without scrolling to the section
  header.
- Tags: a `ChipRow` of non-interactive `AssistChip`s rather than a comma-joined string
  (`DetailsScreen.kt:371`). Chips cannot run off the edge; the string can, and tag lists
  are long.
- Chapter rows: `AppListRow(headline, meta, selected = isCurrent)`. The current-chapter
  marker today is `colorScheme.primary` **text colour alone** (`DetailsScreen.kt:483-487`)
  — colour-only, and invisible to anyone who cannot separate cyan from white. As a
  selected `AppListRow` it gets a `secondaryContainer` fill and a `check` icon.
- Download status (`saved`, `downloading 42%`, `queued`, `download failed`) stays in
  `meta`, with `download failed` routed to `problem` instead so it is coloured and
  iconned as a problem. The comment at `DetailsScreen.kt:494-496` on why this belongs
  next to the chapter is correct and is kept.
- `HorizontalDivider` after every chapter row (`DetailsScreen.kt:258`) is removed;
  `AppListRow`'s 64 dp minimum height and hover state carry the separation (see 1.6).
  The one divider that stays is the section break between the header block and the
  chapter list, and it moves from `outlineVariant` to `outline`.

---

### 4.4 Reader chrome

Designed against `r2s.png`, which shows the three problems together: the rail is still
there, `Back` is a filled pill, and the bottom bar is cut mid-word at "Zoom".

#### Position: the reader is immersive, and the rail is hidden

**It contradicts nothing recorded.** D26 is about rail *membership* — which areas earn a
slot — and the rail's own job: *"A root has a rail slot to click your way out of and
these no longer have one, so making one the root would strand the reader with nothing
highlighted and nothing to go back to."* The reader is **not a root**. It is pushed onto
the stack (`Main.kt:138`), so there is always something to go back to, and the rail
highlights whichever root the stack happens to have started from. Open a title from
Discover, start reading, and "Discover" stays lit while you read. That is not navigation
state; it is a lie about where you are.

**It costs the thing the app is for.** The rail is 80 dp, which at UI scale 1.5 is 120
physical px — 9.4% of a 1270 px window, subtracted from the reading surface on every page
of every chapter. The strip is already narrowed to a percentage of what is left
(`ReaderScreen.kt:578`), so the rail's width is taken before the reader's own width
setting is applied and compounds with it.

**Three ways out, none new.**

1. `Escape` — already wired at `ReaderScreen.kt:316`.
2. The back control in the top chrome, present whenever chrome is.
3. Chrome returns on any input, so (2) is never more than a mouse twitch away.

#### Chrome overlays the strip; it does not sit beside it

Structural change: `Column(bar, Box(weight(1f)), statusBar)` becomes
`Box { strip; topChrome.align(TopCenter); bottomChrome.align(BottomCenter) }`.

The comment at `ReaderScreen.kt:349-351` records why the current shape exists: *"weight(1f),
not the content's own fillMaxSize: inside a Column a child that fills takes every
remaining pixel, which left the status bar below it with zero height and therefore
invisible."* That constraint is real and is satisfied differently here — in a `Box`,
`align` places the bars over the strip and neither can starve the other of height. The
strip then gets the full window height, and chrome appearing or disappearing does not
reflow it, which matters: a reflow during a scroll would move the reader's position,
and D23's whole requirement is that the strip does not visibly move under them.

#### Auto-hide rules, exactly

- Visible on open.
- Hides 2500 ms after the last input event — pointer move, key down, scroll, or click.
- Shows on: any pointer move, any key down, a focus change, a page entering the failed
  state, or the strip footer coming into view.
- **Never** hides while the pointer is inside a chrome bar, while any chrome control has
  focus, or while the Display popup is open. A control that vanishes under the cursor is
  worse than one that is always there.
- Transition is alpha only, `motionScheme.fastEffectsSpec()`. No slide: a slide changes
  the bar's bounds and invites the reflow the `Box` was chosen to avoid.
- Hidden chrome is `alpha = 0f` **and** removed from the tab order, so Tab does not land
  on something invisible.

#### Top chrome

```
┌──────────────────────────────────────────────────────────────┐
│ [←]  Chapter 1                                        [ 🔖 ] │
│      Crimson Yaksha · MangaBat · 1 / 150                     │
└──────────────────────────────────────────────────────────────┘
```

- Back: `IconButton` + `AppIcon("arrow_back", "Leave the reader")`. **Not a filled
  `Button`.** `ReaderScreen.kt:429` makes leaving the screen the highest-contrast element
  on the reading screen, which is visible in `r2s.png` as a bright pill in the top-left
  corner of an otherwise black window.
- Title: chapter title at `bodyMedium` in `ReaderOnChrome` (18.55:1 over the page).
- Second line: title · source · position, `bodySmall` in `ReaderOnChromeVariant`
  (12.31:1 over the page), joined by the component's middle dot. This replaces the
  current three stacked `Text`s with their own conditional logic
  (`ReaderScreen.kt:431-449`).
- Bookmark: `IconToggleButton` with `ic_bookmark` / `ic_bookmark_fill`, tinted
  `ReaderAccent` when set, `contentDescription` `"Bookmark this page"` /
  `"Remove this bookmark"`. Replaces the `OutlinedButton` labelled `"☆ Bookmark"` /
  `"★ Saved"` (`BookmarksScreen.kt:247`) — an outlined button with a text label is a lot
  of furniture for a toggle, and the two unicode stars differ only in fill at
  small sizes.
- Fill `ReaderChrome`, 1 dp `ReaderChromeOutline` rule on the bottom edge.

**There is no filled button in the reader chrome at all.** The primary action in a reader
is reading.

#### Bottom chrome — the layout that cannot overflow

```
┌──────────────────────────────────────────────────────────────┐
│ Page 1 of 150    [‹] [›]                              [ ⚙ ]  │
└──────────────────────────────────────────────────────────────┘
```

Four elements and a flexible gap. Fixed width budget:

| element | dp |
|---|---|
| `Page 1 of 150`, `widthIn(min = 120.dp)`, ellipsises under pressure | 120 |
| `‹` previous chapter, `IconButton` | 48 |
| `›` next chapter, `IconButton` | 48 |
| `Spacer(Modifier.weight(1f))` | flexes to 0 |
| `⚙` Display, `IconButton` + `ic_tune` | 48 |
| 3 gaps at `Space.md` + 2 × `Space.lg` padding | 68 |
| **total fixed need** | **332** |

Against the measurement in section 0: the current inline bar needs about **1125 dp** and
has **767 dp**, which is why it is cut. 332 dp fits at UI scale 1.5 (767 dp available,
and more once the rail is gone), and at UI scale 2.0 in the same 1270 px window there are
635 dp. It also fits at UI scale 2.0 in an 800 px window (400 dp). Because the only
flexible element is a `Spacer(weight(1f))`, the degradation mode is "the gap closes", not
"the controls leave the window". **No breakpoint, no `horizontalScroll`, no hidden
content.** The `horizontalScroll` at `ReaderScreen.kt:760` and the comment justifying it
are removed, because there is nothing left to scroll.

#### The Display popup

`DropdownMenu` anchored to the `⚙` button, 280 dp wide, `shapes.medium`. A `Popup` is
clamped to the window by its position provider, so it cannot be cut off the edge the way
the inline bar is.

```
┌────────────────────────────────┐
│ Strip width                54% │
│ ●──────────────────────────    │
│                                │
│ Zoom                      100% │
│ ────●──────────────────────    │
│                      [ Reset ] │
└────────────────────────────────┘
```

Strip width keeps its `20f..100f` range and its persistence; zoom keeps `1f..6f` and
stays per-session. The reasoning at `ReaderScreen.kt:733-739` — that width and zoom
belong together because the real complaint is strip length, which only width fixes —
is the reason they are in one popup rather than two places. The `−` and `+` buttons at
`ReaderScreen.kt:810` and `:817` are dropped: a slider plus keyboard `+`/`-`
(`ReaderScreen.kt:310-312`, already wired) covers it, and the buttons existed to make
zoom discoverable, which the labelled popup now does.

Container `#42484B`. Contrast inside it: `ReaderOnChrome` 8.21:1, `ReaderOnChromeVariant`
5.45:1, `ReaderAccent` 5.40:1. **Slider tracks use `ReaderAccent` (active) and
`ReaderOnChromeVariant` (inactive), not `ReaderChromeOutline`**, which is only 2.92:1 on
this container and would miss the 3:1 non-text threshold. The popup's own boundary is a
1 dp `ReaderChromeOutline` border, which is read against the black page behind it at
6.60:1.

#### How the reader gets its colours

Not by substituting a token at each of the nine call sites. The reader's content is
wrapped once:

```kotlin
MaterialExpressiveTheme(
    colorScheme = DropSauceReaderScheme,   // derived from DropSauceDarkColors
    motionScheme = MaterialTheme.motionScheme,
) { /* strip + chrome */ }
```

where `DropSauceReaderScheme` is `DropSauceDarkColors.copy(surface = ReaderPage,
surfaceContainer = ReaderChrome, surfaceContainerHigh = #42484B, onSurface =
ReaderOnChrome, onSurfaceVariant = ReaderOnChromeVariant, outline = ReaderChromeOutline,
primary = ReaderAccent, ...)`.

Everything inside — `Slider`, `DropdownMenu`, `IconButton`, `ErrorBox`, `LoadingStrip` —
then picks up reader colours with no per-call-site colour code, in both app themes, and
`ErrorBox` works unmodified. `motionScheme` is passed through explicitly because nesting
a theme would otherwise reset it and silently lose `MotionScheme.expressive()` inside the
reader, which is exactly the kind of regression D6a exists to catch.

All eleven hardcoded colours at `ReaderScreen.kt:284, 433, 440, 447, 689, 755, 759, 771,
792, 802, 808` are deleted.

#### The fourth error treatment, folded in

`r2s.png` shows a page failure as centred body text plus a bare `Try again` text link
(`ReaderScreen.kt:680-695`) — a fourth distinct error shape beside `ErrorBox`, the
compact strips and `BackupScreen`'s card. Under the local theme it becomes:

```kotlin
ErrorBox(
    headline = "This page could not be loaded",
    message  = reason ?: "The source did not say why.",
    onRetry  = { retry++ },
    modifier = Modifier.height(placeholderHeight),
)
```

So it gains `ic_error`, a headline separate from the reason, and a real button instead of
a text link. The `placeholderHeight` is kept — the comment at `ReaderScreen.kt:663-671`
explains that reserving a screen's worth of height is what stops the list composing
fifteen pages at once, and that is load bearing.

The strip footer's four states (`ReaderScreen.kt:870-925`) keep their behaviour exactly
and change only their rendering: the append failure becomes `ErrorBox(compact = true)`,
the appending spinner becomes `LoadingStrip("Loading the next chapter")`, and the two
end-of-content messages keep their `bodySmall` + `onSurfaceVariant` in a centred box.
D23's rule — *"A chapter that fails to load is reported under the strip, not in place of
it"* — is why the footer stays a footer and why the compact variant is the right one.

#### What is not touched

Nothing in `WebtoonStrip`, `PageImage`, the `Semaphore`, the prefetch watcher, the
`snapshotFlow`, the default index keys, or the zoom transform. D22 (webtoon only) and D23
(one continuous strip) are the shape of this screen and nothing here changes them. No
paged mode, no reading direction, no tap zones.

---

### 4.5 Discover / global search

#### Today

```
┌──────────────────────────────────────────────────────────────┐
│ ( Search everywhere ) ( Browse a source )                    │  ← chips used as tabs,
├──────────────────────────────────────────────────────────────┤    no screen title
│ [ Search every selected source, then press Enter ] [Search] Sources (15) │
│ Recent [Murim Login] [Estate Developer] [weird]  Clear       │
├──────────────────────────────────────────────────────────────┤
│ Type a title. Every selected source is asked at once and…    │  ← top-left of a
│                                                              │    350 px void
│                                                              │
```

#### Proposed

```
┌──────────────────────────────────────────────────────────────┐
│ Discover                                                     │  TopBar, which this
│ 15 of 658 sources selected                                   │  screen has never had
├──────────────────────────────────────────────────────────────┤
│  Search everywhere  │  Browse a source                       │  PrimaryTabRow
│ ━━━━━━━━━━━━━━━━━━━ │                                        │
├──────────────────────────────────────────────────────────────┤
│ [ ⌕ Search every selected source ............] [Search] [⚙15]│
│ Recent: [Murim Login] [Estate Developer] [weird]       Clear │  ChipRow
├──────────────────────────────────────────────────────────────┤
│                                                              │
│                           (⌕)                                │
│                 Search every source at once                  │  EmptyBox, centred
│         Type a title and press Enter. 15 sources are         │
│       selected; each is asked at once and the answers        │
│                   arrive as they come.                       │
│                                                              │
│                    [ Choose sources ]                        │
│                                                              │
```

#### What changes and why

**A `TopBar`.** `DiscoverFeature.kt:86-103` is a chip row with no title, so Discover is
the one rail destination that does not say what it is when you arrive. Its subtitle
carries the number that decides what the search will do — how many sources are selected
— which today is buried in a `TextButton` label at the end of the search row.

**The mode chips become a `PrimaryTabRow`.** Two mutually exclusive modes that each own
the whole screen below them are tabs. As `FilterChip`s they sit in the same visual class
as the Recent query chips directly beneath them, which makes two completely different
kinds of control look identical — visible in `shot-discover-s.png`. `PrimaryTabRow` is
present in this material3 build. The comment at `DiscoverFeature.kt:133-137` — *"Two
modes rather than two destinations, because they are the same job at different breadths
and a user moves between them constantly"* — is the argument **for** tabs, not against
them; tabs are the Material control for exactly that.

**The source picker becomes a real control.** `Sources (15)` is a `TextButton`
(`GlobalSearchScreen.kt:108-110`) that toggles a 240 dp inline picker. It becomes an
`OutlinedButton` with `ic_tune` reading `15 sources`, and the picker opens as a modal
rather than pushing the results down the screen. Which sources are being searched is the
single most consequential setting on this screen; it should not look like a link.

**The void is filled with the next action.** The 350 px empty region below the controls
gets a centred `EmptyBox(icon = "search", headline = "Search every source at once",
detail = ..., action = { Button("Choose sources") })`. The current text
(`GlobalSearchScreen.kt:156-161`) is good copy rendered at the top-left of a blank window
because its `Box(Modifier.fillMaxSize())` has no `contentAlignment`.

**`No sources selected` becomes a `NoticeBar`.** `GlobalSearchScreen.kt:113-120` renders
it as `bodySmall` in `colorScheme.error` with no icon and no container — a colour-only
cue for the state in which the screen cannot work at all. As a `Problem`-toned
`NoticeBar` with `ic_warning` and a `Choose sources` action it is both visible and
actionable.

#### Results, which are mostly right already

The grouped-strip layout is kept. D38's ranking is what makes it work and the screen
surfaces it honestly — `matched`, `loose`, `failed` and `settled` are all reported
(`GlobalSearchScreen.kt:203-236`). Changes are cosmetic:

- The summary row becomes a `LoadingStrip(label = summary, progress = state.progress())`,
  which merges the hand-drawn `ProgressBar` at `:193`, the 14 dp spinner at `:202` and
  the summary text at `:203` into one component instead of three stacked things.
- Group headers become `SectionHeader(group.title, count = items.size, trailing = …)`
  with `ic_expand_more` / `ic_chevron_right` replacing the literal `"▾"` / `"▸"`
  (`:345`), and a `Modifier.semantics { heading() }` so the groups are navigable.
- `Show N unrelated` / `Show failures` stay as `TextButton`s in the summary row. They are
  the right affordance and the copy is already precise about what is being withheld.
- The failure list at `:239-261` becomes a stack of `ErrorBox(compact = true)` rows
  inside the same `heightIn(max = 140.dp)` scroller, so a failed source reads the same
  here as anywhere else.
- `Still asking N more` (`:312-317`) stays, and stays at the bottom of the list.
- The strip cells use the shared `CoverCard` at `ThumbSize.strip` (132 dp), which is
  already what they do — the only change is that `STRIP_ITEM_WIDTH` is declared once.

#### Browse-a-source mode

Only two changes, since `SourceBrowseScreen` is in reasonable shape:

- `Filters` / `Filters (3)` gets `ic_filter_list` (`SourceBrowseScreen.kt:201`).
- `Could not load more: …` (`:390-405`) becomes `ErrorBox(compact = true)`.

The filter sidebar (`FilterPanel.kt`) already uses `FlowRow` throughout and keeps its
layout; it adopts `Space.sm` for its chip gaps, up from 6 dp, so a chip row looks the
same here as in the Library.

---

## 5. The other 24 screens: tokens and shared states only

No layout changes. Each screen keeps its structure and swaps its hand-rolled states for
the components in section 3. The table below is the full migration list, so nobody has to
go looking.

### 5.1 Empty states — 7 helpers plus 9 inline one-offs become `EmptyBox`

| from | to |
|---|---|
| `EmptyState` (`CurateScreen.kt:773`), 3 call sites `:276`, `:281`, `:678` | `EmptyBox(headline, detail)` |
| `Empty` (`LocalxScreen.kt:608`), 1 call site `:153` | `EmptyBox` |
| `EmptyLocalLibrary` (`LocalScreen.kt:195`), 1 call site `:119` | `EmptyBox` |
| `EmptyNote` (`BookmarksScreen.kt:333`), 1 call site `:144` | `EmptyBox`, gains a headline/detail split |
| `EmptyNote` (`SyncUi.kt:158`), 1 call site `UpdatesScreen.kt:174` | `EmptyBox` + an action button for "Track my library" |
| `EmptyNote` (`SuggestionsScreen.kt:182`) | `EmptyBox`; its `Working out what you like.` and `Asking the sources.` phases are **loading**, not empty, and become `LoadingBox(label = …)` |
| `TrackingEmptyNote` (`TrackingUi.kt:61`) | **deleted** — zero call sites |
| `BrowseScreen.kt:216`, `:224` | `EmptyBox`; fixes the missing `contentAlignment` |
| `GlobalSearchScreen.kt:155`, `:270`, `:279` | `EmptyBox`; same fix |
| `SourceBrowseScreen.kt:366`, `:378` | `EmptyBox`; same fix |
| `BrokenSourcesScreen.kt:163` `"Nothing to fix."` | `EmptyBox(headline, icon = "check_circle")` |
| `MigrationScreen.kt:120` `"No other source has this title."` | `EmptyBox(icon = "search")` — **it is rendered through `ErrorBox` today and nothing failed** |
| `TrackingLinkWidget.kt:247` `"No matches…"` | `EmptyBox(compact placement inside the card)` |
| `RelatedTitles.kt:211-218` | stays suppressed by default (`showExplanationWhenEmpty = false`); when shown, `EmptyBox` |
| `StatsScreen.kt:72` — a deliberately blank `Box` while loading | `LoadingBox()`; the comment says the blank is intentional to avoid a flash, so this one keeps the blank for the first 300 ms and then shows the box |

Five screens currently render their empty text at the **top-left corner** of a
full-height box because their `Box(Modifier.fillMaxSize())` has no `contentAlignment`.
`EmptyBox` centres, which fixes all five at once.

### 5.2 Error states — 11 inline treatments become `ErrorBox`

| from | to |
|---|---|
| `SourceBrowseScreen.kt:390-405` "Could not load more" | `ErrorBox(compact = true)` |
| `BookmarksScreen.kt:135-141` screen-level failure strip | `ErrorBox(compact = true)` |
| `UpdatesScreen.kt:165-169` "The run stopped: …" | `ErrorBox(compact = true)` — **currently has no colour role at all** |
| `UpdatesScreen.kt:261-278` per-row errors | `AppListRow(problem = …)` |
| `BackupScreen.kt:203-220` the bespoke error `Card` | `ErrorBox(headline = "That did not work", message, extra = { Text("Nothing was changed.") })` |
| `DownloadsScreen.kt:184-194` conditional error colour | `AppListRow(problem = …)` |
| `MigrationScreen.kt:119` | `ErrorBox` — already correct, keeps its Cancel sibling |
| `BrokenSourcesScreen.kt:232-243` outcome line | success stays `primary`; failure becomes `ErrorBox(compact = true)` |
| `TrackingNote(isError = true)` (`TrackingUi.kt:75`), 4 call sites | `ErrorBox(compact = true)` |
| `SuggestionsScreen.kt:163` "The refresh could not run" | `ErrorBox(compact = true)` — **currently in `onSurfaceVariant`, so a hard failure reads as a hint** |
| `LocalScreen.kt:169-192` import summary | success keeps `onSurfaceVariant`; failures become `ErrorBox(compact = true)` — **currently failures are `onSurfaceVariant` on a `surfaceVariant` background** |
| `LocalxScreen.kt:193-200`, `:344-351`, `:425-459`, `:519` | `ErrorBox(compact = true)`; `:452-459` currently renders "This chapter could not be read" **as the chapter's body text**, in no error colour |
| `AppUpdateScreen.kt:155-161` `browse()` swallows its failure with no UI | a `NoticeBar(Problem)` saying the browser could not be opened, with the URL as selectable text |

### 5.3 Loading states

| from | to |
|---|---|
| 4 × bare `LinearProgressIndicator(fillMaxWidth())` (`LocalxScreen.kt:137`, `LocalScreen.kt:113`, `MigrationScreen.kt:107`, `BrokenSourcesScreen.kt:152`) | `LoadingStrip(label)` — each currently shows motion with no explanation of what is moving |
| 4 × determinate (`UpdatesScreen.kt:151`, `BackupScreen.kt:170`, `DownloadsScreen.kt:196`, `LibraryScreen.kt:372`) | `LoadingStrip(label, progress)` |
| `CircularProgressIndicator` at 14, 18, 20 dp (`GlobalSearchScreen.kt:202`, `SourceBrowseScreen.kt:350`, `TrackingLinkWidget.kt:103`, `TrackingServicesScreen.kt:169`, `BookmarksScreen.kt:325`) | `LoadingStrip` where it labels a region; a 20 dp indicator stays where it replaces a button in place |
| `ProgressBar` (`DiscoverCommon.kt:70` **and** `SuggestionsScreen.kt:282`, verbatim duplicates) | `LoadingStrip`; see 3.8 on the overload ambiguity that produced them |
| `AppUpdateScreen.kt:72-76` a sentence instead of an indicator | `LoadingStrip("Checking for a newer version")` |
| `RelatedTitles.kt:209` `Loading -> Unit` | `LoadingStrip`, inside the strip's own height so the section does not jump |
| `StatsScreen.kt:229-236` `LinearProgressIndicator` used as a **data bar** | **left alone.** It is a chart, not a spinner, and `LoadingStrip` would be wrong |

### 5.4 Headers

`FeatureHeader` (`SyncUi.kt:132`) and `TrackingHeader` (`TrackingUi.kt:36`) are deleted;
their 3 call sites use `TopBar`. The 4 ad-hoc headers — `SuggestionsToolbar`
(`SuggestionsScreen.kt:108`), the bare `Text("Reader")` at `ReaderExtrasFeature.kt:79`,
the title-less chip row at `DiscoverFeature.kt:86`, and the three `readerx` section rows
— become `TopBar` or `SectionHeader` as appropriate.

### 5.5 Chip rows

The seven containers with **no overflow handling at all** are the priority, because their
chips are not merely clipped, they are unreachable:

| site | chips | risk |
|---|---|---|
| `LibraryScreen.kt:492` `CategoryChips` | 1 + user categories, unbounded | **highest** — handled in 4.1 |
| `TitlePrefsEditor.kt:54` | 1 + scanlator branches, caller-supplied, unbounded | **high** — branch lists are long |
| `SettingsScreen.kt:300` `ChoiceRow` | one per enum entry, generic signature | medium |
| `SettingsScreen.kt:267` `ScaleRow` | 6, fixed | medium — and it is the UI-scale control itself, so it clips worst at the setting that causes clipping |
| `TrackingLinkWidget.kt:173` | one per expressible status | low |
| `TrackingLinkWidget.kt:228` | 2 + a `Button` sharing the row | low |
| `DiscoverFeature.kt:91` | 2, fixed | low — becomes a `PrimaryTabRow` (4.5) |

All become `ChipRow`. The three `horizontalScroll` sites in `CurateScreen.kt`
(`:405`, `:438`, `:526`) also become `ChipRow`, and the five existing `FlowRow` sites
change only their gap, 6 dp to `Space.sm`.

### 5.6 List rows and grid cells

Six row implementations become `AppListRow`; five grid implementations become
`CoverCard`. Concretely this collapses five thumbnail sizes (36×50, 44×62, 48×68, 52×74,
56×76) to one `ThumbSize.list` of 48×68, four corner radii (4, 6, 10, 12) to two
(`shapes.small`, `shapes.medium`), and three `COVER_ASPECT` constants to one.

`LocalxScreen.kt:543` is the one to check first: its thumbnail is
`widthIn(max = 48.dp)` with **no height and no aspect ratio**, under a `ContentScale.Crop`
with no size modifier at `:565`, so its rows are whatever height the decoded image
happens to make them.

---

## 6. Accessibility annotations

### 6.1 Focus-visible

Compose on desktop draws no focus indication by default, so keyboard-only operation is
currently invisible everywhere. One shared modifier:

```kotlin
/** A 2 dp primary ring, drawn only when focus arrived from the keyboard. */
fun Modifier.focusRing(shape: Shape = CircleShape): Modifier
```

2 dp outside the component bounds in `primary`, at the component's own shape. Measured
against every surface it can land on: **10.79:1** (dark `surface`), **9.55:1** (dark
card), **8.33:1** (dark container-high), **6.17:1** (light `surface`), **5.59:1** (light
card), **5.29:1** (light container-high), **7.14:1** (reader chrome). All clear the 3:1
non-text threshold with room.

It is applied by `AppListRow`, `CoverCard`, `NavRailItem`, `ChipRow`'s children,
`TopBar`'s back button and every `IconButton`. The ring is drawn **around the whole
focusable**, including its label — not around the icon alone, which is the failure mode
in a rail where icon and label are separate children.

Focus never moves without the target being scrolled into view
(`Modifier.bringIntoViewRequester`), which matters most in the Sources grid and the
105-row chapter list.

### 6.2 Keyboard navigation and focus order, per screen

The shell first: `F6` moves between the rail and the content area, which is the
convention on this platform and currently does not exist — today the rail and the content
are one flat Tab sequence, so reaching a cover means tabbing past eleven destinations.

**Library.** Rail → New category → category chips (each a stop, `+N more` last) →
Chapters menu → overflow → notice bar action → notice bar dismiss → grid. The grid is one
tab stop; arrow keys move between cells, `Enter` opens, `Home`/`End` jump to the first and
last title. Without this, Tab visits 359 cards.

**Sources.** Rail → filter field → clear → grid. Same grid behaviour; typing while the
grid has focus moves focus back to the filter field and inserts the character, which is
what a 658-item list needs.

**Details.** Rail → back → Continue → In library → overflow → description `Show more` →
related strip (one stop, arrows within) → chapter list (one stop, arrows within,
`Enter` reads). `Continue` is the second stop after back, so the primary action is two
keystrokes from arrival.

**Reader.** The reading surface holds focus, as it does today
(`ReaderScreen.kt:278-291`, with the `awaitEachGesture` focus-recovery that stops a click
on the strip killing every shortcut — that behaviour is kept). Existing keys are
unchanged: arrows, `Space`, `PageUp`/`PageDown`, `+`/`-`/`0`, `Escape`
(`ReaderScreen.kt:300-317`). `Tab` reveals the chrome and enters it: back → bookmark →
previous chapter → next chapter → Display. `Escape` inside the chrome returns focus to
the strip; `Escape` with the strip focused leaves the reader. Chrome at `alpha = 0f` is
removed from the tab order, so `Tab` cannot land on something invisible.

**Discover.** Rail → tabs (arrow keys move between the two, `Tab` leaves the row — the
standard tablist pattern, which `FilterChip`s do not give) → search field → Search →
source picker → Recent chips → results. `Enter` in the field submits, which
`Modifier.onEnter` (`Common.kt:225`) already does and which is deliberate per its comment:
each keystroke would be a live request to a third-party site.

### 6.3 `contentDescription` for every icon-only control

| control | description |
|---|---|
| `TopBar` back | `backLabel`, default `"Back"` |
| `NoticeBar` dismiss | `"Dismiss this message"` |
| Library overflow `⇅` | `"More library options"` |
| Details overflow `⋮` | `"More actions for this title"` |
| Sources filter clear | `"Clear the filter"` |
| Reader back | `"Leave the reader"` |
| Reader bookmark | `"Bookmark this page"` / `"Remove this bookmark"` — state in the description, because an `IconToggleButton`'s meaning is its state |
| Reader previous / next chapter | `"Previous chapter"` / `"Next chapter"` |
| Reader Display `⚙` | `"Display options"` |
| Discover source picker | has the visible label `"15 sources"`; its icon is `null` |
| Source group chevron | `null`; the whole header row is the toggle and carries `"Expand MangaDex"` / `"Collapse MangaDex"` |
| Nav rail icons | `null`; the label is visible and in the same semantics node, so a description would make a screen reader say it twice |
| `EmptyBox` / `ErrorBox` icons | `null`; the headline carries the meaning |
| `CoverCard` cover | the title, as `RemoteImage` is already given at `LibraryScreen.kt:566` |
| `ChipRow` expand chip | the visible `"+3 more"` / `"Show less"` label |

Rule, enforced by `AppIcon` taking `contentDescription` as a **required** parameter: an
icon is either labelled or explicitly `null`, and `null` is a decision visible in the
diff.

### 6.4 Not conveyed by colour alone

Four places currently fail this and are fixed above:

| where | today | fixed by |
|---|---|---|
| `UnopenableBar` (`LibraryScreen.kt:342-350`) | `colorScheme.error` text, no icon | `ic_warning` (4.1) |
| current chapter (`DetailsScreen.kt:483-487`) | `colorScheme.primary` text colour | `AppListRow(selected)` fill + `check` (4.3) |
| nav rail selection | a tonal fill at 1.75:1 / 1.11:1 | a 1 dp `outline` stroke at 5.17:1 / 3.84:1, plus the filled icon variant and the emphasised label (1.6, 3.10) |
| `ErrorBox` / every inline error | red text and nothing else | `ic_error` and a headline (3.7) |

### 6.5 What happens at UI scale 2.0

`Main.kt:100` multiplies `LocalDensity.density`, so everything expressed in `dp` scales.
Nothing in this spec is in px or in a fraction of the window. The failure mode to guard
against is a **fixed height containing text**, and these are the places it bites:

| element | at scale 2.0 | why it holds |
|---|---|---|
| navigation rail, 11 destinations | needs ~1366 px in an 810 px window | the inner `verticalScroll` (3.10). Today five destinations are unreachable |
| Library category chips | wrap to 2 rows, then collapse | `ChipRow`'s `FlowRow`. Today they are unreachable at **any** scale |
| Library filter row | the pinned controls drop to their own line | the row is itself a `FlowRow` with two children, so it wraps instead of squeezing the chip flow to zero. No breakpoint |
| Details action row | 2 buttons + icon ≈ 330 dp, against 555 dp (the rail is still drawn here, 160 px at this scale) | the `horizontalScroll` is no longer needed |
| Reader bottom bar | 332 dp against 635 dp, and 400 dp in an 800 px window | the only flexible element is `Spacer(weight(1f))`, so the gap closes before anything leaves. Today it is cut at the default scale |
| Reader top chrome | back 48 + title `weight(1f)` + bookmark 48 | the title ellipsises; the buttons never move |
| Sources grid | `Adaptive(240.dp)` resolves to 1 column | degrades to exactly today's layout rather than breaking |
| every `TopBar` | `maxLines = 1` + `Ellipsis` already | unchanged |
| `AppListRow` | `heightIn(min = 64.dp)` | a **minimum**, so wrapped text grows the row |
| the reader's Display popup | a `Popup`, clamped by its position provider | cannot be cut by the window edge, unlike the inline bar it replaces |

**One real clipping hazard this pass must also fix.** Three `AlertDialog` bodies build an
unbounded `Column` and loop over a caller-supplied list with no scroll container:
`CategoryPickerDialog` (`DetailsScreen.kt:289-316`), `RefileDialog`
(`LibraryScreen.kt:436-454`) and `DownloadChaptersDialog` (`DetailsScreen.kt:432-460`).
With a dozen categories at UI scale 2.0 the dialog is taller than the window and the
confirm button is off-screen. Each gets
`Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())` on its body.
`CurateScreen.kt:338` already does this with `heightIn(max = 320.dp)`, so the pattern
exists in the tree and simply was not applied everywhere.

### 6.6 Hit targets

Every interactive element is at least 40 × 40 dp, which at UI scale 1.0 on a pointer
device is the Material desktop minimum. The ones below it today are the rail's glyph
`Text`s — `Main.kt:169-171` has a comment explaining that an *empty* icon slot made the
item awkward to click, which is the right observation applied to the wrong fix: a 24 dp
`AppIcon` inside `NavigationRailItem`'s own 56 dp target solves it properly.

---

## 7. Implementation order

Packages do not share a file. The contract packages are marked and **must land before any
other package starts** — not because of build order but because every other package
imports from them.

CLAUDE.md caps parallel agents at 5. The parallel packages below fit in two batches of
four.

### The shared contract — WP1, main agent only, nothing else runs during it

After WP1 lands, these files are **frozen**. No other package may edit them. A package
that needs a change here stops and asks, rather than editing and merging.

**WP1a — tokens.** No behaviour change; the app looks different and does nothing new.

| file | action |
|---|---|
| `desktop/src/main/resources/icons/*.svg` | new — 43 vendored Material Symbols Rounded files (2.3–2.5) |
| `desktop/src/main/resources/icons/LICENSE` | new — Apache-2.0 attribution |
| `README-DESKTOP.md` | edit — record the vendored icon source and licence |
| `desktop/.../ui/Theme.kt` | new — both colour schemes, `DropSauceReaderColors`, `Space`, `ThumbSize`, `COVER_ASPECT` (1.12) |
| `desktop/.../ui/AppIcons.kt` | new — the icon-name constants |
| `desktop/.../Main.kt` | edit — line 103 only, to use the new schemes |
| `desktop/src/test/.../ui/ThemeContrastTest.kt` | new — every pair in 1.5, with a positive control |
| `desktop/src/test/.../ui/AppIconsTest.kt` | new — every name resolves, with a positive control |

**WP1b — shared components.**

| file | action |
|---|---|
| `desktop/.../ui/Common.kt` | edit — `TopBar` (3.1), `ErrorBox` (3.7), `LoadingBox` + `LoadingStrip` (3.8), new `EmptyBox` (3.6), `SectionHeader` (3.5), `AppListRow` (3.4), `NoticeBar` (4.1), `AppIcon` (2.6), `Modifier.focusRing` (6.1). `RemoteImage` and `onEnter` unchanged |
| `desktop/.../ui/Covers.kt` | new — `CoverCard`, `CoverBadge`, `CoverBadgeTone` (3.3) |
| `desktop/.../ui/Chips.kt` | new — `ChipRow` (3.9) |

**WP1c — the navigation contract.** This is the one that touches many files, all
one-line edits.

| file | action |
|---|---|
| `desktop/.../feature/FeatureContext.kt` | edit — add `Feature.icon: String`, remove `Feature.glyph` |
| `feature/{appupdate/AppUpdateFeature, curate/CurateFeature, discover/DiscoverFeature, download/DownloadFeature, local/LocalFeature, localx/LocalxFeature, migration/MigrationFeature, readerx/ReaderExtrasFeature, reading/ReadingFeatures, scrobbling/ScrobblingFeature, suggestions/SuggestionsFeature, sync/Features}.kt` | edit — `override val glyph` → `override val icon`, one line each, **no other change to these files** |
| `desktop/.../ui/NavRail.kt` | new — `AppNavRail`, `NavDestination`, `NavGroup` (3.10) |
| `desktop/.../Main.kt` | edit — `ROOTS` becomes `NavDestination`s; `NavRail` deleted; the rail is not drawn for `Screen.Reader` / `Screen.LocalReader` (4.4); `BackToSettings` uses `ic_chevron_left` |
| `desktop/src/test/.../ui/NavigationLayoutTest.kt` | edit — unchanged assertions, plus one that every `Feature.icon` resolves to a vendored file |

Two of those twelve files also contain screens — `DiscoverFeature.kt` (holds
`DiscoverRoot` and `SourceList`) and `ReaderExtrasFeature.kt` (holds `ReaderExtrasScreen`).
WP1c changes **only the `glyph` line** in each. WP5 and WP9 own the rest of those files
and run afterwards, so there is no overlap in time.

**Gate before anything else starts:** `./gradlew :desktop:test` green,
`./gradlew :app:assembleDebug` green, `:desktop:run` opens and the rail scrolls.

---

### Batch A — the five reworked screens. Four agents, no shared files.

**WP2 — Library and Details.**
`ui/LibraryScreen.kt`, `ui/DetailsScreen.kt`.
These two are one package because `CategoryNameDialog` is declared in `LibraryScreen.kt`
(`:609`) and called from `DetailsScreen.kt` (`:322`); splitting them would put two agents
on one symbol. Sections 4.1 and 4.3, plus the unbounded-dialog fix in 6.5.

**WP3 — Sources, History, Browse.**
`ui/CatalogScreen.kt`, `ui/HistoryScreen.kt`, `ui/BrowseScreen.kt`.
Section 4.2 for the catalogue; History and Browse are tokens-and-components only
(`AppListRow`, `CoverCard`, `EmptyBox`, `ErrorBox`). `BrowseScreen.kt`'s own `CoverCard`
copy and `COVER_ASPECT` are deleted here.

**WP4 — the reader.**
`ui/ReaderScreen.kt`.
Section 4.4 in full: the local theme, the `Box` restructure, auto-hide, the new bottom
bar, the Display popup, all eleven hardcoded colours. The bookmark toggle is already a
slot (`ReaderScreen.kt:417`), so this package does not touch `feature/reading/`.

**WP5 — Discover.**
`feature/discover/**` — `DiscoverFeature.kt`, `GlobalSearchScreen.kt`,
`SourceBrowseScreen.kt`, `FilterPanel.kt`, `DiscoverCommon.kt`.
Section 4.5. `DiscoverCommon.kt`'s `CoverCard`, `ProgressBar`, `COVER_ASPECT` and
`STRIP_ITEM_WIDTH` are deleted in favour of the WP1 versions; the file may end up empty
and be removed.

**After batch A:** integrate, `:desktop:test`, `:app:assembleDebug`, run the app, commit.

---

### Batch B — tokens and shared components everywhere else. Four agents.

**WP6 — reading and curate.**
`feature/reading/**` (`BookmarksScreen.kt`, `StatsScreen.kt`, `ReadingFeatures.kt`),
`feature/curate/**` (`CurateScreen.kt`).
Includes the `BookmarkToggle` restyle WP4's chrome depends on, and the three
`horizontalScroll` chip rows in `CurateScreen.kt`. `StatsScreen.kt`'s chart
`LinearProgressIndicator` and its `Canvas` are left alone (5.3).

**WP7 — local, localx, download.**
`feature/local/**`, `feature/localx/**`, `feature/download/**`.
Four `TopBar`s in `LocalxScreen.kt` alone, two grid implementations, the unsized
thumbnail at `LocalxScreen.kt:543`, and the "chapter could not be read" text rendered as
body copy.

**WP8 — sync, migration, suggestions.**
`feature/sync/**`, `feature/migration/**`, `feature/suggestions/**`.
Deletes `FeatureHeader` (`SyncUi.kt:132`) and the second `ProgressBar` copy
(`SuggestionsScreen.kt:282`). Fixes the empty state rendered as an error
(`MigrationScreen.kt:120`), the uncoloured run failure (`UpdatesScreen.kt:165`) and the
failure rendered as a hint (`SuggestionsScreen.kt:163`). `RelatedTitles.kt`'s
`STRIP_ITEM_WIDTH` duplicate is deleted here.

**WP9 — scrobbling, appupdate, readerx, settings.**
`feature/scrobbling/**`, `feature/appupdate/**`, `feature/readerx/**`,
`ui/SettingsScreen.kt`.
Deletes `TrackingHeader` (`TrackingUi.kt:36`) and the dead `TrackingEmptyNote`
(`TrackingUi.kt:61`). Fixes the two unhandled chip rows in `SettingsScreen.kt` and the
unbounded branch chip row in `TitlePrefsEditor.kt`. `ColorFilterEditor.kt`'s five literal
colours and `TapZoneEditor.kt`'s four **stay** — those are a colour-filter preview and
semantic zone tints, which are data, not theme.

**After batch B:** integrate, `:desktop:test`, `:app:assembleDebug`, run at UI scale 1.0,
1.5 and 2.0, commit.

---

### Three things to compile before trusting them

Each of these is an assumption in this spec that a build will confirm or refute. None is
load bearing on the design; each has a stated fallback.

1. **`TopBar` gaining `modifier` as its second parameter.** Source-compatible only if all
   19 call sites use named arguments. If any is positional, `modifier` goes last instead
   and the Compose convention loses. *(WP1b)*
2. **`LoadingStrip` using the real `LinearProgressIndicator`.** The comment at
   `DiscoverCommon.kt:64-67` says the overload set is ambiguous with every optional
   parameter omitted. Passing `progress` as an explicit lambda is what
   `LibraryScreen.kt:372`, `UpdatesScreen.kt:151` and `DownloadsScreen.kt:196` already do
   successfully, so it should resolve. If it does not, the two-box drawing moves inside
   `LoadingStrip` and lives there once instead of twice. **Do not delete the hand-drawn
   copies until this compiles.** *(WP1b, then WP5 and WP8)*
3. **Nesting `MaterialExpressiveTheme` inside the reader.** `motionScheme` must be passed
   through explicitly or `MotionScheme.expressive()` is silently lost inside the reader —
   which is the exact class of regression D6a exists to detect, so it is worth asserting
   rather than eyeballing. *(WP4)*

---

## 8. What was cut, and what needs an answer

### Cut, with reasons

| cut | why |
|---|---|
| Content-type and language filter chips on Sources | the data exists on `MangaParserSource` and it would help a 658-item list a great deal, but it is a **new feature** and the brief puts those out of scope. Raised as a question below rather than smuggled in |
| A "sources you already read" section at the top of the catalogue | same: the computation already exists at `DiscoverFeature.kt:267-271`, but wiring it into the catalogue is new behaviour |
| A filter or sort control on the Details chapter list | new feature. A 105-chapter list wants one; that is a separate decision |
| A grid/list view toggle outside Organise | new feature. Organise already has one; nothing else needs one yet |
| Custom typography or a bundled font | would be a new dependency or a new asset class, and `MaterialTheme.typography` is not the problem — how it is applied was |
| A light-theme reader | deliberately refused. `ReaderScreen.kt:284` is right that a manga page belongs on black, and D22's webtoon-only strip makes a pale surround worse, not better |
| Wiring up the colour filter and tap zones | `DECISIONS.md` lists these as built-but-not-connected, and D22 notes tap zones are largely moot for a scrolling reader. Feature work, not design work |
| A reduced-motion setting | there is no platform signal to read on Linux desktop here, and the motion set in 1.11 is six fast specs with nothing decorative and nothing looping. If a setting is added later it switches every one of them to a snap |
| Empty-state illustrations | a new asset class beyond the icon set, and an icon plus a headline does the job |

### Questions that need you

1. **The reader hiding the navigation rail.** The argument is in 4.4 and I do not think it
   contradicts D26, but D26 is yours and the reading is a judgement. If you want the rail
   to stay, the bottom-bar fix and the Back demotion still stand on their own and nothing
   else in 4.4 depends on it.
2. **Demoting Start over, Download and Other sources to an overflow menu on Details.**
   D27 says Other sources must be *offered whenever the details screen is open*, and an
   always-present menu satisfies that, with the two failure states still promoting it to
   a first-class button. Confirm you read D27 the same way.
3. **The Library length filter as a menu rather than seven chips.** This trades
   discoverability for a whole row of vertical space and for never clipping. It is the
   one change in 4.1 I would most expect you to push back on.
4. **Removing `Feature.glyph` rather than deprecating it beside `icon`.** Removal touches
   twelve files for one line each. Keeping both means a new area can still ship a glyph
   and nobody notices, which is how the rail reached eighteen destinations.
5. **A warm sand tertiary instead of Material's lavender default.** Reasoning in 1.1. If
   you would rather stay on the TonalSpot default, the generated values are
   `#CAC1ED` / `#484267` dark and `#605980` / `#E5DEFF` light, and they clear the same
   thresholds — they just look like the purple being removed.
6. **Deleting `FeatureHeader` and `TrackingHeader`.** Both carry comments defending the
   duplication as feature independence. That independence mattered while five agents were
   building five areas at once; it is now three copies of a header that will drift.
7. **The two out-of-scope Sources additions in the table above.** Both are cheap and both
   are the highest-value things that could be done to that screen. Say the word and they
   become a sixth package.

### Flagged as uncertain rather than asserted

- **The rail's 61 dp item pitch** was measured off a capture downscaled from 1270 px to
  1100 px, by reading label baselines and multiplying by 1.1545. The spacing is very
  regular (79 image px between every consecutive pair, seven times) and the result is
  consistent with Material's 56 dp item plus padding, but the derived "~1366 px at UI
  scale 2.0" should be re-measured in the running app before anyone quotes it.
  I got this wrong once already by forgetting the downscale factor and reporting 52.7 dp;
  the conclusion survived the correction but the number did not, which is the reason it
  is flagged here. The conclusion does not depend on precision: at any plausible pitch,
  eleven destinations do not fit in 810 px and the rail needs to scroll.
- **The reader bar's ~1125 dp requirement** is a sum of the widths visible in `r2s.png`
  plus the declared widths of the controls that were cut off (`Modifier.width(150.dp)`
  twice, `widthIn(min = 76.dp)` once, plus button and gap estimates). The cut-off itself
  is directly observed; the total is an estimate. Again the conclusion does not depend on
  it — the bar is demonstrably cut at the default window size and default scale.
- **The Material Symbols FILL axis** is a weak shape cue on `search` and `history`. The
  rail's conformance claim rests on the 1 dp indicator stroke, not on the icon swap
  (1.6).
- **`CatalogScreen.kt:43`** has a `remember(query)` that reads `all` without keying on it.
  Not reachable today because `selectRoot` recreates the screen, but it is a trap if the
  catalogue ever stops being a root. Noted for whoever implements WP3; not a design item.

---

## Appendix: how the colour numbers were produced

Not by eye and not from memory. A script converted sRGB to CIELAB (D65, the standard
matrices), generated each tonal ramp at a fixed Lab hue with chroma clamped to the sRGB
gamut by 60-step bisection, mapped tones onto Material 3's role assignments, and computed
every ratio with the WCAG 2.x relative-luminance formula. Inputs that were measured
rather than chosen:

```
#64D2FF  ->  L* 79.62   C* 36.54   h 239.35      (the brand seed)
#1A1A1A  ->  L*  9.26                            (the icon's ground)
#FF0000  ->                        h  40.00      (the error palette's hue)
```

Maximum in-gamut chroma at the seed hue, which is why the ramps are clamped per tone:

```
L*10: 12.6   L*20: 17.5   L*30: 22.4   L*40: 27.2   L*50: 32.1
L*60: 37.0   L*70: 41.8   L*80: 35.9   L*90: 17.8
```

Every hex in sections 1.2, 1.3 and 1.4 and every ratio in 1.5 came out of that run.
`ThemeContrastTest` (WP1a) recomputes the ratios from the `ColorScheme` objects
themselves, so the tables and the shipped code cannot drift apart unnoticed — and it
carries a deliberately failing fixture so the assertion is proven to fire before it is
trusted.
