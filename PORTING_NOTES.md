# PORTING_NOTES.md

Android-framework and third-party dependency inventory, with the desktop
replacement for each. Companion to `ARCHITECTURE.md`; scope decisions live
in `DECISIONS.md`.

Counts are file counts in `app/src/main/kotlin` unless stated otherwise.

## A. How coupled is the code, really

**These numbers were wrong in the Phase 0 draft and are corrected here.**
The original grep looked only for `android.*` / `androidx.*` /
`com.google.android.*`, which misses three forms of Android coupling that
Phase 1 Agent D found are more common in `domain/` code than any of them:

- `import org.koitharu.kotatsu.R` (356 files in `main`). Domain enums such
  as `ListSortOrder`, `ListFilterOption` and `SourcesSortOrder` carry
  `@StringRes`/`@DrawableRes` Ints and are read by the library DAOs.
- `BuildConfig`, which does not exist in a KMP `jvm()` source set.
  `local/data/MangaIndex.kt:70-71` writes it into every `index.json`.
- `printStackTraceDebug`, used by **87 files** and declared *only* in
  `src/debug/.../Debug.kt:5` and `src/release/.../Debug.kt:6` (the release
  one compiles to `Unit`). Neither source set exists in a KMP target, so
  87 files fail to resolve it on a move. No import scan catches this
  because it is resolved by source set, not by import.

Counting all six signals:

| Slice | Files | Genuinely free of Android coupling |
|---|---|---|
| all of `main` | 1145 | **270** (24%) |
| every `data/` + `domain/` dir | 182 | **72** (40%, not the 49% first reported) |
| every `ui/` dir | 500 | **64** (13%) |

24% sounds bad and is misleading. The `android.*` imports that dominate
`data/` and `domain/` are shallow:

```
26 android.content.Context      11 androidx.room.withTransaction
11 androidx.room.ColumnInfo     10 androidx.room.Entity
 8 androidx.room.Query           8 androidx.room.ForeignKey
 8 androidx.room.Dao             8 androidx.core.net.toUri / toFile
 8 androidx.annotation.StringRes 7 androidx.core.content.edit
 7 android.net.Uri               5 androidx.sqlite.db.SupportSQLiteQuery
 3 android.util.Log              3 android.content.SharedPreferences
```

Room annotations are multiplatform. `androidx.annotation` is
multiplatform. What actually has to go is `Context`, `Uri`,
`SharedPreferences`, `Log`, `DocumentFile`, and the `SupportSQLite*`
raw-query API.

The `ui/` number is honest: that code is genuinely Android UI and is
rewritten, not ported.

## B. Bucket (a): portable as-is

Runs on desktop JVM with no change beyond a source-set move.

| What | Where | Note |
|---|---|---|
| `kotatsu-parsers` models and utils | dependency | `Manga`, `MangaChapter`, `MangaPage`, `MangaSource`, `MangaListFilter`, `MangaListFilterCapabilities`, `MangaListFilterOptions`, `SortOrder`, `ContentType`, `parsers.util.*`. Pure JVM jar. |
| `kotatsu-parsers` site catalogue | dependency | 3213 parser classes, unused today. See bucket (b) for the host context it needs. |
| kotlinx-coroutines | dependency | `-core` is multiplatform; only `-android` must go. |
| kotlinx-serialization (json, json-okio, protobuf) | dependency | multiplatform |
| OkHttp 5.3.2 + brotli/zstd/tls/dnsoverhttps, Okio | dependency | JVM native |
| jsoup | dependency | JVM native |
| xmlutil | dependency | swap `core-android` for `core-jvm` |
| Room entities, DAOs, migrations | `core/db`, `*/data` | Room 2.8.4 publishes `room-runtime-jvm`. Annotations are unchanged. Exceptions in bucket (b). |
| Enum/value prefs types | `core/prefs/*.kt` except `AppSettings`/`SourceSettings` | `ColorScheme`, `ListMode`, `ReaderMode`, `NetworkPolicy`, `TriStateOption`, ... plain enums |
| Domain use cases and mappers | `*/domain` | the android-free half of the 182 |
| `core/util/*` non-ext helpers | `core/util` | **Only `iterator/`.** The Phase 0 draft also listed `FileSize`, `MimeTypes` and `progress/` here and all three were wrong: `FileSize.kt:3` imports `Context` (and `R`), `MimeTypes.kt:4` imports `android.webkit.MimeTypeMap`, and 3 of the 6 files in `progress/` are Android. |
| Interceptors without Android types | `core/network` | `RateLimitInterceptor`, `GZipInterceptor`, `CommonHeaders*`, `CacheLimitInterceptor`, `DoHProvider`, `imageproxy/*` |
| Backup model/serialisation | `backup/` | the zip+json format itself; the file picking is not |
| Scrobbling API clients | `scrobbling/*/data` | 31 of 56 scrobbling files are already android-free |
| Most Compose composables | `settings/compose`, `details/ui`, `stats/ui` | see bucket (b) for the specific snags |

## C. Bucket (b): needs a desktop implementation behind an interface

### Platform types that leak everywhere

| Android thing | Sites | Desktop replacement |
|---|---|---|
| `android.content.Context` | ~500 imports of `android.content`, injected as `@ApplicationContext` | Delete from shared code. Split into narrow interfaces: `AppPaths` (data/cache/config dirs), `StringProvider`, `ClipboardService`, `Notifier`, `UriOpener`. Desktop supplies XDG-based impls. |
| `android.net.Uri` | 72 `android.net` imports, plus `androidx.core.net.toUri` / `toFile` | `okio.Path` for filesystem, `okhttp3.HttpUrl` for remote, a small `ContentRef` sealed type where the app genuinely mixes both (local CBZ page addressing uses a `file+zip://<abs-path>#<entry>` scheme, `core/util/ext/Uri.kt:8`, a constant the SSIV fork also declares itself). |
| `SharedPreferences` (`AppSettings`, 1501 lines) | 1 god object, read from ~200 places | Interface `Settings` with the same property names, backed on desktop by `java.util.prefs` or a JSON file + `StateFlow`. `observeChanges` becomes a `MutableSharedFlow<String>` of changed keys. Android keeps the SharedPreferences impl. |
| `android.util.Log` | 82 `android.util` imports | tiny `Logger` interface, `println`/SLF4J on desktop |
| `androidx.documentfile.DocumentFile`, SAF, `OpenDocumentTreeHelper` | `local/`, `settings/storage` | plain `java.io.File` + an AWT/Compose file chooser. SAF has no desktop analogue and does not need one. |
| `android.graphics.Bitmap` / `Rect` / `Color` | `core/image`, reader, parsers descrambling | Skia (`org.jetbrains.skia.Image`, via Compose's bundled Skiko) for decode; `parsers.bitmap.Bitmap` already has a platform-free interface to implement. |

### Subsystems needing a real desktop implementation

| Subsystem | Android today | Desktop |
|---|---|---|
| **Parser host context** | nothing (never instantiated) | New `DesktopMangaLoaderContext : MangaLoaderContext`. Needs: OkHttp client, a `CookieJar`, `evaluateJs` (two overloads), `getConfig(source)`, `getDefaultUserAgent()`, `redrawImageResponse`, `createBitmap`. This is the single highest-value piece of new code in the port. |
| **JS engine** | QuickJS (`app.cash.quickjs`, Mihon compat) and a headless `WebView` (LNReader) | **Not needed for v1.** The parser catalogue's `evaluateJs` cannot be served by GraalJS at all: all 5 call sites use the `(url, script)` overload and the scripts read `window.localStorage` and `window.location.search`, so they need a real loaded page. Desktop throws a typed `UnsupportedOperationException` instead, costing 11 of 1270 sources. See DECISIONS.md D17. GraalJS returns for D2 (LNReader) in v1.1, where the requirement is Promises plus a bridged `fetch` rather than a DOM, and `TextEncoder`/`TextDecoder` shims map cleanly onto `java.nio.charset`. |
| **Cloudflare / interactive challenge** | a full Activity in `browser/cloudflare/` (**not** `WebViewExecutor`, which is 25 lines returning a User-Agent string) + `AndroidCookieJar` over `android.webkit.CookieManager` | No WebView. Options are JCEF/KCEF (~100 MB, drags a Chromium into the .deb) or no interactive solve at all. See `DECISIONS.md`. Cookie jar becomes a plain persistent OkHttp `CookieJar` on disk. |
| **Image decoding** | `ImageDecoder` / `BitmapFactory` / `BitmapRegionDecoder`, AVIF via `org.aomedia` native | Skia via Skiko for full-image decode. For **region/tiled** decode, Skiko has no equivalent but **the JDK does**: `javax.imageio.ImageReadParam.setSourceRegion` is `BitmapRegionDecoder`'s contract with no new dependency. Measured on this machine (JDK 21): a 2000x12000 PNG region-read of 2000x1000 took 121ms, the same JPEG 20ms, both verified against a positive control pixel. Reader formats available are JPG, PNG, TIFF, BMP, GIF, WBMP. **No WebP**, and `PageLoader.kt:341` sends `Accept: image/webp,...` so WebP is the *preferred* wire format. AVIF: no JVM decoder ships with Skiko. |
| **Zoomable/tiled page view** | `subsampling-scale-image-view` (17 files) | Hand-written Compose: `Modifier.graphicsLayer` + `pointerInput` transform gestures, with a downsample-on-load strategy instead of true tiling. |
| **HTTP image pipeline** | Coil 3.4.0 with 11 custom components | Coil 3 is multiplatform and supports JVM desktop. The custom fetchers/keyers/interceptors port; `MihonImageFetcher` does not (no Mihon). |
| **Background work** | WorkManager + 7 workers + 12 foreground services | Plain coroutines on a supervisor scope owned by the app, plus a `DesktopScheduler` interface for the periodic ones (tracker, suggestions, backup). No OS-level scheduling in v1. |
| **Notifications** | `NotificationManager`, 12 services posting progress | `java.awt.SystemTray` / libnotify via `notify-send`, or in-app only. |
| **DI** | Hilt (Android-only by construction) | Hilt cannot target desktop JVM. Options: plain Dagger 2 (JVM-capable, same annotations, KSP), or manual constructor wiring in a composition root. See `DECISIONS.md`. |
| **ViewModels** | `androidx.lifecycle` + `@HiltViewModel` x66 | `androidx.lifecycle:lifecycle-viewmodel` publishes KMP artifacts; alternatively a plain `CoroutineScope`-owning class. The 66 VMs are mostly pure logic over flows. |
| **Navigation** | `AppRouter.kt`, 958 lines of intents and fragment transactions | Rewrite. A sealed `Screen` type + a back stack in Compose state. Nothing to port. |
| **Resources / i18n** | `res/values-*` x90, `stringResource`, `@StringRes` | Compose Multiplatform resources (`org.jetbrains.compose.components:resources`), which can consume the existing `strings.xml` files. |
| **Raw SQL queries** | `MangaQueryBuilder` -> `SupportSQLiteQuery`, 8 `@RawQuery` methods + 5 query-construction sites | Room KMP drops the `SupportSQLite*` API entirely (verified: 0 `SupportSQLite*` classes in `room-runtime-jvm:2.8.4`). `MangaQueryBuilder` itself is a 2-line port because it emits a complete SQL string with **no bind arguments**: `SimpleSQLiteQuery(it)` becomes `RoomRawQuery(it)`. The real problem is `android.database.DatabaseUtils.sqlEscapeString` (8 sites across 5 DAOs), which is the only thing making that string concatenation safe and which has no JVM equivalent. |
| **`withTransaction`** | `androidx.room.withTransaction`, **50 call sites across 14 files** | Room KMP's `useWriterConnection { it.immediateTransaction { ... } }`. Effort is per-lambda, not per-file. |
| **`InvalidationTracker.Observer`** | multibound in `AppModule`, used at `MangaDatabase.kt:173` | **Does not exist on JVM.** Verified: `room-runtime-jvm:2.8.4`'s `InvalidationTracker` has no nested `Observer`; it exposes `createFlow(tables, emitInitialState)` instead. Any code touching it cannot move to `:shared` unchanged. |

## D. Bucket (c): Android-only, not ported

| Feature | Why |
|---|---|
| **Mihon / Tachiyomi extension APKs** | Extensions are dex inside APKs, discovered via `PackageManager`, loaded via `ChildFirstPathClassLoader`, signature-verified through Android's `PackageInfo`. A JVM host would need dex2jar at install time *plus* a reimplementation of the Android APIs extensions link against (`Application`, `SharedPreferences`, `Uri`, `Bundle`, preference screens). Drops: `mihon/` (13), `extensions/` (5), `eu/kanade/tachiyomi/` (50), `tachiyomi/` compat, RxJava, Injekt, QuickJS, unifile. |
| **Shizuku silent install** | Android privileged-service IPC. Meaningless off-device. |
| **Home screen widgets** | `widget/` (19 files), `AppWidgetProvider`. No analogue. |
| **App shortcuts** | `core/os/AppShortcutManager`, `ShortcutManager`. |
| **Google Drive sync** | `play-services-auth` is Android-only. Drive itself has a REST API, so this is *re-implementable*, not impossible, but it is a separate project. Drops `sync/` (10 files) and the `SyncAdapter`/`ContentProvider`. |
| **Discord Rich Presence (KizzyRPC)** | Android library. Discord IPC over a unix socket is easy on Linux, but it is a different implementation. |
| **Biometric app lock** | `androidx.biometric`. PIN-only is portable; fingerprint is not. |
| **Text to speech** | `android.speech.tts`. Linux would need `speech-dispatcher`. |
| **ACRA crash reporting** | Android-only. Desktop writes a crash log file. |
| **Screenshot policy, battery optimisation prompts, `RomCompat`, boot receiver, foreground services** | Android platform concepts. |
| **AVIF page decoding** | `org.aomedia.avif.android` is an Android AAR with native `.so`. No drop-in JVM decoder. AVIF pages fail with a clear error rather than a stub. |
| **AdapterDelegates, ViewBinding, all 157 XML layouts, 42 activities, 61 fragments, SSIV, Markwon, Material Components** | Android View toolkit. |

## E. Dependency table, `libs.versions.toml` -> desktop

Read off `gradle/libs.versions.toml` at `7310639`. "keep" means the same
coordinate resolves for a JVM target; "swap" means a different artifact
in the same family.

| Current | Desktop |
|---|---|
| `com.github.YakaTeam:kotatsu-parsers` | **keep** (pure JVM jar). Do **not** re-apply the app's `exclude group: 'org.json'`: Android ships `org.json` in the platform, the JVM does not. |
| `kotlinx-coroutines-core` | keep |
| `kotlinx-coroutines-android`, `-guava` | drop `-android`; `-guava` only if Guava stays |
| `kotlinx-serialization-*` | keep |
| `okhttp`, `okhttp-brotli`, `-zstd`, `-tls`, `-dnsoverhttps`, `okio` | keep |
| `org.jsoup:jsoup` | keep |
| `xmlutil-core` (`core-android`) | **swap to the root KMP artifact `io.github.pdvrieze.xmlutil:core`**, not `core-jvm`. At the app's pinned 0.91.3 there is no `core-jvm` publication (its versions jump 0.90.0-RC3 -> 1.0.0); the JVM variant at 0.91.3 is published as **`core-jvmcommon`** (`env=standard-jvm`, `type=jvm`) and is reached through the root module's Gradle metadata. |
| `xmlutil-serialization` | keep |
| `androidx.room:room-runtime` / `-ktx` / `-compiler` | **swap** to `room-runtime` KMP + `androidx.sqlite:sqlite-bundled`. Note `room-ktx:2.8.4` is an **empty shim**: its `classes.jar` holds one 6-byte version marker and nothing else, so dropping it changes nothing. `withTransaction` actually lives in `room-runtime`'s *Android* source set, so it is the room-runtime **variant** that must change. `room-runtime-jvm:2.8.4` is **404 on Maven Central** and only on Google Maven, so the desktop module needs `google()` in its repositories. |
| `coil3` core/compose/network-okhttp/gif/svg | keep (Coil 3 supports JVM desktop) |
| `androidx.compose.*` + BOM | **swap** to `org.jetbrains.compose` (CMP) **1.12.0**, confirmed latest stable. |
| `androidx.compose.material3:1.5.0-alpha28` (Expressive) | **Resolved. CMP's material3 is version-decoupled from CMP itself:** `compose.material3` in CMP 1.12.0 resolves to material3 **1.9.0**, and `org.jetbrains.compose.material3:material3:1.12.0` is a 404. In 1.9.0 the Expressive APIs are present but **`internal`** (proven by name mangling: `expressive$material3()` in 1.9.0 vs `expressive()` in 1.12.0-alpha03) and `MaterialShapes`/`ButtonGroup`/wavy/FAB-menu are absent. Pinning `material3:1.12.0-alpha03` makes them public; it corresponds to androidx material3 1.5.0-alpha22, six alphas behind the app's 1.5.0-alpha28. See D6a. |
| `androidx.graphics:graphics-shapes` | **keep**, `graphics-shapes-desktop` is published (1.1.0) |
| `androidx.lifecycle:lifecycle-viewmodel` | KMP artifacts exist; `-service`, `-process` are Android-only |
| `com.google.dagger:hilt-android` + `androidx.hilt:hilt-work` | **drop.** Hilt has no desktop target. |
| `androidx.work:work-runtime` | drop, replaced by coroutines |
| `androidx.appcompat`, `core-ktx`, `activity`, `fragment`, `transition`, `constraintlayout`, `recyclerview`, `viewpager2`, `swiperefreshlayout`, `preference`, `documentfile`, `biometric`, `webkit`, `window` | drop |
| `androidx.collection` | keep (`collection-jvm` exists; the parsers jar already pulls it) |
| `com.google.android.material:material` | drop |
| `adapterdelegates4` | drop |
| `subsampling-scale-image-view` | drop, replace with Compose gesture code |
| `org.aomedia.avif.android:avif` | drop, no replacement |
| `com.github.solkin:disk-lru-cache` | publishes **AAR variants only**, so a JVM target cannot resolve it, but its `classes.jar` bytecode contains **zero** `android/*` references (checked against a positive control that does find `java/io/File`). Pure JVM code in Android packaging: vendor the ten classes, or replace with an okio LRU. |
| `io.noties.markwon` | drop (Android `Spanned`). **Not** novel rendering: its only consumers are `settings/about/AppUpdateActivity.kt` and the changelog screen. The novel reader uses `HtmlCompat.fromHtml` + Jsoup + `StaticLayout`. |
| `com.github.dead8309:KizzyRPC` | drop |
| `play-services-auth` | drop |
| `dev.rikka.shizuku:*` | drop |
| `com.github.tachiyomiorg:unifile` | drop |
| `com.github.null2264.injekt` | drop (Mihon compat only) |
| `com.github.zhanghai.quickjs-java:quickjs-android` | **swap** to GraalJS |
| `io.reactivex:rxjava` | drop (Mihon compat only) |
| `com.google.guava:guava` (`-android` flavour) | swap to the JRE flavour if still needed after WorkManager goes |
| `ch.acra:acra-dialog` | drop |
| `com.squareup.moshi` (androidTest only) | drop |
| `desugar_jdk_libs` | drop (JVM 17+ target) |

## F. New dependencies desktop needs

None of these are in the current catalogue. Each is a decision, recorded
in `DECISIONS.md` before any is added.

| Need | Candidate |
|---|---|
| Compose for Desktop runtime + packaging | `org.jetbrains.compose` Gradle plugin 1.12.0 |
| Desktop SQLite driver | `androidx.sqlite:sqlite-bundled` |
| Logging | `org.slf4j:slf4j-simple`, or none |
| Cloudflare interactive solve (if taken) | `dev.datlag:kcef` |

## G-0. The dependency arrow points the wrong way

Phase 1 Agent D's most structural finding, and it invalidates any naive
"shared domain first" sequencing: **`domain` depends on `ui`, not the
reverse.** `list/domain/MangaListMapper.kt:16-23` imports
`core/ui/model/MangaOverride`, `core/ui/widgets/ChipsView` and four
`list/ui/model/*` types; `list/domain/MangaListQuickFilter.kt:9-10` does
the same. `ListFilterOption` carries drawable ids.

So `list/ui/` (56 files) is on the critical path for moving `list/domain`,
and `DECISIONS.md` D5 step 2 ("`:shared` with models, enums, pure domain")
cannot execute as written until those mappers are inverted. The fix is
refactoring **inside** `:app` first, which is cheap to gate: every step is
an ordinary Android change verified by `:app:assembleDebug`.

## G. Things that will bite

**Items 1, 2 and 6 of the Phase 0 draft were tested in Phase 1. Two were
overstated and one was simply wrong; they are corrected here rather than
left standing.**

1. ~~Compose Material 3 Expressive on CMP~~ **Overstated.** The draft
   said that if CMP's material3 lacks the Expressive APIs, "every
   portable Compose screen needs its theme rewritten". Measured: the
   worst case is **two files**. `ExperimentalMaterial3ExpressiveApi`
   appears in **0** source files (it is one global compiler flag in
   `app/build.gradle`), and real Expressive symbol usage is confined to
   `settings/compose/SettingsTheme.kt` (`MaterialExpressiveTheme`,
   `MotionScheme.expressive()`) and `details/ui/ProgressComponents.kt`
   (`LinearWavyProgressIndicator`). `MaterialShapes` appears only inside
   a comment; `FloatingActionButtonMenu` and `CircularWavyProgressIndicator`
   appear nowhere; the `ButtonGroup` hits are
   `com.google.android.material.button.MaterialButtonGroup` (an Android
   View) and the app's own private `ThemeButtonGroup` composable. The
   real constraint is different and is now D6a.
2. ~~Kotlin 2.3.21 vs CMP 1.12.0~~ **Refuted, there is no gap.** Kotlin
   2.3.21 + `kotlin.plugin.compose` 2.3.21 + CMP 1.12.0 + Room 2.8.4 KMP
   + KSP 2.3.6 + `sqlite-bundled` 2.8.0-alpha01 + AGP 9.2.1 all compiled
   in one build with both `jvm()` and android targets, config cache
   stored. The actual blocker is AGP, not Kotlin: see item 7.
3. **Room KMP and the 36 migrations.** Resolved, see DECISIONS.md D14.
4. **`org.json`.** Corrected: it arrives **transitively** with the
   parsers jar, so the instruction is "stop excluding it", not "add it".
5. **`configuration-cache = true` and `parallel = true`.** Tested clean:
   the CMP, KSP and Room plugins all store and reuse the configuration
   cache with zero problems.
6. ~~Lint is `warningsAsErrors = true`, so a shared-module change breaks
   the gate~~ **Refuted with a positive control:** `:app` does not
   analyse `:shared` at all. The real risk is the inverse, that
   `:shared` gets no runnable lint task and is simply unchecked. A
   separate live risk does exist though: `AndroidGradlePluginVersion`
   combined with `warningsAsErrors` can fail the gate on its own the day
   a newer Gradle ships, with no change from us.
7. **AGP 9 forbids the obvious module setup, and this is the largest
   structural constraint found.** Since AGP 9.0, `com.android.library`
   combined with `kotlin.multiplatform` is a hard error. `:shared` must
   apply `com.android.kotlin.multiplatform.library`, and that plugin's
   documented `androidLibrary { }` block is *already deprecated* at AGP
   9.2.1 + KGP 2.3.21 in favour of `kotlin { android { } }`.
8. **Silent Java target divergence.** Left unpinned, `:shared`'s android
   bytecode compiles to Java 21 (major 65) while `:app` is on Java 11,
   and `:app:assembleDebug` still passes. Pin the toolchain explicitly.
3. **Room KMP and the 36 migrations.** Migrations use
   `SupportSQLiteDatabase`. Room KMP migrations use `SQLiteConnection`.
   All 36 need mechanical conversion, and the desktop app has no existing
   installs to migrate, so an alternative is to ship desktop at schema
   version 37 with no migration history at all.
4. **`org.json`.** The app excludes it because Android provides it. The
   parsers library uses it at runtime. Desktop must include it.
5. **`configuration-cache = true` and `parallel = true`** are on in
   `gradle.properties` and apply to the whole build. A badly-written
   desktop module will break configuration cache for the Android build
   too.
6. **Lint is `warningsAsErrors = true`.** Any shared-module change that
   introduces a lint warning breaks `:app:assembleDebug`, which is the
   gate.
