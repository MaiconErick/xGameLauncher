# xGameLauncher

A game launcher and library manager for Android handhelds, written from scratch in Kotlin and
Jetpack Compose. It replaces Daijishō on my AYN Thor, Odin 2 and RG353M: one library for
emulated and native games, controller-first navigation, playtime tracking and optional
cloud sync.

## Features

- **Unified library** — scans ROM folders, detects the platform and groups games by system.
  Native Android apps and Windows/PC entries live in the same library as emulated titles.
- **Artwork pipeline** — cover/metadata clients for SteamGridDB, ScreenScraper, RAWG and
  IGDB, with prefetching and a picker to correct wrong matches.
- **Daijishō import** — reads an existing Daijishō database and playtime records, so moving
  from another launcher loses nothing.
- **Playtime tracker** — a foreground service follows emulator and native sessions, survives
  reboots and app kills, and exports/imports the xPTT format.
- **Optional Dropbox sync** — refresh-token based, no OAuth UI, shared folder with my other
  trackers.
- **Dual-screen support** — on the Thor, the bottom display runs a separate activity with a
  widget gallery: system stats, clock, media, cluster tuning.
- **Clipboard hub** and ROM catalog/downloader tools.
- **Controller-first UI** with per-device layouts (16:9 handheld, 4:3 RG353M, dual screen).

## Tech

Kotlin · Jetpack Compose · Room · OkHttp · kotlinx.serialization · Coil · foreground
services · Accessibility/overlay APIs · `minSdk 26`, `targetSdk 35`

## Architecture

```
app/src/main/java/com/maiconerick/xgamelauncher/
├── art/        artwork + metadata providers (SteamGridDB, ScreenScraper, RAWG, IGDB)
├── daijisho/   Daijishō database reader and importer
├── data/       Room database, models, platform repository
├── launch/     emulator/app launching, Windows launcher, launch variables
├── rom/        ROM folder scanning and catalog
├── tracker/    playtime tracking service, overlay, boot receiver, export/import
├── net/        Dropbox sync and self-updater
├── bottom/     second-screen activities and widgets (AYN Thor)
├── tools/      clipboard hub, ROM downloader
└── ui/         Compose screens, tabs, dialogs, theme
```

## Build

Requirements: JDK 17, Android SDK 35.

```bash
# local.properties
sdk.dir=/path/to/Android/Sdk

./gradlew assembleDebug
```

Dropbox sync is optional. Add your own app key/secret/refresh token in
`net/Dropbox.kt` (placeholders are committed on purpose — real credentials never are).

## License

MIT — see [LICENSE](LICENSE).
