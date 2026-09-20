# C-reader.md

Phase 1 Agent C. Read-only recon of the reader and image subsystems, to
de-risk `DECISIONS.md` **D10** (Compose reader, paged + webtoon, hand-written
zoom/pan, Skia decode, no tiling).

Scope actually read: `reader/**` (80 files), `core/image/**` (8 files),
`image/**` (5 files), `core/ui/image/**` (10 files),
`res/layout/*reader*`, `res/layout/*page*`, plus the SSIV artifact itself
(`~/.gradle/caches/.../subsampling-scale-image-view-376930523c-runtime.jar`)
and the non-owned files the pipeline passes through
(`local/data/LocalStorageCache.kt`, `core/AppModule.kt`,
`details/ui/pager/pages/MangaPageFetcher.kt`, `core/util/ext/Coil.kt`,
`core/util/ext/Uri.kt`, `core/util/ext/Android.kt`).

Repo state: branch `desktop-port`, HEAD `1d30c9b`, clean.

Every claim below cites `file:line`. Where I could not determine something
it says **unknown** and what would settle it.

Citations were machine-checked: all 204 fully-qualified `file:line`
references resolve to a real file and fall within its length, and the
load-bearing ones were re-read at the cited line. (An earlier draft had
line numbers shifted by concatenated `cat -n` output; those were corrected
in place, not appended.)

---

## 0. Corrections to the existing docs

`ARCHITECTURE.md` §8 is broadly right. Four things are wrong or misleading,
and one of them changes the shape of D10.

**C-1. "paged modes are `ViewPager2`" is wrong for two of the five paged
modes.** `fragment_reader_pager.xml:2` is a `ViewPager2`, used by standard,
reversed and vertical. But `fragment_reader_double.xml:2` is a plain
`androidx.recyclerview.widget.RecyclerView` with
`DoublePageLayoutManager` (`fragment_reader_double.xml:10`), used by
double and reversed-double. So the container split is ViewPager2 x3,
RecyclerView x3 (double, reversed-double, webtoon), not ViewPager2 x5.

**C-2. "`core/image/BitmapDecoderCompat.kt` decodes via `ImageDecoder` /
`BitmapFactory` / `BitmapRegionDecoder`" implies those three are the reader's
decode path. They are not.** `BitmapDecoderCompat.decode` is called from
exactly two places in the reader
(`reader/domain/PageLoader.kt:174`, `PageLoader.kt:184`), both inside
`convertBimap`, which is the *recovery* path taken only after SSIV has
already failed to decode the file (`reader/ui/pager/vm/PageViewModel.kt:95-111`).
The normal reader decode happens entirely inside SSIV's own decoder
factories, set at `reader/ui/config/ReaderSettings.kt:81-82`.
`BitmapDecoderCompat.createRegionDecoder` (`core/image/BitmapDecoderCompat.kt:62`)
has exactly one caller and it is not the reader (see C-3).

**C-3. `RegionBitmapDecoder.kt` is not in the reader page pipeline at all.**
It is a **Coil** `Decoder`, wired only through
`core/util/ext/Coil.kt:44-49` (`ImageRequest.Builder.decodeRegion`), whose
only two callers are `core/image/CoilImageView.kt:174-175` and
`image/ui/CoverImageView.kt:156`. In XML the `app:decodeRegion="true"`
attribute (`res/values/attrs.xml:24`) appears on four views:
`res/layout/item_page_thumb.xml:18` (the chapter page-grid thumbnail),
`res/layout/popup_reader_scrub_preview.xml:29` (the scrubber preview popup),
`res/layout/activity_color_filter.xml:60` and
`res/layout-w600dp-land/activity_color_filter.xml:63`. Its job is to crop a
*representative strip* out of a very tall image for a small thumbnail, not
to tile a page for reading. Naming it as part of the page pipeline (the
brief does) is a misread that I am correcting here, because it changes what
"no region decoder on desktop" costs: it costs thumbnails and the scrubber
preview, on top of what it costs the reader.

**C-4. SSIV's fork defines the `file+zip` scheme itself.**
`core/util/ext/Uri.kt:8` declares `const val URI_SCHEME_ZIP = "file+zip"`,
and the SSIV artifact declares the identical constant in its own
`internal/ConstantsKt` (`URI_SCHEME_ZIP = "file+zip"`, read out of the jar
with `javap -p -constants`). SSIV also exposes
`ImageSource.zipEntry(File, String)`. So the scheme is not an app-local
convention that the desktop port can rename freely; it exists because the
Kotatsu SSIV fork can region-decode a zip entry in place.
`PORTING_NOTES.md` section C describes it as "local CBZ page addressing uses
a `zip://` scheme", which understates it: the scheme is `file+zip`, not
`zip`, and its reason for existing is SSIV, which desktop is dropping.

---

## 1. The page pipeline, end to end

A single page, from `MangaRepository.getPages(chapter)` to pixels. Where the
bytes physically are is called out at each hop.

1. **`getPages(chapter)` -> `List<MangaPage>`.** Owned by Agent B. The
   reader receives them through `ChaptersLoader`
   (`reader/domain/ChaptersLoader.kt`) and wraps each in a `ReaderPage`
   (`reader/ui/pager/ReaderPage.kt`). A `MangaPage` carries `url`,
   `preview`, `source`, `id`. **Bytes: none yet, just strings.**

2. **The holder binds.** `BasePageHolder.bind(data)`
   (`reader/ui/pager/BasePageHolder.kt:107-114`) calls
   `viewModel.onBind(data.toMangaPage())`, which launches
   `PageViewModel.doLoad` (`reader/ui/pager/vm/PageViewModel.kt:50-56`,
   `:137`). State goes to `PageState.Loading(null, -1)`
   (`PageViewModel.kt:138`).

3. **Optional preview, via Coil.** In parallel,
   `PageViewModel.doLoad` launches `loader.loadPreview(data)`
   (`PageViewModel.kt:139-143`). `PageLoader.loadPreview`
   (`reader/domain/PageLoader.kt:135-146`) only runs if
   `page.preview` is non-empty, builds a Coil `ImageRequest` with a
   `TrimTransformation()` (`PageLoader.kt:143`), executes it, and converts
   the resulting Coil `Image` to an SSIV `ImageSource.cachedBitmap` /
   `ImageSource.bitmap` (`PageLoader.kt:304-308`).
   **Bytes: a decoded `Bitmap` in the JVM/native heap, plus whatever Coil
   put in its own disk cache at `externalCacheDir/image_cache`
   (`core/AppModule.kt:110-116`, `local/data/CacheDir.kt` `THUMBS`).**
   This is a *different* cache from the page cache in step 6.

4. **`PageLoader.loadPageAsync` -> `loadPageImpl`.**
   `PageLoader.kt:148-160` dedupes in-flight loads through a
   `LongSparseArray<ProgressDeferred<Uri, Float>>` keyed on `page.id`
   (`PageLoader.kt:97`). `loadPageImpl` takes one of 4 permits
   (`Semaphore(4)`, `PageLoader.kt:99`, commented as matching Mihon's
   four-page preload).

5. **Resolve the real image URL.** `loadPageImpl` first calls
   `getPageUrl(page)` -> `repository.getPageUrl(page)`
   (`PageLoader.kt:198-200`, `:261`) and hard-fails on blank
   (`PageLoader.kt:262`). **Bytes: still none.**

6. **Disk-cache hit check.** Unless `skipCache`,
   `cache.get(pageUrl)` (`PageLoader.kt:263-265`). This is the
   `@PageCache LocalStorageCache`, provided at
   `core/AppModule.kt:201-210`: `CacheDir.PAGES` -> directory `pages`
   (`local/data/CacheDir.kt`), **default 200 MB, floor 20 MB**
   (`AppModule.kt:208-209`), living under
   `context.externalCacheDirs + context.cacheDir`, first writeable
   (`local/data/LocalStorageCache.kt:37-42`), capped again at 80% of free
   space (`LocalStorageCache.kt:45-46`). Backing store is
   `com.tomclaw.cache.DiskLruCache` (`LocalStorageCache.kt:52`).
   **On a hit the result is a `File` on disk and the pipeline jumps to
   step 10 with a `file://` Uri.**

7. **Branch on URL scheme** (`PageLoader.kt:267-297`):
   - **`file+zip` (or a legacy `cbz:` / `zip:` scheme):** the Uri is
     returned as-is, normalised to `file+zip`
     (`PageLoader.kt:268-272`). `Uri.isZipUri()` is
     `core/util/ext/Uri.kt:15-17`. Nothing is extracted, nothing is copied.
     **Bytes: stay inside the CBZ/ZIP on disk.**
   - **`file`:** returned as-is (`PageLoader.kt:274`).
     **Bytes: already a file on disk.**
   - **anything else (http/https):** step 8.

8. **Network fetch, two paths.** `PageLoader.kt:275-295`:
   - **Extension path:** `repo.getImageStream(pageUrl, page)`
     (`PageLoader.kt:282`). A Mihon source's own `getImage()`, which does
     decryption/unscrambling inside the extension. Returns an OkHttp
     `Response`.
   - **Direct OkHttp path:** only when `getImageStream` returns null
     (`PageLoader.kt:283-290`). Builds the request with
     `PageLoader.createPageRequest` (`PageLoader.kt:330-345`), which sets
     `Accept: image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8`
     (`PageLoader.kt:341`), `Cache-Control: no-store`
     (`PageLoader.kt:343`), tags the `MangaSource` (`PageLoader.kt:344`),
     and prepends extension-supplied headers such as `Referer`
     (`PageLoader.kt:287`, `:340`). It then goes through
     `imageProxyInterceptor.interceptPageRequest(request, okHttp)`
     (`PageLoader.kt:289`), which is the optional wsrv.nl / 0.ms proxy.
   - Prefetch requests are additionally paced by
     `downloadSourceThrottler.pace(page.source)` (`PageLoader.kt:276-278`).
   **Bytes: streaming through an OkHttp `ResponseBody`.**

9. **Write to the page cache.** `PageLoader.kt:291-295`:
   `r.body.withProgress(progress)` wraps the body so download progress
   reaches the `MutableStateFlow<Float>`, then
   `cache.set(pageUrl, it.source(), contentType)`.
   `LocalStorageCache.set` (`local/data/LocalStorageCache.kt:64-80`) writes
   to a **UUID-named temp file in the cache dir's parent**
   (`LocalStorageCache.kt:112-118`), then `DiskLruCache.put` moves it in,
   then the temp file is deleted in a `finally`
   (`LocalStorageCache.kt:77-79`). Zero bytes received throws
   `NoDataReceivedException` (`LocalStorageCache.kt:70-72`).
   **Bytes: temp file, then cache file. The return value is a `File`,
   converted to a `file://` Uri at `PageLoader.kt:295`.**

10. **Optional edge trim.** Back in `PageViewModel.doLoad:151-155`, if
    `isPagesCropEnabled(isWebtoon)` (`reader/ui/config/ReaderSettings.kt:68-72`)
    the Uri goes to `PageLoader.getTrimmedBounds`
    (`PageLoader.kt:192-196`) -> `EdgeDetector.getBounds`
    (`reader/domain/EdgeDetector.kt:33`). `EdgeDetector` spins up its
    **own** `SkiaPooledImageRegionDecoder(Bitmap.Config.RGB_565)`
    (`EdgeDetector.kt:39`), decodes the whole image at a sample size
    derived from `calculateScaleFactor` (`EdgeDetector.kt:190-198`:
    1.0 at <=1024px, 0.75 at <=2048, 0.5 at <=4096, **0.25 above 4096**),
    scans four edges in parallel coroutines (`EdgeDetector.kt:53-60`) for
    near-white margins (tolerance 16, `EdgeDetector.kt:203`,`:214`),
    and returns a source-space `Rect`. Results are memoised in a
    24-entry sieve cache (`EdgeDetector.kt:31`, `:204`).
    **Bytes: one downsampled full bitmap, recycled at `EdgeDetector.kt:75`.**

11. **Hand off to SSIV.** `PageViewModel.kt:156` sets
    `PageState.Loaded(uri.toImageSource(cachedBounds), isConverted = false)`.
    `toImageSource` (`PageViewModel.kt:182-189`) is `ImageSource.uri(uri)`,
    optionally `.region(bounds)`. `BasePageHolder.onStateChanged`
    (`BasePageHolder.kt:228-233`) applies the bitmap config and calls
    `ssiv.setImage(state.source)`.
    **This is the point where the app stops managing bytes. From here SSIV
    owns decoding.**

12. **SSIV decodes and tiles.** Decoder factories were installed by
    `ReaderSettings.applyBitmapConfig` (`ReaderSettings.kt:74-87`):
    `AvifCapableRegionDecoder.Factory(config, isLowRamDevice)` and
    `AvifCapableImageDecoder.Factory(config)`. Those wrappers sniff the
    first 64 bytes for an ISO-BMFF `ftyp` + `avif`/`avis` brand
    (`core/image/AvifCapableDecoders.kt:29-33`, `:35-41`); on a hit they
    decode the whole image with the bundled software AVIF decoder
    (`AvifCapableDecoders.kt:43-47` -> `BitmapDecoderCompat.decode` ->
    `core/image/BitmapDecoderCompat.kt:91-107`) and then serve "regions"
    by `Canvas.drawBitmap` out of that one full bitmap
    (`AvifCapableDecoders.kt:109-118`). On a miss they delegate to
    SSIV's stock `SkiaPooledImageRegionDecoder`
    (or `SkiaImageRegionDecoder` on a low-RAM device,
    `AvifCapableDecoders.kt:83-87`), which is the genuine tiled path.
    **Bytes: for the tiled path, only the visible tiles, each capped to
    `Canvas.getMaximumBitmapWidth()/Height()` (verified by disassembling
    `SubsamplingScaleImageView.class`: it reads both and stores them into
    `maxTileWidth` / `maxTileHeight`). For the AVIF path, the entire
    decoded image in memory.**

13. **Ready / shown.** SSIV fires `onReady()` (each mode's holder sets
    scale and centre: `standard/PageHolder.kt:77-115`,
    `reversed/ReversedPageHolder.kt:37-77`,
    `doublepage/DoublePageHolder.kt:56-70`,
    `webtoon/WebtoonHolder.kt:36-48`) and then `onImageLoaded()`, which
    moves state to `PageState.Shown` (`PageViewModel.kt:85-93`).
    `BasePageHolder.kt:244` then applies the upscale effect.

14. **The recovery path, and the only place `BitmapDecoderCompat` runs.**
    If SSIV throws an `IOException` while decoding,
    `PageViewModel.onImageLoadError` (`PageViewModel.kt:95-111`) moves to
    `PageState.Converting` and calls `PageLoader.convertBimap(uri)`
    (`PageLoader.kt:167-190`), serialised behind a single global `Mutex`
    (`PageLoader.kt:100`). That:
    - for a `file+zip` Uri: opens the `ZipFile`, checks
      `context.ensureRamAtLeast(entry.size * 2)` (`PageLoader.kt:172`),
      decodes the entry with `BitmapDecoderCompat.decode(stream, mime)`
      (`PageLoader.kt:174`), and **writes the decoded bitmap back into the
      page cache as PNG** (`PageLoader.kt:178` ->
      `LocalStorageCache.set(url, bitmap)` at
      `local/data/LocalStorageCache.kt:82-93`);
    - for a `file` Uri: checks `ensureRamAtLeast(file.length() * 2)`
      (`PageLoader.kt:183`), decodes, and **overwrites the cache file in
      place with PNG** (`PageLoader.kt:186`, `image.compressToPNG(file)`).
    Then re-hands the Uri to SSIV. **Bytes: a full decoded bitmap plus a
    re-encoded PNG on disk, which for a large page is strictly bigger than
    the JPEG/WebP it replaced.**

15. **Prefetch.** `PageLoader.prefetch` (`PageLoader.kt:118-133`) pushes
    up to `PREFETCH_LIMIT_DEFAULT = 6` pages (`PageLoader.kt:327`) onto a
    `LinkedList` queue, drained by `onIdle()` (`PageLoader.kt:210-219`)
    when the in-flight counter hits zero. Gated by
    `isPrefetchApplicable()` (`PageLoader.kt:110-115`): repository must be
    a `CachingMangaRepository`, the setting on, not in power-save mode,
    and `ramAvailable > 80 MB` (`PageLoader.kt:300-302`, `:328`).

### Where the bytes live, summarised

| Stage | Location |
|---|---|
| page list | strings in memory |
| preview | decoded bitmap in memory + Coil disk cache `image_cache` |
| network body | OkHttp stream, never fully buffered by the app |
| cache write | UUID temp file in the *parent* of the pages dir, then DiskLruCache entry in `pages/` |
| local CBZ page | never extracted; addressed in place as `file+zip://<archive path>#<entry>` |
| reader decode | SSIV tiles, each <= `Canvas.getMaximumBitmapWidth/Height` |
| AVIF decode | whole image in memory, no tiling |
| convert fallback | whole image in memory, then re-encoded PNG replacing the cache entry |

### What the `zip://` scheme is actually for

It is **`file+zip`**, not `zip`. Defined at `core/util/ext/Uri.kt:8`, built
by `File.toZipUri(entryPath)` (`core/util/ext/Uri.kt:25`) as
`file+zip://<absolute archive path>#<entry path>`, with legacy `cbz:`/`zip:`
aliases accepted at `Uri.kt:15-17`. Producer:
`local/data/input/LocalMangaParser.kt:305-315` (`Uri.child`), which builds
one per image entry of a CBZ. Consumers:
1. **SSIV itself** - the Kotatsu fork declares the same `file+zip` constant
   internally and offers `ImageSource.zipEntry(File, String)`, so a page
   inside a CBZ is region-decoded without ever being extracted.
2. **Coil**, via `core/image/CbzFetcher.kt:23-32`, which does
   `okio.openZip(filePath)` and hands Coil an `ImageSource` on the zip
   filesystem.
3. **`PageLoader.convertBimap`** (`PageLoader.kt:168-179`) and the
   existence/emptiness probes (`PageLoader.kt:349-368`), which open a
   `java.util.zip.ZipFile` directly.
4. **The EPUB reader**, `reader/ui/epub/EpubReaderFragment.kt:1813`,
   `:1836`, `:1857`, `:1964`.

The point of the scheme is to avoid extracting CBZ entries to temp files.
On desktop only consumer 2 survives natively (okio `openZip` is
multiplatform and already used). Consumers 1 and 3 need replacing.

---

## 2. What SSIV is actually doing for us

`com.github.KotatsuApp:subsampling-scale-image-view` (coordinate at
`gradle/libs.versions.toml:130`), resolved version `376930523c`.
17 Kotlin files import `com.davemorrissey.labs.subscaleview`, plus 2
layouts reference the class directly
(`res/layout/item_page.xml:9`, `res/layout/activity_image.xml`).

### Every use, enumerated

| # | File | What it uses SSIV for |
|---|---|---|
| 1 | `reader/ui/pager/BasePageHolder.kt:16-17,45,55,70-74` | holds the abstract `ssiv`, implements `DefaultOnImageEventListener`, binds SSIV to the holder lifecycle, sets `isEagerLoadingEnabled` (`:71`), registers two event listeners |
| 2 | `reader/ui/pager/BasePageHolder.kt:97,128,136,283-290` | `downSampling` throttle: 1 foreground, else 4, or 8 on low-RAM, or 32 in debug |
| 3 | `reader/ui/pager/BasePageHolder.kt:104,232,240` | `ssiv.setImage(source)` - the one entry point for pixels |
| 4 | `reader/ui/pager/BasePageHolder.kt:153` | `ssiv.recycle()` on holder recycle |
| 5 | `reader/ui/pager/BasePageHolder.kt:248-276` | upscale: reads `ssiv.isReady`, `ssiv.sWidth`, `ssiv.width`, calls `ssiv.setRenderEffect` |
| 6 | `reader/ui/pager/standard/PageHolder.kt:77-115` | initial fit: `maxScale`, `minimumScaleType`, `minScale`, `resetScaleAndCenter`, `setScaleAndCenter`, `colorFilter` for all four `ZoomMode`s |
| 7 | `reader/ui/pager/standard/PageHolder.kt:148-157` | `getCenter()` + `animateScaleAndCenter(...).withDuration/withInterpolator/start()` for the zoom buttons |
| 8 | `reader/ui/pager/reversed/ReversedPageHolder.kt:37-77` | same as #6 with RTL anchor points |
| 9 | `reader/ui/pager/doublepage/DoublePageHolder.kt:41,56-77` | `panLimit = PAN_LIMIT_INSIDE`, centre-seam alignment from `sWidth * minScale` |
| 10 | `reader/ui/pager/standard/PagerEventSupplier.kt:11-23` | finds the child SSIV of the current page and forwards key events to `ssiv.dispatchKeyEvent` |
| 11 | `reader/ui/pager/webtoon/WebtoonImageView.kt:17` | **subclasses** `SubsamplingScaleImageView`; overrides `onMeasure`, `getSuggestedMinimumHeight`, `onDraw`, `onReady`, `onDownSamplingChanged`, `recycle`; implements its own `scrollTo`/`scrollBy`/`getScroll`/`getScrollRange` on top of `setScaleAndCenter` |
| 12 | `reader/ui/pager/vm/PageState.kt:3` + `vm/PageViewModel.kt:7-8,40,100,182-189` | `ImageSource` is the state type; `PageViewModel` *is* an `OnImageEventListener`; `ImageSource.region(rect)` carries the trim |
| 13 | `reader/ui/config/ReaderSettings.kt:7,75-86` | installs `regionDecoderFactory` and `bitmapDecoderFactory`, reads `regionDecoderFactory.bitmapConfig` to decide whether a reload is needed |
| 14 | `reader/domain/PageLoader.kt:18,135-146,192-196,304-308` | returns `ImageSource` from `loadPreview`; `getTrimmedBounds` takes an `ImageSource` |
| 15 | `reader/domain/EdgeDetector.kt:13-14,31,39,42,47` | uses `SkiaPooledImageRegionDecoder` **directly** as a standalone image reader, and `ImageSource` as the cache key |
| 16 | `reader/domain/UpscaleEffect.kt:8,38-65` | keeps `WeakReference<SubsamplingScaleImageView>` per page id; `viewportFor` reads `ssiv.scale`, `ssiv.isReady`, `ssiv.isShown`, `getGlobalVisibleRect`, `viewToSourceCoord` |
| 17 | `core/image/AvifCapableDecoders.kt:10-16,49-136` | implements SSIV's `ImageDecoder` / `ImageRegionDecoder` / `DecoderFactory` interfaces, delegating to `SkiaImageDecoder`, `SkiaImageRegionDecoder`, `SkiaPooledImageRegionDecoder` |
| 18 | `core/image/BitmapDecoderCompat.kt:10,89,95,104` | throws SSIV's `ImageDecodeException` |
| 19 | `core/util/ext/Throwable.kt` | maps `ImageDecodeException` to a user-facing message |
| 20 | `image/ui/ImageActivity.kt` + `res/layout/activity_image.xml` | the standalone full-screen image viewer (saved page / cover preview), a second independent SSIV host |

### Responsibilities, split

**(a) Tiled / region decoding of very large images.**
This is the load-bearing one. `SkiaPooledImageRegionDecoder` maintains a
pool of `BitmapRegionDecoder`s (`DecoderPool` in the jar) and SSIV decodes
only visible tiles, sized to `Canvas.getMaximumBitmapWidth()` /
`getMaximumBitmapHeight()` (verified in the disassembly of
`SubsamplingScaleImageView.class`; those two calls populate `maxTileWidth`
and `maxTileHeight`, and `TILE_SIZE_AUTO` is the default).
**Does desktop need it?** For paged mode, mostly no. For webtoon, see
section 8 - this is where D10 is exposed. **Replacement cost:** there is no
Skia region decoder in Skiko, so the honest options are (i) decode-whole +
downsample, (ii) pre-slice tall images into N horizontal strips at decode
time and hold them as N Skia `Image`s, (iii) go outside Skiko for a real
region decoder (libjpeg-turbo / libwebp via JNI or a pure-JVM
`ImageReader` with `ImageReadParam.setSourceRegion`, which `javax.imageio`
*does* support for JPEG and PNG). Option (iii) is not in
`PORTING_NOTES.md` and is worth pricing: `javax.imageio` ships in the JDK,
needs no new dependency, and `ImageReadParam.setSourceRegion` +
`setSourceSubsampling` is exactly `BitmapRegionDecoder`'s contract for
JPEG and PNG. It does not cover WebP, which is the format gap.

**(b) Zoom / pan / fling gestures.**
SSIV owns this for the paged modes (`isPanEnabled`, `isZoomEnabled`,
`isQuickScaleEnabled`, `doubleTapZoomScale`, `doubleTapZoomStyle`,
`panLimit`, plus `AnimationBuilder` / `animateScaleAndCenter`). The app
uses `PAN_LIMIT_INSIDE` only in double mode
(`DoublePageHolder.kt:41`); everywhere else the default applies.
**Webtoon does not use SSIV gestures at all**: `item_page_webtoon.xml:15-17`
sets `panEnabled="false" quickScaleEnabled="false" zoomEnabled="false"`,
and `WebtoonScalingFrame.kt` re-implements pinch
(`ScaleGestureDetector`, `:40`, `:255-258`), pan (`:297-307`), double-tap
zoom (`:309-320`), fling (`OverScroller`, `:42`, `:322-343`), ctrl+wheel
zoom (`:100-116`) and keyboard zoom (`:118-159`) by hand, over a
`Matrix`. **Does desktop need it?** Yes, and D10 already says so.
**Replacement cost:** `WebtoonScalingFrame` is a 357-line worked example
of exactly the hand-written transform D10 proposes, including the
non-obvious parts (clamping via `translateBounds`,
`MIN_SCALE = 0.5f` / `MAX_SCALE = 2.5f` at `:27-28`,
`FLING_RANGE = 20_000` at `:30`, and the child-relayout when scale < 1 at
`:191-196`). Port the algorithm, not the class.

**(c) Scale modes and initial fit.**
Four `ZoomMode`s (`core/model/ZoomMode.kt`), each mapped in each holder's
`onReady()`. `FIT_CENTER` -> `SCALE_TYPE_CENTER_INSIDE` + reset;
`FIT_HEIGHT` / `FIT_WIDTH` -> `SCALE_TYPE_CUSTOM` + an explicit `minScale`
and anchor point; `KEEP_START` -> centre-inside but start at `maxScale`
anchored to the corner (`PageHolder.kt:83-114`). `maxScale` is always
`2f * max(viewW/srcW, viewH/srcH)` (`PageHolder.kt:78-81`).
**Does desktop need it?** The four modes are ~25 lines of arithmetic per
mode. Trivially reimplementable. **Cost: near zero.**

**(d) Rotation.**
SSIV has `orientation` with `ORIENTATION_USE_EXIF` / `0` / `90` / `180` /
`270` and reads EXIF itself (`SubsamplingScaleImageView$initTiles$1$exifOrientation$1`
in the jar). **I found no call site in the app that sets
`ssiv.orientation`.** Grep for `subscaleview` across the 17 files turns up
no `orientation =` assignment on an SSIV instance. The reader's rotation
feature is `reader/ui/ScreenOrientationHelper.kt` (84 lines), which rotates
the *activity*, not the image. So SSIV's rotation support is unused, except
for whatever implicit EXIF handling the default gives. **Desktop need:
EXIF-orientation-correct decode only.** Skia's `Image.makeFromEncoded` does
**not** apply EXIF orientation, so this is a small real gap: a page served
with a non-default EXIF orientation would render rotated. **Unknown:**
whether any real source serves such pages; nothing in this repo indicates
either way. What would settle it: decode a sample of real pages from two
or three catalogue sources and check the EXIF orientation tag.

**(e) Everything else.**
- **`downSampling`** (`BasePageHolder.kt:283-290`): a Kotatsu-fork
  addition, not upstream SSIV. Drops off-screen / background pages to 1/4
  (1/8 low-RAM, 1/32 debug) resolution. This is the app's memory-pressure
  valve and desktop will want an equivalent.
- **`isEagerLoadingEnabled`** (`BasePageHolder.kt:71`): pre-decodes the
  base layer ahead of display, off on low-RAM devices.
- **`restoreStrategy="deferred"`** (`res/layout/item_page.xml:16`) and
  `ImageViewState`: SSIV serialises scale+centre and restores it after a
  config change. Desktop has no config change, but it has window resize.
- **Colour filter** (`ssiv.colorFilter`, set in each `onReady()`): the
  reader's brightness/contrast/invert/grayscale filter
  (`reader/domain/ReaderColorFilter.kt`, 98 lines) is applied by SSIV as an
  `android.graphics.ColorFilter`. Cut from v1 by `DECISIONS.md` §2, so no
  cost, but note it is a `ColorMatrix`, which maps directly onto a Skia
  `ColorFilter` if it is ever wanted back.
- **`setRenderEffect`** for the AGSL upscale shader
  (`BasePageHolder.kt:266`). Cut from v1.
- **`snapshot(Bitmap.Config)`**: exists in the API; **no call site found in
  the app**.
- **`ImageDecodeException`** is SSIV's, and the app's user-facing error
  mapping depends on it (`core/util/ext/Throwable.kt`). Dropping SSIV means
  defining an app-owned exception type.

### `RegionBitmapDecoder`, `WebtoonImageView`, `WebtoonScalingFrame` specifically

**`RegionBitmapDecoder.kt` (191 lines).** As established in C-3, this is
Coil, not SSIV, and not the reader. What it does is worth understanding
anyway because it encodes the app's own answer to "how do I show a 20000px
strip in a thumbnail": it does *not* downscale the whole thing. It picks a
crop rect matching the destination aspect ratio
(`RegionBitmapDecoder.kt:75-82`, with a comment
`// probably manga` for the tall case), offsets it either to the centre or
to a caller-supplied scroll position (`:83-94`, the
`regionScrollKey` extra, fed from `Bookmark.scroll` at
`image/ui/CoverImageView.kt:156`), and region-decodes just that.
It falls back to the next Coil decoder if `createRegionDecoder` returns
null (`:36-54`), which is the correct shape for desktop too.
**Desktop replacement:** either a centre-crop after a subsampled whole
decode (cheap, wrong for very tall images: you pay the full decode), or
`javax.imageio` `setSourceRegion` (correct, JPEG/PNG only). This affects
`item_page_thumb.xml` and the scrub preview, both of which are arguably out
of v1 scope anyway - but the *bookmark thumbnail* is not obviously out of
scope, and it is the one that genuinely needs a scroll-positioned region.

**`WebtoonImageView.kt` (144 lines).** This is the file that decides
whether D10 survives. Two things:
- `onMeasure` (`:75-97`) ends with
  `desiredHeight.coerceAtMost(parentHeight())` (`:95`), where
  `parentHeight()` is the enclosing `RecyclerView`'s height (`:127-129`).
  **So a webtoon page's view is never taller than one screen, no matter
  how tall the image is.**
- `scrollToInternal` (`:112-118`) pins `minScale = maxScale = width/sWidth`
  and moves the *SSIV's own centre point* down the source image. The
  "scroll" within a page is a pan of a tiled decoder over a strip that is
  never fully resident.
Combined: the webtoon reader shows a 15000px strip through a ~2000px
window that pans over it, and only the tiles under that window are ever
decoded. That is the entire memory model of webtoon mode, and it is
tiling-shaped.

**`WebtoonScalingFrame.kt` (357 lines).** The outermost layer of webtoon
mode (`fragment_reader_webtoon.xml:2`). It wraps the `WebtoonRecyclerView`
and applies a single `Matrix` scale+translate to it as a whole
(`:175-201`), so pinch-zoom zooms the *list*, not any one page. Notable
for the port: when `scale < 1` it grows the child's height to
`height / scale` and forces a relayout of every child
(`:191-196` -> `WebtoonRecyclerView.relayoutChildren()` at
`WebtoonRecyclerView.kt:139-146`), and after any scale it re-syncs every
child's internal scroll (`:270-278` ->
`WebtoonRecyclerView.updateChildrenScroll()` at
`WebtoonRecyclerView.kt:148-176`). Those two fix-ups exist because the
per-page internal scroll and the list scroll can drift apart. A Compose
reimplementation that uses one continuous scrollable instead of
list-of-panning-views does not need them, which is a simplification, but
only if it can afford to lay out the full-height strips.

The third piece is `WebtoonRecyclerView.consumeVerticalScroll`
(`:87-125`): it intercepts the list's nested pre-scroll and feeds the delta
into the first (or last) child's internal SSIV scroll *first*, spilling the
remainder into the next child and only then into the list itself. This is
the glue that makes N independently-panning one-screen views look like one
continuous strip. It is ~40 lines and it is subtle; if the Compose reader
keeps the "each page is its own viewport" model it has to reproduce this,
and if it uses one continuous `LazyColumn` of full-height items it does
not.

---

## 3. How big do pages actually get

### What the code enforces, quoted

| Guard | Where | Value |
|---|---|---|
| reader bitmap config | `reader/ui/config/ReaderSettings.kt:49-53` | `ARGB_8888` if `is32BitColorsEnabled` else `RGB_565`. `is32BitColorsEnabled` defaults **false** (`core/prefs/AppSettings.kt:1065-1066`), so the reader decodes at **2 bytes/pixel by default** |
| `downSampling` | `reader/ui/pager/BasePageHolder.kt:283-290` | `1` when foreground **or** optimisation off; `32` in debug; `8` on a low-RAM device; `4` otherwise. `isReaderOptimizationEnabled` defaults **false** (`AppSettings.kt:404-405`), so in a default install **this is always 1** |
| eager base-layer decode | `BasePageHolder.kt:71` | off on low-RAM devices only |
| SSIV tile ceiling | disassembly of `SubsamplingScaleImageView.class` | `maxTileWidth` / `maxTileHeight` are set from `Canvas.getMaximumBitmapWidth()` / `getMaximumBitmapHeight()`. Read at runtime, never hardcoded. This is the GPU texture limit. |
| `ensureRamAtLeast` | `reader/domain/PageLoader.kt:172`, `:183`; definition `core/util/ext/Android.kt:190-194` | throws `IllegalStateException("Not enough free memory")` if `ActivityManager.MemoryInfo.availMem < required`. Required is `entry.size * 2` or `file.length() * 2`, i.e. **twice the compressed size** |
| prefetch RAM floor | `PageLoader.kt:300-302`, `:328` | prefetch disabled below **80 MB** free |
| prefetch queue | `PageLoader.kt:327` | 6 pages ahead, drained only when nothing is in flight |
| in-flight page loads | `PageLoader.kt:99` | `Semaphore(4)` |
| page-cache size | `core/AppModule.kt:207-209` | 200 MB default, 20 MB floor, 80% of free space cap (`local/data/LocalStorageCache.kt:45-46`) |
| `allowRgb565` | `core/AppModule.kt:123` | Coil only, and only on a low-RAM device. Does **not** touch the reader path. |
| Coil `maxBitmapSize` | `core/image/AvifImageDecoder.kt:57` | Coil's own default, used only by the AVIF Coil decoder, not the reader |
| `EdgeDetector` sample factor | `reader/domain/EdgeDetector.kt:190-198` | `1.0` at <=1024, `0.75` at <=2048, `0.5` at <=4096, **`0.25` above 4096** |
| webtoon classification | `reader/domain/DetectReaderModeUseCase.kt:125`, `:76` | `MIN_WEBTOON_RATIO = 1.8`; a page is "webtoon" when `width * 1.8 < height`, majority vote over 3 sampled pages (`:114-121`) |
| upscale gate | `reader/domain/UpscaleEffect.kt:21` | `MIN_SCALE = 1.5f`, described in the source as "only pages whose native size is well below the screen" |

### The honest read on the two guards that look like limits but are not

**`ensureRamAtLeast` is not a dimension check and does not protect the
reader.** It is called only from `convertBimap`
(`PageLoader.kt:172`, `:183`), the post-failure recovery path, and it
budgets `2 x compressed bytes`. For a page that compresses well - which
webtoon line art does, aggressively - that estimate is wrong by more than
an order of magnitude. A 4 MB JPEG at 1200 x 20000 needs
`1200 * 20000 * 2 = 48 MB` at `RGB_565`, or 96 MB at `ARGB_8888`, against
a check that only demanded 8 MB. So the Android app has **no working
guard** against a huge decode; what saves it is that the huge decode
normally never happens, because SSIV tiles.

**`downSampling` is not a limit either.** It is off by default
(`isReaderOptimizationEnabled` false at `AppSettings.kt:405`) and, even
when on, only applies to backgrounded pages (`BasePageHolder.kt:285`:
`isForeground || !settings.isReaderOptimizationEnabled -> 1`).

### So: what is the realistic worst case?

**The code does not state one.** There is no maximum width, height, pixel
count or byte budget anywhere in `reader/**` or `core/image/**`. This is
**unknown** from the source, and it is unknown *by design*: the Android
architecture's answer to "how tall can a page be" is "arbitrarily, we
tile".

Three things the code *does* tell us, which bound the answer from below:

1. It expects images **above 4096 px** in at least one dimension, because
   `EdgeDetector.calculateScaleFactor` has a dedicated `else -> 0.25f`
   branch for exactly that (`EdgeDetector.kt:196-197`).
2. It expects aspect ratios **above 1.8:1** routinely enough to
   auto-switch reader mode on them (`DetectReaderModeUseCase.kt:125`).
3. It expects a single page to exceed the viewport by enough that
   `WebtoonImageView` needs its own internal scroll range
   (`WebtoonImageView.kt:51-57`, `getScrollRange()` returns
   `sHeight * width / sWidth - height`, clamped at 0). If pages fit the
   screen this whole mechanism would be dead code.

**What would settle it:** fetch 3-5 chapters from real webtoon sources in
the `kotatsu-parsers` catalogue and record `(width, height, encoded bytes,
format)` per page. That is ~30 lines against the parser host Agent B is
building, and it is the single measurement that turns D10 from a guess
into a decision. Until then, treat the following as arithmetic, not as
facts read out of this repo:

| Shape | Pixels | `RGB_565` | `ARGB_8888` |
|---|---|---|---|
| ordinary manga page 1600 x 2400 | 3.8 M | 7.7 MB | 15 MB |
| tall page 1200 x 8000 | 9.6 M | 19 MB | 38 MB |
| long strip 1200 x 20000 | 24 M | 48 MB | 96 MB |
| very long strip 800 x 40000 | 32 M | 64 MB | 128 MB |

Multiply by whatever the desktop reader keeps resident. The Android
webtoon reader holds roughly `viewport / pageHeight + 1` items plus one
screen of `calculateExtraLayoutSpace` in the scroll direction
(`WebtoonLayoutManager.kt:33-41`), but each of those is a *tiled*
decoder, not a resident full bitmap. A Compose reader with no tiling that
keeps 3 strips resident is at 144 MB in the third row of that table, at
`RGB_565`. Skia's default is BGRA 8888, so unless the port explicitly
decodes to a 16-bit colour type it is at 288 MB.

### The part that is not about memory

Even if you accept the memory, there is a second ceiling D10 does not
mention: **a Skia image drawn through the GPU backend has to become a
texture, and max texture size on desktop GL/Vulkan is finite.** Android
hits the identical wall, which is precisely why SSIV reads
`Canvas.getMaximumBitmapWidth()/Height()` and tiles to it. A 20000 px tall
Skia `Image` will not upload as one texture on most hardware. Compose
Desktop will either fall back to CPU rasterisation for that draw or fail.

This is the hard part of D10 and I do not think the decision record has
priced it. Downsampling does fix it - downsample a 20000 px strip to
4096 px and it is both smaller and uploadable - but at 4096/20000 it is a
**5x reduction**, so the strip is rendered at about 20% of native
resolution and zooming in shows you the downsample, not the page. That is
a visible quality regression on exactly the content type webtoon mode
exists for.

---

## 4. Reader modes

### Container and ownership

| Mode | Fragment | Container | Adapter | Holder | Layout |
|---|---|---|---|---|---|
| standard | `PagerReaderFragment` (via `BasePagerReaderFragment`) | `ViewPager2`, horizontal | `PagesAdapter` | `PageHolder` | `fragment_reader_pager.xml`, `item_page.xml` |
| reversed | `reversed/ReversedReaderFragment.kt:12` | same `ViewPager2` | `ReversedPagesAdapter` | `ReversedPageHolder` | same |
| vertical | `vertical/VerticalReaderFragment.kt:8` | same `ViewPager2`, `ORIENTATION_VERTICAL` (`:12`) | `PagesAdapter` (inherited) | `PageHolder` | same |
| double | `doublepage/DoubleReaderFragment.kt:29` | **`RecyclerView`** + `DoublePageLayoutManager` + `DoublePageSnapHelper` (`:58`) | `DoublePagesAdapter` | `DoublePageHolder` | `fragment_reader_double.xml`, `item_page.xml` |
| reversed-double | `doublereversed/ReversedDoubleReaderFragment.kt:7` | same as double | inherited | inherited | same |
| webtoon | `webtoon/WebtoonReaderFragment.kt:36` | `WebtoonScalingFrame` > `WebtoonRecyclerView` + `WebtoonLayoutManager` | `WebtoonAdapter` | `WebtoonHolder` | `fragment_reader_webtoon.xml`, `item_page_webtoon.xml` |

`ReaderManager.kt` (94 lines) picks the fragment for a `ReaderMode`.

### Mode-specific vs shared, by line count

`BaseReaderFragment` is 83 lines and holds **all** of the shared contract:
the `ReaderViewModel` reference (`:18`), the pending-state reconciliation
between `content.state` and `getCurrentState()` (`:27-48`, a three-branch
`when` with a comment explaining each), the save-on-pause and
save-on-destroy hooks (`:53-62`), and five abstract members
(`switchPageBy`, `switchPageTo`, `scrollBy`, `getCurrentState`,
`onCreateAdapter`, `onPagesChanged`).

`BaseReaderAdapter` is 87 lines and is entirely shared: `AsyncListDiffer`
with a `ReaderPage` diff callback on `(id, chapterId)`
(`BaseReaderAdapter.kt:77-85`), `StateRestorationPolicy.PREVENT`
(`:29`), holder bind/recycle/attach/detach plumbing. Every subclass
supplies only `onCreateViewHolder`.

`BasePagerReaderFragment` is 202 lines and is the *paged* shared layer:
`offscreenPageLimit = 2` (`:191`), page transformer selection for the
three `ReaderAnimation` values (`:78-90`), key forwarding via
`PagerEventSupplier` (`:71`), wheel handling (`:107-119`, `:184-186`),
`onPagesChanged` with index lookup by `(chapterId, index)` and a
"not found" snackbar (`:121-142`), `switchPageBy`/`switchPageTo` with the
`SMOOTH_SCROLL_LIMIT = 3` guard (`:169`, `:200`), and `getCurrentState`
which always reports `scroll = 0` (`:180`).

The genuinely mode-specific code is tiny:

- **reversed: 52 lines total** (`ReversedReaderFragment.kt`), of which the
  logic is five overrides that all reduce to one helper,
  `reversed(position) = itemCount - position - 1` (`:49-51`), plus
  `pages.reversed()` (`:41`) and an LTR-control preference flip (`:27-30`).
- **vertical: 16 lines total** (`VerticalReaderFragment.kt`), two
  overrides: set the pager orientation, pick a different transformer.
- **reversed-double: 28 lines** (`ReversedDoubleReaderFragment.kt`),
  the identical `reversed()` trick applied to `DoubleReaderFragment`.
- **double: 169 lines** (`DoubleReaderFragment.kt`). Genuinely different:
  it does not use `ViewPager2` at all, so it re-implements
  `onPagesChanged`, `switchPageBy`, `switchPageTo`, `getCurrentState` and
  the visible-range scroll listener against a `RecyclerView`. The pairing
  rule is `Int.toPagePosition() = this and 1.inv()` (`:145`), i.e. snap to
  the even index. `DoublePageSnapHelper` is another 277 lines.
- **webtoon: 291 + 281 + 357 + 144 + 59 + 41 + 30 + 39 = 1242 lines**
  across `WebtoonReaderFragment`, `WebtoonRecyclerView`,
  `WebtoonScalingFrame`, `WebtoonImageView`, `WebtoonHolder`,
  `WebtoonLayoutManager`, `WebtoonGapsDecoration`, `WebtoonAdapter`.

So of the six modes, **three are ~100 lines of index arithmetic over one
shared paged implementation**, one (double) is a separate container, and
one (webtoon) is 1242 lines and is effectively its own reader.
`DECISIONS.md` §2 cutting double and reversed-double is cheap and correct.
Cutting *vertical* would save 16 lines and is not worth doing; it is the
same pager rotated, and on a desktop with a mouse wheel it is arguably the
most natural paged mode. **Recommendation: v1 should ship standard, RTL,
vertical and webtoon, not the two D10 names**, because vertical is free.

### What of `ReaderViewModel` and `ChaptersLoader` is toolkit-independent

**`ChaptersLoader` (104 lines): portable, with two one-line changes.**
Imports are `android.util.LongSparseArray` (`:3`),
`androidx.annotation.CheckResult` (`:4`),
`dagger.hilt.android.scopes.ViewModelScoped` (`:5`). The first swaps to
`androidx.collection.LongSparseArray` (multiplatform, and `ChapterPages`
already uses that one at `ChapterPages.kt:3`). The second is
multiplatform already. The third is the Hilt scope annotation, which D7
replaces. **Everything else is `MangaRepository`, `MangaDetails`,
`MangaChapter`, `MangaPage`, `ReaderPage`, a `Mutex` and an
`ArrayDeque`.** Move as-is.

**`ChapterPages` (86 lines): portable unchanged.** No Android imports at
all beyond `androidx.collection`, which is multiplatform. It is a
chapter-indexed deque with a sliding window; `PAGES_TRIM_THRESHOLD = 120`
lives in `ChaptersLoader.kt:15`.

**`ReaderPage` (36 lines): needs `@Parcelize` removed.** Imports
`android.os.Parcelable` (`:3`) and `MangaSourceParceler` (`:6`). Strip
both and it is a plain data class.

**`ReaderState` (37 lines): same.** `android.os.Parcelable` only (`:3`).
Note the EPUB offset encoding at `:223-225`
(`encodeEpubOffset(o) = -o - 1`), which is dead weight if D2 holds but is
three lines, so leave it.

**`ReaderUiState` (31 lines): one method to move.** The only Android
import is `android.content.res.Resources` (`:3`), used solely by
`getChapterTitle(resources)` (`:30`). Delete that method from the shared
type and let the UI layer call `getLocalizedTitle` itself. The rest is
pure data plus four predicates (`hasNextChapter`, `hasPreviousChapter`,
`isSliderAvailable`, `chapterNumber`).

**`ReaderContent` (7 lines): portable unchanged.**

**`ReaderViewModel` (751 lines): the logic is portable, the class is not.**
Android imports are `android.net.Uri` (`:3`, only for the
`onPageSaved: MutableEventFlow<Collection<Uri>>` page-save event),
three `androidx.annotation` markers (`:4-6`, multiplatform),
`SavedStateHandle` (`:7`), `viewModelScope` (`:8`) and `@HiltViewModel`
(`:9`). `SavedStateHandle` is used for the incoming intent
(`MangaIntent(savedStateHandle)` at `:116`), the incognito and peek flags
(`:137`, `:141`) and persisting `EXTRA_STATE` (`:259`). On desktop that
becomes constructor parameters plus one key-value store. The class also
extends `ChaptersPagesViewModel` (`details/ui/pager/`), which is Agent D's
territory and is a real dependency to check.

The pieces worth moving verbatim, all toolkit-free arithmetic:
- `onCurrentPageChanged(lowerPos, upperPos)` (`:359-391`), including the
  centre-of-range rule `centerPos = (lowerPos + upperPos) / 2` (`:368`)
  and the two chapter-boundary triggers at `BOUNDS_PAGE_OFFSET = 2`
  (`:80`, `:381-386`).
- `loadPrevNextChapter` (`:534-541`).
- `notifyStateChanged` (`:591-637`), minus the Discord and stats calls
  (`:629-635`), both cut from v1.
- `computePercent` (`:638`) and `getPageProgress`.
- `switchChapter` (`:314`) / `switchChapterBy` (`:328`).
- `trySublist` (`:543-551`).

---

## 5. State and progress

### The state object

`ReaderState(chapterId: Long, page: Int, scroll: Int)`
(`reader/ui/ReaderState.kt:9-13`). Three ints. `scroll` is **only ever
non-zero in webtoon mode and EPUB**:
- paged modes hardcode `scroll = 0`
  (`BasePagerReaderFragment.kt:180`, `DoubleReaderFragment.kt:134`);
- webtoon reads it from the current holder's internal SSIV scroll
  (`WebtoonReaderFragment.kt:172`: `(... as? WebtoonHolder)?.getScrollY()`),
  and restores it via `WebtoonHolder.restoreScroll` (`WebtoonHolder.kt:52-58`),
  which defers if SSIV is not ready yet (`:56`);
- EPUB overloads negative `scroll` values as a character offset
  (`ReaderState.kt:31-35`).

**Porting consequence:** `scroll` is in *view pixels of the SSIV at its
current fit scale*, not in source pixels or in a fraction. A Compose
webtoon reader with a different layout model will produce a different
`scroll` unit for the same reading position, so a history row written by
the Android app and read by the desktop app restores to the wrong place
within a webtoon page. It restores to the right *page*, which is most of
the value. Worth writing down, because `DECISIONS.md` D15 makes
backup/restore the interop path and this is a silent, small
incompatibility inside that path.

### Where it is persisted

Two places, both from `ReaderViewModel.saveCurrentState`
(`ReaderViewModel.kt:256-271`):
1. **In-process / process-death:** `readingState.value = state` and
   `savedStateHandle[ReaderIntent.EXTRA_STATE] = state` (`:257-259`).
2. **Durable history:** `historyUpdateUseCase.invokeAsync(manga,
   readerState, percent = computePercent(readerState))` (`:265-269`).
   `HistoryUpdateUseCase` (`history/domain/HistoryUpdateUseCase.kt`, 46
   lines) is **completely Android-free** - no `android.*` import at all -
   and just calls `historyRepository.addOrUpdate(manga, chapterId, page,
   scroll, percent, force = false)` (`:22-29`). `invokeAsync` fires it on
   `processLifecycleScope` under `NonCancellable` (`:37-40`) so a write
   survives the reader closing.

Two guards suppress the write entirely:
`isIncognitoMode.value != false || isPeekMode.value`
(`ReaderViewModel.kt:261-263`). Peek mode is documented at `:139-140` as
"the reader works as usual but never writes reading progress".

Save is triggered from `BaseReaderFragment.onPause()` (`:53-56`) and
`onDestroyView()` (`:58-62`), plus once during `loadImpl` (`:489-493`).

### `ReaderUiState`

`reader/ui/pager/ReaderUiState.kt`, 31 lines. It is the **toolbar/infobar
projection** of reading position, not the reading position itself. Built
only in `ReaderViewModel.notifyStateChanged()` (`:591-637`) and published
to `uiState: MutableStateFlow<ReaderUiState?>` (`:135`). Fields:
`mangaName`, `chapter`, `chapterIndex`, `chaptersTotal`, `currentPage`,
`totalPages`, `percent`, `incognito`, `isPeek`, `isEpub`, `isEpubPaged`.
Consumers: `ReaderActivity` (title and slider), `ReaderInfoBarView`
(365 lines, the overlay bar), and `WebtoonReaderFragment.kt:87-95`, which
uses it only to decide whether the pull-to-change-chapter gesture should
report "no previous/next chapter".

`totalPages` is `chaptersLoader.getPagesCount(chapter.id)` for manga
(`:603`), and `EPUB_SLIDER_MAX + 1 = 1001` for EPUB (`:82`, `:605`) -
i.e. the EPUB slider is a permille scrollbar wearing a page-count costume.
That one detail is why `ReaderUiState` has three EPUB fields; strip them
with D2 and the type gets simpler.

### Next/previous chapter, and preload

**Chapter ordering** comes from `MangaDetails.allChapters`, and the
lookup is a linear `indexOfFirst`/`indexOfLast` on chapter id:
`ChaptersLoader.loadPrevNextChapter` (`ChaptersLoader.kt:36-61`) and
`ReaderViewModel.switchChapterBy` (`:328`). There is no chapter-number
arithmetic and no branch traversal at this level; branch filtering happens
earlier via `selectedBranch` (`ReaderViewModel.kt:473`, `:486`).

**The sliding window.** `ChaptersLoader` keeps a `ChapterPages` deque
spanning *several* chapters at once. On loading a neighbour it trims the
far end, but only if more than one chapter is loaded and the total exceeds
`PAGES_TRIM_THRESHOLD = 120` pages (`ChaptersLoader.kt:15`, `:44-53`).
So the reader holds up to ~120 `ReaderPage` records plus one chapter's
overflow, and page indices shift as chapters are added at the front
(`ChapterPages.shiftIndices`, `ChapterPages.kt:76-81`).

**Two triggers, both in `onCurrentPageChanged`** (`ReaderViewModel.kt:377-387`):
```
if (upperPos >= pages.lastIndex - BOUNDS_PAGE_OFFSET) loadPrevNextChapter(last, isNext = true)
if (lowerPos <= BOUNDS_PAGE_OFFSET)                   loadPrevNextChapter(first, isNext = false)
```
with `BOUNDS_PAGE_OFFSET = 2` (`:80`). Gated by `autoLoadAllowed`
(`:378`): in webtoon mode with the pull gesture enabled, auto-advance is
**off**, because the user is expected to pull for the next chapter
(`WebtoonReaderFragment.kt:232-252`). That interaction is easy to miss.

**Page prefetch** is separate and lives in `PageLoader`
(`ReaderViewModel.kt:388-390`): `pageLoader.prefetch(pages.trySublist(
upperPos + 1, upperPos + PREFETCH_LIMIT))` with `PREFETCH_LIMIT = 10`
(`:81`). Note the two different limits: the VM asks for up to 10 pages
ahead, `PageLoader` keeps at most 6 in its queue
(`PageLoader.kt:327`) and runs at most 4 at a time (`PageLoader.kt:99`).

### Dependency summary for reuse

| Piece | Android deps | Verdict |
|---|---|---|
| `ChapterPages` | none | move as-is |
| `HistoryUpdateUseCase` | none | move as-is |
| `ChaptersLoader` | `android.util.LongSparseArray`, Hilt scope | move, swap one import |
| `ReaderState`, `ReaderPage` | `@Parcelize` only | move, strip parcelization |
| `ReaderUiState` | `Resources` in one method | move, drop `getChapterTitle` |
| `ReaderContent` | none | move as-is |
| `ReaderViewModel` logic | `SavedStateHandle`, `viewModelScope`, Hilt, `Uri` in one event | rewrite the shell, lift the methods |
| `DetectReaderModeUseCase` | `BitmapFactory`, `android.util.Size`, `toUri`/`toFile` | needs a `decodeImageBounds(stream): Size` abstraction; logic (`:67-83`, `:114-121`) is portable |
| `PageLoader` | `Context`, `Uri`, `Rect`, Hilt `ActivityRetainedScoped`, Coil, SSIV `ImageSource` | rewrite; the *policy* (semaphore 4, prefetch 6, cache-then-network, the two fetch paths) is what to carry over |
| `PageViewModel` | `Context`, `Rect`, `Uri`, SSIV listener interface | rewrite; the state machine (`PageState` six states, the convert-on-IOException retry) is the valuable part |


## 6. The novel reader

`reader/ui/epub/EpubReaderFragment.kt`, 1985 lines, the largest file in the
repo. Its own header comment (`:131-135`) is accurate:

> Chapters are virtualized by RecyclerView in vertical mode and converted to
> a flat, chapter-spanning ViewPager2 page list in paged mode. Both modes
> navigate with the same chapter/character locator, so changing modes never
> reloads a chapter or loses the position.

### What it does

- **Content abstraction.** `ChapterContent` (`:1792-1799`), a three-method
  interface: `loadHtml(url)`, `imageData(chapterUrl, source)`,
  `resolveExternalLink`. One implementation, `HybridContentSource`
  (`:1801-1897`), serves a local EPUB out of a pool of open `ZipFile`s and a
  remote novel source out of a `MangaRepository`, transparently
  (`prepareBook` at `:501-524` decides which).
- **HTML rendering.** `parseChapter` (`:544-561`): Jsoup parses the chapter
  HTML, strips `script`/`style`/`noscript`, rewrites `<svg><image>` into
  `<img>`, then hands `document.body().html()` to
  **`HtmlCompat.fromHtml(..., FROM_HTML_MODE_LEGACY, ImageGetter, null)`**
  producing an `android.text.Spanned`. Inline images come back through
  `loadEpubImage` (`:563-587`), which decodes an embedded `ByteArray` with
  `BitmapFactory` or fetches a remote URL through Coil, then bounds the
  drawable to the screen width and `MAX_IMAGE_HEIGHT_FRACTION = 0.75f` of
  screen height (`:1925`).
  **So the HTML renderer is `android.text.Html`, not Markwon, not a WebView.**
- **Pagination.** `paginate(viewWidth, viewHeight, range)` (`:901-947`) lays
  out each chapter's `Spanned` with `StaticLayout` and cuts it into
  `NativePage`s that fit the viewport, with an inter-word justification
  compat shim (`:948-952`). Paged mode then shows those in a `ViewPager2`
  (`:787-866`), with a sliding page window (`extendPageWindow`, `:867-900`).
- **Vertical mode** is a `RecyclerView` of one `TextView` per chapter
  (`renderVertical`, `:755-786`; `ChaptersAdapter` at `:1596`).
- **Locator model.** `Locator` = (chapter index, character offset). Both
  modes read and write it (`currentLocator` `:1340`, `goTo` `:1422`,
  `positionVertical` `:1436`), which is what makes mode switching lossless.
  It serialises into `ReaderState.scroll` as a negative number
  (`ReaderState.kt:33-35`).
- **Text features, all hand-rolled span work:** bionic reading
  (`applyBionicReading` `:992`, with a fixation table at `bionicPrefixLength`
  `:1950`), paragraph spacing via a `LineHeightSpan` (`:1007`, `:1759`),
  custom typeface (`:1579`), theme colour animation (`:636-673`), text
  alignment (`:1036`).
- **Selection and highlights.** A custom `AppCompatTextView` subclass
  (`EpubSelectableTextView`, `:1626-1716`) that overrides
  `onSelectionChanged`, draws its own selection handles
  (`createSelectionHandle` `:1665`) and corrects handle horizontals
  (`:1690`, `:1706`). Highlights are `Bookmark` rows rendered as
  `HighlightColorSpan` (`:1620`), added/removed through an `ActionMode`
  (`:1064-1116`).
- **In-book search** (`:1463-1578`), capped at `MAX_SEARCH_RESULTS = 100`.
- **A dictionary lookup** that calls out to
  `https://api.dictionaryapi.dev/api/v2/entries/en` (`:1928`), parses the
  JSON and renders a bottom sheet (`:1259-1339`).
- **TTS integration** (`:253-388`), coupling to `reader/ui/tts/`.

### Is it reachable only for LN_/novel sources?

**No, and this is worth flagging.** The gate is
`readerManager.isEpub = viewModel.getMangaOrNull()?.isEpub == true`
(`reader/ui/ReaderActivity.kt:340`), and `Manga.isEpub`
(`local/data/CbzFilter.kt:81-84`) is:

```
source.isNovelSource ||
  hasEpubExtension(url.substringBefore('#')) ||
  chapters?.firstOrNull()?.let { hasEpubExtension(it.url.substringBefore('#')) } == true
```

So it is true for novel sources (`LN_*` and Mihon novel extensions, via
`core/model/MangaSource.kt:106-117`) **and** for any manga whose own url or
first chapter url ends in `.epub` - i.e. a **local EPUB file imported into
the library**. `DECISIONS.md` §2 already lists "EPUB reading" as out of v1
separately from the novel plugins, so the scope decision is right; I am
noting it because "only for LN_ sources" as a mental model would be wrong,
and because v1 *does* keep local-file import.

### Does cutting it break the manga path? No.

Every reference to the class outside its own package is either a safe cast
or behind the `isEpub` flag:

| Site | Form |
|---|---|
| `ReaderManager.kt:44` | `if (readerClass == EpubReaderFragment::class.java)` |
| `ReaderManager.kt:52-58` | `if (isEpub) EpubReaderFragment::class.java else requireNotNull(modeMap[newMode])` |
| `ReaderActivity.kt:312` | `(readerManager.currentReader as? EpubReaderFragment)?.setTtsPickMode(...)` |
| `ReaderActivity.kt:692` | `... as? EpubReaderFragment ?: return` |
| `ReaderActivity.kt:702` | `(... as? EpubReaderFragment)?.showBookSearch()` |

`ReaderManager.invalidateTypesMap` (`:78-91`) populates `modeMap` for all
four `ReaderMode`s with image-reader fragments only, so with `isEpub`
forced false the EPUB path is unreachable and nothing else changes.
`ReaderManager.setDoubleReaderMode` early-returns on `isEpub` (`:66-68`).

The residue inside shared code is small and inert: three EPUB fields on
`ReaderUiState` (`ReaderUiState.kt:17-18`), `EPUB_SLIDER_MAX = 1000`
(`ReaderViewModel.kt:82`), `onEpubProgressChanged`
(`ReaderViewModel.kt:553`), the `isEpub` branches in
`notifyStateChanged` (`:596-626`) and `getPageProgress` (`:656`), and the negative-
`scroll` encoding on `ReaderState` (`:221-225`). All of that can stay or go
without touching the image path.

**One correction to `PORTING_NOTES.md`.** Its dependency table says of
`io.noties.markwon`: "drop (Android `Spanned`). Novel HTML rendering needs
a different approach anyway." Markwon is **not** used by the novel reader.
Its only two consumers in the whole tree are
`settings/about/AppUpdateActivity.kt` and
`settings/about/changelog/ChangelogFragment.kt`. The novel reader uses
`HtmlCompat.fromHtml` + Jsoup + `StaticLayout`. Dropping Markwon is still
correct; the stated reason is wrong, and whoever reads that row later
should not conclude that replacing Markwon buys them a novel reader.

---

## 7. Decoding formats

### What the reader actually accepts

There is **no format allowlist anywhere in the reader**. Local pages are
filtered only by `MimeType.isImage` (`core/util/ext/MimeType.kt:32`, used at
`local/data/input/LocalMangaParser.kt` `Path.isImage()`), and remote pages
are whatever the server returns. The effective format set is therefore "what
the decoder chain accepts", and the chain differs by path:

| Path | Decoder | Set |
|---|---|---|
| reader page, non-AVIF | SSIV `SkiaImageRegionDecoder` / `SkiaPooledImageRegionDecoder` -> `BitmapRegionDecoder` | whatever the Android platform region-decodes: JPEG, PNG, WebP, and on recent API levels HEIF/AVIF |
| reader page, AVIF | `AvifCapableRegionDecoder` (`core/image/AvifCapableDecoders.kt:75-136`) -> `BitmapDecoderCompat.decode` -> `org.aomedia.avif.android.AvifDecoder` | AVIF and AVIS |
| reader page, recovery | `BitmapDecoderCompat.decode` (`core/image/BitmapDecoderCompat.kt:33-59`) -> `ImageDecoder` (API 28+) or `BitmapFactory` | everything the platform decodes |
| preview / cover / thumbnail | Coil chain from `core/AppModule.kt:125-150` | `AnimatedImageDecoder` (API 28+) or `GifDecoder`, `SvgDecoder`, `AvifImageDecoder`, plus Coil's defaults |

### What the app asks for

`PageLoader.createPageRequest` (`reader/domain/PageLoader.kt:341`):

```
Accept: image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8
```

WebP first, unweighted, then PNG at 0.9, JPEG unweighted, everything else at
0.8. That is the closest thing in the repo to a statement of expected
format distribution, and it says **WebP and JPEG carry the traffic**.

### Per-format desktop verdict

**JPEG, PNG.** Skia decodes both. No problem. `javax.imageio` also decodes
both *with region support*, which is the tiling escape hatch from section 2.

**WebP.** Skia decodes it (libwebp is in Skia's default codec set).
`javax.imageio` does **not**, without a third-party plugin. So the format
the app asks for first is exactly the one with no JDK region decoder. If
the port takes the `javax.imageio` region route it needs a WebP fallback
path, which in practice means decode-whole for WebP.
**Unknown:** whether the Skiko build bundled with CMP 1.12.0 includes the
WebP codec. What would settle it: a 10-line Skiko harness
(`org.jetbrains.skia.Image.makeDeferredFromEncodedBytes`) run against one
sample file per format. This belongs in Agent E's toolchain proof and is
about an hour of work.

**AVIF: genuinely absent, and the code says why it was added.**
Three separate AVIF code paths exist, all resting on
`org.aomedia.avif.android:avif:1.1.1.14d8e3c4`
(`gradle/libs.versions.toml:6`, `:87`; `app/build.gradle:249`), an Android
AAR with native `.so` libraries:
- `AvifImageDecoder` (`core/image/AvifImageDecoder.kt`, 112 lines), the Coil
  decoder, registered at `core/AppModule.kt:143`;
- `AvifCapableImageDecoder` / `AvifCapableRegionDecoder`
  (`core/image/AvifCapableDecoders.kt`, 136 lines), the SSIV decoders,
  installed at `reader/ui/config/ReaderSettings.kt:81-82`;
- `BitmapDecoderCompat.decodeAvif` (`core/image/BitmapDecoderCompat.kt:91-107`),
  the recovery path.

The comments say this was not speculative hardening. `AvifCapableDecoders.kt:19-24`:

> Some sources (e.g. Mangago) serve AVIF images mislabelled as image/jpeg.
> The subsampling library's stock Skia decoders hand them to the platform
> decoder, whose hardware AV1 path rejects tall webtoon frames - surfacing
> as "Cannot decode image - format image/jpeg may not be supported".

and `AvifImageDecoder.kt:94-98`:

> The mime type is unreliable: images served from the disk cache carry none
> at all, and some sources label AVIF as `image/jpeg`.

Both therefore sniff the header rather than trust the MIME type
(`isAvifHeader`, `AvifCapableDecoders.kt:29-33`, an ISO-BMFF `ftyp` +
`avif`/`avis` brand check on the first 64 bytes). Note also that the AVIF
path is **not tiled**: `AvifCapableRegionDecoder.init` decodes the whole
image into `full` and then serves "regions" by `Canvas.drawBitmap`
(`:89-118`). So on Android, AVIF webtoon strips are *already* the
full-decode case, and they already work. That is a small but real data
point for D10: the app survives full-decoding at least some large images
today.

**Desktop:** `PORTING_NOTES.md` and `DECISIONS.md` §2 both say AVIF is cut
with a clear error, and that is the right call for v1. The nuance to carry
forward is that the *detection* code
(`isAvifHeader`, `AvifCapableDecoders.kt:29-33`) is pure Kotlin over a
`ByteArray` and should be ported anyway, so the desktop reader can emit
"this page is AVIF, which is not supported" instead of a confusing Skia
decode failure on a page the server labelled `image/jpeg`.

**Animated GIF / WebP.** The reader **already renders these as stills on
Android**: SSIV's decoders return a `Bitmap`, and Coil's
`AnimatedImageDecoder`/`GifDecoder` are only in the Coil chain
(`core/AppModule.kt:136-139`), which the reader uses solely for the
low-res `preview` (`PageLoader.kt:135-146`) and for covers/thumbnails. So
**desktop loses nothing** by rendering animated pages as their first frame.
This is worth stating because "animated GIF/WebP support" reads like a gap
and is not one.

**SVG.** `SvgDecoder` is registered in the Coil chain
(`core/AppModule.kt:141`), so it can serve a cover or a favicon, but it is
not reachable from the reader page path at all. Coil 3's `coil-svg` is
multiplatform, so this ports if wanted.

### What fraction of real pages is affected

**Unknown from this repo, and I will not guess.** The three signals
available are: the `Accept` header prioritises WebP then PNG then JPEG
(`PageLoader.kt:341`); exactly one source is named in a comment as an AVIF
offender, Mangago (`AvifCapableDecoders.kt:19`); and enough AVIF was hit in
practice to justify three independent code paths and a native dependency.
That is consistent with AVIF being a small single-digit percentage
concentrated in a handful of sources, but the repo does not say so.
**What would settle it:** sample `Content-Type` and header bytes across a
few hundred pages from the top catalogue sources once Agent B's parser host
runs. Same measurement as section 3, same harness.

---

## 8. What I will get wrong

Ranked by expected pain, each with a test that can be run before any reader
code is written.

### Risk 1. The full-strip decode does not fit in a GPU texture

**Why.** Section 3. Android's answer to tall strips is to tile to
`Canvas.getMaximumBitmapWidth/Height()`, and the desktop GPU has the same
class of limit. A 1200 x 20000 Skia `Image` is not a texture on most
hardware. Downsampling to fit is a 5x resolution loss on exactly the
content webtoon mode exists for.

**Early test.** Before writing any reader: a ~40-line Compose Desktop
harness that generates synthetic PNGs at 1200x4000, 1200x8000, 1200x20000
and 800x40000, decodes each with Skia, and draws it in an `Image`
composable. Record: does it decode, does it draw, what is the RSS delta,
what is the frame time while panning. **Run the positive control first** -
draw a 1200x2000 image and confirm the harness reports a *successful* draw
- so a failure at 20000 means something. Then query the actual max texture
size from the Skia `DirectContext` and print it, rather than assuming.

### Risk 2. `javax.imageio` was never priced, and it may remove the whole problem

**Why.** `PORTING_NOTES.md` says flatly "Region/tiled decoding has no Skia
equivalent", and that is true of *Skia*. It is not true of the JVM.
`javax.imageio.ImageReadParam.setSourceRegion(Rectangle)` plus
`setSourceSubsampling` is the same contract as `BitmapRegionDecoder`, ships
in the JDK, needs no new dependency, and covers JPEG and PNG. If it works,
D10's "no tiling" premise is a choice rather than a constraint, and the
webtoon reader can keep the Android memory model.

**Early test.** ~30 lines: `ImageIO.getImageReaders` on a 1200x20000 JPEG
and the same as PNG; read a 1200x2000 band out of the middle; time it and
measure peak heap. Positive control: confirm the reader reports
`isImageTiled` / that reading the band is materially cheaper than reading
the whole image, otherwise ImageIO is buffering the lot internally and the
API is lying to you. Then repeat for WebP to confirm it is unsupported
(that is the expected negative, and it defines the fallback boundary).
**This is the highest-value hour in the whole reader port** and it should
happen before D10 is signed off, not after.

### Risk 3. The webtoon scroll model is not what it looks like

**Why.** Section 2. It looks like "a list of tall images". It is actually
"a list of one-screen viewports, each panning independently over its own
tiled image, with the list's nested pre-scroll hand-feeding deltas into the
first and last child before the list itself moves"
(`WebtoonImageView.kt:95`, `WebtoonRecyclerView.kt:87-125`). A Compose
`LazyColumn` of full-height items is a *different* model, and it is the one
that forces whole-strip decode. Choosing the Compose-natural model is
choosing Risk 1.

**Early test.** Same harness as Risk 1, but a `LazyColumn` with 5 synthetic
20000 px items, scrolled end to end programmatically. Watch peak heap and
dropped frames. Then implement the alternative (one screen-height item per
page, panning internally via `graphicsLayer` translation over a
`BitmapRegionDecoder`-equivalent) and compare. If ImageIO from Risk 2
works, the second model is available and is strictly better.

### Risk 4. Page state and scroll units silently diverge from Android

**Why.** Section 5. `ReaderState.scroll` is in view pixels at the SSIV's
current fit scale (`WebtoonImageView.scrollToInternal:112-118`,
`WebtoonReaderFragment.kt:172`), which is a number only the Android webtoon
view can produce. D15 makes backup/restore the Android-desktop interop
path, so this lands in real user data. Also: `ChapterPages` shifts page
indices whenever a previous chapter is prepended
(`ChapterPages.shiftIndices`, `:180-185`), and `onCurrentPageChanged` uses
`(lowerPos + upperPos) / 2` (`ReaderViewModel.kt:368`) against a snapshot
of `content.value.pages` captured *before* the coroutine runs, with a
size-changed bail-out at `:365-367` that carries a `// TODO`. Reproducing
that in Compose without reproducing the race is not automatic.

**Early test.** Property test on the ported `ChapterPages` +
`ChaptersLoader`: random sequences of `addFirst`/`addLast`/`removeFirst`/
`removeLast`, assert `subList(id)` always returns exactly that chapter's
pages and that `indices` stay consistent. Then a round-trip test: write a
`ReaderState` from the desktop reader, read it back, assert the restored
page index matches; assert explicitly that `scroll` is either honoured or
documented as lossy. Do not skip the second half.

### Risk 5. The recovery path disappears and takes the error budget with it

**Why.** Section 1 step 14. On Android, when the primary decoder fails, the
page is not lost: `PageViewModel.onImageLoadError` converts it
(`PageViewModel.kt:95-111` -> `PageLoader.convertBimap:167-190`) by
decoding with a *different* decoder and re-encoding to PNG. That is the
safety net under mislabelled MIME types, truncated files and format edge
cases - and the AVIF comments show those are common, not theoretical. A
Compose reader with one Skia decode path and no fallback turns each of
those into a visible broken page. `PageState` has six states
(`PageState.kt:5-30`) precisely because the real world needs them.

**Early test.** Build a corpus of deliberately awkward inputs before
writing the decoder: an AVIF file named `.jpg`, a truncated JPEG, a
progressive JPEG, a CMYK JPEG, a 16-bit PNG, an animated WebP, a
zero-length file, and a valid page inside a CBZ. Feed all of them through
the desktop decode path and record which produce a page, which produce an
error, and which hang. **Positive control:** include two known-good files
and confirm they succeed, so a row of failures is a result and not a broken
harness. Then decide which need a second decoder, rather than discovering
it from bug reports.

### Verdict on D10

**Split it. Two of the three claims are fine, one is not proven.**

**"Compose, paged LTR/RTL plus webtoon, hand-written `graphicsLayer` +
`pointerInput` zoom/pan": accept.** The gesture work is well understood and
the app already contains a hand-written reference implementation for the
hard case (`WebtoonScalingFrame.kt`, 357 lines, including clamping, fling
and the scale<1 relayout). The four `ZoomMode`s are ~25 lines each. The
paged container is ~100 lines of index arithmetic over one shared
implementation, and reversed and vertical are nearly free. I would add
vertical to v1 for 16 lines.

**"Skia decoding via Skiko": accept with one verification.** JPEG and PNG
are certain. WebP - the format the app requests first
(`PageLoader.kt:341`) - is very likely present in Skiko's Skia build but is
**unverified**, and it carries most of the traffic. Verify before, not
after. Cutting AVIF is correct; port `isAvifHeader` anyway so the error
message is truthful.

**"No tiled decoding": not survivable as stated, and the premise is
incomplete.**

The reasoning in D10 is "`BitmapRegionDecoder` is Android-only and Skiko
exposes no region decoder", which is true and is not the whole question.
Three things it misses:

1. **It is not only a memory question, it is a texture question.** The
   Android app tiles to `Canvas.getMaximumBitmapWidth/Height()` because a
   large bitmap cannot be drawn as one texture, and desktop GPUs have the
   same limit. "Downsample on load" does not merely cost memory, it costs
   *resolution*, and at the sizes webtoon content actually reaches
   (section 3) the reduction is large enough to be the first thing a user
   notices.
2. **The webtoon reader is architecturally built around tiling.** Every
   webtoon page view is clamped to one screen height
   (`WebtoonImageView.kt:95`) and pans internally over an image it never
   fully holds (`:112-118`). Removing tiling does not simplify that design,
   it invalidates it, and forces the alternative design that has Risk 1.
3. **There is a region decoder on the JVM; it is just not in Skia.**
   `javax.imageio.ImageReadParam.setSourceRegion` covers JPEG and PNG with
   no new dependency. D10 does not consider it.

**Concrete recommendation.** Do not sign off "no tiling" yet. Spend the
Risk 2 hour first. Then pick one of:

- **(a) If ImageIO region reads work for JPEG and PNG:** keep the Android
  memory model. Region-decode JPEG/PNG, full-decode-with-downsample WebP
  and anything else. This preserves webtoon quality on most content and
  degrades only on WebP strips.
- **(b) If they do not:** accept downsampling, but make it explicit rather
  than incidental. Decode tall strips to a fixed maximum edge derived from
  the *measured* max texture size at startup, not a hardcoded constant, and
  state in `DECISIONS.md` that webtoon pages above that height render
  downsampled. Also slice the decoded strip into N screen-height Skia
  images at decode time so the draw path never needs one oversized texture;
  that is cheap, it is not tiling, and it removes Risk 1 even under (b).
- **(c) Reduce v1 scope honestly:** ship paged modes fully and webtoon with
  a documented height ceiling, rather than shipping a webtoon mode that
  looks soft on the content people use it for.

I would take (a) if the hour says yes, and (b) with the slicing otherwise.
I would not take D10 as written, because as written it commits to the
outcome of a measurement nobody has made.
