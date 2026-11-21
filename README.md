<a href="https://github.com/biskyt/MyPlexPlayer/releases">
  <img width="200" alt="get it on github" src="https://github.com/user-attachments/assets/53812976-a0f8-4e6d-bd46-3bc47a19ebdb" />
</a>

Download the APK from [Releases](https://github.com/biskyt/MyPlexPlayer/releases) and sideload it.

<a href="https://play.google.com/store/apps/details?id=com.biskyt.myplexplayer" />
  <img width="200" alt="get it on google play" src="https://github.com/user-attachments/assets/5b4c45ca-ab6d-4a9b-8f7e-cee1de27d438" />
</a>

MyTVPlayer is currently in closed testing on Google Play. You need to join the google group to get access to it in the play store:

1. [Join the group](https://groups.google.com/g/biskyt-apps-beta) → 2. [Accept the test](https://play.google.com/apps/testing/com.biskyt.myplexplayer) → 3. [Install from Play](https://play.google.com/store/apps/details?id=com.biskyt.myplexplayer)


[![Ko-fi](https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/biskyt) [![GitHub Sponsors](https://img.shields.io/badge/Sponsor-GitHub-ea4aaa?style=for-the-badge&logo=github-sponsors&logoColor=white)](https://github.com/sponsors/biskyt)

_This work is free, with no ads, no tracking and no data collection. If you find it useful, you can support its development by buying me a coffee with the links above._


# MyPlexPlayer

A fast, lightweight and free player for your own **Plex Media Server**, made for Android TV, Google TV and Fire TV
(Android 9 and later). It also works by touch on phones and tablets.

![Home](store/screenshots/1-home.png)

MyPlexPlayer is an independent app. It is not made or endorsed by Plex, Inc. You need your own Plex Media Server.

## Why MyPlexPlayer
- **Fast and lightweight:** a small app that starts quickly and stays smooth on older TV boxes.
- **Free, with no ads and no tracking:** no analytics, no advertising and no account of its own. Open source (GPL-3.0).
- **Never transcodes:** it plays your files as they are, so a low-powered server is enough. When the TV can't open a
  file's container, the server only repackages it, with the picture and sound copied.
- **Made for the remote:** every screen works with the D-pad, the Guide button, channel up and down, and number keys.

## Features
- **Sign in with a code:** enter the code shown on the TV at plex.tv/link. Every server on your account is ready to use.
- **Home:** Continue watching, recently added series and films, your favourites, and your favourite channels with
  what's on now.
- **Films and TV shows** from your Plex libraries, with filters, sorting, and pages that show the cast, scores and the
  picture and sound of each title.
- **Picks up where you left off:** what you watch, and how far you got, is saved on your Plex server, the same as in
  your other Plex apps.
- **Skip intro and credits**, by itself after a short countdown or when you press the button.
- **4K, HDR10, HDR10+ and Dolby Vision**, with Dolby Atmos and other surround sound sent to your soundbar or receiver.
- **Subtitles and audio tracks:** forced subtitles in your language by default, and every track to choose from.
- **Live TV and the TV guide** from your Plex server's tuner, 8 days ahead. Your favourite channels are your Plex
  favourites.
- **DVR:** record a programme or a whole series from the TV guide, watch a recording from its start while it records,
  and see what's recording, what's recently recorded, what's coming up and your series recordings.
- **Search** across films, series, channels and programmes.
- **VPN check:** a badge in the top bar shows whether Android reports a VPN, and can also show your external IP address
  and its country (from ipinfo.io, off by default).
- **The TV's home screen** (off by default): Continue watching in the home screen's Play Next row, with posters, and
  Home's other rows (recently added and favourite series and films, and your favourite channels) as rows of their own. Back from what a card opens returns to the TV's home screen, and
  Continue watching there is updated as soon as playback stops. Android TV's own launcher and others that read the TV
  provider show them. Google TV's home screen shows no app rows.
- **The TV guide for other apps:** a launcher can open the TV guide directly, as the remote's Guide button does on Home.
  It opens the component `com.biskyt.myplexplayer/.TvGuide`, or the action `com.biskyt.myplexplayer.action.TV_GUIDE`.
  If the server has no Live TV, Home opens.
- **Rows refreshed for a launcher:** while the TV's home screen rows are on, a launcher (such as MyTVLauncher) can
  send the broadcast `com.biskyt.myplexplayer.action.REFRESH_HOME_ROWS`. The app then brings in what was played and
  added on the Plex server, and writes the rows again, at most once in 3 minutes. It does nothing on a schedule.
- **Languages:** English, Spanish, Portuguese (Brazil) and French.

| | |
|---|---|
| ![Film page](store/screenshots/2-movie-details.png) | ![Series page](store/screenshots/3-series-details.png) |
| ![Films](store/screenshots/4-movies.png) | ![TV guide](store/screenshots/5-tv-guide.png) |

## Install
- **Google Play:** search for MyPlexPlayer on your Android TV or Google TV.
- **Amazon Appstore:** search for MyPlexPlayer on your Fire TV.
- **Sideload:** download `MyPlexPlayer-<version>.apk` from the latest [release](../../releases), copy it to the TV
  (for example with the *Downloader* or *Send Files to TV* app) and open it with a file manager.

A store install and a sideloaded APK are signed with different keys, so one can't update the other. Use one of them on
each device.

## Build

Requires JDK 17 or later and the Android SDK (platform 36).

```sh
./gradlew assembleRelease                 # app/build/outputs/apk/release/app-release.apk
./gradlew :core:test testDebugUnitTest    # unit, Robolectric and UI tests
./gradlew ktlintCheck detekt lintDebug    # style and static checks
./gradlew :app:recordRoborazziDebug --tests '*StoreAssetsTest*' -PstoreAssets   # store/ (README and store listings)
```

Project layout: `core/` is plain Kotlin (the Plex client, parsers and the player's key logic) with fast JVM tests.
`app/` is the Android app (Jetpack Compose for TV, Room, Media3 ExoPlayer). Releases and signing:
[RELEASING.md](RELEASING.md). Store listings: [store/README.md](store/README.md).

## Support

MyPlexPlayer is free, with no ads and no tracking. If you find it useful, you can support its development:

- [Ko-fi](https://ko-fi.com/biskyt)
- [GitHub Sponsors](https://github.com/sponsors/biskyt)

## Licence

GPL-3.0 (see `LICENSE`). Nunito font: SIL Open Font License. Playback uses AndroidX Media3 and FFmpeg (LGPL). The demo
art in the screenshots is from the Blender Foundation's open movies (CC BY), see
[the credits](app/src/test/resources/demo-art/CREDITS.md).
