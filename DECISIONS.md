# DECISIONS.md

Every significant technical choice for the Linux desktop port, with a
one-line rationale, plus everything deliberately left out of v1.

Status: **Phase 0 proposal, awaiting sign-off. No code written yet.**

Read `ARCHITECTURE.md` for how the Android app is built and
`PORTING_NOTES.md` for the dependency-by-dependency inventory.

---

## 0. The finding that shapes everything

DropSauce has **three** source backends and **two of them cannot leave
Android**:

- Mihon extensions are dex inside APKs, found through `PackageManager`,
  loaded through `ChildFirstPathClassLoader`.
- LNReader plugins are JavaScript run in a headless `WebView`.
- `LOCAL` is files on disk.

Meanwhile the `kotatsu-parsers` dependency the app already ships is a
**pure JVM jar** (verified: Gradle module metadata says
`standard-jvm` / `jar` / `platform.type=jvm`, JVM 11; dependencies are
jsoup, kotlin-stdlib, `coroutines-core-jvm`, `okhttp-jvm`, `okio-jvm`,
`org.json`, `collection-jvm`) containing **3213 site-parser classes**.
The app currently uses it for its data models only. There is no
`MangaLoaderContext` implementation anywhere in the tree, so the entire
catalogue is dead weight in the APK.

So the desktop app gets its remote sources from the one backend that is
natively JVM, and the Android app keeps the two that are not.

---

## 1. Decisions

### D1. Desktop v1 sources come from the native `kotatsu-parsers` catalogue

Implement `DesktopMangaLoaderContext : MangaLoaderContext` (7 abstract
members) and drive `newParserInstance(MangaParserSource.X)`.

**Rationale:** it is the only remote backend that runs on the JVM at all,
it needs ~300 lines of new host code rather than a dex loader and an
Android API emulator, and it speaks the exact model types (`Manga`,
`MangaChapter`, `MangaPage`) the rest of the app already uses.

**Consequence, and the one thing most worth your sign-off:** the desktop
app's source list will not match the Android app's. Android browses Mihon
extensions; desktop browses the Kotatsu catalogue. Library data still
shares a schema, but a favourite saved from a `MIHON_*` source will show
as a missing source on desktop, and vice versa. The `MissingMangaSource`
path already handles exactly this case (it caches `source_title` so the
entry still renders), so nothing breaks, but the two apps are not
interchangeable readers of the same library.

**Reading of the definition of done:** "add a source/extension" on desktop
means enabling a source from the catalogue, the same gesture the Android
app's own Sources Catalog screen performs. If you want desktop to install
something at runtime instead, that is D2.

**The argument against D1, which you should weigh.** This fork has
deliberately moved *away* from the Kotatsu native parsers.
`kotatsumigration/data/KotatsuSourceMap.kt` exists solely to map legacy
`MangaParserSource` names out of Kotatsu backups and onto equivalent
Mihon extensions, via a generated `res/raw/kotatsu_source_map.json`. So
D1 points desktop at a catalogue you consciously migrated users off. The
counter-argument is that it is that or no remote sources at all on
desktop, since the alternative backends are D2 (novels only, deferred)
and D3 (cut permanently). Flagged here rather than buried because it is a
product direction question, not a technical one.

### D2. LNReader JS plugins are deferred to v1.1, not cut

They are the only backend that is genuinely *installable at runtime* and
they are only Android-bound by their host, not by their contract. The
plugin `fetch` is already bridged to OkHttp, so nothing browser-specific
crosses the boundary except Promises and `TextEncoder`/`TextDecoder`.
GraalJS has a real job queue (the reason the Android app chose a WebView
over QuickJS) and the JDK already provides GBK / Big5 / Shift_JIS /
EUC-KR charsets.

**Rationale for deferring:** novels are a text-rendering path, and v1
needs the image-reading path working first. Also the 426 KB checked-in
`lnreader-libs.js` bundle is built `--platform=browser`, so it needs
re-verifying against GraalJS before it can be trusted.

### D3. Mihon extension APKs are cut from desktop permanently

**Rationale:** loading them would need dex2jar at install time *plus* a
reimplementation of the Android APIs extensions link against
(`Application`, `SharedPreferences`, `Uri`, `Bundle`, preference screens),
*plus* the RxJava 1 surface, *plus* signature verification without
`PackageInfo`. That is a separate product, not a port.

### D4. Project structure: `:app` + `:shared` (KMP) + `:desktop`

```
settings.gradle
  :app       Android, unchanged in kind, gradually thinned
  :shared    Kotlin Multiplatform, targets androidTarget() + jvm()
  :desktop   JVM only, Compose for Desktop application
```

`:shared` is populated by **moving** code out of `:app`, never by
copying. `:app` then depends on `:shared`. Each move is its own commit,
gated on `./gradlew :app:assembleDebug`.

**Rationale:** converting `:app` itself to KMP in place puts the hard
constraint (Android must build at every commit) at maximum risk on day
one. A third module lets the risky moves happen one subsystem at a time
with a green gate in between. Rejected alternatives: full in-place KMP
conversion of `:app` (too risky for the gate); a standalone `:desktop`
that shares nothing (shares nothing, which is the point of the mission).

### D5. Migration order is skeleton, then database, then domain, then UI

1. `:desktop` skeleton + toolchain proof (D6) - Android gate must stay green
2. `:shared` with models, enums, pure domain - gate
3. **Room layer into `:shared`** - gate. Deliberately early because it is
   the highest-risk move; failing at step 3 is cheap, failing at step 8 is not.
4. Desktop platform implementations (paths, settings, http, parser host,
   JS, image decode)
5. Desktop UI
6. Packaging

### D6. The toolchain compatibility question is settled before anything else

The app is on Kotlin 2.3.21 with Compose material3 pinned to
`1.5.0-alpha28` for the Material 3 **Expressive** APIs, which the theme
itself depends on. Compose Multiplatform's latest stable is **1.12.0**.
Two things must be proven with a real build, not assumed:

- Kotlin 2.3.21 works with the CMP Gradle plugin (one Kotlin version
  serves the whole build; there is no per-module escape hatch)
- CMP's material3 exposes `MotionScheme`, `MaterialShapes`, `ButtonGroup`,
  wavy progress and the FAB menu

If the second is false, the "portable" Compose screens
(`settings/compose`, `details/ui`, `stats/ui`) need their theme rewritten
and their reuse value drops sharply. **This is Phase 1 Agent E's first
job and it gates the v1 UI scope.**

### D7. Dependency injection: Hilt stays on Android, plain Dagger 2 on desktop

**Rationale:** Hilt has no desktop target and never will. Plain Dagger 2
is JVM-capable, KSP-processed, and reads the same `@Inject` constructor
annotations already on the `:shared` classes, so shared code needs no
change. `:app` keeps Hilt. Rejected: a manual composition root (loses the
existing `@Inject` annotations); Koin (a new paradigm for no gain).

### D8. `AppSettings` becomes an interface, and desktop implements only what it uses

The 1501-line god object stays on Android as-is. `:shared` gets a narrow
`Settings` interface per consumer area; desktop backs it with a JSON file
under `$XDG_CONFIG_HOME/dropsauce/` and a `StateFlow` per key.

**Rationale:** porting 1501 lines of preferences for a v1 that exposes
maybe 20 of them is waste, and the Android impl has to keep existing
anyway for the existing install base.

### D9. `Context` and `Uri` do not enter `:shared`

Narrow interfaces instead: `AppPaths`, `StringProvider`, `Notifier`,
`UriOpener`, `Logger`. Filesystem addressing is `okio.Path`, remote
addressing is `okhttp3.HttpUrl`.

**Rationale:** it matches the CLAUDE.md rule (business logic free of
platform types) and the existing coupling is shallow enough to make it
cheap - 26 `Context` and 7 `Uri` imports across all of `data/` + `domain/`.

### D10. Reader: Compose, two modes, no tiling

v1 ships **paged** (LTR and RTL) and **webtoon** (continuous vertical).
Zoom and pan is a hand-written `Modifier.graphicsLayer` +
`pointerInput` transform. Decoding is Skia via Skiko, downsampling large
images on load.

**Rationale:** SSIV's tiled decode has no JVM equivalent
(`BitmapRegionDecoder` is Android-only and Skia exposes no region
decoder through Skiko). Downsample-on-load is the honest trade: it costs
memory on very tall webtoon strips and it works.

### D11. Background work is coroutines, not a scheduler

A supervisor scope owned by the app, plus a `Scheduler` interface for the
periodic jobs. No OS-level scheduling (no systemd timers, no cron) in v1.

**Rationale:** WorkManager's value is surviving process death and doze,
neither of which exists on desktop.

### D12. Packaging is `packageDeb` via Compose `nativeDistributions`

jpackage bundles a JRE automatically. An AppImage task is not in v1.

**Rationale:** `.deb` is one Gradle block; AppImage needs an external
toolchain.

### D13. "Wayland" means XWayland, and the docs will say so

Compose for Desktop renders into an AWT window. On a Wayland session that
is XWayland. Native Wayland needs a JDK with a Wayland toolkit, which is
not something this port can deliver.

**Rationale:** stating it is more useful than a checkbox that implies
something untrue. It will run on a Wayland desktop; it will not be a
native Wayland client.

### D14. Desktop ships at schema version 37 with no migration history

The Room entities and DAOs are shared; the 36 `Migration` classes are not
moved to `:shared` in v1.

**Rationale:** all 36 are written against `SupportSQLiteDatabase`, which
Room KMP does not expose, so they need mechanical rewriting against
`SQLiteConnection`. There are no existing desktop installs to migrate, so
the work buys nothing in v1. Android keeps its migrations untouched in
`:app`. This does mean a desktop database cannot be created by restoring
an older Android database file directly; backup/restore (D15) is the
supported path, and it is version-aware already.

### D15. Backup/restore is the interop path between Android and desktop

Not Google Drive sync (`play-services-auth` is Android-only), not raw
database copying (D14).

### D16. No new dependency is added without appearing in this file first

Planned for v1, each already justified above:

| Coordinate | For |
|---|---|
| `org.jetbrains.compose` Gradle plugin 1.12.0 | Compose for Desktop |
| `androidx.sqlite:sqlite-bundled` | desktop SQLite driver for Room KMP |
| `org.graalvm.polyglot:js` | `MangaLoaderContext.evaluateJs` |
| `org.json:json` | parsers runtime; Android gets it from the platform, JVM does not |
| `com.google.dagger:dagger` + `dagger-compiler` | desktop DI (D7) |

---

## 2. Desktop v1 scope

### In

- Browse the `kotatsu-parsers` catalogue: enable and disable sources
- Per-source list browsing with sort orders and filters
- Search within a source
- Manga details: cover, description, tags, chapter list
- Read a chapter: paged LTR/RTL and webtoon, zoom and pan, keyboard and
  mouse navigation
- Library: favourites with categories, reading history, resume where you
  left off
- Local persistence in SQLite that survives restart
- Import and read local CBZ/ZIP
- A settings screen for the subset of preferences v1 actually uses
- Backup and restore in the app's existing zip format
- `./gradlew :desktop:run` and `./gradlew :desktop:packageDeb`

### Out of v1, deliberately

| Cut | Why |
|---|---|
| Mihon extension APKs | D3, permanent |
| LNReader novel plugins and the novel reader | D2, v1.1 |
| EPUB reading | follows the novel reader |
| Google Drive sync | `play-services-auth` is Android-only |
| Scrobbling: AniList, MAL, Kitsu, Shikimori, MangaBaka | six OAuth flows, none of them load-bearing for reading a chapter |
| Discord Rich Presence | KizzyRPC is an Android library |
| Downloads for offline reading | a whole worker, notification and storage subsystem; reading is online in v1 |
| Chapter-update tracker and notifications | depends on background scheduling and notifications |
| Suggestions | depends on the tracker |
| Statistics | nice, not load-bearing |
| Text to speech | `android.speech.tts`; Linux needs speech-dispatcher |
| App lock, biometric or PIN | no threat model on a desktop session |
| AVIF pages | no JVM decoder exists; fails with a clear error, not a stub |
| Interactive Cloudflare solving | needs an embedded browser (KCEF, ~100 MB into the .deb). Sources behind an active challenge will fail with a clear error. |
| Home screen widgets, app shortcuts, Shizuku | Android platform concepts |
| Double-page and reversed-double reader modes, page animations, configurable tap grid, colour filters, upscaling | reader polish; two modes is a reader |
| AppImage | D12 |
| Windows and macOS | mission says Linux |

### Explicitly still true at every commit

- `./gradlew :app:assembleDebug` passes
- no `TODO()`, no `NotImplementedError`, no empty catch, no compiler-satisfying stubs
- GPL-3.0 headers and Kotatsu/Mihon attribution preserved
- never rewrite git history

---

## 3. Proposed Phase 1 agent split

Five read-only agents, exclusive directories, output to `docs/porting/`.
Adjusted from the mission's suggestion because the extension system
question is settled (D1/D2/D3) and the toolchain question is not.

| Agent | Owns | Question to answer |
|---|---|---|
| A - data | `core/db/**`, `*/data/**`, `core/prefs/**` | Exactly which DAOs, queries and entities survive a Room KMP move; every `@RawQuery` and `withTransaction` call site; what `AppSettings` keys the v1 scope actually reads |
| B - parser host | `core/parser/**`, `core/network/**`, `core/model/**`, the `kotatsu-parsers` jar | The full `MangaLoaderContext` contract as the catalogue's parsers actually use it; how many parsers call `evaluateJs`, `redrawImageResponse`, `requestBrowserAction`; what the OkHttp stack must reproduce |
| C - reader | `reader/**`, `core/image/**` | The page pipeline from `getPages` to pixels; what SSIV is actually relied on for; what a Compose reader must reproduce and what it can drop |
| D - domain | `favourites/**`, `history/**`, `details/**`, `list/**`, `search/**`, `explore/**`, `filter/**`, `local/**` | Which use cases are already platform-free; the real dependency graph of the library/history/details flows; what `LocalMangaRepository` needs that is not `java.io.File` |
| E - build | `gradle/**`, `*.gradle`, `gradle.properties`, `.github/**` | **First: prove Kotlin 2.3.21 + CMP 1.12.0 + Room KMP 2.8.4 resolve together (D6).** Then the module layout, configuration-cache safety, and the jpackage `.deb` path |

No two agents share a directory. `MangaRepository.kt`, the `:shared`
module skeleton and all interface contracts are mine to write before any
Phase 2 agent starts.

---

## 4. Open questions for you

1. **D1, and it is the one that matters.** Desktop would browse the
   Kotatsu native catalogue, which is the catalogue `KotatsuSourceMap`
   shows you deliberately migrated users off. The three options are:
   (a) D1 as proposed, full manga catalogue on desktop, diverging from
   Android; (b) promote D2 into v1 instead, so desktop installs LNReader
   plugins at runtime like Android does, but reads novels only and no
   manga; (c) desktop is local-files-only in v1, which fails the stated
   definition of done. I recommend (a) and will proceed with it unless
   you say otherwise.
2. **Downloads.** Cut from v1. If offline reading on desktop matters more
   than, say, favourites categories, say so and I will trade them.
3. **`:app` thinning.** D4 moves code out of `:app` into `:shared`. That
   is a lot of churn in a repo you also ship Android releases from. If you
   would rather `:app` stay untouched and accept duplication in v1, that
   is a real option and changes D4.
