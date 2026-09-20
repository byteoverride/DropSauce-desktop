# D - domain and local storage

Phase 1 read-only recon, Agent D. Repo at `desktop-port`, commit `1d30c9b`.
Everything below was read out of the tree at that commit. No Gradle task was
run and no source file was modified.

Scope: `favourites/`, `history/`, `details/domain`, `details/data`,
`list/domain`, `search/domain`, `explore/`, `filter/`, `local/`, `bookmarks/`,
`alternatives/`, `core/util/`, `core/cache/`, `core/fs/`, `core/io/`,
`core/zip/`. 239 Kotlin files.

## 0. Corrections to the existing docs

Read `ARCHITECTURE.md`, `PORTING_NOTES.md` and `DECISIONS.md` first. They are
broadly accurate. Four things in them are wrong or misleading for my area, and
one of them is load-bearing for D9.

**0.1 `PORTING_NOTES.md` §A undercounts the coupling, because it only counted
`android.*` / `androidx.*` / `com.google.android.*` imports.** The single most
common Android coupling in `domain/` code in my directories is
`import org.koitharu.kotatsu.R`, which matches none of those prefixes. 67 of my
239 files import `R`. Five of them would otherwise have scored as clean or
near-clean domain code:

- `explore/data/SourcesSortOrder.kt:3`
- `favourites/domain/FavoritesListQuickFilter.kt:9`
- `list/domain/ListFilterOption.kt:5`
- `list/domain/ListSortOrder.kt:4`
- `list/domain/MangaListQuickFilter.kt` (via `ListFilterOption`)

`ListSortOrder` is the sort enum for the entire library, history and favourites
UI, and it carries `@get:StringRes val titleResId` (`list/domain/ListSortOrder.kt:41-43`)
plus a nested `Type` enum whose constructor parameter is a resource id
(`list/domain/ListSortOrder.kt:52-60`). `ListFilterOption` is worse: it is a
sealed interface where every variant exposes both `titleResId` and `iconResId`
(`list/domain/ListFilterOption.kt:19-31`). These are domain types with
presentation identity baked in. They are not a mechanical `androidx.annotation`
strip.

**0.2 `PORTING_NOTES.md` §A says "8 `androidx.core.net.toUri` / `toFile`".** In
my directories alone there are 6 files using them, all in `local/`, and they are
the exact seam where D9 ("`Uri` does not enter `:shared`") meets the code that
actually has to move for local CBZ reading. See §3. (§C's description of the
zip scheme was `zip://` when I started this pass and has since been corrected
to `file+zip://` by another agent; my §3.2 finding agrees with the corrected
version and was reached independently.)

**0.3 `PORTING_NOTES.md` §B claims `core/util/*` non-ext helpers are
"portable as-is", naming `FileSize`, `MimeTypes`, `iterator/`, `progress/`.**
Three of those four are wrong:

- `core/util/FileSize.kt:3` imports `android.content.Context` (it formats sizes
  with `Formatter.formatFileSize`).
- `core/util/MimeTypes.kt:3-5` imports `android.os.Build`,
  `android.webkit.MimeTypeMap` and Coil's `MimeTypeMap`.
- `core/util/progress/` is 6 files, of which 3 are Android:
  `ImageRequestIndicatorListener.kt` (Coil + Material `LoadingIndicator`),
  `IntPercentLabelFormatter.kt` (`Context` + Material `LabelFormatter`),
  `RealtimeEtaEstimator.kt:3` (`android.os.SystemClock`).
  `Progress.kt`, `ProgressDeferred.kt`, `ProgressResponseBody.kt` are clean.
- `core/util/iterator/` is the only one of the four that is clean as stated.

**0.4 `DECISIONS.md` §3 assigns me `details/**` and `list/**` wholesale; the
task brief narrowed that to `details/domain`, `details/data` and `list/domain`.**
I followed the brief. `list/ui/` (56 files) and `details/ui/` (46 files) are
therefore unexamined here, and `list/ui/model/` in particular is a shared
dependency of almost every `domain` file in my area (see §2). Somebody has to
own it; on the current split nobody does.

## 1. The move-ready list

Classification rules used:

- **GREEN** - no `android.*` / `androidx.*` / `com.google.android.*` import, no
  `org.koitharu.kotatsu.R` import, no import of an Android-bound project type.
- **AMBER** - only `androidx.annotation`, `androidx.collection`,
  `android.util.Log`, or Room annotations. Removable mechanically or kept as-is
  (Room and `androidx.collection` both publish JVM artifacts, so several AMBER
  files need literally no edit, only a source-set move).
- **RED** - real platform coupling: `Context`, `Uri`, `SharedPreferences`,
  `DocumentFile`, SAF, `Bitmap`, Android I/O, Android views, lifecycle, Hilt
  Android, WorkManager, Coil Android, Material Components, `R`.

### Counts by feature

| Feature | Files | GREEN | AMBER | RED |
|---|---:|---:|---:|---:|
| `core/util/` (incl. `ext/`) | 60 | 15 | 4 | 41 |
| `favourites/` | 41 | 9 | 4 | 28 |
| `local/` | 36 | 10 | 5 | 21 |
| `filter/` | 24 | 10 | 0 | 14 |
| `explore/` | 17 | 7 | 0 | 10 |
| `history/` | 15 | 6 | 2 | 7 |
| `bookmarks/` | 11 | 3 | 2 | 6 |
| `alternatives/` | 8 | 3 | 0 | 5 |
| `details/domain` | 7 | 6 | 0 | 1 |
| `list/domain` | 7 | 3 | 0 | 4 |
| `core/cache/` | 4 | 2 | 0 | 2 |
| `search/domain` | 4 | 3 | 0 | 1 |
| `details/data` | 2 | 1 | 0 | 1 |
| `core/io/` | 1 | 1 | 0 | 0 |
| `core/zip/` | 1 | 0 | 1 | 0 |
| `core/fs/` | 1 | 0 | 0 | 1 |
| **total** | **239** | **79** | **18** | **142** |

The RED column is dominated by `ui/` subdirectories, which are rewritten not
ported (`DECISIONS.md` D10, `PORTING_NOTES.md` §D). Excluding every `*/ui/`
directory, the picture for the code that actually has to move is:

| | files | GREEN | AMBER | RED |
|---|---:|---:|---:|---:|
| files outside any `*/ui/` directory | 149 | 59 | 18 | 72 |

### AMBER, full list with the offending import

Room annotations and `androidx.collection` both have JVM artifacts, so "AMBER"
here means "no work" for most of these, not "some work". The column says which.

| File | Offending import | Work |
|---|---|---|
| `bookmarks/data/BookmarkEntity.kt` | `androidx.room.{ColumnInfo,Entity,ForeignKey}` | none, Room KMP |
| `bookmarks/data/BookmarksDao.kt` | `androidx.room.{Dao,Delete,Insert,Query,Transaction,Upsert}` | none, Room KMP |
| `favourites/data/FavouriteCategoriesDao.kt` | `androidx.room.{Dao,Insert,OnConflictStrategy,Query,RoomWarnings,Upsert}` | none |
| `favourites/data/FavouriteCategoryEntity.kt` | `androidx.room.{ColumnInfo,Entity,PrimaryKey}` | none |
| `favourites/data/FavouriteEntity.kt` | `androidx.room.{ColumnInfo,Entity,ForeignKey}` | none |
| `favourites/data/FavouriteManga.kt` | `androidx.room.{Embedded,Junction,Relation}` | none |
| `history/data/HistoryEntity.kt` | `androidx.room.{ColumnInfo,Entity,ForeignKey,PrimaryKey}` | none |
| `history/data/HistoryWithManga.kt` | `androidx.room.{Embedded,Junction,Relation}` | none |
| `local/data/index/LocalMangaIndexDao.kt` | `androidx.room.{Dao,Query,Upsert}` | none |
| `local/data/index/LocalMangaIndexEntity.kt` | `androidx.room.{ColumnInfo,Entity,ForeignKey,PrimaryKey}` | none |
| `core/util/ext/Collections.kt` | `androidx.collection.{ArrayMap,ArraySet,LongSet}` | none, `collection-jvm` |
| `core/util/SynchronizedSieveCache.kt` | `androidx.collection.SieveCache` | none, `collection-jvm` |
| `core/zip/ZipOutput.kt` | `androidx.annotation.WorkerThread`, `androidx.collection.ArraySet` | strip annotation |
| `core/util/ext/EventFlow.kt` | `androidx.annotation.AnyThread` | strip annotation |
| `core/util/MultiMutex.kt` | `androidx.annotation.VisibleForTesting` | strip annotation |
| `local/data/MangaIndex.kt` | `androidx.annotation.WorkerThread` | strip annotation |
| `local/data/output/LocalMangaZipOutput.kt` | `androidx.annotation.WorkerThread` | strip annotation |
| `local/data/output/LocalNovelEpubOutput.kt` | `androidx.annotation.WorkerThread` | strip annotation |

That is all 18 AMBER files. `androidx.annotation` does publish a KMP artifact,
so even the "strip annotation" rows can be deferred; they are listed because
keeping an `androidx` dependency in `:shared/commonMain` for four annotations
is not worth it. Eleven of the eighteen need no edit at all.

Four files that would otherwise sit in this table are classified RED instead
because they import `org.koitharu.kotatsu.R`:
`explore/data/SourcesSortOrder.kt`, `list/domain/ListSortOrder.kt`,
`list/domain/ListFilterOption.kt` and (transitively)
`list/domain/MangaListQuickFilter.kt`. They carry `@StringRes Int` and
`@DrawableRes Int` in domain types. The fix is a `StringProvider`-friendly key
(`DECISIONS.md` D9 already names `StringProvider`) rather than an `Int`, and it
touches every UI call site that reads `titleResId`. They are the cheapest RED
files in the whole scope and the ones that unblock the most, so they are
step 7 of the move order.

### RED, full list with the offending import

`ui/` files are folded into one line per feature because they are rewritten, not
ported.

**`core/util/` and `core/util/ext/` (41 RED)** - see §5 for the split analysis.

| File | Offending import |
|---|---|
| `core/util/AcraScreenLogger.kt` | `android.app.Activity`, `android.content.Context`, `androidx.fragment.app.*` |
| `core/util/FileSize.kt` | `android.content.Context` |
| `core/util/IdlingDetector.kt` | `android.os.Handler`, `android.os.Looper`, `androidx.lifecycle.*` |
| `core/util/KotatsuColors.kt` | `android.content.Context`, `com.google.android.material.color.MaterialColors` |
| `core/util/LocaleComparator.kt` | `androidx.core.os.LocaleListCompat` |
| `core/util/LocaleStringComparator.kt` | `androidx.core.os.LocaleListCompat` |
| `core/util/MimeTypes.kt` | `android.os.Build`, `android.webkit.MimeTypeMap`, `coil3.util.MimeTypeMap` |
| `core/util/RecyclerViewScrollCallback.kt` | `androidx.recyclerview.widget.*` |
| `core/util/RetainedLifecycleCoroutineScope.kt` | `dagger.hilt.android.lifecycle.RetainedLifecycle` |
| `core/util/ShareHelper.kt` | `android.content.Context`, `android.net.Uri`, `androidx.core.content.FileProvider` |
| `core/util/Throttler.kt` | `android.os.SystemClock` |
| `core/util/ViewBadge.kt` | `android.view.View`, `com.google.android.material.badge.*` |
| `core/util/progress/ImageRequestIndicatorListener.kt` | `coil3.request.*`, `com.google.android.material.loadingindicator.LoadingIndicator` |
| `core/util/progress/IntPercentLabelFormatter.kt` | `android.content.Context`, `com.google.android.material.slider.LabelFormatter` |
| `core/util/progress/RealtimeEtaEstimator.kt` | `android.os.SystemClock` |
| `core/util/ext/Android.kt` | 34 Android imports, the dumping ground |
| `core/util/ext/Bundle.kt` | `android.os.Bundle` |
| `core/util/ext/Coil.kt` | Coil Android + views |
| `core/util/ext/ContentResolver.kt` | `android.content.Context`, `android.net.Uri`, `android.provider.DocumentsContract` |
| `core/util/ext/Coroutines.kt` | `android.content.BroadcastReceiver`, `androidx.lifecycle.ProcessLifecycleOwner` |
| `core/util/ext/Cursor.kt` | `android.database.Cursor` |
| `core/util/ext/Date.kt` | `android.content.res.Resources` |
| `core/util/ext/File.kt` | `android.content.Context`, `android.net.Uri`, `android.os.Environment`, `android.os.storage.StorageManager` |
| `core/util/ext/Flow.kt` | `android.os.SystemClock` |
| `core/util/ext/FlowObserver.kt` | `androidx.lifecycle.*` |
| `core/util/ext/Fragment.kt` | `androidx.fragment.app.*` |
| `core/util/ext/Graphics.kt` | `android.graphics.*` |
| `core/util/ext/Haptics.kt` | `android.view.*` |
| `core/util/ext/Insets.kt` | `androidx.core.view.WindowInsets*` |
| `core/util/ext/IO.kt` | `android.content.ContentResolver`, `android.net.Uri` |
| `core/util/ext/LocaleList.kt` | `android.content.Context`, `androidx.core.os.LocaleListCompat` |
| `core/util/ext/Menu.kt` | `android.view.Menu` |
| `core/util/ext/Preferences.kt` | `android.content.SharedPreferences` |
| `core/util/ext/RecyclerView.kt` | `androidx.recyclerview.widget.*` |
| `core/util/ext/Resources.kt` | `android.content.res.Resources` |
| `core/util/ext/String.kt` | `android.content.Context` |
| `core/util/ext/TextView.kt` | `android.widget.TextView` |
| `core/util/ext/Theme.kt` | `android.content.Context`, Material |
| `core/util/ext/Throwable.kt` | `android.content.ActivityNotFoundException`, `android.database.sqlite.SQLiteFullException`, `coil3.network.HttpException` |
| `core/util/ext/Uri.kt` | `android.net.Uri`, `androidx.core.net.toUri` |
| `core/util/ext/WorkManager.kt` | `androidx.work.*` including `androidx.work.impl.WorkManagerImpl` |

**`local/` non-`ui` (13 RED)** - see §3 for the deep analysis.

| File | Offending import |
|---|---|
| `local/data/LocalMangaRepository.kt` | `androidx.core.net.toFile`, `androidx.core.net.toUri` |
| `local/data/LocalStorageManager.kt` | `android.Manifest`, `android.content.ContentResolver`, `android.content.Context`, `android.content.Intent`, `android.content.pm.PackageManager`, `android.net.Uri`, `android.os.Build`, `android.os.Environment`, `android.os.StatFs`, `androidx.core.content.ContextCompat`, `androidx.core.net.toFile` |
| `local/data/LocalStorageCache.kt` | `android.content.Context`, `android.graphics.Bitmap`, `android.os.StatFs`, `android.webkit.MimeTypeMap` |
| `local/data/index/LocalMangaIndex.kt` | `android.content.Context`, `androidx.core.content.edit`, `androidx.room.withTransaction` |
| `local/data/input/LocalMangaParser.kt` | `android.net.Uri`, `androidx.core.net.toFile`, `androidx.core.net.toUri` |
| `local/data/input/EpubParser.kt` | `android.util.Xml` |
| `local/data/importer/MihonDownloads.kt` | `android.content.ContentResolver`, `android.util.Xml`, `androidx.documentfile.provider.DocumentFile` |
| `local/data/importer/SingleMangaImporter.kt` | `android.content.Context`, `android.graphics.Bitmap`, `android.graphics.Color`, `android.graphics.Matrix`, `android.graphics.pdf.PdfRenderer`, `android.net.Uri`, `android.os.ParcelFileDescriptor`, `androidx.documentfile.provider.DocumentFile` |
| `local/data/output/LocalMangaDirOutput.kt` | `androidx.core.net.toFile`, `androidx.core.net.toUri` |
| `local/data/output/LocalMangaUtil.kt` | `androidx.core.net.toFile`, `androidx.core.net.toUri` |
| `local/domain/model/LocalManga.kt` | `android.net.Uri`, `androidx.core.net.toFile`, `androidx.core.net.toUri` |
| `local/ui/*` (8 files) | activities, services, workers, fragments - rewritten |

**Data-layer RED elsewhere**

| File | Offending import | Note |
|---|---|---|
| `favourites/data/FavouritesDao.kt` | `android.database.DatabaseUtils.sqlEscapeString`, `androidx.sqlite.db.SimpleSQLiteQuery`, `androidx.sqlite.db.SupportSQLiteQuery` | the `@RawQuery` seam, `PORTING_NOTES.md` §C row "Raw SQL queries" |
| `history/data/HistoryDao.kt` | `android.database.DatabaseUtils.sqlEscapeString`, `androidx.sqlite.db.SupportSQLiteQuery` | same |
| `favourites/domain/FavouritesRepository.kt` | `androidx.room.withTransaction` | one of the 11 call sites |
| `history/data/HistoryRepository.kt` | `androidx.room.withTransaction` | same |
| `bookmarks/domain/BookmarksRepository.kt` | `android.database.SQLException`, `androidx.room.withTransaction` | same, plus a catch on an Android exception type |
| `alternatives/domain/MigrateUseCase.kt` | `androidx.room.withTransaction` | same |
| `explore/data/MangaSourcesRepository.kt` | `android.content.Context`, `android.content.SharedPreferences`, `androidx.core.os.ConfigurationCompat` | D8 `Settings` interface |
| `filter/data/SavedFiltersRepository.kt` | `android.content.Context`, `android.content.SharedPreferences`, `androidx.core.content.edit` | D8 `Settings` interface |
| `search/domain/MangaSearchRepository.kt` | `android.app.SearchManager`, `android.content.Context`, `android.provider.SearchRecentSuggestions` | search suggestions are an Android `ContentProvider` |
| `list/domain/MangaListMapper.kt` | `android.annotation.SuppressLint`, `android.content.Context` | the domain->UI-model mapper, see §2 |
| `details/domain/DetailsLoadUseCase.kt` | `android.text.Html`, `android.text.Spanned`, `android.text.style.ForegroundColorSpan`, `androidx.core.text.parseAsHtml`, `coil3.request.CachePolicy` | description HTML parsed to a `Spanned` in a use case |
| `details/data/ReadingTime.kt` | `android.content.res.Resources` | formats itself |
| `core/cache/MemoryContentCache.kt` | `android.app.Application`, `android.content.ComponentCallbacks2`, `android.content.res.Configuration` | `onTrimMemory`, see §4 |
| `core/cache/ExpiringValue.kt` | `android.os.SystemClock` | see §4 |
| `core/fs/FileSequence.kt` | `android.os.Build` | an API-level branch only |
| `favourites/domain/FavoritesListQuickFilter.kt` | `R`, `core.ui.widgets.ChipsView`, `core.os.NetworkState`, `mihon.MihonExtensionManager` | domain class that returns view models |
| `history/domain/HistoryListQuickFilter.kt` | `core.os.NetworkState`, `mihon.MihonExtensionManager` | same shape, no direct Android import |

**`ui/` RED, counted not listed**: `favourites/ui` 26, `filter/ui` 14,
`explore/ui` 9, `local/ui` 8, `bookmarks/ui` 5, `history/ui` 5,
`alternatives/ui` 4. These are activities, fragments, adapter delegates,
bottom sheets and view models. `DECISIONS.md` D10 and `PORTING_NOTES.md` §D
already call this a rewrite.

### GREEN, by directory

79 files. Counts, with the caveat below.

| Directory | GREEN files |
|---|---:|
| `core/util/` (non-`ext`) | 12 |
| `filter/` | 10 |
| `local/` | 10 |
| `favourites/` | 9 |
| `details/` | 7 |
| `explore/` | 7 |
| `history/` | 6 |
| `alternatives/` | 3 |
| `bookmarks/` | 3 |
| `core/util/ext/` | 3 (`Http.kt`, `MimeType.kt`, `Other.kt`) |
| `list/domain` | 3 |
| `search/domain` | 3 |
| `core/cache/` | 2 |
| `core/io/` | 1 |

**The caveat that matters: "no Android import" is not "compiles in
`commonMain`".** 20 of the 79 GREEN files import a project type that is itself
RED. The ones that block a move:

| GREEN file | Blocked by |
|---|---|
| `core/util/AcraCoroutineErrorHandler.kt:3` | `core.util.ext.printStackTraceDebug` - see the note below, it is not in `main` at all |
| `explore/domain/ExploreRepository.kt`, `explore/domain/RecoverMangaUseCase.kt`, `details/domain/RelatedMangaUseCase.kt`, `local/domain/DeleteLocalMangaUseCase.kt`, `local/domain/DeleteReadChaptersUseCase.kt`, `search/domain/SearchV2Helper.kt`, `history/domain/HistoryUpdateUseCase.kt`, `local/data/output/LocalMangaOutput.kt` | same `printStackTraceDebug` |
| `history/domain/HistoryUpdateUseCase.kt` | also `core.util.ext.processLifecycleScope` (`ext/Coroutines.kt`, RED) |
| `favourites/domain/LocalFavoritesObserver.kt`, `history/data/HistoryLocalObserver.kt`, `local/domain/LocalObserveMapper.kt` | `local.data.index.LocalMangaIndex` (RED, `Context`) |
| `details/domain/DetailsInteractor.kt`, `details/domain/ReadingTimeUseCase.kt`, `explore/domain/ExploreRepository.kt`, `local/domain/DeleteReadChaptersUseCase.kt` | `core.prefs.AppSettings` (D8) |
| `details/domain/ProgressUpdateUseCase.kt` | `core.os.NetworkState` (RED, `ConnectivityManager`) |
| `alternatives/domain/AutoFixUseCase.kt` | `core.model.parcelable.ParcelableManga` (Android `Parcelable`) |
| `bookmarks/domain/Bookmark.kt`, `alternatives/ui/MangaAlternativeModel.kt`, `explore/ui/model/*`, `favourites/ui/categories/adapter/*ListModel.kt`, `filter/ui/model/*`, `filter/ui/mihon/model/*` | `list.ui.model.ListModel` - **this one is free**: `list/ui/model/ListModel.kt` is a 10-line interface with no imports at all |
| `bookmarks/ui/AllBookmarksActivity.kt`, `explore/ui/adapter/ExploreAdapter.kt`, `favourites/ui/categories/adapter/CategoriesAdapter.kt`, `filter/ui/model/FilterHeaderModel.kt` | Android base classes in `core/ui/` - these are GREEN by import scan and RED in reality |

So the honest GREEN count, meaning "moves with no other file having to move
first", is **59**, of which **43 are outside any `ui/` directory**.
`printStackTraceDebug` alone gates 9 of the 20 blocked ones.

**`printStackTraceDebug` is a build-variant trap and nobody has flagged it.**
It is used by 87 files across the whole app and it is not declared in
`src/main` at all. It has two definitions in two build-type source sets:

- `app/src/debug/kotlin/org/koitharu/kotatsu/core/util/ext/Debug.kt:5`
  `fun Throwable.printStackTraceDebug() = printStackTrace()`
- `app/src/release/kotlin/org/koitharu/kotatsu/core/util/ext/Debug.kt:6`
  `inline fun Throwable.printStackTraceDebug() = Unit`

Two consequences. First, the function itself is pure Kotlin, so it is trivially
GREEN once it exists somewhere a shared module can see. Second, `:app`'s
`preview` build type folds `src/release/kotlin` into its own source set
(`ARCHITECTURE.md` §1), so this symbol is resolved differently in three of the
app's build variants. A `:shared` KMP module with `androidTarget()` +
`jvm()` has no equivalent per-build-type source set on the `jvm()` side, so the
move needs a real decision: either a `Logger` interface (`DECISIONS.md` D9
already names one) injected where it is used, or a single non-variant
implementation that checks a build flag at runtime. Picking the second silently
turns `= Unit` into "always print" in release, which is a behaviour change to
the shipped Android app.

Extracting or replacing that one function is the highest leverage single edit in
my whole area. See §7 step 1.

## 2. The dependency graph of the core flows

Notation: `->` is "calls / injects". Marks are GREEN / AMBER / RED as in §1.
Files outside my directories are marked `(not mine)` and are still listed
because they are on the path.

### (a) Library and favourites list, with categories and sorting

```
FavouritesContainerFragment (ViewPager2 of category tabs)          RED
  -> FavouritesContainerViewModel                                  RED  (@HiltViewModel, 84 lines)
       -> FavouritesRepository.observeCategoriesForLibrary()
  -> FavouritesListFragment (one per category)                     RED
       -> FavouritesListViewModel                                  RED  (@HiltViewModel, 250 lines)
            : MangaListViewModel (not mine, 87 lines)              RED  (androidx.lifecycle only)
            -> FavouritesRepository                                RED  (withTransaction, 342 lines)
                 -> FavouritesDao                                  RED  (@RawQuery + SupportSQLiteQuery)
                      -> MangaQueryBuilder (not mine, core/db)     RED  (builds SupportSQLiteQuery)
                      -> ListSortOrder.toOrderBy  (ListSortOrderSql) GREEN
                 -> FavouriteCategoriesDao                         AMBER (Room only)
                 -> FavouriteEntity / FavouriteCategoryEntity      AMBER
                 -> FavouriteManga (@Embedded/@Relation)           AMBER
                 -> favourites/data/EntityMapping.kt               GREEN
                 -> Cover                                          GREEN
                 -> ReversibleHandle (core/ui/util, not mine)      RED
            -> MangaListMapper                                     RED  (@ApplicationContext)
            -> MarkAsReadUseCase                                   GREEN
            -> FavoritesListQuickFilter (via AssistedFactory)      RED  (R, ChipsView, MihonExtensionManager)
                 : MangaListQuickFilter                            AMBER+R
                 -> ListFilterOption                               AMBER+R
            -> AppSettings (not mine, core/prefs)                  RED  (D8)
            -> MangaDataRepository (not mine, core/parser)         not analysed
            -> @LocalStorageChanges SharedFlow<LocalManga?>        RED  (LocalManga holds a Uri)
  -> LocalFavoritesObserver                                        GREEN-by-import, blocked by LocalMangaIndex
```

What this says:

- **The favourites *data* layer is nearly free.** Of the 6 files in
  `favourites/data/`, 4 are AMBER-Room-only and 1 is GREEN. The only RED one is
  `FavouritesDao.kt`, and only because of three imports:
  `android.database.DatabaseUtils.sqlEscapeString` (`favourites/data/FavouritesDao.kt:3`),
  `androidx.sqlite.db.SimpleSQLiteQuery` (`:11`) and
  `androidx.sqlite.db.SupportSQLiteQuery` (`:12`). Two `@RawQuery` methods
  (`:239-240`, `:242-243`) and two `SimpleSQLiteQuery` constructions
  (`:125`, `:138`) are the whole surface.
- **`sqlEscapeString` is an Android SQLite helper with no KMP equivalent and it
  is used to build a WHERE clause from a `ListFilterOption`**
  (`favourites/data/FavouritesDao.kt:280-281`,
  `history/data/HistoryDao.kt:205-206`). It has to be reimplemented. It is 15
  lines of quoting logic, and getting it wrong is a SQL injection into the
  user's own database from a source name, so it should be ported carefully and
  not hand-waved.
- **`ListSortOrder` is the hinge of this flow and it imports `R`.** It is read
  by `FavouritesDao`, `FavouriteCategoryEntity` (persisted as a string, see the
  comment at `list/domain/ListSortOrder.kt:7-11`), `FavouritesRepository`,
  `HistoryDao`, `LocalFavoritesObserver` and `HistoryLocalObserver`. It cannot
  move until the `@StringRes` is replaced.
- **`MangaListMapper` is the domain/UI boundary and it is on the wrong side of
  it.** It is in `list/domain/` but it produces `MangaListModel` subtypes
  (`list/ui/model/`), it takes `@ApplicationContext context: Context`
  (`list/domain/MangaListMapper.kt:34`), and the only thing it does with the
  Context is `context.resources.openRawResource(R.raw.tags_warnlist)` at
  `list/domain/MangaListMapper.kt:209-210` to load a tag warning list. That is
  a single asset read. It is a `Resource`/`AppPaths` interface away from being
  portable, and it is the widest fan-in class in the whole library flow
  (injected by every list view model).

### (b) Manga details and chapter list

```
DetailsActivity / DetailsExpressiveScreen (not mine, details/ui)   RED
  -> DetailsViewModel (not mine)                                   RED
       -> DetailsLoadUseCase                                       RED  (311 lines)
            -> Html.ImageGetter  (constructor param!)              RED  (android.text)
            -> mangaDataRepository.resolveIntent(MangaIntent)      RED  (core/nav, an Intent wrapper)
            -> LocalMangaRepository                                RED  (Uri)
            -> MangaRepository.Factory (not mine, core/parser)     -
            -> CachingMangaRepository (not mine) + coil3.CachePolicy RED
            -> RecoverMangaUseCase                                 GREEN*
            -> NetworkState (not mine, core/os)                    RED
            -> MihonExtensionManager                               RED  (cut on desktop, D3)
            -> CheckNewChaptersUseCase (tracker, not mine)         -
            => MangaDetails                                        GREEN*
       -> DetailsInteractor                                        GREEN* (96 lines, AppSettings only)
       -> ReadingTimeUseCase -> ReadingTimeEstimator               GREEN
            => ReadingTime                                         RED (android.content.res.Resources)
       -> ProgressUpdateUseCase                                    GREEN* (NetworkState)
       -> RelatedMangaUseCase                                      GREEN*
       -> BranchComparator                                         GREEN
       -> HistoryRepository                                        RED (withTransaction)
       -> FavouritesRepository                                     RED
```

`*` = GREEN by import scan, blocked by a project dependency listed in §1.

What this says:

- **`details/domain` is the cleanest package in my whole scope**: 6 of 7 files
  have no Android import at all, and `details/data/MangaDetails.kt` (154 lines,
  the chapter-list model, branch merging, override application) is fully GREEN.
- **The one RED file, `DetailsLoadUseCase.kt`, is RED for exactly one reason:
  the description is parsed into an Android `Spanned` inside the use case.**
  `android.text.Html`, `SpannableString`, `Spanned`, `ForegroundColorSpan` and
  `androidx.core.text.parseAsHtml` are all in service of the private
  `String.parseAsHtml` helper (`details/domain/DetailsLoadUseCase.kt:272-296`)
  and `Spanned.filterSpans()` (`:303-311`). Worse, `Html.ImageGetter` is a
  **constructor dependency** (`:54`), so the class cannot even be constructed
  without the Android text stack.
- **`MangaDetails.sourceDescription` is typed `CharSequence?`**
  (`details/data/MangaDetails.kt:21`, exposed as `description` at `:26-27`).
  Because it is `CharSequence` and not `Spanned`, the *model* is portable; only
  the producer is not. The desktop fix is to keep the raw HTML string in
  `MangaDetails` and render it at the UI layer, which is what a Compose reader
  has to do anyway. That is a behaviour-preserving change for Android too, but
  it touches every `details/ui` consumer of `description`.
- **`MangaIntent`** (`core/nav/`) is how `DetailsLoadUseCase` receives its
  input. It wraps an Android `Intent`. Desktop needs a plain data class here;
  this is small but it is on the critical path of the details flow.

### (c) Search within a source, and the filter/sort machinery

```
RemoteListFragment (not mine, remotelist/)                         RED
  -> RemoteListViewModel (not mine)                                RED
       -> FilterCoordinator                                        RED  (761 lines, @ViewModelScoped)
  -> FilterHeaderFragment -> FilterHeaderProducer                  RED
  -> FilterSheetFragment / TagsCatalogSheet / SaveFilterDialog     RED
  -> MihonFilterSheetFragment / MihonSortSheet                     RED  (cut, D3)

FilterCoordinator's own dependencies:
  savedStateHandle: SavedStateHandle                               RED  (androidx.lifecycle)
  mangaRepositoryFactory: MangaRepository.Factory                  -
  searchRepository: MangaSearchRepository                          RED  (SearchRecentSuggestions)
  savedFiltersRepository: SavedFiltersRepository                   RED  (SharedPreferences)
  @ApplicationContext context: Context                             RED  -> SourceSettings only
  lifecycle: ViewModelLifecycle                                    RED  (dagger.hilt.android)
  MihonFilterHost / MihonFilterMapper                              RED  (cut, D3)
  eu.kanade.tachiyomi.source.model.Filter / FilterList             RED  (cut, D3)
  FilterProperty, TagTitleComparator, PersistableFilter            GREEN
  MangaListFilter, SortOrder, MangaTag, MangaState, ContentRating,
    ContentType, Demographic  (kotatsu-parsers)                    GREEN
```

**What FilterCoordinator actually coordinates, and how much is UI.** It is not
a view model and it holds no view state. It is a `@ViewModelScoped` holder of
one `MutableStateFlow<MangaListFilter>` plus one
`MutableStateFlow<SortOrder>` (`filter/ui/FilterCoordinator.kt:75-77`), and it
projects those two into **12 `StateFlow<FilterProperty<T>>` facets** that the
sheets and the header chip row observe:

`sortOrder` (`:93`), `tags` (`:100`), `tagsExcluded` (`:117`), `authors`
(`:138`), `states` (`:152`), `contentRating` (`:169`), `contentTypes` (`:186`),
`demographics` (`:203`), `locale` (`:220`), `originalLocale` (`:237`),
`year` (`:258`), `yearRange` (`:269`), plus `savedFilters` (`:282`) and
`canSaveFilter` (`:300`).

Each facet is the same shape: combine "what the source supports"
(`repository.getFilterOptions()`, memoised in `filterOptions` at `:80`) with
"what the user picked" (`currentListFilter`), gated on
`repository.filterCapabilities`. The write side is ~20 `set`/`toggle` methods
(`:453-631`) that each do one `currentListFilter.update { copy(...) }`.

Line budget of the 761 lines:

| Part | Lines | Portable? |
|---|---|---|
| The 14 `StateFlow` facets and their combines | ~215 (`:90-302`) | yes, pure flow algebra over `kotatsu-parsers` model types |
| The ~20 `set`/`toggle`/`apply` writers | ~180 (`:444-631`) | yes |
| Tag ranking helpers (`getTopTags`, `getBottomTags`, `addFirstDistinct`) | ~60 (`:643-700`) | yes |
| Saved-filter persistence (`saveCurrentFilter`, `deleteSavedFilter`, `applySavedFilter`) | ~35 (`:476-508`) | yes, behind `SavedFiltersRepository` |
| **Mihon dynamic-filter path** | **~165** (`:304-349` + `:355-443` + `:707-730`) | **no, cut by D3** |
| `Fragment` lookup helpers `find` / `require` + `Owner` | ~25 (`:729-759`) | no, pure Android view tree walking |
| Wiring (`Context`->`SourceSettings`, `ViewModelLifecycle`->scope) | ~8 (`:63-73`) | no, three narrow swaps |

So roughly **500 of 761 lines are portable flow logic, ~165 are Mihon-only and
are deleted rather than ported, and under 40 lines are genuinely UI.** Deleting
the Mihon path removes the `eu.kanade.tachiyomi` imports, the
`MihonFilterHost`/`MihonFilterMapper` imports, the `init` block at `:311-322`
and `restoreSortFilter()` at `:326-336`, which in turn removes the only reason
`SourceSettings` (and therefore `Context`) is injected at all. **On desktop,
FilterCoordinator loses its `Context` dependency for free.** That is a
significant and non-obvious result: the largest file in my scope is one of the
easier ports.

**`MangaSearchRepository` is the mixed class on this path.** 4 files in
`search/domain`, 3 GREEN. `MangaSearchRepository.kt` is RED because query
*history* is stored in an Android `SearchRecentSuggestions`
(`search/domain/MangaSearchRepository.kt:5,33`) backed by a `ContentProvider`,
used by `getQuerySuggestion` (`:53-77`), `saveSearchQuery` (`:149`),
`clearSearchHistory` (`:153`), `deleteSearchQuery` (`:157`) and
`getSearchHistoryCount` (`:165-172`). Everything else in the class -
`getMangaSuggestion`, `getAuthorsSuggestion`, `getTagsSuggestion`,
`getRareTags`, `getTopTags`, `getSourcesSuggestion`, `getAuthors` - is DAO
queries and is portable. This class must be **split**, not ported: a portable
`MangaSuggestionRepository` and a platform `SearchHistoryStore` interface.
`FilterCoordinator:64` only needs the portable half.

### (d) Reading history, record and read, and "continue reading"

Write path (reader -> disk):

```
ReaderViewModel (not mine)                                         RED
  -> HistoryUpdateUseCase                                          GREEN-by-import, blocked twice
       -> ReaderState (reader/ui/ReaderState.kt:3)                 RED  (android.os.Parcelable, @Parcelize)
       -> core.util.ext.processLifecycleScope                      RED  (androidx.lifecycle)
       -> HistoryRepository.addOrUpdate (:115-124)                 RED  (withTransaction at :120)
            -> HistoryDao                                          RED  (@RawQuery, sqlEscapeString)
            -> HistoryEntity                                       AMBER (Room)
            -> settings.isIncognitoModeEnabled (:264)              RED  (D8)
```

Read path (disk -> library screen):

```
HistoryListFragment -> HistoryListViewModel                        RED
  -> HistoryRepository.observeAllWithHistory (:93)                 RED
       -> HistoryDao.observeAllImpl (@RawQuery, :194-195)          RED
       -> MangaQueryBuilder (not mine)                             RED
  -> HistoryLocalObserver                                          GREEN-by-import, blocked by LocalMangaIndex
  -> HistoryListQuickFilter                                        RED (NetworkState, MihonExtensionManager)
  -> MangaListMapper                                               RED
  -> MangaWithHistory / MangaHistory                               GREEN
```

"Continue reading" path:

```
MainViewModel (main/, not mine) :108,:115  -> HistoryRepository.getLastOrNull()
main/domain/ReadingResumeEnabledUseCase.kt:35 -> HistoryRepository.observeLast()
details/service/MangaPrefetchService.kt:66   -> HistoryRepository.getLastOrNull()
widget/continuereading/ContinueReadingWidget.kt:34 -> getLastOrNull()   (cut, widgets)
```

`getLastOrNull` (`history/data/HistoryRepository.kt:69-73`) and `observeLast`
(`:74-80`) are two small DAO-backed methods. There is no separate
"continue reading" subsystem to port; the feature is those two methods plus UI.

`HistoryRepository` is 317 lines with **7 `db.withTransaction { }` call sites**
(`:120, :133, :219, :224, :229, :235, :273`). That is more than half of the 11
`withTransaction` sites `PORTING_NOTES.md` §C counts for the whole app, and it
means history is where the Room-KMP transaction API swap has to be proven.

### Cycles and things that must move together

1. **`list/domain` <-> `list/ui/model` is a hard cycle and it crosses the
   agent split.** `MangaListMapper` (in `list/domain`) returns
   `MangaListModel`, `MangaGridModel`, `MangaCompactListModel`,
   `MangaDetailedListModel` (all in `list/ui/model`). `ListFilterOption` (in
   `list/domain`) is rendered by `ChipsView`. `FavoritesListQuickFilter` (in
   `favourites/domain`) returns a `ChipsView.ChipModel`. So `domain` depends on
   `ui`, not the other way round. **`list/ui/model/` has to be triaged before
   any of `favourites`, `history`, `explore` or `bookmarks` domain code can
   move**, and on the current `DECISIONS.md` §3 split nobody owns it. The good
   news is that the root of it, `list/ui/model/ListModel.kt`, is a 10-line
   interface with zero imports.
2. **`history/domain` -> `reader/ui`.** `HistoryUpdateUseCase` imports
   `ReaderState` from `reader/ui`, which is `@Parcelize`/`Parcelable`. History
   and the reader's state model move in the same commit, or `ReaderState` gets
   a platform-free twin first. This crosses into Agent C's territory.
3. **`favourites/domain` -> `local/data/index`.** `LocalFavoritesObserver`,
   `HistoryLocalObserver` and `LocalObserveMapper` all depend on
   `LocalMangaIndex`, which takes a `Context`
   (`local/data/index/LocalMangaIndex.kt`). Favourites, history and the local
   index are one move, unless `LocalMangaIndex` is interfaced first.
4. **`favourites/data/FavouritesDao` -> `core/db/MangaQueryBuilder` <-
   `history/data/HistoryDao`.** Both DAOs implement
   `MangaQueryBuilder.ConditionCallback` and both build `SupportSQLiteQuery`.
   Favourites and history cannot move independently of `core/db`, which is
   Agent A's. This is the single biggest cross-agent dependency in the plan and
   it is why `DECISIONS.md` D5 step 3 ("Room layer into `:shared`") must land
   before anything of mine.
5. **`explore/domain/ExploreRepository` -> `suggestions/domain/TagsBlacklist`**
   and **`details/domain/DetailsInteractor` -> `scrobbling/` + `tracker/`**.
   Suggestions, scrobbling and the tracker are all cut from v1
   (`DECISIONS.md` §2), but these are compile-time imports, so the classes must
   still resolve. Either those packages come along as dead code or these two
   files need the dependency removed.

## 3. Local storage

36 files, 3464 lines. This is the only source backend that survives to desktop
intact (`DECISIONS.md` §0), so it is the part of my scope that has to be right.

### 3.1 The headline result

**Almost none of `local/` needs content-URI semantics.** `Uri` is used here as a
*string-encoded (archive path, entry path) pair*, not as a content resolver
handle. There are exactly two places in `local/` that use a real Android
content URI, and both are on the import path, not the read path.

### 3.2 Where `Uri` / `DocumentFile` / SAF / `Context` actually appear

| Site | What it uses | Real need | Verdict |
|---|---|---|---|
| `local/data/input/LocalMangaParser.kt:56` `class LocalMangaParser(private val uri: Uri)` | `Uri.scheme`, `.schemeSpecificPart`, `.fragment`, `.path`, `.buildUpon()`, `.appendEncodedPath()`, `.toFile()` | a path plus an optional in-archive entry | `okio.Path` + entry, or `java.net.URI` which has all four accessors |
| `local/data/input/LocalMangaParser.kt:422-448` `resolveFsAndPath()` | branches on scheme, then `FileSystem.SYSTEM` or `FileSystem.SYSTEM.openZip(path)` | already okio | **already portable**, only the `Uri` accessors need swapping |
| `local/data/input/LocalMangaParser.kt:304-317` `Uri.child(path, resolve)` | builds a child URI, switching to the zip scheme when the parent is an archive | string building | trivial |
| `core/util/ext/Uri.kt:8-13` `URI_SCHEME_ZIP = "file+zip"`, plus legacy `cbz`/`zip` | the whole zip addressing scheme | a scheme string | independently confirmed: `file+zip://<absolutePath>#<entryPath>` (`core/util/ext/Uri.kt:25`), with `cbz://` and `zip://` accepted for backwards compatibility (`:15-17`). `PORTING_NOTES.md` §C carried `zip://` when I started and now says `file+zip://`, so another agent corrected it in parallel |
| `local/domain/model/LocalManga.kt:14` `file = manga.url.toUri().toFile()` | parse a `file://` string | `File(java.net.URI(url))` | trivial |
| `local/data/LocalMangaRepository.kt:138,142,146,168` | `manga.url.toUri()` / `.toFile()` | same | trivial |
| `local/data/output/LocalMangaDirOutput.kt`, `local/data/output/LocalMangaUtil.kt` | `toUri()`/`toFile()` round trips | same | trivial |
| `local/data/LocalStorageManager.kt:96-102` `resolveUri(uri)` | `uri.isFileUri()` -> `toFile()`, else `resolveFile(context)` | **SAF tree URI -> real path**, via `core/util/ext/ContentResolver.kt:16-32` which reflects into `android.os.storage.StorageManager` | **delete on desktop.** This exists only to undo SAF. A desktop file chooser returns a `java.io.File` already |
| `local/data/LocalStorageManager.kt:108-114` `takePermissions(uri)` | `contentResolver.takePersistableUriPermission` | SAF persistable grants | **delete on desktop** |
| `local/data/LocalStorageManager.kt:120-133` `hasExternalStoragePermission` | `Environment.isExternalStorageManager`, runtime permissions | Android storage permissions | **delete on desktop**, POSIX permissions are checked by `isReadable`/`isWriteable` already |
| `local/data/importer/SingleMangaImporter.kt:46,58,83,124` | `import(uri: Uri)`, `contentResolver.openFileDescriptor`, `DocumentFile.fromTreeUri` | **genuine content URI**: the user picks a file or tree through SAF | rewrite for desktop with a `java.io.File` entry point. The copy logic underneath (`copyTo`, `destinationDir`, `getOutputDir`) is already `File`-based |
| `local/data/importer/SingleMangaImporter.kt:97-120` `renderPdfToCbz` | `android.graphics.pdf.PdfRenderer`, `Bitmap` | PDF page rasterisation | **no JVM equivalent in the current deps.** PDF import is a separate decision; it is not in the `DECISIONS.md` §2 in-scope list, so cut it |
| `local/data/importer/MihonDownloads.kt:53` and throughout | `DocumentFile`, `ContentResolver` | tree walking over a SAF tree | the *algorithm* is filesystem-generic; only the tree abstraction is SAF. See 3.5 |
| `local/data/importer/MihonDownloads.kt:4` `android.util.Xml` | XmlPullParser factory for `ComicInfo.xml` | XML pull parsing | `org.xmlpull` is already imported directly at `:20`; the JDK has `javax.xml.stream`, and `xmlutil` is already a project dependency (`PORTING_NOTES.md` §E) |
| `local/data/input/EpubParser.kt:3` `android.util.Xml` | same, for EPUB OPF/NCX | same | same swap. EPUB is cut from v1 anyway (`DECISIONS.md` §2) |
| `local/data/index/LocalMangaIndex.kt:26,30` `@ApplicationContext context` | `context.getSharedPreferences("_local_index")` holding **one int**, `KEY_VERSION` (`:33-35`, `:104-108`) | remember an index schema version | a single int in the D8 `Settings` interface, or a row in the database it already owns |
| `local/data/LocalStorageCache.kt:31,37` `context` | `context.externalCacheDirs + context.cacheDir` | cache directory list | `AppPaths.cacheDirs` |
| `local/data/LocalStorageCache.kt:81-93` `set(url, bitmap: Bitmap)` | `android.graphics.Bitmap`, `compressToPNG` | write a decoded image to cache | Skiko `Image.encodeToData` per `PORTING_NOTES.md` §C. Agent C's territory |
| `local/data/LocalStorageCache.kt:106-113` | `android.webkit.MimeTypeMap.getFileExtensionFromUrl`, `StatFs` | extension guess, free space | `MimeTypes` table + `File.usableSpace` |
| `local/data/LocalStorageCache.kt:8` `com.tomclaw.cache.DiskLruCache` | the LRU itself | `PORTING_NOTES.md` §E already flags this as "check JVM-compat" | unresolved, see §7 risk 4 |

### 3.3 `LocalStorageManager` is `AppPaths` with three Android methods bolted on

195 lines. Every public method returns `java.io.File` or a size in bytes. The
directory sources are `context.filesDir`, `context.getExternalFilesDirs`,
`context.cacheDir`, `context.externalCacheDirs`
(`local/data/LocalStorageManager.kt:152-182`) plus
`settings.userSpecifiedMangaDirectories`. On desktop that is one XDG lookup:
`$XDG_DATA_HOME/dropsauce/manga`, `$XDG_CACHE_HOME/dropsauce`, plus the
user-configured dirs.

The only genuinely Android methods are `resolveUri` (`:96`), `takePermissions`
(`:108`), `hasExternalStoragePermission` (`:120`) and the `contentResolver`
accessor (`:45-46`), and all four exist to serve SAF. **`LocalStorageManager`
is the `AppPaths` interface `DECISIONS.md` D9 asks for; it just has to be
declared as one and have those four members dropped from the shared side.**

### 3.4 The on-disk format

A "local manga" is one of three shapes, chosen by
`local/data/output/LocalMangaOutput.kt:87-137`:

1. **A directory** (`DownloadFormat.MULTIPLE_CBZ`) - `<root>/<Title>/` holding
   `index.json` plus one `.cbz` per chapter. Written by `LocalMangaDirOutput`.
2. **A single archive** (`DownloadFormat.SINGLE_CBZ`) - `<root>/<Title>.cbz`,
   a zip holding `index.json` plus every page. Written by `LocalMangaZipOutput`.
3. **An EPUB** (novels only) - `<root>/<Title>.epub`. Written by
   `LocalNovelEpubOutput`. Cut from v1.

Title collisions append `_1`, `_2`, ... and each candidate is probed by reading
its `index.json` and comparing `manga.id`
(`local/data/output/LocalMangaOutput.kt:96-99`, `:138-147`).

**`index.json`** is the metadata file, entry name `index.json`
(`local/data/output/LocalMangaOutput.kt:60`). It is hand-rolled `org.json`, not
kotlinx-serialization (`local/data/MangaIndex.kt:33-36`). Top-level keys
(`local/data/MangaIndex.kt:177-206`): `id`, `title`, `title_alt`, `alt_titles`,
`url`, `public_url`, `author`, `authors`, `cover`, `description`, `rating`,
`content_rating`, `nsfw`, `state`, `source`, `cover_large`, `tags`,
`chapters`, `cover_entry`, `app_id`, `app_version`. Per-chapter keys: `number`,
`volume`, `name`, `uploadDate`, `scanlator`, `branch`, `entries`, `file`.

Page entry names inside an archive follow
`FILENAME_PATTERN = "%08d_%04d%04d"`
(`local/data/output/LocalMangaZipOutput.kt:149`), formatted as
`(branch.hashCode(), chapterIndex + 1, pageNumber)`
(`:66`). The cover is the same pattern with `(0, 0, 0)` (`:51`) and its name is
recorded in `index.json` under `cover_entry` (`:60`).

**Can desktop read a CBZ library the Android app produced, and vice versa?**

Yes for the bytes, with three caveats, and one of them is a real
interoperability break:

- The container is a plain zip and the index is plain JSON. `MangaIndex` uses
  `org.json`, which `DECISIONS.md` D16 already plans to add for desktop.
  Nothing in the format is Android-specific.
- **`index.json` records `app_id` and `app_version` from `BuildConfig`**
  (`local/data/MangaIndex.kt:70-71`). `BuildConfig` is generated by the Android
  Gradle Plugin and **does not exist in a KMP `jvm()` source set**. This is a
  compile blocker my import scan did not catch, because `BuildConfig` is in
  `org.koitharu.kotatsu`, not `android.*`. Four files in my scope reference it:
  `local/data/MangaIndex.kt`, `core/util/ext/Collections.kt`,
  `core/util/ext/Throwable.kt`, `core/util/ShareHelper.kt`. `:shared` needs a
  small `AppInfo` (id, versionCode, isDebug) supplied per platform.
- **`branch.hashCode()` in the page entry name is the interop break.**
  `local/data/output/LocalMangaZipOutput.kt:66` names every page with
  `chapter.value.branch.hashCode()`. `String.hashCode()` is specified by the
  JLS and is stable across JVM and ART, and a null branch gives 0, so this
  particular one is safe. But it is a latent hazard: the whole page ordering of
  a downloaded manga is keyed on a hash, and `local/data/output/LocalMangaZipOutput.kt:177-178`
  re-matches entries against regex patterns built from it during a merge. Worth
  an explicit round-trip test rather than an assumption.
- `manga.url` inside `index.json` is the **remote** URL (`KEY_URL`), so it is
  source-name-dependent. A CBZ downloaded on Android from a `MIHON_*` source
  carries `source = "MIHON_XXX"`, which desktop resolves to
  `MissingMangaSource` (`DECISIONS.md` D1 already covers this). The pages still
  read; the source attribution does not.

Verdict: **a desktop build can read an Android-produced CBZ library and write
one Android can read, as long as `app_id`/`app_version` get a platform-supplied
value and `org.json` is on the classpath.** That should be the first end-to-end
test of the whole port, because it is cheap and it proves the format claim.

### 3.5 `MangaLock`, `SingleMangaImporter`, `MihonDownloads`

**`MangaLock`** (`local/domain/MangaLock.kt`, 9 lines) is
`@Singleton class MangaLock : MultiMutex<Manga>()`. `MultiMutex`
(`core/util/MultiMutex.kt`) is a `ConcurrentHashMap<T, Mutex>`. It assumes
nothing about the filesystem at all: it is an **in-process** lock keyed on a
`Manga`. Two consequences for desktop:

- It is GREEN apart from one `androidx.annotation.VisibleForTesting`
  (`core/util/MultiMutex.kt:3`). Moves as-is.
- It does **not** protect the library from a second process. On Android that is
  fine, because there is one app process. On Linux a user can launch a second
  instance of the app, and then two processes write the same `.cbz` with no
  lock between them. That is not a port blocker, but it is a behaviour
  difference worth an explicit single-instance check at startup.

**`SingleMangaImporter`** (233 lines) assumes: (a) the input is a SAF `Uri`,
either a single document or a tree (`:46`, `:124-125`); (b) it can open a
`ParcelFileDescriptor` on it for PDF rendering (`:87`, `:151`); (c) the
destination is a plain `java.io.File` directory from `LocalStorageManager`
(`:195`, `:211`). Only (a) and (b) are Android. The desktop version takes a
`File`, keeps `importFile`/`importDirectory`/`copyTo`/`destinationDir`
essentially unchanged, and drops the PDF branch.

**`MihonDownloads`** (265 lines) is a pure tree-shape recogniser: it looks for
`downloads/<Source>/<Manga>/<Chapter>/000.jpg` or `<Chapter>.cbz`, plus
`ComicInfo.xml` per chapter (`local/data/importer/MihonDownloads.kt:26-40`). It
assumes: a hierarchical tree it can list and descend two levels
(`CONTAINER_DEPTH = 2`, `:40`); names it can test for `_tmp` / `.tmp` suffixes
(`:37`, `:42-43`); the ability to open an `InputStream` per entry. Every one of
those is expressible over `java.io.File`. **The file is RED only because it is
written against `DocumentFile` rather than against an abstraction.** It is the
best candidate in `local/` for "introduce a two-method `FileNode` interface and
the class becomes portable", and it is worth doing even though Mihon itself is
cut, because a desktop user migrating from Mihon on Android has a downloads
folder on disk.

## 4. Caching

Four files in `core/cache/` (201 lines total) plus `CachingMangaRepository`
(`core/parser/`, 103 lines, Agent B's directory but on my flows).

| File | Verdict | Why |
|---|---|---|
| `core/cache/SafeDeferred.kt` | **GREEN** | 22 lines, wraps `Deferred<Result<T>>`. Zero imports beyond `kotlinx.coroutines.Deferred` |
| `core/cache/ExpiringLruCache.kt` | **GREEN** | no Android import; delegates to `SynchronizedSieveCache` |
| `core/util/SynchronizedSieveCache.kt` | **AMBER** | `androidx.collection.SieveCache` (`:3`). `collection-jvm` exists, so this is a no-op |
| `core/cache/ExpiringValue.kt` | **RED, trivially** | `android.os.SystemClock.elapsedRealtime()` at `:3`, `:11`, `:14`. Swap for `System.nanoTime()`. Note `elapsedRealtime` is monotonic and includes deep sleep; `nanoTime` is monotonic and does not sleep-advance. For a 5-to-10-minute in-memory TTL on a desktop that is equivalent |
| `core/cache/MemoryContentCache.kt` | **RED, structurally** | `android.app.Application` (`:3`), `ComponentCallbacks2` (`:4`), `Configuration` (`:5`) |

### Is anything dependent on Android memory pressure?

**Yes, and it is the only real question here.** `MemoryContentCache`
*implements* `ComponentCallbacks2` and registers itself on the `Application`
(`core/cache/MemoryContentCache.kt:15`, `:25-27`). `onTrimMemory(level)`
(`:63-67`) is the sole eviction trigger beyond the per-entry TTL, and it maps
levels to actions at `:70-82`:

- `TRIM_MEMORY_RUNNING_CRITICAL` / `COMPLETE` / `MODERATE` -> `cache.clear()`
- `TRIM_MEMORY_UI_HIDDEN` / `RUNNING_LOW` / `BACKGROUND` -> `trimToSize(1)`
- anything else -> `trimToSize(maxSize / 2)`

There is **no desktop equivalent of `onTrimMemory`.** The honest desktop
behaviour is: keep the TTL, keep the LRU bound, drop the trim callback. The
caches are tiny by construction (4, 4 and 3 entries on a non-low-RAM device,
`:18-23`), so losing pressure-driven eviction costs at most a handful of
`Manga` objects and page lists. The Android app must keep the callback.

The other `Application` use is
`application.isLowRamDevice()` (`:17`, via
`core/util/ext/Android.kt`) which picks 1 vs 4 entries. On desktop that is a
constant.

So the shared shape is: `MemoryContentCache` becomes a platform-free class with
public `clear()` / `trimToSize(n)` methods, and the Android module keeps a
thin `ComponentCallbacks2` adapter that calls them. That is a ~20 line split
of an 89 line file.

### Cache key strategy

One key type for all three caches:
`MemoryContentCache.Key(source: MangaSource, url: String)`
(`core/cache/MemoryContentCache.kt:85-88`), a `data class`. Both components come
from `kotatsu-parsers` model types, so the key is portable.

- details cache: keyed on `(source, manga.url)`, 5 minute TTL
- pages cache: keyed on `(source, chapter.url)`, 10 minute TTL
- related cache: keyed on `(source, seed.url)`, 10 minute TTL

Eviction is SIEVE (`androidx.collection.SieveCache`) not classic LRU, wrapped
in a `synchronized` block. `removeAll(source)` scans with
`cache.removeIf { key, _ -> key.source == source }`
(`core/cache/ExpiringLruCache.kt:41`), which is how "a source's settings
changed, drop its cache" works.

`ExpiringValue` stores the *absolute* expiry timestamp at construction
(`core/cache/ExpiringValue.kt:11`) and `ExpiringLruCache.get` removes the entry
lazily on read (`core/cache/ExpiringLruCache.kt:17-22`). Nothing sweeps in the
background, so an untouched stale entry stays resident until the LRU evicts it
or `onTrimMemory` fires. On desktop, with no `onTrimMemory`, a stale entry can
sit for the process lifetime. Bounded by `maxSize`, so it is a few objects, not
a leak.

### `CachingMangaRepository`

`core/parser/CachingMangaRepository.kt` is 103 lines and is the class every
remote repository extends. Two Android couplings, both shallow:

- `android.util.Log` (`:3`) - `PORTING_NOTES.md` §C already plans a `Logger`
  interface for this
- `androidx.collection.MutableLongSet` (`:4`) - `collection-jvm`, no-op
- `coil3.request.CachePolicy` (`:5`) - Coil 3 is multiplatform
  (`PORTING_NOTES.md` §E), but using an *image loader's* enum as the cache
  policy type for a *metadata* repository is a wart. It leaks into
  `DetailsLoadUseCase` (`details/domain/DetailsLoadUseCase.kt:8`,`:265-269`)
  and makes a domain use case depend on Coil. A three-value enum of its own
  would remove that. Not required for the port, worth doing during it.

Concurrency: three `MultiMutex` instances keyed on manga/chapter id
(`:35`, `:44`, `:53`) so two callers asking for the same details share one
network trip. `MultiMutex` is AMBER (§3.5). Portable.

**Verdict: the whole caching layer is platform-free apart from one clock call,
one `Application` reference and one `Log` call.** It is the single easiest
subsystem in my scope and it is a good candidate for the first non-trivial
`:shared` move, because `CachingMangaRepository` sits underneath the details
flow and proves the coroutines + `kotatsu-parsers` model plumbing works in
`commonMain` before anything harder depends on it.

## 5. Utilities

`core/util/` is 60 files: 32 in `ext/` (2824 lines) and 28 at the top level or
in `iterator/` / `progress/`.

### 5.1 `core/util/ext/`, the three buckets

**MOVABLE - pure Kotlin / coroutines / okio / OkHttp (3 files, 97 lines)**

| File | Lines | Note |
|---|---:|---|
| `ext/Http.kt` | 46 | `org.json` + jsoup + OkHttp. Needs `org.json` on the desktop classpath (`DECISIONS.md` D16 already has it) |
| `ext/MimeType.kt` | 33 | a `@JvmInline value class MimeType` plus OkHttp `MediaType` bridging |
| `ext/Other.kt` | 18 | reflection + serialization probe |

**ANDROID-ONLY - stay in `:app` entirely (17 files, 1725 lines)**

`ext/Android.kt` (281), `ext/View.kt` (294), `ext/Haptics.kt` (138),
`ext/Bundle.kt` (112), `ext/RecyclerView.kt` (112), `ext/Coil.kt` (114),
`ext/Menu.kt` (105), `ext/ContentResolver.kt` (91), `ext/WorkManager.kt` (88),
`ext/Insets.kt` (84), `ext/Theme.kt` (74), `ext/TextView.kt` (65),
`ext/LocaleList.kt` (53), `ext/FlowObserver.kt` (42), `ext/Fragment.kt` (38),
`ext/Cursor.kt` (20), `ext/Graphics.kt` (14).

Two notes. `ext/ContentResolver.kt` exists *only* to turn a SAF tree URI back
into a `java.io.File` by reflecting into `android.os.storage.StorageManager`
(`core/util/ext/ContentResolver.kt:16-91`); desktop deletes it outright rather
than porting it. `ext/Preferences.kt` (68) is listed under MIXED below rather
than here because of what its `observeChanges` shape implies for D8.

**MIXED - must be split (12 files, 1002 lines). This is the fiddly work.**

Named specifically, with what goes each way:

| File | Lines | Portable part | Android part |
|---|---:|---|---|
| `ext/Flow.kt` | 153 | everything except one function: `onFirst`, `onEachIndexed`, `mapItems`, `requireValue`, `flatten`, `zipWithPrevious`, `tickerFlow`, `withTicker`, the two 6- and 7-arity `combine` overloads (`:104`, `:124`), `firstNotNull`, `flattenLatest`, `asFlow`, `append` | `throttle(timeoutMillis)` at `:50-65` uses `android.os.SystemClock`. One clock call in a 153-line file. **Highest value split in the whole area** because `flattenLatest` and the wide `combine`s are used by every view model on my flows |
| `ext/Collections.kt` | 79 | all 12 functions | none, except `androidx.collection.LongSet`/`ArraySet` (AMBER, `collection-jvm`) and a `BuildConfig` reference |
| `ext/Throwable.kt` | 229 | `getCauseUrl` (`:154`), `isNetworkError` (`:192`), `isWebViewUnavailable` (`:200`) | `getDisplayMessage(resources)` (`:59`), `getDisplayMessageOrNull` (`:62`), `getHttpDisplayMessage` (`:171`), `mapDisplayMessage` (`:179`), `parseMessage(resources)` (`:205`), `@DrawableRes getDisplayIcon` (`:138-139`). Roughly 180 of 229 lines are `Resources`-based error-message formatting. The split is a `Throwable -> ErrorKind` classifier in `:shared` and the string lookup in the UI layer |
| `ext/File.kt` | 116 | `subdir`, `takeIfReadable`, `takeIfWriteable`, `isNotEmpty`, `ZipFile.readText`, `deleteAwait`, `computeSize`, `withChildren`, `creationTime`, `isReadable`, `isWriteable` | `getStorageName(context)` (`:41`), `Uri.toFileOrNull` (`:55`), `ContentResolver.resolveName` (`:61`), and the `Build.VERSION.SDK_INT` branches in `FileSequence` (`:82`) and `walkCompat` (`:96`). On a JVM the branches collapse to the NIO side. **This file is load-bearing for `local/`**: `takeIfWriteable`, `computeSize`, `withChildren`, `creationTime` and `deleteAwait` are all used by `LocalStorageManager` and `LocalMangaRepository` |
| `ext/IO.kt` | 67 | `withProgress`, `cancellable`, `writeAllCancellable`, `readByteBuffer`, `toByteBuffer`, `FileSystem.isDirectory`, `FileSystem.isRegularFile` | one function: `ContentResolver.openSource(uri)` (`:64-65`) |
| `ext/String.kt` | 73 | `toUUIDOrNull`, `transliterate`, `toFileNameSafe`, `sanitize`, `isReplacement`, `isHttpUrl` | one function: `joinToStringWithLimit(context, ...)` (`:52`). `toFileNameSafe` is used by every local output writer |
| `ext/Date.kt` | 79 | `calculateTimeAgo`, `toInstantOrNull`, `toMillis`, `groupByDateBucket` | one function: `Resources.formatDurationShort` (`:40`) |
| `ext/Coroutines.kt` | 50 | `getCompletionResultOrNull` (`:26`), `cancelChildrenAndJoin` (`:35`) | `processLifecycleScope` (`:20`), `RetainedLifecycle.lifecycleScope` (`:23`), `BroadcastReceiver.goAsync` (`:41`). `processLifecycleScope` is what blocks `HistoryUpdateUseCase` (§1) and it is an app-lifetime `CoroutineScope`, which on desktop is just the app's own supervisor scope (`DECISIONS.md` D11) |
| `ext/EventFlow.kt` | 18 | both functions | `androidx.annotation.AnyThread` only. AMBER really, listed here because `Event`/`EventFlow` is the app-wide one-shot-event idiom and its fate should be decided once |
| `ext/Preferences.kt` | 68 | `getEnumValue`/`putEnumValue` **semantics**, `JSONArray.toStringSet` (`:61`) | all of it is typed on `SharedPreferences`. This file is the concrete shape that D8's `Settings` interface has to reproduce: `observeChanges(): Flow<String?>` (`:28`) and `observe(key) { producer }` (`:38`) are what ~200 call sites consume |
| `ext/Resources.kt` | 32 | none meaningfully | Android. Listed as mixed only because `getQuantityStringSafe` (`:18`) encodes a plural fallback rule the desktop i18n layer still needs |
| `ext/Uri.kt` | 38 | the scheme constants and all the predicate logic | typed on `android.net.Uri`. See §3.2: this is the local-storage addressing scheme and it is the file to rewrite first, not to split |

### 5.2 `core/util/` top level, `iterator/`, `progress/`

**Movable (12 files)**: `AlphanumComparator.kt` (63, natural-order sort used by
every local directory listing), `CancellableSource.kt` (18),
`CompositeResult.kt` (59), `ContinuationResumeRunnable.kt` (13), `Event.kt`
(36), `MediatorStateFlow.kt` (39), `iterator/MappingIterator.kt` (11),
`progress/Progress.kt` (41), `progress/ProgressDeferred.kt` (16),
`progress/ProgressResponseBody.kt` (53), `MultiMutex.kt` (AMBER),
`SynchronizedSieveCache.kt` (AMBER).

`AcraCoroutineErrorHandler.kt` (14) is import-clean but it is the app's global
`CoroutineExceptionHandler` and its name promises ACRA; it belongs in `:app`.

**Android-only (15 files)**: `AcraScreenLogger.kt`, `FileSize.kt`,
`IdlingDetector.kt`, `KotatsuColors.kt`, `LocaleComparator.kt`,
`LocaleStringComparator.kt`, `MimeTypes.kt`, `RecyclerViewScrollCallback.kt`,
`RetainedLifecycleCoroutineScope.kt`, `ShareHelper.kt`, `Throttler.kt`,
`ViewBadge.kt`, `progress/ImageRequestIndicatorListener.kt`,
`progress/IntPercentLabelFormatter.kt`, `progress/RealtimeEtaEstimator.kt`.

Four of those are shallow enough to be worth rescuing rather than
reimplementing:

- `Throttler.kt` and `progress/RealtimeEtaEstimator.kt` - `SystemClock` only.
- `LocaleComparator.kt` / `LocaleStringComparator.kt` - `androidx.core.os.LocaleListCompat`
  only, to rank locales by the user's preference order. `java.util.Locale` plus
  a supplied preference list does the same. `LocaleStringComparator` is a
  dependency of `details/domain/BranchComparator.kt`, so it is on the details
  flow.
- `MimeTypes.kt` - `android.webkit.MimeTypeMap` + Coil's map. A static
  extension-to-type table covers everything `local/` actually looks up. It is a
  dependency of `local/data/input/LocalMangaParser.kt` and
  `bookmarks/domain/Bookmark.kt`, so it blocks two features.
- `FileSize.kt` - `Context` only for `Formatter.formatFileSize`. The value type
  is portable; only the formatting is not.

### 5.3 The pattern

Nine of the twelve MIXED files are mixed because of **one or two functions in
an otherwise clean file**: `ext/Flow.kt` (one `SystemClock`), `ext/IO.kt` (one
`ContentResolver`), `ext/String.kt` (one `Context`), `ext/Date.kt` (one
`Resources`), `ext/Collections.kt` (one `BuildConfig`). Splitting them is
mechanical: move the Android function into a new
`core/util/ext/<Name>Android.kt` in `:app`, leave the rest. The three that need
thought are `ext/Throwable.kt` (an error-to-message policy that has to be
re-expressed), `ext/Preferences.kt` (the D8 `Settings` contract) and
`ext/File.kt` (the `local/` dependency surface).

## 6. The favourites / history / bookmarks domain

These three are the v1 library (`DECISIONS.md` §2 "Library: favourites with
categories, reading history, resume where you left off").

### 6.1 Favourites

41 files: 6 `data/`, 5 `domain/`, 30 `ui/`.

Domain operations, all on `favourites/domain/FavouritesRepository.kt` (342
lines, 38 public members):

- reads: `getAllManga`, `getLastManga(limit)`, `search(query, kind, limit)`,
  `getManga(categoryId)`, `observeAll` x3 overloads (`:63`, `:82`, `:96`),
  `observeMangaCount`, `observeCategories` x2, `observeCategoriesForLibrary`,
  `observeCategoriesWithCovers`, `getAllFavoritesCovers`, `observeCategory`,
  `getCategory`, `isFavorite`, `getCategoriesIds`, `findPopularSources`,
  `findSources`, `getMostUpdatedCategories`
- category writes: `createCategory`, `updateCategory` x2,
  `updateCategoryTracking`, `setCategoryOrder`, `reorderCategories`,
  `removeCategories`, `setNewChaptersDownloadCategories`,
  `enableNewChaptersDownloadForTrackedCategories`, `isNewChaptersDownloadEnabled`
- membership writes: `addToCategory`, `removeFromFavourites`,
  `removeFromCategory`, with undo via `ReversibleHandle` and private
  `recoverToFavourites` / `recoverToCategory` (`:327`, `:335`)

**Is the logic free of UI?** Mostly yes. The repository is clean apart from
`androidx.room.withTransaction` and `ReversibleHandle` (which lives in
`core/ui/util/`, is an Android-free functional interface by name but sits in a
UI package - it should be checked and moved). `DuplicatesUseCase` (favourites
duplicate detection) is GREEN. `LocalFavoritesObserver` is GREEN-by-import.

**What lives in view models that should not.** Three things:

1. `FavouritesListViewModel.mapList` (`favourites/ui/list/FavouritesListViewModel.kt:162-192`,
   ~30 lines) applies the pinned-items overlay by `copy(isPinned = true)` on
   three separate model subtypes. That is list-composition logic, not
   rendering.
2. `FavouritesListViewModel.setPinned` (`:199-207`) and `takeIfDefaultState`
   (`:226-227`) hold the *rule* that pins only apply in the unfiltered state.
   That is a domain rule living in a view model, and it is duplicated between
   `observeFavorites()` and `content`.
3. Pinned ids are stored in `AppSettings` under a per-category key
   (`AppSettings.KEY_FAVORITES_PINNED + categoryId`, `:85-94`), read and written
   directly from the view model. That is a repository responsibility.

Estimated extraction: **~60 lines out of `FavouritesListViewModel`'s 250**, into
a `FavouritesListUseCase`. `FavouritesCategoriesViewModel` (139 lines) is
thinner: `toUiList` is genuine presentation, `saveOrder` (`:64-75`) is a
one-call passthrough with a cancel-previous guard worth keeping.
`FavouritesContainerViewModel` (84), `FavouritesCategoryEditViewModel` (79),
`FavoriteDialogViewModel` (92) and `DuplicatesViewModel` (198, backed by the
GREEN `DuplicatesUseCase`) are all thin.

### 6.2 History

15 files: 6 `data/`, 4 `domain/`, 5 `ui/`.

Domain operations, on `history/data/HistoryRepository.kt` (317 lines) - note it
is in `data/`, not `domain/`, which is inconsistent with favourites:

- read: `getList(offset, limit)`, `search`, `getLastOrNull`, `observeLast`,
  `observeAll` x2, `observeAllWithHistory(order, filters, limit)`, `observeOne`,
  `getOne`, `getProgress(mangaId, mode)`, `getPopularTags`, `getPopularSources`,
  `shouldSkip` / `observeShouldSkip` (incognito)
- write: `addOrUpdate`, `advanceFromTracking`, `clear`, `delete(manga)`,
  `delete(ids)` with undo, `deleteAfter(minDate)`, `deleteNotFavorite`,
  `deleteOrSwap`

`history/domain/` is 4 files: `HistoryUpdateUseCase` (46, the reader write
path), `MarkAsReadUseCase` (50, GREEN), `HistoryListQuickFilter` (43),
`MangaWithHistory` (GREEN model).

**What lives in the view model.** `HistoryListViewModel` (244 lines) is the
worst offender in my scope. Two blocks are domain logic:

- `mapList` (`history/ui/HistoryListViewModel.kt:167-204`, ~38 lines) walks the
  sorted list and inserts date-bucket headers by comparing consecutive items.
  That is grouping, not rendering.
- `MangaHistory.header(order)` (`:206-226`, ~21 lines) is the bucket **policy**:
  which sort orders group, and for `PROGRESS` the actual thresholds
  (`isCompleted`, `0f..0.01f` -> planned, `0f..1f` -> reading). Those thresholds
  are business rules sitting in a private function of a Hilt view model, and
  they are expressed as `R.string` ids.

Estimated extraction: **~60 lines out of 244**, into a
`HistoryGroupingUseCase` returning a platform-free bucket enum. The
`calculateTimeAgo` it calls is already in the portable half of
`core/util/ext/Date.kt` (§5).

### 6.3 Bookmarks

11 files: 3 `data/`, 2 `domain/`, 6 `ui/`.

`bookmarks/domain/BookmarksRepository.kt` is the whole domain:
`observeBookmark(manga, chapterId, page)`, `observeBookmarks(manga)`,
`observeBookmarks()` (grouped by manga), `addBookmark`, `updateBookmark`,
`removeBookmark` x2, `removeBookmarks(ids)` with undo, plus the undo recovery.

This is the cleanest of the three. It is RED for exactly two imports:
`android.database.SQLException` (`bookmarks/domain/BookmarksRepository.kt:3`)
and `androidx.room.withTransaction` (`:4`). `Bookmark.kt` and the entity
mapping are GREEN and AMBER respectively. `AllBookmarksViewModel` is 85 lines
and is a passthrough.

Note that `android.database.SQLException` is caught, not thrown, and Room KMP
surfaces `androidx.sqlite.SQLiteException` instead. That is a one-line change
but it is a **behaviour** change if the catch is load-bearing; check what it
guards before swapping it.

### 6.4 Summary

| Feature | Domain free of UI? | Logic to extract from VMs |
|---|---|---|
| Favourites | mostly | ~60 lines from `FavouritesListViewModel` (pin overlay, pin-applies-only-unfiltered rule, pin storage) |
| History | mostly, but the repository sits in `data/` | ~60 lines from `HistoryListViewModel` (date-bucket grouping, bucket policy incl. progress thresholds) |
| Bookmarks | yes | none |

Across all three the shared blocker is the same three things and not the
feature code: `withTransaction` (11 sites app-wide, 7 of them in
`HistoryRepository`), `@RawQuery` + `SupportSQLiteQuery` + `sqlEscapeString`
(2 DAOs), and `ListSortOrder`/`ListFilterOption` carrying resource ids.

## 7. What you will get wrong, and the move order

### 7.1 Top five risks

**R1. The `domain` packages depend on the `ui` packages, not the other way
round, and the package that causes it belongs to nobody.**

`list/domain/MangaListMapper.kt` returns `list/ui/model/MangaListModel`.
`list/domain/ListFilterOption.kt` exposes `@DrawableRes`/`@StringRes` for a
chip row. `favourites/domain/FavoritesListQuickFilter.kt` returns
`core/ui/widgets/ChipsView.ChipModel`. `bookmarks/domain/Bookmark.kt`
implements `list/ui/model/ListModel`. Every "move the domain layer first" plan
(`DECISIONS.md` D5 step 2) assumes the arrow points the other way. `list/ui/`
is 56 files and the `DECISIONS.md` §3 agent split assigns it to nobody.

*Early test, cheap:* take `bookmarks/` alone, the smallest of the three v1
features, and compute the transitive closure of its `org.koitharu.kotatsu.*`
imports. If that closure pulls in `core/ui/`, the domain-first plan is wrong
for every feature, not just bookmarks. `bookmarks/domain/Bookmark.kt` already
imports `list.ui.model.ListModel` and `core.util.ext.isImage`, so the closure
is non-trivial before you start. Do this before writing a line of `:shared`.

**R2. Resource ids are baked into domain types, and `PORTING_NOTES.md`'s
coupling numbers do not see them.**

67 of my 239 files import `org.koitharu.kotatsu.R`, which matches none of the
prefixes §A grepped for. Three of them are domain enums on the library's
critical path (`list/domain/ListSortOrder.kt:41-43,:52-60`,
`list/domain/ListFilterOption.kt:19-31`, `explore/data/SourcesSortOrder.kt:3`).
`core/util/ext/Throwable.kt` is 229 lines of which ~180 are
`Resources`-formatted error messages. `list/domain/MangaListMapper.kt:209-210`
holds a `Context` purely to read one raw resource.

*Early test:* `grep -c '^import org.koitharu.kotatsu.R$'` over every `data/`
and `domain/` directory in the repo, not just mine, and add that number to
`PORTING_NOTES.md` §A. Then pick `ListSortOrder` and convert its
`titleResId: Int` to a stable string key in `:app` only. Count the call sites
you had to touch. That number, multiplied out, is the real cost of D9.

**R3. `withTransaction`, `@RawQuery` and `sqlEscapeString` all land on the two
DAOs the library cannot function without.**

`HistoryRepository` has 7 of the app's 11 `withTransaction` sites
(`history/data/HistoryRepository.kt:120,133,219,224,229,235,273`).
`FavouritesDao` and `HistoryDao` are the only two `@RawQuery` DAOs in my scope
and both use `android.database.DatabaseUtils.sqlEscapeString` to interpolate a
source name and a state name straight into a WHERE clause
(`favourites/data/FavouritesDao.kt:280-281`, `history/data/HistoryDao.kt:205-206`).
Room KMP exposes neither API. A hand-rolled `sqlEscapeString` that gets quoting
wrong is a SQL injection into the user's own library from a source name.

*Early test:* convert **`HistoryDao` only** to `RoomRawQuery` plus a
hand-written escaper, keep it on Android, and run the existing history screen.
Write a unit test for the escaper with `'`, `''`, `\`, a NUL byte and a
multi-byte character before you trust it. If `HistoryDao` converts cleanly,
`FavouritesDao` will too; if it does not, `DECISIONS.md` D5 step 3 is in
trouble and you have found out at the cheapest possible moment.

**R4. Two symbol classes are invisible to an `android.*` import scan and both
are on the local-reading path.**

`printStackTraceDebug` is used by **87 files** and is declared in neither
`src/main` nor any Android package: it has one definition per build type
(`app/src/debug/kotlin/org/koitharu/kotatsu/core/util/ext/Debug.kt:5`,
`app/src/release/kotlin/.../Debug.kt:6`, the latter compiling to `Unit`).
`BuildConfig` is AGP-generated and is referenced by
`local/data/MangaIndex.kt:70-71` (it writes `app_id` and `app_version` into
every `index.json`), `core/util/ext/Collections.kt`,
`core/util/ext/Throwable.kt` and `core/util/ShareHelper.kt`.

*Early test with a positive control:* add a throwaway JVM source file to any
JVM-only context that references `printStackTraceDebug` and `BuildConfig`, and
confirm it **fails** to compile. That proves your detector works. Then re-run
the same probe after introducing the `Logger` and `AppInfo` seams and confirm
it passes. Do not conclude from "no `android.*` import" that a file is clean;
that inference is exactly what produced the wrong number in
`PORTING_NOTES.md` §A.

**R5. The page/cover disk cache ships as an AAR, so the reader's image cache
cannot resolve on a JVM target even though its code is portable.**

`local/data/LocalStorageCache.kt:8` uses `com.tomclaw.cache.DiskLruCache` from
`com.github.solkin:disk-lru-cache:1.5`
(`gradle/libs.versions.toml:94`). `PORTING_NOTES.md` §E lists this as "check
JVM-compat". It is resolved. The Gradle module metadata publishes exactly two
variants, both with `org.gradle.libraryelements = aar`, and no `jar` or
`platform.type = jvm` variant, so **a JVM-only target cannot resolve it at
all**. However, disassembling the AAR's `classes.jar` (10 classes:
`DiskLruCache`, `Journal`, `Record`, `RecordComparator`, `FileManager`,
`SimpleFileManager`, `Logger`, `SimpleLogger`, `RecordNotFoundException`,
`BuildConfig`) and grepping the full constant pool finds **zero** references to
any `android/*` class, against a positive control that finds `java/io/File`,
`java/io/DataInputStream` and friends. The bytecode is pure JVM; only the
packaging is Android.

It lives in my directory but it serves the reader: `AppModule` builds two
instances (pages, favicons, `ARCHITECTURE.md` §3) and the consumers are
`reader/domain/PageLoader.kt`, `details/ui/pager/pages/MangaPageFetcher.kt` and
`download/ui/worker/DownloadWorker.kt`. So this blocks Agent C's page pipeline,
not CBZ reading, and it should be handed to whoever owns the reader.

*Early test:* in the `:desktop` or `:shared/jvmMain` toolchain proof
(`DECISIONS.md` D6), add `com.github.solkin:disk-lru-cache:1.5` as a
dependency and try to resolve it. It should fail with a variant-selection
error, confirming the above. Then pick one of: vendor the ten classes under the
appropriate licence, or replace with a small LRU over `okio.FileSystem` as
`PORTING_NOTES.md` §E already suggests. Update `PORTING_NOTES.md` §E to say
"AAR-only, bytecode is pure JVM" instead of "check JVM-compat".

*Runners-up, not in the top five but worth naming:* `Html.ImageGetter` is a
**constructor** dependency of `DetailsLoadUseCase`
(`details/domain/DetailsLoadUseCase.kt:54`), so the details use case cannot be
instantiated without the Android text stack; and lint runs with
`warningsAsErrors = true` (`ARCHITECTURE.md` §1), so every move that leaves an
unused import behind in `:app` breaks the gate.

### 7.2 Proposed move order

Principle that shapes this list: **most of the work is refactoring inside
`:app`, not moving.** A refactor that keeps everything in `:app` is gated by
one Gradle command you already run and cannot break the Android app's
behaviour if it compiles. A move is the risky half. So every step below is
either "prepare in `:app`" (cheap, reversible, gate is trivially green) or
"move to `:shared`" (only done once the preparing step has removed the
coupling). Steps marked **[prep]** do not touch `:shared` at all.

Prerequisites owned by others, must land before step 4:

- P1. `:desktop` skeleton and the Kotlin 2.3.21 / CMP 1.12.0 / Room KMP
  toolchain proof (`DECISIONS.md` D6, Agent E).
- P2. `:shared` module skeleton with `androidTarget()` + `jvm()`, and the
  `Logger`, `AppInfo`, `AppPaths`, `Settings`, `StringKey` interfaces
  (main agent, per `DECISIONS.md` §3).
- P3. Room layer into `:shared` (`DECISIONS.md` D5 step 3, Agent A). Nothing in
  steps 9-12 below can start before this.

---

**1. [prep] Kill `printStackTraceDebug`.** Introduce a `Logger` interface with
one Android and one no-op implementation, replace the 87 call sites, delete
`src/debug/.../Debug.kt` and `src/release/.../Debug.kt`. Largest mechanical
diff of the whole plan and the one with the widest unblock: it gates 9 of my
GREEN files directly and a large fraction of the app indirectly. Do it first,
while the codebase is still one module and the refactor is a single rename.
*Gate: `:app:assembleDebug` plus a manual check that release still swallows.*

**2. [prep] Introduce `AppInfo`.** Three fields (`applicationId`,
`versionCode`, `isDebug`) replacing `BuildConfig` at the four sites in my scope
(`local/data/MangaIndex.kt`, `core/util/ext/Collections.kt`,
`core/util/ext/Throwable.kt`, `core/util/ShareHelper.kt`). Small, and it is on
the local-storage format path so it has to precede step 8.

**3. Move the zero-dependency leaves of `core/util` to `:shared`.** Twelve
files with no `org.koitharu.kotatsu` imports at all and no Android imports:
`core/cache/SafeDeferred.kt`, `core/io/NullOutputStream.kt`,
`core/util/AlphanumComparator.kt`, `core/util/CancellableSource.kt`,
`core/util/CompositeResult.kt`, `core/util/ContinuationResumeRunnable.kt`,
`core/util/Event.kt`, `core/util/MediatorStateFlow.kt`,
`core/util/iterator/MappingIterator.kt`, `core/util/progress/Progress.kt`,
`core/util/progress/ProgressDeferred.kt`,
`core/util/progress/ProgressResponseBody.kt`, plus
`core/util/ext/MimeType.kt` and `core/util/ext/Other.kt`. This is the first
real move and its only job is to prove the module topology: `:app` depends on
`:shared`, Hilt still processes `:app`, lint still passes, configuration cache
still works. Defer `core/util/ext/Http.kt` because it needs `org.json`, which
`:app` deliberately excludes (`PORTING_NOTES.md` §E) and the JVM target needs.

**4. [prep] Split the five one-function MIXED ext files.** `ext/Flow.kt`
(26 importers, one `SystemClock` in `throttle`), `ext/Collections.kt` (15),
`ext/Date.kt` (9, one `Resources.formatDurationShort`), `ext/String.kt` (18,
one `joinToStringWithLimit(context)`), `ext/IO.kt` (9, one
`ContentResolver.openSource`). Move the Android function out into a sibling
`*Android.kt` in `:app`; leave the rest where it is. Then move the pure halves
in the same commit sequence. `ext/Flow.kt` is the highest-value one: its wide
`combine` overloads and `flattenLatest` are used by every view model on all
four flows in §2.

**5. [prep] Rewrite the four rescuable Android-only utils.**
`core/util/MimeTypes.kt` (extension table instead of `android.webkit.MimeTypeMap`;
blocks `local/` and `bookmarks/`), `core/util/LocaleComparator.kt` and
`core/util/LocaleStringComparator.kt` (`java.util.Locale` plus a supplied
preference list; `LocaleStringComparator` is on the details flow via
`BranchComparator`), `core/util/Throttler.kt` and
`core/util/progress/RealtimeEtaEstimator.kt` (`System.nanoTime`). Then move
them.

**6. Move `core/cache` and split `MemoryContentCache`.** Swap
`ExpiringValue`'s `SystemClock.elapsedRealtime()` for `System.nanoTime()`; make
`MemoryContentCache` a plain class with `clear()`/`trimToSize(n)` and leave a
~20 line `ComponentCallbacks2` adapter in `:app`. Then move
`core/parser/CachingMangaRepository.kt` with it (coordinate with Agent B;
it needs step 1's `Logger` and a three-value cache-policy enum replacing
`coil3.request.CachePolicy`). This is the first non-trivial subsystem in
`:shared` and it proves coroutines + `kotatsu-parsers` model types compile in
`commonMain`.

**7. [prep] Replace resource ids in domain types.** `list/domain/ListSortOrder.kt`,
`list/domain/ListFilterOption.kt`, `explore/data/SourcesSortOrder.kt`: swap
`@StringRes Int` / `@DrawableRes Int` for a stable key, and give `:app` a
key-to-id lookup. Then `list/domain/MangaListMapper.kt`: replace
`@ApplicationContext context` with an injected tag-warnlist supplier so the one
`openRawResource` call (`:209-210`) leaves the class. Big call-site diff, all
of it in `ui/`. Do it before step 9, because those two enums are read by both
library DAOs.

**8. Local storage, addressing first.** Replace `android.net.Uri` in `local/`
with `java.net.URI` or a small `LocalRef(path, entry)` type, keeping the
`file+zip://<path>#<entry>` string form byte-identical so existing libraries
still open. Six files: `core/util/ext/Uri.kt`,
`local/data/input/LocalMangaParser.kt`, `local/data/LocalMangaRepository.kt`,
`local/domain/model/LocalManga.kt`, `local/data/output/LocalMangaDirOutput.kt`,
`local/data/output/LocalMangaUtil.kt`. Then split `ext/File.kt` (24 importers)
per step 4's pattern, declare `LocalStorageManager` as the `AppPaths` interface
with its four SAF methods on the Android side only, put the `LocalMangaIndex`
version int into `Settings`, and resolve the `DiskLruCache` AAR question from
R5. Then move `local/data/CbzFilter.kt`, `MangaIndex.kt`, `input/`, `output/`,
`domain/MangaLock.kt` and the index. **Verify with a real round trip**: a CBZ
written by the Android app opens on the JVM and vice versa, checking
`index.json` and the `%08d_%04d%04d` page names (§3.4). This is the single
highest-value integration test in the whole port and it can be run before any
UI exists.

**9. Move the details flow.** Change `MangaDetails.sourceDescription` to hold
raw HTML rather than a `Spanned`, move `parseAsHtml`/`filterSpans` and the
`Html.ImageGetter` dependency out of `DetailsLoadUseCase` into `details/ui`,
replace `MangaIntent` with a plain data class, replace the
`coil3.request.CachePolicy` parameter from step 6. Then move all 7 files of
`details/domain` and both of `details/data`. Six of those nine files already
have zero Android imports, so once the description seam is cut this is nearly
free, and it is the flow that proves the parser host (Agent B) end to end.

**10. Move `filter/`, minus the Mihon path.** Delete the `~165` Mihon dynamic
filter lines from `FilterCoordinator` (`:304-349`, `:355-443`, `:707-730`),
which removes the `eu.kanade.tachiyomi` imports and, as a side effect, the only
reason `Context` is injected. Replace `ViewModelLifecycle` with a plain
`CoroutineScope`, `SavedStateHandle` with a constructor parameter, and move the
`find`/`require` fragment helpers into `:app`. Split `MangaSearchRepository`
into a portable suggestion repository and a platform `SearchHistoryStore`. Then
move `filter/data/`, `FilterProperty`, `TagTitleComparator` and the
~500 portable lines of `FilterCoordinator`. High reward: this is the search and
browse machinery for the whole desktop app.

**11. Move `bookmarks/`.** Smallest v1 feature, and after P3 it is two import
swaps (`android.database.SQLException`, `androidx.room.withTransaction`).
Use it as the proof that a *complete vertical feature* can live in `:shared`
before attempting the two that matter.

**12. Move `history/`, then `favourites/`.** History first because it is
smaller (15 files vs 41) and because it carries 7 of the 11 `withTransaction`
sites, so it is the harder Room test on the smaller surface. Extract the
date-bucket grouping (`history/ui/HistoryListViewModel.kt:167-226`, ~60 lines)
into a use case with a platform-free bucket enum. Then favourites: extract the
pin overlay and the pins-only-in-default-state rule
(`favourites/ui/list/FavouritesListViewModel.kt:162-227`, ~60 lines), move
`favourites/data/` (4 of 6 files are AMBER-Room-only) and
`favourites/domain/`. `LocalFavoritesObserver` and `HistoryLocalObserver` go
with step 8's `LocalMangaIndex`, not with their own features.

**13. `explore/`, `alternatives/`, `search/`.** `explore/domain` is 2 GREEN
files; `explore/data/MangaSourcesRepository` needs the `Settings` interface and
a locale source. `alternatives/domain` is 2 GREEN plus one `withTransaction`.
These are small and they depend on everything above, so they come last among
the non-UI work.

Everything in `*/ui/` is out of scope for the move and is rewritten per
`DECISIONS.md` D10.

### 7.3 Two things this order deliberately does not do

- It does not move `core/util/ext/Throwable.kt`. 180 of its 229 lines are
  `Resources`-based error-message formatting with 44 importers. Splitting it
  into a `Throwable -> ErrorKind` classifier plus a UI-side string lookup is
  real design work, not a mechanical split, and nothing in v1 is blocked by it
  as long as the classifier's three portable functions (`getCauseUrl`,
  `isNetworkError`, `isWebViewUnavailable`) are duplicated or left behind.
- It does not move the 36 Room migrations, per `DECISIONS.md` D14.

### 7.4 Unknowns

- Whether `androidx.collection`'s JVM artifact actually resolves for the
  `jvm()` target alongside the version `kotatsu-parsers` pulls. Settled by
  Agent E's dependency resolution proof. 6 AMBER files in my scope assume it.
- Whether `ReversibleHandle` (`core/ui/util/`) is Android-free. It is in a
  `ui` package and is returned by `FavouritesRepository`, `HistoryRepository`
  and `BookmarksRepository`, so all three domain layers depend on it. I did not
  read it because `core/ui/` is outside my directories. Settled by reading one
  file.
- The exact fan-out of `list/ui/model/` (56 files in `list/ui/`). I confirmed
  `ListModel.kt` itself is a 10-line import-free interface, but not the ~15
  model subtypes that `MangaListMapper` produces. Settled by whoever is given
  `list/ui/`, which on the current split is nobody.
- Whether the release-variant `printStackTraceDebug() = Unit` is masking
  failures that would become visible once step 1 routes them through a real
  `Logger`. Settled by running a debug build after step 1 and watching for
  newly-visible stack traces.
