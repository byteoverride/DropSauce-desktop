<p align="center">
  <img src="assets/app-icon-dark.svg" alt="DropSauce dark mode app icon" width="112" />
</p>

<h1 align="center">DropSauce</h1>

<p align="center">
  A lightweight, modern comic and novel reader for Android with a beautiful Material 3 Expressive design. Supports Mihon and LNReader extensions.
</p>

<p align="center">
  <a href="https://github.com/byteoverride/DropSauce-desktop/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/byteoverride/DropSauce-desktop?style=for-the-badge&logo=github&label=latest"></a>
  <a href="https://github.com/byteoverride/DropSauce-desktop/releases"><img alt="Total downloads" src="https://img.shields.io/github/downloads/byteoverride/DropSauce-desktop/total?style=for-the-badge&logo=github&label=downloads"></a>
  <a href="LICENSE"><img alt="GPLv3 license" src="https://img.shields.io/github/license/byteoverride/DropSauce-desktop?style=for-the-badge"></a>
</p>

<p align="center">
  <a href="#desktop"><img alt="Linux x86_64" src="https://img.shields.io/badge/Linux-x86__64-FCC624?style=for-the-badge&logo=linux&logoColor=black"></a>
  <a href="#desktop"><img alt="Windows x64" src="https://img.shields.io/badge/Windows-x64-0078D4?style=for-the-badge&logo=windows&logoColor=white"></a>
  <a href="https://developer.android.com/"><img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B%20(upstream)-3DDC84?style=for-the-badge&logo=android&logoColor=white"></a>
  <a href="https://kotlinlang.org/"><img alt="Kotlin" src="https://img.shields.io/github/languages/top/byteoverride/DropSauce-desktop?style=for-the-badge&logo=kotlin&logoColor=white"></a>
  <a href="https://developer.android.com/compose"><img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white"></a>
  <a href="https://m3.material.io/"><img alt="Material 3 Expressive" src="https://img.shields.io/badge/Material%203-Expressive-6750A4?style=for-the-badge&logo=materialdesign&logoColor=white"></a>
</p>

<p align="center">
  <a href="https://github.com/byteoverride/DropSauce-desktop/releases/latest"><strong>Download for Linux or Windows</strong></a>
  |
  <a href="https://github.com/HuzaifaKhalid1311/DropSauce/releases/latest"><strong>Android APK (upstream)</strong></a>
  |
  <a href="https://github.com/byteoverride/DropSauce-desktop/issues"><strong>Issues</strong></a>
  |
  <a href="https://drop-sauce.app"><strong>Upstream website</strong></a>
</p>

---

> ## This is a fork
>
> This repository is a fork of **[DropSauce](https://github.com/HuzaifaKhalid1311/DropSauce)**
> by HuzaifaKhalid1311, which is itself derived from
> [Kotatsu](https://github.com/KotatsuApp/Kotatsu). Maintained here by
> [byteoverride](https://github.com/byteoverride).
>
> **What this fork adds:** a Compose Multiplatform **Linux desktop** application, built
> from the same source tree and sharing its database and logic. See
> [Desktop](#desktop) below.
>
> The Android app is upstream's work and is **not** released from this fork. For Android
> builds, the website, the Discord and the community, go to the
> [upstream project](https://github.com/HuzaifaKhalid1311/DropSauce).
>
> GPL-3.0, same as upstream. Changes are recorded in the git history and in `DECISIONS.md`.

---

## About

DropSauce is a free and open-source comic and novel reader, built to feel quick, clean and
comfortable to use. Upstream targets Android; this fork adds a native **Linux desktop**
build from the same source tree.

⭐Please give the repo a star if you like the project. It helps more people find it.🌟

## Screenshots

<p align="center">
  <img src="assets/main_favorites-preview.webp" alt="DropSauce favorites screen" width="31%" />
  <img src="assets/manga_details_page-preview.webp" alt="DropSauce details screen" width="31%" />
  <img src="assets/reading_ui-preview.webp" alt="DropSauce manga reading screen" width="31%" />
  <img src="assets/novel_reading_ui-preview.webp" alt="DropSauce novel reading screen" width="31%" />
  <img src="assets/extension_page-preview.webp" alt="DropSauce extensions screen" width="31%" />
  <img src="assets/settings-preview.webp" alt="DropSauce settings screen" width="31%" />
</p>

<p align="center">
  <sub>Favorites | Details | Manga/Webtoon Reader | Novel Reader | Extensions | Settings</sub>
</p>

## Highlights
- Full novel reading support alongside manga, including offline EPUB file importing.
- Multi-source extension engine supporting LNReader JS plugins and Tsundoku APK extensions.
- Lightweight Android-first experience with a modern, polished interface.
- Rich extension support with library, reading, history, bookmarks, tracking, stats, and settings tools.
- Google Drive sync, local backup/restore, and in-app updates to keep your setup moving with you.
- Supports Kotatsu and Mihon backup restoration alongside google drive sync
- Free and open-source under the GPLv3 license.

<details>
<summary><strong>Features</strong></summary>

- Comfortable manga, webtoon and novel reading experience with configurable reader behavior, haptics, and zoom gestures.
- EPUB novel importing for offline reading.
- Extensive extension ecosystem supporting native extensions, LNReader JS plugins, and Tsundoku APK extensions.
- Reverse tracking integration with a redesigned tracking menu.
- Favorites, history, bookmarks, tracking, stats, and categories to keep your library organized.
- Google Drive sync for library, history, bookmarks, tracking, stats, settings, and covers.
- Local backup and restore system for moving or protecting your setup.
- Material 3 Expressive details page for clear and quick overview
- New onboarding/welcome flow with sync and restore setup.
- Android widgets for continue reading, favorites, and reading stats.
- PDF import support, converting PDFs into readable CBZ chapters.
- App lock with biometric or device credential support.
- Downloads for offline reading when a source supports it.
- In-app updates, with APKs also published through GitHub Releases.

</details>

<details>
<summary><strong>Recent improvements</strong></summary>

- Full novel support with offline EPUB file importing.
- LNReader JS plugin and Tsundoku APK extension support.
- Interactive zoom gestures in novel reading mode.
- Added reverse tracking and refreshed tracking menu design.
- New popup animations across app flows.
- Redesigned list options, filter menu, and progress tracking.
- Minor UI improvements, edge-case crash fixes, and release build cleanups.

</details>

## Desktop

A native desktop build of the reader, written in Compose Multiplatform. It is a second
front end over the same shared logic, not a rewrite, and the Android app still builds
untouched.

Linux is what it is developed and tested on. Windows installers are built by CI and have
**not been run on a real machine yet**, so treat those as early.

<p align="center">
  <img src="assets/desktop_library-preview.webp" alt="DropSauce desktop library screen, showing categories, the chapter length filter and the cover grid" width="90%" />
</p>

<p align="center">
  <img src="assets/desktop_sources-preview.webp" alt="DropSauce desktop source catalogue" width="32%" />
  <img src="assets/desktop_updates-preview.webp" alt="DropSauce desktop updates screen for tracked titles" width="32%" />
  <img src="assets/desktop_appupdate-preview.webp" alt="DropSauce desktop app update screen" width="32%" />
</p>

<p align="center">
  <sub>Library | Sources | Updates | App update</sub>
</p>

### Filtering a shelf by length

The library filters by how many chapters a title has, which is the quick way to find the
ones on a "read it once it is long enough" shelf that have grown past your threshold.

<p align="center">
  <img src="assets/desktop_chapter-filter-preview.webp" alt="The chapter length filter, with buckets from 1 to 25 up to Over 500 and a 100 or more bucket, above a bar offering to load the counts that are still unknown" width="90%" />
</p>

Counts are read from the database, not fetched while you scroll. They get there three
ways:

- **From your reading history, at startup.** Free, local, no requests. If you have read a
  title, the app already knows how long it was.
- **From opening, reading or tracking a title**, as before.
- **From the Load counts button**, for everything else. A title you have never opened has
  no count anywhere on your machine, and the only way to learn one is to ask the source.
  That runs on a button rather than on its own, scoped to the category you have selected,
  four requests at a time, and you can stop it.

The bar says how many titles in view still have no count, so an empty result never leaves
you guessing whether nothing matched or nothing had been counted yet.

Buckets run 1 to 25, 26 to 100, 101 to 500 and Over 500, plus **100 or more**, which
deliberately overlaps the others because emptying a shelf at a threshold is a different
question from browsing one. Once filtered, the whole set can be moved into another
category in one step.

### Install

Both builds are on the
[latest release](https://github.com/byteoverride/DropSauce-desktop/releases/latest), and
both bundle their own Java runtime, so neither cares what Java you have.

**Linux**, `dropsauce_<version>_amd64.deb`, around 70 MB:

```bash
sudo dpkg -i dropsauce_*_amd64.deb
```

Installs to `/opt/dropsauce`; remove it with `sudo dpkg -r dropsauce`. Your library lives
in `~/.local/share/dropsauce/`.

**Windows**, `DropSauce-<version>.msi`, around 76 MB: double click it and choose a folder.
Uninstall through Apps and Features.

> Windows has not been run on real hardware yet. Your library goes to
> `%APPDATA%\DropSauce` and the cache to `%LOCALAPPDATA%\DropSauce`, as they should.
> An install from `desktop-v0.9.10` or earlier wrote to `.local\share` under your user
> folder instead; that library is still found and used, and nothing is moved.

Copy your library directory somewhere safe before trying a new build if you care about
what is in it.

### What it does

**Reading.** A library with categories, catalogue browsing and search across sources, a
webtoon reader, reading history and bookmarks.

**Offline.** Download chapters from a title, choosing everything, one scanlation branch,
everything after what you have read, or the next few. Pick where they are saved in
Settings. Import your own comics as CBZ or EPUB.

**Keeping up.** Track your library and see which titles have new chapters, with a count on
the sidebar and a mark on the cards themselves. Checks are manual: there is no background
updater.

**When a source breaks.** Around 380 of the catalogue's sources are flagged broken, and
titles get dropped from working ones too. Any title can be searched for on every other
source and moved across, carrying its categories, reading position, bookmarks and
tracking. The library says how many of its entries sit on a source that cannot be opened.

**Housekeeping.** Backup and restore that round-trips with the Android app, reading
statistics, duplicate and category cleanup, per-title reader preferences, and optional
AniList or MyAnimeList tracking.

Sources come from the `kotatsu-parsers` catalogue. Mihon extension APKs and LNReader JS
plugins are Android-only and are **not** available on desktop.

### Staying up to date

The app checks this repository's releases once when it starts and puts a count on the
**App update** item in the sidebar when there is a newer build. That screen shows what
changed and links to the package.

It does not download or install anything for you. A `.deb` needs root, so the install
stays yours to run. The only request it makes is to GitHub's public releases API, once
per launch.

### Build it yourself

```bash
./gradlew :desktop:run          # run it
./gradlew :desktop:packageDeb   # build the .deb
```

Needs JDK 21. `dpkg-deb` and `fakeroot` are required for `packageDeb` and are already
present on a normal Debian or Ubuntu install. The Android SDK is only needed for `:app`.

### Known limits

- Linux x86_64 and Windows x64. No macOS packaging, and the Windows build is untested on
  real hardware.
- Wayland runs through XWayland, since the app renders into an AWT window.
- The reader is webtoon mode only. Paged modes are not implemented.
- Very tall strips decode whole, as tiled decoding is not implemented yet.
- AVIF pages and CBR archives cannot be decoded.
- Tracking needs your own AniList or MyAnimeList OAuth application. No keys ship in the
  source, and a service with nothing configured says so instead of failing oddly.

Full detail, including where every file is written, is in
[README-DESKTOP.md](README-DESKTOP.md).

## Install on Android

This fork does not publish Android builds. Its releases are the Linux `.deb` only, so the
APK comes from the [upstream project](https://github.com/HuzaifaKhalid1311/DropSauce/releases/latest):
download the newest `DropSauce` APK and install it on a compatible device. Android may ask
you to allow installs from your browser or file manager, which is normal for an APK from
outside the Play Store.

For Linux, see [Desktop](#desktop) above.

## FAQ

### Does DropSauce include manga or novels?
> No. DropSauce does not include built-in content. Sources are provided through external libraries, JS plugins, or repositories added by users.

### Is DropSauce free?
> Yes. DropSauce is free and open source under the GPLv3 license.

### How do updates work?
> Both builds check for themselves. The Android app has in-app updates and publishes APKs on upstream's Releases page. The desktop build checks this repository's releases once when it starts and puts a count on the **App update** item in the sidebar; it never downloads or installs anything for you, because a `.deb` needs root.

### Can I contribute?
> Yes. Pull requests for patches, fixes, and new features are welcome.

## Project structure

```plaintext
app/src/main/
├── kotlin/org/koitharu/kotatsu/
│   ├── core/          # Shared database, network, parser, preferences, UI, and utility code
│   ├── main/          # App entry points, main activity, and app-level screens
│   ├── reader/        # Manga and novel reader UI and reading behavior
│   ├── details/       # Manga and novel details, chapters, metadata, and related services
│   ├── explore/       # Browse and discovery screens
│   ├── search/        # Search screens and search flows (with Manga/Novel toggle)
│   ├── favourites/    # Favorites and library-facing flows
│   ├── history/       # Reading history and progress
│   ├── download/      # Offline downloads and download queue
│   ├── extensions/    # Extension browsing, JS plugins, and APK extension management
│   ├── lnreader/      # LNReader JS plugin integration
│   ├── mihon/         # Mihon & Tsundoku APK extension integration
│   ├── backup/        # Local backup and restore
│   ├── sync/          # Sync data, domain, UI, and workers
│   ├── tracker/       # Tracking integrations and reverse tracking
│   ├── widget/        # Android home screen widgets
│   └── settings/      # Settings screens and preferences
└── res/
    ├── drawable*/     # Icons, backgrounds, and app artwork
    ├── layout*/       # XML screens, widgets, and reusable layouts
    ├── mipmap*/       # Launcher icons
    ├── values*/       # Strings, colors, themes, and translations
    └── xml/           # Android XML configuration
```

## Contribute

You can send a Pull Request for your patches, fixes, or new features here.

1. Fork the repository.
2. Create a focused branch for your change.
3. Build locally with `./gradlew :app:assembleDebug`.
4. Open a Pull Request with a short explanation of what changed.

Small fixes are welcome. Clear screenshots or short screen recordings are extra helpful for UI changes.

## Credits

DropSauce exists because of the work already done by the open-source Android manga reader community.

Built on top of [DropSauce](https://github.com/HuzaifaKhalid1311/DropSauce) by HuzaifaKhalid1311, which this repository forks.

Special thanks to the original [Kotatsu](https://github.com/KotatsuApp/Kotatsu) developers, [LNReader](https://github.com/LNReader/lnreader) developers, and the [Mihon](https://github.com/mihonapp/mihon) developers/community for the ideas, code, source ecosystem, and long-running maintenance work that helped shape projects like this.

## Certificate fingerprints

> These are the **upstream** project's Android signing certificates. This fork releases the Linux desktop `.deb` only and publishes no signed APK, so nothing downloaded from here will match them.

<div align="left">

SHA1:

```plaintext
9A:11:C9:FC:90:C6:E4:3F:7B:D4:2B:44:A3:37:D0:85:E6:E3:27:27
```

SHA256:

```plaintext
B8:4B:C7:C7:0A:5C:B0:BF:EA:9D:EA:D9:E0:5F:00:52:CB:A1:38:4C:AE:F5:97:71:3F:27:52:E4:3F:C9:63:18
```

</div>

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

<div align="left">

All programs from DropSauce™ project are free, open-source programs under the GPL license. You may copy, distribute, and modify the software as long as you keep track of changes/dates in the source files. Any modifications to the software, including code licensed under the GPL (via a compiler), must also be provided under the GPL license.

</div>

## Disclaimer

<div align="left">

The developer(s) of this application do not have any affiliation with the content providers available. If there is any content, it is provided by external libraries added or imported by users; the application itself does not include any built-in content.

</div>
