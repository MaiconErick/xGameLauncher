# xGameLauncher

Compose game launcher (Daijishō replacement). Runs on TWO very different handhelds, and
every change must work on both:

| Device | Screen | RAM | Displays | OS |
|---|---|---|---|---|
| Ayn Thor | 16:9 large top + bottom screen | lots | 2 (bottom = display 4, looked up at runtime) | Android 13+ |
| Anbernic RG353M | 3.5" 640x480 **4:3** (density 160 → 640x480dp) | **2 GB** | 1 | **Android 12**, spoofed as a Pixel 3a (intentional; identify it by `Build.HARDWARE`/`BOARD` starting with "rk", never MODEL) |

xPlayTimeTracker (xPTT) is **no longer part of the launch path** (removed 2026-09-28). All
launches go through `tracker/InternalLauncher`. xPTT survives only as data: a one-time
session import (`XpttImporter`, `PlaytimeReader`) and the xPTT-compatible `PlayTime` table
format of the Dropbox backup, which the xPlayTime viewer reads. Don't reintroduce an xPTT
dependency.

Both are rooted (`su --mount-master`, see `util/RootShell.kt`).

## Build

Build via xBuildServer only, never `./gradlew` on the Pi. Handhelds get the **release**
build (non-debuggable, signed with the debug key, so it installs over debug builds). A
debuggable Compose build is noticeably sluggish on the RG353M.
```
python3 /home/pi/MaiconErick/Projects/Android/remote-build.py xGameLauncher --task assembleRelease
adb -s <device> install -r /mnt/share/DEV/Android/Projects/xGameLauncher/app/build/outputs/apk/release/app-release.apk
adb -s <device> shell cmd package compile -m speed -f com.maiconerick.xgamelauncher
```
Careful: remote-build.py reports and auto-installs `apk/debug/app-debug.apk` even for
assembleRelease, so install the release APK by hand as shown.

Room uses `fallbackToDestructiveMigration()`. **Never bump the DB version or change an
entity's columns casually**, because that wipes the user's library, overrides and sessions.
Put new per-platform/per-player data in the JSON columns (`intentsJson`, `LaunchIntent`
fields with defaults) or in DataStore prefs.

## RG353M compatibility checklist (apply to EVERY new feature)

`ui/DeviceProfile.kt` → `LocalDeviceProfile.current` gives `compact`, `lowPower` and
`multiDisplay`. The Settings → Device override is "auto" / "full" / "compact".

1. **Layout fits the 353M (4:3, ~640x480dp) and stays usable down to ~420x300dp when `compact`.** Never add fixed widths above ~300dp without
   a compact branch (`profile.dp(full, compact)` / `profile.sp(...)`). Side panels,
   sidebars over 130dp and 2-column forms need a stacked/narrow variant. Dialogs use
   `fillMaxWidth(0.94f)` plus `widthIn(max = …)`, never a bare `.width(520.dp)`.
2. **Long lists scroll.** Use LazyColumn/LazyGrid or `verticalScroll`; the 353M has
   ~300dp of height.
3. **Decoration is gated on `lowPower`.** No `rememberInfiniteTransition`, frame loops,
   `Modifier.blur`, Crossfade or full-screen image backdrops unless `!profile.lowPower`.
   (`Modifier.blur` is also a no-op below Android 12.)
4. **Images:** Coil `AsyncImage` sized by layout; no `crossfade(true)` without the
   lowPower check; never decode originals for thumbnails.
5. **Displays:** never hard-code display ids. Use `DisplayTargets.displayIdFor(ctx, pref)`,
   which returns null on single-screen devices. Hide top/bottom-screen UI when
   `!multiDisplay`.
6. **API level:** minSdk 26; the 353M runs Android 12 (API 31/32). Guard API 31+ calls
   (RenderEffect, PackageInfoFlags, etc.) with `Build.VERSION.SDK_INT` checks.
7. **No Thor-only assumptions:** BannerHub, Winlator and Daijishō may be missing, so
   check with `AppIntrospect.isInstalled` and degrade quietly. Device names come from `prefs.deviceName` (default
   `DeviceProfile.defaultDeviceName()`), never a literal "THOR".
8. **Root is expensive:** every `su` call shows a Magisk prompt or toast. Never call
   `RootShell` from refresh/recomposition paths. Cache results (`RootShell.haveRoot()` is
   per-process; `DaijishoReader.readPlatformsCached`), skip when the target file or app
   doesn't exist, and never run `su` on the main thread.
9. **Static composition locals must get stable values** (`remember`ed lambdas); a new
   lambda per recomposition recomposes the whole tree.
10. **Input:** the RG353M pad reports ZL/ZR as analog axes (BRAKE/GAS). MainActivity
   synthesizes L2/R2 keys from them and routes unconsumed shoulder keys to AppRoot, so
   don't handle shoulders only through focused-node key modifiers. B while the IME is up
   closes the keyboard; editors guard unsaved changes via BackHandler.
11. **Platform folders:** a platform must never point at the ROMS/storage root
   (`RomFolders.isRootLike`). The editor suggests the folder from the name via Daijishō
   short names and refuses to save a root, the scanner refuses roots and skips junk
   (`RomFolders.isJunk`), and refresh repairs old root-pointing platforms.
12. **Cold starts are common** (2 GB RAM kills the launcher while emulators run), so keep
   startup work off the main thread and keep the boot splash short in lowPower.
13. **Gamepad-first:** every control needs `.focusable()` + `.onGamepadActivate(...)`.
   Test D-pad reachability in the compact layout.

## UI conventions (Daijishō-style, set 2026-09-28)

- **Focus:** every focusable control uses `Modifier.focusRing(focused, shape)`
  (`ui/components/FocusKit.kt`): an animated accent "snake" border on the focused element
  only (static outline in lowPower). "Selected" (current tab/section/swatch) uses accent text, an accent bar or a
  check mark, never the focus border. Tiles get the ring through `neonFocusStroke`.
- **Menus are lists, not side rails:** use `ui/components/ListKit.kt` (`PageHeader`,
  `ListRow` icon·title·subtitle, `ListSectionLabel`, `ListDivider`). A section opens as its
  own page. In Settings that's a new `Route.Settings(id)`; in editors it's a nullable
  `activeTab` with a BackHandler that returns to the list first. When a page opens, focus its
  first control (`focusRequester` + `focusGroup()` and `requestFocus()`), and when returning,
  re-focus the row you came from.
- **Bottom HintBar** (`ui/components/HintBar.kt`) on every route: clock · date · breadcrumb
  plus button hints. New routes must add a case to `breadcrumb()` / `hintsFor()` in
  MainActivity.
- **Text inputs** use `ui/components/GamepadTextField.kt`, never a raw BasicTextField: the D-pad
  can pass over them without the keyboard popping up, and A or a tap starts editing. The Search
  screen is the only exception.
- **Type size:** AppRoot scales `fontScale` by 0.9 (0.95 compact), so pick sp values as usual.
- Colour inputs use `ui/dialogs/ColorPickerDialog.kt` (HSV bars driven by the D-pad).

## Bottom-screen launcher & tools

- `bottom/BottomHomeActivity` ("xGL Bottom", SECONDARY_HOME + HOME, own task, wallpaper
  theme): widget pages (`bottom/Widgets.kt` built-ins + hosted AppWidgets, layout JSON in
  prefs `tool_bottom_layout`) and a swipe-up / Y drawer that embeds the full `AppRoot`
  (`embedded = true`, Apps or Platforms). Both activities extend `LauncherActivityBase`
  (controller plumbing). Stats that need root go through `util/RootSession` (one su shell).
- Tools tab (`tools/`): romgi catalog + downloader (HTTP + libtorrent4j single-file
  torrents), folder → Dropbox watcher, clipboard via the Pi hub (:8768). `ToolsService` runs
  only while a tool needs it.
- Background maker (`ui/screens/BackgroundMaker.kt`) is an overlay inside the platform
  editor, so the editor's unsaved state survives; one renderer for preview and PNG export.
- L/R/ZL/ZR are "locked" inside editors (`shouldersLocked()` in AppRoot).

## Players (launch)

- `data/Models.kt` `LaunchIntent` = one Daijishō-style player: component, action, data,
  mime, categories, typed extras, `--activity-*` flags, `-f`, `killBefore`.
- `launch/AmArgs.kt` parses/formats Daijishō `amStartArguments` and builds the Intent
  (tokens are split before template fill; `LIBRETRO short_name` expands to the core .so;
  content:// uris in data/extras get re-granted via ClipData).
- `launch/LaunchVars.kt`: `{file.path} {file.uri} {file.name} {file.basename} {file.dir}
  {file.mime} {tags.KEY}` (.dpt files) plus legacy `{path} {uri} {id} {name} {platform}`.
  `{file.uri}` becomes a SAF tree-document uri when Settings → Device → ROM folder access
  covers the file, else file://.
- Routing (MainActivity.launchFlow): always `tracker/InternalLauncher` (session row →
  optional root force-stop → TrackerService → intent). Non-exported activities fall back
  to `su -c am start …`. A platform with no players prompts to open its Players tab
  (`Route.PlatformEdit(id, initialTab = "players")`); platforms discovered from
  Daijishō's local DB start with no players.
- Daijishō player DB snapshot: `assets/daijisho_players.json` (regenerate from
  github.com/TapiocaFox/Daijishou/platforms, skipping index.json); in-app refresh reads
  `platforms/index.json`.

## Storage paths
- ROMS root: `/storage/emulated/0/MaiconErick/ROMS` (`RomFolders.ROMS_ROOT`); the old
  `/storage/emulated/0/ROMS` roots are legacy — platform folders under them are rewritten on load
  (`RomFolders.migrate`, `PlatformRepo.updatePaths`).
- Windows games: `/storage/emulated/0/MaiconErick/Windows` (`RomFolders.WINDOWS_ROOT`, scanned 4 deep;
  folder names like BannerHub/ cronyx/ winlator/ pick the player). Old Download/bannerhub +
  winlator/Frontend defaults are migrated.
- Never hardcode these paths elsewhere; reference the constants.

## romgi torrents
The catalog's `torrents` table has magnet/trackers only (no .torrent blob). `RomDownloader` fetches
metadata with `SessionManager.fetchMagnet` (180 s) and caches it in `filesDir/romgi/torrents/<hash>.torrent`.

## Bottom home layout
Widgets are placed freely (`WidgetSpec.x/y/w/heightDp`, dp, overlap allowed; list order = z-order).
Unplaced specs (old layouts, new widgets) get positions from `autoPlace`. Gesture handlers in
`HomePages` must read the current layout via `rememberUpdatedState` (stale captures made drags jump).

## Releases / in-app updater
Every build gets `versionCode` = minutes since 2025 and `versionName` 0.2.yyMMdd.HHmm (build.gradle.kts).
After every release build that is installed on devices, publish it:
`python3 /home/pi/MaiconErick/Projects/Android/publish-xgl.py "notes"`. That uploads to Dropbox
`/xGameLauncher/xGameLauncher.apk` + `releases/` + `version.json`. `net/Updater.kt` checks it on launch
(Settings → Updates) and installs with root `pm install -r` (detached).
Install on all three devices: Thor 192.168.1.126:5555, Odin 2 192.168.1.122:5555, RG353M 192.168.1.20:5555.

## Game names
ROM names come from `NameCleaner.sanitize(fileName)`, which strips stacked extensions and region/rev/language tags,
keeps disc numbers, and turns "Title, The" into "The Title". Windows names keep the legacy `prettify` because
their session history uses those names. When new ROM games appear, Route.NameReview shows them for editing
before the art picker opens.
