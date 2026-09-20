# A - Data layer

Phase 1 recon, Agent A. Read-only pass over `core/db/**`, `core/prefs/**`,
`app/schemas/**` and every `*/data/**` except `local/data`, `filter/data`,
`scrobbling/*/data`, `mihon/*`, `lnreader/*`.

Repo at `1d30c9b`, branch `desktop-port`. Every claim below cites a
`file:line` or a command that was actually run. Anything not established is
written as **unknown** with what would settle it.

## Corrections to the shared docs (read this first)

Three claims in `PORTING_NOTES.md` §C are wrong. The second one is the
material one, because it points the fix at the wrong dependency.

**1. `withTransaction` is 48 call sites in 14 files, not 11.**
`PORTING_NOTES.md` §C row "`withTransaction`" says "11 call sites". 11 is
the number of *files that import it inside a `data/` or `domain/`
directory*, which is the number the §A table correctly reports. The §C
row copied that number into a "call sites" column. Measured:

```
$ grep -rn "\.withTransaction\s*{\|\.withTransaction {" --include=*.kt app/src/main/kotlin | wc -l
48
$ grep -rln "^import androidx.room.withTransaction" --include=*.kt app/src/main/kotlin | wc -l
14
$ grep -rln "^import androidx.room.withTransaction" --include=*.kt app/src/main/kotlin | grep -E "/(data|domain)/" | wc -l
11
```

The conversion is per-lambda, not per-file, so 48 is the number that
matters for effort. Full list in §2.

**2. `withTransaction` does not come from `room-ktx` at Room 2.8.4.**
`PORTING_NOTES.md` §C and §E both say `withTransaction` is from
`androidx.room:room-ktx`, "an Android-only artifact". At 2.8.4 `room-ktx`
is an empty compatibility shim:

```
$ unzip -l <gradle cache>/androidx.room/room-ktx/2.8.4/.../room-ktx-2.8.4.aar -> classes.jar
Archive:  classes.jar
        6  1981-01-01 01:01   META-INF/androidx.room_room-ktx.version
---------                     -------
        6                     1 file
```

One entry, a version marker, zero classes. `withTransaction` now lives in
`room-runtime`'s **Android source set**, as the file facade
`androidx/room/RoomDatabaseKt__RoomDatabase_androidKt.class` inside
`room-runtime-android-2.8.4.aar`. The conclusion (unavailable on JVM) is
unchanged; the reason is different, and it matters because dropping
`room-ktx` from the dependency list changes nothing at all. The real
dependency to change is the `room-runtime` *variant*.

**3. Raw SQL is 8 `@RawQuery` methods and 5 query-construction sites, not
"~7 call sites".** Detail in §2.

`ARCHITECTURE.md` §4 and §5 are accurate as far as they go. Counts
confirmed: 16 entities, 15 DAOs, 36 migrations, `DATABASE_VERSION = 37`
(`core/db/MangaDatabase.kt:83`). §4 is extended below; §5 is extended in
§5 of this file.

### Verified against the published artifacts, not recalled

`room-runtime:2.8.4` module metadata declares a `standard-jvm` variant
redirecting to `room-runtime-jvm`:

```
$ cat <cache>/androidx.room/room-runtime/2.8.4/*.module | python3 -c "..."
jvmApiElements-published     -> room-runtime-jvm  standard-jvm
jvmRuntimeElements-published -> room-runtime-jvm  standard-jvm
androidApiElements-published -> room-runtime-android android
```

The published JVM jar was downloaded and inspected:

```
$ curl -sSfL https://repo1.maven.org/maven2/androidx/room/room-runtime-jvm/2.8.4/room-runtime-jvm-2.8.4.jar
curl: (22) The requested URL returned error: 404
$ curl -sSfL https://dl.google.com/dl/android/maven2/androidx/room/room-runtime-jvm/2.8.4/room-runtime-jvm-2.8.4.jar
-> 312041 bytes
```

**Note for Agent E: `room-runtime-jvm` is not on Maven Central.** It is
only on Google Maven. A `:desktop`/`:shared` module that resolves against
`mavenCentral()` alone will fail. `dl.google.com` serves it.

`javap` over that published jar:

| Class | In `room-runtime-jvm:2.8.4`? |
|---|---|
| `androidx.room.RoomRawQuery` | yes |
| `androidx.room.Transactor` / `TransactionScope` / `TransactorKt` | yes |
| `androidx.room.RoomDatabaseKt.withTransaction` | **no** |
| any `androidx.room.support.SupportSQLite*` | **no** (0 matches) |
| `RoomDatabaseKt__RoomDatabase_androidKt` | **no** |

The same grep run against `room-runtime-android-2.8.4.aar` *does* match
`RoomDatabase_androidKt` and `withTransaction`, so the absence above is a
real absence, not a broken grep.

The JVM `RoomDatabaseKt` exposes only:
`useReaderConnection`, `useWriterConnection`, `validateMigrationsNotRequired`,
`validateAutoMigrations`, `validateTypeConverters`.

And `TransactorKt` exposes:
`execSQL(PooledConnection, String)`,
`deferredTransaction(Transactor) { TransactionScope<R> }`,
`immediateTransaction(...)`, `exclusiveTransaction(...)`.

`RoomRawQuery` has exactly two useful constructors:

```
public androidx.room.RoomRawQuery(java.lang.String)
public androidx.room.RoomRawQuery(java.lang.String,
        kotlin.jvm.functions.Function1<? super androidx.sqlite.SQLiteStatement, kotlin.Unit>)
```

---

## 1. Inventory

16 `@Entity`, 15 `@Dao`. `core/db/MangaDatabase.kt:85-93` lists all 16
entities; `:96-124` lists all 15 DAO accessors.

### Entities

| Entity | File | Table | Feature | Android types in columns |
|---|---|---|---|---|
| `MangaEntity` | `core/db/entity/MangaEntity.kt:8` | `manga` | core | none |
| `TagEntity` | `core/db/entity/TagEntity.kt:8` | `tags` | core | none |
| `MangaTagsEntity` | `core/db/entity/MangaTagsEntity.kt:9` | `manga_tags` | core | none |
| `ChapterEntity` | `core/db/entity/ChapterEntity.kt:9` | `chapters` | core | none |
| `MangaPrefsEntity` | `core/db/entity/MangaPrefsEntity.kt:10` | `preferences` | core (per-manga reader prefs) | none |
| `MangaSourceEntity` | `core/db/entity/MangaSourceEntity.kt:9` | `sources` | core | none |
| `HistoryEntity` | `history/data/HistoryEntity.kt:11` | `history` | history | none |
| `FavouriteEntity` | `favourites/data/FavouriteEntity.kt:10` | `favourites` | favourites | none |
| `FavouriteCategoryEntity` | `favourites/data/FavouriteCategoryEntity.kt:8` | `favourite_categories` | favourites | none |
| `TrackEntity` | `tracker/data/TrackEntity.kt:11` | `tracks` | tracker | none (`@IntDef`, see §3) |
| `TrackLogEntity` | `tracker/data/TrackLogEntity.kt:10` | `track_logs` | tracker | none |
| `SuggestionEntity` | `suggestions/data/SuggestionEntity.kt:11` | `suggestions` | suggestions | none (`@FloatRange`, see §3) |
| `BookmarkEntity` | `bookmarks/data/BookmarkEntity.kt:9` | `bookmarks` | bookmarks | none |
| `ScrobblingEntity` | `scrobbling/common/data/ScrobblingEntity.kt:7` | `scrobblings` | scrobbling | none |
| `StatsEntity` | `stats/data/StatsEntity.kt:9` | `stats` | stats | none |
| `LocalMangaIndexEntity` | `local/data/index/LocalMangaIndexEntity.kt:10` | `local_index` | local (Agent D's dir; listed for completeness) | none |

Table-name constants are in `core/db/Tables.kt`.

Relation POJOs (not entities, but they are DAO return types and they move
with the DAOs): `core/db/entity/MangaWithTags.kt`,
`favourites/data/FavouriteManga.kt`, `history/data/HistoryWithManga.kt`,
`suggestions/data/SuggestionWithManga.kt`, `tracker/data/MangaWithTrack.kt`,
`tracker/data/TrackLogWithManga.kt`, `tracker/data/TrackWithManga.kt`. All
`@Embedded` + `@Relation`, all platform-free.

### DAOs

Counted by annotation occurrence per file (`grep -c`):

| DAO | File | `@Query` | `@RawQuery` | `@Insert`/`@Update`/`@Delete`/`@Upsert` | `@Transaction` | Android types |
|---|---|---|---|---|---|---|
| `MangaDao` | `core/db/dao/MangaDao.kt:23` | 18 | 0 | 1/1/1/1 | 10 | none |
| `FavouritesDao` | `favourites/data/FavouritesDao.kt:27` | 23 | **2** | 1/0/0/1 | 10 | `sqlEscapeString`, `SimpleSQLiteQuery`, `SupportSQLiteQuery` |
| `HistoryDao` | `history/data/HistoryDao.kt:25` | 19 | **1** | 1/0/0/1 | 9 | `sqlEscapeString`, `SupportSQLiteQuery` |
| `FavouriteCategoriesDao` | `favourites/data/FavouriteCategoriesDao.kt:11` | 18 | 0 | 1/0/0/1 | 0 | none |
| `TracksDao` | `tracker/data/TracksDao.kt:14` | 16 | **1** | 0/0/0/1 | 5 | `DatabaseUtils`, `SupportSQLiteQuery` |
| `TrackLogsDao` | `core/db/dao/TrackLogsDao.kt:17` | 14 | **1** | 1/0/0/0 | 1 | `DatabaseUtils`, `SupportSQLiteQuery` |
| `BookmarksDao` | `bookmarks/data/BookmarksDao.kt:15` | 9 | 0 | 1/0/1/1 | 2 | none |
| `StatsDao` | `stats/data/StatsDao.kt:17` | 8 | **2** | 0/0/0/1 | 0 | `SimpleSQLiteQuery`, `SupportSQLiteQuery` |
| `TagsDao` | `core/db/dao/TagsDao.kt:8` | 8 | 0 | 0/0/0/1 | 0 | none |
| `ScrobblingDao` | `scrobbling/common/data/ScrobblingDao.kt:9` | 7 | 0 | 0/0/0/1 | 0 | none (`androidx.room.*` wildcard import) |
| `SuggestionDao` | `suggestions/data/SuggestionDao.kt:18` | 6 | **1** | 1/1/0/0 | 4 | `sqlEscapeString`, `SupportSQLiteQuery` |
| `MangaSourcesDao` | `core/db/dao/MangaSourcesDao.kt:12` | 5 | 0 | 0/0/0/1 | 0 | none |
| `PreferencesDao` | `core/db/dao/PreferencesDao.kt:9` | 5 | 0 | 0/0/0/1 | 0 | none |
| `LocalMangaIndexDao` | `local/data/index/LocalMangaIndexDao.kt:7` | 5 | 0 | 0/0/0/1 | 0 | none |
| `ChaptersDao` | `core/db/dao/ChaptersDao.kt:10` | 4 | 0 | 1/0/0/0 | 1 | none |
| **total** | | **165** | **8** | | **42** | |

**No DAO returns an Android-specific type.** Full evidence: every
`^import android` / `^import androidx` line in all 15 DAO files was listed.
The only non-`androidx.room` imports anywhere are
`android.database.DatabaseUtils` (4 files) and `androidx.sqlite.db.*`
(6 files), and both appear in *argument* or *helper* positions, never in a
return type. No `LiveData`, no `Cursor`, no `PagingSource`, no `Uri`, no
`Bitmap`, no `Parcelable`. Return types are `Flow<…>`, `List<…>`,
primitives, entities and relation POJOs.

Five DAOs are `abstract class` rather than `interface`, because they
implement `MangaQueryBuilder.ConditionCallback`: `HistoryDao:26`,
`SuggestionDao:19`, `TracksDao:15`, `TrackLogsDao:18`, `FavouritesDao:28`.

---

## 2. The KMP blockers

### 2a. `androidx.room.withTransaction` - 48 call sites, 14 files

All 48, by file and line. Files marked (cut) are out of desktop v1 scope
per `DECISIONS.md` §2, so their call sites do not need converting for v1 -
but they DO need converting if the file moves to `:shared`, which is a
scoping decision, not a technical one.

| File | Lines | Count | v1? |
|---|---|---|---|
| `favourites/domain/FavouritesRepository.kt` | 129, 235, 253, 268, 276, 295, 305, 328, 336 | 9 | **v1** |
| `history/data/HistoryRepository.kt` | 120, 133, 219, 224, 229, 235, 273 | 7 | **v1** |
| `tracker/domain/TrackingRepository.kt` | 145, 150, 161, 173, 194, 212, 229 | 7 | cut (tracker) |
| `core/parser/MangaDataRepository.kt` | 47, 55, 86, 107, 169, 195 | 6 | **v1** |
| `sync/domain/GoogleDriveSyncRepository.kt` | 567, 589, 601, 613, 675 | 5 | cut (Drive sync) |
| `backup/local/data/LocalBackupRepository.kt` | 388, 422, 456 | 3 | **v1** (backup/restore is in scope) |
| `bookmarks/domain/BookmarksRepository.kt` | 47, 74, 92 | 3 | unclear - bookmarks not named in §2 either list |
| `local/data/index/LocalMangaIndex.kt` | 44, 81 | 2 | **v1** (local CBZ) - Agent D's dir |
| `alternatives/domain/MigrateUseCase.kt` | 52 | 1 | cut |
| `backup/MihonBackupManager.kt` | 181 | 1 | cut (Mihon) |
| `kotatsumigration/domain/KotatsuMangaMigrator.kt` | 59 | 1 | cut |
| `settings/sources/migration/SourceMangaListViewModel.kt` | 76 | 1 | cut |
| `stats/data/StatsRepository.kt` | 215 | 1 | cut (stats) |
| `suggestions/domain/SuggestionRepository.kt` | 63 | 1 | cut (suggestions) |

**v1-relevant subtotal: 30 call sites across 6 files** (favourites 9,
history 7, MangaDataRepository 6, LocalBackupRepository 3, bookmarks 3,
LocalMangaIndex 2).

Two of them use the `return@withTransaction` label
(`MigrateUseCase.kt:82`, `HistoryRepository.kt:136`), so the conversion is
not a pure textual substitution at those two sites - the label name
changes with the function name.

**What it does.** `db.withTransaction { ... }` runs the block inside a
single SQLite transaction on Room's transaction dispatcher, with
`ThreadLocal`-style reentrancy so nested `@Transaction` DAO calls inside
join the outer transaction rather than deadlocking. It returns the block's
value and rolls back on exception.

**Room KMP equivalent.** From the published `room-runtime-jvm:2.8.4`:

```kotlin
// androidx.room.useWriterConnection + androidx.room.immediateTransaction
suspend fun <R> MangaDatabase.transaction(block: suspend () -> R): R =
    useWriterConnection { transactor ->
        transactor.immediateTransaction { block() }
    }
```

`Transactor.withTransaction(SQLiteTransactionType, block)` is the
lower-level form; `deferredTransaction` / `immediateTransaction` /
`exclusiveTransaction` in `TransactorKt` are the three named wrappers.
`withTransaction` (Android) maps to `immediateTransaction` semantically
(Room's Android impl uses `beginTransaction()`, which is `IMMEDIATE` in
WAL mode).

**The concrete port.** Write ONE shared extension with the same name and
the same shape in `:shared`, `actual`-free, backed by
`useWriterConnection`. Then all 48 sites are an import change only, from
`androidx.room.withTransaction` to `org.koitharu.kotatsu.core.db.withTransaction`.
The two labelled `return@withTransaction` sites keep working because the
label follows the function name.

**The trap.** `useWriterConnection` gives you a `Transactor`, and calling
a suspend DAO method from *inside* that lambda is only safe because Room
propagates the connection through the coroutine context. That propagation
is exactly the part I have not executed. See §6 item 1.

### 2b. `@RawQuery` - 8 methods

| Method | File:line | Returns | Observed entities |
|---|---|---|---|
| `observeAllImpl` | `favourites/data/FavouritesDao.kt:239-240` | `Flow<List<FavouriteManga>>` | `FavouriteEntity` |
| `findCoversImpl` | `favourites/data/FavouritesDao.kt:242-243` | `List<Cover>` (suspend) | - |
| `observeAllImpl` | `history/data/HistoryDao.kt:194-195` | `Flow<List<HistoryWithManga>>` | `HistoryEntity` |
| `observeMangaImpl` | `tracker/data/TracksDao.kt:123-124` | `Flow<List<MangaWithTrack>>` | `TrackEntity` |
| `observeAllImpl` | `core/db/dao/TrackLogsDao.kt:78-79` | `Flow<List<TrackLogWithManga>>` | `TrackLogEntity` |
| `observeAllImpl` | `suggestions/data/SuggestionDao.kt:66-67` | `Flow<List<SuggestionWithManga>>` | `SuggestionEntity` |
| `getDurationStatsImpl` | `stats/data/StatsDao.kt:54-57` | `Map<@MapColumn("manga") MangaEntity, @MapColumn("d") Long>` | - |
| `getSessionsImpl` | `stats/data/StatsDao.kt:72-73` | `List<StatsEntity>` (suspend) | - |

`@RawQuery` and `@MapColumn` are both in `room-common-jvm:2.8.4`
(verified: `androidx/room/RawQuery.class`, `androidx/room/MapColumn.class`
are in the jar). The annotations are multiplatform. **Only the parameter
type changes**: `SupportSQLiteQuery` becomes `androidx.room.RoomRawQuery`.

### 2c. `SimpleSQLiteQuery` construction sites - 5

| Site | Bind args? | Port |
|---|---|---|
| `core/db/MangaQueryBuilder.kt:100` | **no** | `RoomRawQuery(sql)` |
| `favourites/data/FavouritesDao.kt:125-131` | yes, `arrayOf<Any>(categoryId)` | `RoomRawQuery(sql) { it.bindLong(1, categoryId) }` |
| `favourites/data/FavouritesDao.kt:138-147` | yes, `arrayOf<Any>(limit)` | `RoomRawQuery(sql) { it.bindLong(1, limit.toLong()) }` |
| `stats/data/StatsDao.kt:48-50` | **no** | `RoomRawQuery(sql)` |
| `stats/data/StatsDao.kt:66-68` | **no** | `RoomRawQuery(sql)` |

Only two of the five bind anything, and both bind a single value.
`RoomRawQuery`'s binding function takes an `androidx.sqlite.SQLiteStatement`
with 1-based indices and typed binders, so the untyped `arrayOf<Any>`
becomes typed. Verified against the published `sqlite-jvm-2.6.2.jar`,
`androidx.sqlite.SQLiteStatement` provides `bindLong`, `bindDouble`,
`bindText`, `bindBlob`, `bindNull` as abstract members plus `bindInt`,
`bindFloat`, `bindBoolean` as default members, so `bindInt(1, limit)` is
available and no `.toLong()` widening is needed.

### 2d. `core/db/MangaQueryBuilder.kt` - read in full (113 lines)

**What it builds.** A single `SELECT * FROM <table>` string, assembled
from six optional pieces, in this fixed order (`:43-99`):

1. `SELECT * FROM <table>` (`:44-45`) - table name is a constructor arg
2. optional `extraJoins` appended verbatim (`:46-49`)
3. `WHERE` from a `LinkedList<String>` of literal condition strings,
   `AND`-joined (`:50-56`)
4. filter options (`:57-87`): grouped by `ListFilterOption.groupKey`,
   options within one group `OR`-joined inside parentheses, groups
   `AND`-joined, each option turned into a string by the DAO's own
   `getCondition` callback; `ListFilterOption.Inverted` wraps in `NOT(...)`
   recursively (`:103`)
5. optional `GROUP BY` (`:88-91`), optional `ORDER BY` (`:92-95`)
6. optional `LIMIT <int>` (`:96-99`)

`.let { SimpleSQLiteQuery(it) }` at `:100`, **the single-argument
constructor**. There are no bind arguments anywhere in this class. Every
value that reaches SQL - category ids, tag ids, pinned manga ids, limits,
source names, manga states - is string-interpolated into the SQL text by
the `getCondition` implementations and the `.where(...)` callers.

**How hard is `RoomRawQuery`?** Trivially easy, and that is the surprise.
Because the builder produces a complete SQL string with zero binds, the
entire porting diff for this class is:

```kotlin
-import androidx.sqlite.db.SimpleSQLiteQuery
+import androidx.room.RoomRawQuery
-    }.let { SimpleSQLiteQuery(it) }
+    }.let { RoomRawQuery(it) }
```

Two lines. `java.util.LinkedList` at `:5` is a JDK type and resolves on
both `androidTarget()` and `jvm()`, so it does not even need swapping for
`ArrayDeque` unless a native target is ever added.

The `ConditionCallback` fun-interface (`:109-112`) and the five DAO
implementations of it are all plain Kotlin. The five callers -
`HistoryDao:57`, `SuggestionDao:29`, `TracksDao:82` and `:94`,
`TrackLogsDao:24`, `FavouritesDao:104` (6 construction sites in 5 DAOs) -
need no change beyond their `observe*Impl` parameter type.

**The one genuinely hard part is not `RoomRawQuery`, it is
`sqlEscapeString`** (§2e). That is what makes the string-concatenation
design safe today.

### 2e. `android.database.*`

| Site | What | Port |
|---|---|---|
| `core/db/dao/TrackLogsDao.kt:3, :87` | `DatabaseUtils.sqlEscapeString(state.name)` | reimplement |
| `tracker/data/TracksDao.kt:3, :132` | same | reimplement |
| `favourites/data/FavouritesDao.kt:3, :280, :281` | `sqlEscapeString(source.name)`, `sqlEscapeString(state.name)` | reimplement |
| `history/data/HistoryDao.kt:3, :205, :206` | same two | reimplement |
| `suggestions/data/SuggestionDao.kt:3, :73-77, :79` | same two | reimplement |
| `bookmarks/domain/BookmarksRepository.kt:3` | `android.database.SQLException` caught | see below |
| `core/util/ext/Throwable.kt:5` | `android.database.sqlite.SQLiteFullException` | see below |
| `core/util/ext/Cursor.kt:3` | `android.database.Cursor` | Agent B/D territory; not a Room DAO path |
| `reader/ui/ReaderActionsView.kt:7`, `reader/ui/ScreenOrientationHelper.kt:6` | `ContentObserver`, unrelated to the DB | Agent C |

**`sqlEscapeString`: 8 call sites in 5 DAOs.** It wraps a value in single
quotes and doubles any embedded single quote, plus (in AOSP's
implementation) it emits an `X'…'` hex blob literal when the string
contains a `\u0000`. A desktop reimplementation is about six lines, and
it must be written, not skipped, because the values reaching it are
`MangaSource.name` and `MangaState.name` - **which on the desktop build
come from the `kotatsu-parsers` catalogue rather than from a fixed enum**,
so "they are enum names, they cannot contain a quote" is a weaker argument
on desktop than it is on Android. Write the escaper.

The better port, if there is appetite: stop concatenating and make
`MangaQueryBuilder` collect `(sqlFragment, binder)` pairs so
`RoomRawQuery`'s binding function does the work. That is a real refactor
of five DAOs and is not v1-necessary.

**`SQLException` / `SQLiteFullException`.** Both are Android exception
types caught in error handling. On JVM, `androidx.sqlite` throws
`androidx.sqlite.SQLiteException`. These two catch sites need replacing
with a shared abstraction or they will silently stop catching. `unknown`:
whether `bundled` SQLite surfaces disk-full as a distinguishable code;
what would settle it is filling a tmpfs and running a write.

### 2f. `SupportSQLiteDatabase` - 37 sites

36 migrations (`core/db/migrations/Migration*.kt`, each
`override fun migrate(db: SupportSQLiteDatabase)`) plus
`core/db/DatabasePrePopulateCallback.kt:11`. Covered in §4.

### 2g. Summary of the mechanical blocker count

| Blocker | Sites | Files | v1-relevant sites |
|---|---|---|---|
| `withTransaction` lambdas | 48 | 14 | 30 |
| `@RawQuery` parameter type | 8 | 6 | 3 (`FavouritesDao` 2, `HistoryDao` 1; tracker/stats/suggestions are all cut) |
| `SimpleSQLiteQuery(...)` construction | 5 | 3 | 3 (`MangaQueryBuilder`, `FavouritesDao` x2) |
| `sqlEscapeString` | 8 | 5 | 4 (`FavouritesDao` 2, `HistoryDao` 2) |
| `SupportSQLiteDatabase` | 37 | 37 | 0 if D14 holds |
| Android SQL exception types | 2 | 2 | 2 |

---

## 3. Type converters and Android types in entities

**There are zero `@TypeConverter`s and zero `@TypeConverters` in the entire
repository.**

```
$ grep -rn "TypeConverter\|@TypeConverters" --include=*.kt app/src/main/kotlin
(no matches)
```

`core/db/MangaDatabase.kt:85-93` carries no `@TypeConverters` annotation
either. This is the single best piece of news in the data layer: type
converters are where Android types normally sneak into a Room schema
(`Uri`, `Date`, `Bitmap`, serialized `Parcelable`), and there are none.

**Every column in all 16 entities is a SQLite primitive.** Tallied across
every entity file:

| Kotlin type | Column count |
|---|---|
| `Long` | 40 |
| `Int` | 20 |
| `String` | 18 |
| `String?` | 17 |
| `Boolean` | 12 |
| `Float` | 9 |

No `Uri`, no `Bitmap`, no `Parcelable`, no `Date`, no `Instant`, no
enum-typed columns, no `ByteArray`. Enums are stored as their `.name`
string (e.g. `MangaEntity.state: String?` at
`core/db/entity/MangaEntity.kt:21`, `source: String` at `:24`) and mapped
by hand in `core/db/entity/EntityMapping.kt`, whose imports are entirely
`org.koitharu.kotatsu.core.model` and `org.koitharu.kotatsu.parsers.*` -
no Android import at all.

### The two annotations that are not `androidx.room`

| Annotation | Site | Verdict |
|---|---|---|
| `androidx.annotation.FloatRange` | `suggestions/data/SuggestionEntity.kt:3, :24` | `androidx.annotation` publishes KMP artifacts; this is a source-retention documentation annotation and Room ignores it. Safe. |
| `androidx.annotation.IntDef` | `tracker/data/TrackEntity.kt:3, :33` | declared `@Retention(AnnotationRetention.SOURCE)` at `:34`, so it does not survive to bytecode. Resolves for `androidTarget()` and `jvm()`. |

`unknown`: whether `IntDef` is in `androidx.annotation`'s **common**
source set or only jvm/android. It does not matter for a
`androidTarget() + jvm()` module, and it would only matter if a native
target were ever added. What would settle it: `unzip -l` on
`annotation-<version>.klib`.

### One non-obvious platform coupling in an entity

`suggestions/data/SuggestionEntity.kt:26` defaults `createdAt` to
`System.currentTimeMillis()`. That is a JDK call, not an Android call, so
it ports, but it means row creation time depends on the wall clock at the
call site rather than on SQLite. Same pattern in
`core/db/DatabasePrePopulateCallback.kt:15`. Not a blocker, noted because
"epoch millis everywhere" is a design choice the desktop side must match
exactly or history sort orders will disagree between the two apps after a
backup round-trip.

### Return positions

Confirmed in §1: no DAO returns an Android type. The full
`^import android` / `^import androidx` listing across all 15 DAO files
yields only `androidx.room.*`, `androidx.sqlite.db.*` (§2) and
`android.database.DatabaseUtils` (§2e). Nothing else.

---

## 4. Migrations

### Count confirmed

```
$ ls app/src/main/kotlin/org/koitharu/kotatsu/core/db/migrations | wc -l
36
```

Named `Migration1To2` through `Migration36To37`, all 36 registered in
`core/db/MangaDatabase.kt:127-164`. `DATABASE_VERSION = 37` at `:83`.

`app/schemas/` contains exactly **one** file:
`org.koitharu.kotatsu.core.db.MangaDatabase/37.json`. The earlier schema
versions were never committed. Its `identityHash` is
`726a15e39f868c94b6dc76958eff6ba7` and it declares 16 entities, 0 views.

### What API they use

Across all 36 files, the complete set of calls on the `db` parameter:

```
$ grep -rhoP 'db\.\w+' core/db/migrations | sort | uniq -c
     89 db.execSQL
```

89 `execSQL` calls and nothing else. The complete set of imports across
all 36 files is three lines:

```
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koitharu.kotatsu.parsers.model.SortOrder
```

**No migration reads a cursor, touches a file, or uses `Context`.**
Verified negatively:

```
$ grep -rn "db.query\|Cursor\|getContext\|Context\|resources\|File(\|java.io\|moveToNext" core/db/migrations
(no matches)
```

`getDatabaseMigrations(context: Context)` at `MangaDatabase.kt:127` takes a
`Context` and **never uses it** - all 36 constructors are zero-arg
(`:128-163`). That parameter is dead and can be dropped when the function
moves.

### Eight sampled across the range

| Migration | Lines | What it does |
|---|---|---|
| `Migration1To2.kt` | 54 | The heaviest one. Four full table rebuilds (`manga_tags`, `favourites`, `history`, `preferences`) to add foreign keys: `CREATE ..._tmp`, `CREATE INDEX`, `INSERT INTO _tmp SELECT`, `DROP TABLE`, `ALTER TABLE RENAME`. Pure SQL. |
| `Migration8To9.kt` | 11 | One `ALTER TABLE favourite_categories ADD COLUMN \`order\` TEXT NOT NULL DEFAULT ${SortOrder.NEWEST.name}` (`:10`). The only migration that imports a Kotlin symbol. See the latent-bug note below. |
| `Migration14To15.kt` | 9 | `override fun migrate(db: SupportSQLiteDatabase) = Unit` (`:8`). A no-op version bump. |
| `Migration19To20.kt` | 18 | Backup-table rebuild of `tracks` (drop two columns, add two), then one `ALTER TABLE track_logs ADD COLUMN`. Pure SQL. |
| `Migration24To25.kt` | 12 | `ALTER TABLE manga ADD COLUMN content_rating`, then `UPDATE manga SET content_rating = 'ADULT' WHERE nsfw = 1` (`:10`). A data backfill derived entirely from existing rows. |
| `Migration30To31.kt` | 11 | One `ALTER TABLE manga ADD COLUMN description TEXT`. |
| `Migration35To36.kt` | 32 | Rebuild `chapters` to drop `date_fetch` (comment at `:9-10` explains `ALTER ... DROP COLUMN` needs SQLite 3.35 / Android 14), then `UPDATE favourite_categories SET \`order\` = 'NEWEST'` for eight removed sort names (`:26-30`). |
| `Migration36To37.kt` | 11 | One `ALTER TABLE preferences ADD COLUMN \`description_override\` TEXT`. |

Eight of the 36, spanning versions 1, 8, 14, 19, 24, 30, 35 and 36 - two
more than the six asked for, chosen to cover the largest (1To2), the
empty one (14To15), both table rebuilds, both data backfills and the two
most recent.

### D14 safety: does anything at v37 depend on migration-seeded data?

**No. Migrations seed nothing.** Every `INSERT` and `UPDATE` in the 36
files was enumerated:

| Statement | File:line | Kind |
|---|---|---|
| `INSERT INTO manga_tags_tmp ... SELECT` | `Migration1To2.kt:20` | copy of existing rows |
| `INSERT INTO favourites_tmp ... SELECT` | `Migration1To2.kt:32` | copy |
| `INSERT INTO history_tmp ... SELECT` | `Migration1To2.kt:41` | copy |
| `INSERT INTO preferences_tmp ... SELECT` | `Migration1To2.kt:50` | copy |
| `INSERT INTO tracks_bk ... SELECT` | `Migration19To20.kt:10` | copy |
| `INSERT INTO tracks ... SELECT` | `Migration19To20.kt:13` | copy |
| `INSERT INTO chapters_new ... SELECT` | `Migration35To36.kt:20` | copy |
| `UPDATE manga SET content_rating = 'ADULT' WHERE nsfw = 1` | `Migration24To25.kt:10` | backfill of existing rows |
| `UPDATE favourite_categories SET \`order\` = 'LATEST_CHAPTER' WHERE \`order\` = 'UPDATED'` | `Migration34To35.kt:14` | rewrite of existing rows |
| `UPDATE favourite_categories SET \`order\` = 'NEWEST' WHERE \`order\` = 'RELEVANCE'` | `Migration34To35.kt:15` | rewrite |
| `UPDATE favourite_categories SET \`order\` = 'NEWEST' WHERE \`order\` IN (...)` | `Migration35To36.kt:26-30` | rewrite |

Every one is `INSERT ... SELECT` from a table being rebuilt, or an
`UPDATE` of rows that already exist. On a fresh v37 database every source
table is empty, so every one of these is a no-op. **Nothing in the 36
migrations produces a row that the v37 `CREATE` statements would not.**

### But `DatabasePrePopulateCallback` DOES seed a row, and D14 does not cover it

`core/db/DatabasePrePopulateCallback.kt:11-25`, wired at
`MangaDatabase.kt:169`:

```kotlin
override fun onCreate(db: SupportSQLiteDatabase) {
    db.execSQL(
        "INSERT INTO favourite_categories (created_at, sort_key, title, `order`, track, download_new_chapters, show_in_lib, `deleted_at`) VALUES (?,?,?,?,?,?,?,?)",
        arrayOf<Any?>(System.currentTimeMillis(), 1, resources.getString(R.string.read_later),
                      SortOrder.NEWEST.name, 1, 0, 1, 0L))
}
```

This runs on `onCreate`, not in a migration, and it is **not** in the
exported schema. The v37 `createSql` for `favourite_categories` is:

```sql
CREATE TABLE IF NOT EXISTS `favourite_categories` (`category_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `created_at` INTEGER NOT NULL, `sort_key` INTEGER NOT NULL, `title` TEXT NOT NULL,
  `order` TEXT NOT NULL, `track` INTEGER NOT NULL, `download_new_chapters` INTEGER NOT NULL,
  `show_in_lib` INTEGER NOT NULL, `deleted_at` INTEGER NOT NULL)
```

No default row. So **a desktop database built from the v37 schema with no
prepopulate callback starts with zero favourite categories**, while every
Android install starts with one. Favourites are in the v1 scope
(`DECISIONS.md` §2, "Library: favourites with categories").

This is not hypothetical coupling. `backup/local/data/LocalBackupRepository.kt:433-442`
is written against the assumption:

```kotlin
// The built-in "Read later" category is pre-populated on DB creation and dumped into every
// backup (even when empty), so a restore always brings it back. ...
private suspend fun removeEmptyReadLaterCategory() {
    val readLaterTitle = context.getString(R.string.read_later)
    ...
    val readLater = dao.findAll().firstOrNull { it.title == readLaterTitle } ?: return
```

It matches on the **localized** title string. Two consequences for
desktop, both in v1 scope because backup/restore is D15's interop path:

1. If desktop does not port the prepopulate callback, restoring an Android
   backup brings in "Read later", `removeEmptyReadLaterCategory` deletes
   it because it is empty, and desktop is left with **no categories at
   all** and no way to have got one.
2. If desktop's `StringProvider` returns a different string for
   `R.string.read_later` than the Android device that made the backup -
   different locale, or an English desktop restoring a German backup -
   the title match fails, the dedupe does not fire, and you accumulate a
   duplicate category per restore.

**Verdict on D14: safe as written about the 36 migrations, incomplete
about the callback.** D14's rationale ("all 36 are written against
`SupportSQLiteDatabase`") is correct and its conclusion for v1 is
defensible. But `DECISIONS.md` should be amended to say the prepopulate
callback IS ported, because it is the one place a v37 schema is not
self-sufficient.

Porting it is small. On the JVM the signature is
`onCreate(connection: androidx.sqlite.SQLiteConnection)` (verified by
`javap` on `room-runtime-jvm:2.8.4`), and since the statement binds 8
arguments it needs `prepare`/`bind`/`step` rather than the single-argument
`androidx.sqlite.execSQL(connection, sql)`:

```kotlin
override fun onCreate(connection: SQLiteConnection) {
    connection.prepare("INSERT INTO favourite_categories (...) VALUES (?,?,?,?,?,?,?,?)").use { st ->
        st.bindLong(1, System.currentTimeMillis()); st.bindInt(2, 1)
        st.bindText(3, strings.readLater); st.bindText(4, SortOrder.NEWEST.name)
        st.bindInt(5, 1); st.bindInt(6, 0); st.bindInt(7, 1); st.bindLong(8, 0L)
        st.step()
    }
}
```

The only Android type in the whole class is the `Resources` constructor
parameter (`:9`) feeding `R.string.read_later` (`:17`), which is exactly
the `StringProvider` interface `DECISIONS.md` D9 already plans.

### D14 is more conservative than it needs to be, and here is the evidence

`PORTING_NOTES.md` §G.3 and `DECISIONS.md` D14 both rest on "Room KMP
migrations use `SQLiteConnection`, Android's use `SupportSQLiteDatabase`,
so all 36 need converting and the work buys nothing." The second half of
that is right for v1. The first half implies the two are mutually
exclusive. They are not.

`javap` on `room-runtime-android-2.8.4.aar`:

```
public abstract class androidx.room.migration.Migration {
  public void migrate(androidx.sqlite.db.SupportSQLiteDatabase);
  public void migrate(androidx.sqlite.SQLiteConnection);
}
```

Both overloads exist on Android, and both are open, not abstract.
`javap -c` on `androidx.room.BaseRoomConnectionManager` shows Room calls
exactly one of them:

```
invokevirtual  Method androidx/room/migration/Migration.migrate:(Landroidx/sqlite/SQLiteConnection;)V
invokevirtual  Method androidx/room/RoomDatabase$Callback.onCreate:(Landroidx/sqlite/SQLiteConnection;)V
```

Room **always** calls the `SQLiteConnection` overload. The
`SupportSQLiteDatabase` overload is reached only through a bridge inside
`Migration.migrate(SQLiteConnection)` itself:

```
 7: instanceof  class androidx/sqlite/driver/SupportSQLiteConnection
10: ifeq        27
18: invokevirtual  SupportSQLiteConnection.getDb:()Landroidx/sqlite/db/SupportSQLiteDatabase;
21: invokevirtual  migrate:(Landroidx/sqlite/db/SupportSQLiteDatabase;)V
27: new         class kotlin/NotImplementedError
31: ldc         "Migration functionality with a provided SQLiteDriver requires overriding the migrate(SQLiteConnection) function."
```

Positive control: a `javap -c` sweep over **every** class in
`room-runtime-android-2.8.4.aar` finds exactly **1** call site of
`migrate:(Landroidx/sqlite/db/SupportSQLiteDatabase;)V`, and it is line 21
above. Nothing else in the runtime invokes it.

**Two conclusions follow.**

**(a) A migration written once against `SQLiteConnection` works on both
platforms.** If a subclass overrides `migrate(SQLiteConnection)`, the
bridge never runs and the `SupportSQLiteDatabase` overload is dead. Since
all 36 migrations are nothing but 89 `execSQL(String)` calls, the
conversion is `db.execSQL(x)` to `connection.execSQL(x)` using
`androidx.sqlite.execSQL(SQLiteConnection, String)` (verified present in
the published `sqlite-jvm-2.6.2.jar`, class `androidx.sqlite.SQLite`).
Zero of the 89 pass bind arguments, so the single-argument extension
covers all of them. The whole conversion is one mechanical sed plus an
import swap, and it would let `:shared` own the migrations for both apps.
Whether that is worth doing in v1 is a scope call - but D14's premise that
it "buys nothing" is only true if desktop is the only beneficiary, and it
is not: it removes a divergence.

**(b) There is a live hazard for the Android app.** If `:app` ever gains a
`setDriver(...)` call - which a KMP `:shared` Room setup invites, because
`setDriver` is how the JVM side is configured - every one of the 36
migrations will hit `ifeq 27` and throw
`kotlin.NotImplementedError` at migration time on a real user's upgrade.
Not at compile time. At runtime, on upgrade, for users with an existing
database, and `:app:assembleDebug` will be perfectly green. This is a
`NotImplementedError` the project's own hard rule 3 forbids, thrown by the
library rather than written by us. See §6 item 2.

### Latent data note, Android-side, which D14 makes moot for desktop

`Migration8To9.kt:10` interpolates without quoting:

```kotlin
db.execSQL("ALTER TABLE favourite_categories ADD COLUMN `order` TEXT NOT NULL DEFAULT ${SortOrder.NEWEST.name}")
```

The emitted SQL is `... DEFAULT NEWEST` - a bareword, not `'NEWEST'`.
SQLite accepts barewords as string literals in some contexts as a
documented misfeature, so this probably stored the text `NEWEST` and the
`WHERE \`order\` = 'NEWEST'` comparisons in `Migration34To35.kt` and
`Migration35To36.kt` probably match. **`unknown`: whether it actually
did.** What would settle it: create a v8 database, run the migration on
`sqlite3`, and `SELECT typeof(\`order\`), \`order\``. Flagged because it
is the one migration whose correctness is not obvious by reading, and
because D14 means desktop never executes it - a desktop v37 database gets
the column from the `CREATE`, correctly typed, with no default at all.

---

## 5. Preferences

### Shape of `AppSettings`

`core/prefs/AppSettings.kt`, 1501 lines, `@Singleton class AppSettings @Inject constructor(@ApplicationContext context: Context)`
(`:56-57`). Measured:

| | count |
|---|---|
| `const val KEY_*` | 205 |
| top-level `val`/`var` properties | 177 |
| top-level functions | 32 |
| `getStringSet` reads / `putStringSet` writes | 15 / 7 |
| `getEnumValue` / `putEnumValue` uses | 24 |
| `kotlinx.serialization` JSON-in-a-pref uses | 5 |
| files in the tree referencing `AppSettings` | 165 |
| call sites of `observeChanges()` / `settings.observe(...)` | 99 |

`core/prefs/` also holds 17 small enum/value files
(`ColorScheme`, `ListMode`, `ReaderMode`, `NetworkPolicy`, `TriStateOption`,
`ReaderBackground`, `ReaderControl`, `NavItem`, `ScreenshotsPolicy`,
`DownloadFormat`, `DetailsUiMode`, `ProgressIndicatorMode`,
`ReaderAnimation`, `SearchSuggestionType`, `TrackerDownloadStrategy`,
`AppProtectionTimeout`, `SourcesSortOrder` lives in `explore/data`), all
plain Kotlin enums. `PORTING_NOTES.md` §B already has these right.

Android imports in `AppSettings.kt:3-16`: `Context`, `SharedPreferences`,
`ActivityInfo`, `ConnectivityManager`, `Uri`, `Build`, `AppCompatDelegate`,
`ArraySet`, `edit`, `LocaleListCompat`, `DocumentFile`, `PreferenceManager`,
plus `dagger.hilt.android.qualifiers.ApplicationContext` (`:17`).

### Categorised inventory

The 177 properties fall into these groups. Line numbers are the property
declaration.

**Reader - portable (paged/webtoon core)**
`defaultReaderMode` :508, `isReaderModeDetectionEnabled` :511,
`readerBackground` :505, `zoomMode` :585, `isWebtoonZoomEnabled` :1013,
`defaultWebtoonZoomOut` :1025, `isWebtoonGapsEnabled` :1016,
`isWebtoonPullGestureEnabled` :1020, `isPagesNumbersEnabled` :841,
`isReaderBarEnabled` :908, `isReaderNavigationInverted` :398,
`isReaderControlAlwaysLTR` :395, `isReaderZoomButtonsEnabled` :392,
`isChapterJumpDialogEnabled` :410, `isReaderOptimizationEnabled` :404,
`is32BitColorsEnabled` :1065, `isReadingTimeEstimationEnabled` :1082.

**Reader - cut from v1 by `DECISIONS.md` D10 / §2**
`isReaderDoubleOnLandscape` :265, `isReaderDoubleOnFoldable` :269,
`readerDoublePagesSensitivity` :274, `readerAnimation` :502,
`readerColorFilter` :914 (five `cf_*` keys), `isReaderUpscaleEnabled` :407,
`readerControlsLayout` :419 + `readerControls` :440 (tap grid),
`readerAutoscrollSpeed` :1029, `readerAutoscrollPageDelay` :1043,
`isReaderAutoscrollFabVisible` :1048, `isPagesCropEnabled` :1101.

**Reader - Android platform, cannot move**
`readerScreenOrientation` :278 (returns `ActivityInfo.SCREEN_ORIENTATION_*`),
`isReaderVolumeButtonsEnabled` :282, `isReaderFullscreenEnabled` :401,
`isReaderKeepScreenOn` :911, `isReaderMultiTaskEnabled` :554,
`isReaderTtsFabVisible` :1056, `isStatusBarHidden` :114,
`isPagesPreloadEnabled` :1062 (no key at all - computed from
`ConnectivityManager.restrictBackgroundStatus`, `:1196-1202`).

**EPUB / novel reader - 22 properties, all deferred with D2**
`epubFontSize` :285 through `epubCustomFontRevision` :388, plus
`epubTts*` :333/:338/:346 (`android.speech.tts`), `isNovelTabFirst` :359.

**Library / list**
`listMode` :88, `historyListMode` :238, `favoritesListMode` :246,
`suggestionsListMode` :242, `gridSize` :165, `gridSizePages` :217,
`isGridSpacingIncreased` :213, `isTitleOverCover` :176,
`isTitleTapToReadEnabled` :180, `isListCheckpointEnabled` :184,
`isHistoryGroupingEnabled` :514, `isUpdatedGroupingEnabled` :518,
`progressIndicatorMode` :523, `historySortOrder` :988,
`allFavoritesSortOrder` :992, `getPinnedFavourites` :997 /
`setPinnedFavourites` :1003, `isAllFavouritesVisible` :460,
`localListOrder` :984, `defaultBrowseSortOrder` :580,
`isQuickFilterEnabled` :221, `isDuplicateCheckEnabled` :204,
`isRelatedMangaEnabled` :1007, `getMangaListBadges` :1165.

**Details screen**
`detailsUiMode` :542, `defaultDetailsTab` :706, `lastDetailsTab` :718,
`isPagesTabEnabled` :703, `isBackdropEnabled` :224,
`backdropBlurAmount` :227, `isChaptersReverse` :557,
`isChaptersGridView` :561, `isChaptersSortedByName` :566.

**Network**
`dnsOverHttps` :948, `proxyType` :966 (`java.net.Proxy.Type`),
`proxyAddress` :972, `proxyPort` :975, `proxyLogin` :978,
`proxyPassword` :981, `isSSLBypassEnabled` :962, `imagesProxy` :942,
`mihonUserAgentOverride` :957, `isAdBlockEnabled` :847,
`isOfflineCheckDisabled` :457, `isContentPrefetchEnabled` :722.

**Storage and local files**
`userSpecifiedMangaDirectories` :850 (`Set<File>`), `mangaStorageDir` :860
(`File?`), `getPagesSaveDir(context)` :1126 (returns `DocumentFile?`),
`setPagesSaveDir(uri: Uri?)` :1161, `isPagesSavingAskEnabled` :1085,
`isAutoLocalChaptersCleanupEnabled` :1091, `localChaptersCleanupKeep` :1098.

**Backup**
`isPeriodicalBackupEnabled` :1131, `periodicalBackupDirectory` :1134
(`Uri?`), `periodicalBackupFrequencyMillis` :1141,
`periodicalBackupMaxCount` :1155, plus `upsertAll` :1194 and
`getAllValues` :1189 which are the backup/restore entry points.

**Theme and shell**
`theme` :92 (`Int`, `AppCompatDelegate.MODE_NIGHT_*`), `colorScheme` :100,
`isAmoledTheme` :107, `uiScalePercent` :172, `appLocales` :254
(`LocaleListCompat`), `mainNavItems` :129, `isNavLabelsVisible` :145,
`isNavBarPinned` :148, `isLegacyNavigationBar` :151,
`isMainFabEnabled` :154, `isExitConfirmationEnabled` :697,
`isOnboardingCompleted` :117, `dismissedUpdateVersion` :161,
`isTipEnabled` :1110 / `closeTip` :1114.

**Android-only, never moves**
`screenshotsPolicy` :844, `isAppProtectionEnabled` :591,
`appProtectionTimeout` :595, `appPasswordHash` :603,
`isAppPasswordSet` :607, `setAppPassword` :611, `verifyAppPassword` :624,
`isDynamicShortcutsEnabled` :700, `isShizukuInstallerEnabled` :655,
`isPrivateInstallEnabled` :663, `allowDownloadOnMeteredNetwork` :876,
`isTrackerWifiOnly` :467, `isSuggestionsWiFiOnly` :887.

**Mihon / LNReader extension management - cut with D3/D2**
`mihonPerExtActiveLangs` :747, `mihonHiddenPackages` :766,
`lnHiddenPlugins` :776, `externalExtensionsRepoUrl` :788,
`externalRepoInfos` :809, `extensionStoreRegistryState` :814,
`isExtensionStoreMigrationComplete` :822, `isAutoUpdateExtensionsEnabled` :670,
`hasExtensionUpdates` :678, `isExtensionUpdateNotificationsEnabled` :682,
`lastExtensionUpdateNotificationTime` :686, `novelSourceIds` :635,
`isGlobalSearchNovelScope` :643.

**Features cut from v1**
Tracker (`isTrackerEnabled` :464 and 7 more), suggestions
(`isSuggestionsEnabled` :883 and 5 more), stats (`isStatsEnabled` :1088),
Discord (`isDiscordRpcEnabled` :1068, `discordToken` :1074),
scrobbling (`trackSources` :588, `isScrobblingProgressSyncEnabled` :1010),
downloads (`preferredDownloadFormat` :880).

### Minimal key set desktop v1 actually needs

Mapping `DECISIONS.md` §2 "In" scope onto the 205 keys gives **50**.
Grouped by the v1 feature that forces each one in.

| v1 feature | Keys |
|---|---|
| browse catalogue, enable/disable sources | `sources_sort_order`, `sources_grid`, `hidden_source_languages` |
| per-source browsing, sort, filter | `default_browse_sort`, `list_mode_2`, `grid_size`, `quick_filter`, `no_nsfw` |
| search within a source | `search_local_only` |
| details | `details_ui`, `details_tab`, `details_last_tab`, `reverse_chapters`, `grid_view_chapters`, `sort_chapters_by_name_` |
| reader paged + webtoon | `reader_mode`, `reader_mode_detect`, `reader_background`, `zoom_mode`, `webtoon_zoom`, `webtoon_zoom_out`, `webtoon_gaps`, `pages_numbers`, `reader_bar`, `reader_navigation_inverted`, `reader_taps_ltr` |
| favourites with categories | `fav_order`, `fav_pinned_order_`, `all_favourites_visible`, `list_mode_favorites` |
| history, resume | `history_order`, `history_grouping`, `list_mode_history`, `reading_indicator_enabled`, `incognito`, `incognito_nsfw` |
| local CBZ import | `local_manga_dirs`, `local_storage`, `local_order` |
| network (needed by every source fetch) | `doh`, `proxy_type_2`, `proxy_address`, `proxy_port`, `proxy_login`, `proxy_password`, `ssl_bypass`, `images_proxy_2` |
| theme | `theme`, `color_theme`, `amoled_theme` |
| backup/restore | none of its own; `getAllValues` / `upsertAll` operate over the whole map |

That is 50 keys, or **39** if the network block (8) and the theme block
(3) are handed to the agents who own those subsystems - B owns the network
stack, E owns theming. **Either way it is under a quarter of the 205 keys,
and the properties behind them number well under 50 of the 177.**
D8's "desktop implements only what it uses" is the right call and the
number backs it.

Three of those keys need care:

- `incognito` and `incognito_nsfw` (:550, :546) gate whether history rows
  are written at all. They are a *data-layer* switch, not a UI
  preference, and if they are omitted the desktop app silently always
  records history. Include them in v1 even if no settings screen exposes
  them.
- `fav_pinned_order_` (:997/:1003) feeds `FavouritesDao.getOrderBy(order, pinned)`
  at `favourites/data/FavouritesDao.kt:258-271`, which interpolates the
  pinned ids straight into a `CASE ... WHEN <id> THEN <i>` SQL fragment.
  A missing or malformed value there produces broken SQL, not a wrong
  default.
- `local_storage` (:860) is typed `File?` and `local_manga_dirs` (:850) is
  `Set<File>`. Both are already `java.io.File`, not `Uri`, so they port
  as-is. The `Uri`-typed ones are `periodicalBackupDirectory` (:1134) and
  the `pages_dir` pair (:1126/:1161), all of which are out of v1 scope.

### How `observeChanges` works

Three layers.

**Layer 1**, `core/util/ext/Preferences.kt:28-36`:

```kotlin
fun SharedPreferences.observeChanges(): Flow<String?> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        trySendBlocking(key)
    }
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}
```

A cold `callbackFlow` of **changed key names**, not values. The element
type is `String?` and `null` is meaningful: Android's contract emits a
null key when `Editor.clear()` was called, i.e. "assume everything
changed".

**Layer 2**, `AppSettings.kt:1182-1187`:

```kotlin
fun observeChanges() = prefs.observeChanges()

fun observe(vararg keys: String): Flow<String?> = prefs.observeChanges()
    .filter { key -> key == null || key in keys }
    .onStart { emit(null) }
    .flowOn(Dispatchers.IO)
```

`onStart { emit(null) }` gives every subscriber one synthetic "everything
changed" tick so consumers can use a single code path for "read initial
value" and "value changed". `flowOn(Dispatchers.IO)` moves the upstream,
which matters because the SharedPreferences listener fires on the main
thread.

**Layer 3**, `core/prefs/AppSettingsObserver.kt:10-31`, two adapters that
turn a key into a value stream:

```kotlin
fun <T> AppSettings.observeAsFlow(key: String, valueProducer: AppSettings.() -> T)
fun <T> AppSettings.observeAsStateFlow(scope, key, valueProducer): StateFlow<T>
```

`observeAsFlow` emits the current value first, then re-reads on a matching
key and **suppresses duplicates itself** (`:16-19`).
`observeAsStateFlow` uses `stateIn(scope, SharingStarted.Eagerly, valueProducer())`.

99 call sites across the tree use `observeChanges()` or
`settings.observe(...)`.

### What a desktop `StateFlow`-based equivalent must reproduce

Not just "a flow of values". Seven behaviours, and getting any of them
wrong is a silent bug:

1. **Key-level granularity, not whole-object.** All 99 consumers filter by
   key string. A single `StateFlow<SettingsSnapshot>` would wake every one
   of them on every write.
2. **The `null` key wildcard.** `observe()` at `:1185` passes `null`
   through the filter unconditionally. Restore (`upsertAll`, `:1194`)
   writes many keys at once; on Android that is one `edit {}` producing
   many callbacks. A desktop store that writes a JSON file wholesale must
   emit `null` (or every affected key) after a restore, or the UI will not
   refresh after "Restore from backup" - which is D15's headline feature.
3. **The synthetic initial `null`.** `onStart { emit(null) }` at `:1186`.
   A `MutableStateFlow<String?>` naturally replays its current value to
   new subscribers, which is *similar* but not identical: `StateFlow`
   conflates, so two rapid writes to different keys can collapse and one
   key's notification is lost. **`MutableSharedFlow` with
   `extraBufferCapacity` and `replay = 0`, plus an explicit `onStart`,
   reproduces the semantics; a `MutableStateFlow<String?>` does not.**
   This is the single most likely thing to get wrong.
4. **Non-blocking writers.** `trySendBlocking` at `:30` blocks the writing
   thread if the buffer is full. On desktop the writer may be a UI
   coroutine. Use `tryEmit` on a buffered `MutableSharedFlow` and accept a
   dropped duplicate rather than blocking.
5. **Type-change tolerance.** SharedPreferences throws
   `ClassCastException` when a key is read as a type other than the one
   stored, and `AppSettings` relies on that: `progressIndicatorMode`
   (`:523-540`) catches `ClassCastException` **twice** to migrate a key
   that used to hold a `Boolean` and now holds a `String`. A JSON-backed
   desktop store where everything is a `JsonPrimitive` will not throw, so
   that migration branch silently never runs. Any desktop store must
   either throw on type mismatch or the migrations must be rewritten.
6. **`Set<String>` as a first-class type.** 15 `getStringSet` / 7
   `putStringSet` sites. `java.util.prefs` (one of the two options
   `PORTING_NOTES.md` §C names) has **no** set type and a 8192-character
   per-value limit, which `mihon_repo_infos` and `extension_store_registry`
   (JSON blobs in a pref) would exceed. A JSON file is the right choice;
   `java.util.prefs` should be ruled out in `DECISIONS.md` for those two
   reasons rather than left as an alternative.
7. **`getAllValues(): Map<String, *>` and `upsertAll(Map<String, *>)`**
   (`:1189`, `:1194`) are the backup format's contract. The comment at
   `:1191-1193` says restore deliberately merges rather than clearing.
   Whatever desktop stores, those two must round-trip the same primitive
   types (`Boolean`/`Int`/`Long`/`Float`/`String`/`Set<String>`) because
   `core/util/ext/Preferences.kt:47-56` dispatches on exactly those.

### `SourceSettings` (144 lines)

`core/prefs/SourceSettings.kt:18`:
`class SourceSettings(context: Context, source: MangaSource) : MangaSourceConfig`.

**This is the most directly load-bearing prefs file for D1 and it is not
mentioned in `DECISIONS.md`.** `MangaSourceConfig` is
`org.koitharu.kotatsu.parsers.config.MangaSourceConfig` (`:11`) - the
return type of `MangaLoaderContext.getConfig(source)`, which
`PORTING_NOTES.md` §C lists as one of the seven members
`DesktopMangaLoaderContext` must implement. So porting `SourceSettings` is
a prerequisite for D1, not a settings-screen nicety. Agent B owns the
context; this is the piece that plugs into it.

One `SharedPreferences` **per source**, named by
`getStorageName(source.name)` (`:20`, `:100-111`).

Surface:

| Member | Line | Note |
|---|---|---|
| `defaultSortOrder: SortOrder?` | :26-28 | enum-as-string |
| `lastSortTagKey` / `lastSortTagTitle` | :32-38 | Mihon-only (dynamic FilterList sort), cut with D3 |
| `isSlowdownEnabled` | :41 | |
| `isCaptchaNotificationsDisabled` | :44 | |
| `isInterceptCloudflareEnabled` | :47 | ties to the Cloudflare cut in `DECISIONS.md` §2 |
| `get(key: ConfigKey<T>): T` | :52-67 | the `MangaSourceConfig` override. Handles 5 `ConfigKey` subtypes: `UserAgent`, `Domain`, `ShowSuspiciousContent`, `SplitByTranslations`, `PreferredImageServer` |
| `set(key: ConfigKey<T>, value: T)` | :69-77 | same 5 |
| `subscribe` / `unsubscribe` | :79-85 | raw listener, no Flow wrapper |

Only two Android touch points, both shallow:

- `context.getSharedPreferences(name, MODE_PRIVATE)` at `:20` and `:117`
  and `:119`. On desktop this is one JSON file per source under
  `$XDG_CONFIG_HOME/dropsauce/sources/<name>.json`.
- `androidx.core.content.edit` at `:5`.

`java.io.File.separatorChar` at `:109` and `:115` is used to sanitise a
source name into a filename. On Linux that is `/` where Android is also
`/`, so behaviour is identical; it would differ on Windows, which is out
of scope per `DECISIONS.md` §2.

`getStorageName` (`:100-111`) special-cases `MIHON_` prefixed source names
into `source_<id>`. With D3 cutting Mihon, desktop will only ever hit the
`else` branch (`:109`). `migrateLegacyStorageIfNeeded` (`:113-133`) exists
only to move Mihon-era storage names and is dead code on desktop.

`isValidDomain` (`:89-98`) builds an `okhttp3.HttpUrl.Builder` - already
portable, and already the type `DECISIONS.md` D9 chose for remote
addressing.

**Note for Agent B:** `SourceSettings` has no `observeChanges` Flow at
all, only the raw `subscribe`/`unsubscribe` pair (`:79-85`). If the
desktop source-settings UI needs reactivity it is new code, not a port.

---

## 6. What I will get wrong

Ranked by expected damage, which is severity times how late you find out.
Every one of these compiles clean.

### 1. Transaction re-entrancy: the 48 `withTransaction` blocks call suspend DAO methods from inside, and the KMP replacement may deadlock rather than fail

**Why.** Android's `withTransaction` installs a transaction marker in the
coroutine context; a suspend DAO method invoked inside the block finds it
and joins the open transaction. The KMP shape is different:
`useWriterConnection { transactor -> transactor.immediateTransaction { ... } }`
checks out the single writer connection from Room's pool. Every DAO call
inside that lambda checks out a writer connection too. If Room's JVM
connection pool does not detect that the current coroutine already holds
the writer, the inner call waits for a connection that the outer block
will not release until the inner call returns.

**Why it is ranked first.** That is a **deadlock, not an exception**. It
produces a hung coroutine with no stack trace, no log line, and a green
build. And it is not rare: `favourites/domain/FavouritesRepository.kt` has
9 of these, `history/data/HistoryRepository.kt` has 7,
`core/parser/MangaDataRepository.kt` has 6, and every one of them wraps
multiple DAO writes - that is the entire point of using a transaction.
`MangaDataRepository.kt:107` even does `return db.withTransaction { ... }`
with a value, so the failure surfaces as a screen that never loads.

**What I do not know.** Whether Room 2.8.4's `androidx.room.coroutines`
connection pool on JVM is re-entrant for the writer connection within one
coroutine. I found `androidx/room/coroutines/PassthroughConnection$PassthroughTransactor`
in the published `room-runtime-jvm-2.8.4.jar`, whose name suggests
exactly this re-entrancy shim, but I did not execute it and a class name
is not behaviour.

**Test that settles it early, before any repository is moved.** A
throwaway `:desktop` main with a two-table Room database, one DAO with two
suspend `@Insert` methods, and:

```kotlin
withTimeout(5_000) {
    db.useWriterConnection { it.immediateTransaction { dao.insertA(...); dao.insertB(...) } }
}
```

Positive control first: run the same two inserts **outside** a transaction
and assert both rows land, so you know the harness works before you trust
the transaction result. Then assert the transaction version completes
inside the timeout and both rows are present. Also assert rollback: throw
from between the two inserts and assert neither row exists. Fifteen
minutes of work; it decides whether D5 step 3 is a week or a month.

### 2. `@RawQuery(observedEntities = ...)` returning `Flow` is the backbone of every v1 list, and its invalidation on the bundled JVM driver is unverified

**Why.** Favourites and history - both explicitly in v1 scope - do not
use `@Query`. They use `MangaQueryBuilder` into
`FavouritesDao.observeAllImpl` (`favourites/data/FavouritesDao.kt:238-240`)
and `HistoryDao.observeAllImpl` (`history/data/HistoryDao.kt:193-195`),
each `@Transaction @RawQuery(observedEntities = [...]) fun ...: Flow<List<...>>`.
Room satisfies that by registering the named entities' tables with the
`InvalidationTracker` and re-running the raw SQL when they change. Three
things have to line up on desktop: the `@RawQuery` + `Flow` +
`observedEntities` combination must be supported by the KMP code
generator, `InvalidationTracker` must work under `androidx.sqlite:sqlite-bundled`
rather than the Android framework helper, and it must fire across the
pool's separate reader and writer connections.

**Failure mode.** The library screen renders once, correctly, and then
never updates. You add a favourite and nothing happens until restart. A
developer will reasonably spend a day looking at Compose recomposition
before suspecting Room.

**What I confirmed.** `androidx.room.InvalidationTracker` exists in
`room-runtime-jvm-2.8.4.jar` with
`createFlow(vararg tables: String): Flow<Set<String>>`. The `@RawQuery`
annotation is in `room-common-jvm`. I did **not** verify the generated
code path.

**Test.** In the same throwaway harness: a `@RawQuery(observedEntities = [A::class])`
method returning `Flow<List<A>>`, collected into a list; insert a row
through an unrelated `@Insert`; assert a second emission arrives within a
timeout. Positive control: do the same with a plain `@Query` Flow first,
so you can tell "raw query invalidation is broken" apart from
"invalidation is broken".

### 3. Dropping `sqlEscapeString` turns a crafted backup file into SQL injection

**Why this is real and not theoretical.** `MangaQueryBuilder` builds SQL
by string concatenation with zero bind arguments (`core/db/MangaQueryBuilder.kt:100`,
the single-argument `SimpleSQLiteQuery` constructor). The only thing
standing between that and injection is
`android.database.DatabaseUtils.sqlEscapeString`, used at 8 sites in 5
DAOs (`FavouritesDao.kt:280-281`, `HistoryDao.kt:205-206`,
`SuggestionDao.kt:73-79`, `TracksDao.kt:132`, `TrackLogsDao.kt:87`). That
class does not exist on the JVM. The tempting move when the compiler
complains is to delete the call, because the argument "looks like" an
enum name.

It is not always an enum name. `core/model/MangaSource.kt:32-33` declares
`class MissingMangaSource(override val name: String, ...)` - the `name` is
whatever string sits in the `manga.source` column, and that column is
populated from restored backups. `DECISIONS.md` D15 makes backup/restore
the **supported interop path** between Android and desktop, so a backup
file is an untrusted input by design, and `ListFilterOption.Source(...).name`
carries its contents straight into concatenated SQL.

**Test.** Restore a hand-edited backup whose `manga.source` value is
`X' OR 1=1 --`, then apply a source filter on the library screen. With a
correct escaper the filter matches nothing; without one the filter matches
everything, which is the visible tell. Then assert the stronger case with
a value containing `'); DROP TABLE favourites; --`. Write the escaper
first and this test passes on day one; skip it and the test is how you
find out.

**Also note** the same class of exposure on the non-escaped paths that are
*not* currently escaped and rely on the value being numeric:
`FavouritesDao.kt:264` builds `CASE favourites.manga_id WHEN $id THEN $i`
from the `fav_pinned_order_` preference, and `StatsDao.kt:56`
interpolates `favouriteCategories.joinToString(",")`. Those are `Long`s
today. If the desktop settings store round-trips them as strings - which
a JSON file naturally does - they stop being `Long`s.

### 4. Moving `core/db/MangaDatabase.kt` into `:shared` breaks the Android app in two ways that the compiler will not both catch

**4a, a compile error you will hit immediately.**
`MangaDatabase.kt:172-180` declares
`fun InvalidationTracker.removeObserverAsync(observer: InvalidationTracker.Observer)`.
`InvalidationTracker.Observer` **does not exist on the JVM**:

```
android: javap androidx.room.InvalidationTracker$Observer
  -> Compiled from "InvalidationTracker.android.kt"  (exists)
jvm:     javap androidx.room.InvalidationTracker$Observer
  -> Error: class not found
```

Same for `addObserver` / `removeObserver` / `addWeakObserver`, which the
Android `InvalidationTracker` has and the JVM one does not. The JVM
replacement is `createFlow(vararg tables): Flow<Set<String>>`. The three
consumers are `core/os/AppShortcutManager.kt:52`,
`widget/common/WidgetRefreshObserver.kt:22` and
`settings/tracker/TrackerSettingsViewModel.kt:64`, wired through Hilt
multibinding at `core/AppModule.kt:160-166` and consumed at
`core/BaseApp.kt:44`. All three are Android-only features already cut, so
the fix is simply that `removeObserverAsync` and the factory function
`MangaDatabase(context)` (`:166-170`) stay behind in `:app` while the
`@Database` class moves. Annoying, not dangerous - because it does not
compile.

**4b, the dangerous one, which does compile.** Room KMP is configured with
`RoomDatabase.Builder.setDriver(SQLiteDriver)`. Verified by bytecode, if
`:app` ever gains a `setDriver(...)` call - and it will be tempting once
`:shared` needs one, or if someone "unifies" the two builders - then all
36 migrations take this branch of `Migration.migrate(SQLiteConnection)`:

```
 7: instanceof  androidx/sqlite/driver/SupportSQLiteConnection
10: ifeq        27
27: new         kotlin/NotImplementedError
31: ldc         "Migration functionality with a provided SQLiteDriver requires overriding the migrate(SQLiteConnection) function."
```

`:app:assembleDebug` stays green. Nothing fails in a fresh install, because
a fresh install runs `onCreate`, not migrations. It fails only for users
upgrading from an older schema version, at first launch after the update,
with a `kotlin.NotImplementedError` - the exact thing the project's hard
rule 3 forbids, delivered by the library instead of written by us.

**Test.** An instrumented or Robolectric migration test that opens a v36
database file and migrates it to 37, run on `:app` as it is configured
today, and kept in CI. It fails the moment someone adds `setDriver`. If
that is too heavy, the cheap version is a `grep -r "setDriver" app/` in
the same CI step that runs the Android gate, with a comment pointing here.
Note that `app/build.gradle:30` already sets
`arg('room.generateKotlin', 'true')`, so the Kotlin-codegen prerequisite
for Room KMP is met and one common source of surprise is already absent.

### 5. A fresh desktop database has zero favourite categories, and restoring an Android backup will not give it one

Established in §4. `core/db/DatabasePrePopulateCallback.kt:11-25` seeds the
"Read later" category on `onCreate`, that row is **not** in the v37
`CREATE` statements, and `DECISIONS.md` D14 says nothing about the
callback. Then `backup/local/data/LocalBackupRepository.kt:433-442`
deletes an empty "Read later" after every restore, matching on the
**localized** title via `context.getString(R.string.read_later)`.

So the two failure modes are: desktop starts with no categories and the
restore path removes the one the backup brought; or the desktop
`StringProvider` returns a different localization than the Android device
that wrote the backup, the title match at `:438` fails, and each restore
adds another duplicate category.

Neither throws. Both look like a UI bug in the favourites screen.

**Test.** Two assertions, both cheap and both worth keeping:
(a) build a fresh desktop database, `SELECT COUNT(*) FROM favourite_categories`,
assert it is 1 and the title matches the desktop locale's "Read later";
(b) restore an Android-produced backup into a fresh desktop database twice
in a row and assert the count does not grow. Run (b) with a non-English
Android backup, because English-to-English is the case that accidentally
passes.

### Honourable mentions, ranked 6 to 8

- **`ClassCastException` type tolerance in preferences.** `AppSettings.kt:523-540`
  catches `ClassCastException` twice to migrate `reading_indicator_enabled`
  from `Boolean` to `String`. A JSON-backed desktop store will never
  throw, so that migration branch is dead and the value reads as its
  default. Test: write a legacy `Boolean` into the desktop store and
  assert `progressIndicatorMode` resolves to `PERCENT_READ`, not the
  string-path default.
- **`java.util.prefs` as the desktop backing store.** `PORTING_NOTES.md`
  §C offers it as an option. It has no set type (needed by 15
  `getStringSet` sites) and an 8192-character value limit that
  `mihon_repo_infos` and `extension_store_registry` would exceed. It
  should be struck from the options, not left for someone to try.
- **`android.database.SQLException` and `SQLiteFullException`.** Caught at
  `bookmarks/domain/BookmarksRepository.kt:3` and
  `core/util/ext/Throwable.kt:5`. The published `sqlite-jvm-2.6.2.jar`
  exposes only `androidx.sqlite.SQLiteException(String)` - no error code,
  no subclass hierarchy. **`unknown`: whether `sqlite-bundled` gives any
  way to distinguish disk-full.** What would settle it: write into a small
  tmpfs until it fills and inspect the thrown exception's message.
