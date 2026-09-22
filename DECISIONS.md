# DECISIONS.md

Every significant technical choice for the Linux desktop port, with a
one-line rationale, plus everything deliberately left out of v1.

Status: **Phase 2 in progress.** See "Phase 2 progress" below for what
is built and verified. Phase 1 is complete; all five agent reports are in
`docs/porting/`.
Decisions revised by Phase 1 findings are marked as such in place, with
the superseded reasoning stated rather than silently dropped. Agent
reports live in `docs/porting/`.

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

## 0a. Phase 2 progress

Every row was verified by running the command, not by inspection. The
Android gate is re-run before each commit.

**Current state:** `:app:assembleDebug` green, **497 tests** passing
across `:shared` and `:desktop`, `.deb` built and installed to
`/opt/dropsauce` running on its own bundled JRE.

### Working in the app

Nav rail with 12 destinations. Library with categories (create, rename,
delete, multi-category membership) and a chapter-count filter. Source
catalogue of 890 usable sources with an adult filter. Per-source browse
and search. Details with chapters, save-to-category, Continue/Start
over. Webtoon reader, zoom and pan, keyboard navigation, and one
continuous strip that carries on across chapter boundaries (D22, D23).
History with progress. Settings, everything in it wired to
real behaviour. Local import, downloads, bookmarks, statistics, backup
and restore, chapter tracking, source migration.

### Foundations

| Step | State |
|---|---|
| `:desktop` + `:shared` skeleton, toolchain proof | done, D6 |
| Parser host, 1270 sources instantiate, live fetch verified | done, D1 |
| Room KMP, re-entrancy proven with a positive control | done |
| Schema v1 -> v4, each migration verified on the real database | done |
| Packaging, install, bundled JRE | done, D12 and D13 |
| `README-DESKTOP.md` | done |

### Feature areas

Built in two parallel batches of five, each area in an exclusive
directory behind the `FeatureContext` contract.

Batch one, integrated: local library, downloads, discover (per-source
filters and multi-source global search), bookmarks, statistics, backup
and restore, chapter tracker, source migration and auto-fix.

Batch two, integrated: external tracking services, reader extras,
recommendations, library batch operations and incognito, richer local
import (multi-chapter layouts, EPUB, ComicInfo.xml). All thirteen
features are registered in `AppState.features` and reachable from the
nav rail.

### Deliberately not attempted

Discord Rich Presence, Google Drive sync, home screen widgets, Shizuku,
text to speech, and the Mihon and LNReader extension runtimes (D3, D2).

### Known open items

**Built but not connected.** Both are screens that write settings the
rest of the app never reads. They look finished from the nav rail, which
is exactly why they are recorded here.

- Reader extras: the colour filter and the tap-zone grid are edited and
  persisted by `ReaderExtrasStore`, but nothing outside
  `feature/readerx/` imports `ColorFilters`, `TapZones` or the store, so
  `ReaderScreen` applies neither. Tap zones are also largely moot under
  D22: webtoon scrolls, it does not tap to turn.
- Per-title overrides: `TitlePrefsRepository` stores a custom title,
  custom cover and preferred branch, and the extras screen lists them,
  but `AppState.titlePrefs` is referenced nowhere else, so no screen
  applies any of the three. Per-title **incognito** is the exception and
  does work, because it is read through `CurateRepository` instead.

**Debt.**

- Tiled page decoding is not implemented; very tall webtoon strips decode
  whole. D10 records the `javax.imageio` region-decode path that fixes it.
- `MangaEntity` to `Manga` mapping is duplicated across five files
  because `LibraryRepository`'s copy is private. Worth hoisting.
- Two hand-rolled JSON codecs remain from before `kotlinx-serialization`
  was added to `:desktop`; both can now be deleted.
- `:desktop` has no `BuildConfig`, so the backup's app id and version are
  hardcoded and will drift from `:app`.
- There is no `manga_tags` table, so tags are not persisted and the
  suggestions area re-derives them.
- `FeatureContext` has no `list(source, sort, filter)`, so feature areas
  reach through the `internal` `sources.session()`.
- Backup hand-lists the settings fields it writes. Two were silently
  missed once already; it should derive them from `SettingsData`.
- `FeatureNavigator.openLocalReader` takes no page list, so chapters
  inside an archive route around the shell reader.

### Process notes worth keeping

- A `git add -A` during parallel agent work swept nineteen files of five
  agents' in-flight code into an unrelated commit. Stage explicit paths.
- A commit ran behind a shell `&&` chain whose earlier command succeeded,
  so it committed while `:desktop:test` was failing. Verification must
  gate the commit, not merely precede it.
- Agents that ran a **positive control**, deliberately breaking their own
  mechanism to prove the test detects it, caught real defects. Agents
  that only reported green did not. The controls are worth requiring.

**Contracts owned centrally** (never by a feature area): the Room schema
and its migrations, `FeatureContext`, `Feature`, `FeatureNavigator`,
`AppState`, `Main.kt` and the navigation shell.

---

## 1. Decisions

### D1. Desktop v1 sources come from the native `kotatsu-parsers` catalogue

Implement `DesktopMangaLoaderContext : MangaLoaderContext` (**8**
abstract members, not 7) and drive `newParserInstance(MangaParserSource.X)`.

**Phase 1 proved this rather than estimating it.** Agent B wrote a 55-line
Java stub context, put it on a stock JDK 21 classpath (kotlin-stdlib,
okhttp-jvm, okio-jvm, coroutines, jsoup, org.json, collection-jvm) and
ran it: all **1270** `MangaParserSource` constants instantiated,
`ok=1270 fail=0`, and live `getList` -> `getDetails` -> `getPages` chains
completed against MangaDex, a Madara site and a ZeistManga site. The
catalogue jar is also Android-free under a positive control:
`grep -rlE "android[^x]"` across all 3365 classes returns 0 while the
same grep for `androidx` returns 296, so the detector fires and the code
is genuinely clean.

**The trap that must be in the design from day one.**
`MangaParser extends okhttp3.Interceptor`, and
`parsers/core/MangaParserWrapper.intercept` is the **only** place a
parser's `getRequestHeaders()` (User-Agent, Referer) is merged into an
outgoing request. Verified in bytecode: it reads the chain request's
headers, merges `MangaParser.getRequestHeaders()` via
`OkHttpUtils.mergeWith`, rebuilds the request and delegates through a
`ProxyChain`. **Nothing in the library installs that interceptor.** A
host that builds one shared OkHttpClient and hands it to every parser
sends no per-source headers and collects 403s, and it still compiles and
MangaDex still works, which is exactly how this survives to production.
The desktop host must construct a per-parser client with the parser
installed as an interceptor.

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

**Prerequisite found in Phase 1, not in the original decision:**
`core/prefs/SourceSettings.kt:18` already declares
`class SourceSettings(context, source) : MangaSourceConfig`. That is
precisely the type `MangaLoaderContext.getConfig()` must return, so the
per-source settings layer is not a settings nicety to defer, it is part
of D1's critical path. Its current constructor takes a `Context` and a
`getSharedPreferences` name, so the desktop implementation is a
reimplementation of that interface, not a move.

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
  :app       Android, Groovy DSL, unchanged in kind, gradually thinned
  :shared    Kotlin Multiplatform, android + jvm() targets
             MUST apply com.android.kotlin.multiplatform.library,
             NOT com.android.library (see D6)
  :desktop   JVM only, Compose for Desktop application
```

Groovy and Kotlin DSL build files coexist in one build (proven in
Phase 1), so `:app` keeps its Groovy build file and only the new modules
need Kotlin DSL.

`:shared` is populated by **moving** code out of `:app`, never by
copying. `:app` then depends on `:shared`. Each move is its own commit,
gated on `./gradlew :app:assembleDebug`.

**Rationale:** converting `:app` itself to KMP in place puts the hard
constraint (Android must build at every commit) at maximum risk on day
one. A third module lets the risky moves happen one subsystem at a time
with a green gate in between. Rejected alternatives: full in-place KMP
conversion of `:app` (too risky for the gate); a standalone `:desktop`
that shares nothing (shares nothing, which is the point of the mission).

### D5. Migration order: skeleton, decouple in place, database, domain, UI

**Revised after Phase 1 Agent D. The original step 2 ("`:shared` with
models, enums, pure domain") cannot execute as written.**

1. `:desktop` skeleton + toolchain proof (D6) - Android gate must stay green
2. **Decoupling refactors performed entirely inside `:app`**, before any
   module move. Each is an ordinary Android change gated by
   `:app:assembleDebug`. This is where most of the work actually is:
   - kill `printStackTraceDebug` (87 files, resolved by source set, so it
     silently fails to compile the moment anything moves)
   - remove `BuildConfig` from `local/data/MangaIndex.kt` and three others
   - invert the `domain` -> `ui` dependency in `list/domain`
     (`MangaListMapper`, `MangaListQuickFilter`) so domain stops importing
     `list/ui/model` and `ChipsView`
   - strip `@StringRes`/`@DrawableRes` Ints off the domain enums
     (`ListSortOrder`, `ListFilterOption`, `SourcesSortOrder`)
   - split the mixed `core/util/ext` files
3. `:shared` with models, enums and the now-decoupled domain - gate
4. **Room layer into `:shared`** - gate. Still deliberately early because
   it is the highest-risk move.
5. Desktop platform implementations (paths, settings, http, parser host,
   JS, image decode)
6. Desktop UI
7. Packaging

**Why:** `domain` currently depends on `ui`. Moving domain first would
drag `list/ui/` (56 files) into `:shared` with it, or fail. Doing the
decoupling as plain Android refactors first keeps every step verifiable
against the existing gate and leaves a smaller, cleaner thing to move.

### D6. The toolchain gate: PASSED

**Proven by build in Phase 1, not assumed.** These compiled together in
one Gradle build, with both a `jvm()` and an android target, a real
`@Entity`/`@Dao`/`@Database` and a real `@Composable` desktop `Window`:

| | version |
|---|---|
| Kotlin | 2.3.21 |
| `org.jetbrains.kotlin.plugin.compose` | 2.3.21 |
| Compose Multiplatform | 1.12.0 (confirmed latest stable) |
| Room KMP | 2.8.4 |
| KSP | 2.3.6 |
| `androidx.sqlite:sqlite-bundled` | 2.8.0-alpha01 |
| AGP | 9.2.1 |

`BUILD SUCCESSFUL`, configuration cache stored. **There is no Kotlin/CMP
version gap**, which was the thing most likely to sink the plan.

**The real blocker was somewhere else entirely.** Since AGP 9.0,
`com.android.library` + `kotlin.multiplatform` is a **hard error**.
`:shared` must apply `com.android.kotlin.multiplatform.library`, and that
plugin's documented `androidLibrary { }` block is already deprecated at
AGP 9.2.1 + KGP 2.3.21 in favour of `kotlin { android { } }`. Nothing in
the Phase 0 plan anticipated this and it is the largest structural
constraint in the port.

Also proven, so D4 and D7 hold: Groovy and Kotlin DSL build files coexist
in one build (so `:app` stays Groovy); `:app` -> `:shared` works
end-to-end with Hilt generating a real `Provider<SharedThing>` and the
shared class landing in the APK dex; and plain Dagger 2 + KSP on a JVM
target generates `DaggerDesktopComponent`.

**Trap to pin on day one:** unpinned, `:shared`'s android bytecode
compiles to Java 21 while `:app` targets Java 11, and
`:app:assembleDebug` still passes. Silent divergence.

### D6a. Pin CMP material3 to 1.12.0-alpha03 for the Expressive APIs

CMP's material3 is **version-decoupled from CMP**: `compose.material3`
in CMP 1.12.0 resolves to material3 **1.9.0**, and a
`material3:1.12.0` does not exist (404). In 1.9.0 `MotionScheme`,
`MaterialExpressiveTheme` and `.expressive()` are present but
**`internal`** (proven by Kotlin name mangling, `expressive$material3()`
in 1.9.0 against `expressive()` in 1.12.0-alpha03, and by a control pair
where identical source compiles clean against the alpha and emits 24
errors against 1.9.0), while `MaterialShapes`, `ButtonGroup`, the wavy
indicators and the FAB menu are absent entirely.

**Decision:** pin `org.jetbrains.compose.material3:material3:1.12.0-alpha03`.
It corresponds to androidx material3 1.5.0-alpha22, six alphas behind the
app's 1.5.0-alpha28 but in the same line.

**The fallback is cheap, which is why this is not a risk.** Without the
pin the exposure is **two files**: `settings/compose/SettingsTheme.kt`
(`MaterialExpressiveTheme` + `MotionScheme.expressive()`) and
`details/ui/ProgressComponents.kt` (`LinearWavyProgressIndicator`).
`ExperimentalMaterial3ExpressiveApi` appears in **0** source files; it is
one global compiler flag. The Phase 0 draft's fear that the whole theme
would need rewriting was wrong.

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

**Rationale:** porting all of it for a v1 that exposes a fraction is
waste, and the Android impl has to keep existing anyway for the existing
install base. Measured in Phase 1: `AppSettings` carries **205 keys across
177 properties**, and desktop v1 needs **50** of them (39 excluding
network and theme).

**`java.util.prefs` is struck as the backing store.** It has no set type
for the 15 `getStringSet` call sites, and an 8 KB per-value cap that two
of the app's JSON-blob preferences already exceed. A JSON file it is.

### D9. `Context` and `Uri` do not enter `:shared`

Narrow interfaces instead: `AppPaths`, `StringProvider`, `Notifier`,
`UriOpener`, `Logger`. Filesystem addressing is `okio.Path`, remote
addressing is `okhttp3.HttpUrl`.

**Rationale:** it matches the CLAUDE.md rule (business logic free of
platform types) and the existing coupling is shallow enough to make it
cheap - 26 `Context` and 7 `Uri` imports across all of `data/` + `domain/`.

### D10. Reader: Compose, tiled decode via ImageIO

**Revised twice. Phase 1 Agent C corrected the tiling premise; D22 then
cut every mode but webtoon. Neither superseded version is preserved
above; this is what stands.**

v1 ships **webtoon** only (D22).
Zoom and pan is a hand-written `Modifier.graphicsLayer` + `pointerInput`
transform. Full-image decode is Skia via Skiko. Region decode is
`javax.imageio.ImageReadParam.setSourceRegion`.

**What changed and why.** The original decision dropped tiled decoding on
the premise that no JVM region decoder exists. That premise was false.
`javax.imageio.ImageReadParam.setSourceRegion` is exactly
`BitmapRegionDecoder`'s contract, ships in the JDK, and needs no new
dependency. Measured here on JDK 21: a 2000x12000 PNG region-read of a
2000x1000 slice took 121ms, the same image as JPEG took 20ms, both
verified against a known-colour control pixel so the reader is provably
returning the requested region and not just any region.

Dropping tiling was also not survivable on its own terms.
`WebtoonImageView.onMeasure` clamps every webtoon page view to
`parentHeight()`, one screen, and `scrollToInternal` pans the SSIV centre
down the source. The webtoon reader is built *around* tiling, so removing
it invalidates the design rather than simplifying it. And the constraint
is not only memory: SSIV caps tiles at `Canvas.getMaximumBitmapWidth/Height`
because an oversized bitmap cannot be a single GPU texture, and desktop
GPUs have the same limit.

**The gap, stated honestly.** ImageIO's reader formats here are JPG, PNG,
TIFF, BMP, GIF and WBMP. **There is no WebP reader**, and
`PageLoader.kt:341` sends `Accept: image/webp,image/png;q=0.9,image/jpeg`,
so WebP is the format sources are actively encouraged to return. WebP
therefore needs either full-image Skia decode with downsampling (the
original fallback, now the exception rather than the rule) or a WebP
ImageIO plugin. Resolving this is the first task of the reader work.

**On the modes.** Agent C argued vertical mode was nearly free at 16
lines against webtoon's 1242, and it was, but D22 removed it along with
the paged modes anyway: cheap to build is not the same as wanted.

### D11. Background work is coroutines, not a scheduler

A supervisor scope owned by the app, plus a `Scheduler` interface for the
periodic jobs. No OS-level scheduling (no systemd timers, no cron) in v1.

**Rationale:** WorkManager's value is surviving process death and doze,
neither of which exists on desktop.

### D12. Packaging is `packageDeb` via Compose `nativeDistributions`

**Confirmed empirically in Phase 1**, not assumed. A real
`dropsauce-probe_1.0.0_amd64.deb` was produced on this machine: **53.5 MB**
packed, 137 MB installed, **106 files under `lib/runtime/`** including a
55 MB `lib/modules`, and a `Depends:` line listing only C libraries
(`libc6`, `libfreetype6`, `libx11-6`, ...) with **no JVM dependency**, so
the bundled runtime is genuinely self-contained. `dpkg-deb` and
`fakeroot` are both present here. AppImage stays out of v1.

### D13. "Wayland" means XWayland, and the docs will say so

Compose for Desktop renders into an AWT window. On a Wayland session that
is XWayland. Native Wayland needs a JDK with a Wayland toolkit, which is
not something this port can deliver.

**Rationale:** stating it is more useful than a checkbox that implies
something untrue. It will run on a Wayland desktop; it will not be a
native Wayland client. **Confirmed empirically:** the probe app launched
and held a window for 90 seconds over XWayland on this machine.

### D14. Migrations move to `:shared`, rewritten once against `SQLiteConnection`

**Revised after Phase 1 Agent A. The original version said migrations
would not be ported and desktop would ship fresh at v37; that rested on a
false dichotomy and is not preserved above.**

All 36 migrations move to `:shared`, rewritten against
`androidx.sqlite.SQLiteConnection`. `DatabasePrePopulateCallback` moves
with them.

**Why the original reasoning was wrong.** It assumed a migration must be
written against either `SupportSQLiteDatabase` (Android) or
`SQLiteConnection` (KMP). Verified by disassembling both published
artifacts: the **Android** `Migration` declares *both* `migrate(SupportSQLiteDatabase)`
and `migrate(SQLiteConnection)`, while the **JVM** `Migration` declares
only `migrate(SQLiteConnection)`. So one migration written against
`SQLiteConnection` compiles and runs on both platforms. The rewrite is
mechanical: all 36 migrations together are 523 lines containing 89
`execSQL(String)` calls, **zero** bind arguments and **no other `db.*`
call of any kind**.

**And the prepopulate callback is not optional.**
`DatabasePrePopulateCallback` seeds the "Read later" favourite category
in `onCreate`, and that row is not in the v37 `CREATE` statements. A
fresh desktop database would come up with zero categories, and
`LocalBackupRepository:433-442` then deletes the one a restore brings in,
matching on the *localized* title. Favourites are in v1 scope, so this
would have been a real data bug shipped on day one.

### D14a. A live hazard in the Android app, discovered during this recon

This is not a desktop decision, it is a warning about `:app` and it
belongs on the record.

Android's `Migration.migrate(SQLiteConnection)` base implementation
checks `instanceof SupportSQLiteConnection`; if the connection is not one,
it throws `kotlin.NotImplementedError("Migration functionality with a
provided SQLiteDriver requires overriding the migrate(SQLiteConnection)
function.")`. Verified in the bytecode of `room-runtime-android:2.8.4`.

All 36 of this app's migrations override only the `SupportSQLiteDatabase`
overload. So **if `:app` ever gains a `setDriver(...)` call**, which is
exactly what adopting a KMP Room setup invites, every existing user hits
`NotImplementedError` on database upgrade while `:app:assembleDebug` stays
perfectly green. Rewriting the migrations against `SQLiteConnection`
(D14) removes the hazard as a side effect. Until that lands, `setDriver`
must not appear in `:app`.

### D15. Backup/restore stays the Android/desktop interop path

Not Google Drive sync (`play-services-auth` is Android-only), not raw
database file copying. Note one known wrinkle from Agent C:
`ReaderState.scroll` is stored in view pixels at SSIV's fit scale, so
webtoon intra-page position will not round-trip between the two apps even
through a backup. Chapter-level position will.

### D19. Desktop does its own image loading, not Coil

Coil 3 supports JVM desktop and was the obvious choice. It is not used
because Coil centres on one shared `ImageLoader` with one HTTP client,
while covers and pages must be fetched through the *originating parser's*
client so the source's own headers apply. Desktop instead fetches with
OkHttp and decodes with Skia (`org.jetbrains.skia.Image`), behind a small
bounded `ImageCache`. One fewer dependency, and the client stays per
source.

### D20. RESOLVED: page 404s were a cold cache, not our client

**This decision recorded two wrong diagnoses before the right one. Both
are kept, because the way each was ruled out is the useful part.**

**Wrong diagnosis 1 (mine).** A control showed `parserClient=404` against
`bareClient=200` on the identical url, so I concluded our per-parser
client was at fault and specifically that installing the parser as an
OkHttp interceptor broke image requests.

**Wrong diagnosis 2 (the downloads agent's).** Reading the jar, it found
that for MangaDex the interceptor cannot be the cause, and proposed the
shared cookie jar instead. Verified independently: `MangaDexParser`
overrides neither `intercept` nor `getRequestHeaders`, and
`FlexibleMangaParser.intercept` disassembles to exactly
`chain.proceed(chain.request())`. A no-op passthrough cannot break
anything, so diagnosis 1 was dead.

**The actual cause**, from a live 2x2 control that also re-ran the first
client last:

```
page 7
  full client            = 404
  no parser interceptor  = 200
  no cookie jar          = 200
  bare client            = 200
  full client, with dump = 200
  full client, again     = 200
```

The same full client that 404s first succeeds later on the same url.
**Whichever client asks first eats the 404**, so the earlier control was
order-confounded: the full client was simply always first. MangaDex@Home
nodes 404 a page they have not cached yet and serve it once warm.

**Fix:** `PageImage` retries with a backoff (4 attempts, 400ms times the
attempt number) instead of hammering immediately. Nothing about the
client changes, and the separate image client that diagnosis 1 called
for is not built, because it would have fixed nothing.

**The lesson worth keeping:** a control that varies one thing but always
in the same order is not a control. The line that cracked this was
re-running the *first* configuration *last*.

### D21. Local comics may have several chapters, detected from structure only

Reverses an earlier judgment, with evidence rather than preference,
which is the bar a reversal has to clear.

The first local-library pass returned exactly one chapter per file,
reasoning that a CBZ records no chapter boundaries and that inferring
them from filenames guesses wrong on every scanlation naming scheme.
That reasoning is still correct **for a flat archive**, and filename
inference is still refused.

It was wrong as a blanket rule. A folder of chapter sub-folders was not
being treated conservatively, it was **unrepresentable**: `expand`
returned it unchanged, `listDirectoryPages` reads the top level only,
and the import failed outright with "folder contains no images". A dead
layout is not a safe default.

So layout is now detected from **structure the container actually
records** (an entry's parent directory), never from names. Detection is
a pure function returning `Detected`, `Ambiguous` or `NotAComic`, and it
returns `Ambiguous` precisely in the cases where only filenames could
decide. The user confirms the detected layout and chapter count before
anything is written, the single-chapter reading stays available as an
explicit option wherever it is representable, and it remains the only
option when detection is ambiguous.

### D22. The desktop reader is webtoon only

Paged left-to-right, paged right-to-left and vertical are gone from the
desktop UI, on your instruction. Webtoon is the only mode the reader
offers and the only one the settings screen exposes.

`ReadingMode` itself stays in `:shared`. It is the `manga_prefs.reading_mode`
column and it is in the backup format, so deleting the enum would either
break restore from an Android backup or silently drop a column the phone
still reads. The desktop simply never writes anything but `Webtoon`.

### D23. Chapters are one continuous strip, not one screen each

Reaching the end of a chapter used to replace the reader screen with a
new one for the next chapter. That is a visible break, and you asked for
chapters one and two to run together without noticing the change.

The reader therefore holds a flat list of pages that spans chapters. Each
entry carries the chapter it came from, so "the current chapter" is a
property of the page under the scroll position rather than an argument
the screen was opened with. Three pages before the end of what is loaded,
the next chapter is fetched and appended. Appending to the end of a
`LazyColumn` does not move the scroll position, which is what makes the
join invisible.

Consequences worth knowing:

- Progress, the bookmark button and the title in the bar all read from
  the page in view, so they follow the reader across a join. Resuming
  from History lands in the chapter you actually stopped in.
- A chapter that fails to load is reported **under** the strip, not in
  place of it. Replacing the screen with an error would throw away the
  position in the pages already being read.
- The `LazyColumn` uses default index keys, not page ids. The strip only
  grows at the end, so indices are stable anyway, and page ids are not
  unique: sources reuse one image URL across chapters, and a duplicate
  key is a crash rather than a glitch.
- Stepping back above the first loaded page still swaps the screen, since
  nothing is prepended. It opens the previous chapter at its **last**
  page (`LAST_PAGE`), so reading backwards over that join lands where the
  story continues.

**The watcher is a snapshot flow, not an effect key.** The first version
keyed the prefetch `LaunchedEffect` on the scroll position, which meant
every page that scrolled past restarted it and cancelled the fetch in
flight. Scrolling steadily towards the end of a chapter, the exact motion
this feature exists for, could therefore never finish loading the next
one, and `runCatching` turned the cancellation into "could not load the
next chapter: the coroutine scope left the composition" under the strip.
Two rules came out of it: the watcher keeps one coroutine and reads the
position through `snapshotFlow`, and `CancellationException` is rethrown
rather than reported.

**How this was caught.** The first verification pressed a key and waited
for the fetch each time, so it never overlapped a fetch with a scroll and
passed on a build that was broken in ordinary use. Driving the same UI
with keys 250ms apart reproduced the failure immediately. A reader
feature has to be tested at reading speed.

Verified live on MangaDex, scrolling continuously: chapter 0 (10 pages)
joined chapter 1 (+18) and then chapter 2 (+16), strip 44 pages, the bar
moving "prologue 10 / 10" -> "Prólogo 1 / 18" -> "Prólogo 18 / 18" with
no screen change; History resumed at the second chapter.

### D24. The library's length filter owns its own counts

The filter reads `manga.chapters_count`. Every writer of that column was a side
effect of visiting one title: opening its details, reading it, tracking it. That
is enough for a library grown inside this app and nothing at all for one that
arrived whole, and a restore is how a real library arrives: `BackupRepository`
already documents that an Android backup does not carry the field.

Measured on a real library before the fix: 359 favourites, 358 of them at zero,
so every bucket except "Not loaded" was empty. The filter was not wrong, it had
nothing to read. 118 of those had the true count sitting in `history.chapters`,
written by the reader and never copied onto the title.

Three writers now, in order of what they cost:

1. `MangaDao.backfillChaptersCountFromHistory`, run on every start. Local,
   idempotent, free. Fills in 117 rows on that library.
2. The existing per-title side effects, unchanged.
3. `ChapterCountRefresher`, started by a button on the library screen, scoped to
   the selected category, four requests at a time. The only thing that can learn
   the count of a title nobody has opened, which on a shelf kept for unread
   titles is most of it: of 190 in "Marinate", 9 were answerable locally.

The refresher is a button and not something the screen does when it opens.
Two hundred requests to third-party sites is not a thing to start on the user's
behalf, and D11 already rules out anything scheduled.

A count of zero is never stored. "The source listed nothing" and "nobody has
asked" are different states and the filter shows them the same way, so a fetch
that comes back empty is reported as failed and offered again.

The screen says how many titles have no count yet, in the bar and in the empty
state. "Nothing matches" and "nothing has been counted" look identical
otherwise, and the second one was the actual bug.

Filtering a category is not the goal, refiling it is: the filter is how you find
the titles in "Marinate" that have passed 100 chapters, and moving them is the
point. That action reuses `CurateRepository.moveToCategory`, which is already
one transaction, rather than growing a second implementation of it.

### D16. No new dependency is added without appearing in this file first

Planned for v1, each already justified above:

| Coordinate | For |
|---|---|
| `org.jetbrains.compose` Gradle plugin 1.12.0 | Compose for Desktop |
| `androidx.sqlite:sqlite-bundled:2.8.0-alpha01` | desktop SQLite driver for Room KMP (this exact version is the one proven in the D6 gate build) |
| `org.jetbrains.compose.material3:material3:1.12.0-alpha03` | Expressive APIs, see D6a |
| ~~`org.graalvm.polyglot:js`~~ | **Struck for v1.** See D17. |
| ~~`org.json:json`~~ | **Not needed as an explicit dependency.** It arrives transitively with the parsers jar; the instruction is to stop applying the app's `exclude group: 'org.json'`, not to add a coordinate. |
| `com.google.dagger:dagger` + `dagger-compiler` | desktop DI (D7) |

### D17. `evaluateJs` is not implemented on desktop, and GraalJS is struck from v1

**This reverses the dependency choice in D16 and part of PORTING_NOTES §C.**

The plan budgeted GraalJS to serve `MangaLoaderContext.evaluateJs`.
GraalJS cannot serve it. Agent B disassembled all 5 call sites: **zero**
use the 1-argument `evaluateJs(script)` overload. All 5 use
`evaluateJs(url, script)`, and the script bodies run *inside that URL's
loaded page*, reading `window.localStorage.getItem(...)` and
`new URLSearchParams(window.location.search).get(...)`. That needs a DOM,
an origin and a navigation. GraalJS has none of them, so it would not be
a partial implementation, it would be a non-implementation.

**Decision:** desktop's `evaluateJs` throws a clean typed
`UnsupportedOperationException`, matching how the library's own
`requestBrowserAction` default behaves (verified: it throws
`UnsupportedOperationException("Browser is not available")`, it does not
return `Void` as the Phase 0 draft said). No new dependency.

**Blast radius, measured not guessed:** by reflecting over all 1270
sources, `evaluateJs` affects **11**, and `requestBrowserAction` affects
**2** (`KOHARU`, `ZENMANGA`). `redrawImageResponse` / `createBitmap`
affect **13**, and those two *are* implemented, with
`BufferedImage` + `Graphics2D`, which is sufficient because
`parsers.bitmap.Bitmap` exposes only `getWidth`, `getHeight` and
`drawBitmap` with no pixel access.

GraalJS returns as a dependency question when D2 (LNReader plugins) is
picked up in v1.1. That is a different problem: those plugins need
Promises and a bridged `fetch`, not a DOM.

### D18. The catalogue is smaller than 1270 and the UI must say so

Agent B measured `isBroken == true` on **380 of 1270** sources, 30% of
the catalogue, and a random 12-source live sample of the *non*-broken
remainder had roughly 4 fully working.

So the sources screen must filter `isBroken` by default and must not
present 1270 as the number of usable sources. Sequencing note: this needs
deciding before the catalogue UI is designed, not after.

---

## 2. Desktop v1 scope

### In

- Browse the `kotatsu-parsers` catalogue: enable and disable sources,
  with `isBroken` sources filtered by default (D18)
- Per-source list browsing with sort orders and filters
- Search within a source
- Manga details: cover, description, tags, chapter list
- Read a chapter: webtoon, zoom and pan, keyboard and mouse navigation,
  continuous across chapters (D22, D23)
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
| ~~EPUB reading~~ | **No longer cut.** Implemented in the local import area: container.xml to OPF spine to NCX, with a text reader. It did not need the deferred novel-source runtime (D2) after all, because a file on disk needs no JS plugin host. |
| Google Drive sync | `play-services-auth` is Android-only |
| ~~Scrobbling: AniList, MAL, Kitsu, Shikimori, MangaBaka~~ | **No longer cut.** Built in batch two; `TrackingRepository` pushes progress from the reader |
| Discord Rich Presence | KizzyRPC is an Android library |
| ~~Downloads for offline reading~~ | **No longer cut.** Built in batch one; downloaded chapters open in the reader from the Downloads screen |
| Chapter-update tracker | **Partly cut.** The tracker is built and shows updates in-app; there are no desktop notifications and no periodic background check, so it refreshes when asked |
| ~~Suggestions~~ | **No longer cut.** Built in batch two as recommendations, with a related-titles strip on details |
| ~~Statistics~~ | **No longer cut.** Built in batch one |
| Text to speech | `android.speech.tts`; Linux needs speech-dispatcher |
| App lock, biometric or PIN | no threat model on a desktop session |
| AVIF pages | no JVM decoder exists; fails with a clear error, not a stub |
| WebP page decoding via the region decoder | ImageIO has no WebP reader; WebP falls back to full-image Skia decode with downsampling (D10) |
| The 11 sources needing `evaluateJs` and the 2 needing `requestBrowserAction` | D17, both throw a typed exception |
| Interactive Cloudflare solving | needs an embedded browser (KCEF, ~100 MB into the .deb). Sources behind an active challenge will fail with a clear error. |
| Home screen widgets, app shortcuts, Shizuku | Android platform concepts |
| Double-page and reversed-double reader modes, page animations, upscaling | reader polish, and moot under D22: webtoon has no pages to pair |
| Colour filters and the tap grid | editors exist and persist; the reader does not read them. See "Built but not connected" |
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

**Gap found during Phase 1:** `list/ui/` (56 files) was assigned to no
agent, and Agent D's dependency-inversion finding puts it on the critical
path for step 2 above. It needs covering before Phase 2 planning closes.

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
