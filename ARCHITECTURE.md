# ARCHITECTURE.md

How DropSauce is actually built today, as of `desktop-port` branch point
(`7310639`). Everything here was read out of the tree, not recalled.

DropSauce is a fork of [Kotatsu](https://github.com/KotatsuApp/Kotatsu)
(`org.koitharu.kotatsu` namespace, GPL-3.0) with the Mihon/Tachiyomi
extension runtime and the LNReader novel-plugin runtime grafted on.
Application id is `org.haziffe.dropsauce`.

## 1. Module layout

Single Gradle module. Groovy DSL, version catalog in
`gradle/libs.versions.toml`.

```
settings.gradle        -> include ':app' only
build.gradle           -> plugin aliases, all `apply false`
app/build.gradle       -> the entire build
app/lnreader-libs/     -> esbuild source for a checked-in JS bundle (not built by Gradle)
```

Toolchain actually in use:

| Thing | Version |
|---|---|
| Gradle wrapper | 9.6.1 |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.3.21 |
| KSP | 2.3.6 |
| compileSdk / targetSdk | 37.0 / 37 |
| minSdk | 26 |
| Java source/target | 11 (with core library desugaring) |
| Compose BOM | 2026.09.00, material3 pinned to 1.5.0-alpha28 |
| Room | 2.8.4 |
| OkHttp | 5.3.2 |

Build types: `debug`, `preview`, `release`. `preview` folds
`src/release/kotlin` and `src/release/res` into its own source set.
Lint runs with `abortOnError = true` and `warningsAsErrors = true`, with a
long explicit disable list.

### Verified baseline

`./gradlew :app:assembleDebug` was run on this machine at the branch
point before any change: **BUILD SUCCESSFUL in 11m 51s**, 47 tasks,
producing a 40.5 MB `app/build/outputs/apk/debug/app-debug.apk`.
Environment: OpenJDK 21.0.12.1, Android SDK with platform 37.0 and
build-tools 37.0.0, `local.properties` pointing at `~/Android/Sdk`
(gitignored). Gradle prints one deprecation warning about Gradle 10
compatibility; it does not fail the build.

## 2. Source tree size

| | count |
|---|---|
| Kotlin files (all source sets) | 1199 |
| Kotlin files (`main`) | 1145 |
| Kotlin LOC | ~133k total, ~127k in `main` |
| XML files | 690 |
| `res/layout*/*.xml` | 157 |
| Java files | 0 |

Three source roots under `app/src/main/kotlin`:

- `org/koitharu/kotatsu/` - the app (1145 files)
- `eu/kanade/tachiyomi/` - vendored Mihon compat surface (50 files)
- `tachiyomi/core`, `tachiyomi/domain` - more Mihon compat

Top-level packages under `org.koitharu.kotatsu`, by file count:

```
core 336   settings 104   reader 80   list 63   app 62   details 57
scrobbling 56   favourites 41   local 36   tracker 32   search 27
filter 24   download 23   main 22   backup 20   widget 19   explore 17
stats 16   history 15   mihon 13   suggestions 11   bookmarks 11
sync 10   browser 8   alternatives 8   picker 7   lnreader 7
kotatsumigration 6   image 5   extensions 5   remotelist 3
```

Most features follow a `data/` + `domain/` + `ui/` split. That split is
real and it matters for porting: across every feature,
`data/` + `domain/` is 182 files and `ui/` is 499 files.

## 3. Dependency injection

Hilt / Dagger 2.59.2, KSP-processed. Three `@Module`s only:

- `core/AppModule.kt` - database, Coil `ImageLoader`, `WorkManager`,
  `NetworkState`, the two `LocalStorageCache` instances (pages, favicons),
  activity lifecycle callback set, DB invalidation observer set
- `core/network/NetworkModule.kt`
- `scrobbling/ScrobblingModule.kt`

Everything else is constructor injection with `@Inject` / `@Singleton` /
`@Reusable`, plus `@AssistedInject` (17 sites). 66 `@HiltViewModel`s.

`@ApplicationContext` is injected into a large number of classes including
ones that are otherwise pure logic. There is also a custom
`@LocalizedAppContext` qualifier wrapping
`ContextCompat.getContextForLanguage`.

## 4. Database

Room 2.8.4, schema exported to `app/schemas`.

- `core/db/MangaDatabase.kt`, `DATABASE_VERSION = 37`
- 16 `@Entity` classes, 15 `@Dao` interfaces, 36 migration classes in
  `core/db/migrations/`
- `core/db/MangaQueryBuilder.kt` builds `SupportSQLiteQuery` for the
  dynamic library/filter queries; several DAOs expose `@RawQuery`

Entities: `MangaEntity`, `TagEntity`, `MangaTagsEntity`, `ChapterEntity`,
`MangaPrefsEntity`, `MangaSourceEntity`, `HistoryEntity`,
`FavouriteEntity`, `FavouriteCategoryEntity`, `TrackEntity`,
`TrackLogEntity`, `SuggestionEntity`, `BookmarkEntity`,
`ScrobblingEntity`, `StatsEntity`, `LocalMangaIndexEntity`.

DAOs live next to their feature (`favourites/data/FavouritesDao.kt` etc),
not all in `core/db`.

`InvalidationTracker.Observer`s are multibound through Hilt
(`AppShortcutManager`, `WidgetRefreshObserver`).

## 5. Preferences

`core/prefs/AppSettings.kt`, 1501 lines, a single god-object wrapping
`SharedPreferences` from `PreferenceManager.getDefaultSharedPreferences`.
Change notification is via `observeChanges` -> a `Flow` over
`OnSharedPreferenceChangeListener`. Enum values are stored as strings
through `getEnumValue` / `putEnumValue` helpers.

Per-source settings are a second layer: `core/prefs/SourceSettings.kt`.

## 6. Networking

`core/network/`:

- `HttpClients.kt` + `NetworkModule.kt` build the OkHttp stack
- interceptors: `CommonHeadersInterceptor`, `CacheLimitInterceptor`,
  `RateLimitInterceptor`, `GZipInterceptor`, `CloudFlareInterceptor`
- `DoHManager` / `DoHProvider` for DNS-over-HTTPS
- `proxy/ProxyProvider`, `SSLUtils`
- cookies: `AndroidCookieJar` (wraps `android.webkit.CookieManager`),
  `PreferencesCookieJar`, `MutableCookieJar`, `CookieWrapper`
- `imageproxy/` - optional third-party image proxies (wsrv.nl, 0.ms)
- `webview/WebViewExecutor` - runs a headless `WebView` to clear
  Cloudflare challenges; `webview/adblock/` is a small rule engine

OkHttp 5.3.2 with brotli, zstd, tls and dnsoverhttps modules.

## 7. Source / extension mechanism

This is the part that diverges most from upstream Kotatsu, and it is the
single most important thing about porting this app.

`core/parser/MangaRepository.kt` defines the `MangaRepository` interface
(getList / getDetails / getPages / getPageUrl / getChapterHtml /
getFilterOptions / getRelated / ...) and a `MangaRepository.Factory` that
dispatches on source type. `core/model/MangaSource.kt` resolves a stored
source name string to a source object.

There are exactly **three** source backends, and **the native
kotatsu-parsers site catalogue is not one of them**:

1. **`LOCAL`** - `local/data/LocalMangaRepository.kt`. CBZ / ZIP
   directories / EPUB on disk.
2. **`MIHON_*`** - Mihon (Tachiyomi) extension **APKs**.
   `mihon/MihonExtensionLoader.kt` enumerates installed packages through
   `PackageManager`, filters on the `tachiyomi.extension` /
   `tachiyomi.novelextension` feature, reads `.class` / `.factory` /
   `.nsfw` manifest metadata, verifies APK signatures, and loads the
   source class through `ChildFirstPathClassLoader` (dex).
   `extensions/install/` installs and updates those APKs, optionally via
   Shizuku. `MihonMangaRepository` adapts a Tachiyomi `CatalogueSource`
   to `MangaRepository`.
3. **`LN_*`** - LNReader novel plugins, which are JavaScript.
   `lnreader/LnPluginManager.kt` scans `filesDir/` for installed plugins;
   `lnreader/js/JsHost.kt` runs all of them in **one shared headless
   `WebView`** realm. Plugin `fetch` is bridged back into the app's OkHttp
   through a `@JavascriptInterface`, so the app's UA / DoH / proxy /
   Cloudflare handling still applies. The JS libraries plugins
   `require()` (cheerio, htmlparser2, dayjs, noble-ciphers, a hand-rolled
   urlencode) are pre-bundled and checked in at
   `app/src/main/assets/lnreader-libs.js` (426 KB), with the host shim at
   `assets/lnreader-host.js`. `app/lnreader-libs/entry.mjs` is the esbuild
   input, regenerated by hand.

The `com.github.YakaTeam:kotatsu-parsers` dependency **is** still present,
but the app consumes only its **model and util** classes: `Manga`,
`MangaChapter`, `MangaPage`, `MangaSource`, `MangaListFilter`,
`SortOrder`, `ContentType`, `org.koitharu.kotatsu.parsers.util.*`. There is
no `MangaLoaderContext` implementation anywhere in the tree, so
`newParserInstance` is never called and none of the ~3200 bundled site
parser classes are reachable at runtime.

The vendored `eu.kanade.tachiyomi.*` / `tachiyomi.*` packages exist purely
to satisfy what extension APKs link against: `Source`, `CatalogueSource`,
`HttpSource`, the RxJava 1 `Observable` surface, `NetworkHelper`,
`JavaScriptEngine` (QuickJS), Injekt, `ChildFirstPathClassLoader`.

## 8. Image loading and the reader

Coil 3.4.0 is the image pipeline. `AppModule.provideCoil` assembles it:
`MihonImageFetcher` first (so extension covers go through the extension's
own client and headers), then the OkHttp network fetcher, then
animated/GIF, SVG, `CbzFetcher`, `AvifImageDecoder`, `FaviconFetcher`,
`MangaPageKeyer`, `MangaPageFetcher`, the image-proxy interceptor,
`CoverRestoreInterceptor` and `MangaSourceHeaderInterceptor`.

The reader (`reader/`, 80 files) is entirely View-based:

- `ReaderActivity` + `ReaderViewModel`, with a `BaseReaderFragment`
  subclass per mode: `PagerReaderFragment`, `ReversedReaderFragment`,
  `VerticalReaderFragment`, `DoubleReaderFragment`,
  `ReversedDoubleReaderFragment`, `WebtoonReaderFragment`
- three modes are `ViewPager2` (standard, reversed, vertical); three are
  `RecyclerView` (double, reversed-double, webtoon). `fragment_reader_double.xml`
  is a plain `RecyclerView` + `DoublePageLayoutManager`. Webtoon is a custom `RecyclerView`
  (`WebtoonRecyclerView`, `WebtoonLayoutManager`, `WebtoonFrameLayout`,
  `WebtoonScalingFrame`, `WebtoonGapsDecoration`)
- zoom/pan/tiling is `subsampling-scale-image-view` (SSIV, Kotatsu's
  fork), referenced from 17 files
- `reader/domain/PageLoader.kt` fetches page bytes through OkHttp or the
  repository hook, caches to `LocalStorageCache`, hands an SSIV
  `ImageSource` to the holder. Real decoding happens inside SSIV's decoder
  factories (`ReaderSettings.kt:81-82`); `BitmapDecoderCompat` is only the
  recovery path after SSIV fails (`PageLoader.convertBimap`), and
  `RegionBitmapDecoder` is a **Coil** decoder for thumbnails, scrub previews
  and the colour-filter screen, not part of the reader page pipeline
- `core/image/BitmapDecoderCompat.kt` decodes via `ImageDecoder` /
  `BitmapFactory` / `BitmapRegionDecoder`, with AVIF through the
  `org.aomedia.avif.android` native decoder
- `reader/ui/epub/EpubReaderFragment.kt` (1985 lines, the largest file in
  the repo) is the novel reader, rendering chapter HTML into a `Spanned`
- `reader/ui/tts/` is text-to-speech over `android.speech.tts`
- tap zones (`reader/ui/tapgrid/`), colour filters, an upscale effect,
  a scroll timer

## 9. Background work

WorkManager 2.11.2 with `androidx.hilt:hilt-work`. Workers:

`DownloadWorker`, `TrackWorker`, `SuggestionsWorker`, `SyncWorker`,
`PeriodicalBackupWorker`, `ExtensionUpdateWorker`,
`LocalStorageCleanupWorker`.

Separately, 12 `<service>` entries in the manifest, including foreground
services for import, local index update, chapter removal, TTS playback,
and the Shizuku installer.

## 10. UI: XML vs Compose

The app is mid-migration. XML still carries the shell and every list.

XML side:
- 157 layouts (plus `layout-land`, `layout-w600dp-land`, `layout-w840dp`)
- 41 `<activity>` manifest entries, 42 `*Activity` classes, 61 `*Fragment`
  classes plus 17 bottom-sheet/dialog classes
- `viewBinding = true`
- RecyclerView lists are built with AdapterDelegates
  (`com.hannesdorfmann:adapterdelegates4`), used in 51 files
- custom views in `core/ui/widgets/`, a custom `FastScroller`,
  `core/ui/list/decor/` item decorations
- navigation is manual: `core/nav/AppRouter.kt`, 958 lines of intent and
  fragment-transaction construction. No Navigation component.
- ~90 locale `values-*` directories

Compose side: 69 files contain `@Composable`. Concentrated in:
- **settings** (`settings/compose/*` plus most `*SettingsFragment`s, which
  are fragments hosting `ComposeView`)
- **details** (`DetailsExpressiveScreen`, `HeroSectionComponents`,
  `ActionDockComponents`, `ProgressComponents`, ...)
- **stats** (`StatsScreen`, `StatsComponents`)
- the floating bottom nav (`main/ui/nav/FloatingNavBar.kt`)
- onboarding, app-lock screen, several dialogs and bottom sheets
- `filter/ui/mihon/MihonFilterContent.kt`

Compose import volume across the tree: `androidx.compose.foundation` 717,
`androidx.compose.ui` 670, `androidx.compose.runtime` 351,
`androidx.compose.material3` 299. Material 3 **Expressive** APIs
(`MotionScheme`, `MaterialShapes`, `ButtonGroup`, wavy progress, FAB menu)
are opted into globally in `app/build.gradle` and used throughout, which
is why material3 is pinned to the `1.5.0-alpha*` line rather than the BOM
version.

The reader, the library/favourites/history/explore lists, search, and the
whole navigation shell are still XML.

## 11. Everything else worth knowing

- **Backup/restore**: own zip format (`backup/`), plus
  `MihonBackupManager` (620 lines) reading Mihon's protobuf backups.
- **Sync**: `sync/domain/GoogleDriveSyncRepository.kt` (869 lines) over
  `play-services-auth`, plus an Android `SyncAdapter`/`ContentProvider`.
- **Scrobbling**: 56 files, six services (AniList, MAL, Kitsu, Shikimori,
  MangaBaka, Discord RPC via KizzyRPC). OAuth flows go out to a browser
  and come back through an intent filter.
- **Tracker**: polls followed manga for new chapters, posts notifications.
- **Widgets**: 19 files of `AppWidgetProvider` home-screen widgets.
- **App lock**: `main/ui/protect/`, biometric + PIN.
- **Crash reporting**: ACRA 5.13.1 with a dialog reporter.
- **Shizuku**: privileged silent APK install for extensions.
- **Migration**: `kotatsumigration/` imports an existing Kotatsu install.

## 12. The three facts that decide the port

1. **`kotatsu-parsers` is a pure JVM jar.** Its Gradle module metadata
   declares `org.gradle.jvm.environment = standard-jvm`,
   `libraryelements = jar`, `platform.type = jvm`, JVM 11. Dependencies
   are jsoup, kotlin-stdlib, `kotlinx-coroutines-core-jvm`, `okhttp-jvm`,
   `okio-jvm`, `org.json`, `androidx.collection-jvm`. The jar contains
   3213 site-parser classes. It runs on desktop as-is.
   `MangaLoaderContext` is a 7-method abstract class
   (`getHttpClient`, `getCookieJar`, `evaluateJs` x2, `getConfig`,
   `getDefaultUserAgent`, `redrawImageResponse`, `createBitmap`) and the
   library already abstracts bitmaps behind its own
   `parsers.bitmap.Bitmap` interface rather than `android.graphics`.

2. **Both remote source backends the app actually ships are
   Android-bound.** Mihon extensions are dex inside APKs, discovered
   through `PackageManager`; LNReader plugins run in a `WebView`.

3. **Room 2.8.4 and androidx.sqlite 2.7.x publish JVM (KMP) artifacts.**
   `room-runtime-jvm:2.8.4` and `androidx.sqlite:sqlite-bundled` both
   exist, so the 37-version schema and its 36 migrations can move to
   desktop on the same Room version the Android app is already using.
