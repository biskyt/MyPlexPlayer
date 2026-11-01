# Store listings

Everything for MyPlexPlayer's Google Play and Amazon Appstore (Fire TV) listings: graphics, screenshots and the text to
paste in. The step-by-step guide for setting up both stores is the "Publish MyPlexPlayer" artifact (see RELEASING.md).

## Graphics

| File | Play Console field | Size |
|---|---|---|
| `graphics/icon-512.png` | App icon | 512 × 512, 32-bit PNG |
| `graphics/feature-graphic-1024x500.png` | Feature graphic | 1024 × 500, 24-bit PNG |
| `graphics/tv-banner-1280x720.png` | Android TV banner | 1280 × 720, 24-bit PNG |
| `screenshots/*.png` | Android TV screenshots (also phone and tablet screenshots, if asked) | 1920 × 1080, 24-bit PNG |

For the **Amazon Appstore** (Fire TV):

| File | Amazon Developer Console field | Size |
|---|---|---|
| `graphics/amazon-icon-114.png` | Small icon | 114 × 114 |
| `graphics/amazon-icon-512.png` | Large icon | 512 × 512 |
| `graphics/tv-banner-1280x720.png` | Fire TV app icon (1280 × 720) | 1280 × 720 |
| `graphics/fire-tv-background-1920x1080.png` | Fire TV background image (shown behind the app's details, so it has no text) | 1920 × 1080 |
| `screenshots/*.png` | Screenshots (3 to 10) | 1920 × 1080 |

Every file is a 24-bit PNG (no transparency), except Play's `icon-512.png`, which is 32-bit as Play asks.

The screenshots, in the order to upload them:
1. `1-home.png`: Home
2. `2-movie-details.png`: a film's page
3. `3-series-details.png`: a series' page
4. `4-movies.png`: Movies
5. `5-tv-guide.png`: the TV guide
6. `6-live-tv.png`: Live TV
7. `7-dvr.png`: the DVR tab

The app itself draws them (`app/src/test/.../ui/StoreAssetsTest.kt`), from a fake Plex server:
- The channels and programmes are invented, so no real broadcaster or programme appears. The channel logos are
  original, drawn by `demo-logos.py`.
- The films and the series are the Blender Foundation's open movies, with their posters and stills, licensed CC BY.
  If a store asks, or the listing has room, credit them: "Movie and series art © Blender Foundation and the films'
  artists (Big Buck Bunny, Elephants Dream, Sintel, Tears of Steel, Cosmos Laundromat, Charge, Caminandes), CC BY
  3.0/4.0, cropped. Credits: https://github.com/biskyt/MyPlexPlayer/blob/main/app/src/test/resources/demo-art/CREDITS.md".

To make them again after the app's look changes:

```
./gradlew :app:recordRoborazziDebug --tests '*StoreAssetsTest*' -PstoreAssets
```

## Privacy policy

`docs/privacy.html`, published with GitHub Pages at **https://biskyt.github.io/MyPlexPlayer/privacy.html**.

## Listing text

This file is the source for the listing. The publishing guide copies it for a browser agent: when the two differ,
this file is right. Keep the feature list in step with README.md's. The same text goes to both stores. Write it in English (United Kingdom) for Play, which is the listing's default
language, and in English (US) for Amazon.

**App name** (30 characters max): `MyPlexPlayer`

**Short description** (Play, 80 max; Amazon's short description too):

> A fast, free player for your own Plex server. Films, TV, Live TV and DVR.

**Full description** (4000 max):

> MyPlexPlayer is a fast, lightweight player for your own Plex Media Server, made for Android TV, Google TV and Fire TV.
> It also works by touch on phones and tablets.
>
> It is totally free, with no ads and no tracking. It has no analytics and no account of its own.
>
> • Fast and lightweight: starts quickly and stays smooth on older TV boxes
> • Never transcodes: plays your files as they are, so a low-powered server is enough
> • Sign in with a code at plex.tv/link, and every server on your account is ready to use
> • Home: carry on watching, recently added, and your favourites
> • Films and TV shows from your libraries, with the cast, scores and picture and sound of each title
> • Picks up where you left off, with what you watched saved on your Plex server
> • Skip intro and credits
> • 4K, HDR10, HDR10+ and Dolby Vision, with Dolby Atmos and surround sound sent to your soundbar
> • Subtitles and audio tracks, with forced subtitles in your language by default
> • Live TV and an 8-day TV guide from your Plex server's tuner, with your Plex favourite channels
> • DVR: record a programme or a whole series from the TV guide
> • Search across films, series, channels and programmes
> • Made for the remote: the Guide button, channel up and down, and number keys
> • In English, Spanish, Portuguese (Brazil) and French
>
> You need your own Plex Media Server with your own media. MyPlexPlayer has no content of its own. Live TV and DVR
> need a tuner set up in Plex, and Plex Pass.
>
> MyPlexPlayer is an independent app, and it is not made or endorsed by Plex, Inc. It is open source (GPL-3.0):
> https://github.com/biskyt/MyPlexPlayer

**Amazon product feature bullets** (if asked, up to 5):
- Fast and lightweight
- Free, with no ads and no tracking
- Never transcodes: plays your files as they are
- Live TV, TV guide and DVR from your Plex server
- Skip intro and credits

**Amazon keywords:** plex, plex media server, media player, live tv, dvr, tv guide, fire tv, 4k, hdr, dolby vision

**Release notes for 1.0.0** (Play's "What's new", Amazon's release notes): the 1.0.0 section of CHANGELOG.md.

**Category:** Play: *Video Players & Editors*. Amazon: *Movies & TV*.

**Contact email:** the developer account's email. **Website:** https://github.com/biskyt/MyPlexPlayer

## Answers for the consoles

- **Price:** free. **In-app purchases:** none.
- **Ads:** no ads.
- **Data safety (Play) and privacy (Amazon):** *No data collected* and *No data shared*.
  - The app talks only to plex.tv and to the user's own Plex servers, which the user chose. Nothing reaches the
    developer.
  - Play doesn't count data that stays on the device, or that goes straight to a service the user chose, as
    "collected".
  - Data is encrypted in transit: plex.tv and remote connections use HTTPS. A connection on the home network can use
    HTTP, as Plex allows.
  - Users can't ask the developer to delete data, because the developer has none. Uninstalling the app, or clearing
    its storage, removes the Plex sign-in and everything else from the device.
- **Content rating (IARC questionnaire):** a media player with no content of its own.
  - It plays only what the user's own server holds.
  - It has no user-to-user features, no chat, no shared location and no purchases.
  - The usual result is PEGI 3 / Everyone, sometimes with a note that the content depends on the user.
- **Target audience:** 18 and over. Not directed at children.
- **App access:** some or all of the app needs a sign-in. Reviewers need a Plex account with a shared server that has
  some media (see the guide's "Reviewer account" step). Instructions for them:

  > 1. Open MyPlexPlayer. It shows a code.
  > 2. On a phone or computer, go to plex.tv/link and sign in with the account below.
  > 3. Enter the code. The app opens Home with the server's films and series.
  >
  > Account: [REVIEWER EMAIL] · Password: [REVIEWER PASSWORD]

- **Government, financial, health, news or COVID-19 app:** no.
- **Android TV (Play):**
  - The app declares `LEANBACK_LAUNCHER` and a TV banner, and doesn't need a touchscreen.
  - Opt in to Android TV under *Release › Setup › Advanced settings › Form factors*.
  - Add the TV screenshots under the store listing's *Android TV* section. Play reviews TV apps for TV quality before
    it shows them on TVs.
- **Touch (Amazon asks):** yes, the app supports touch. Every screen works by touch on phones and tablets, and with the
  remote's D-pad on TV. A touchscreen is not required.
- **Fire TV (Amazon):** under *Device support*, choose the Fire TV devices, and phones and tablets if you want them.
  The app has no Google Play services, so it runs on Fire TV as it is.
