[![Ko-fi](https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/biskyt) [![GitHub Sponsors](https://img.shields.io/badge/Sponsor-GitHub-ea4aaa?style=for-the-badge&logo=github-sponsors&logoColor=white)](https://github.com/sponsors/biskyt)

_This work is free, with no ads and no tracking and no data collection. If you find it useful, you can support its development by buying me a coffee with the links above._


# MyPlexPlayer

A fast, free and open-source player for your own **Plex Media Server**, for Android TV, Google TV and Fire TV (Android
9 and later), that also works by touch on phones and tablets in landscape. It is built from
[MyTVPlayer](https://github.com/biskyt/MyTVPlayer), and shares its look, its remote-first controls and its player.

**Status: early development.** The app currently still carries MyTVPlayer's playlist screens; Plex sign-in, libraries,
Live TV and DVR arrive step by step (see [ROADMAP.md](ROADMAP.md)).

MyPlexPlayer is an independent app and is not made or endorsed by Plex, Inc.

What's planned:
- **Sign in with Plex** (a code on the TV, entered at plex.tv/link), then pick your server. Several servers on one
  account are supported.
- **Home**: Continue watching, recently added series and movies, your favourite series and movies, and your favourite
  Live TV channels with what's on now.
- **Movies and series** from your Plex libraries, resuming where you left off, with watched state kept in Plex.
- **Live TV and the TV guide** from your Plex server's tuner and guide.
- **DVR**: your recordings, what's scheduled, and Record from the TV guide.

## Install on your TV (sideload)

1. Download `MyPlexPlayer-<version>.apk` from the latest [release](../../releases).
2. Copy the APK to the TV (e.g. with the *Downloader* or *Send Files to TV* app, or a USB stick) and open it with a
   file manager; allow installs from that app when asked.

Development builds are signed with a shared public test key (`app/debug.keystore`) so they install over each other.
Releases are signed with a private key held in the repository's secrets; see [RELEASING.md](RELEASING.md).

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew :core:test testDebugUnitTest   # unit, Robolectric and UI tests
./gradlew ktlintCheck detekt lintDebug   # style and static checks
./gradlew recordRoborazziDebug     # refresh the test screenshots in app/screenshots
./gradlew :app:recordRoborazziDebug --tests '*StoreAssetsTest*' -PstoreAssets   # store/ (README and store listings)
```

Project layout: `core/` is plain Kotlin (source clients and parsers, player key logic) with fast JVM tests; `app/` is the Android TV app (Jetpack Compose for TV, Room, Media3 ExoPlayer).

## Support

MyPlexPlayer is free, with no ads and no tracking. If you find it useful, you can support its development:

- [Ko-fi](https://ko-fi.com/biskyt)
- [GitHub Sponsors](https://github.com/sponsors/biskyt)

## Licence

GPL-3.0 (see `LICENSE`). Nunito font: SIL Open Font License. Playback uses AndroidX Media3 and FFmpeg (LGPL).
