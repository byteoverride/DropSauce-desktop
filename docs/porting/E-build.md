# E - Build system

Phase 1 recon, Agent E. Everything below was produced by a real Gradle
build or a real Maven metadata fetch in a throwaway project under
`/tmp/.../scratchpad/probe-e/`. No version here is recalled from memory.
The repo was not modified; this file is the only write.

Environment: OpenJDK 21.0.12.1, Gradle 9.6.1, Android SDK with
android-36 / android-37.0 / android-37.1, Debian 13.

---

## TASK 1 - The gate: Kotlin 2.3.21 + CMP + Room KMP in one build

**Verdict: PROVEN. They coexist. D6's first clause holds.**

The exact combination that compiled, both targets, from a clean project:

| Component | Version | Evidence |
|---|---|---|
| Gradle | 9.6.1 | wrapper, same as the repo |
| Kotlin Multiplatform | `org.jetbrains.kotlin.multiplatform` **2.3.21** | build succeeded |
| Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose` **2.3.21** | build succeeded |
| Compose Multiplatform | `org.jetbrains.compose` **1.12.0** | build succeeded |
| AGP (KMP flavour) | `com.android.kotlin.multiplatform.library` **9.2.1** | build succeeded |
| KSP | `com.google.devtools.ksp` **2.3.6** | `kspKotlinJvm`, `kspAndroidMain` both ran |
| Room Gradle plugin | `androidx.room` **2.8.4** | `copyRoomSchemas` ran |
| Room runtime | `androidx.room:room-runtime` **2.8.4** -> `room-runtime-jvm:2.8.4` | resolved on `jvmCompileClasspath` |
| Room compiler | `androidx.room:room-compiler` **2.8.4** | generated `GateDbConstructor.kt` for both targets |
| SQLite driver | `androidx.sqlite:sqlite-bundled` **2.8.0-alpha01** -> `sqlite-bundled-jvm` | resolved |
| Skiko (transitive) | `org.jetbrains.skiko:skiko` **0.150.1** | resolved |

There is **no Kotlin version gap**. CMP 1.12.0 does not pin or complain
about Kotlin 2.3.21. The CMP plugin's own
`checkJvmMainComposeLibrariesCompatibility` task ran and passed. So the
PORTING_NOTES §G item 2 risk ("Kotlin 2.3.21 vs CMP 1.12.0") is
**refuted**: no Kotlin move is needed and the Android gate is not touched
on that account.

### Latest CMP stable

`org.jetbrains.compose:compose-gradle-plugin` maven-metadata (Maven
Central, lastUpdated 20260909123336) tail:

```
1.11.0  1.11.1
1.12.0-alpha01 ... 1.12.0-rc01  1.12.0
1.13.0-alpha01
```

**1.12.0 is the newest stable.** The only thing newer is
`1.13.0-alpha01`. PORTING_NOTES and D16 are correct.

### The one real blocker found, and it is AGP, not Kotlin

The first attempt used `com.android.library` on the KMP module, which is
what every pre-AGP-9 KMP guide tells you to do. AGP 9.2.1 rejects it:

```
* What went wrong:
An exception occurred applying plugin request [id: 'com.android.library']
> Failed to apply plugin 'com.android.internal.library'.
   > The 'com.android.library' (or 'com.android.application') plugin is not compatible with the 'org.jetbrains.kotlin.multiplatform' plugin since AGP 9.0.
     Solution:
       - [Recommended] Replace the 'com.android.library' plugin with the 'com.android.kotlin.multiplatform.library' plugin (see https://developer.android.com/kotlin/multiplatform/plugin).
       - Or set the Gradle property 'android.builtInKotlin=false' and 'android.newDsl=false' to temporarily bypass this issue.
```

This is the single most important structural fact for `:shared`. See
TASK 3 for what it changes.

### The build file that worked

`shared/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        namespace = "demo.gate"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
                implementation("androidx.room:room-runtime:2.8.4")
                implementation("androidx.sqlite:sqlite-bundled:2.8.0-alpha01")
            }
        }
        val jvmMain by getting {
            dependencies { implementation(compose.desktop.currentOs) }
        }
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    add("kspJvm", "androidx.room:room-compiler:2.8.4")
    add("kspAndroid", "androidx.room:room-compiler:2.8.4")
}
```

Note the DSL shape: there is **no `androidTarget()`** and **no top-level
`android { }` block**. `androidLibrary { }` lives inside `kotlin { }` and
creates the android target itself. `minSdk` is a direct property, not
inside `defaultConfig`.

KSP configuration names are `kspJvm` and `kspAndroid`; the resulting task
names are `kspKotlinJvm` and `kspAndroidMain`.

### The sources that compiled

A real `@Entity` / `@Dao` / `@Database` in `commonMain`, using the Room
KMP `@ConstructedBy` + `expect object ... : RoomDatabaseConstructor<T>`
pattern (this is mandatory for Room KMP; the Android-only reflective
constructor is not available in common code):

```kotlin
@Entity(tableName = "manga")
data class MangaEntity(@PrimaryKey val id: Long, val title: String)

@Dao
interface MangaDao {
    @Query("SELECT * FROM manga WHERE id = :id") suspend fun find(id: Long): MangaEntity?
    @Insert suspend fun insert(e: MangaEntity)
}

@Database(entities = [MangaEntity::class], version = 1, exportSchema = true)
@ConstructedBy(GateDbConstructor::class)
abstract class GateDb : RoomDatabase() { abstract fun mangaDao(): MangaDao }

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object GateDbConstructor : RoomDatabaseConstructor<GateDb> {
    override fun initialize(): GateDb
}
```

plus a `commonMain` `@Composable` and a `jvmMain` `application { Window { } }`.

Build output:

```
> Task :shared:checkJvmMainComposeLibrariesCompatibility
> Task :shared:kspKotlinJvm
> Task :shared:kspAndroidMain
> Task :shared:copyRoomSchemas
> Task :shared:compileKotlinJvm
> Task :shared:compileAndroidMain
BUILD SUCCESSFUL in 1m 49s
7 actionable tasks: 7 executed
Configuration cache entry stored.
```

The only warnings were the expected `'expect'/'actual' classes ... are in
Beta` for the generated `GateDbConstructor`. Suppress project-wide with
`-Xexpect-actual-classes`; it is not an error.

### Sub-findings

- Room 2.8.4 resolves `androidx.sqlite:sqlite:2.6.2` by default; declaring
  `sqlite-bundled:2.8.0-alpha01` upgraded the whole sqlite graph to
  `2.8.0-alpha01` cleanly (`androidx.sqlite:sqlite:2.6.2 -> 2.8.0-alpha01`).
  Latest **stable** `sqlite-bundled` per Google Maven metadata is **2.7.1**;
  `2.8.0-alpha01` is the only 2.8 line release. D16 should name a version;
  it currently does not.
- Room **2.8.5** exists on Google Maven (newer than the repo's 2.8.4). Not
  required, noted for completeness.

---

## TASK 2 - Material 3 Expressive on CMP

**Verdict: the APIs EXIST, but not at the version CMP 1.12.0 gives you by
default. You must pin material3 explicitly. With that pin, all six are
present and public, and the blast radius on the existing code is 3 files.**

### The trap: CMP material3 is version-decoupled from CMP

`compose.material3` in the CMP 1.12.0 DSL does **not** resolve to 1.12.0.
Strings from `ComposePlugin.class` inside `compose-gradle-plugin-1.12.0.jar`:

```
org.jetbrains.compose.runtime:runtime        "org.jetbrains.compose.runtime:runtime:1.12.0"
org.jetbrains.compose.foundation:foundation  "org.jetbrains.compose.foundation:foundation:1.12.0"
org.jetbrains.compose.ui:ui                  "org.jetbrains.compose.ui:ui:1.12.0"
org.jetbrains.compose.material3:material3    "org.jetbrains.compose.material3:material3:1.9.0"
org.jetbrains.compose.material3:material3-adaptive-navigation-suite
                                             "...:material3-adaptive-navigation-suite:1.9.0"
```

Confirmed by resolution, not just by strings:

```
$ ./gradlew :shared:dependencies --configuration jvmCompileClasspath
org.jetbrains.compose.material3:material3:1.9.0
org.jetbrains.compose.material3:material3-desktop:1.9.0
```

`org.jetbrains.compose.material3:material3` maven-metadata shows **1.9.0
is the last stable** on that artifact. Everything after it is an alpha:
`1.10.0-alpha01..05`, `1.11.0-alpha01..07`, `1.12.0-alpha01..03`,
`1.13.0-alpha01`. There is no `material3:1.12.0`
(`material3-1.12.0.pom` -> HTTP 404).

### Presence matrix

Determined by `javap -public` on the real desktop jars
(`material3-desktop-<v>.jar` from Maven Central) and then confirmed by
compiling against them.

| API | CMP material3 **1.9.0** (the default) | CMP material3 **1.12.0-alpha03** |
|---|---|---|
| `MotionScheme` (interface) | present but **internal** | **present, public** |
| `MotionScheme.expressive()` / `.standard()` | **internal** | **present, public** |
| `MaterialTheme.motionScheme` | present but **internal** | **present, public** |
| `MaterialExpressiveTheme` | present but **internal** | **present, public** |
| `ExperimentalMaterial3ExpressiveApi` | present but **internal** | **present, public** |
| `MaterialShapes` | **absent** | **present, public** (all ~34 shapes) |
| `ButtonGroup` | **absent** | **present, public** |
| `LinearWavyProgressIndicator` | **absent** | **present, public** |
| `CircularWavyProgressIndicator` | **absent** | **present, public** |
| `FloatingActionButtonMenu` / `...MenuItem` / `ToggleFloatingActionButton` | **absent** | **present, public** |

"internal" is not a guess. Kotlin mangles `internal` members with the
module name, and `javap` on 1.9.0 shows exactly that:

```
$ javap -public -cp m3-1.9.0.jar 'androidx.compose.material3.MotionScheme$Companion'
public final class androidx.compose.material3.MotionScheme$Companion {
  public final androidx.compose.material3.MotionScheme standard$material3();
  public final androidx.compose.material3.MotionScheme expressive$material3();
}

$ javap -public -cp m3-1.12.0-alpha03.jar 'androidx.compose.material3.MotionScheme$Companion'
public final class androidx.compose.material3.MotionScheme$Companion {
  public final androidx.compose.material3.MotionScheme standard();
  public final androidx.compose.material3.MotionScheme expressive();
}
```

This is the same situation `libs.versions.toml` already documents for
androidx material3 1.4.0 stable. CMP material3 1.9.0 is that generation.

### Signatures, for the ones present in 1.12.0-alpha03

```
androidx.compose.material3.WavyProgressIndicatorKt:
  LinearWavyProgressIndicator-1YwxWKA(Function0<Float> progress, Modifier, long, long,
      Stroke, Stroke, float, float, Function1<Float,Float>, float, float, ...)
  LinearWavyProgressIndicator-hvuEXSk(Modifier, long, long, Stroke, Stroke, float, float, float, float, ...)
  CircularWavyProgressIndicator-L8eD4gc(Function0<Float> progress, ...)
  CircularWavyProgressIndicator-hvuEXSk(Modifier, ...)

androidx.compose.material3.ButtonGroupKt:
  ButtonGroup(Function3<ButtonGroupMenuState,Composer,Integer,Unit> overflowIndicator,
      Modifier, float, Arrangement$Horizontal, Alignment$Vertical,
      Function1<ButtonGroupScope,Unit> content, ...)

androidx.compose.material3.FloatingActionButtonMenuKt:
  FloatingActionButtonMenu(boolean expanded, Function2 button, Modifier,
      Alignment$Horizontal, Function3<FloatingActionButtonMenuScope,...> content, ...)
  FloatingActionButtonMenuItem-WMdw5o4(FloatingActionButtonMenuScope, Function0 onClick,
      Function2 icon, Function2 text, Modifier, long, long, ...)
  ToggleFloatingActionButton(boolean, Function1<Boolean,Unit>, ...)

androidx.compose.material3.MaterialThemeKt:
  MaterialExpressiveTheme(ColorScheme, MotionScheme, Shapes, Typography, Function2 content, ...)

androidx.compose.material3.MaterialShapes$Companion:
  getCircle/getSquare/getSlanted/getArch/getFan/getArrow/getSemiCircle/getOval/getPill/
  getTriangle/getDiamond/getClamShell/getPentagon/getGem/getSunny/getVerySunny/
  getCookie4Sided/6/7/9/12Sided/getGhostish/getClover4Leaf/getClover8Leaf/
  getBurst/getSoftBurst/getBoom/getSoftBoom/...   -> androidx.graphics.shapes.RoundedPolygon
```

These match the androidx signatures the app is written against. The
`-1YwxWKA` style suffixes are Kotlin inline-class mangling for `Dp`/`Color`
parameters, not a different API.

### Proof by compilation, both directions

A `commonMain` file using `MaterialExpressiveTheme`, `MotionScheme.expressive()`,
`MaterialTheme.motionScheme.slowSpatialSpec()`, `LinearWavyProgressIndicator`,
`CircularWavyProgressIndicator`, `ButtonGroup { clickableItem(...) }`,
`FloatingActionButtonMenu { FloatingActionButtonMenuItem(...) }` and
`MaterialShapes.Cookie9Sided.toShape()`.

**With `implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")`
(CMP core still 1.12.0):**

```
> Task :shared:checkJvmMainComposeLibrariesCompatibility
> Task :shared:compileKotlinJvm
BUILD SUCCESSFUL in 37s
```

**Negative control, the identical source with `implementation(compose.material3)`
(= 1.9.0):**

```
e: Expressive.kt:4:35 Unresolved reference 'ButtonGroup'.
e: Expressive.kt:5:35 Unresolved reference 'CircularWavyProgressIndicator'.
e: Expressive.kt:6:35 Cannot access 'annotation class ExperimentalMaterial3ExpressiveApi : Annotation': it is internal in file.
e: Expressive.kt:8:35 Unresolved reference 'FloatingActionButtonMenu'.
e: Expressive.kt:10:35 Unresolved reference 'LinearWavyProgressIndicator'.
e: Expressive.kt:11:35 Cannot access 'fun MaterialExpressiveTheme(...)': it is internal in file.
e: Expressive.kt:12:35 Unresolved reference 'MaterialShapes'.
e: Expressive.kt:14:35 Cannot access 'interface MotionScheme : Any': it is internal in file.
e: Expressive.kt:23:37 Cannot access 'fun expressive(): MotionScheme': it is internal in 'androidx.compose.material3.MotionScheme.Companion'.
e: Expressive.kt:26:34 Cannot access 'val motionScheme: MotionScheme': it is internal in 'androidx.compose.material3.MaterialTheme'.
```

The pair is the point: the detector fires on the shape we expect, and it
goes quiet on the pinned version. The presence matrix above is measured,
not inferred.

### Which androidx material3 version each CMP release corresponds to

From the published POMs:

| CMP material3 | androidx marker | compose core it wants |
|---|---|---|
| 1.9.0 | no `material3-lint` constraint; core deps are `*-desktop:1.9.1` | androidx compose **1.9.1** generation, i.e. androidx material3 ~**1.4.x** |
| 1.12.0-alpha03 | constraint `androidx.compose.material3:material3-lint:` **1.5.0-alpha22** | `*-desktop:1.12.0-beta01` |
| 1.13.0-alpha01 | core deps `*:1.13.0-alpha01` | 1.13 line |

So **CMP material3 1.12.0-alpha03 == androidx material3 1.5.0-alpha22**.
The app is pinned to androidx material3 **1.5.0-alpha28**. That is six
alphas of drift in the same 1.5.0 line, not a generation gap. All six
Expressive APIs the app cares about are already public at alpha22.

Bonus, answers an open item in PORTING_NOTES §E: material3
1.12.0-alpha03 declares `graphics-shapes-desktop:1.1.0` as a dependency,
so `androidx.graphics:graphics-shapes` **does** have a desktop/KMP
variant and does not need reimplementing. (The repo pins `1.0.1`; the
desktop variant resolves at `1.1.0`.)

### Blast radius in the actual repo

Read-only grep over `app/src`. 69 files contain
`androidx.compose.runtime.Composable`. Only **3** touch an Expressive API:

| File | Usage |
|---|---|
| `app/src/main/kotlin/org/koitharu/kotatsu/settings/compose/SettingsTheme.kt` | `import MaterialExpressiveTheme` (L5), `import MotionScheme` (L6), `MaterialExpressiveTheme(...)` (L165), `motionScheme = MotionScheme.expressive()` (L167) |
| `app/src/main/kotlin/org/koitharu/kotatsu/details/ui/ProgressComponents.kt` | `import LinearWavyProgressIndicator` (L11), `MaterialTheme.motionScheme.slowSpatialSpec()` (L81), `LinearWavyProgressIndicator(...)` (L84) |
| `app/src/main/kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt` | comment only (L79), references the app-wide MotionScheme; no Expressive symbol |

Not used anywhere in the app: `ButtonGroup` (0 files),
`CircularWavyProgressIndicator` (0), `FloatingActionButtonMenu` (0),
`ToggleFloatingActionButton` (0). `MaterialShapes` appears in exactly one
place and it is a **comment** in
`settings/about/AboutSettingsFragment.kt:458` explaining that the shapes
there were hand-rolled *because* the Compose material3 version in use
predates `MaterialShapes`. No code depends on it.

`ExperimentalMaterial3ExpressiveApi` appears in **0** source files; the
opt-in is global, from `app/build.gradle:153`:

```groovy
// The Material 3 Expressive theme + motion scheme are referenced from every Compose
'-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi',
```

**So the answer to D6's second clause is yes with a pin.** Pinning
`org.jetbrains.compose.material3:material3:1.12.0-alpha03` makes all
Expressive APIs available and the ~69 Compose files stay reusable. Even
in the worst case where you refuse to ship an alpha and stay on CMP
material3 1.9.0, the damage is one theme wrapper
(`MaterialExpressiveTheme` -> `MaterialTheme`, drop the `motionScheme`
argument) and one progress bar (`LinearWavyProgressIndicator` ->
`LinearProgressIndicator`). That is two files, not a theme rewrite.
PORTING_NOTES §G item 1 overstates the risk.

**The cost that is real:** `material3:1.12.0-alpha03` is an alpha, and
CMP has shipped no stable material3 since 1.9.0. That is a supply
decision, not a technical blocker, and it needs a line in DECISIONS.md.

---

## TASK 3 - Module layout

**Verdict: `:app` (Groovy) + `:shared` (KMP, Kotlin DSL) + `:desktop`
(Kotlin DSL) works, built and verified. The project does NOT need to move
to Kotlin DSL. But AGP 9 changes how a KMP module is declared, and that
is not optional.**

### Groovy and Kotlin DSL coexist in one build - proven

The verification project used `settings.gradle` (Groovy), root
`build.gradle` (Groovy), `app/build.gradle` (Groovy),
`shared/build.gradle.kts` (Kotlin) and `desktop/build.gradle.kts`
(Kotlin), all in one build, and `:app:assembleDebug` passed. Gradle picks
the DSL per build file by extension; there is no project-wide setting.

So: **leave `settings.gradle`, `build.gradle` and `app/build.gradle` in
Groovy exactly as they are.** Write the two new modules in Kotlin DSL,
which is what every CMP and KMP example uses and what the
`compose.desktop { }` and `kotlin { }` blocks are ergonomic in.

### The AGP 9 change that forces the shape of `:shared`

Covered in TASK 1: since AGP 9.0, `com.android.library` **cannot** be
applied together with `org.jetbrains.kotlin.multiplatform`. The KMP
module must use **`com.android.kotlin.multiplatform.library`**, which is
a different plugin with a different DSL:

| pre-AGP-9 KMP | AGP 9.2.1 KMP |
|---|---|
| `plugins { id("com.android.library") }` | `plugins { id("com.android.kotlin.multiplatform.library") }` |
| `kotlin { androidTarget() }` | the plugin creates the android target; no `androidTarget()` call |
| top-level `android { namespace = ...; compileSdk = ... }` | `kotlin { android { namespace = ...; compileSdk = ...; minSdk = ... } }` |
| `defaultConfig { minSdk = 26 }` | `minSdk` is a direct property of the `android` block |
| compilation name `debug` / `release` | single `main` compilation, task `compileAndroidMain` |
| KSP configs `kspAndroid`, task `kspDebugKotlinAndroid` | KSP configs `kspAndroid` / `kspJvm`, tasks `kspAndroidMain` / `kspKotlinJvm` |

There is a documented escape hatch in the error message
(`android.builtInKotlin=false` and `android.newDsl=false`) but it is
described as temporary and it is a global property, so it would change
`:app`'s build too. Do not use it.

**Second-order detail found by reading the build warnings:** the block is
spelled `androidLibrary { }` in most current docs, and with AGP 9.2.1 +
KGP 2.3.21 that spelling is **already deprecated**:

```
w: shared/build.gradle.kts:9:5: 'fun KotlinMultiplatformExtension.androidLibrary(...)' is deprecated.
   The 'androidLibrary' block is deprecated. Please use 'android' instead.
```

Use `kotlin { android { } }`. Verified: with `android { }` the build is
warning-free and `:app:assembleDebug` still passes.

Also deprecated under Gradle 9.6, hit while writing the source sets:
`val commonMain by getting` . Use `sourceSets { getByName("commonMain").dependencies { } }`.

### `settings.gradle` - the whole change

```groovy
include ':app'
include ':shared'
include ':desktop'
rootProject.name = "DropSauce"
```

`pluginManagement` and `dependencyResolutionManagement` need no change.
The existing `google()` content filter
(`includeGroupByRegex("com\\.android.*" / "com\\.google.*" / "androidx.*")`)
already covers `com.android.kotlin.multiplatform.library`, and
`org.jetbrains.compose` comes from `gradlePluginPortal()` /
`mavenCentral()`, both already listed. **No repository changes required.**
`RepositoriesMode.FAIL_ON_PROJECT_REPOS` stays valid; none of the new
modules declares its own repositories.

### `shared/build.gradle.kts` - the verified form

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // add as needed: kotlin.plugin.compose, org.jetbrains.compose,
    //                com.google.devtools.ksp, androidx.room,
    //                org.jetbrains.kotlin.plugin.serialization
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "org.koitharu.kotatsu.shared"
        compileSdk = 37
        minSdk = 26
        // MUST match :app, which is JvmTarget.JVM_11
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
            }
        }
    }

    jvm()

    sourceSets {
        getByName("commonMain").dependencies { /* ... */ }
        getByName("jvmMain").dependencies { /* ... */ }
        getByName("androidMain").dependencies { /* ... */ }
    }
}
```

**The JVM-target pin is load-bearing and is a real trap.** `:app` sets
`jvmTarget = JvmTarget.JVM_11`. Without the pin above, `:shared`'s
*android* compilation follows `jvmToolchain(21)` and emits Java 21
bytecode:

```
$ javap -v -cp shared/build/classes/kotlin/android/main shared.SharedThing | grep major
  major version: 65        # Java 21, unpinned
```

With the pin:

```
  major version: 55        # Java 11, android target  - matches :app
  major version: 65        # Java 21, jvm target      - desktop, correct
```

In the probe, `:app:assembleDebug` happened to survive the unpinned case
(D8 dexed the 65s without complaint), so this will **not** announce
itself as a build failure. It is a silent divergence that only bites
later. Pin it from the first commit.

### `desktop/build.gradle.kts` - the verified form

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.google.devtools.ksp")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("com.google.dagger:dagger:2.59.2")
    ksp("com.google.dagger:dagger-compiler:2.59.2")
}

compose.desktop {
    application {
        mainClass = "..."
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "dropsauce"
            packageVersion = "1.0.0"
            linux { debMaintainer = "..." }
        }
    }
}
```

`:desktop` is a plain `kotlin("jvm")` module, not KMP. That is simpler
and it consumes the `jvm()` variant of `:shared` correctly.

### `:app` consumes `:shared` - proven, including Hilt across the boundary

`app/build.gradle` (Groovy) gained exactly one line:

```groovy
implementation project(':shared')
```

`:shared/commonMain` declares:

```kotlin
@Singleton
class SharedThing @Inject constructor() {
    fun describe(): String = "shared thing from :shared"
}
```

`:app` has `@HiltAndroidApp class DemoApp : Application()` and
`@AndroidEntryPoint class MainActivity : ComponentActivity()` with
`@Inject lateinit var thing: SharedThing`.

```
> Task :shared:compileAndroidMain
> Task :shared:bundleAndroidMainClassesToCompileJar
> Task :app:kspDebugKotlin
> Task :app:hiltAggregateDepsDebug
> Task :app:hiltJavaCompileDebug
> Task :app:packageDebug
> Task :app:assembleDebug
BUILD SUCCESSFUL in 35s
```

Hilt really generated the binding, it is not just that the build was
quiet. `app/build/generated/ksp/debug/java/demo/MainActivity_MembersInjector.java`:

```java
import shared.SharedThing;
  private final Provider<SharedThing> thingProvider;
  public static void injectThing(MainActivity instance, SharedThing thing) { ... }
```

And the class is genuinely in the shipped APK, not just on a classpath:

```
$ dexdump app/build/outputs/apk/debug/app-debug.apk | grep Lshared/SharedThing
  Class descriptor  : 'Lshared/SharedThing;'
```

So D7's premise holds in both directions: `:shared` carries plain
`javax.inject` annotations, Hilt in `:app` consumes them, and Dagger in
`:desktop` consumes the same annotations (see below). `:shared` needs
`api("javax.inject:javax.inject:1")`; it must **not** depend on Hilt.

### `:desktop` consumes `:shared` with plain Dagger 2 + KSP - proven (D7)

```kotlin
@Singleton @Component
interface DesktopComponent { fun sharedThing(): SharedThing }
```

```
> Task :desktop:kspKotlin
> Task :desktop:compileKotlin
BUILD SUCCESSFUL in 15s
$ find desktop/build/generated/ksp -name 'DaggerDesktopComponent*'
desktop/build/generated/ksp/main/java/desktop/DaggerDesktopComponent.java
```

`DaggerDesktopComponent.create().sharedThing()` compiles and is called
from the desktop `main()`. **D7 is proven, not assumed.**

### Migration-order consequence for D5

D5 step 3 moves the Room layer into `:shared` early. Note that Room KMP
forces an API change that Android-only Room does not have: the
`@ConstructedBy(XConstructor::class)` annotation plus an
`expect object ... : RoomDatabaseConstructor<X>` is **mandatory** in
common code. `MangaDatabase` therefore cannot move to `:shared`
unmodified; it needs the constructor object and an `actual` per target.
That is mechanical, but it is a change to a file `:app` depends on, so it
lands in the same commit as the move.

---

## TASK 4 - Configuration cache and lint

### Configuration cache: no problems from any of it

`org.gradle.configuration-cache=true` and `org.gradle.parallel=true` were
set in **both** probe projects, matching the repo. Results:

Gate project (KMP + CMP + Room + KSP, jvm and android targets):

```
run 1: BUILD SUCCESSFUL in 18s   Configuration cache entry stored.
run 2: Reusing configuration cache.
       BUILD SUCCESSFUL in 2s    Configuration cache entry reused.
```

Layout project (`:app` Android + Hilt + KSP, `:shared` KMP, `:desktop` CMP):

```
run 1: BUILD SUCCESSFUL in 35s   Configuration cache entry stored.
run 2: Reusing configuration cache.
       BUILD SUCCESSFUL in 4s    Configuration cache entry reused.
```

Zero configuration-cache problems were reported in any run, including the
`:desktop:packageDeb` run. So:

- **the CMP Gradle plugin 1.12.0 is configuration-cache compatible.** Its
  tasks (`createRuntimeImage`, `packageDeb`, `run`, `checkRuntime`)
  stored and reused a cache entry.
- **KSP 2.3.6 and the Room 2.8.4 Gradle plugin are configuration-cache
  compatible.** `kspKotlinJvm`, `kspAndroidMain` and `copyRoomSchemas` all
  ran under a stored entry.

PORTING_NOTES §G item 5 ("a badly-written desktop module will break
configuration cache for the Android build too") is **true as a general
warning but no longer a specific risk from these plugins**. The
remaining ways to break it are the ordinary ones, and they are worth
writing into the module conventions now:

1. Reading `System.getenv` / `System.getProperty` / `project.property` at
   **configuration** time instead of wrapping in a `Provider`.
2. Referencing `project` inside a task action (`doLast { project.… }`).
   This is the one most likely to appear in a hand-rolled desktop
   packaging or version-stamping task.
3. `Task.project`, `Gradle` or `Configuration` instances captured in task
   fields.
4. Resolving a configuration at configuration time (my own
   dependency-sweep task had to declare
   `notCompatibleWithConfigurationCache(...)` for exactly this reason).

The repo already sets `org.gradle.configuration-cache.max-problems=8`,
which means up to 8 problems are tolerated before failing. Consider
dropping that to 0 once `:shared` and `:desktop` land, so a regression is
loud.

### Lint: `:app` does NOT analyse `:shared`, and that cuts both ways

`android { lint { abortOnError = true; warningsAsErrors = true } }` is on
in `:app`. The question is whether adding `:shared` widens what can fail
the gate.

Tested with a deliberate violation planted in `:shared/src/androidMain`:

```kotlin
import android.graphics.text.LineBreaker   // API 29; module minSdk is 26
object LintBait { fun strategy(): Int = LineBreaker.BREAK_STRATEGY_BALANCED }
```

Confirmed the bait actually compiled into the android artifact
(`shared/build/classes/kotlin/android/main/shared/LintBait.class` exists),
then:

```
$ ./gradlew :app:lintDebug
BUILD SUCCESSFUL in 16s
```

**Positive control**, the byte-identical code moved into `:app`:

```
$ ./gradlew :app:lintDebug
app/src/main/kotlin/demo/LintBaitApp.kt:8: Error: Field requires API level 29
  (current min is 26): android.graphics.text.LineBreaker#BREAK_STRATEGY_BALANCED [InlinedApi]
BUILD FAILED
```

The detector fires on the shape we expect and stays silent on the real
one, so this is measured: **`:app:lintDebug` does not analyse `:shared`
sources.** `checkDependencies` defaults to false.

Two consequences, and the second is the one that matters:

1. **PORTING_NOTES §G item 6 is refuted as stated.** Moving code into
   `:shared` does not create new ways for `:app`'s lint to fail the gate.
   If anything it *reduces* lint surface, because every file that leaves
   `app/src/main/kotlin` leaves `:app`'s lint scope.
2. **The real risk is the inverse: code moved to `:shared` becomes
   unlinted.** The KMP android library plugin registers no runnable lint
   task at all. Full task list for `:shared`:
   `androidCompileLintChecks`, `compileLint`,
   `bundleAndroidMainLocalLintAar`, `prepareLintJarForPublish` - all of
   these are for *publishing custom lint checks*, none of them runs lint
   on the module. So D4's "gradually thinned `:app`" quietly means
   "gradually less linted codebase".

   Mitigation, pick one: set `lint { checkDependencies = true }` in
   `:app` so its lint run walks into `:shared` (this *does* re-expose the
   gate, so do it deliberately and expect a first-run cleanup), or accept
   that `:shared` is lint-free and lean on the compiler plus the existing
   `disable` list. The repo's `disable` list is 33 entries long already;
   `checkDependencies = true` will likely add to it.

Unrelated but caught by the same run, worth knowing before you enable
anything: `warningsAsErrors = true` turns the **`AndroidGradlePluginVersion`**
check into a build failure whenever a newer Gradle exists
("A newer version of Gradle than 9.6.1 is available: 9.7.1"). The repo
already disables `NewerVersionAvailable` and `GradleDependency` but
**not** `AndroidGradlePluginVersion`, so this can break the gate on a day
nobody touched the code. Worth adding to the disable list.

---

## TASK 5 - Packaging

**Verdict: `packageDeb` works on this machine, produces a real installable
`.deb` with a fully bundled JRE, and needs no tool that is not already
installed.**

### Real run

```
$ ./gradlew :desktop:packageDeb
> Task :desktop:checkRuntime
> Task :desktop:createRuntimeImage
> Task :desktop:packageDeb
The distribution is written to .../desktop/build/compose/binaries/main/deb/dropsauce-probe_1.0.0_amd64.deb
BUILD SUCCESSFUL in 42s
11 actionable tasks: 8 executed, 3 up-to-date
Configuration cache entry stored.
```

| | |
|---|---|
| Task | `:desktop:packageDeb` (release variant: `:desktop:packageReleaseDeb`) |
| Output path | `desktop/build/compose/binaries/main/deb/<packageName>_<packageVersion>_amd64.deb` |
| Artifact size | **53,544,184 bytes (51 MiB)** |
| Installed-Size | **140,670 KiB (137 MiB)** |
| Install prefix | `/opt/<packageName>/` |

That size is for a hello-world window. The real app adds the parsers jar
(~13 MiB resolved), Coil, OkHttp and the bundled SQLite native (~5 MiB),
so budget roughly **120-150 MiB installed** for v1. GraalJS would have
added another ~60-67 MiB, but D17 strikes it from v1; if D2 brings it
back in v1.1 the installed size goes back up to roughly 200 MiB.

### The bundled runtime is really there

Not inferred from the size:

```
$ dpkg-deb -c dropsauce-probe_1.0.0_amd64.deb | grep -c 'lib/runtime/'
106
$ dpkg-deb -c ... | grep 'lib/runtime/lib/modules'
-rw-r--r-- root/root  55272545 ./opt/dropsauce-probe/lib/runtime/lib/modules
```

106 files under `lib/runtime/`, including a 55 MB `lib/modules` (the
jlink'd module image). jpackage ran `jlink` against the JDK 21 that built
it, so **the `.deb` does not depend on a system JVM**. Confirmed by the
generated `Depends:` line, which lists only C libraries:

```
Depends: libasound2t64, libatomic1, libbrotli1, libbz2-1.0, libc6, libexpat1,
 libfontconfig1, libfreetype6, libgcc-s1, libgif7, libgl1, libglib2.0-0t64,
 libglvnd0, libglx0, libgraphite2-3, libharfbuzz0b, libjpeg62-turbo, liblcms2-2,
 libpcre2-8-0, libpng16-16t64, libstdc++6, libx11-6, libxau6, libxcb1, libxdmcp6,
 libxext6, libxi6, libxrender1, libxtst6, xdg-utils, zlib1g
```

No `default-jre`, no `openjdk-*`. It also ships `preinst` / `postinst` /
`prerm` / `postrm` maintainer scripts, so it is a well-formed package.

Application jars land in `/opt/<packageName>/lib/app/`, content-hashed,
e.g. `foundation-desktop-1.12.0-ba5937e2….jar`,
`graphics-shapes-desktop-1.1.0-….jar`, `kotlin-stdlib-2.3.21-….jar`,
`skiko-awt-runtime-linux-x64-….jar`.

### External tools jpackage needs, and what is here

| Tool | Needed for | This machine |
|---|---|---|
| `dpkg-deb` | building the `.deb` | **present**, `/usr/bin/dpkg-deb`, Debian 1.22.22 (amd64) |
| `fakeroot` | root-owned files without root | **present**, `/usr/bin/fakeroot` 1.37.1.1 |
| `jpackage` | the whole thing | **present**, `/usr/bin/jpackage` (and in the JDK 21 image) |
| `objcopy` (binutils) | app-image launcher work | **present** |
| `rpmbuild` | only for `TargetFormat.Rpm` | **missing** - irrelevant, not in scope |
| `appimagetool` | AppImage | **missing** |

Nothing needs installing for D12.

### Running it

Both work, verified on this machine:

| Invocation | What it does |
|---|---|
| `./gradlew :desktop:run` | runs `mainClass` straight off the Gradle compile classpath, no packaging. The normal dev loop. |
| `./gradlew :desktop:runDistributable` | runs the jlink'd app image from `build/compose/binaries/main/app/` - use this to reproduce a packaging problem. |

Other registered tasks on `:desktop`: `createDistributable`,
`createReleaseDistributable`, `createRuntimeImage`, `package`,
`packageDistributionForCurrentOS`, `packageUberJarForCurrentOS`,
`packageReleaseDeb`, `runRelease`, `runReleaseDistributable`,
`suggestRuntimeModules`, and `runHot` (deprecated, use `hotRun`).

`:desktop:run` was actually executed, not just configured. It launched a
Compose window using `MaterialExpressiveTheme` + `MotionScheme.expressive()`
+ `SharedThing` from `:shared`, and stayed alive until killed at 90s with
no exception on stdout or stderr:

```
> Task :desktop:run
exit=124   (timeout killed it; the process was still running)
```

This machine is `XDG_SESSION_TYPE=wayland` with `DISPLAY=:1`, so that run
went through **XWayland**. **D13 is confirmed empirically**, not just
architecturally: CMP renders into an AWT/Skiko window and it works on
this Wayland session via XWayland.

### AppImage, if it is ever wanted

Not in v1 per D12, and the finding supports that. jpackage has no
AppImage target at all, so it is not a `targetFormats(...)` entry. It
would need: `appimagetool` installed (**absent here**), a hand-written
`AppDir` layout, a `.desktop` file and an icon, and a custom Gradle task
that post-processes the output of `createDistributable`. That is a real
chunk of work for no functional gain on a Debian target. **D12 holds.**

---

## TASK 6 - Dependency resolution sweep

Every coordinate below was resolved on a **real JVM (`kotlin("jvm")`,
toolchain 21) configuration** with `google()`, `mavenCentral()` and
`jitpack.io`, and the artifacts were downloaded. "files" is the resolved
transitive file count.

### Resolves - use these exact coordinates

| Coordinate | Result |
|---|---|
| `com.github.YakaTeam:kotatsu-parsers:21d4b79b5f` | **OK** - 10 files, 12,968 KiB |
| `io.github.pdvrieze.xmlutil:core:0.91.3` | **OK** - 4 files, 2,650 KiB |
| `io.github.pdvrieze.xmlutil:core-jvmcommon:0.91.3` | **OK** - 4 files, 2,650 KiB |
| `io.github.pdvrieze.xmlutil:serialization:0.91.3` | **OK** - 5 files, 3,227 KiB |
| `io.coil-kt.coil3:coil-core:3.4.0` | **OK** - 7 files, 5,219 KiB |
| `io.coil-kt.coil3:coil-compose:3.4.0` | **OK** - 43 files, 18,746 KiB |
| `io.coil-kt.coil3:coil-network-okhttp:3.4.0` | **OK** - 12 files, 6,088 KiB |
| `io.coil-kt.coil3:coil-gif:3.4.0` | **OK** - 29 files, 7,947 KiB |
| `io.coil-kt.coil3:coil-svg:3.4.0` | **OK** - 8 files, 5,244 KiB |
| `androidx.lifecycle:lifecycle-viewmodel:2.10.0` | **OK** - 5 files, 3,279 KiB |
| `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0` | **OK** - 28 files, 6,905 KiB |
| `androidx.collection:collection-jvm:1.6.0` | **OK** - 5 files, 2,656 KiB |
| `org.graalvm.polyglot:js:25.3.4.1` | **OK** - 13 files, 68,273 KiB |
| `org.graalvm.polyglot:js:24.1.1` | **OK** - 15 files, 61,919 KiB |
| `com.google.dagger:dagger:2.59.2` | **OK** - 4 files, 70 KiB |
| `com.google.dagger:dagger-compiler:2.59.2` | **OK** - 24 files, 12,700 KiB |
| `org.json:json:20260814` (latest) | **OK** - 1 file, 87 KiB |
| `com.squareup.okhttp3:okhttp:5.3.2` | **OK** - 4 files |
| `com.squareup.okhttp3:okhttp-brotli:5.3.2` | **OK** - 6 files |
| `com.squareup.okhttp3:okhttp-zstd:5.3.2` | **OK** - 7 files |
| `com.squareup.okhttp3:okhttp-tls:5.3.2` | **OK** - 5 files |
| `com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.2` | **OK** - 5 files |
| `com.squareup.okio:okio:3.17.0` | **OK** - 3 files |
| `org.jsoup:jsoup:1.22.1` | **OK** - 1 file, 496 KiB |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0` | **OK** - 3 files |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0` | **OK** - 4 files |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.11.0` | **OK** - 6 files |
| `org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0` | **OK** - 4 files |
| `androidx.room:room-runtime:2.8.4` | **OK** - 8 files, 4,516 KiB |
| `androidx.room:room-compiler:2.8.4` | **OK** - 28 files, 28,107 KiB |
| `androidx.sqlite:sqlite-bundled:2.7.1` (latest stable) | **OK** - 5 files, 5,466 KiB |
| `androidx.sqlite:sqlite-bundled:2.8.0-alpha01` | **OK** (used in the gate build) |
| `org.jetbrains.compose.material3:material3:1.12.0-alpha03` | **OK** - 45 files, 25,252 KiB |
| `com.github.solkin:disk-lru-cache:1.5` | **OK** - 1 file, 11 KiB (pure JVM, keeps) |
| `com.google.guava:guava:33.5.0-jre` | **OK** - 6 files, 2,999 KiB |
| `org.slf4j:slf4j-simple:2.0.17` | **OK** - 2 files, 83 KiB |

### Does NOT resolve - PORTING_NOTES §E is wrong here

| Coordinate | Error |
|---|---|
| `io.github.pdvrieze.xmlutil:core-jvm:0.91.3` | `Could not resolve all files for configuration ':probe_xmlutil-core-jvm'.` -> `io.github.pdvrieze.xmlutil:core-jvm:0.91.3 FAILED` |

`core-jvm-0.91.3.pom` returns **HTTP 404** on Maven Central. The artifact
does not exist. What exists at 0.91.3: `core` (200), `core-android` (200),
`core-jvmcommon` (200).

**PORTING_NOTES §E says "swap `core-android` to `core-jvm`". That is not a
valid coordinate.** The correct swap is to the root KMP module
**`io.github.pdvrieze.xmlutil:core:0.91.3`**, and Gradle module metadata
selects the JVM variant. (`xmlutil:serialization-jvm`'s own POM depends on
`core-jvmcommon`, which is the platform artifact underneath.) Fix this
line in PORTING_NOTES before anyone writes it into a build file.

### `kotatsu-parsers` and `org.json` - confirmed, and better than expected

Resolved tree on a plain JVM configuration:

```
com.github.YakaTeam:kotatsu-parsers:21d4b79b5f
+--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
+--- com.squareup.okhttp3:okhttp:5.3.2
+--- com.squareup.okio:okio:3.16.4
+--- org.json:json:20251224
+--- androidx.collection:collection:1.5.0 -> collection-jvm:1.5.0
+--- org.jsoup:jsoup:1.22.1
\--- org.jetbrains.kotlin:kotlin-stdlib:2.2.10 -> 2.2.21
```

Three things confirmed:

1. It resolves on a plain JVM configuration with no Android anything.
2. **`org.json:json:20251224` comes in transitively.** So PORTING_NOTES §E
   is right that the `exclude group: 'org.json'` must not be re-applied,
   and it goes one better: **desktop does not need to declare `org.json`
   at all**, it arrives with the parsers. D16 lists `org.json:json` as a
   dependency "to add" - it is really "a dependency to stop excluding".
   Declare it explicitly only if you want to pin a version.
3. `androidx.collection` also arrives transitively as `collection-jvm`,
   confirming that §E row.

Note the version skew inside the parsers jar's own deps
(`coroutines 1.10.2` vs the app's `1.11.0`, `okio 3.16.4` vs `3.17.0`).
Gradle upgrades to the higher, which is what the app already does today.

### GraalJS - struck from v1 by D17; data retained for the v1.1 question

`org.graalvm.polyglot:js` latest on Maven Central is **25.3.4.1**
(metadata lastUpdated 20260915115044). Beyond resolution, I executed it:

```java
Context c = Context.newBuilder("js").allowAllAccess(true).build();
Value v = c.eval("js", "var x = [1,2,3].map(n=>n*2).join('-'); x");
```

```
GRAALJS_OK result=2-4-6
engine=Interpreted version=25.3.4.1
```

It works. But note `engine=Interpreted`, and the warning it printed:

```
[engine] WARNING: The polyglot engine uses a fallback runtime that does not
support runtime compilation to native code.
Execution without runtime compilation will negatively impact the guest
application performance.
The following cause was found: Version check failed.
Your Java runtime '21.0.12.1+1-1-deb13u1-Debian' is incompatible with
optimized Truffle runtime version '25.3.4.1'.
The Java runtime version must be greater or equal to JDK '25' and smaller than JDK '26'.
```

`24.1.1` on the same JDK 21 also fell back (`engine=Interpreted`).

**Status: superseded for v1.** DECISIONS.md **D17** (Agent B, landed while
this sweep was running) strikes GraalJS from v1 entirely, on stronger
grounds than performance: all 5 `evaluateJs` call sites use the
2-argument `evaluateJs(url, script)` overload and execute inside a loaded
page's DOM (`window.localStorage`, `window.location`), which a bare JS
engine cannot provide at any speed. Desktop throws a typed
`UnsupportedOperationException` instead. I am not re-litigating that; the
resolution and runtime numbers above stand as measured and are kept only
because D17 says GraalJS "returns as a dependency question when D2
(LNReader plugins) is picked up in v1.1". For that question the relevant
finding is the JDK coupling: on stock OpenJDK 21 both 24.1.1 and 25.3.4.1
fall back to `Interpreted`, so getting the optimizing runtime would mean
bundling a JDK matching the polyglot major version, or adding the
`truffle-runtime` artifacts, or accepting interpreted JS. That is a
build/toolchain constraint on jpackage, which is why it is recorded here
rather than in the parser-host notes.

---

## TASK 7 - Verdict

### Top 5 build-system risks, ranked

**1. CMP has shipped no stable material3 since 1.9.0, and 1.9.0 has no
Expressive API.**
The whole Expressive story depends on pinning
`org.jetbrains.compose.material3:material3:1.12.0-alpha03`, an alpha, while
the rest of CMP is stable 1.12.0. You are one JetBrains release-timing
decision away from being stuck on an alpha for the life of v1.
*Mitigation:* pin the version explicitly in `libs.versions.toml` as its own
entry (never rely on the `compose.material3` accessor, which silently means
1.9.0), record it in DECISIONS.md D16 as a knowing alpha dependency, and
keep the fallback documented: dropping to material3 1.9.0 costs exactly two
files (`SettingsTheme.kt`, `ProgressComponents.kt`). Re-check for a stable
material3 at each CMP release before v1 ships.

**2. AGP 9 forbids the KMP module shape every guide describes.**
`com.android.library` + KMP is a hard error since AGP 9.0, and the
replacement plugin's documented `androidLibrary { }` block is *already*
deprecated in favour of `android { }` at AGP 9.2.1 + KGP 2.3.21. This is
churning fast and there is little written about it.
*Mitigation:* use `com.android.kotlin.multiplatform.library` with
`kotlin { android { } }` from the first commit (verified warning-free
here). Do not use the `android.builtInKotlin=false` /
`android.newDsl=false` escape hatch: those are global properties and
would change `:app`'s compilation too. Treat AGP upgrades as a
`:shared`-affecting event, not a routine bump.

**3. Silent JVM-target divergence between `:app` (11) and `:shared` (21).**
Unpinned, `:shared`'s android compilation emits Java 21 bytecode
(`major version: 65`) while `:app` targets 11, and
**`:app:assembleDebug` still passes**, so the gate does not catch it.
*Mitigation:* pin `jvmTarget = JVM_11` on the android compilation of
`:shared` (verified: gives `major version: 55`) and leave the `jvm()`
target at 21. Add a one-line `javap | grep major` assertion to CI if you
want it enforced rather than remembered.

**4. Code moved into `:shared` becomes unlinted, and nobody will notice.**
`:app:lintDebug` does not analyse `:shared` (proven with a positive
control), and the KMP android library plugin registers no runnable lint
task at all. D4's plan is to progressively move code out of `:app`, so
lint coverage shrinks monotonically as the port proceeds.
*Mitigation:* decide now, explicitly. Either set
`lint { checkDependencies = true }` in `:app` and pay a one-time cleanup,
or record in DECISIONS.md that `:shared` is deliberately unlinted. Also
add `AndroidGradlePluginVersion` to the existing `disable` list: with
`warningsAsErrors = true` it fails the gate whenever a newer Gradle is
released, which already happened during this probe (9.7.1 is out).

**5. Desktop artifact size, and the JDK coupling that returns in v1.1.**
A hello-world `.deb` is already 51 MiB / 137 MiB installed, before the
parsers jar (~13 MiB), Coil, OkHttp and the bundled SQLite native.
*Mitigation:* budget ~120-150 MiB installed for v1 and put the number in
the README rather than discovering it at release. D17 struck GraalJS from
v1, which removes ~60-67 MiB and, more importantly, removes the JDK
coupling for now. It comes back with D2 in v1.1: polyglot 25.x needs a
JDK 25 runtime to JIT, so if LNReader plugins are taken, the version of
the JDK that jpackage bundles stops being free and becomes a decision.
Measured evidence for that future call is in TASK 6.

### Does D6 hold?

**Yes, both clauses, with one amendment.**

- *"Kotlin 2.3.21 works with the CMP Gradle plugin"* - **proven.** Kotlin
  2.3.21 + `kotlin.plugin.compose` 2.3.21 + CMP 1.12.0 + Room 2.8.4 KMP +
  KSP 2.3.6 + AGP 9.2.1 compiled together for `jvm()` and android in one
  build. No Kotlin version move is needed and the Android gate is not at
  risk from this.
- *"CMP's material3 exposes `MotionScheme`, `MaterialShapes`, `ButtonGroup`,
  wavy progress and the FAB menu"* - **yes, but only at
  `material3:1.12.0-alpha03`, which you must pin by hand.** At the version
  CMP 1.12.0 gives you by default (1.9.0), `MaterialShapes` / `ButtonGroup`
  / wavy / FAB menu are absent outright and `MotionScheme` /
  `MaterialExpressiveTheme` exist but are Kotlin-`internal`.

**The amendment D6 needs:** it is written as if CMP's material3 version
tracks CMP's version. It does not. That decoupling is the actual finding
and it should be stated in DECISIONS.md, because someone who writes
`implementation(compose.material3)` will silently get 1.9.0 and conclude
the Expressive APIs are missing from CMP.

The real blocker D6 did not anticipate was **AGP 9's KMP plugin split**,
which is a bigger structural constraint than the Kotlin/CMP question it
was written to answer.

### Does the v1 UI scope need to change?

**No.**

D6 said: *"If the second is false, the 'portable' Compose screens
(`settings/compose`, `details/ui`, `stats/ui`) need their theme rewritten
and their reuse value drops sharply."* The second is **true** (with the
pin), so that consequence does not trigger. The ~69 Compose files keep
their reuse value and the v1 scope in DECISIONS.md §2 stands as written.

Even the downside case is small, and this is the part worth internalising
because PORTING_NOTES §G overstates it. §G item 1 says "the Android app
opts into `ExperimentalMaterial3ExpressiveApi` globally and uses it in the
theme itself. If CMP's material3 lacks those APIs, every 'portable'
Compose screen needs its theme rewritten." Measured against the actual
tree, that is not so:

- `ExperimentalMaterial3ExpressiveApi` appears in **0** source files. The
  opt-in is a single global compiler flag (`app/build.gradle:153`), not a
  per-file annotation, so removing it touches one line.
- Only **3 of 69** Compose files reference an Expressive symbol, and one
  of those three (`FloatingNavBar.kt:79`) is a **comment**.
- `MaterialShapes`'s only occurrence in the repo is a comment at
  `AboutSettingsFragment.kt:458` saying the shapes there were hand-rolled
  *because* the Compose material3 in use predates it. No code depends on it.
- `ButtonGroup`, `CircularWavyProgressIndicator`, `FloatingActionButtonMenu`
  and `ToggleFloatingActionButton` are used **nowhere**.

So the worst case is two files:
`SettingsTheme.kt` (`MaterialExpressiveTheme` -> `MaterialTheme`, drop the
`motionScheme` argument) and `ProgressComponents.kt`
(`LinearWavyProgressIndicator` -> `LinearProgressIndicator`, drop the
`MaterialTheme.motionScheme.slowSpatialSpec()` animation spec). That is a
degradation of two visual details, not a theme rewrite, and not a scope
change.

### Recommended additions to DECISIONS.md D16

The table currently lists five coordinates without versions. Verified
values:

| Coordinate | Verified version | Note |
|---|---|---|
| `org.jetbrains.compose` Gradle plugin | **1.12.0** | latest stable, confirmed against Maven Central metadata |
| `org.jetbrains.compose.material3:material3` | **1.12.0-alpha03** | **new row D16 does not have.** Must be pinned separately; the CMP accessor gives 1.9.0 |
| `com.android.kotlin.multiplatform.library` | **9.2.1** | **new row.** Not a "new dependency" in spirit but it is a new plugin in the build |
| `androidx.sqlite:sqlite-bundled` | **2.7.1** stable, or **2.8.0-alpha01** | D16 gives no version; prefer 2.7.1 unless Room needs the 2.8 line |
| ~~`org.graalvm.polyglot:js`~~ | **25.3.4.1** if ever taken | **Struck for v1 by D17**, which landed after my sweep began. Resolution/runtime data in TASK 6 is retained for the v1.1 D2 question only. |
| `org.json:json` | n/a | **arrives transitively with `kotatsu-parsers`.** Reword from "add" to "stop excluding" |
| `com.google.dagger:dagger` + `dagger-compiler` | **2.59.2** | same version already in the catalogue for Hilt |
| `javax.inject:javax.inject` | **1** | **new row.** `:shared` needs it to carry `@Inject` without Hilt |
| `org.slf4j:slf4j-simple` | **2.0.17** | if logging is taken (PORTING_NOTES §F) |

### Corrections owed to the other docs

1. `PORTING_NOTES.md` §E: `xmlutil` swap target `core-jvm` **does not
   exist** (404). Correct coordinate is `io.github.pdvrieze.xmlutil:core`.
2. `PORTING_NOTES.md` §E: `androidx.graphics:graphics-shapes` "check for a
   KMP variant" - **it has one**, `graphics-shapes-desktop:1.1.0`, and it
   arrives transitively with material3 1.12.0-alpha03.
3. `PORTING_NOTES.md` §E: `org.json` - desktop does not need to add it,
   only to stop excluding it.
4. `PORTING_NOTES.md` §G item 2 (Kotlin vs CMP): **refuted**, no gap.
5. `PORTING_NOTES.md` §G item 5 (config cache): the named plugins are all
   config-cache clean; the risk is generic, not specific.
6. `PORTING_NOTES.md` §G item 6 (lint): **refuted as stated**; the real
   risk is the inverse (`:shared` is unlinted).
7. `PORTING_NOTES.md` §G is missing what turned out to be the largest
   structural constraint: **AGP 9 requires
   `com.android.kotlin.multiplatform.library` for any KMP module.**
