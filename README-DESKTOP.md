# DropSauce Desktop

A native Linux build of DropSauce, written with Compose Multiplatform.
The Android app in `app/` is unchanged and still builds; this is a second
front end over shared logic, not a replacement.

## Requirements

| | |
|---|---|
| JDK | 21 (the build pins `jvmToolchain(21)`) |
| OS | Linux x86_64. Runs under X11, and under Wayland via XWayland |
| Android SDK | only for `:app`. Not needed to build or run the desktop app |

`dpkg-deb` and `fakeroot` are needed for `packageDeb`, and are present on
a normal Debian or Ubuntu install.

## Build, run, package

```bash
./gradlew :desktop:run          # run it
./gradlew :desktop:packageDeb   # installable .deb with a bundled JRE
./gradlew :desktop:test         # desktop tests
./gradlew :shared:jvmTest       # shared logic tests
./gradlew :app:assembleDebug    # the Android app, which must always pass
```

The `.deb` lands at:

```
desktop/build/compose/binaries/main/deb/dropsauce_<version>_amd64.deb
```

It is about 66 MB and installs to `/opt/dropsauce`. It bundles its own
Java runtime: `Depends:` lists only C libraries, no JVM, so it does not
care what Java the host has. Install and remove with:

```bash
sudo dpkg -i desktop/build/compose/binaries/main/deb/dropsauce_*_amd64.deb
sudo dpkg -r dropsauce
```

Removal unregisters the menu entry and deletes `/opt/dropsauce`. Nothing
is written outside that directory and the `.desktop` file.

## Where your data lives

The app follows the XDG Base Directory Specification, honouring
`XDG_DATA_HOME`, `XDG_CONFIG_HOME` and `XDG_CACHE_HOME` when they are set
to absolute paths, and ignoring them otherwise as the specification
requires.

```
~/.local/share/dropsauce/library.db     library, history, bookmarks, downloads
~/.local/share/dropsauce/downloads/     downloaded pages
~/.local/share/dropsauce/library/       imported local comics index
~/.config/dropsauce/settings.json       settings
~/.cache/dropsauce/                     discardable
```

Deleting `library.db` resets the library. Deleting anything under
`~/.cache/dropsauce` while the app is closed is always safe.

The downloads folder can be moved in **Settings, Storage, Downloads
folder**, which is worth doing if your library is large: a dotfile
directory is rarely on the drive with room for it. Changing it copies
nothing. Each download records where its pages went and reads follow
that, so chapters already saved keep working from the old folder and new
ones go to the new one.

## Module layout

```
:app       Android. Groovy build file. Unchanged in kind.
:shared    Kotlin Multiplatform, android + jvm targets.
           Room schema, settings, paths.
:desktop   JVM only. Compose for Desktop, the UI and feature areas.
```

`:shared` applies `com.android.kotlin.multiplatform.library`, not
`com.android.library`: since AGP 9.0 the latter cannot be combined with
`kotlin.multiplatform`. Its android compilation pins `JvmTarget.JVM_11`
to match `:app`; without that pin it silently emits Java 21 bytecode and
`:app:assembleDebug` still passes, which is a divergence that only bites
later.

## Sources

Desktop gets its sources from the `kotatsu-parsers` catalogue, which is a
pure JVM jar already on the classpath. Mihon extension APKs and LNReader
JS plugins, which the Android app supports, are Android-bound and are not
available here. See `DECISIONS.md` D1, D2 and D3 for the reasoning.

The catalogue declares 1270 sources and flags about 380 of them broken,
so the app lists the remainder and filters adult sources by default. Both
are adjustable in Settings.

## Interface size

Compose for Desktop takes its density from the toolkit, which on many
Linux setups reports 1.0 regardless of the display's actual pixel
density, making everything render small. **Settings, Appearance,
Interface size** scales text, icons and spacing together. It defaults to
150 percent; lower it if that is too large.

## Feature areas

Each area under `desktop/.../feature/` is self-contained: it declares a
`Feature` object and the shell reads it to build navigation. An area
never edits the navigation shell, which is what allows several to be
developed at the same time. The contract an area may depend on is
`FeatureContext`, and nothing beyond it.

Adding an area means writing it in its own directory and adding one entry
to `AppState.features`.

## Testing notes

Tests that need the network are gated behind `-Dlive=true` and skipped by
default, so a third-party site being down cannot fail the build:

```bash
./gradlew :desktop:test -Dlive=true
```

## When something goes wrong

The app writes `dropsauce.log` next to its database: `~/.local/share/dropsauce/` on
Linux, `%APPDATA%\DropSauce\` on Windows. It records what the app is running on and any
uncaught exception. A packaged build on Windows has no console at all, so this is the
only place a crash leaves a trace.

The first lines of it answer most questions:

```
version   0.9.10
os        Windows 11 10.0 amd64
maxHeap   1024 MB
renderApi (default)
```

`maxHeap` is the one to look at on a small machine or a virtual one. The JVM takes a
quarter of physical memory, so a 4 GB VM gets about 1 GB, and the image cache sizes
itself from that.

If the window is slow or the app dies while scrolling, the renderer is the next thing to
try. Skia picks one for itself and the choice is not always right on a virtual machine
with no real GPU:

```bash
DropSauce -J-Dskiko.renderApi=SOFTWARE          # or OPENGL, or DIRECT3D on Windows
```

## Known limitations

- Native Wayland is not supported. The app renders into an AWT window, so
  a Wayland session runs it through XWayland.
- Tiled page decoding is not implemented, so very tall webtoon strips
  decode whole. `DECISIONS.md` D10 records the measurements and the
  `javax.imageio` region-decode path that would fix it.
- AVIF pages cannot be decoded; no JVM decoder ships with Skia.
- Nothing bounds how much memory Skia itself uses for the window, only what the image
  cache keeps. A machine short of memory may still struggle with very long strips.
- CBR is not supported, as that needs a RAR decoder.
